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
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Analyzes follow-up reviews by comparing new findings against prior reviews. */
@ApplicationScoped
public class FollowUpAnalyzer {

  private static final String STATUS_UNRESOLVED = "unresolved";
  private static final String STATUS_RESOLVED = "resolved";
  private static final String STATUS_JUSTIFIED = "justified";

  /**
   * Synthetic status for a prior finding whose flagged code is no longer in the current diff — the
   * model never emits it; {@link #supersedeVanished} rewrites a stale {@code unresolved} to it so
   * the finding stops holding APPROVE while its outcome stays visible in the summary counts.
   */
  static final String STATUS_SUPERSEDED = "superseded";

  private static final String SUPERSEDED_NOTE =
      "The code this finding targeted is no longer in this revision's diff (removed by a"
          + " force-push or a later commit) — superseded.";

  private static final String PURE_RENAME_UNRESOLVED_NOTE =
      "The finding's file was renamed without content changes, so the finding remains unresolved.";

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

  /** Whether {@link #recheckDeclines} may override a maintainer decline; see the config key. */
  private final boolean declineRecheckEnabled;

  @Inject
  public FollowUpAnalyzer(ObjectMapper mapper, ThrillhouseConfig config) {
    this(mapper, config.review().declineRecheckEnabled());
  }

  /** Visible for tests; the decline re-check is on, matching the shipped default. */
  FollowUpAnalyzer(ObjectMapper mapper) {
    this(mapper, true);
  }

  /** Visible for tests: pins the decline re-check flag. */
  FollowUpAnalyzer(ObjectMapper mapper, boolean declineRecheckEnabled) {
    this.mapper = mapper;
    this.declineRecheckEnabled = declineRecheckEnabled;
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
    var previous = parseResponse(previousAiResponseJson);
    return buildPreviousFindingsContext(
        previous.findings(),
        isPersistedResponse(previous),
        priorReviews,
        inlineComments,
        parsePreviousResponses(olderAiResponseJsons),
        botIdentity);
  }

  /**
   * Same as the JSON overload, but consumes findings already deserialized for this review so the
   * prior-response JSON is not re-parsed.
   *
   * <p>{@code previousResponsePersisted} is what keeps the review-body fallback in its lane. The
   * fallback exists for sessions that persisted no readable AI response at all; inferring that from
   * an empty rendering instead also caught the ordinary case of a persisted round that legitimately
   * found nothing, and fed the bot's own review prose back to the model under a header saying "the
   * following issues were flagged in the previous review" (#455). The two cases are told apart by
   * the caller, which knows which one it is in, not by the shape of the output.
   */
  public String buildPreviousFindingsContext(
      List<ReviewResponse.Finding> previousFindings,
      boolean previousResponsePersisted,
      List<GitHubReviewClient.ReviewResponse> priorReviews,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<ReviewResponse> olderAiResponses,
      BotIdentity botIdentity) {
    return buildPreviousFindingsContext(
        previousFindings,
        previousResponsePersisted,
        priorReviews,
        inlineComments,
        olderAiResponses,
        botIdentity,
        Set.of());
  }

  /**
   * Variant that also skips prior findings a newer round already closed ({@code settledIds}, {@link
   * #settledPreviousIds}) so the model is not re-shown a finding that has been settled and asked to
   * re-account for it (#470). The settled entries keep their id slots — only their content is
   * omitted — so the surviving ids do not shift.
   */
  public String buildPreviousFindingsContext(
      List<ReviewResponse.Finding> previousFindings,
      boolean previousResponsePersisted,
      List<GitHubReviewClient.ReviewResponse> priorReviews,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<ReviewResponse> olderAiResponses,
      BotIdentity botIdentity,
      Set<Integer> settledIds) {
    var structured =
        formatStructuredFindings(previousFindings, inlineComments, botIdentity, settledIds);
    var answered = formatAnsweredEarlier(olderAiResponses, inlineComments, botIdentity);
    if (!structured.isEmpty() || previousResponsePersisted) {
      return structured + answered;
    }
    var fallback = buildPreviousFindingsContext(priorReviews, botIdentity);
    return fallback + answered;
  }

  /**
   * The prior round the current review reports on: the newest persisted round that actually raised
   * findings, or an empty list when none did.
   *
   * <p>A round that legitimately found nothing has no findings of its own to be reported on, so
   * treating it as "the previous round" evicted the still-open set entirely — the finding raised
   * two rounds ago dropped out of the prompt, out of {@code previous_findings_status}, and out of
   * every id-keyed consumer, and could never be marked resolved again (#455). Skipping such a round
   * carries the open set forward.
   *
   * <p>The round's own list is returned <em>whole</em>, deliberately: every id stays exactly the
   * 1-based position the finding had when it was posted, which is the index its inline comment's
   * hidden marker carries and the id space {@code previous_findings_status} references. Filtering
   * out findings a later round already closed would renumber the rest and break both.
   *
   * @param priorAiResponses every completed prior round's parsed response, newest first
   */
  public static List<ReviewResponse.Finding> effectivePreviousFindings(
      List<ReviewResponse> priorAiResponses) {
    var index = effectivePreviousRoundIndex(priorAiResponses);
    return index < 0 ? List.of() : priorAiResponses.get(index).findings();
  }

