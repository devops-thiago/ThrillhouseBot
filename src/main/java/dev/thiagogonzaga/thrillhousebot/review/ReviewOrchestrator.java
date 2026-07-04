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

import dev.thiagogonzaga.thrillhousebot.config.ReviewExecutor;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.dashboard.SessionEventBroadcaster;
import dev.thiagogonzaga.thrillhousebot.github.*;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

@ApplicationScoped
public class ReviewOrchestrator {

  // Check run status constant used when building the completion update (CheckRunManager owns
  // create).
  private static final String CHECK_STATUS_COMPLETED = "completed";

  // Check run conclusion constants
  private static final String CONCLUSION_FAILURE = "failure";

  private final ThrillhouseConfig config;
  private final GitHubAuthClient authClient;

  private final SessionEventBroadcaster broadcaster;

  private final ReviewSessionPersistence sessionPersistence;

  private final CiStatusEvaluator ciStatusEvaluator;

  private final CheckRunManager checkRunManager;

  private final ReviewContextLoader contextLoader;

  private final ReviewPromptAssembler promptAssembler;

  private final DiffBudgetPlanner budgetPlanner;

  private final ReviewPublisher reviewPublisher;

  private final VerdictBuilder verdictBuilder;

  private final FindingPipeline findingPipeline;

  // The CI status I/O (required-context resolution + check evaluation) is independent of the model
  // response, so it runs on this executor concurrently with the blocking AI call rather than after.
  private final ExecutorService reviewExecutor;

  /**
   * Parameter object for the {@link #review(ReviewRequest)} method.
   *
   * @param owner repository owner login
   * @param repo repository name
   * @param prNumber pull request number
   * @param commitSha HEAD commit SHA
   * @param prTitle PR title
   * @param prDescription PR body as written by the author (may be empty)
   * @param baseSha base branch SHA (for comparison)
   * @param defaultBranch repo default branch name
   * @param installationId GitHub App installation ID
   * @param isManualTrigger {@code true} when triggered by a /review command
   * @param baseRef PR base/target branch name, used to resolve required CI checks without an extra
   *     PR fetch; may be empty until {@link ReviewContextLoader#resolveMissingPrDetails} fills it
   * @param forceSummary {@code true} when the PR summary comment must be (re)posted even on a
   *     follow-up review — set by {@code /summary} to regenerate a summary that was deleted from
   *     the PR. Off by every other path, which posts the summary only on the first user-visible
   *     review
   */
  public record ReviewRequest(
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      String prTitle,
      String prDescription,
      String baseSha,
      String defaultBranch,
      long installationId,
      boolean isManualTrigger,
      String baseRef,
      boolean forceSummary) {

    /**
     * Back-compat convenience for callers that carry the base ref but never force the summary — the
     * automatic {@code pull_request} path and tests. Defaults {@code forceSummary} to {@code
     * false}.
     */
    public ReviewRequest(
        String owner,
        String repo,
        int prNumber,
        String commitSha,
        String prTitle,
        String prDescription,
        String baseSha,
        String defaultBranch,
        long installationId,
        boolean isManualTrigger,
        String baseRef) {
      this(
          owner,
          repo,
          prNumber,
          commitSha,
          prTitle,
          prDescription,
          baseSha,
          defaultBranch,
          installationId,
          isManualTrigger,
          baseRef,
          false);
    }

    /**
     * Back-compat convenience for callers that don't yet carry the base ref — the manual /review
     * entry points (filled in later by {@link ReviewContextLoader#resolveMissingPrDetails}) and
     * tests. Defaults {@code baseRef} to empty and {@code forceSummary} to {@code false}, so the CI
     * resolver gates on all checks until known.
     */
    public ReviewRequest(
        String owner,
        String repo,
        int prNumber,
        String commitSha,
        String prTitle,
        String prDescription,
        String baseSha,
        String defaultBranch,
        long installationId,
        boolean isManualTrigger) {
      this(
          owner,
          repo,
          prNumber,
          commitSha,
          prTitle,
          prDescription,
          baseSha,
          defaultBranch,
          installationId,
          isManualTrigger,
          "");
    }
  }

