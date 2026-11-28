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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Stands down a run whose pull request head moved while it reviewed (#704): a push landing during
 * the model call supersedes the run, and the dispatcher already queued a coalesced run for the new
 * head, which re-reviews and posts in this run's place. The run's verified findings are handed to
 * that replacement rather than dropped with the post (#806). Applies to a manual {@code /review}
 * too.
 */
@ApplicationScoped
public class HeadMovedGuard implements PrePublishGuard {

  /** SKIPPED check-run title when the finished run's post was abandoned (#704). */
  static final String SUPERSEDED_CHECK_TITLE = "Review superseded by a newer commit";

  private final ReviewContextLoader contextLoader;
  private final SupersededFindingsCarryover carryover;

  @Inject
  public HeadMovedGuard(ReviewContextLoader contextLoader, SupersededFindingsCarryover carryover) {
    this.contextLoader = contextLoader;
    this.carryover = carryover;
  }

  /**
   * Re-reads the head; when it moved, stashes the run's findings for the run queued for the new
   * head. A head that cannot be read lets the run post.
   */
  @Override
  public Optional<StandDown> check(Review review) {
    var req = review.req();
    var movedHead =
        contextLoader.currentHeadSha(review.auth(), req).filter(fresh -> headMoved(req, fresh));
    if (movedHead.isEmpty()) {
      return Optional.empty();
    }
    var freshHead = movedHead.get();
    carryover.stash(
        req.owner(),
        req.repo(),
        req.prNumber(),
        req.commitSha(),
        freshHead,
        review.aiResponse().findings());
    return Optional.of(
        new StandDown(
            ReviewSkipReason.HEAD_MOVED,
            "head moved from "
                + req.commitSha()
                + " to "
                + freshHead
                + " while the review ran — abandoning the post; the queued run for the new head"
                + " replaces it",
            SUPERSEDED_CHECK_TITLE,
            "The pull request head moved to "
                + freshHead
                + " while this review ran, so its"
                + " result was not posted. The review of the new head replaces it.",
            "Superseded: the PR head moved to " + freshHead + " during the review",
            "superseded"));
  }

  /**
   * The stand-down of a run whose head moved before its context finished loading (#1412). It is
   * counted, concluded and recorded like a stand-down before the first write; the run reviewed
   * nothing, so there is nothing to carry over.
   */
  static StandDown supersededWhileLoading(ReviewOrchestrator.ReviewRequest req, String freshHead) {
    return new StandDown(
        ReviewSkipReason.HEAD_MOVED,
        "head moved from "
            + req.commitSha()
            + " to "
            + freshHead
            + " while the review context loaded; the queued run for the new head replaces it",
        SUPERSEDED_CHECK_TITLE,
        "The pull request head moved to "
            + freshHead
            + " while this review loaded its context, so it reviewed nothing. The review of the"
            + " new head replaces it.",
        "Superseded: the PR head moved to " + freshHead + " while loading the review context",
        "superseded");
  }

  /**
   * Whether the freshly read head names a different commit than the one this run reviewed. False
   * when the run has no reviewed sha to compare against; a fresh read that failed never reaches
   * here (fail-open — a finished review is never lost to its own guard). Visible for tests.
   */
  static boolean headMoved(ReviewOrchestrator.ReviewRequest req, String freshHead) {
    return req.commitSha() != null
        && !req.commitSha().isBlank()
        && !freshHead.equalsIgnoreCase(req.commitSha());
  }
}
