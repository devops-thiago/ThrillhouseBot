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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Analyzes follow-up reviews by comparing new findings against prior reviews. */
@ApplicationScoped
public class FollowUpAnalyzer {

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
      if ("unresolved".equalsIgnoreCase(status.status())) {
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
   * Deterministic approve backstop for issue #118. Prior findings the model left OUT of
   * previous_findings_status entirely — neither resolved, justified, nor even reported as
   * unresolved — that are still present in the current diff and carry no maintainer reply, surfaced
   * as synthetic {@code "unresolved"} statuses. Merging these into the result's previous-findings
   * statuses makes the existing APPROVE → COMMENT gate hold over a silently dropped finding, and
   * makes every downstream count and message truthful, without a separate code path.
   *
   * <p>The findings are reconstructed from the persisted previous response (keyed by repo+PR, so it
   * survives a force-push/rebase), which means the backstop fires even when the model received the
   * previous-findings context but ignored it — the exact PR #99 dogfood symptom.
   *
   * <p>Scoped to the newest prior round only ({@code previousAiResponseJson});
   * previous_findings_status ids are 1-based positions over exactly that list, so iterating it
   * keeps the id mapping aligned with {@link #unresolvedFindings} and rules the off-by-one out by
   * construction. Findings the model <em>did</em> account for (any status id) are skipped, so this
   * never double-counts the model-reported {@code unresolved} items the {@link #hasUnresolved} gate
   * already holds.
   *
   * <p>It is downgrade-only — these statuses reach the APPROVE gate but never {@code outstanding},
   * so they can turn APPROVE into COMMENT, never into REQUEST_CHANGES. A maintainer reply on the
   * thread clears the hold (the human is engaged; defer to them, matching {@link
   * #dropRepliedDuplicates}), as does the model marking the finding resolved/justified.
   */
  public List<ReviewResult.PreviousFindingStatus> unreportedUnresolvedStatuses(
      String previousAiResponseJson,
      List<ReviewResponse.PreviousFindingStatus> statuses,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      DiffLineResolver lineResolver,
      String botLogin) {
    var previous = parsePreviousFindings(previousAiResponseJson);
    if (previous.isEmpty() || lineResolver == null) {
      return List.of();
    }
    var reportedIds = new HashSet<Integer>();
    if (statuses != null) {
      for (var status : statuses) {
        reportedIds.add(status.id());
      }
    }
    var held = new ArrayList<ReviewResult.PreviousFindingStatus>();
    for (var i = 0; i < previous.size(); i++) {
      var id = i + 1;
      if (reportedIds.contains(id)) {
        continue; // the model accounted for it (resolved/justified/unresolved) — not a silent drop
      }
      var finding = previous.get(i);
      // isLineInDiff already rejects a null file, so no separate guard is needed here.
      if (!lineResolver.isLineInDiff(finding.file(), finding.line(), DUPLICATE_LINE_TOLERANCE)) {
        continue; // its code is no longer in this round's diff — cannot confirm it is still open
      }
      if (answeredRootComment(finding, inlineComments, botLogin) != null) {
        continue; // a maintainer already engaged on the thread — defer to them, do not auto-hold
      }
      held.add(
          new ReviewResult.PreviousFindingStatus(
              id,
              "unresolved",
              "Flagged in an earlier round and still present; not addressed in this revision."));
    }
    return held;
  }

  private List<ReviewResponse.Finding> parsePreviousFindings(String aiResponseJson) {
    if (aiResponseJson == null || aiResponseJson.isBlank()) {
      return List.of();
    }
    try {
      return mapper.readValue(aiResponseJson, ReviewResponse.class).findings();
    } catch (JsonProcessingException e) {
      Log.warn("Could not parse previous AI response, falling back to review body context", e);
      return List.of();
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

  /** Converts AI response's previous_findings_status into ReviewResult statuses. */
  public List<ReviewResult.PreviousFindingStatus> toStatuses(
      List<ReviewResponse.PreviousFindingStatus> aiStatuses) {
    if (aiStatuses == null) return List.of();

    var result = new ArrayList<ReviewResult.PreviousFindingStatus>();
    for (var s : aiStatuses) {
      result.add(new ReviewResult.PreviousFindingStatus(s.id(), s.status(), s.note()));
    }
    return result;
  }

  /** Checks if there are any unresolved findings that should be re-flagged. */
  public boolean hasUnresolved(List<ReviewResult.PreviousFindingStatus> statuses) {
    return statuses.stream().anyMatch(s -> "unresolved".equalsIgnoreCase(s.status()));
  }
}