  @Inject
  public ReviewOrchestrator(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      SessionEventBroadcaster broadcaster,
      ReviewSessionPersistence sessionPersistence,
      CiStatusEvaluator ciStatusEvaluator,
      CheckRunManager checkRunManager,
      ReviewContextLoader contextLoader,
      ReviewPromptAssembler promptAssembler,
      DiffBudgetPlanner budgetPlanner,
      ReviewPublisher reviewPublisher,
      VerdictBuilder verdictBuilder,
      FindingPipeline findingPipeline,
      @ReviewExecutor ExecutorService reviewExecutor) {
    this.config = config;
    this.authClient = authClient;
    this.broadcaster = broadcaster;
    this.sessionPersistence = sessionPersistence;
    this.ciStatusEvaluator = ciStatusEvaluator;
    this.checkRunManager = checkRunManager;
    this.contextLoader = contextLoader;
    this.promptAssembler = promptAssembler;
    this.budgetPlanner = budgetPlanner;
    this.reviewPublisher = reviewPublisher;
    this.verdictBuilder = verdictBuilder;
    this.findingPipeline = findingPipeline;
    this.reviewExecutor = reviewExecutor;
  }

  /**
   * Main entry point for reviewing a PR. Called from WebhookController for pull_request (opened,
   * synchronize) and /review triggers.
   *
   * @return {@code true} when the review result was surfaced to GitHub (post-result steps may still
   *     have failed); {@code false} when the review failed before posting, so callers must not
   *     treat it as a completed review (e.g. for rate-limit accounting).
   */
  @ActivateRequestContext
  public boolean review(ReviewRequest request) {
    Log.infof(
        "Starting review for %s/%s #%d (sha: %s)",
        request.owner(), request.repo(), request.prNumber(), request.commitSha());
    var repository = request.owner() + "/" + request.repo();
    var auth = authClient.getAuthHeader(request.installationId());

    ReviewSession session =
        ReviewSession.create(
            repository, request.prNumber(), request.prTitle(), request.commitSha());
    sessionPersistence.create(session);
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(session));

