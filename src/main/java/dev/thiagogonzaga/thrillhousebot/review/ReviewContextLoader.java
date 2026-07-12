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

import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubLabelClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Loads everything a review reads from GitHub and persistence before the AI is called — the diff,
 * base comparison, prior reviews/comments, persisted prior findings, repository instructions,
 * existing labels, and project stack — and computes the first-visible / has-context signals.
 * Extracted from {@code ReviewOrchestrator} as the read side of the pipeline; every fetch fails
 * soft exactly as before, except the PR-files fetch whose failure must reach the caller.
 *
 * <p>When token budgeting is on ({@code max-input-tokens > 0}), the legacy line-capped mega-diff is
 * not built: {@link DiffBudgetPlanner} owns what the model sees. The base comparison is still
 * rendered, but without the line cap, so overhead accounting uses the full comparison text.
 */
@ApplicationScoped
public class ReviewContextLoader {

  private static final String ACCEPT = "application/vnd.github+json";

  private final GitHubPullRequestClient prClient;
  private final GitHubReviewClient reviewClient;
  private final GitHubCommentClient commentClient;
  private final InstructionsResolver instructionsResolver;
  private final ProjectStackResolver projectStackResolver;
  private final ReviewDiffFormatter diffFormatter;
  private final PrLabeler labeler;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final ReviewSessionPersistence sessionPersistence;
  private final BotIdentity botIdentity;
  private final ActiveModelSettings activeModel;

  @Inject
  public ReviewContextLoader(
      @RestClient GitHubPullRequestClient prClient,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubCommentClient commentClient,
      InstructionsResolver instructionsResolver,
      ProjectStackResolver projectStackResolver,
      ReviewDiffFormatter diffFormatter,
      PrLabeler labeler,
      FollowUpAnalyzer followUpAnalyzer,
      ReviewSessionPersistence sessionPersistence,
      BotIdentity botIdentity,
      ActiveModelSettings activeModel) {
    this.prClient = prClient;
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.instructionsResolver = instructionsResolver;
    this.projectStackResolver = projectStackResolver;
    this.diffFormatter = diffFormatter;
    this.labeler = labeler;
    this.followUpAnalyzer = followUpAnalyzer;
    this.sessionPersistence = sessionPersistence;
    this.botIdentity = botIdentity;
    this.activeModel = activeModel;
  }

  /**
   * GitHub's authoritative PR-level file/line totals, read from the pulls endpoint. Preferred over
   * the ignore-glob-filtered diff counts for the summary's "Changes Overview"; {@code null} when
   * the totals could not be fetched, in which case the summary falls back to the diff-derived
   * counts.
   */
  public record PrTotals(int filesChanged, int additions, int deletions) {}

  /** Everything the review pipeline reads before the model call, loaded once up front. */
  public record ReviewContext(
      List<GitHubPullRequestClient.FileDiff> files,
      String diff,
      String baseComparison,
      int omittedFiles,
      List<GitHubReviewClient.ReviewResponse> priorReviews,
      List<String> priorAiResponseJsons,
      boolean isFirstVisibleReview,
      boolean hasContext,
      String previousAiResponseJson,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      String previousFindings,
      InstructionsResolver.ResolvedInstructions instructions,
      List<GitHubLabelClient.Label> repoLabels,
      String projectStack,
      List<GitHubPullRequestClient.FileDiff> reviewableFiles,
      DiffLineResolver lineResolver,
      PrTotals prTotals) {
    public ReviewContext {
      files = List.copyOf(files);
      priorReviews = List.copyOf(priorReviews);
      priorAiResponseJsons = List.copyOf(priorAiResponseJsons);
      inlineComments = List.copyOf(inlineComments);
      repoLabels = List.copyOf(repoLabels);
      reviewableFiles = List.copyOf(reviewableFiles);
    }
  }

