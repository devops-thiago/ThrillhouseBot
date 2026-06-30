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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Posts a review's outcome to GitHub — inline finding comments, the review verdict (or no-issues
 * body), dismissal of the bot's stale pending review, and resolution of addressed finding threads.
 * The write side of the pipeline.
 */
@ApplicationScoped
public class ReviewPublisher {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String EVENT_REQUEST_CHANGES = "REQUEST_CHANGES";
  private static final String EVENT_APPROVE = "APPROVE";
  private static final String EVENT_COMMENT = "COMMENT";
  private static final String CI_PENDING = "pending";

  private final GitHubReviewClient reviewClient;
  private final GitHubCommentClient commentClient;
  private final ReviewThreadService reviewThreadService;
  private final SuggestionFormatter suggestionFormatter;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final PrLabeler labeler;
  private final ThrillhouseConfig config;
  private final BotIdentity botIdentity;

  @Inject
  public ReviewPublisher(
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubCommentClient commentClient,
      ReviewThreadService reviewThreadService,
      SuggestionFormatter suggestionFormatter,
      FollowUpAnalyzer followUpAnalyzer,
      PrLabeler labeler,
      ThrillhouseConfig config,
      BotIdentity botIdentity) {
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.reviewThreadService = reviewThreadService;
    this.suggestionFormatter = suggestionFormatter;
    this.followUpAnalyzer = followUpAnalyzer;
    this.labeler = labeler;
    this.config = config;
    this.botIdentity = botIdentity;
  }

  /**
   * Posts the PR summary comment, but only on the first user-visible review (and only when there is
   * a summary to post). Follow-up reviews carry their signal in the review itself, not a new
   * comment.
   */
  void publishSummary(String auth, String owner, String repo, int prNumber, ReviewResult result) {
    if (result.isFirstReview() && !result.summaryMarkdown().isBlank()) {
      commentClient.createComment(
          auth,
          ACCEPT,
          owner,
          repo,
          prNumber,
          new GitHubCommentClient.CreateCommentRequest(result.summaryMarkdown()));
    }
  }

  /**
   * Applies or suggests the model's labels (best-effort; never blocks the review). The suggested
   * labels come from the model summary and are reconciled against the repo's existing labels.
   */
  void applyLabels(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      ReviewResult result,
      ReviewResponse aiResponse,
      ReviewContextLoader.ReviewContext ctx) {
    labeler.applyOrSuggest(
        new PrLabeler.LabelRequest(
            auth,
            req.owner(),
            req.repo(),
            req.prNumber(),
            result.isFirstReview(),
            aiResponse.summary() != null ? aiResponse.summary().suggestedLabels() : List.of(),
            ctx.repoLabels()));
  }

  /**
   * Posts the "review could not be completed" notice when a review fails before its result was
   * surfaced. Best-effort: a failure to post it is logged, not propagated.
   */
  void postFailureNotice(String auth, String owner, String repo, int prNumber) {
    try {
      commentClient.createComment(
          auth,
          ACCEPT,
          owner,
          repo,
          prNumber,
          new GitHubCommentClient.CreateCommentRequest(
              """
                  ⚠️ **ThrillhouseBot review could not be completed.**

                  The review service encountered an error. \
                  Please reply with `/review` or `@Thrillhousebot review` to retry."""));
    } catch (RuntimeException commentError) {
      Log.warnf(
          commentError,
          "Failed to post review failure comment for %s/%s #%d",
          owner,
          repo,
          prNumber);
    }
  }