  /**
   * Position of {@link #effectivePreviousFindings}'s round in {@code priorAiResponses} (newest
   * first), or {@code -1} when no prior round raised anything. An absent list, and an absent slot
   * within one, count as rounds that raised nothing: this is the id space every downstream consumer
   * keys off, so it degrades to "no previous round" rather than failing the review — the same way
   * {@link #parsePreviousResponses} and {@link #toStatuses} treat absent input.
   */
  public static int effectivePreviousRoundIndex(List<ReviewResponse> priorAiResponses) {
    if (priorAiResponses == null) {
      return -1;
    }
    for (var i = 0; i < priorAiResponses.size(); i++) {
      var response = priorAiResponses.get(i);
      if (response != null && !response.findings().isEmpty()) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Ids (1-based positions in the effective previous round) that a <em>newer</em> round already
   * closed with a resolved/justified verdict, and that therefore must be treated as settled: not
   * re-listed to the model as still open, and never re-emitted as {@code superseded} when their
   * code later leaves the diff.
   *
   * <p>The effective previous round is the newest round that raised findings, so every round newer
   * than it raised nothing and reports on it (ids reference that round). {@link
   * #effectivePreviousFindings} returns that round's list <em>whole</em> — ids stay stable — so a
   * later round's close is invisible to the id-keyed supersede/context passes; without this set
   * {@link #addUnreportedVanished} re-supersedes a finding a newer round already resolved, which
   * pins {@code hasSupersededPrevious} on and re-posts the summary every push while suppressing the
   * follow-up delta (#470). This is the same {@code closeAddressed} replay the backstop does.
   *
   * @param priorAiResponses every completed prior round's parsed response, newest first
   */
  public static Set<Integer> settledPreviousIds(List<ReviewResponse> priorAiResponses) {
    var index = effectivePreviousRoundIndex(priorAiResponses);
    if (index <= 0) {
      return Set.of();
    }
    var settled = new HashSet<Integer>();
    for (var i = 0; i < index; i++) {
      var round = priorAiResponses.get(i);
      if (round == null) {
        continue;
      }
      for (var status : round.previousFindingsStatus()) {
        if (isAddressedVerdict(status.status())) {
          settled.add(status.id());
        }
      }
    }
    return settled;
  }

  /**
   * Whether a parsed round is a response the bot actually persisted, as opposed to the shared blank
   * stand-in {@link #parseResponse} substitutes for a missing or unparseable one. Reference
   * identity is the discriminator on purpose: a round that legitimately found nothing parses into
   * its own instance — equal to the stand-in, but not the same object — and telling those two apart
   * is exactly what keeps the review-body fallback out of a zero-finding round. It also lets
   * callers reuse the single parse they already did rather than parsing the JSON a second time.
   *
   * <p>An absent response is the same absence a missing or unparseable one is, and so is not
   * persisted either.
   */
  public static boolean isPersistedResponse(ReviewResponse response) {
    return response != null && response != EMPTY_RESPONSE;
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
      BotIdentity botIdentity,
      Set<Integer> settledIds) {
    if (previous == null || previous.isEmpty()) {
      return "";
    }
    // The prompt template provides the lead-in sentence; emit only the numbered findings
    var sb = new StringBuilder();
    var id = 1;
    for (var finding : previous) {
      // A finding a newer round already closed keeps its id slot, so the surviving ids do not
      // shift, but it is not re-shown as open for the model to re-account for. See issue 470.
      if (settledIds.contains(id)) {
        id++;
        continue;
      }
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
   * The bot's root inline comment for a finding, for prompt context ({@link #appendThreadReplies}),
   * thread resolution ({@link #matchFindingThreads}), and the decline re-check ({@link
   * #declineContradiction}). Marker indices are per-round 1-based positions, so index {@code N}
   * recurs every round; the same three-step content match {@link
   * #answeredRootComment(ReviewResponse.Finding, int, List, BotIdentity)} uses for the
   * safety-critical clearing decision applies here so a summary-only finding (no inline thread of
   * its own that round, because {@link Finding#postsInline} routed it to the summary) does not bind
   * to an <em>earlier, unrelated</em> round's same-index thread (F2 — the sibling defect of F5):
   *
   * <ol>
   *   <li>the finding's own {@code finding=N} thread ({@code requireOwnContent}: its title, or its
   *       description when it has no title, in the comment) — the unambiguous match;
   *   <li>otherwise, when a {@code finding=N} comment <em>does</em> exist on the file it belongs to
   *       a different finding (the index recurs across rounds), so nothing binds rather than
   *       binding to it;
   *   <li>only when no {@code finding=N} comment exists on the file at all does the title scan run,
   *       for genuinely pre-marker comments.
   * </ol>
   */
  private static Long rootCommentId(
      ReviewResponse.Finding finding,
      int findingId,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    Long own = markerRootComment(finding, findingId, true, inlineComments, botIdentity);
    if (own != null) {
      return own;
    }
    if (markerRootComment(finding, findingId, false, inlineComments, botIdentity) != null) {
      return null;
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

  /**
   * A maintainer reply that may clear an approve hold, drop a re-raised finding, or overrule a
   * decline — a non-bot reply whose author holds (or may hold) write access to the repository.
   *
   * <p>A reply's {@code author_association} is GitHub's per-comment statement of the author's
   * relationship to the repo; only {@code OWNER}/{@code MEMBER}/{@code COLLABORATOR} can carry
   * write access, so a fork-PR author's reply ({@code CONTRIBUTOR}/{@code NONE}/…) is not a
   * maintainer decision and must not clear the backstop, delete a finding, or drive an override —
   * it renders as context in the prompt only. This mirrors the cheap association prefilter {@code
   * ManualReviewAuthorizer}/{@code FindingFeedbackCaptureService} apply before any write. The
   * authoritative collaborator-permission call those services follow up with is not repeated here:
   * these predicates run deep in the verdict path with no installation token in hand, and deferring
   * a bot hold to an invited collaborator who engaged on the thread is the safe direction.
   */
  private static boolean hasHumanReply(
      Long rootId,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    return inlineComments.stream()
        .anyMatch(
            c ->
                rootId.equals(c.inReplyToId())
                    && c.user() != null
                    && !botIdentity.matches(c.user().login())
                    && mayHoldWriteAccess(c.authorAssociation()));
  }

  /**
   * GitHub {@code author_association} values a write-access holder can present. Any other value
   * provably lacks write access — association precedence guarantees a collaborator/member is never
   * reported as one of those — so a reply carrying it is not a maintainer decision.
   */
  private static final Set<String> WRITE_CAPABLE_ASSOCIATIONS =
      Set.of("OWNER", "MEMBER", "COLLABORATOR");

  /**
   * Package-private rather than private so {@link MaintainerReplyService} can acknowledge a clear
   * directive against the very gate that will decide it. The acknowledgment is written before any
   * review runs, and the two used to disagree: the mention path admits a login on the
   * manual-trigger allowlist whatever its association, while {@link #clearedInConversation}
   * requires a write-capable one, so an allowlist-only commenter was promised a closure that never
   * came.
   */
  static boolean mayHoldWriteAccess(String authorAssociation) {
    return authorAssociation != null
        && WRITE_CAPABLE_ASSOCIATIONS.contains(authorAssociation.strip().toUpperCase(Locale.ROOT));
  }

  /**
   * Compiled clear directives, one per {@link BotIdentity}. A deployment has exactly one identity,
   * so this holds a single entry in production; caching it here keeps the pattern compiled once per
   * config rather than once per comment while letting the static directive predicates stay shared
   * with {@link MaintainerReplyService}.
   */
  private static final Map<BotIdentity, Pattern> CLEAR_DIRECTIVES = new ConcurrentHashMap<>();

  /**
   * The directive a maintainer writes in a PR conversation comment to clear findings the review
   * threads cannot reach. Mirrors the {@code @thrillhousebot <word>} mention form every other
   * command uses, but deliberately spells the word {@code resolved} rather than {@code resolve}, so
   * it is not the existing {@code /resolve} command (which resolves GitHub review threads) under a
   * second name — {@code TriggerDetector}'s {@code resolve} pattern ends in a word boundary and
   * therefore does not fire on {@code resolved}.
   *
   * <p>The mention names come from the configured bot logins ({@link BotIdentity#mentionNames}),
   * not a hardcoded slug, so an install whose GitHub App runs under a different login still
   * recognizes its maintainers' directives (#679). Each name is {@link Pattern#quote}d — a login is
   * data, never regex. The leading anchor requires the {@code @} to open the comment or follow a
   * non-word character, the way a GitHub mention is written — an email address's local part
   * ("root@thrillhousebot resolved …") never mentions the bot and must not clear anything.
   *
   * <p>The separator admits Unicode space separators ({@code \p{Zs}}) alongside {@code \s}: pasted
   * text carries non-breaking spaces, and a directive is a directive whichever space the maintainer
   * pasted. The interrogative guard additionally skips format characters ({@code \p{Cf}}): a
   * zero-width character smuggled before the {@code ?} must not turn a question into a clearing
   * order — the same over-clear direction the locator guards already refuse (#654). The asymmetry
   * is deliberate: a format character <em>inside the separator</em> still fails the match, which
   * merely holds the finding — the safe direction.
   *
   * <p>The trailing lookahead rejects the interrogative: a word boundary holds before {@code ?}, so
   * "@thrillhousebot resolved? `src/A.java:10` — SQL injection" — a maintainer <em>asking</em>
   * whether a finding was fixed, while quoting it well enough to name it — otherwise parses as an
   * instruction and closes the very finding the question is about. Asking is not deciding, and this
   * is the over-clear direction, so a token immediately followed by a question mark (whitespace
   * aside) is not a directive. The lookahead cannot skip past the naming, so a genuine directive
   * that happens to end in a question ("…— fixed in abc123, ok?") still counts.
   */
  private static Pattern clearDirective(BotIdentity botIdentity) {
    return CLEAR_DIRECTIVES.computeIfAbsent(
        botIdentity,
        identity -> {
          String mentions =
              identity.mentionNames().stream().map(Pattern::quote).collect(Collectors.joining("|"));
          return Pattern.compile(
              "(?:^|[^\\w@])@(?:"
                  + mentions
                  + ")[\\s\\p{Zs}]+resolved\\b(?![\\s\\p{Zs}\\p{Cf}]*\\?)",
              Pattern.CASE_INSENSITIVE);
        });
  }

  /** Fenced code blocks, dropped before a conversation comment is read as a directive. */
  private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```|~~~.*?~~~");

  /** Markdown blockquote lines — what GitHub's "Quote reply" produces. */
  private static final Pattern BLOCKQUOTE_LINE = Pattern.compile("(?m)^[ \\t]*>.*$");

  /**
   * Inline code spans, dropped only when looking for the directive token itself. The delimiter is
   * {@code `+} rather than a single backtick so the double-backtick form documentation uses to show
   * a span that itself contains a backtick is recognized too.
   */
  private static final Pattern INLINE_CODE = Pattern.compile("`+[^`\\n]*`+");

  /**
   * Note recorded on a finding a maintainer cleared from the PR conversation. Renders the bot's
   * configured mention name so the note describes a comment the install's maintainers can actually
   * have written.
   */
  static String conversationClearedNote(BotIdentity botIdentity) {
    return "Cleared by a maintainer's @"
        + botIdentity.primaryMention()
        + " resolved comment on the PR conversation.";
  }

  /**
   * Whether {@code body} <em>uses</em> the clearing directive, as opposed to quoting it. Shared
   * with {@link MaintainerReplyService} so the conversational-reply path recognizes exactly the
   * text this analyzer acts on.
   *
   * <p>The token is looked for in {@link #directiveText}: outside blockquotes, code fences <em>and
   * inline code</em>. Explaining the feature to a colleague — "comment {@code `@thrillhousebot
   * resolved src/A.java:10 — the title`}" — is documentation, not a decision, and a rule that fired
   * on it would let the project's own docs close findings. Marking up the directive is the
   * universal way to show rather than issue a command, so it is the line drawn here.
   */
  static boolean isClearDirective(String body, BotIdentity botIdentity) {
    return body != null && clearDirective(botIdentity).matcher(directiveText(body)).find();
  }

  /**
   * The shape every locator has — a path, a colon, a line number. Only ever used to prove a
   * <em>negative</em>; see {@link #namesALocator}.
   *
   * <p>Written as a lookbehind rather than the obvious {@code \S+:\d+}, which is the same predicate
   * but quadratic on this input. {@code :} is itself a {@code \S}, so in the obvious form every
   * colon gives the engine another way to split the same prefix and the body — arbitrary text from
   * a PR comment — drives the backtracking. The two forms accept exactly the same strings: {@code
   * \S+:\d+} matches iff some colon has a non-space character before it and a digit after it, which
   * is what this states directly, with no quantifier to backtrack over.
   */
  private static final Pattern LOCATOR_SHAPE = Pattern.compile("(?<=\\S):\\d");

  /**
   * How much horizontal space may sit either side of a range's separator before the text stops
   * reading as one range. A separator is spaced off its operands by a character or two at most; a
   * longer run is two separate thoughts, and the bound keeps the scan over untrusted comment prose
   * linear.
   */
  private static final int MAX_RANGE_SPACING = 16;

  /**
   * Whether {@code body} names anything locator-shaped at all. This is a <em>necessary</em>
   * condition of {@link #clearedInConversation}, never a sufficient one: clearing a finding
   * requires its exact {@code path:line}, so a comment carrying no {@code path:line} token
   * whatsoever provably clears nothing, while one that carries such a token may still match no
   * finding.
   *
   * <p>That asymmetry is the whole point. {@link MaintainerReplyService} answers a directive before
   * any review has run, with no prior round loaded and no findings to match against — it cannot
   * decide whether a clear will happen, and guessing would be worse than silence. It can, however,
   * state the one thing derivable from the comment alone: that a directive naming no locator will
   * clear nothing, so a maintainer who wrote the directive but forgot (or mistyped away) the
   * locator is told immediately rather than after the next review quietly changes nothing.
   *
   * <p>"Locator-shaped" has to mean the same thing here as it does at clearing time. Shape alone —
   * any {@code :<digit>} anywhere — counts {@code src/A.java:1-3} as a naming, and {@link
   * #namesLocator} then refuses to clear it, so the maintainer is promised a closure that never
   * comes. That is the promise-without-delivery this service exists to avoid, arrived at from the
   * other side, so the guards {@link #continuesLocator} and {@link #startsSpacedRange} apply here
   * too: a body names a locator when at least one locator-shaped token in it is whole.
   */
  static boolean namesALocator(String body) {
    return strongestNaming(body) == LocatorNaming.WHOLE;
  }

  /**
   * Whether the strongest locator-shaped naming in {@code body} is an ambiguous spaced range — the
   * one rejection an ack cannot stand behind. The documented {@code path:line — <title>} form with
   * a digit-leading title is spelled exactly like a spaced range, and telling them apart takes the
   * finding set, which the reply path does not have (#548): the clearing path resolves the
   * ambiguity by matching the text after the separator against the finding's own title ({@link
   * #separatorLeadsOwnContent}), so the ack for this shape must name the ambiguity rather than
   * claim no finding was named (#653).
   */
  static boolean namesOnlyAmbiguousRanges(String body) {
    return strongestNaming(body) == LocatorNaming.AMBIGUOUS_RANGE;
  }

  /** What {@link #namesALocator} and {@link #namesOnlyAmbiguousRanges} tell apart. */
  private enum LocatorNaming {
    WHOLE,
    AMBIGUOUS_RANGE,
    NONE
  }

  private static LocatorNaming strongestNaming(String body) {
    if (body == null) {
      return LocatorNaming.NONE;
    }
    var text = namingText(body);
    var shapes = LOCATOR_SHAPE.matcher(text);
    var strongest = LocatorNaming.NONE;
    while (shapes.find()) {
      // The match starts at the ':'; the line number is the digit run after it. ASCII only, to
      // match the shape pattern's own \d and the clearing side: Character.isDigit accepts the
      // full-width digits, which continuesLocator reads as continuing the token rather than as the
      // line number, and a locator the two sides parse differently is one they disagree about.
      var after = shapes.start() + 1;
      while (after < text.length() && text.charAt(after) >= '0' && text.charAt(after) <= '9') {
        after++;
      }
      if (after >= text.length() || !continuesLocator(text, after)) {
        if (after < text.length() && startsSpacedRange(text, after)) {
          strongest = LocatorNaming.AMBIGUOUS_RANGE;
        } else {
          return LocatorNaming.WHOLE;
        }
      }
    }
    return strongest;
  }

  /**
   * The comment body with quoted <em>blocks</em> removed: fenced code and blockquote lines.
   * Dropping blockquotes is what keeps GitHub's "Quote reply" from clearing findings — quoting the
   * summary's "Things to double-check" list reproduces every row verbatim, and matching inside it
   * would clear findings the maintainer never named.
   *
   * <p>Inline code is deliberately <em>kept</em> here: the summary prints each finding's locator as
   * {@code `path:line`}, so a maintainer who copies the row must still be naming the finding. This
   * is the text the locator and content are matched against, never the directive token.
   */
  private static String namingText(String body) {
    return BLOCKQUOTE_LINE.matcher(FENCED_CODE.matcher(body).replaceAll(" ")).replaceAll(" ");
  }

  /**
   * The text the directive token must appear in: {@link #namingText} with inline code spans dropped
   * too. Using the directive and quoting it are different acts, and only the first may clear a
   * finding; keeping the two texts separate is what lets a comment carry a backticked locator (how
   * the summary prints it) while a backticked <em>token</em> reads as documentation.
   */
  private static String directiveText(String body) {
    return INLINE_CODE.matcher(namingText(body)).replaceAll(" ");
  }

  /**
   * Whether a maintainer cleared this finding from the PR conversation — the escape hatch for a
   * finding that has no review thread to reply on, because {@link Finding#postsInline} routed it to
   * the summary's "Things to double-check" instead of the diff (#548). Without it a low-confidence
   * finding held by the backstop could never be closed by any maintainer action: the reply hatch
   * (#133/#142) needs a thread, and there is none.
   *
   * <p>Over-clearing is the dangerous direction, so every leg below must hold and any one missing
   * leaves the finding held:
   *
   * <ul>
   *   <li>the comment is a human's, not the bot's — the bot's own summary lists every finding
   *       verbatim, so reading it back would clear the whole round;
   *   <li>its author may hold write access ({@link #mayHoldWriteAccess}) — the same association
   *       prefilter the review-thread hatch applies, so a drive-by commenter on a public repo
   *       cannot clear a hold;
   *   <li>it <em>uses</em> the {@link #clearDirective} rather than quoting it ({@link
   *       #isClearDirective}: outside blockquotes, fences and inline code) — an ordinary comment
   *       that merely discusses a finding is engagement, not a decision, and a comment that shows
   *       the directive marked up is documentation, not an instruction;
   *   <li>it names <em>this</em> finding by BOTH its printed locator ({@code path:line}) and its
   *       own content (its title, or its description when it has no title) — the same
   *       content-anchoring the marked-thread hatch uses. A finding with neither title nor
   *       description matches nothing and stays held.
   * </ul>
   *
   * <p>Marker reuse cannot leak a clear across rounds: no {@code thrillhousebot:finding=N} marker
   * is read here at all, so a pasted marker names nothing. The locator is the finding's own
   * persisted {@code file}:{@code line} — the pair the summary printed for that round — so a
   * same-titled finding at another location is not named by it either.
   */
  private static boolean clearedInConversation(
      ReviewResponse.Finding finding,
      List<GitHubCommentClient.IssueComment> conversationComments,
      BotIdentity botIdentity) {
    if (conversationComments == null || conversationComments.isEmpty() || finding.file() == null) {
      return false;
    }
    String anchor = ownContentAnchor(finding);
    if (anchor == null) {
      return false;
    }
    String locator = finding.file() + ":" + finding.line();
    for (var comment : conversationComments) {
      if (!isMaintainerConversationComment(comment, botIdentity)
          || !isClearDirective(comment.body(), botIdentity)) {
        continue;
      }
      // The naming may sit in backticks (the shape the summary prints); only the directive token
      // may not — see isClearDirective.
      String body = namingText(comment.body());
      if (namesLocator(body, locator, anchor) && body.contains(anchor)) {
        Log.infof(
            "Clearing previous finding '%s' (%s) — a maintainer named it in an"
                + " @thrillhousebot resolved comment on the PR conversation",
            finding.title(), locator);
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code body} names {@code path:line} as a whole locator. A bare {@code contains} would
   * let a comment about {@code src/A.java:10} clear the distinct finding at {@code src/A.java:1},
   * whose locator is a prefix of it — an over-clear, and the direction that must never happen — so
   * a match the following character continues is skipped and the scan continues.
   *
   * <p>Rejecting only a following <em>digit</em> was too narrow: it left every other continuation
   * of the line-number token reading as a whole match. {@code src/A.java:10-12} (a maintainer
   * naming a range) and {@code src/A.java:10x} both cleared the finding at line 10, and a locator
   * whose printed form the summary never emits is not a naming this hatch may act on. The summary
   * prints {@code path:line} followed by a space, a backtick or an em dash, and the documented
   * directive form ({@code @thrillhousebot resolved path/to/File.java:42 — <title>}) is spelled the
   * same way, so no legitimate naming ends in one of these characters — under-clearing here only
   * leaves the finding held for one more round, which is the safe direction.
   *
   * <p>One spaced-range reading is overruled: the documented {@code path:line — <title>} form with
   * a title that opens with a digit ({@code src/A.java:1 — 2 call sites of this SQL injection}) is
   * spelled exactly like a spaced range, and it is the very form the summary prints (#653). Only
   * the clearing path can tell the two apart, because only it holds the finding: when the em dash
   * the summary prints is followed by this finding's full {@code anchor}, the comment is the
   * printed row itself — a range's end line is a number, not the finding's title — so the match
   * counts ({@link #separatorLeadsOwnContent}).
   */
  private static boolean namesLocator(String body, String locator, String anchor) {
    for (var at = body.indexOf(locator); at >= 0; at = body.indexOf(locator, at + 1)) {
      var after = at + locator.length();
      if (after >= body.length()) {
        return true;
      }
      if (!continuesLocator(body, after)
          && (!startsSpacedRange(body, after) || separatorLeadsOwnContent(body, after, anchor))) {
        return true;
      }
    }
    return false;
  }

  /** The separator the summary prints between a finding's locator and its title: the em dash. */
  private static final char PRINTED_SEPARATOR = '—';

  /**
   * Whether the text after a locator reads as the summary's own printed row: the em dash separator
   * followed by this finding's full content anchor, exactly as printed. Only {@link
   * #PRINTED_SEPARATOR} qualifies — a spaced hyphen or en dash is how a range is typed, and a range
   * whose end line happens to equal the title's leading digits ({@code :1 - 2 call sites …} for a
   * finding titled {@code 2 call sites …}) must stay a range; the summary never prints those
   * separators, so refusing them costs no printed-form naming. {@code ..} is git range syntax and
   * never the printed separator either, so a dotted range stays a range whatever follows it.
   *
   * <p>Only called on a spaced-range reading, which puts a separator character past the spacing —
   * {@code skipSpacing} is deterministic, so {@code at} lands on it and never past the end.
   */
  private static boolean separatorLeadsOwnContent(String body, int after, String anchor) {
    var at = skipSpacing(body, after);
    if (body.charAt(at) != PRINTED_SEPARATOR) {
      return false;
    }
    at = skipSpacing(body, at + 1);
    return body.startsWith(anchor, at);
  }

  /**
   * A line range whose separator is not adjacent to the line number: {@code :1 - 3}, {@code :1 –
   * 3}, {@code :1..3}. {@link #continuesLocator} catches only the adjacent spellings, and a range
   * is written both ways about equally often.
   *
   * <p>The trailing digit is what tells a range apart from the documented {@code path:line —
   * <title>} separator, which is spelled identically up to that point, so it is required rather
   * than optional: {@code :1 — SQL injection} still clears the finding. A title that opens with a
   * digit ({@code :1 — 2 call sites}) therefore reads as a range here; the clearing path recovers
   * that case by matching the text after the separator against the finding's own title ({@link
   * #separatorLeadsOwnContent}), and where no finding set is available the rejection stays — an
   * under-clear holds the finding one more round, whereas an over-clear drops it silently.
   *
   * <p>Written as a scan rather than a pattern so the separator test is literally {@link #isDash},
   * the one {@link #continuesLocator} applies to the adjacent spelling. An enumerated character
   * class drifts from the category test the moment either is edited, and the two guards disagreeing
   * about the same character means a dash rejected adjacent is accepted spaced — the over-clear
   * this exists to stop, arrived at through the fix for it.
   */
  private static boolean startsSpacedRange(String body, int after) {
    var at = skipSpacing(body, after);
    if (at < body.length() && isDash(body.charAt(at))) {
      at++;
    } else if (at + 1 < body.length() && body.charAt(at) == '.' && body.charAt(at + 1) == '.') {
      // The whole run, not two: 1...3 is git's own triple-dot range, and stopping at two would
      // leave the third dot where the end line is expected and read the range as a whole locator.
      while (at < body.length() && body.charAt(at) == '.') {
        at++;
      }
    } else {
      return false;
    }
    at = skipSpacing(body, at);
    return at < body.length() && Character.isDigit(body.charAt(at));
  }

  /**
   * Index of the first character at or after {@code from} that is not range spacing.
   *
   * <p>Spacing is every horizontal space, not just the ASCII one: a no-break space or a narrow
   * no-break space reaches comment text through copy-paste and locale-aware autocorrect, and a
   * separator this does not step over stops reading as a range and clears a finding the comment
   * only named the start of. Line terminators are deliberately excluded even though {@link
   * Character#isWhitespace} counts them — a {@code - 3} on the next line is a markdown list item,
   * and reading it as a range would under-clear on ordinary prose rather than on a range.
   */
  private static int skipSpacing(String body, int from) {
    var at = from;
    var limit = Math.min(body.length(), from + MAX_RANGE_SPACING);
    while (at < limit) {
      var c = body.codePointAt(at);
      if (!isHorizontalSpace(c)) {
        break;
      }
      at += Character.charCount(c);
    }
    return at;
  }

  /**
   * Whether code point {@code c} spaces text apart on one line. {@code isSpaceChar} misses only the
   * tab.
   *
   * <p>Format characters ({@code Cf}) count as spacing here, the same category {@link
   * #continuesLocator} counts as a continuation: an invisible character inside a range's spacing
   * ({@code :1 -<U+200B>3}) otherwise fails the trailing-digit test, the range stops being
   * recognized, and {@code :1} reads as a whole locator — the over-clear direction. Stepping over
   * it keeps the range reading, which at worst under-clears.
   */
  private static boolean isHorizontalSpace(int c) {
    return Character.isSpaceChar(c) || c == '\t' || Character.getType(c) == Character.FORMAT;
  }

  /**
   * Whether {@code c} extends the locator's line-number token rather than ending it: any letter or
   * digit, the {@code _} an identifier continues with, or any dash a line range continues with.
   *
   * <p>Every dash counts, not just the ASCII hyphen: smart-punctuation autocorrect rewrites a typed
   * {@code 1-3} to an en dash, and a range copied out of prose can carry an em dash or a minus
   * sign. Missing one of those clears a finding the comment only named the start of, which is the
   * over-clear direction this guard exists to stop; treating a dash that turns out not to be a
   * range as a continuation only under-clears, and the maintainer can say so again.
   *
   * <p>Format characters ({@code Cf}: a zero-width space or joiner, a soft hyphen, a BOM) count as
   * continuations too. They are neither letters nor dashes nor {@code Zs} space, so without this a
   * U+200B between the line number and the range dash left {@code :1} reading as a whole locator,
   * over-clearing the finding at line 1 even though the comment names a range. A soft hyphen is
   * what some editors insert at a hyphenation point, and the zero-width characters arrive by
   * copy-paste from rendered pages; reading them as continuations errs safe — the locator stops
   * being whole and the finding is held.
   *
   * <p>Classified by code point, not UTF-16 char: a supplementary-plane {@code Cf} such as U+E0001
   * arrives as a surrogate pair, and {@code Character.getType(char)} on either half reports a
   * surrogate rather than {@code FORMAT}, which would let an astral format character end the token
   * and over-clear the same way.
   */
  private static boolean continuesLocator(String body, int at) {
    var c = body.codePointAt(at);
    return Character.isLetterOrDigit(c)
        || c == '_'
        || isDash(c)
        || Character.getType(c) == Character.FORMAT;
  }

  /**
   * Whether {@code c} is a dash a line range may be written with — the whole {@code Pd} category
   * plus the minus sign, which Unicode files under {@code Sm} but readers and autocorrect treat as
   * a hyphen.
   *
   * <p>The category test rather than an enumeration: {@code Pd} holds a fullwidth hyphen-minus and
   * a wave dash as well as the four dashes anyone lists from memory, and a range typed on a CJK
   * keyboard is not a rarer input than one carrying an en dash from smart punctuation. Both places
   * that ask this question call here, so neither can drift into accepting a dash the other rejects.
   */
  private static boolean isDash(int c) {
    return Character.getType(c) == Character.DASH_PUNCTUATION || c == '−';
  }

  /** A non-bot conversation comment with a body and a write-capable author association. */
  private static boolean isMaintainerConversationComment(
      GitHubCommentClient.IssueComment comment, BotIdentity botIdentity) {
    return comment != null
        && comment.body() != null
        && comment.user() != null
        && !botIdentity.matches(comment.user().login())
        && mayHoldWriteAccess(comment.authorAssociation());
  }

  /**
   * The text that identifies a finding in prose: its title, or its description when it has no
   * usable title (the same fallback {@link #bodyCarriesOwnContent} uses, for the same reason — the
   * bot renders a null title as the literal {@code "null"}, which names nothing). {@code null} when
   * the finding carries neither, so it can never be cleared by content it does not have.
   */
  private static String ownContentAnchor(ReviewResponse.Finding finding) {
    String title = finding.title();
    if (title != null && !title.isBlank()) {
      return title;
    }
    String description = finding.description();
    return description == null || description.isBlank() ? null : description;
  }

  /**
   * Rewrites a model-reported {@code unresolved} to {@code resolved} when a maintainer cleared that
   * finding from the PR conversation ({@link #clearedInConversation}). The model never sees the
   * conversation, so without this pass a finding the maintainer explicitly closed keeps being
   * reported unresolved and keeps holding APPROVE — the same dead end the backstop had (#548).
   *
   * <p>Only {@code unresolved} entries are touched: a {@code justified}/{@code resolved}/{@code
   * superseded} verdict is already an accounting, and an id outside the prior round names no
   * finding to check.
   */
  public List<ReviewResponse.PreviousFindingStatus> clearNamedInConversation(
      List<ReviewResponse.Finding> previous,
      List<ReviewResponse.PreviousFindingStatus> statuses,
      List<GitHubCommentClient.IssueComment> conversationComments,
      BotIdentity botIdentity) {
    if (statuses == null || statuses.isEmpty()) {
      return statuses == null ? List.of() : statuses;
    }
    if (previous == null
        || previous.isEmpty()
        || conversationComments == null
        || conversationComments.isEmpty()) {
      return statuses;
    }
    var rewritten = new ArrayList<ReviewResponse.PreviousFindingStatus>(statuses.size());
    for (var status : statuses) {
      var id = status.id();
      if (STATUS_UNRESOLVED.equalsIgnoreCase(status.status())
          && id >= 1
          && id <= previous.size()
          && clearedInConversation(previous.get(id - 1), conversationComments, botIdentity)) {
        rewritten.add(
            new ReviewResponse.PreviousFindingStatus(
                id, STATUS_RESOLVED, conversationClearedNote(botIdentity)));
      } else {
        rewritten.add(status);
      }
    }
    return rewritten;
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
    if (previous == null || previous.isEmpty()) {
      return threads;
    }
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
   * Rewrites a model-reported {@code unresolved} status to {@link #STATUS_SUPERSEDED} when the
   * prior finding's flagged code is no longer in the current diff (its file left the diff or the
   * anchored hunk vanished, typically after a force-push). The model reports on the <em>prior</em>
   * round's findings and can hold one open even though the code it targeted no longer exists;
   * presence is judged deterministically via {@link DiffLineResolver#isFindingPresent} on the
   * finding's persisted {@code suggestion_old} anchor — the same predicate the approve backstop
   * uses — so a vanished finding no longer blocks APPROVE.
   *
   * <p>A finding without a file cannot be placed in the diff at all, so its status is left
   * untouched — clearing is the dangerous direction, and "cannot verify" must not read as "gone".
   * Statuses other than {@code unresolved}, or with an id outside the prior round, also pass
   * through unchanged.
   */
  public List<ReviewResponse.PreviousFindingStatus> supersedeVanished(
      String previousAiResponseJson,
      List<ReviewResponse.PreviousFindingStatus> statuses,
      DiffLineResolver lineResolver) {
    return supersedeVanished(previousAiResponseJson, statuses, lineResolver, Map.of());
  }

  /** Rename-aware variant used by the review verdict path. */
  public List<ReviewResponse.PreviousFindingStatus> supersedeVanished(
      String previousAiResponseJson,
      List<ReviewResponse.PreviousFindingStatus> statuses,
      DiffLineResolver lineResolver,
      Map<String, String> renameTargets) {
    return supersedeVanished(
        previousAiResponseJson, statuses, lineResolver, renameTargets, Set.of());
  }

  /**
   * Rename- and settle-aware variant: a finding a newer round already closed ({@code settledIds},
   * {@link #settledPreviousIds}) is left untouched rather than rewritten to {@code superseded} when
   * its code later leaves the diff — the close already accounted for it, and a phantom supersede
   * would pin {@code hasSupersededPrevious} on and re-post the summary every push (#470).
   */
  public List<ReviewResponse.PreviousFindingStatus> supersedeVanished(
      String previousAiResponseJson,
      List<ReviewResponse.PreviousFindingStatus> statuses,
      DiffLineResolver lineResolver,
      Map<String, String> renameTargets,
      Set<Integer> settledIds) {
    if (statuses == null || statuses.isEmpty() || lineResolver == null) {
      return statuses == null ? List.of() : statuses;
    }
    var previous = parsePreviousFindings(previousAiResponseJson);
    if (previous.isEmpty()) {
      return statuses;
    }
    var rewritten = new ArrayList<ReviewResponse.PreviousFindingStatus>(statuses.size());
    for (var status : statuses) {
      if (!settledIds.contains(status.id())
          && hasVanished(status, previous, lineResolver, renameTargets)) {
        Log.infof(
            "Superseding unresolved previous finding #%d — its targeted code is no longer in the"
                + " current diff",
            status.id());
        rewritten.add(
            new ReviewResponse.PreviousFindingStatus(
                status.id(), STATUS_SUPERSEDED, SUPERSEDED_NOTE));
      } else {
        rewritten.add(status);
      }
    }
    return rewritten;
  }

  /**
   * Adds deterministic statuses when the model omits a prior finding entirely. Vanished findings
   * become superseded; findings moved by a content-identical pure rename remain unresolved because
   * their flagged code is unchanged. This keeps the regenerated summary and approval gate in sync
   * even when {@code previous_findings_status} is empty.
   */
  public List<ReviewResponse.PreviousFindingStatus> addUnreportedVanished(
      List<ReviewResponse.Finding> previous,
      List<ReviewResponse.PreviousFindingStatus> statuses,
      DiffLineResolver lineResolver,
      Map<String, String> renameTargets) {
    return addUnreportedVanished(previous, statuses, lineResolver, renameTargets, Set.of());
  }

  /**
   * Settle-aware variant: a prior finding a newer round already closed ({@code settledIds}, {@link
   * #settledPreviousIds}) is not re-emitted here — neither superseded when its code left the diff
   * nor held as unresolved — because the close already accounted for it. Re-superseding it pins
   * {@code hasSupersededPrevious} on and re-posts the summary every push while suppressing the
   * follow-up delta (#470). Its id slot is still skipped by position, so no id shifts.
   */
  public List<ReviewResponse.PreviousFindingStatus> addUnreportedVanished(
      List<ReviewResponse.Finding> previous,
      List<ReviewResponse.PreviousFindingStatus> statuses,
      DiffLineResolver lineResolver,
      Map<String, String> renameTargets,
      Set<Integer> settledIds) {
    if (previous == null || previous.isEmpty() || lineResolver == null) {
      return statuses == null ? List.of() : statuses;
    }
    var result = new ArrayList<>(statuses == null ? List.of() : statuses);
    var reportedIds = new HashSet<Integer>();
    for (var status : result) {
      reportedIds.add(status.id());
    }
    for (int index = 0; index < previous.size(); index++) {
      var id = index + 1;
      var finding = previous.get(index);
      if (reportedIds.contains(id) || settledIds.contains(id) || finding.file() == null) {
        continue;
      }
      var currentPath = renameTargets.getOrDefault(finding.file(), finding.file());
      if (currentPath.isBlank()) {
        // A content-identical rename is deliberately absent from the reviewable diff. The anchor
        // therefore cannot be resolved at either path, but the unchanged finding must keep holding
        // approval when the model silently omits it.
        result.add(
            new ReviewResponse.PreviousFindingStatus(
                id, STATUS_UNRESOLVED, PURE_RENAME_UNRESOLVED_NOTE));
      } else if (!lineResolver.isFindingPresent(currentPath, finding.suggestionOld())) {
        result.add(
            new ReviewResponse.PreviousFindingStatus(id, STATUS_SUPERSEDED, SUPERSEDED_NOTE));
      }
    }
    return result;
  }

  private static boolean hasVanished(
      ReviewResponse.PreviousFindingStatus status,
      List<ReviewResponse.Finding> previous,
      DiffLineResolver lineResolver,
      Map<String, String> renameTargets) {
    if (!STATUS_UNRESOLVED.equalsIgnoreCase(status.status())) {
      return false;
    }
    var id = status.id();
    if (id < 1 || id > previous.size()) {
      return false;
    }
    var finding = previous.get(id - 1);
    if (finding.file() == null) {
      return false;
    }
    var currentPath = renameTargets.getOrDefault(finding.file(), finding.file());
    if (currentPath.isBlank()) {
      return false;
    }
    return !lineResolver.isFindingPresent(currentPath, finding.suggestionOld());
  }

  /**
   * Re-checks a maintainer's decline against the code before it is recorded {@code justified}. The
   * model reports a decline as an outcome; this step treats it as a <em>claim</em>. When the code
   * the review actually saw plainly contradicts the rebuttal's premise ({@link
   * RebuttalContradiction}), the status is rewritten back to {@code unresolved} with a one-line
   * note quoting both the claim and the contradicting line — so a correct finding is not closed by
   * an incorrect rebuttal, and only declines that survive the re-check are safe to remember.
   *
   * <p>Trusting the maintainer stays the default; every leg below must hold before an override
   * fires, and any one of them missing leaves the {@code justified} status untouched:
   *
   * <ul>
   *   <li>the re-check is enabled ({@code thrillhousebot.review.decline-recheck-enabled});
   *   <li>the finding's own thread is identifiable and carries <em>exactly one</em> maintainer
   *       reply. One reply is the decline, and the re-check pushes back once; a second human reply
   *       is the maintainer answering that push-back, and it always wins — that is the guaranteed
   *       escape hatch, and it is why the override cannot recur round after round;
   *   <li>{@link RebuttalContradiction} finds a contradiction, which it only reports for a premise
   *       refutable from code text. A rebuttal about style, intent, accepted risk, or priority
   *       matches nothing and keeps the decline.
   * </ul>
   *
   * <p>Overridden findings re-enter the ordinary {@code unresolved} path — they hold APPROVE
   * exactly like a model-reported unresolved status and are never re-posted as new findings, so the
   * maintainer is not asked to answer the same comment twice.
   *
   * @param reviewedCode supplies the diff text the review call saw; resolved lazily because most
   *     rounds have no declined finding at all
   */
  public List<ReviewResponse.PreviousFindingStatus> recheckDeclines(
      List<ReviewResponse.Finding> previous,
      List<ReviewResponse.PreviousFindingStatus> statuses,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity,
      Supplier<String> reviewedCode) {
    if (statuses == null || statuses.isEmpty()) {
      return statuses == null ? List.of() : statuses;
    }
    if (!declineRecheckEnabled || !hasDecline(statuses)) {
      return statuses;
    }
    // An empty prior round and an empty comment list need no fast path of their own: the
    // id-range check and the thread lookup below already yield "no contradiction" for both.
    String code = reviewedCode == null ? null : reviewedCode.get();
    if (previous == null || inlineComments == null || code == null || code.isBlank()) {
      return statuses;
    }
    var rewritten = new ArrayList<ReviewResponse.PreviousFindingStatus>(statuses.size());
    for (var status : statuses) {
      var contradiction = declineContradiction(status, previous, inlineComments, botIdentity, code);
      if (contradiction == null) {
        rewritten.add(status);
        continue;
      }
      Log.infof(
          "Re-opening previous finding #%d: the maintainer's decline claims '%s' but the reviewed"
              + " code shows '%s'",
          status.id(), contradiction.claim(), contradiction.evidence());
      rewritten.add(
          new ReviewResponse.PreviousFindingStatus(
              status.id(), STATUS_UNRESOLVED, contradiction.note()));
    }
    return rewritten;
  }

  /** Whether any status is a maintainer decline — the only kind this re-check looks at. */
  private static boolean hasDecline(List<ReviewResponse.PreviousFindingStatus> statuses) {
    return statuses.stream().anyMatch(s -> STATUS_JUSTIFIED.equalsIgnoreCase(s.status()));
  }

  /**
   * The contradiction that disqualifies a {@code justified} status, or {@code null} when the
   * decline stands. Returning {@code null} is the conservative outcome and is what every unmatched,
   * absent, or ambiguous input produces.
   */
  private static RebuttalContradiction.Contradiction declineContradiction(
      ReviewResponse.PreviousFindingStatus status,
      List<ReviewResponse.Finding> previous,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity,
      String reviewedCode) {
    if (!STATUS_JUSTIFIED.equalsIgnoreCase(status.status())) {
      return null;
    }
    var id = status.id();
    if (id < 1 || id > previous.size()) {
      return null;
    }
    var finding = previous.get(id - 1);
    Long rootId = rootCommentId(finding, id, inlineComments, botIdentity);
    if (rootId == null) {
      return null;
    }
    var humanReplies = humanReplies(rootId, inlineComments, botIdentity);
    if (humanReplies.size() != 1) {
      return null;
    }
    return RebuttalContradiction.find(finding, humanReplies.get(0), reviewedCode).orElse(null);
  }

  /**
   * Bodies of the maintainer replies on a thread, oldest first; bot replies are not rebuttals, and
   * neither is a reply from an author without write access ({@link #mayHoldWriteAccess}) — a
   * fork-PR author cannot supply the rebuttal the decline re-check reads.
   */
  private static List<String> humanReplies(
      Long rootId,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      BotIdentity botIdentity) {
    return inlineComments.stream()
        .filter(c -> rootId.equals(c.inReplyToId()))
        .filter(c -> c.user() != null && !botIdentity.matches(c.user().login()))
        .filter(c -> mayHoldWriteAccess(c.authorAssociation()))
        .map(GitHubReviewClient.PullRequestComment::body)
        .filter(body -> body != null && !body.isBlank())
        .toList();
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
   * #dropRepliedDuplicates}), as does the model marking the finding resolved/justified, as does an
   * {@code @thrillhousebot resolved} comment naming the finding on the PR conversation — the hatch
   * for a finding that has no thread to reply on ({@link #clearedInConversation}).
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
   * persisted round is not re-parsed. Rename-blind overload; the verdict path uses the {@code
   * renameTargets} variant below.
   */
  public List<ReviewResult.PreviousFindingStatus> unreportedUnresolvedStatusesFromParsed(
      List<ReviewResponse> priorAiResponses,
      List<ReviewResponse.PreviousFindingStatus> currentStatuses,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      DiffLineResolver lineResolver,
      BotIdentity botIdentity) {
    return unreportedUnresolvedStatusesFromParsed(
        priorAiResponses, currentStatuses, inlineComments, lineResolver, botIdentity, Map.of());
  }

  /**
   * Rename-aware variant used by the review verdict path. A still-present finding whose file was
   * renamed-and-edited lives at {@code renameTargets.get(finding.file())} in the current diff, not
   * at its pre-rename path; {@link #supersedeVanished}/{@link #addUnreportedVanished} already map
   * through {@code renameTargets}, so the backstop's presence check must too — otherwise a finding
   * moved by a rename escapes both gates and sails past APPROVE (F5 — the sibling defect of F2,
   * where one of two paths got the guard and the twin did not).
   */
  public List<ReviewResult.PreviousFindingStatus> unreportedUnresolvedStatusesFromParsed(
      List<ReviewResponse> priorAiResponses,
      List<ReviewResponse.PreviousFindingStatus> currentStatuses,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      DiffLineResolver lineResolver,
      BotIdentity botIdentity,
      Map<String, String> renameTargets) {
    return unreportedUnresolvedStatusesFromParsed(
        priorAiResponses,
        currentStatuses,
        inlineComments,
        List.of(),
        lineResolver,
        botIdentity,
        renameTargets);
  }

  /**
   * Conversation-aware variant used by the review verdict path. A finding that never posted inline
   * ({@link Finding#postsInline}) has no thread for the reply hatch to reach, so its only
   * maintainer-driven escape is an {@code @thrillhousebot resolved} comment on the PR conversation
   * ({@link #clearedInConversation}); without it the backstop holds such a finding every round with
   * no action able to clear it (#548).
   */
  public List<ReviewResult.PreviousFindingStatus> unreportedUnresolvedStatusesFromParsed(
      List<ReviewResponse> priorAiResponses,
      List<ReviewResponse.PreviousFindingStatus> currentStatuses,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<GitHubCommentClient.IssueComment> conversationComments,
      DiffLineResolver lineResolver,
      BotIdentity botIdentity,
      Map<String, String> renameTargets) {
    if (priorAiResponses == null || priorAiResponses.isEmpty() || lineResolver == null) {
      return List.of();
    }
    var chrono = toChronological(priorAiResponses);
    var open = openFindingsAcrossRounds(chrono, currentStatuses);
    var clusters = clusterByIdentity(open);
    return heldFromClusters(
        clusters, inlineComments, conversationComments, lineResolver, botIdentity, renameTargets);
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
   * the next round's status addressed, and {@code currentStatuses} — which reports on the round
   * {@link #effectivePreviousFindings} named — drops everything the current round accounted for.
   *
   * <p>The round a {@code previous_findings_status} block reports on is the newest <em>earlier</em>
   * round that actually raised findings, not simply the round before it. A round that found nothing
   * exposes no ids to reference, so the next round is shown — and reports on — the last round that
   * did. Pairing a status block with an empty round instead left every id unmappable, so nothing
   * ever closed and the held set drifted upward with each further round (#455).
   */
  private Map<String, OpenFinding> openFindingsAcrossRounds(
      List<ReviewResponse> chrono, List<ReviewResponse.PreviousFindingStatus> currentStatuses) {
    var open = new LinkedHashMap<String, OpenFinding>();
    var reportedRound = List.<ReviewResponse.Finding>of();
    for (var round : chrono) {
      closeAddressed(open, reportedRound, round.previousFindingsStatus());
      addOpenFindings(open, round.findings());
      if (!round.findings().isEmpty()) {
        reportedRound = round.findings();
      }
    }
    closeReported(open, reportedRound, currentStatuses);
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
      List<GitHubCommentClient.IssueComment> conversationComments,
      DiffLineResolver lineResolver,
      BotIdentity botIdentity,
      Map<String, String> renameTargets) {
    var held = new ArrayList<ReviewResult.PreviousFindingStatus>();
    for (var cluster : clusters) {
      OpenFinding target =
          holdableTarget(
              cluster,
              inlineComments,
              conversationComments,
              lineResolver,
              botIdentity,
              renameTargets);
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
   * The first still-present member of the cluster to hold, or {@code null} when its code is gone, a
   * maintainer has replied on its thread, or a maintainer cleared it from the PR conversation
   * ({@link #clearedInConversation} — the only hatch a finding with no thread has, #548). The reply
   * is located by the round-relative marker ({@link OpenFinding#id()}) plus the finding's own
   * content rather than by title, so a null-title finding's thread is still seen and a thread-less
   * finding cannot bind to a different finding that reused the same marker index in another round.
   *
   * <p>Presence is resolved through {@code renameTargets} the same way {@link #hasVanished} does:
   * the finding's flagged code lives at its rename target, not its pre-rename path, when the file
   * was renamed-and-edited (F5).
   */
  private OpenFinding holdableTarget(
      List<OpenFinding> cluster,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<GitHubCommentClient.IssueComment> conversationComments,
      DiffLineResolver lineResolver,
      BotIdentity botIdentity,
      Map<String, String> renameTargets) {
    OpenFinding target = null;
    boolean answered = false;
    for (var member : cluster) {
      var finding = member.finding();
      if (target == null && isStillPresent(finding, lineResolver, renameTargets)) {
        target = member;
      }
      if (answeredRootComment(finding, member.id(), inlineComments, botIdentity) != null
          || clearedInConversation(finding, conversationComments, botIdentity)) {
        answered = true;
      }
    }
    return answered ? null : target;
  }

  /**
   * Whether a finding's flagged code is still in the current diff, resolving a renamed-and-edited
   * file to its rename target as {@link #hasVanished} does. A content-identical pure rename (blank
   * target) leaves the anchor resolvable at neither path but the finding unchanged, so it counts as
   * present — the same way {@link #addUnreportedVanished} keeps it {@code unresolved}.
   *
   * <p>The {@code file() == null} guard is defensive: {@link #holdableTarget} only passes cluster
   * members, and {@link #addOpenFindings} admits a finding to a cluster only when {@link
   * #findingKey} is non-null — which it never is for a null-file finding — so no production caller
   * can reach it with a null file. Package-private (not {@code private}) so that contract can be
   * pinned directly by a unit test rather than left as an unreachable branch.
   */
  static boolean isStillPresent(
      ReviewResponse.Finding finding,
      DiffLineResolver lineResolver,
      Map<String, String> renameTargets) {
    if (finding.file() == null) {
      return false;
    }
    var currentPath = renameTargets.getOrDefault(finding.file(), finding.file());
    if (currentPath.isBlank()) {
      return true;
    }
    return lineResolver.isFindingPresent(currentPath, finding.suggestionOld());
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
   * unusable input — the backstop replay needs both lists, and the compact constructor of {@link
   * ReviewResponse} guarantees non-null copies. Never returns null: a stored body that is the JSON
   * literal {@code null} is syntactically valid, so Jackson returns Java null without throwing, and
   * callers copy the result into immutable collections that reject null elements.
   */
  ReviewResponse parseResponse(String aiResponseJson) {
    if (aiResponseJson == null || aiResponseJson.isBlank()) {
      return EMPTY_RESPONSE;
    }
    try {
      var parsed = mapper.readValue(aiResponseJson, ReviewResponse.class);
      if (parsed == null) {
        Log.warn("Previous AI response deserialized to null, falling back to review body context");
        return EMPTY_RESPONSE;
      }
      return parsed;
    } catch (JsonProcessingException e) {
      Log.warn("Could not parse previous AI response, falling back to review body context", e);
      return EMPTY_RESPONSE;
    }
  }

  /**
   * Fallback context built from the last bot review body, for sessions without a persisted AI
   * response. The body carries no structured findings, so this is best-effort only — and a body the
   * bot generated about its own verdict carries nothing at all, so it is dropped rather than
   * offered to the model as a prior finding ({@link #isSelfAuthoredStatusBody}).
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
    if (body == null || isSelfAuthoredStatusBody(body)) {
      return "";
    }
    return body;
  }

  /**
   * Whether a bot review body is the bot's own verdict prose rather than review content. The prompt
   * frames whatever this fallback returns as "the following issues were flagged in the previous
   * review … determine if each is resolved, unresolved, or justified", so a sentence the bot wrote
   * about its own verdict would be handed back to it as an issue to account for, and re-counted
   * every round (#455). The bot must never treat its own output as its input.
   *
   * <p>Recognition is by the body's first non-blank line against the producers' own constants — the
   * unresolved-previous status sentence, the clean-review message, the two CI-hold notices, and the
   * partial-review banner — so the check cannot drift from the text it recognizes. A body a human
   * (or an older, findings-carrying review) wrote starts with none of them and is preserved.
   */
  static boolean isSelfAuthoredStatusBody(String body) {
    var first = body.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst();
    if (first.isEmpty()) {
      return true;
    }
    var line = first.get();
    return ReviewResult.isUnresolvedPreviousMessage(line)
        || line.startsWith(ReviewResult.NO_ISSUES_CI_PENDING_LEAD_IN)
        || line.startsWith(ReviewResult.NO_ISSUES_CI_UNREADABLE_LEAD_IN)
        || line.startsWith(ReviewResult.TRUNCATION_NOTICE_LEAD_IN)
        || line.startsWith(ZERO_ISSUES_FIRST_LINE);
  }

  /**
   * First line of {@link PrSummaryGenerator#ZERO_ISSUES_MESSAGE} — the clean-review celebration.
   */
  private static final String ZERO_ISSUES_FIRST_LINE =
      PrSummaryGenerator.ZERO_ISSUES_MESSAGE.lines().findFirst().orElse("");

  /**
   * Converts AI response's previous_findings_status into ReviewResult statuses, keeping only the
   * recognized values (resolved / justified / unresolved) plus the synthetic {@link
   * #STATUS_SUPERSEDED} that {@link #supersedeVanished} rewrites in. An unrecognized status is
   * meaningless noise that no count or gate acts on, and for a still-open finding the backstop
   * already emits a synthetic {@code "unresolved"} for that id — passing the raw value through too
   * would leave two entries with the same id in the result. Dropping it keeps {@code
   * previousStatuses} one-per-id and matches the backstop's recognized-status contract.
   */
  public List<ReviewResult.PreviousFindingStatus> toStatuses(
      List<ReviewResponse.PreviousFindingStatus> aiStatuses) {
    if (aiStatuses == null) return List.of();

    var result = new ArrayList<ReviewResult.PreviousFindingStatus>();
    for (var s : aiStatuses) {
      if (isRecognizedStatus(s.status()) || STATUS_SUPERSEDED.equalsIgnoreCase(s.status())) {
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