  /**
   * Loads the full read-side context for a review. Mirrors the prior inline sequence: diff + base
   * comparison, prior reviews, persisted prior AI responses, the first-visible / has-context
   * signals, inline comments and previous-findings context (only when prior responses exist),
   * repository instructions, existing labels, and project stack.
   *
   * <p>With token budgeting enabled, the line-capped mega-diff is skipped ({@code diff} is empty
   * and line-path {@code omittedFiles} is 0); {@link DiffBudgetPlanner} is authoritative for what
   * the model sees. The base comparison is still loaded, without the line cap.
   */
  ReviewContext load(
      String auth, ReviewOrchestrator.ReviewRequest req, ReviewSession session, String repository) {
    var files = fetchPrFiles(auth, req.owner(), req.repo(), req.prNumber());
    var prTotals = fetchPrTotals(auth, req.owner(), req.repo(), req.prNumber());
    var reviewableFiles = diffFormatter.reviewableFiles(files);
    var tokenBudgeted = activeModel.maxInputTokens() > 0;
    var diffResult =
        tokenBudgeted
            ? new ReviewDiffFormatter.FormattedDiff("", 0)
            : diffFormatter.buildDiffStringWithStats(files, reviewableFiles);
    // Token-budgeted multi-call drops base comparison from every batch; loading the full uncapped
    // comparison only bloated shared overhead (and starved the diff budget). Legacy line-capped
    // path keeps it for single-call reviews.
    var baseComparisonResult =
        tokenBudgeted
            ? new ReviewDiffFormatter.FormattedDiff("", 0)
            : buildBaseComparisonWithStats(
                auth, req.owner(), req.repo(), req.baseSha(), req.commitSha(), true);
    var omittedFiles = diffResult.omittedFiles();
    var lineResolver =
        new DiffLineResolver(diffFormatter.patchesByReviewableFiles(reviewableFiles));

    var priorReviews = fetchPriorReviews(auth, req.owner(), req.repo(), req.prNumber());
    // isFirstVisibleReview keys off the summary comment directly, not reviews alone: a first
    // round held back only by pending CI posts the summary comment but no review.
    List<String> priorAiResponseJsons =
        sessionPersistence.findAllPriorAiResponseJsons(repository, req.prNumber(), session.id);
    var isFirstVisibleReview =
        priorReviews.stream()
                .noneMatch(r -> r.user() != null && botIdentity.matches(r.user().login()))
            && !botSummaryCommentExists(auth, req.owner(), req.repo(), req.prNumber());
    var hasContext = !priorAiResponseJsons.isEmpty();
    String previousAiResponseJson =
        priorAiResponseJsons.isEmpty() ? null : priorAiResponseJsons.get(0);
    List<String> olderAiResponseJsons =
        priorAiResponseJsons.size() > 1
            ? priorAiResponseJsons.subList(1, priorAiResponseJsons.size())
            : List.of();
    List<GitHubReviewClient.PullRequestComment> inlineComments =
        hasContext
            ? fetchPullRequestComments(auth, req.owner(), req.repo(), req.prNumber())
            : List.of();
    String previousFindings =
        hasContext
            ? followUpAnalyzer.buildPreviousFindingsContext(
                previousAiResponseJson,
                priorReviews,
                inlineComments,
                olderAiResponseJsons,
                botIdentity)
            : "";

    var instructions =
        instructionsResolver.resolve(
            req.owner(), req.repo(), req.defaultBranch(), req.installationId());

    var repoLabels = labeler.fetchExistingLabels(auth, req.owner(), req.repo());
    var projectStack = resolveProjectStack(req);

    return new ReviewContext(
        files,
        diffResult.text(),
        baseComparisonResult.text(),
        omittedFiles,
        priorReviews,
        priorAiResponseJsons,
        isFirstVisibleReview,
        hasContext,
        previousAiResponseJson,
        inlineComments,
        previousFindings,
        instructions,
        repoLabels,
        projectStack,
        reviewableFiles,
        lineResolver,
        prTotals);
  }

  /**
   * Manual /review triggers arrive from issue_comment webhooks, which carry no PR head/base or
   * title — fetch them so the check run gets a valid head_sha (GitHub rejects blank with 422).
   */
  ReviewOrchestrator.ReviewRequest resolveMissingPrDetails(
      String auth, ReviewOrchestrator.ReviewRequest req) {
    if (req.commitSha() != null && !req.commitSha().isBlank()) {
      return req;
    }
    var pr = prClient.getPullRequest(auth, ACCEPT, req.owner(), req.repo(), req.prNumber());
    return new ReviewOrchestrator.ReviewRequest(
        req.owner(),
        req.repo(),
        req.prNumber(),
        pr.head() != null ? pr.head().sha() : "",
        pr.title() != null ? pr.title() : req.prTitle(),
        pr.body() != null ? pr.body() : "",
        pr.base() != null ? pr.base().sha() : "",
        req.defaultBranch(),
        req.installationId(),
        req.isManualTrigger(),
        pr.base() != null ? pr.base().ref() : "",
        req.forceSummary());
  }

  /** Stack context is best-effort enrichment — its failure must never fail the review. */
  String resolveProjectStack(ReviewOrchestrator.ReviewRequest req) {
    return SoftLoaders.projectStack(
        projectStackResolver,
        req.owner(),
        req.repo(),
        req.defaultBranch(),
        req.installationId(),
        "review");
  }