  void postReview(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      ReviewResult result,
      DiffLineResolver lineResolver) {
    if (!result.hasIssues()) {
      postNoIssuesReview(auth, owner, repo, prNumber, commitSha, result);
      return;
    }

    var inline = postInlineComments(auth, owner, repo, prNumber, commitSha, result, lineResolver);
    var event =
        result.reviewState() == ReviewState.REQUEST_CHANGES ? EVENT_REQUEST_CHANGES : EVENT_COMMENT;

    if (inline.posted() == 0) {
      // Inline anchoring failed for every finding (e.g. all lines fell outside the diff after a
      // force-push). List them in the review body with their descriptions so they are never
      // invisible. This does not point at the PR summary comment even on a first review: the
      // summary
      // is posted best-effort (a transient failure leaves no such comment), and its Key Findings is
      // only a brief TOC without descriptions — so the body is the one place the detail is sure to
      // appear.
      Log.warnf(
          "No inline comments posted for %s/%s #%d — surfacing findings in the review body",
          owner, repo, prNumber);
      createReviewWithFallback(
          auth,
          owner,
          repo,
          prNumber,
          new GitHubReviewClient.CreateReviewRequest(
              commitSha, String.join("\n\n", skippedFindingsBodyParts(inline)), event, List.of()));
      return;
    }

    // Some findings anchored inline. Still post a review body when there are changes to flag and/or
    // findings that could not be anchored — so a finding whose line fell outside the diff is
    // reported (with its description), not silently dropped just because its siblings anchored.
    var bodyParts = new ArrayList<String>();
    if (result.reviewState() == ReviewState.REQUEST_CHANGES) {
      bodyParts.add("ThrillhouseBot requested changes — see inline comments on the diff.");
    }
    // List every un-anchored finding with its description, even on a first review where the summary
    // comment also names it. The summary's Key Findings is a brief table of contents (risk, title,
    // location — no description), so this body is the only place an un-anchored finding's detail is
    // surfaced; repeating the title here is the acceptable cost of never dropping the problem.
    bodyParts.addAll(skippedFindingsBodyParts(inline));
    if (!bodyParts.isEmpty()) {
      createReviewWithFallback(
          auth,
          owner,
          repo,
          prNumber,
          new GitHubReviewClient.CreateReviewRequest(
              commitSha, String.join("\n\n", bodyParts), event, List.of()));
    }
  }

  /**
   * A review-body list of findings, used when none could be anchored as inline comments (their
   * lines fell outside the current diff). It keeps the findings visible on a follow-up review,
   * which posts no summary comment — without it the findings would surface only as a red check run.
   */
  /** Review-body sections for findings not posted inline — un-anchored and/or cap-skipped. */
  private static List<String> skippedFindingsBodyParts(InlineCommentResult inline) {
    var parts = new ArrayList<String>();
    if (!inline.unanchored().isEmpty()) {
      parts.add(unanchoredFindingsBody(inline.unanchored()));
    }
    if (!inline.capSkipped().isEmpty()) {
      parts.add(capSkippedFindingsBody(inline.capSkipped()));
    }
    return parts;
  }

  private static String unanchoredFindingsBody(List<Finding> findings) {
    var sb = new StringBuilder();
    sb.append("ThrillhouseBot found ")
        .append(findings.size())
        .append(" issue(s) that could not be anchored to the current diff:\n\n");
    appendFindingList(sb, findings);
    return sb.toString();
  }

  private static String capSkippedFindingsBody(List<Finding> findings) {
    var sb = new StringBuilder();
    sb.append("ThrillhouseBot found ")
        .append(findings.size())
        .append(
            " issue(s) not posted inline because the per-run comment cap was reached — re-run"
                + " `/review` or raise the comment cap:\n\n");
    appendFindingList(sb, findings);
    return sb.toString();
  }

  private static void appendFindingList(StringBuilder sb, List<Finding> findings) {
    for (Finding f : findings) {
      sb.append("- **")
          .append(f.risk().name())
          .append(":** ")
          .append(f.title())
          .append(" (`")
          .append(f.file())
          .append(":")
          .append(f.line())
          .append("`)");
      // No inline comment carries the detail here, so include the description to still explain the
      // problem (a suggestion can't be placed, but the issue is reported).
      if (f.description() != null && !f.description().isBlank()) {
        sb.append("\n  ").append(f.description().strip().replace("\n", "\n  "));
      }
      sb.append("\n");
    }
  }

