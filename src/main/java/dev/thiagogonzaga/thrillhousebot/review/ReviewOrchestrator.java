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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.config.ReviewExecutor;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.dashboard.SessionEventBroadcaster;
import dev.thiagogonzaga.thrillhousebot.github.*;
import dev.thiagogonzaga.thrillhousebot.notification.ReviewNotifier;
import dev.thiagogonzaga.thrillhousebot.observability.ReviewOutcomeMetrics;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiContextWindowExceededException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponseTruncatedException;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.TruncationReport;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

@ApplicationScoped
public class ReviewOrchestrator {

  private static final String CHECK_STATUS_COMPLETED = "completed";

  private static final String CONCLUSION_FAILURE = "failure";

  /**
   * FAILED check-run title when the failure was a length stop (#500). Not "its cap": the provider
   * can stop a call short of the cap the request licensed (#895).
   */
  static final String TRUNCATION_CHECK_TITLE = "Review failed: the AI response was cut short";

  /**
   * FAILED check-run summary for a truncation, matching the PR notice instead of the bare
   * conclusion-only update a generic failure gets: the licensed cap and the setting that supplied
   * it, the billed usage, and the setting to raise only when the stop reached the cap (#895).
   */
  static String truncationCheckSummary(TruncationReport report) {
    return "The model's response was cut on a length limit (finish_reason=length), so the review"
        + " output was incomplete. "
        + report.describe(TruncationReport.PLAIN)
        + (report.advisesASettingChange()
            ? " Retrying without that change would be cut the same way; make it, then run /review"
                + " again."
            : "");
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
  private final CiFailureContextResolver ciFailureContext;
  private final TicketContextResolver ticketContext;
  private final ReviewLearnings learnings;

  private final CheckRunManager checkRunManager;

  private final ReviewContextLoader contextLoader;

  private final ReviewPromptAssembler promptAssembler;

  private final DiffBudgetPlanner budgetPlanner;

  private final ReviewPublisher reviewPublisher;

  private final VerdictBuilder verdictBuilder;

  private final FindingPipeline findingPipeline;
  private final CitedLocationResolver citedLocationResolver;

  private final ReviewOutcomeMetrics outcomeMetrics;

  private final FindingFeedbackCaptureService findingFeedbackCapture;

  private final ReviewStandDown standDown;

  private final CiHoldRegistry ciHoldRegistry;

  private final ExecutorService reviewExecutor;

  private final ReviewNotifier notifier;

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
   * @param repositoryId GitHub's numeric repository id from the webhook payload; with {@code
   *     installationId} it scopes every stored row of the review (#1220)
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
      long repositoryId,
      boolean isManualTrigger,
      String baseRef,
      boolean forceSummary) {

    /** The installation and repository every row this review stores is scoped to. */
    public RepoScope scope() {
      return new RepoScope(installationId, repositoryId);
    }

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
        long repositoryId,
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
          repositoryId,
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
        long repositoryId,
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
          repositoryId,
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
      CitedLocationResolver citedLocationResolver,
      ReviewOutcomeMetrics outcomeMetrics,
      FindingFeedbackCaptureService findingFeedbackCapture,
      ReviewStandDown standDown,
      CiHoldRegistry ciHoldRegistry,
      @ReviewExecutor ExecutorService reviewExecutor,
      ReviewNotifier notifier,
      CiFailureContextResolver ciFailureContext,
      TicketContextResolver ticketContext,
      ReviewLearnings learnings) {
    this.config = config;
    this.authClient = authClient;
    this.broadcaster = broadcaster;
    this.sessionPersistence = sessionPersistence;
    this.ciStatusEvaluator = ciStatusEvaluator;
    this.ciFailureContext = ciFailureContext;
    this.ticketContext = ticketContext;
    this.learnings = learnings;
    this.checkRunManager = checkRunManager;
    this.contextLoader = contextLoader;
    this.promptAssembler = promptAssembler;
    this.budgetPlanner = budgetPlanner;
    this.reviewPublisher = reviewPublisher;
    this.verdictBuilder = verdictBuilder;
    this.findingPipeline = findingPipeline;
    this.citedLocationResolver = citedLocationResolver;
    this.outcomeMetrics = outcomeMetrics;
    this.findingFeedbackCapture = findingFeedbackCapture;
    this.standDown = standDown;
    this.ciHoldRegistry = ciHoldRegistry;
    this.reviewExecutor = reviewExecutor;
    this.notifier = notifier;
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
            request.scope(),
            repository,
            request.prNumber(),
            request.prTitle(),
            request.commitSha());
    sessionPersistence.create(session);
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(session));

    var req = request;
    var checkRunId = -1L;
    var resultSurfaced = false;
    var tracked = false;
    var heldOnCi = false;
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
      // Tracked before the CI gate is read (#825): CI that completes during the model call
      // completes after that read, and its event must still find this pull request so a recheck
      // can be queued behind this run.
      ciHoldRegistry.track(req.owner(), req.repo(), req.prNumber(), req.commitSha());
      tracked = true;

      checkRunId =
          checkRunManager.createCheckRun(
              auth, req.owner(), req.repo(), req.commitSha(), sessionUrl(session));
      final var ciReq = req;
      // With the CI-failure context on (#59) the early CI reading feeds the prompt, so it starts
      // alongside the context load and is joined before the prompt is assembled; its failing
      // checks are the ones the gate reads, not a second fetch. Off, the reading keeps its place
      // beside the model call, so the gate reads CI as late as it did before.
      var ciFuture = ciFailureContext.enabled() ? startCiEvaluation(auth, ciReq) : null;
      var ctx = contextLoader.load(auth, req, session, repository);
      var priorReviews = ctx.priorReviews();
      var previousFindings = ctx.previousFindingsList();
      var inlineComments = ctx.inlineComments();
      var lineResolver = ctx.lineResolver();
      var ciFailures =
          ciFuture == null
              ? ""
              : ciFailureContext.resolve(auth, req.owner(), req.repo(), ciFuture.join());
      // #58: the linked issues' text, read-only and best-effort; "" when the feature is off.
      var linkedIssues =
          ticketContext.resolve(auth, req.owner(), req.repo(), req.prNumber(), req.prDescription());

      // #38: what maintainers taught the review on earlier pull requests, bounded and relevant to
      // the files this one changes; blank when the feature is off.
      var learned = recallLearnings(req, ctx);
      // Bound the one prompt section that grows every round before anything is sized or sent, so
      // the plan's overhead estimate and the text the calls actually carry are the same (#583).
      var promptInputs =
          budgetPlanner.boundPreviousFindings(
              promptAssembler.assemble(
                  ctx,
                  req,
                  new ReviewPromptAssembler.TrailingContext(
                      ciFailures, linkedIssues, learned.section())));
      var plan = budgetPlanner.plan(ctx.reviewableFiles(), promptInputs);

      if (ciFuture == null) {
        ciFuture = startCiEvaluation(auth, ciReq);
      }

      // #650/#475: one evidence round for the whole review, so the batches share its fetch budget,
      // its file cache, and the single character budget every attached note is charged to. The
      // findings' evidence reads only files this pull request changes; the summary reads at most a
      // few more, the ones the description cites as already meeting a linked-issue criterion.
      var evidence =
          ReviewEvidence.forReview(
                  citedLocationResolver,
                  auth,
                  req.owner(),
                  req.repo(),
                  req.commitSha(),
                  ctx,
                  ciFailures)
              .withLearnings(learned.selected());
      var reviewed = findingPipeline.run(session, promptInputs, ctx, plan, lineResolver, evidence);

      var earlyCi = ciFuture.join();
      CiStatusEvaluator.CiEvaluation ciEvaluation = rereadCiIfHeld(auth, ciReq, earlyCi);
      var renderedCiFailures = refreshCiFailures(auth, ciReq, ciFailures, earlyCi, ciEvaluation);
      var aiResponse =
          floorOnRefreshedCi(
              session, reviewed, ciFailures, renderedCiFailures, ctx.reviewableFiles());

      // #1074: the description findings the summary names must quote or paraphrase this text.
      var result =
          verdictBuilder.build(
              ctx,
              aiResponse,
              ciEvaluation,
              plan,
              PromptSections.prContext(req.prTitle(), req.prDescription()) + "\n" + linkedIssues,
              CiFailureCitations.parse(renderedCiFailures, ctx.reviewableFiles()),
              // #1148: the description alone, never the issue text, is read for citations.
              new VerdictBuilder.LinkedIssueContext(
                  linkedIssues,
                  BaseCodeCitations.of(
                      req.prDescription(), ctx.files(), evidence.citedLocations())));

      String conclusion = VerdictBuilder.conclusionForResult(result);
      String checkTitle = VerdictBuilder.checkTitleForResult(result);
      String checkSummary = VerdictBuilder.checkSummaryForResult(result);
      // #704/#1021: the model call can run for minutes. A push landing in that window supersedes
      // this run (the dispatcher already queued a coalesced run for the new head), and a label
      // added meanwhile — Dependabot labels a pull request a second or two after opening it — can
      // make a label gate reject it. Both are honoured just before the first write, instead of
      // posting a review — and resolving inline comments — that no longer applies.
      var stoodDown =
          standDown.beforeFirstWrite(
              new PrePublishGuard.Review(
                  auth, req, checkRunId, sessionUrl(session), ctx, aiResponse, result));
      if (stoodDown.isPresent()) {
        closeStoodDownSession(session, stoodDown.get());
        return false;
      }

      boolean summaryPosted = publishSummaryBestEffort(auth, req, result);
      // The optional delta enriches the review, never a second notification beside it.
      var followUpDelta =
          followUpDeltaBestEffort(result, summaryPosted && req.forceSummary(), previousFindings);
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
              previousFindings,
              followUpDelta,
              priorReviews));

      resultSurfaced = true;
      final var doneReq = req;
      final var concludedCheckRunId = checkRunId;
      heldOnCi = holdVerdictOnCi(doneReq, session, concludedCheckRunId, result);
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
                  auth,
                  doneReq.scope(),
                  doneReq.owner(),
                  doneReq.repo(),
                  doneReq.prNumber(),
                  inlineComments));
      if (learnings.enabled()) {
        runPostResultStep(
            doneReq,
            "capture review learnings",
            () ->
                learnings.captureSurvivingDeclines(
                    new ReviewLearnings.DeclineCapture(
                        auth,
                        doneReq.scope(),
                        doneReq.owner(),
                        doneReq.repo(),
                        doneReq.prNumber(),
                        previousFindings,
                        result.previousStatuses(),
                        inlineComments,
                        ctx.conversationComments(),
                        () -> VerdictBuilder.reviewedCode(ctx, plan))));
      }
      runPostResultStep(
          doneReq,
          "apply labels",
          () -> reviewPublisher.applyLabels(auth, doneReq, result, aiResponse, ctx));
      runPostResultStep(
          doneReq,
          "complete the session",
          () -> {
            // #1066: the earlier rounds' findings this round closed are recorded with the round,
            // so the next round's replay no longer carries them and does not close or name them
            // again.
            var closedEarlier = result.earlierRoundClosedMembers();
            if (!closedEarlier.isEmpty()) {
              findingPipeline.persistAiResponse(
                  session, aiResponse.withClosedEarlierRound(closedEarlier));
            }
            // Persist first: a failed write must skip the broadcast so persisted and live
            // dashboard state stay consistent.
            applyReviewResult(session, result);
            broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.completed(session));
          });
      // #73: the verdict is final for this run once it is on the pull request. A superseded run
      // returned above without reaching here, and a verdict held on CI is announced now, not again
      // when CiHoldRevisit posts the approval.
      runPostResultStep(
          doneReq,
          "send the outcome notification",
          () -> notifier.reviewCompleted(doneReq, sessionUrl(session), result));

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
    } finally {
      // Every outcome but a CI hold — a verdict with findings, a failure, a superseded run — is
      // final for this head, so a CI completion for it has nothing to revisit. A run that failed
      // before it tracked its head leaves whatever the pull request held alone.
      if (tracked && !heldOnCi) {
        ciHoldRegistry.release(req.owner(), req.repo(), req.prNumber());
      }
    }
    return resultSurfaced;
  }

  /** The exact bounded recall used by both the prompt and the deterministic decline check. */
  private ReviewLearnings.Recall recallLearnings(
      ReviewRequest req, ReviewContextLoader.ReviewContext ctx) {
    return learnings.enabled()
        ? learnings.recall(
            req.scope(),
            req.owner(),
            req.repo(),
            ctx.reviewableFiles().stream().map(GitHubPullRequestClient.FileDiff::filename).toList())
        : ReviewLearnings.Recall.EMPTY;
  }

  /**
   * Records the verdict when the strict CI gate was all that kept it from APPROVE (#825), so the CI
   * completion for this head can post the approval without another review.
   *
   * @return whether the verdict was held
   */
  private boolean holdVerdictOnCi(
      ReviewRequest req, ReviewSession session, long checkRunId, ReviewResult result) {
    if (!result.heldOnCiOnly()) {
      return false;
    }
    ciHoldRegistry.hold(
        req.owner(),
        req.repo(),
        req.prNumber(),
        new CiHoldRegistry.HeldVerdict(
            req.commitSha(),
            req.baseRef(),
            checkRunId,
            sessionUrl(session),
            req.isManualTrigger()));
    Log.infof(
        "Holding the approval of %s/%s #%d on CI for %s — re-evaluated when its checks complete",
        req.owner(), req.repo(), req.prNumber(), req.commitSha());
    return true;
  }

  /**
   * Closes out the session of a run that stood down before its first write ({@link
   * ReviewStandDown}). Nothing is posted for it, and the stand-down already handed a superseded
   * run's findings to its replacement. Visible for tests.
   */
  void closeStoodDownSession(ReviewSession session, PrePublishGuard.StandDown stoodDown) {
    try {
      applyReviewFailure(session, stoodDown.sessionError());
    } catch (RuntimeException persistenceError) {
      Log.warnf(
          persistenceError, "Failed to persist %s review session %d", stoodDown.kind(), session.id);
    }
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.failed(session));
  }

  /**
   * Posts or edits the PR summary comment, swallowing any failure: it is enrichment, not the review
   * itself, so a transient failure here must not abort before {@code postReview} and surface a hard
   * FAILED check for a review that would otherwise post.
   *
   * @return {@code true} when the summary comment stands in for the round's outcome (see {@link
   *     ReviewPublisher#publishSummary}) — {@code false} on a skip, an ordinary follow-up edit, or
   *     a swallowed failure, so the summary-only review skip never fires without a summary on the
   *     PR.
   */
  private boolean publishSummaryBestEffort(String auth, ReviewRequest req, ReviewResult result) {
    try {
      return reviewPublisher.publishSummary(
          auth,
          req.owner(),
          req.repo(),
          req.prNumber(),
          result,
          req.forceSummary(),
          PromptSections.prContext(req.prTitle(), req.prDescription()));
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

  /** Optional delta rendering cannot suppress the review if enrichment fails. */
  private String followUpDeltaBestEffort(
      ReviewResult result,
      boolean summaryCommandPosted,
      List<ReviewResponse.Finding> previousFindings) {
    try {
      return reviewPublisher.followUpDeltaSection(result, summaryCommandPosted, previousFindings);
    } catch (RuntimeException e) {
      Log.warn("Failed to render the follow-up delta — continuing with the review outcome", e);
      return "";
    }
  }

  /**
   * Resolves the CI evaluation for a request: the required-context lookup unioned across rulesets
   * and classic protection, then the per-check evaluation on the head commit. Runs off the review
   * executor concurrently with the blocking AI call — it depends only on the commit and base branch
   * carried on the request, not the model response — and again after the call when that early
   * reading held approval ({@link #rereadCiIfHeld}).
   */
  private CompletableFuture<CiStatusEvaluator.CiEvaluation> startCiEvaluation(
      String auth, ReviewRequest req) {
    return CompletableFuture.supplyAsync(() -> resolveCiEvaluation(auth, req), reviewExecutor);
  }

  private CiStatusEvaluator.CiEvaluation resolveCiEvaluation(String auth, ReviewRequest req) {
    return ciStatusEvaluator.evaluate(
        auth, req.owner(), req.repo(), req.commitSha(), req.baseRef());
  }

  /**
   * The CI reading the verdict is built from (#853). The early reading is taken concurrently with a
   * model call that routinely runs for minutes, so a required check that was pending, failing or
   * unreadable then may well have finished green by the time the verdict is built. When the early
   * reading holds approval under the configured gating — the same decision the verdict makes — CI
   * is read once more and the fresh reading wins; otherwise the early reading stands and nothing
   * else is fetched. A fresh reading that still holds leads to the CI hold (#825) exactly as the
   * early one did, and one that no longer holds lets the verdict approve without placing a hold.
   */
  private CiStatusEvaluator.CiEvaluation rereadCiIfHeld(
      String auth, ReviewRequest req, CiStatusEvaluator.CiEvaluation early) {
    if (!verdictBuilder.ciHoldsApproval(early)) {
      return early;
    }
    var fresh = resolveCiEvaluation(auth, req);
    if (!verdictBuilder.ciHoldsApproval(fresh)) {
      Log.infof(
          "CI for %s/%s #%d no longer holds approval after the model call — building the verdict"
              + " from the re-read (held earlier by: %s)",
          req.owner(), req.repo(), req.prNumber(), heldByNames(early));
    }
    return fresh;
  }

  /**
   * The CI-failure section the summary is rendered with (#1092). The section the review call read
   * was built from the review-start reading, and a check still running then often fails before the
   * review posts. The re-read {@link #rereadCiIfHeld} took is used when there is one (a required
   * check not yet reported at review start holds approval without counting as pending); otherwise,
   * when the early reading counted a pending check or saw no check at all, the check runs are read
   * with one call: GitHub often creates a pull request's check suites a minute after the event that
   * started the review, and on all 12 round-19 corpus PRs the review-start reading saw none.
   * Nothing is read when every check it saw had finished, and a failed read keeps the review-start
   * section.
   */
  private String refreshCiFailures(
      String auth,
      ReviewRequest req,
      String section,
      CiStatusEvaluator.CiEvaluation early,
      CiStatusEvaluator.CiEvaluation verdictReading) {
    if (!ciFailureContext.enabled() || (verdictReading == early && settled(early.failures()))) {
      return section;
    }
    var fresh =
        verdictReading == early
            ? ciStatusEvaluator.checkRunFailures(auth, req.owner(), req.repo(), req.commitSha())
            : Optional.of(verdictReading.failures());
    return fresh
        .map(
            failures ->
                ciFailureContext.refresh(
                    auth, req.owner(), req.repo(), section, early.failures(), failures))
        .orElse(section);
  }

  /** Whether a CI reading saw checks and none of them was still running. */
  private static boolean settled(CiStatusEvaluator.CiFailures failures) {
    return failures.pending() == 0 && failures.reported() > 0;
  }

  /**
   * {@code reviewed} with the established-evidence confidence floor applied once more, over the CI
   * section refreshed after the model call (#1092, #952). The verifier floored the findings that
   * cite a check the review-start reading saw failing; a finding citing a check that failed while
   * the review ran is lifted from low to medium confidence here, and the round is persisted again,
   * since later rounds read it from the stored response. A failing test is cited by name or by an
   * assertion of its body in {@code files} the finding quotes. Unchanged when the section was not
   * refreshed or no finding cites what it added.
   */
  private ReviewResponse floorOnRefreshedCi(
      ReviewSession session,
      ReviewResponse reviewed,
      String section,
      String refreshed,
      List<GitHubPullRequestClient.FileDiff> files) {
    if (refreshed.equals(section)) {
      return reviewed;
    }
    var floored =
        FindingVerificationService.floorCiCitedConfidence(
            reviewed, CiFailureCitations.parse(refreshed, files));
    if (floored != reviewed) {
      findingPipeline.persistAiResponse(session, floored);
    }
    return floored;
  }

  /** The check names an early CI reading held approval on, flattened for one log line. */
  private static String heldByNames(CiStatusEvaluator.CiEvaluation evaluation) {
    if (evaluation.offendingChecks().isEmpty()) {
      return "an unreadable CI source";
    }
    return String.join(
        ", ",
        evaluation.offendingChecks().stream().map(check -> LogSafe.oneLine(check.name())).toList());
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
      checkSummary = truncationCheckSummary(truncation.get().report());
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
          auth, req.owner(), req.repo(), req.prNumber(), truncation.get().report());
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
    notifier.reviewFailed(req, sessionUrl(session), e);
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
    outcomeMetrics.recordCompleted();
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
    outcomeMetrics.recordFailed();
  }

  private void applySessionState(ReviewSession session, Consumer<ReviewSession> mutator) {
    mutator.accept(session);
    sessionPersistence.update(session.id, mutator);
  }
}