    // Resolution happens inside the try: a failing PR fetch (deleted PR, rate limit) must take
    // the same failure path as any other error — comment, failed session, broadcast.
    var req = request;
    var checkRunId = -1L;
    var resultSurfaced = false;
    try {
      var resolved = contextLoader.resolveMissingPrDetails(auth, request);
      req = resolved;
      if (resolved != request) {
        applySessionState(
            session,
            s -> {
              s.setPrTitle(resolved.prTitle());
              s.setCommitSha(resolved.commitSha());
            });
      }

      checkRunId =
          checkRunManager.createCheckRun(
              auth, req.owner(), req.repo(), req.commitSha(), sessionUrl(session));
      var ctx = contextLoader.load(auth, req, session, repository);
      var priorReviews = ctx.priorReviews();
      var previousAiResponseJson = ctx.previousAiResponseJson();
      var inlineComments = ctx.inlineComments();
      var lineResolver = ctx.lineResolver();

      var promptInputs = promptAssembler.assemble(ctx, req);
      // Planned from the fully assembled prompt so the overhead estimate covers every section a
      // review call actually repeats — not just the loader-visible subset (#53).
      var plan = budgetPlanner.plan(ctx.reviewableFiles(), promptInputs);

      // CI status (required-context resolution + check evaluation) depends only on the
      // commit/branch,
      // not the model response, so kick it off concurrently with the blocking AI call and join
      // after
      // — the GitHub latency overlaps the model latency instead of stacking on top of it.
      final var ciReq = req;
      var ciFuture =
          CompletableFuture.supplyAsync(() -> resolveCiEvaluation(auth, ciReq), reviewExecutor);

      var aiResponse = findingPipeline.run(session, promptInputs, ctx, plan, lineResolver);

      CiStatusEvaluator.CiEvaluation ciEvaluation = ciFuture.join();

      var result = verdictBuilder.build(ctx, aiResponse, ciEvaluation, plan);

      String conclusion = VerdictBuilder.conclusionForResult(result);
      String checkTitle = VerdictBuilder.checkTitleForResult(result);
      String checkSummary = VerdictBuilder.checkSummaryForResult(result);
      // The summary comment is first-review enrichment, not the review itself: a transient failure
      // posting it must not abort before postReview and surface a hard FAILED check for a review
      // that would otherwise post. Keep it best-effort; the review below is the critical step.
      boolean summaryPosted = publishSummaryBestEffort(auth, req, result);
      reviewPublisher.dismissPendingBotReviews(
          auth, req.owner(), req.repo(), req.prNumber(), priorReviews);
      // The summary-only skip applies only when the regenerated summary actually landed on the
      // PR — a failed best-effort summary post must leave the review as the run's visible outcome.
      reviewPublisher.postReview(
          auth,
          req.owner(),
          req.repo(),
          req.prNumber(),
          req.commitSha(),
          result,
          lineResolver,
          req.forceSummary() && summaryPosted);

      // The review and its comments are on the PR now. Everything past this point is
      // best-effort and independent: each step runs in isolation so one failure can't abort the
      // rest. In particular a failure in an earlier step must not skip the session completion and
      // broadcast, which would otherwise leave the session stuck IN_PROGRESS in the dashboard even
      // though the review was posted.
      resultSurfaced = true;
      final var doneReq = req;
      final var concludedCheckRunId = checkRunId;
      runPostResultStep(
          doneReq,
          "conclude the check run",
          () ->
              checkRunManager.updateCheckRun(
                  new CheckRunManager.CheckRunUpdate(
                      auth,
                      doneReq.owner(),
                      doneReq.repo(),
                      concludedCheckRunId,
                      CHECK_STATUS_COMPLETED,
                      conclusion,
                      checkTitle,
                      checkSummary,
                      sessionUrl(session))));
      runPostResultStep(
          doneReq,
          "resolve addressed threads",
          () ->
              reviewPublisher.resolveAddressedThreads(
                  auth,
                  doneReq,
                  previousAiResponseJson,
                  inlineComments,
                  aiResponse.previousFindingsStatus()));
      runPostResultStep(
          doneReq,
          "apply labels",
          () -> reviewPublisher.applyLabels(auth, doneReq, result, aiResponse, ctx));
      runPostResultStep(
          doneReq,
          "complete the session",
          () -> {
            // Persist the completed status FIRST, then broadcast: the broadcast must not fire if
            // the
            // persistence write throws, or the dashboard would show a "completed" event over a row
            // still IN_PROGRESS (and revert to IN_PROGRESS on reload). Keeping them in one step
            // means
            // a failed write skips the broadcast, so persisted and live state stay consistent.
            applyReviewResult(session, result);
            broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.completed(session));
          });