  /**
   * Posts the review when there are no new findings: a bare APPROVE, or a COMMENT explaining why.
   */
  private void postNoIssuesReview(
      String auth, String owner, String repo, int prNumber, String commitSha, ReviewResult result) {
    if (result.reviewState() == ReviewState.APPROVE) {
      // On a first review the celebration is part of the summary comment, so the approval itself
      // carries no body; follow-ups post no summary and keep the message here.
      var req =
          new GitHubReviewClient.CreateReviewRequest(
              commitSha,
              result.isFirstReview() ? "" : PrSummaryGenerator.ZERO_ISSUES_MESSAGE,
              EVENT_APPROVE,
              List.of());
      createReviewWithFallback(auth, owner, repo, prNumber, req);
      return;
    }
    // A COMMENT held back only by pending/failed CI (no findings, nothing unresolved) merely
    // restates the summary comment the first review already posts, so emitting it too would
    // duplicate that message. Skip it on the first review and let the summary stand alone — EXCEPT
    // when the hold is (also) a truncated diff: the summary comment is posted best-effort, so
    // a transient failure would leave the partial review disclosed nowhere on the PR (CI holds stay
    // visible via the red checks themselves; truncation has no other PR surface). Post the body for
    // a truncated review so the partial-review notice is never lost.
    // Unresolved previous findings are excluded — their COMMENT carries distinct "reply on the
    // thread" guidance the summary lacks, so it still posts. Follow-ups post no summary, so the
    // COMMENT is their only signal; REQUEST_CHANGES always posts; the merge gate is the check run.
    if (result.reviewState() == ReviewState.COMMENT
        && result.isFirstReview()
        && result.unresolvedPreviousCount() == 0
        && !result.truncated()) {
      return;
    }
    // No new findings, but previous ones are still unresolved or CI checks are pending/failed —
    // never claim a clean review.
    var req =
        new GitHubReviewClient.CreateReviewRequest(
            commitSha,
            noIssuesBody(result),
            result.reviewState() == ReviewState.REQUEST_CHANGES
                ? EVENT_REQUEST_CHANGES
                : EVENT_COMMENT,
            List.of());
    createReviewWithFallback(auth, owner, repo, prNumber, req);
  }

  /**
   * Body for a no-new-findings COMMENT review: failing/pending CI checks, unresolved previous
   * findings, and/or a truncated diff — whichever held the verdict back from APPROVE. The
   * unresolved message is emitted only when there actually are unresolved findings (never a bogus
   * "0 … unresolved"), and truncation is disclosed here on follow-up reviews, which post no summary
   * comment to carry the first-review banner.
   */
  private String noIssuesBody(ReviewResult result) {
    long unresolved = result.unresolvedPreviousCount();
    var sb = new StringBuilder();
    // Offending checks and an unreadable CI source are independent hold reasons — a review can be
    // held by both at once (one CI source failing while another is unreadable), so disclose each on
    // its own rather than letting the offending branch suppress the unreadable note. This mirrors
    // the
    // summary table, which lists the two separately.
    boolean ciHeld = false;
    if (!result.offendingCiChecks().isEmpty()) {
      sb.append(
          "ThrillhouseBot found no issues in this PR, but some checks are still pending or"
              + " failed:\n");
      for (var check : result.offendingCiChecks()) {
        String status = check.isFailing() ? "failed" : CI_PENDING;
        sb.append("- Check **").append(check.name()).append("** is ").append(status).append("\n");
      }
      ciHeld = true;
    }
    if (result.ciUnreadable()) {
      sb.append(
          "ThrillhouseBot found no issues in this PR, but the CI status could not be read, so"
              + " approval is held until it can be confirmed.\n");
      ciHeld = true;
    }
    if (unresolved > 0) {
      sb.append(ciHeld ? "\nAdditionally, " : "")
          .append(ReviewResult.unresolvedPreviousMessage(unresolved));
    }
    // Disclose the partial review here whenever the diff was truncated — on follow-ups (which post
    // no summary) and on first reviews too: the first-review summary banner is posted best-effort,
    // so relying on it alone would lose the only PR-visible notice if that post fails. The
    // summary may repeat it; a duplicated notice is the acceptable cost of never dropping it.
    if (result.truncated()) {
      if (!sb.isEmpty()) {
        sb.append("\n\n");
      }
      sb.append(ReviewResult.truncationNotice(result.omittedFiles()).strip());
    }
    return sb.toString();
  }

  /**
   * How many findings anchored as inline comments, the ones that could not be anchored, and the
   * ones skipped because {@code maxReviewComments} was reached (never tried for anchoring).
   */
  record InlineCommentResult(int posted, List<Finding> unanchored, List<Finding> capSkipped) {}