  List<GitHubPullRequestClient.FileDiff> fetchPrFiles(
      String auth, String owner, String repo, int prNumber) {
    return prClient.getPullRequestFiles(auth, ACCEPT, owner, repo, prNumber);
  }

  /**
   * GitHub's authoritative PR-level file/line totals ({@code changed_files}/{@code
   * additions}/{@code deletions} on the pulls endpoint), or {@code null} when they can't be read.
   * The summary reports these rather than the ignore-glob-filtered diff counts, which undercount
   * whenever a changed file is dropped by the ignore-glob. Best-effort: a fetch failure returns
   * {@code null} so the summary falls back to the diff-derived counts rather than failing the
   * review.
   */
  PrTotals fetchPrTotals(String auth, String owner, String repo, int prNumber) {
    try {
      var pr = prClient.getPullRequest(auth, ACCEPT, owner, repo, prNumber);
      return new PrTotals(pr.changedFiles(), pr.additions(), pr.deletions());
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch PR totals; summary will fall back to diff-derived counts", e);
      return null;
    }
  }

  ReviewDiffFormatter.FormattedDiff buildBaseComparisonWithStats(
      String auth, String owner, String repo, String base, String head) {
    return buildBaseComparisonWithStats(auth, owner, repo, base, head, true);
  }

  /**
   * @param applyLineBudget when false (token-budgeted reviews), include every patched file; the
   *     planner counts the text in shared overhead instead of dropping files here by line count
   */
  ReviewDiffFormatter.FormattedDiff buildBaseComparisonWithStats(
      String auth, String owner, String repo, String base, String head, boolean applyLineBudget) {
    if (base == null || head == null || base.length() < 7 || head.length() < 7) {
      return new ReviewDiffFormatter.FormattedDiff(
          "(regression comparison unavailable — refs too short)", 0);
    }
    try {
      var comparison = prClient.compareCommits(auth, ACCEPT, owner, repo, base, head);
      return diffFormatter.buildBaseComparisonWithStats(comparison, base, head, applyLineBudget);
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch base comparison, continuing without regression context", e);
      return new ReviewDiffFormatter.FormattedDiff("(regression comparison unavailable)", 0);
    }
  }

  List<GitHubReviewClient.ReviewResponse> fetchPriorReviews(
      String auth, String owner, String repo, int prNumber) {
    try {
      return reviewClient.listReviews(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.debug("No prior reviews found (this is normal for first review)", e);
      return List.of();
    }
  }

  /**
   * Whether the bot has already posted its PR summary comment on this PR. Used to suppress a
   * duplicate summary on a re-review when a prior round left a summary comment but no review (e.g.
   * a first round held back only by pending CI). Best-effort: on a fetch failure it returns {@code
   * false}, falling back to the review-based signal rather than blocking the summary.
   */
  public boolean botSummaryCommentExists(String auth, String owner, String repo, int prNumber) {
    for (var comment : fetchIssueComments(auth, owner, repo, prNumber)) {
      var user = comment.user();
      var body = comment.body();
      if (user != null && botIdentity.matches(user.login()) && isBotSummaryComment(body)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether an issue-comment body is the bot's PR summary. The heading may be preceded by the
   * truncation blockquote banner ({@link ReviewResult#truncationNotice(int)}), so a
   * starts-with-heading check alone would miss an already-posted summary on a large PR and re-post
   * it on every re-review.
   */
  private static boolean isBotSummaryComment(String body) {
    if (body == null) {
      return false;
    }
    if (body.stripLeading().startsWith(PrSummaryGenerator.SUMMARY_HEADING)) {
      return true;
    }
    return body.lines()
        .anyMatch(line -> line.stripLeading().startsWith(PrSummaryGenerator.SUMMARY_HEADING));
  }

  List<GitHubCommentClient.IssueComment> fetchIssueComments(
      String auth, String owner, String repo, int prNumber) {
    try {
      // listComments paginates internally and always returns a non-null list (empty when none).
      return commentClient.listComments(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.debug("Could not fetch PR issue comments (continuing as if none exist)", e);
      return List.of();
    }
  }

  List<GitHubReviewClient.PullRequestComment> fetchPullRequestComments(
      String auth, String owner, String repo, int prNumber) {
    try {
      // The client walks every page and never returns null.
      return reviewClient.listPullRequestComments(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch PR inline comments, continuing without thread context", e);
      return List.of();
    }
  }
}
