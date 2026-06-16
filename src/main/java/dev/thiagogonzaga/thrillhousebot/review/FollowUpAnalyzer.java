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
   * for a finding — see {@link #isRecognizedStatus} and #131.
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
      String botLogin) {
    return buildPreviousFindingsContext(previousAiResponseJson, priorReviews, List.of(), botLogin);
  }

  /**
   * Variant that also renders the reply threads under each finding's inline comment, so the model
   * can weigh maintainer responses (fix pushed, intentional, disputed) when assigning statuses.
   */
  public String buildPreviousFindingsContext(
      String previousAiResponseJson,
      List<GitHubReviewClient.ReviewResponse> priorReviews,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      String botLogin) {
    return buildPreviousFindingsContext(
        previousAiResponseJson, priorReviews, inlineComments, List.of(), botLogin);
  }

  /**
   * Variant that additionally renders findings from review rounds older than the previous one.
   * Sessions only carry the immediately previous response, so a finding a maintainer answered two
   * rounds ago would otherwise fall out of context and be rediscovered as new — dogfooding showed
   * exactly that, with the severity drifting upward on each rediscovery. Older answered findings
   * are listed unnumbered, outside previous_findings_status.
   */
  public String buildPreviousFindingsContext(
      String previousAiResponseJson,
      List<GitHubReviewClient.ReviewResponse> priorReviews,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<String> olderAiResponseJsons,
      String botLogin) {
    var structured = formatStructuredFindings(previousAiResponseJson, inlineComments, botLogin);
    var answered = formatAnsweredEarlier(olderAiResponseJsons, inlineComments, botLogin);
    if (!structured.isEmpty()) {
      return structured + answered;
    }
    var fallback = buildPreviousFindingsContext(priorReviews, botLogin);
    return fallback + answered;
  }

  /**
   * Unnumbered list of findings from rounds before the previous one whose threads carry a human
   * reply. These were answered once; the model must not raise them again or include them in
   * previous_findings_status.
   */
  private String formatAnsweredEarlier(
      List<String> olderAiResponseJsons,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      String botLogin) {
    if (olderAiResponseJsons == null || olderAiResponseJsons.isEmpty()) {
      return "";
    }
    var sb = new StringBuilder();
    var seen = new HashSet<String>();
    for (String json : olderAiResponseJsons) {
      for (var finding : parsePreviousFindings(json)) {
        appendAnsweredEntry(sb, seen, finding, inlineComments, botLogin);
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
      String botLogin) {
    if (finding.file() == null || finding.title() == null) {
      return;
    }
    if (!seen.add(finding.file() + "#" + finding.title())) {
      return;
    }
    Long rootId = answeredRootComment(finding, inlineComments, botLogin);
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
      String aiResponseJson,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      String botLogin) {
    var previous = parsePreviousFindings(aiResponseJson);
    if (previous.isEmpty()) {
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
      appendThreadReplies(sb, finding, id, inlineComments, botLogin);
      id++;
    }
    return sb.toString();
  }

  private static void appendThreadReplies(
      StringBuilder sb,
      ReviewResponse.Finding finding,
      int findingId,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      String botLogin) {
    Long rootId = rootCommentId(finding, findingId, inlineComments, botLogin);
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
      String botLogin) {
    // Markers reuse the same indices every review round, so a PR with several rounds carries
    // several comments with this exact marker. Restricting to the finding's file and taking the
    // newest match binds the latest round's findings to their own threads, not a previous
    // round's thread that happens to share the index.
    String marker = SuggestionFormatter.findingMarker(findingId);
    var markerMatches =
        botRootComments(inlineComments, botLogin)
            .filter(c -> c.body() != null && c.body().contains(marker))
            .filter(c -> finding.file() == null || FilePaths.same(finding.file(), c.path()))
            .map(GitHubReviewClient.PullRequestComment::id)
            .toList();
    if (!markerMatches.isEmpty()) {
      return markerMatches.get(markerMatches.size() - 1);
    }
    return rootCommentByTitle(finding, inlineComments, botLogin);
  }

  /**
   * Marker-free match for the latest round's findings: the newest bot root comment on the finding's
   * file whose body carries its title. The same title can exist once per review round, and the
   * newest comment is the one belonging to the round being analyzed.
   */
  private static Long rootCommentByTitle(
      ReviewResponse.Finding finding,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      String botLogin) {
    List<Long> matches = rootCommentsByTitle(finding, inlineComments, botLogin);
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
      String botLogin) {
    for (Long rootId : rootCommentsByTitle(finding, inlineComments, botLogin)) {
      if (hasHumanReply(rootId, inlineComments, botLogin)) {
        return rootId;
      }
    }
    return null;
  }

  /** All bot root comments matching the finding's file and title, oldest first. */
  private static List<Long> rootCommentsByTitle(
      ReviewResponse.Finding finding,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      String botLogin) {
    if (finding.title() == null || finding.file() == null) {
      return List.of();
    }
    return botRootComments(inlineComments, botLogin)
        .filter(c -> FilePaths.same(finding.file(), c.path()))
        .filter(c -> c.body() != null && c.body().contains(finding.title()))
        .map(GitHubReviewClient.PullRequestComment::id)
        .toList();
  }

  private static Stream<GitHubReviewClient.PullRequestComment> botRootComments(
      List<GitHubReviewClient.PullRequestComment> inlineComments, String botLogin) {
    return inlineComments.stream()
        .filter(c -> c.inReplyToId() == null)
        .filter(c -> c.user() != null && botLogin.equals(c.user().login()));
  }

  /** Lines a finding may drift between revisions and still count as the same location. */
  static final int DUPLICATE_LINE_TOLERANCE = 3;

  /**
   * Deterministic backstop for re-raised findings: drops a new finding when a finding from ANY
   * prior round at the same location already has a maintainer reply on its thread. The prompt
   * forbids re-raising prior findings, but the model occasionally disobeys when it privately
   * disagrees with the reply — the human answered once and should not have to answer again. A prior
   * finding counts as the same when the severity matches or the titles describe the same defect;
   * matching on severity alone let a re-raise through by simply escalating its rating, which
   * dogfooding observed (medium re-raised as high after the reply).
   */
  public ReviewResponse dropRepliedDuplicates(
      ReviewResponse response,
      List<String> priorAiResponseJsons,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      String botLogin) {
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
          findRepliedDuplicate(finding, priorRounds, inlineComments, botLogin);
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
      String botLogin) {
    for (List<ReviewResponse.Finding> previous : priorRounds) {
      for (var prior : previous) {
        if (!isSameFinding(finding, prior)) {
          continue;
        }
        // Marker indices are only meaningful within their own round; across rounds the same
        // index names unrelated findings, so the thread is located by file and title instead
        if (answeredRootComment(prior, inlineComments, botLogin) != null) {
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
        && (RiskLevel.fromString(finding.risk()) == RiskLevel.fromString(prior.risk())
            || FindingDeduplicator.titleSimilarity(finding.title(), prior.title())
                >= FindingDeduplicator.TITLE_SIMILARITY_THRESHOLD)) {
      return true;
    }
    // Paraphrased re-raises drift in both wording and line numbers as the PR evolves
    // (observed: 29 lines and 0.12 title similarity); same file plus strongly overlapping
    // title+description still identifies them, and suppression only ever fires when the
    // prior thread carries a maintainer reply
    return FindingDeduplicator.contentOverlap(finding, prior)
        >= FindingDeduplicator.CONTENT_OVERLAP_THRESHOLD;
  }

  private static boolean hasHumanReply(
      Long rootId, List<GitHubReviewClient.PullRequestComment> inlineComments, String botLogin) {
    return inlineComments.stream()
        .anyMatch(
            c ->
                rootId.equals(c.inReplyToId())
                    && c.user() != null
                    && !botLogin.equals(c.user().login()));
  }

  /** Maps each previous finding's prompt id to its bot root comment, for thread resolution. */
  public Map<Integer, Long> matchFindingThreads(
      String previousAiResponseJson,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      String botLogin) {
    var previous = parsePreviousFindings(previousAiResponseJson);
    var threads = new HashMap<Integer, Long>();
    for (var i = 0; i < previous.size(); i++) {
      Long rootId = rootCommentId(previous.get(i), i + 1, inlineComments, botLogin);
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
    if (statuses == null || statuses.isEmpty()) {
      return List.of();
    }
    var previous = parsePreviousFindings(previousAiResponseJson);
    if (previous.isEmpty()) {
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
   * Deterministic approve backstop for issues #118 and #130. The bot's own prior findings the model
   * silently dropped — still present in the current diff, carrying no maintainer reply, and not
   * closed by any round — surfaced as synthetic {@code "unresolved"} statuses. Merging these into
   * the result's previous-findings statuses makes the existing APPROVE → COMMENT gate hold over a
   * silently dropped finding and keeps every downstream count and message truthful, without a
   * separate code path.
   *
   * <p>The findings are reconstructed from the persisted prior responses (keyed by repo+PR, so they
   * survive a force-push/rebase), which means the backstop fires even when the model received the
   * previous-findings context but ignored it — the exact PR #99 dogfood symptom.
   *
   * <p>It considers <em>all</em> prior rounds, not just the newest (#130). Each round persists only
   * its own new findings; a finding raised in round 1 is referenced in later rounds only via their
   * {@code previous_findings_status}, so a still-open finding the model drops several rounds after
   * raising it would otherwise never be re-checked. The rounds are replayed oldest → newest:
   *
   * <ul>
   *   <li>A round's {@code previous_findings_status} reports on the round immediately before it
   *       (ids are 1-based positions over that round's findings). A {@code resolved}/{@code
   *       justified} verdict there closes the referenced finding, so it is removed from the open
   *       set — this is what keeps the widened scope from re-holding a finding that was
   *       legitimately addressed in an intermediate round (the "block any prior finding"
   *       over-strictness #118 explicitly rejected).
   *   <li>Findings carried across rounds are deduplicated by content (file + line + title), so the
   *       same finding raised at one location is held at most once, while two distinct findings at
   *       different lines are never collapsed into one.
   *   <li>The current round reports on the newest prior round; a finding it accounted for with a
   *       <em>recognized</em> verdict (resolved / justified / unresolved, {@link
   *       #isRecognizedStatus}) is dropped — resolved/justified means addressed, and a reported
   *       {@code unresolved} is already held by the model gate ({@link #unresolvedFindings} +
   *       {@link #hasUnresolved}), so reconstructing it here would double-count. An
   *       <em>unrecognized</em> verdict is not an accounting, so the backstop still holds it — a
   *       malformed status string must not sneak a still-open finding past the APPROVE gate (#131).
   *   <li>Presence is judged by {@link DiffLineResolver#isFindingPresent} against each finding's
   *       persisted {@code suggestion_old} anchor, so a still-open finding survives line drift and
   *       a fixed one is not kept alive by surviving context (#129).
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
      String botLogin) {
    if (priorAiResponseJsons == null || priorAiResponseJsons.isEmpty() || lineResolver == null) {
      return List.of();
    }
    // Persisted newest-first; replay oldest → newest so each round's previous_findings_status
    // (which reports on the round immediately before it) can close the findings it addressed.
    var chrono = new ArrayList<ReviewResponse>();
    for (var i = priorAiResponseJsons.size() - 1; i >= 0; i--) {
      chrono.add(parseResponse(priorAiResponseJsons.get(i)));
    }
    // Still-open prior findings keyed by content, so a finding carried across rounds is held at
    // most once; insertion order is preserved for stable, deterministic output.
    var open = new LinkedHashMap<String, OpenFinding>();
    for (var i = 0; i < chrono.size(); i++) {
      if (i > 0) {
        closeAddressed(open, chrono.get(i - 1).findings(), chrono.get(i).previousFindingsStatus());
      }
      addOpenFindings(open, chrono.get(i).findings());
    }
    // The current round reports on the newest prior round — drop everything it accounted for.
    closeReported(open, chrono.get(chrono.size() - 1).findings(), currentStatuses);

    // Cluster the remaining open findings by tolerant identity.
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

    var held = new ArrayList<ReviewResult.PreviousFindingStatus>();
    for (var cluster : clusters) {
      boolean anyPresent = false;
      boolean anyReplied = false;
      OpenFinding target = null;

      for (var member : cluster) {
        var finding = member.finding();
        boolean present =
            lineResolver.isFindingPresent(
                finding.file(), finding.line(), finding.suggestionOld(), DUPLICATE_LINE_TOLERANCE);
        if (present) {
          anyPresent = true;
          if (target == null) {
            target = member;
          }
        }
        if (answeredRootComment(finding, inlineComments, botLogin) != null) {
          anyReplied = true;
        }
      }

      if (anyPresent && !anyReplied) {
        if (target == null) {
          target = cluster.get(0);
        }
        held.add(
            new ReviewResult.PreviousFindingStatus(
                target.id(),
                STATUS_UNRESOLVED,
                "Flagged in an earlier round and still present; not addressed in this revision."));
      }
    }
    return held;
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
   * still-open silent finding and let APPROVE sail over it (the #118 missed hold). The cost is that
   * one finding re-raised at a <em>drifted</em> line is keyed twice and held twice — a duplicate,
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
   * double-counts an item the gate already holds via the model's own status. (#131)
   */
  private static boolean isRecognizedStatus(String status) {
    return status != null && RECOGNIZED_STATUSES.contains(status.toLowerCase(Locale.ROOT));
  }

  private List<ReviewResponse.Finding> parsePreviousFindings(String aiResponseJson) {
    return parseResponse(aiResponseJson).findings();
  }

  /**
   * Full persisted previous response, with empty (never null) findings and statuses on a missing or
   * unparseable input — the backstop replay needs both lists, and the compact constructor of {@link
   * ReviewResponse} guarantees non-null copies.
   */
  private ReviewResponse parseResponse(String aiResponseJson) {
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
      List<GitHubReviewClient.ReviewResponse> priorReviews, String botLogin) {
    if (priorReviews == null || priorReviews.isEmpty()) return "";

    var lastBotReview =
        priorReviews.stream()
            .filter(r -> botLogin.equals(r.user().login()))
            .reduce((first, second) -> second); // get last

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
   * and matches the backstop's recognized-status contract (#131).
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
