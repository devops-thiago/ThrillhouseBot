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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.webhook.ReviewTriggerFilter;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Applies the draft filter to a review a limit held back, when the review is handed back. The
 * request passed the filter when its event arrived, and it does not say whether the pull request is
 * a draft now. A {@code converted_to_draft} delivery drops the held review, but a delivery can be
 * missed, so the pull request is read again here.
 *
 * <p>{@link DeferredReviewRelease} asks once for a head, the first time it is handed back, not each
 * time a full repository queue turns it away again. Nothing is read when drafts are reviewed
 * ({@code webhook.triggers.skip-drafts} off, the default). A read that fails lets the review go
 * ahead: the webhook is what normally drops it, and a review is not lost to a read that could not
 * be made.
 */
@ApplicationScoped
public class HeldReviewDraftCheck {

  private static final String ACCEPT = "application/vnd.github+json";

  private final ReviewTriggerFilter triggerFilter;
  private final GitHubAuthClient authClient;
  private final GitHubPullRequestClient prClient;

  @Inject
  public HeldReviewDraftCheck(
      ReviewTriggerFilter triggerFilter,
      GitHubAuthClient authClient,
      GitHubPullRequestClient prClient) {
    this.triggerFilter = triggerFilter;
    this.authClient = authClient;
    this.prClient = prClient;
  }

  /**
   * @return the draft skip when drafts are not reviewed and the pull request is a draft now; empty
   *     when it is not, when drafts are reviewed, and when it could not be read
   */
  public Optional<ReviewTriggerFilter.Skip> skipFor(ReviewOrchestrator.ReviewRequest req) {
    if (!triggerFilter.skipsDrafts()) {
      return Optional.empty();
    }
    try {
      var auth = authClient.getAuthHeader(req.installationId(), req.repo());
      var pr = prClient.getPullRequest(auth, ACCEPT, req.owner(), req.repo(), req.prNumber());
      return triggerFilter.draftSkipReason(pr != null && pr.draft());
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Could not read whether %s/%s #%d is a draft; handing its held review back",
          req.owner(),
          req.repo(),
          req.prNumber());
      return Optional.empty();
    }
  }
}
