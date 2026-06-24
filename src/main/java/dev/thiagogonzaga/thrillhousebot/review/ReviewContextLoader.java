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
 * Extracted from {@code ReviewOrchestrator} (#250) as the read side of the pipeline; every fetch
 * fails soft exactly as before, except the PR-files fetch whose failure must reach the caller.
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
      ThrillhouseConfig config) {
    this.prClient = prClient;
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.instructionsResolver = instructionsResolver;
    this.projectStackResolver = projectStackResolver;
    this.diffFormatter = diffFormatter;
    this.labeler = labeler;
    this.followUpAnalyzer = followUpAnalyzer;
    this.sessionPersistence = sessionPersistence;
    this.botIdentity = BotIdentity.from(config.github().botLogins());
  }

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
      String projectStack) {
    public ReviewContext {
      files = List.copyOf(files);
      priorReviews = List.copyOf(priorReviews);
      priorAiResponseJsons = List.copyOf(priorAiResponseJsons);
      inlineComments = List.copyOf(inlineComments);
      repoLabels = List.copyOf(repoLabels);
    }
  }

  /**
   * Loads the full read-side context for a review. Mirrors the prior inline sequence: diff + base
   * comparison, prior reviews, persisted prior AI responses, the first-visible / has-context
   * signals, inline comments and previous-findings context (only when prior responses exist),
   * repository instructions, existing labels, and project stack.
   */
  ReviewContext load(
      String auth, ReviewOrchestrator.ReviewRequest req, ReviewSession session, String repository) {
    var files = fetchPrFiles(auth, req.owner(), req.repo(), req.prNumber());
    var diffResult = diffFormatter.buildDiffStringWithStats(files);
    var baseComparisonResult =
        buildBaseComparisonWithStats(auth, req.owner(), req.repo(), req.baseSha(), req.commitSha());
    var omittedFiles = diffResult.omittedFiles() + baseComparisonResult.omittedFiles();

    var priorReviews = fetchPriorReviews(auth, req.owner(), req.repo(), req.prNumber());
    // Two independent flags decouple UX presentation from context loading:
    //  • isFirstVisibleReview — true when the bot has posted nothing user-visible on the PR yet:
    //    neither a review nor a summary comment. Gates the first-review summary issue-comment and
    //    the APPROVE celebration body. A review alone is not a reliable proxy: a first round held
    //    back only by pending CI posts the summary comment but no review, so keying off the
    //    review would re-post the summary every round until one finally creates a review. The
    //    summary comment is the artifact we must not duplicate, so we look for it directly.
    //  • hasContext — true when persistence holds prior AI responses (surviving force-push/
    //    rebase). Gates previous-findings context loading, inline comment fetching, and the
    //    deterministic backstop. A persisted-but-unreviewed prior round (e.g. createReview
    //    failed after persistAiResponse) must still reconstruct context without suppressing
    //    the first user-visible summary.
    List<String> priorAiResponseJsons =
        sessionPersistence.findAllPriorAiResponseJsons(repository, req.prNumber(), session.id);
    var isFirstVisibleReview =
        priorReviews.stream().noneMatch(r -> botIdentity.matches(r.user().login()))
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

    // Existing repo labels are fetched once (when the feature is on): they both constrain the
    // model's suggestions in the prompt and are reused to reconcile its output afterwards.
    var repoLabels = labeler.fetchExistingLabels(auth, req.owner(), req.repo());

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
        resolveProjectStack(req));
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
        req.isManualTrigger());
  }

  /** Stack context is best-effort enrichment — its failure must never fail the review. */
  String resolveProjectStack(ReviewOrchestrator.ReviewRequest req) {
    try {
      return projectStackResolver.resolve(
          req.owner(), req.repo(), req.defaultBranch(), req.installationId());
    } catch (RuntimeException e) {
      Log.warn("Project stack resolution failed, continuing without stack context", e);
      return "";
    }
  }

  List<GitHubPullRequestClient.FileDiff> fetchPrFiles(
      String auth, String owner, String repo, int prNumber) {
    // A fetch failure must propagate to review()'s handler (failed check + "could not complete"
    // notice). Swallowing it into an empty list is indistinguishable from a PR with no changes and
    // produces a false APPROVE + green check on code that was never read. A genuinely empty
    // PR returns an empty list with no exception and still takes the normal path.
    return prClient.getPullRequestFiles(auth, ACCEPT, owner, repo, prNumber);
  }

  String buildDiffString(List<GitHubPullRequestClient.FileDiff> files) {
    return diffFormatter.buildDiffString(files);
  }

  String buildBaseComparison(String auth, String owner, String repo, String base, String head) {
    return buildBaseComparisonWithStats(auth, owner, repo, base, head).text();
  }

  ReviewDiffFormatter.FormattedDiff buildBaseComparisonWithStats(
      String auth, String owner, String repo, String base, String head) {
    if (base == null || head == null || base.length() < 7 || head.length() < 7) {
      return new ReviewDiffFormatter.FormattedDiff(
          "(regression comparison unavailable — refs too short)", 0);
    }
    try {
      var comparison = prClient.compareCommits(auth, ACCEPT, owner, repo, base, head);
      return diffFormatter.buildBaseComparisonWithStats(comparison, base, head);
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
  boolean botSummaryCommentExists(String auth, String owner, String repo, int prNumber) {
    for (var comment : fetchIssueComments(auth, owner, repo, prNumber)) {
      var user = comment.user();
      var body = comment.body();
      if (user != null
          && botIdentity.matches(user.login())
          && body != null
          && body.stripLeading().startsWith(PrSummaryGenerator.SUMMARY_HEADING)) {
        return true;
      }
    }
    return false;
  }

  List<GitHubCommentClient.IssueComment> fetchIssueComments(
      String auth, String owner, String repo, int prNumber) {
    try {
      // listComments paginates internally and always returns a non-null list (empty when there are
      // no comments), so the only best-effort fallback needed here is the fetch throwing.
      return commentClient.listComments(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.debug("Could not fetch PR issue comments (continuing as if none exist)", e);
      return List.of();
    }
  }

  List<GitHubReviewClient.PullRequestComment> fetchPullRequestComments(
      String auth, String owner, String repo, int prNumber) {
    try {
      // The client walks every page and never returns null, so the only failure to absorb here is
      // the fetch throwing.
      return reviewClient.listPullRequestComments(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch PR inline comments, continuing without thread context", e);
      return List.of();
    }
  }
}
