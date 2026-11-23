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

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/**
 * Runs the {@link PrePublishGuard}s right before a finished run's first write, in a fixed order,
 * and stands the run down on the first that rejects it. The order is {@link HeadMovedGuard} (#704,
 * #806), then {@link LabelGateGuard} (#1021): a run whose head moved is superseded whatever its
 * labels say. A stand-down counts the skip under the guard's reason and concludes the check run as
 * skipped; nothing is posted to the pull request, and the caller closes out the session with the
 * returned {@link PrePublishGuard.StandDown}. A new guard is added to the list here, not to the
 * orchestrator.
 */
@ApplicationScoped
public class ReviewStandDown {

  private final List<PrePublishGuard> guards;
  private final ReviewSkipEmitter skipEmitter;
  private final CheckRunManager checkRunManager;

  @Inject
  public ReviewStandDown(
      HeadMovedGuard headMoved,
      LabelGateGuard labelGate,
      ReviewSkipEmitter skipEmitter,
      CheckRunManager checkRunManager) {
    this(List.of(headMoved, labelGate), skipEmitter, checkRunManager);
  }

  /** The guards run in list order. Visible for tests. */
  ReviewStandDown(
      List<PrePublishGuard> guards,
      ReviewSkipEmitter skipEmitter,
      CheckRunManager checkRunManager) {
    this.guards = List.copyOf(guards);
    this.skipEmitter = skipEmitter;
    this.checkRunManager = checkRunManager;
  }

  /**
   * Asks each guard in order and stands the run down on the first that rejects it; later guards are
   * not asked.
   *
   * @return the stand-down, or empty when the run may post
   */
  public Optional<PrePublishGuard.StandDown> beforeFirstWrite(PrePublishGuard.Review review) {
    for (var guard : guards) {
      var standDown = guard.check(review);
      if (standDown.isPresent()) {
        apply(review, standDown.get());
        return standDown;
      }
    }
    return Optional.empty();
  }

  /**
   * Stands down a run whose head moved before its context finished loading (#1412): nothing was
   * reviewed yet, so there are no findings to hand over, and the dispatcher already queued the
   * review of the new head. The consequences are those of a pre-publish stand-down.
   *
   * @return the stand-down the caller closes the session with
   */
  public PrePublishGuard.StandDown supersededWhileLoading(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      long checkRunId,
      String detailsUrl,
      String freshHead) {
    var standDown = HeadMovedGuard.supersededWhileLoading(req, freshHead);
    apply(auth, req, checkRunId, detailsUrl, standDown);
    return standDown;
  }

  private void apply(PrePublishGuard.Review review, PrePublishGuard.StandDown standDown) {
    apply(review.auth(), review.req(), review.checkRunId(), review.detailsUrl(), standDown);
  }

  /**
   * Counts the skip and concludes the check run as skipped. Concluding the check run is
   * best-effort: the stand-down is already decided and nothing was posted.
   */
  private void apply(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      long checkRunId,
      String detailsUrl,
      PrePublishGuard.StandDown standDown) {
    skipEmitter.recordSkip(
        standDown.reason(),
        req.installationId(),
        req.owner(),
        req.repo(),
        req.prNumber(),
        standDown.skipDetail());
    if (checkRunId <= 0) {
      return;
    }
    try {
      checkRunManager.updateCheckRun(
          new CheckRunManager.CheckRunUpdate(
              auth,
              req.owner(),
              req.repo(),
              checkRunId,
              "completed",
              "skipped",
              standDown.checkTitle(),
              standDown.checkSummary(),
              detailsUrl));
    } catch (RuntimeException checkRunError) {
      Log.warnf(
          checkRunError,
          "Failed to mark %s check run %d of %s/%s #%d as skipped",
          standDown.kind(),
          checkRunId,
          req.owner(),
          req.repo(),
          req.prNumber());
    }
  }
}