  /**
   * Posts each finding as its own pull request review comment on the diff. Individual comments
   * survive 422s that would reject an entire batched review. Findings whose line falls outside the
   * diff (or are otherwise rejected) are returned as {@code unanchored} so the caller can still
   * report them in the review body rather than dropping them.
   */
  InlineCommentResult postInlineComments(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      ReviewResult result,
      DiffLineResolver lineResolver) {
    var target = new CommentTarget(auth, owner, repo, prNumber, commitSha);
    var posted = 0;
    var unanchored = new ArrayList<Finding>();
    var capSkipped = new ArrayList<Finding>();
    var maxComments = config.review().maxReviewComments();
    for (var i = 0; i < result.findings().size(); i++) {
      // The 1-based index doubles as the finding's id in the persisted response and the
      // hidden comment marker, keeping thread matching deterministic on follow-up reviews
      var finding = result.findings().get(i);
      // The cap limits inline comments, not findings: once it's reached, remaining findings are
      // returned as capSkipped (never tried for anchoring) so the caller reports them with the
      // right
      // reason — capped on noise, but never silently dropped or mislabeled as un-anchorable.
      if (posted >= maxComments) {
        capSkipped.add(finding);
        continue;
      }
      if (postFindingComment(target, finding, i + 1, lineResolver)) {
        posted++;
      } else {
        unanchored.add(finding);
      }
    }
    return new InlineCommentResult(posted, List.copyOf(unanchored), List.copyOf(capSkipped));
  }

  /** PR coordinates shared by every inline comment of one review. */
  private record CommentTarget(
      String auth, String owner, String repo, int prNumber, String commitSha) {}

  private boolean postFindingComment(
      CommentTarget target, Finding finding, int findingId, DiffLineResolver lineResolver) {
    var line = lineResolver.resolveRightSideLine(finding.file(), finding.line());
    if (line.isEmpty()) {
      Log.debugf(
          "Skipping inline comment for %s:%d — line is outside PR diff",
          finding.file(), finding.line());
      return false;
    }

    var resolvedLine = line.getAsInt();
    if (resolvedLine != finding.line()) {
      Log.debugf(
          "Adjusted inline comment line for %s from %d to %d",
          finding.file(), finding.line(), resolvedLine);
    }

    // A suggestion whose old code spans several lines is posted as a multi-line comment so the
    // GitHub suggestion overwrites the whole range, not just the anchor line. The range is
    // resolved from the verbatim old code's position in the diff; a blank/single-line/unresolvable
    // anchor leaves it empty and the comment stays single-line.
    var range =
        finding.hasSuggestion()
            ? lineResolver.resolveSuggestionRange(finding.file(), finding.suggestionOld())
            : Optional.<DiffLineResolver.LineRange>empty();

    // A multi-line suggestion must overwrite its whole range. When that range can't be resolved
    // (the old code doesn't match the diff verbatim, is ambiguous, or straddles a hunk), attaching
    // the suggestion would post it against a single line — applying it then overwrites only the
    // anchor line and leaves the rest of the quoted block in place (the same multi-line
    // corruption).
    // In that case post the finding without the suggestion: the problem is still reported, just not
    // as a one-click fix.
    // hasSuggestion() already guarantees a non-blank suggestionOld, so the newline check is safe.
    var multiLineSuggestion =
        finding.hasSuggestion() && finding.suggestionOld().strip().contains("\n");
    var includeSuggestion = finding.hasSuggestion() && (!multiLineSuggestion || range.isPresent());

    if (tryPostInlineComment(target, finding, findingId, resolvedLine, range, includeSuggestion)
        || (includeSuggestion
            && tryPostInlineComment(
                target, finding, findingId, resolvedLine, Optional.empty(), false))) {
      return true;
    }
    Log.warnf("GitHub rejected inline comment for %s:%d", finding.file(), finding.line());
    return false;
  }

  /**
   * Posts one inline comment. When {@code range} is present the comment spans {@code
   * start_line}..{@code line} (both RIGHT side); otherwise it anchors to the single {@code line}.
   * The retry without a suggestion block always passes an empty range — a multi-line span is only
   * meaningful with a suggestion to apply across it.
   */
  private boolean tryPostInlineComment(
      CommentTarget target,
      Finding finding,
      int findingId,
      int line,
      Optional<DiffLineResolver.LineRange> range,
      boolean includeSuggestion) {
    int endLine = range.map(DiffLineResolver.LineRange::endLine).orElse(line);
    Integer startLine = range.map(DiffLineResolver.LineRange::startLine).orElse(null);
    String startSide = startLine != null ? "RIGHT" : null;
    try {
      reviewClient.createPullRequestComment(
          target.auth(),
          ACCEPT,
          target.owner(),
          target.repo(),
          target.prNumber(),
          new GitHubReviewClient.CreatePullRequestCommentRequest(
              target.commitSha(),
              suggestionFormatter.formatReviewComment(finding, includeSuggestion, findingId),
              finding.file(),
              endLine,
              "RIGHT",
              startLine,
              startSide));
      return true;
    } catch (RuntimeException e) {
      Log.debugf(
          e,
          "Inline comment rejected for %s:%d (suggestion=%s)",
          finding.file(),
          endLine,
          includeSuggestion);
      return false;
    }
  }

