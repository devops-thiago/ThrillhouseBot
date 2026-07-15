/*
 * Copyright 2026 Thiago Gonzaga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.thiagogonzaga.thrillhousebot.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Analyzes follow-up reviews by comparing new findings against prior reviews. */
@ApplicationScoped
public class FollowUpAnalyzer {

  private static final String STATUS_UNRESOLVED = "unresolved";
  private static final String STATUS_RESOLVED = "resolved";
  private static final String STATUS_JUSTIFIED = "justified";

  /**
   * The only statuses the prompt contract defines for {@code previous_findings_status} ("resolved"
   * | "unresolved" | "justified"). A value outside this set does not count as the model accounting
   * for a finding — see {@link #isRecognizedStatus}.
   */
  private static final Set<String> RECOGNIZED_STATUSES =
      Set.of(STATUS_RESOLVED, STATUS_JUSTIFIED, STATUS_UNRESOLVED);

  /** Shared blank response for missing/unparseable persisted rounds (empty, never-null lists). */
  private static final ReviewResponse EMPTY_RESPONSE =
      new ReviewResponse(List.of(), List.of(), null);

  private final ObjectMapper mapper;

  @Inject
  public FollowUpAnalyzer(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Builds a "previous findings" context string for the AI prompt. Prefers the structured findings
   * persisted from the previous review session; falls back to the last bot review body when no
   * structured response is available (e.g. sessions persisted before AI responses were stored).
   */
  public String buildPreviousFindingsContext(
      String previousAiResponseJson,
      List<GitHubReviewClient.ReviewResponse> priorReviews,
      BotIdentity botIdentity) {
    return buildPreviousFindingsContext(
        previousAiResponseJson, priorReviews, List.of(), botIdentity);
  }

  /**
   * Variant that also renders the reply threads under each finding's inline comment, so the model
   * can weigh maintainer responses (fix pushed, intentional, disputed) when assigning statuses.
   */
  public String buildPreviousFindingsContext(
      String previousAiResponseJson,
      List<GitHubReviewClient.ReviewResponse> priorReviews,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    return buildPreviousFindingsContext(
        previousAiResponseJson, priorReviews, inlineComments, List.of(), botIdentity);
  }

  /**
   * Variant that additionally renders findings from review rounds older than the previous one.
   * Sessions only carry the immediately previous response, so a finding answered two rounds ago
   * would otherwise fall out of context. Older answered findings are listed unnumbered, outside
   * previous_findings_status.
   */
  public String buildPreviousFindingsContext(
      String previousAiResponseJson,
      List<GitHubReviewClient.ReviewResponse> priorReviews,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<String> olderAiResponseJsons,
      BotIdentity botIdentity) {
    return buildPreviousFindingsContext(
        parsePreviousFindings(previousAiResponseJson),
        priorReviews,
        inlineComments,
        parsePreviousResponses(olderAiResponseJsons),
        botIdentity);
  }

  /**
   * Same as the JSON overload, but consumes findings already deserialized for this review so the
   * prior-response JSON is not re-parsed.
   */
  public String buildPreviousFindingsContext(
      List<ReviewResponse.Finding> previousFindings,
      List<GitHubReviewClient.ReviewResponse> priorReviews,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<ReviewResponse> olderAiResponses,
      BotIdentity botIdentity) {
    var structured = formatStructuredFindings(previousFindings, inlineComments, botIdentity);
    var answered = formatAnsweredEarlier(olderAiResponses, inlineComments, botIdentity);
    if (!structured.isEmpty()) {
      return structured + answered;
    }
    var fallback = buildPreviousFindingsContext(priorReviews, botIdentity);
    return fallback + answered;
  }

  /**
   * Unnumbered list of findings from rounds before the previous one whose threads carry a human
   * reply. These were answered once; the model must not raise them again or include them in
   * previous_findings_status.
   */
  private String formatAnsweredEarlier(
      List<ReviewResponse> olderAiResponses,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    if (olderAiResponses == null || olderAiResponses.isEmpty()) {
      return "";
    }
    var sb = new StringBuilder();
    var seen = new HashSet<String>();
    for (var response : olderAiResponses) {
      for (var finding : response.findings()) {
        appendAnsweredEntry(sb, seen, finding, inlineComments, botIdentity);
      }
    }
    return sb.toString();
  }

  /**
   * Matching is by file and title only: the hidden per-round comment markers reuse the same indices
   * every round, so an index-based match could bind an older finding to a newer round's unrelated
   * thread. Findings without both fields cannot be matched.
   */
  private static void appendAnsweredEntry(
      StringBuilder sb,
      Set<String> seen,
      ReviewResponse.Finding finding,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    if (finding.file() == null || finding.title() == null) {
      return;
    }
    if (!seen.add(finding.file() + "#" + finding.title())) {
      return;
    }
    Long rootId = answeredRootComment(finding, inlineComments, botIdentity);
    if (rootId == null) {
      return;
    }
    if (sb.isEmpty()) {
      sb.append("\nAnswered in earlier rounds — do NOT raise these again and do NOT")
          .append(" include them in previous_findings_status:\n");
    }
    sb.append("- ")
        .append(finding.file())
        .append(":")
        .append(finding.line())
        .append(" — ")
        .append(finding.title())
        .append("\n");
    appendReplies(sb, rootId, inlineComments);
  }

  /**
   * Numbered findings parsed from the previous session's persisted AI response. The numbers are the
   * ids the model references in previous_findings_status.
   */
  private String formatStructuredFindings(
      List<ReviewResponse.Finding> previous,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    if (previous == null || previous.isEmpty()) {
      return "";
    }
    // The prompt template provides the lead-in sentence; emit only the numbered findings
    var sb = new StringBuilder();
    var id = 1;
    for (var finding : previous) {
      sb.append(id)
          .append(". [")
          .append(finding.risk() == null ? "UNKNOWN" : finding.risk().toUpperCase(Locale.ROOT))
          .append("] ")
          .append(finding.file())
          .append(":")
          .append(finding.line())
          .append(" — ")
          .append(finding.title())
          .append("\n");
      if (finding.description() != null && !finding.description().isBlank()) {
        sb.append("   ").append(finding.description()).append("\n");
      }
      appendThreadReplies(sb, finding, id, inlineComments, botIdentity);
      id++;
    }
    return sb.toString();
  }

  private static void appendThreadReplies(
      StringBuilder sb,
      ReviewResponse.Finding finding,
      int findingId,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    Long rootId = rootCommentId(finding, findingId, inlineComments, botIdentity);
    if (rootId == null) {
      return;
    }
    appendReplies(sb, rootId, inlineComments);
  }

  private static void appendReplies(
      StringBuilder sb, Long rootId, List<GitHubReviewClient.PullRequestComment> inlineComments) {
    List<GitHubReviewClient.PullRequestComment> replies =
        inlineComments.stream().filter(c -> rootId.equals(c.inReplyToId())).toList();
    if (replies.isEmpty()) {
      return;
    }
    sb.append("   Thread replies:\n");
    for (var reply : replies) {
      String author = reply.user() != null ? reply.user().login() : "unknown";
      sb.append("   - @").append(author).append(": ").append(reply.body()).append("\n");
    }
  }

  /**
   * The bot's root inline comment for a finding. Preferred match: the hidden finding marker the bot
   * embeds in every comment, which is unambiguous even when findings share a title. Fallback for
   * comments posted before the marker existed: same file and the title in the body.
   */
  private static Long rootCommentId(
      ReviewResponse.Finding finding,
      int findingId,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    Long markerRoot = markerRootComment(finding, findingId, false, inlineComments, botIdentity);
    if (markerRoot != null) {
      return markerRoot;
    }
    return rootCommentByTitle(finding, inlineComments, botIdentity);
  }

  /**
   * The newest bot root comment on the finding's file carrying its {@code findingId} marker, or
   * {@code null} when none does. Markers reuse the same indices every review round, so a PR with
   * several rounds carries several comments with this exact marker; restricting to the finding's
   * file and taking the newest match binds the latest round's findings to their own threads, not a
   * previous round's thread that happens to share the index. Title-independent, so it locates a
   * null-title finding's thread that {@link #rootCommentsByTitle} cannot.
   *
   * <p>The newest-match heuristic alone is not enough when the finding had <em>no</em> thread of
   * its own that round (its line was outside the diff, so it was summary-only): the newest {@code
   * finding=N} comment on the file can then be an <em>earlier, unrelated</em> round's finding that
   * happens to reuse index {@code N}. {@code requireOwnContent} closes that gap for the
   * safety-critical clearing decision — when set, a match must also carry the finding's own content
   * ({@link #bodyCarriesOwnContent}: its title in the comment header, or its description when it
   * has no title), so a thread-less finding cannot bind to a different finding's same-index thread.
   * A finding with neither title nor description matches nothing under {@code requireOwnContent},
   * so the caller holds rather than risks an over-clear.
   */
  private static Long markerRootComment(
      ReviewResponse.Finding finding,
      int findingId,
      boolean requireOwnContent,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    String marker = SuggestionFormatter.findingMarker(findingId);
    var markerMatches =
        botRootComments(inlineComments, botIdentity)
            .filter(c -> c.body() != null && c.body().contains(marker))
            .filter(c -> finding.file() == null || FilePaths.same(finding.file(), c.path()))
            .filter(c -> !requireOwnContent || bodyCarriesOwnContent(finding, c.body()))
            .map(GitHubReviewClient.PullRequestComment::id)
            .toList();
    return markerMatches.isEmpty() ? null : markerMatches.get(markerMatches.size() - 1);
  }

  /**
   * Whether {@code body} is this finding's own comment, judged by content the bot embeds in every
   * comment ({@link SuggestionFormatter#formatReviewComment}). It distinguishes the finding's own
   * thread from a <em>different</em> finding that reused the same marker index in an earlier round.
   *
   * <p>A titled finding is matched on the header framing {@code " — {title}**"}, not a bare title
   * substring, so a short title does not match an unrelated comment that merely mentions the word
   * and a title does not match a longer title that contains it as a prefix. A null-title finding
   * has no usable header title — the bot renders the literal {@code "null"}, which is too generic —
   * so it falls back to its description, which the bot prints verbatim in the body. A finding with
   * neither matches nothing, so the caller holds rather than risk an over-clear.
   */
  private static boolean bodyCarriesOwnContent(ReviewResponse.Finding finding, String body) {
    String title = finding.title();
    if (title != null && !title.isBlank()) {
      return body.contains(" — " + title + "**");
    }
    String description = finding.description();
    return description != null && !description.isBlank() && body.contains(description);
  }

  /**
   * Marker-free match for the latest round's findings: the newest bot root comment on the finding's
   * file whose body carries its title. The same title can exist once per review round, and the
   * newest comment is the one belonging to the round being analyzed.
   */
  private static Long rootCommentByTitle(
      ReviewResponse.Finding finding,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    List<Long> matches = rootCommentsByTitle(finding, inlineComments, botIdentity);
    return matches.isEmpty() ? null : matches.get(matches.size() - 1);
  }

  /**
   * The thread where a maintainer actually answered this finding, whichever round it was raised in.
   * Same-title threads exist once per round and not all of them carry the reply, so every match is
   * checked.
   */
  private static Long answeredRootComment(
      ReviewResponse.Finding finding,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    for (Long rootId : rootCommentsByTitle(finding, inlineComments, botIdentity)) {
      if (hasHumanReply(rootId, inlineComments, botIdentity)) {
        return rootId;
      }
    }
    return null;
  }

  /**
   * Maintainer-reply check for a finding whose 1-based prompt {@code findingId} is known — the
   * backstop's newest-prior-round case. The finding's own {@code thrillhousebot:finding=N} marked
   * thread is authoritative: when one exists, only a reply on <em>it</em> clears the hold. The
   * marker is title-independent, so a {@code null}-title finding's thread is seen — the title-only
   * {@link #answeredRootComment(ReviewResponse.Finding, List, BotIdentity)} consults {@link
   * #rootCommentsByTitle}, which returns {@code List.of()} for a null title and can never find the
   * reply, leaving the backstop to hold the finding every round with no human escape. It also keeps
   * a reply on a same-title sibling's thread (a different index) from clearing this finding.
   *
   * <p>Because clearing the hold is the dangerous direction (over-clearing re-introduces the silent
   * approve-over-open), the marked thread is resolved with {@code requireOwnContent}: a thread-less
   * finding (no inline comment that round) must not bind to an earlier round's <em>different</em>
   * finding that merely reuses index {@code N} on the same file and was answered. The content key
   * is the finding's title in the comment header, or its description when it has no title — so a
   * null-title finding is matched by its own description rather than the recurring marker alone.
   *
   * <p>When no own-content match is found but a {@code finding=N} comment <em>does</em> exist on
   * the file, that comment belongs to a different finding (the index recurs across rounds), so the
   * hold stands — the title-only fallback must not bind to it. Only genuinely pre-marker comments,
   * with no marker for this index on the file at all, fall through to the title scan, where the
   * title is the only available key.
   */
  private static Long answeredRootComment(
      ReviewResponse.Finding finding,
      int findingId,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    Long own = markerRootComment(finding, findingId, true, inlineComments, botIdentity);
    if (own != null) {
      return hasHumanReply(own, inlineComments, botIdentity) ? own : null;
    }
    if (markerRootComment(finding, findingId, false, inlineComments, botIdentity) != null) {
      return null;
    }
    return answeredRootComment(finding, inlineComments, botIdentity);
  }

  /** All bot root comments matching the finding's file and title, oldest first. */
  private static List<Long> rootCommentsByTitle(
      ReviewResponse.Finding finding,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    if (finding.title() == null || finding.file() == null) {
      return List.of();
    }
    return botRootComments(inlineComments, botIdentity)
        .filter(c -> FilePaths.same(finding.file(), c.path()))
        .filter(c -> c.body() != null && c.body().contains(finding.title()))
        .map(GitHubReviewClient.PullRequestComment::id)
        .toList();
  }

  private static Stream<GitHubReviewClient.PullRequestComment> botRootComments(
      List<GitHubReviewClient.PullRequestComment> inlineComments, BotIdentity botIdentity) {
    return inlineComments.stream()
        .filter(c -> c.inReplyToId() == null)
        .filter(c -> c.user() != null && botIdentity.matches(c.user().login()));
  }

  /** Lines a finding may drift between revisions and still count as the same location. */
  static final int DUPLICATE_LINE_TOLERANCE = 3;

  /**
   * Deterministic backstop for re-raised findings: drops a new finding when a finding from ANY
   * prior round at the same location already has a maintainer reply on its thread. The prompt
   * forbids re-raising prior findings, but the model occasionally disobeys when it privately
   * disagrees with the reply — the human answered once and should not have to answer again. A prior
   * finding counts as the same when the titles describe the same defect (not severity alone), so
   * escalating the rating cannot bypass the drop.
   */
  public ReviewResponse dropRepliedDuplicates(
      ReviewResponse response,
      List<String> priorAiResponseJsons,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    if (response.findings().isEmpty() || inlineComments.isEmpty()) {
      return response;
    }
    List<List<ReviewResponse.Finding>> priorRounds = parseAllFindings(priorAiResponseJsons);
    if (priorRounds.isEmpty()) {
      return response;
    }

    var kept = new ArrayList<ReviewResponse.Finding>();
    var dropped = false;
    for (ReviewResponse.Finding finding : response.findings()) {
      ReviewResponse.Finding duplicateOf =
          findRepliedDuplicate(finding, priorRounds, inlineComments, botIdentity);
      if (duplicateOf == null) {
        kept.add(finding);
        continue;
      }
      dropped = true;
      Log.infof(
          "Dropping re-raised finding '%s' (%s:%d) — a maintainer already replied to the prior"
              + " finding '%s' at the same location",
          finding.title(), finding.file(), finding.line(), duplicateOf.title());
    }
    if (!dropped) {
      return response;
    }
    return new ReviewResponse(
        kept,
        response.previousFindingsStatus(),
        FindingVerificationService.recount(response.summary(), kept));
  }

  private List<List<ReviewResponse.Finding>> parseAllFindings(List<String> aiResponseJsons) {
    if (aiResponseJsons == null || aiResponseJsons.isEmpty()) {
      return List.of();
    }
    var rounds = new ArrayList<List<ReviewResponse.Finding>>();
    for (String json : aiResponseJsons) {
      var findings = parsePreviousFindings(json);
      if (!findings.isEmpty()) {
        rounds.add(findings);
      }
    }
    return rounds;
  }

  private static ReviewResponse.Finding findRepliedDuplicate(
      ReviewResponse.Finding finding,
      List<List<ReviewResponse.Finding>> priorRounds,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    for (List<ReviewResponse.Finding> previous : priorRounds) {
      for (var prior : previous) {
        if (!isSameFinding(finding, prior)) {
          continue;
        }
        // Marker indices are only meaningful within their own round; across rounds the same index
        // names unrelated findings, so the thread is located by file and title instead.
        if (answeredRootComment(prior, inlineComments, botIdentity) != null) {
          return prior;
        }
      }
    }
    return null;
  }

  private static boolean isSameFinding(
      ReviewResponse.Finding finding, ReviewResponse.Finding prior) {
    if (finding.file() == null || !FilePaths.same(finding.file(), prior.file())) {
      return false;
    }
    if (Math.abs(finding.line() - prior.line()) <= DUPLICATE_LINE_TOLERANCE
        && FindingDeduplicator.titleSimilarity(finding.title(), prior.title())
            >= FindingDeduplicator.TITLE_SIMILARITY_THRESHOLD) {
      return true;
    }
    return FindingDeduplicator.contentOverlap(finding, prior)
        >= FindingDeduplicator.CONTENT_OVERLAP_THRESHOLD;
  }

  private static boolean hasHumanReply(
      Long rootId,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    return inlineComments.stream()
        .anyMatch(
            c ->
                rootId.equals(c.inReplyToId())
                    && c.user() != null
                    && !botIdentity.matches(c.user().login()));
  }

  /** Maps each previous finding's prompt id to its bot root comment, for thread resolution. */
  public Map<Integer, Long> matchFindingThreads(
      String previousAiResponseJson,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    return matchFindingThreads(
        parsePreviousFindings(previousAiResponseJson), inlineComments, botIdentity);
  }

  /** Same as the JSON overload, using findings already deserialized for this review. */
  public Map<Integer, Long> matchFindingThreads(
      List<ReviewResponse.Finding> previous,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    var threads = new HashMap<Integer, Long>();
    for (var i = 0; i < previous.size(); i++) {
      Long rootId = rootCommentId(previous.get(i), i + 1, inlineComments, botIdentity);
      if (rootId != null) {
        threads.put(i + 1, rootId);
      }
    }
    return threads;
  }

  /**
   * Previous findings the model marked unresolved, as {@link Finding}s so they keep their original
   * risk and confidence when deciding whether the review may approve.
   */
  public List<Finding> unresolvedFindings(
      String previousAiResponseJson, List<ReviewResponse.PreviousFindingStatus> statuses) {
    return unresolvedFindings(parsePreviousFindings(previousAiResponseJson), statuses);
  }

  /** Same as the JSON overload, using findings already deserialized for this review. */
  public List<Finding> unresolvedFindings(
      List<ReviewResponse.Finding> previous, List<ReviewResponse.PreviousFindingStatus> statuses) {
    if (statuses == null || statuses.isEmpty()) {
      return List.of();
    }
    if (previous == null || previous.isEmpty()) {
      return List.of();
    }
    var unresolvedIds = new HashSet<Integer>();
    for (var status : statuses) {
      if (STATUS_UNRESOLVED.equalsIgnoreCase(status.status())) {
        unresolvedIds.add(status.id());
      }
    }
    var unresolved = new ArrayList<Finding>();
    for (int id : unresolvedIds) {
      if (id >= 1 && id <= previous.size()) {
        unresolved.add(Finding.fromAiResponse(previous.get(id - 1)));
      }
    }
    return unresolved;
  }

  /**
   * Deterministic approve backstop. The bot's own prior findings the model silently dropped — still
   * present in the current diff, carrying no maintainer reply, and not closed by any round —
   * surfaced as synthetic {@code "unresolved"} statuses. Merging these into the result's
   * previous-findings statuses makes the existing APPROVE → COMMENT gate hold over a silently
   * dropped finding and keeps every downstream count and message truthful, without a separate code
   * path.
   *
   * <p>The findings are reconstructed from the persisted prior responses (keyed by repo+PR, so they
   * survive a force-push/rebase), which means the backstop fires even when the model received the
   * previous-findings context but ignored it.
   *
   * <p>It considers <em>all</em> prior rounds, not just the newest. Each round persists only its
   * own new findings; a finding raised in round 1 is referenced in later rounds only via their
   * {@code previous_findings_status}, so a still-open finding the model drops several rounds after
   * raising it would otherwise never be re-checked. The rounds are replayed oldest → newest:
   *
   * <ul>
   *   <li>A round's {@code previous_findings_status} reports on the round immediately before it
   *       (ids are 1-based positions over that round's findings). A {@code resolved}/{@code
   *       justified} verdict there closes the referenced finding, so it is removed from the open
   *       set — this is what keeps the widened scope from re-holding a finding that was
   *       legitimately addressed in an intermediate round (the "block any prior finding"
   *       over-strictness explicitly rejected).
   *   <li>Findings carried across rounds are deduplicated by content (file + line + title), so the
   *       same finding raised at one location is held at most once, while two distinct findings at
   *       different lines are never collapsed into one.
   *   <li>The current round reports on the newest prior round; a finding it accounted for with a
   *       <em>recognized</em> verdict (resolved / justified / unresolved, {@link
   *       #isRecognizedStatus}) is dropped — resolved/justified means addressed, and a reported
   *       {@code unresolved} is already held by the model gate ({@link #unresolvedFindings} +
   *       {@link #hasUnresolved}), so reconstructing it here would double-count. An
   *       <em>unrecognized</em> verdict is not an accounting, so the backstop still holds it — a
   *       malformed status string must not sneak a still-open finding past the APPROVE gate.
   *   <li>Presence is judged by {@link DiffLineResolver#isFindingPresent} against each finding's
   *       persisted {@code suggestion_old} anchor, so a still-open finding survives line drift and
   *       a fixed one is not kept alive by surviving context.
   * </ul>
   *
   * <p>It is downgrade-only — these statuses reach the APPROVE gate but never {@code outstanding},
   * so they can turn APPROVE into COMMENT, never into REQUEST_CHANGES. A maintainer reply on the
   * thread clears the hold (the human is engaged; defer to them, matching {@link
   * #dropRepliedDuplicates}), as does the model marking the finding resolved/justified.
   *
   * @param priorAiResponseJsons every completed prior round's persisted AI response, newest first
   *     (as {@link
   *     dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence#findAllPriorAiResponseJsons}
   *     returns them)
   * @param currentStatuses the current round's {@code previous_findings_status} (reports on the
   *     newest prior round)
   */
  public List<ReviewResult.PreviousFindingStatus> unreportedUnresolvedStatuses(
      List<String> priorAiResponseJsons,
      List<ReviewResponse.PreviousFindingStatus> currentStatuses,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      DiffLineResolver lineResolver,
      BotIdentity botIdentity) {
    return unreportedUnresolvedStatusesFromParsed(
        parsePreviousResponses(priorAiResponseJsons),
        currentStatuses,
        inlineComments,
        lineResolver,
        botIdentity);
  }

  /**
   * Same as the JSON overload, using prior responses already deserialized for this review so each
   * persisted round is not re-parsed.
   */
  public List<ReviewResult.PreviousFindingStatus> unreportedUnresolvedStatusesFromParsed(
      List<ReviewResponse> priorAiResponses,
      List<ReviewResponse.PreviousFindingStatus> currentStatuses,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      DiffLineResolver lineResolver,
      BotIdentity botIdentity) {
    if (priorAiResponses == null || priorAiResponses.isEmpty() || lineResolver == null) {
      return List.of();
    }
    var chrono = toChronological(priorAiResponses);
    var open = openFindingsAcrossRounds(chrono, currentStatuses);
    var clusters = clusterByIdentity(open);
    return heldFromClusters(clusters, inlineComments, lineResolver, botIdentity);
  }

  /**
   * Parses the persisted (newest-first) responses into oldest → newest order, so each round's
   * {@code previous_findings_status} (which reports on the round immediately before it) can close
   * the findings it addressed.
   */
  private static List<ReviewResponse> toChronological(List<ReviewResponse> priorAiResponses) {
    var chrono = new ArrayList<ReviewResponse>(priorAiResponses.size());
    for (var i = priorAiResponses.size() - 1; i >= 0; i--) {
      chrono.add(priorAiResponses.get(i));
    }
    return chrono;
  }

  /**
   * The still-open prior findings keyed by content (a finding carried across rounds is held at most
   * once; insertion order is preserved for stable, deterministic output). Each round closes what
   * the next round's status addressed, and {@code currentStatuses} — which reports on the newest
   * prior round — drops everything the current round accounted for.
   */
  private Map<String, OpenFinding> openFindingsAcrossRounds(
      List<ReviewResponse> chrono, List<ReviewResponse.PreviousFindingStatus> currentStatuses) {
    var open = new LinkedHashMap<String, OpenFinding>();
    for (var i = 0; i < chrono.size(); i++) {
      if (i > 0) {
        closeAddressed(open, chrono.get(i - 1).findings(), chrono.get(i).previousFindingsStatus());
      }
      addOpenFindings(open, chrono.get(i).findings());
    }
    closeReported(open, chrono.get(chrono.size() - 1).findings(), currentStatuses);
    return open;
  }

  /** Groups the open findings into clusters of tolerant ({@link #isSameFinding}) identity. */
  private static List<List<OpenFinding>> clusterByIdentity(Map<String, OpenFinding> open) {
    var clusters = new ArrayList<List<OpenFinding>>();
    for (var openFinding : open.values()) {
      List<OpenFinding> home = null;
      for (var cluster : clusters) {
        if (cluster.stream()
            .anyMatch(member -> isSameFinding(member.finding(), openFinding.finding()))) {
          home = cluster;
          break;
        }
      }
      if (home == null) {
        home = new ArrayList<>();
        clusters.add(home);
      }
      home.add(openFinding);
    }
    return clusters;
  }

  /** Holds one unresolved status per cluster whose code is still present and unanswered. */
  private List<ReviewResult.PreviousFindingStatus> heldFromClusters(
      List<List<OpenFinding>> clusters,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      DiffLineResolver lineResolver,
      BotIdentity botIdentity) {
    var held = new ArrayList<ReviewResult.PreviousFindingStatus>();
    for (var cluster : clusters) {
      OpenFinding target = holdableTarget(cluster, inlineComments, lineResolver, botIdentity);
      if (target != null) {
        held.add(
            new ReviewResult.PreviousFindingStatus(
                target.id(),
                STATUS_UNRESOLVED,
                "Flagged in an earlier round and still present; not addressed in this revision."));
      }
    }
    return held;
  }

  /**
   * The first still-present member of the cluster to hold, or {@code null} when its code is gone or
   * a maintainer has replied on it. The reply is located by the round-relative marker ({@link
   * OpenFinding#id()}) plus the finding's own content rather than by title, so a null-title
   * finding's thread is still seen and a thread-less finding cannot bind to a different finding
   * that reused the same marker index in another round.
   */
  private OpenFinding holdableTarget(
      List<OpenFinding> cluster,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      DiffLineResolver lineResolver,
      BotIdentity botIdentity) {
    OpenFinding target = null;
    boolean anyReplied = false;
    for (var member : cluster) {
      var finding = member.finding();
      if (target == null
          && lineResolver.isFindingPresent(finding.file(), finding.suggestionOld())) {
        target = member;
      }
      if (answeredRootComment(finding, member.id(), inlineComments, botIdentity) != null) {
        anyReplied = true;
      }
    }
    return anyReplied ? null : target;
  }

  /** A still-open prior finding and the 1-based id it carried within the round that raised it. */
  private record OpenFinding(ReviewResponse.Finding finding, int id) {}

  private static void addOpenFindings(
      Map<String, OpenFinding> open, List<ReviewResponse.Finding> findings) {
    for (var i = 0; i < findings.size(); i++) {
      var key = findingKey(findings.get(i));
      if (key != null) {
        open.put(key, new OpenFinding(findings.get(i), i + 1));
      }
    }
  }

  /**
   * Removes the findings a following round marked resolved or justified (the verdicts that close
   * one). An unresolved or unrecognized verdict from an intermediate round leaves the finding open.
   */
  private static void closeAddressed(
      Map<String, OpenFinding> open,
      List<ReviewResponse.Finding> reportedRound,
      List<ReviewResponse.PreviousFindingStatus> statuses) {
    removeReferenced(open, reportedRound, statuses, FollowUpAnalyzer::isAddressedVerdict);
  }

  /**
   * Removes the findings the current round accounted for with a recognized verdict
   * (resolved/justified are addressed; a reported unresolved is already held by the model gate). An
   * unrecognized verdict does not count as accounting for the finding, so the backstop still holds
   * it.
   */
  private static void closeReported(
      Map<String, OpenFinding> open,
      List<ReviewResponse.Finding> reportedRound,
      List<ReviewResponse.PreviousFindingStatus> statuses) {
    removeReferenced(open, reportedRound, statuses, FollowUpAnalyzer::isRecognizedStatus);
  }

  private static void removeReferenced(
      Map<String, OpenFinding> open,
      List<ReviewResponse.Finding> reportedRound,
      List<ReviewResponse.PreviousFindingStatus> statuses,
      Predicate<String> closesFinding) {
    if (statuses == null) {
      return;
    }
    for (var status : statuses) {
      if (!closesFinding.test(status.status())) {
        continue;
      }
      var id = status.id();
      if (id >= 1 && id <= reportedRound.size()) {
        var key = findingKey(reportedRound.get(id - 1));
        if (key != null) {
          open.remove(key);
        }
      }
    }
  }

  /**
   * The verdicts that <em>close</em> a finding an intermediate round reported on — resolved or
   * justified. A reported {@code unresolved} keeps it open here (it is held by the model gate, and
   * an intermediate round may yet drop it); {@link #isRecognizedStatus} is the wider current-round
   * predicate that also treats {@code unresolved} as accounted-for.
   */
  private static boolean isAddressedVerdict(String status) {
    return STATUS_RESOLVED.equalsIgnoreCase(status) || STATUS_JUSTIFIED.equalsIgnoreCase(status);
  }

  /**
   * Content identity for cross-round dedup and status-to-finding matching: file, line, and title. A
   * null file yields no key — {@link DiffLineResolver#isFindingPresent} could not place it in the
   * diff anyway.
   *
   * <p>The line is the discriminator, deliberately NOT the {@code suggestion_old} anchor. Keying on
   * the line distinguishes two genuinely-distinct findings that share a file and title but sit at
   * different lines — e.g. a generic anchor like {@code return null;} flagged under one title at
   * two sites — so neither evicts the other from the open set; collapsing them would drop a
   * still-open silent finding and let APPROVE sail over it (the missed hold). The cost is that one
   * finding re-raised at a <em>drifted</em> line is keyed twice and held twice — a duplicate,
   * downgrade-only over-count in the "Still present" summary. That is accepted on purpose: a
   * drifted re-raise and two distinct same-anchor findings present the identical signal (same
   * file+title, different line), so any key that deduplicates the former necessarily collapses the
   * latter, and an over-count is the safe direction where a missed hold is not. (The anchor is
   * still used for <em>presence</em> via {@link DiffLineResolver#isFindingPresent}; it just does
   * not define identity.)
   */
  private static String findingKey(ReviewResponse.Finding finding) {
    if (finding.file() == null) {
      return null;
    }
    return finding.file() + '\0' + finding.line() + '\0' + finding.title();
  }

  /**
   * A model-reported status counts as the model accounting for a finding only when it is one of the
   * contract's recognized values (resolved / justified / unresolved, case-insensitively). Any other
   * value — empty, null, a typo, or an out-of-vocabulary word like {@code "wontfix"} — must fall
   * through to the backstop: otherwise a still-open finding with an unrecognized status escapes
   * BOTH the backstop (its id is present in {@code reportedIds}) AND the {@link #hasUnresolved}
   * gate (its value is not {@code "unresolved"}), re-introducing the silent approve-over-open the
   * backstop exists to stop. {@code unresolved} stays in the set so the backstop never
   * double-counts an item the gate already holds via the model's own status.
   */
  private static boolean isRecognizedStatus(String status) {
    return status != null && RECOGNIZED_STATUSES.contains(status.toLowerCase(Locale.ROOT));
  }

  /**
   * File of each prior finding keyed by its 1-based listed number — the id space the model's {@code
   * previous_findings_status} entries reference. Lets the multi-call merge accept a
   * "resolved"/"justified" claim only from a batch whose diff slice actually contained the
   * finding's file.
   */
  public Map<Integer, String> previousFindingFilesById(String previousAiResponseJson) {
    return previousFindingFilesById(parsePreviousFindings(previousAiResponseJson));
  }

  /** Same as the JSON overload, using findings already deserialized for this review. */
  public Map<Integer, String> previousFindingFilesById(List<ReviewResponse.Finding> previous) {
    var filesById = new HashMap<Integer, String>();
    if (previous == null) {
      return filesById;
    }
    for (var i = 0; i < previous.size(); i++) {
      filesById.put(i + 1, previous.get(i).file());
    }
    return filesById;
  }

  private List<ReviewResponse.Finding> parsePreviousFindings(String aiResponseJson) {
    return parseResponse(aiResponseJson).findings();
  }

  /**
   * Deserializes every prior round's persisted AI response (newest first), once per review. Missing
   * or unparseable entries become empty responses so callers can share one list without re-parsing.
   */
  public List<ReviewResponse> parsePreviousResponses(List<String> priorAiResponseJsons) {
    if (priorAiResponseJsons == null || priorAiResponseJsons.isEmpty()) {
      return List.of();
    }
    var parsed = new ArrayList<ReviewResponse>(priorAiResponseJsons.size());
    for (var json : priorAiResponseJsons) {
      parsed.add(parseResponse(json));
    }
    return List.copyOf(parsed);
  }

  /**
   * Full persisted previous response, with empty (never null) findings and statuses on a missing or
   * unparseable input — the backstop replay needs both lists, and the compact constructor of {@link
   * ReviewResponse} guarantees non-null copies.
   */
  ReviewResponse parseResponse(String aiResponseJson) {
    if (aiResponseJson == null || aiResponseJson.isBlank()) {
      return EMPTY_RESPONSE;
    }
    try {
      return mapper.readValue(aiResponseJson, ReviewResponse.class);
    } catch (JsonProcessingException e) {
      Log.warn("Could not parse previous AI response, falling back to review body context", e);
      return EMPTY_RESPONSE;
    }
  }

  /**
   * Fallback context built from the last bot review body, for sessions without a persisted AI
   * response. The body carries no structured findings, so this is best-effort only.
   */
  public String buildPreviousFindingsContext(
      List<GitHubReviewClient.ReviewResponse> priorReviews, BotIdentity botIdentity) {
    if (priorReviews == null || priorReviews.isEmpty()) return "";

    var lastBotReview =
        priorReviews.stream()
            .filter(r -> botIdentity.matches(r.user().login()))
            .reduce((first, second) -> second);

    if (lastBotReview.isEmpty()) return "";

    // The prompt template provides the lead-in sentence; emit only the review body
    var body = lastBotReview.get().body();
    return body != null ? body : "";
  }

  /**
   * Converts AI response's previous_findings_status into ReviewResult statuses, keeping only the
   * recognized values (resolved / justified / unresolved). An unrecognized status is meaningless
   * noise that no count or gate acts on, and for a still-open finding the backstop already emits a
   * synthetic {@code "unresolved"} for that id — passing the raw value through too would leave two
   * entries with the same id in the result. Dropping it keeps {@code previousStatuses} one-per-id
   * and matches the backstop's recognized-status contract.
   */
  public List<ReviewResult.PreviousFindingStatus> toStatuses(
      List<ReviewResponse.PreviousFindingStatus> aiStatuses) {
    if (aiStatuses == null) return List.of();

    var result = new ArrayList<ReviewResult.PreviousFindingStatus>();
    for (var s : aiStatuses) {
      if (isRecognizedStatus(s.status())) {
        result.add(new ReviewResult.PreviousFindingStatus(s.id(), s.status(), s.note()));
      }
    }
    return result;
  }

  /** Checks if there are any unresolved findings that should be re-flagged. */
  public boolean hasUnresolved(List<ReviewResult.PreviousFindingStatus> statuses) {
    return statuses.stream().anyMatch(s -> STATUS_UNRESOLVED.equalsIgnoreCase(s.status()));
  }
}
