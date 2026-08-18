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
import dev.thiagogonzaga.thrillhousebot.review.ai.AiContextWindowExceededException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponseTruncatedException;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
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

  private static final String CHECK_STATUS_COMPLETED = "completed";

  private static final String CONCLUSION_FAILURE = "failure";

  /** FAILED check-run title when the failure was the model's response-length cap (#500). */
  static final String TRUNCATION_CHECK_TITLE = "Review failed: the AI response hit its length cap";

  /**
   * FAILED check-run summary for a truncation: names the cap and the knob(s) to raise, matching the
   * PR notice, instead of the bare conclusion-only update a generic failure gets.
   */
  static String truncationCheckSummary(boolean conciseModelImplicated) {
    return "The model's response was cut at its response-length cap (finish_reason=length), so the"
        + " review output was incomplete. Retrying without changing configuration would be cut at"
        + " the same point — raise the active model's max-output-tokens (or leave it unset to use"
        + " the provider default)"
        + (conciseModelImplicated
            ? ", and REVIEW_CONCISE_MAX_OUTPUT_TOKENS (the truncated call ran on the concise"
                + " model)"
            : "")
        + ", then run /review again.";
  }

  /** FAILED check-run title when the provider rejected the request as over the window (#622). */
  static final String CONTEXT_WINDOW_CHECK_TITLE =
      "Review failed: the request exceeded the model's context window";

  /**
   * FAILED check-run summary for a context-window rejection: names the deterministic cause and the
   * knob that shrinks the request, matching the PR notice, instead of the bare conclusion-only
   * update a generic failure gets.
   */
  static String contextWindowCheckSummary() {
    return "The provider rejected the review request because it exceeds the model's context"
        + " window — the diff and its context are too large for the model. Retrying the identical"
        + " request would be rejected identically — lower REVIEW_MAX_INPUT_TOKENS so the planned"
        + " material fits the window (token budgeting then batches or clips the diff), then run"
        + " /review again.";
  }

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

  private final FindingFeedbackCaptureService findingFeedbackCapture;

  private final ReviewSkipEmitter skipEmitter;

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
      FindingFeedbackCaptureService findingFeedbackCapture,
      ReviewSkipEmitter skipEmitter,
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
    this.findingFeedbackCapture = findingFeedbackCapture;
    this.skipEmitter = skipEmitter;
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
      var previousFindings = ctx.previousFindingsList();
      var inlineComments = ctx.inlineComments();
      var lineResolver = ctx.lineResolver();

      // Bound the one prompt section that grows every round before anything is sized or sent, so
      // the plan's overhead estimate and the text the calls actually carry are the same (#583).
      var promptInputs = budgetPlanner.boundPreviousFindings(promptAssembler.assemble(ctx, req));
      var plan = budgetPlanner.plan(ctx.reviewableFiles(), promptInputs);

      final var ciReq = req;
      var ciFuture =
          CompletableFuture.supplyAsync(() -> resolveCiEvaluation(auth, ciReq), reviewExecutor);

      var aiResponse = findingPipeline.run(session, promptInputs, ctx, plan, lineResolver);

      CiStatusEvaluator.CiEvaluation ciEvaluation = ciFuture.join();

      var result = verdictBuilder.build(ctx, aiResponse, ciEvaluation, plan);

      String conclusion = VerdictBuilder.conclusionForResult(result);
      String checkTitle = VerdictBuilder.checkTitleForResult(result);
      String checkSummary = VerdictBuilder.checkSummaryForResult(result);
      // #704: the model call can run for minutes; a push landing in that window supersedes this
      // run (the dispatcher already queued a coalesced run for the new head). Re-read the head
      // just before the first write and abandon the post when it moved, instead of posting a
      // review — and resolving inline comments — against a diff that changed underneath it.
      final var headReq = req;
      var movedHead =
          contextLoader.currentHeadSha(auth, req).filter(fresh -> headMoved(headReq, fresh));
      if (movedHead.isPresent()) {
        abandonSupersededRun(auth, req, session, checkRunId, movedHead.get());
        return false;
      }

      boolean summaryPosted = publishSummaryBestEffort(auth, req, result);
      // Opt-in follow-up delta comment. Runs only when no summary was posted this round, and its
      // outcome is intentionally discarded — it must not feed summaryPosted below.
      publishFollowUpDeltaBestEffort(auth, req, result, summaryPosted, previousFindings);
      reviewPublisher.dismissPendingBotReviews(
          auth, req.owner(), req.repo(), req.prNumber(), priorReviews);
      // summaryPosted gates the redundant-review skips: a failed summary post leaves review
      // posting enabled.
      reviewPublisher.postReview(
          new ReviewPublisher.PostReviewRequest(
              auth,
              req.owner(),
              req.repo(),
              req.prNumber(),
              req.commitSha(),
              result,
              lineResolver,
              summaryPosted,
              previousFindings));

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
                  auth, doneReq, previousFindings, inlineComments, result.previousStatuses()));
      runPostResultStep(
          doneReq,
          "capture finding feedback",
          () ->
              findingFeedbackCapture.captureOnPriorFindings(
                  auth, doneReq.owner(), doneReq.repo(), doneReq.prNumber(), inlineComments));
      runPostResultStep(
          doneReq,
          "apply labels",
          () -> reviewPublisher.applyLabels(auth, doneReq, result, aiResponse, ctx));
      runPostResultStep(
          doneReq,
          "complete the session",
          () -> {
            // Persist first: a failed write must skip the broadcast so persisted and live
            // dashboard state stay consistent.
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

  /** SKIPPED check-run title when the finished run's post was abandoned (#704). */
  static final String SUPERSEDED_CHECK_TITLE = "Review superseded by a newer commit";

  /**
   * Whether the freshly read head names a different commit than the one this run reviewed. False
   * when the run has no reviewed sha to compare against; a fresh read that failed never reaches
   * here (fail-open — a finished review is never lost to its own guard). Visible for tests.
   */
  static boolean headMoved(ReviewRequest req, String freshHead) {
    return req.commitSha() != null
        && !req.commitSha().isBlank()
        && !freshHead.equalsIgnoreCase(req.commitSha());
  }

  /**
   * Retires a run whose head moved while it reviewed: counted as a structured skip, the check run
   * on the reviewed (old) sha concluded as skipped, and the session closed out — nothing is posted
   * to the PR and no user-facing error is raised, because the dispatcher's coalesced run for the
   * new head re-reviews and posts in this run's place (#704). Visible for tests.
   */
  void abandonSupersededRun(
      String auth, ReviewRequest req, ReviewSession session, long checkRunId, String freshHead) {
    skipEmitter.recordSkip(
        ReviewSkipReason.HEAD_MOVED,
        req.owner(),
        req.repo(),
        req.prNumber(),
        "head moved from "
            + req.commitSha()
            + " to "
            + freshHead
            + " while the review ran — abandoning the post; the queued run for the new head"
            + " replaces it");
    if (checkRunId > 0) {
      try {
        checkRunManager.updateCheckRun(
            new CheckRunManager.CheckRunUpdate(
                auth,
                req.owner(),
                req.repo(),
                checkRunId,
                CHECK_STATUS_COMPLETED,
                "skipped",
                SUPERSEDED_CHECK_TITLE,
                "The pull request head moved to "
                    + freshHead
                    + " while this review ran, so its"
                    + " result was not posted. The review of the new head replaces it.",
                sessionUrl(session)));
      } catch (RuntimeException checkRunError) {
        Log.warnf(checkRunError, "Failed to mark superseded check run %d as skipped", checkRunId);
      }
    }
    try {
      applyReviewFailure(
          session, "Superseded: the PR head moved to " + freshHead + " during the review");
    } catch (RuntimeException persistenceError) {
      Log.warnf(persistenceError, "Failed to persist superseded review session %d", session.id);
    }
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.failed(session));
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
   * Posts the opt-in follow-up delta comment, swallowing any failure for the same reason {@link
   * #publishSummaryBestEffort} does: it is enrichment, not the review, so a transient failure here
   * must not abort before {@code postReview}. The result is not returned — the delta comment never
   * stands in for the review, so it can never gate the redundant-review skips.
   */
  private void publishFollowUpDeltaBestEffort(
      String auth,
      ReviewRequest req,
      ReviewResult result,
      boolean summaryPosted,
      List<ReviewResponse.Finding> previousFindings) {
    try {
      reviewPublisher.publishFollowUpDelta(
          auth, req.owner(), req.repo(), req.prNumber(), result, summaryPosted, previousFindings);
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Failed to post the follow-up delta comment for %s/%s #%d — continuing to post the"
              + " review",
          req.owner(),
          req.repo(),
          req.prNumber());
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
   * session. A failure caused by the model's response-length cap (anywhere in the cause chain) gets
   * truncation-specific copy on both surfaces (#500 scope B): the cap is named and the {@code
   * max-output-tokens} knob pointed at, because the generic notice's bare {@code /review} retry
   * advice is knowably futile for a deterministic truncation — and the FAILED check run carries the
   * same explanation instead of a bare conclusion.
   */
  void handleReviewFailure(
      String auth, ReviewRequest req, ReviewSession session, long checkRunId, RuntimeException e) {
    Log.errorf(e, "Review failed for %s/%s #%d", req.owner(), req.repo(), req.prNumber());
    var truncation = AiResponseTruncatedException.findIn(e);
    var contextWindow = AiContextWindowExceededException.findIn(e);

    String checkTitle = null;
    String checkSummary = null;
    if (truncation.isPresent()) {
      checkTitle = TRUNCATION_CHECK_TITLE;
      checkSummary = truncationCheckSummary(truncation.get().conciseModelImplicated());
    } else if (contextWindow.isPresent()) {
      checkTitle = CONTEXT_WINDOW_CHECK_TITLE;
      checkSummary = contextWindowCheckSummary();
    }

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
                checkTitle,
                checkSummary,
                sessionUrl(session)));
      } catch (RuntimeException checkRunError) {
        Log.warnf(checkRunError, "Failed to mark check run %d as failed", checkRunId);
      }
    }

    if (truncation.isPresent()) {
      reviewPublisher.postTruncationFailureNotice(
          auth, req.owner(), req.repo(), req.prNumber(), truncation.get().conciseModelImplicated());
    } else if (contextWindow.isPresent()) {
      reviewPublisher.postContextWindowFailureNotice(auth, req.owner(), req.repo(), req.prNumber());
    } else {
      reviewPublisher.postFailureNotice(auth, req.owner(), req.repo(), req.prNumber());
    }

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

  /**
   * Applies failure fields to the in-memory session and persisted entity together, carrying the
   * model's response with them when the run got far enough to have one.
   *
   * <p>#624: a review that dies while publishing has already paid for every model call it made, and
   * the response was written to the database only on the success path — so the one failure mode
   * where the findings are still worth something was the one that discarded them. Persisting it
   * here leaves the finished work on the session and on its dashboard page instead of only in a log
   * line. It stays out of the follow-up history either way: {@code findPreviousAiResponseJson} and
   * {@code findAllPriorAiResponseJsons} both select on {@code STATUS_COMPLETED}, so a failed round
   * cannot make the next one treat findings it never posted as already raised.
   */
  void applyReviewFailure(ReviewSession session, String errorMessage) {
    applySessionState(
        session,
        s -> {
          s.setStatus(ReviewSession.STATUS_FAILED);
          s.setErrorMessage(errorMessage);
          if (session.getAiResponseJson() != null) {
            s.setAiResponseJson(session.getAiResponseJson());
          }
        });
  }

  private void applySessionState(ReviewSession session, Consumer<ReviewSession> mutator) {
    mutator.accept(session);
    sessionPersistence.update(session.id, mutator);
  }
}