  /**
   * Deletes any pending review left by this bot — GitHub allows only one pending review per user.
   * Reuses the reviews already fetched for this run instead of re-listing: no review is created
   * between that fetch and here, so a second {@code listReviews} would only spend extra rate-limit
   * budget.
   */
  void dismissPendingBotReviews(
      String auth,
      String owner,
      String repo,
      int prNumber,
      List<GitHubReviewClient.ReviewResponse> priorReviews) {
    try {
      for (var review : priorReviews) {
        // A review from a since-deleted account serializes as user:null; guard it (matching the
        // first-visible-review check) so one ghost review can't NPE-abort the whole dismissal loop
        // and leave the bot's own stale pending review undeleted.
        if ("PENDING".equals(review.state())
            && review.user() != null
            && botIdentity.matches(review.user().login())) {
          reviewClient.deletePendingReview(auth, ACCEPT, owner, repo, prNumber, review.id());
          Log.debugf(
              "Dismissed pending review %d on %s/%s #%d", review.id(), owner, repo, prNumber);
        }
      }
    } catch (RuntimeException e) {
      Log.debug("Could not dismiss pending bot reviews (continuing)", e);
    }
  }

  /**
   * Submits a PR review, falling back to a summary-only review when inline comments are rejected
   * (e.g. stale line numbers after a force-push).
   */
  void createReviewWithFallback(
      String auth,
      String owner,
      String repo,
      int prNumber,
      GitHubReviewClient.CreateReviewRequest req) {
    try {
      reviewClient.createReview(auth, ACCEPT, owner, repo, prNumber, req);
    } catch (RuntimeException e) {
      if (req.comments() == null || req.comments().isEmpty()) {
        throw new ReviewPostException(
            "GitHub review rejected for " + owner + "/" + repo + " #" + prNumber, e);
      }
      Log.warnf(
          e,
          "PR review with inline comments rejected for %s/%s #%d — retrying without comments",
          owner,
          repo,
          prNumber);
      var fallback =
          new GitHubReviewClient.CreateReviewRequest(
              req.commitId(), req.body(), req.event(), List.of());
      reviewClient.createReview(auth, ACCEPT, owner, repo, prNumber, fallback);
    }
  }

  /**
   * Resolves the GitHub threads of previous findings the model judged resolved (fix landed) or
   * justified (a reply explains the deferral), so addressed feedback stops cluttering the PR.
   * Best-effort: the review outcome is already posted when this runs.
   */
  void resolveAddressedThreads(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      String previousAiResponseJson,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<ReviewResponse.PreviousFindingStatus> statuses) {
    try {
      List<Integer> addressed =
          statuses.stream()
              .filter(
                  s ->
                      "resolved".equalsIgnoreCase(s.status())
                          || "justified".equalsIgnoreCase(s.status()))
              .map(ReviewResponse.PreviousFindingStatus::id)
              .toList();
      if (addressed.isEmpty() || inlineComments.isEmpty()) {
        return;
      }
      var rootByFinding =
          followUpAnalyzer.matchFindingThreads(previousAiResponseJson, inlineComments, botIdentity);
      var threads =
          reviewThreadService.threadsByRootComment(auth, req.owner(), req.repo(), req.prNumber());
      var resolved = 0;
      for (int findingId : addressed) {
        var rootCommentId = rootByFinding.get(findingId);
        ReviewThreadService.ThreadRef thread =
            rootCommentId != null ? threads.get(rootCommentId) : null;
        if (thread != null
            && !thread.resolved()
            && reviewThreadService.resolve(auth, thread.id())) {
          resolved++;
        }
      }
      if (resolved > 0) {
        Log.infof(
            "Resolved %d addressed review thread(s) on %s/%s #%d",
            resolved, req.owner(), req.repo(), req.prNumber());
      }
    } catch (RuntimeException e) {
      Log.warn("Failed to resolve addressed review threads (continuing)", e);
    }
  }
}
