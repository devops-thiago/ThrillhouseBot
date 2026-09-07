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

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Re-evaluates the CI gate for a verdict the strict gate held back, once CI reports on the head it
 * was held on (#825). Only the gate is re-read: when it clears, the APPROVE the review already
 * earned is posted and the check run concluded {@code success}; when it does not, the hold stays
 * and the check run's summary says what is still outstanding. The model is never called — the
 * findings did not change, only CI did.
 *
 * <p>Runs on the dispatcher's per-pull-request worker, serialized after any review in flight for
 * the pull request, so it can never post against a head a newer review is about to replace; the
 * head is re-read before posting all the same, the way {@link ReviewOrchestrator} guards its own
 * post, in case the push landed between the review's release and this run.
 */
@ApplicationScoped
public class CiHoldRevisit {

  /**
   * One CI completion to act on: the pull request whose tracked or held head it reported on, and
   * the installation to act as.
   */
  public record Recheck(
      String owner, String repo, int prNumber, String headSha, long installationId) {}

  /** Appended to the refreshed check-run summary while the hold stays. */
  static final String REVISIT_NOTE = "Approval is re-evaluated as the remaining checks complete.";

  private final GitHubAuthClient authClient;
  private final ReviewContextLoader contextLoader;
  private final CiStatusEvaluator ciStatusEvaluator;
  private final VerdictBuilder verdictBuilder;
  private final CheckRunManager checkRunManager;
  private final ReviewPublisher reviewPublisher;
  private final CiHoldRegistry registry;

  @Inject
  public CiHoldRevisit(
      GitHubAuthClient authClient,
      ReviewContextLoader contextLoader,
      CiStatusEvaluator ciStatusEvaluator,
      VerdictBuilder verdictBuilder,
      CheckRunManager checkRunManager,
      ReviewPublisher reviewPublisher,
      CiHoldRegistry registry) {
    this.authClient = authClient;
    this.contextLoader = contextLoader;
    this.ciStatusEvaluator = ciStatusEvaluator;
    this.verdictBuilder = verdictBuilder;
    this.checkRunManager = checkRunManager;
    this.reviewPublisher = reviewPublisher;
    this.registry = registry;
  }

  /**
   * Acts on one CI completion. Nothing to do when no verdict is held on that exact head: the review
   * is still running (its own gate read, or a recheck queued behind it, covers the completion), the
   * head moved on, or the review never held. A failure while posting is logged and leaves the hold
   * in place, so the next completion for the head tries again.
   */
  @ActivateRequestContext
  public void revisit(Recheck task) {
    var held = registry.heldAt(task.owner(), task.repo(), task.prNumber(), task.headSha());
    if (held.isEmpty()) {
      Log.debugf(
          "No verdict held on %s for %s/%s #%d — nothing to re-evaluate",
          task.headSha(), task.owner(), task.repo(), task.prNumber());
      return;
    }
    try {
      reevaluate(task, held.get());
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "CI re-evaluation for %s/%s #%d failed — keeping the hold for the next CI event",
          task.owner(),
          task.repo(),
          task.prNumber());
    }
  }

  private void reevaluate(Recheck task, CiHoldRegistry.HeldVerdict verdict) {
    var auth = authClient.getAuthHeader(task.installationId());
    var req =
        new ReviewOrchestrator.ReviewRequest(
            task.owner(),
            task.repo(),
            task.prNumber(),
            task.headSha(),
            "",
            "",
            "",
            "",
            task.installationId(),
            false,
            verdict.baseRef());
    var movedHead =
        contextLoader
            .currentHeadSha(auth, req)
            .filter(fresh -> ReviewOrchestrator.headMoved(req, fresh));
    if (movedHead.isPresent()) {
      registry.release(task.owner(), task.repo(), task.prNumber());
      Log.infof(
          "Dropping the verdict held on %s for %s/%s #%d — the head moved to %s",
          task.headSha(), task.owner(), task.repo(), task.prNumber(), movedHead.get());
      return;
    }

    var evaluation =
        ciStatusEvaluator.evaluate(
            auth, task.owner(), task.repo(), task.headSha(), verdict.baseRef());
    if (verdictBuilder.ciHoldsApproval(evaluation)) {
      Log.infof(
          "CI on %s for %s/%s #%d is still not green (%d offending, unreadable=%s) — holding",
          task.headSha(),
          task.owner(),
          task.repo(),
          task.prNumber(),
          evaluation.offendingChecks().size(),
          evaluation.unreadable());
      var stillHeld = resultFor(ReviewState.COMMENT, evaluation);
      updateCheckRun(
          auth,
          task,
          verdict,
          stillHeld,
          VerdictBuilder.checkSummaryForResult(stillHeld) + " " + REVISIT_NOTE);
      return;
    }

    reviewPublisher.createReviewWithFallback(
        auth,
        task.owner(),
        task.repo(),
        task.prNumber(),
        new GitHubReviewClient.CreateReviewRequest(
            task.headSha(),
            approvalBody(task.headSha(), evaluation.requiredContextsKnown()),
            "APPROVE",
            List.of()));
    registry.release(task.owner(), task.repo(), task.prNumber());
    Log.infof(
        "CI on %s for %s/%s #%d is green — posted the held approval",
        task.headSha(), task.owner(), task.repo(), task.prNumber());
    // The approval is on the pull request; concluding the check run is best-effort after it, as
    // it is for a review's own post-result steps.
    try {
      var approved = resultFor(ReviewState.APPROVE, evaluation);
      updateCheckRun(auth, task, verdict, approved, VerdictBuilder.checkSummaryForResult(approved));
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Posted the held approval for %s/%s #%d, but concluding check run %d failed",
          task.owner(),
          task.repo(),
          task.prNumber(),
          verdict.checkRunId());
    }
  }

  /**
   * The APPROVE review body: says why an approval arrives now, on its own, and that nothing was
   * re-reviewed. "Required" is dropped when the required set could not be resolved and every check
   * was gated, matching the check-run copy.
   */
  static String approvalBody(String headSha, boolean requiredContextsKnown) {
    return (requiredContextsKnown ? "Required CI" : "CI")
        + " is now green for "
        + headSha
        + ", so the approval the earlier review held back is posted. The code was not"
        + " re-reviewed: that review found no issues, and only the CI gate held its approval.";
  }

  /**
   * A findings-free result carrying the fresh CI evaluation, so the check-run title and summary
   * render through the same copy a review's own verdict uses.
   */
  private static ReviewResult resultFor(
      ReviewState state, CiStatusEvaluator.CiEvaluation evaluation) {
    return new ReviewResult(
        List.of(),
        0,
        0,
        0,
        0,
        null,
        state,
        false,
        "",
        List.of(),
        evaluation.offendingChecks(),
        0,
        evaluation.unreadable(),
        evaluation.requiredContextsKnown(),
        ReviewResult.TruncationDetail.EMPTY);
  }

  private void updateCheckRun(
      String auth,
      Recheck task,
      CiHoldRegistry.HeldVerdict verdict,
      ReviewResult result,
      String summary) {
    checkRunManager.updateCheckRun(
        new CheckRunManager.CheckRunUpdate(
            auth,
            task.owner(),
            task.repo(),
            verdict.checkRunId(),
            "completed",
            VerdictBuilder.conclusionForResult(result),
            VerdictBuilder.checkTitleForResult(result),
            summary,
            verdict.detailsUrl()));
  }
}