      Log.infof(
          "Review complete for %s/%s #%d: %d findings, state=%s",
          req.owner(), req.repo(), req.prNumber(), result.totalFindings(), result.reviewState());
    } catch (RuntimeException e) {
      if (resultSurfaced) {
        Log.warnf(
            e,
            "Review for %s/%s #%d was posted, but a post-result step failed",
            req.owner(),
            req.repo(),
            req.prNumber());
      } else {
        handleReviewFailure(auth, req, session, checkRunId, e);
      }
    }
    return resultSurfaced;
  }

  /**
   * Posts the PR summary comment, swallowing any failure: it is first-review enrichment, not the
   * review itself, so a transient failure here must not abort before {@code postReview} and surface
   * a hard FAILED check for a review that would otherwise post.
   *
   * @return {@code true} when the summary comment was actually created — {@code false} on a skip or
   *     a swallowed failure, so the summary-only review skip never fires without a summary on the
   *     PR.
   */
  private boolean publishSummaryBestEffort(String auth, ReviewRequest req, ReviewResult result) {
    try {
      return reviewPublisher.publishSummary(
          auth, req.owner(), req.repo(), req.prNumber(), result, req.forceSummary());
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Failed to post the PR summary comment for %s/%s #%d — continuing to post the review",
          req.owner(),
          req.repo(),
          req.prNumber());
      return false;
    }
  }

  /**
   * Resolves the CI evaluation for a request: the required-context lookup unioned across rulesets
   * and classic protection, then the per-check evaluation on the head commit. Runs off the review
   * executor concurrently with the blocking AI call — it depends only on the commit and base branch
   * carried on the request, not the model response.
   */
  private CiStatusEvaluator.CiEvaluation resolveCiEvaluation(String auth, ReviewRequest req) {
    List<String> requiredContexts =
        ciStatusEvaluator
            .resolveRequiredContexts(auth, req.owner(), req.repo(), req.baseRef())
            .orElse(null);
    return ciStatusEvaluator.evaluateCiChecks(
        auth, req.owner(), req.repo(), req.commitSha(), requiredContexts);
  }

  /**
   * Runs one post-result step in isolation. Once the review and its comments are on the PR the
   * remaining work — check-run conclusion, thread resolution, labels, session completion,
   * completion broadcast — is best-effort: a failure in one step is logged and the others still
   * run, so a hiccup can't leave the session stuck IN_PROGRESS or swallow the completion broadcast.
   * The review is already surfaced, so none of these failures should flip it to FAILED.
   */
  private void runPostResultStep(ReviewRequest req, String step, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Review for %s/%s #%d was posted, but post-result step '%s' failed",
          req.owner(),
          req.repo(),
          req.prNumber(),
          step);
    }
  }

  /**
   * Public dashboard deep-link for a review session, used in check runs and comments. Built from
   * the session's random public id — never the guessable sequential id. Sessions without one
   * (pre-migration rows) link to the sessions list instead.
   */
  String sessionUrl(ReviewSession session) {
    var base = config.dashboard().url();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    var publicId = session.getPublicId();
    if (publicId == null || publicId.isBlank()) {
      return base + "/dashboard/sessions/";
    }
    return base + "/session/" + publicId;
  }

  /**
   * Handles a review failure: updates the check run, posts an error comment, and updates the
   * session.
   */
  void handleReviewFailure(
      String auth, ReviewRequest req, ReviewSession session, long checkRunId, RuntimeException e) {
    Log.errorf(e, "Review failed for %s/%s #%d", req.owner(), req.repo(), req.prNumber());

    if (checkRunId > 0) {
      try {
        checkRunManager.updateCheckRun(
            new CheckRunManager.CheckRunUpdate(
                auth,
                req.owner(),
                req.repo(),
                checkRunId,
                CHECK_STATUS_COMPLETED,
                CONCLUSION_FAILURE,
                null,
                null,
                sessionUrl(session)));
      } catch (RuntimeException checkRunError) {
        Log.warnf(checkRunError, "Failed to mark check run %d as failed", checkRunId);
      }
    }

    reviewPublisher.postFailureNotice(auth, req.owner(), req.repo(), req.prNumber());

    String errorMessage =
        e.getMessage() != null
            ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 200))
            : "Unknown error";
    try {
      applyReviewFailure(session, errorMessage);
    } catch (RuntimeException persistenceError) {
      Log.warnf(persistenceError, "Failed to persist failed review session %d", session.id);
    }
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.failed(session));
  }

  /** Applies review completion fields to the in-memory session and persisted entity together. */
  void applyReviewResult(ReviewSession session, ReviewResult result) {
    applySessionState(
        session,
        s -> {
          s.setStatus(ReviewSession.STATUS_COMPLETED);
          s.setCriticalFindings(result.criticalCount());
          s.setHighFindings(result.highCount());
          s.setMediumFindings(result.mediumCount());
          s.setLowFindings(result.lowCount());
          if (session.getAiResponseJson() != null) {
            s.setAiResponseJson(session.getAiResponseJson());
          }
        });
  }

  /** Applies failure fields to the in-memory session and persisted entity together. */
  void applyReviewFailure(ReviewSession session, String errorMessage) {
    applySessionState(
        session,
        s -> {
          s.setStatus(ReviewSession.STATUS_FAILED);
          s.setErrorMessage(errorMessage);
        });
  }

  private void applySessionState(ReviewSession session, Consumer<ReviewSession> mutator) {
    mutator.accept(session);
    sessionPersistence.update(session.id, mutator);
  }
}
