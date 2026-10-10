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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.Optional;

/**
 * One check a finished review must pass right before its first write. The model call can run for
 * minutes, and something that changed meanwhile can make the finished review stale; a guard that
 * finds such a change stands the run down instead of letting it post. {@link ReviewStandDown} runs
 * the guards in a fixed order, stops at the first stand-down, and applies the consequences every
 * stand-down shares (the skip counted, the check run concluded as skipped). A guard keeps only its
 * own side effects, such as the carry-over of {@link HeadMovedGuard}.
 *
 * <p>A guard fails open: a read it cannot make lets the run post, so a finished review is never
 * lost to its own guard.
 */
public interface PrePublishGuard {

  /**
   * The finished run about to publish: the pull request and the check run on the reviewed sha (none
   * when not positive), the dashboard link it carries, and what the run reviewed and verified.
   */
  record Review(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      long checkRunId,
      String detailsUrl,
      ReviewContextLoader.ReviewContext ctx,
      ReviewResponse aiResponse,
      ReviewResult result) {}

  /**
   * Why a run stands down: the skip reason and detail it is counted and logged under, the SKIPPED
   * check-run title and summary, the error its session is closed with, and what kind of stand-down
   * it was, for the log lines when concluding the check run or closing the session fails.
   */
  record StandDown(
      ReviewSkipReason reason,
      String skipDetail,
      String checkTitle,
      String checkSummary,
      String sessionError,
      String kind) {}

  /**
   * @return the stand-down when this guard rejects the run, or empty when it may post
   */
  Optional<StandDown> check(Review review);
}
