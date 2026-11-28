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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import dev.thiagogonzaga.thrillhousebot.webhook.ReviewTriggerFilter;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HeldReviewDraftCheckTest {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final ReviewTriggerFilter.Skip DRAFT =
      new ReviewTriggerFilter.Skip(ReviewSkipReason.DRAFT, "PR is a draft");

  private final ReviewTriggerFilter triggerFilter = mock(ReviewTriggerFilter.class);
  private final GitHubAuthClient authClient = mock(GitHubAuthClient.class);
  private final GitHubPullRequestClient prClient = mock(GitHubPullRequestClient.class);
  private final HeldReviewDraftCheck check =
      new HeldReviewDraftCheck(triggerFilter, authClient, prClient);
  private final ReviewOrchestrator.ReviewRequest req =
      new ReviewOrchestrator.ReviewRequest(
          "owner",
          "repo",
          7,
          "sha",
          "title",
          "",
          "base",
          "main",
          3L,
          TestScopes.REPOSITORY_ID,
          false);

  private void draftsAreSkipped() {
    when(triggerFilter.skipsDrafts()).thenReturn(true);
    when(triggerFilter.draftSkipReason(true)).thenReturn(Optional.of(DRAFT));
    when(triggerFilter.draftSkipReason(false)).thenReturn(Optional.empty());
    when(authClient.getAuthHeader(3L, "repo")).thenReturn("Bearer t");
  }

  private void pullRequestIs(boolean draft) {
    when(prClient.getPullRequest("Bearer t", ACCEPT, "owner", "repo", 7))
        .thenReturn(
            new GitHubPullRequestClient.PullRequestDetails(
                "title", "", null, null, 0, 0, 0, "open", draft));
  }

  @Test
  void aPullRequestThatBecameADraftIsSkippedWhenDraftsAreSkipped() {
    draftsAreSkipped();
    pullRequestIs(true);

    assertEquals(Optional.of(DRAFT), check.skipFor(req));
  }

  @Test
  void aPullRequestThatIsNotADraftIsNotSkipped() {
    draftsAreSkipped();
    pullRequestIs(false);

    assertTrue(check.skipFor(req).isEmpty());
  }

  @Test
  void nothingIsReadWhenDraftsAreReviewed() {
    assertTrue(check.skipFor(req).isEmpty());

    verifyNoInteractions(authClient, prClient);
  }

  @Test
  void aPullRequestGitHubReturnsNothingForIsNotSkipped() {
    draftsAreSkipped();

    assertTrue(check.skipFor(req).isEmpty());
  }

  @Test
  void aReadThatFailsDoesNotSkipTheReview() {
    draftsAreSkipped();
    when(prClient.getPullRequest("Bearer t", ACCEPT, "owner", "repo", 7))
        .thenThrow(new IllegalStateException("GitHub unavailable"));

    assertTrue(check.skipFor(req).isEmpty());
  }

  @Test
  void aTokenThatCannotBeMintedDoesNotSkipTheReview() {
    draftsAreSkipped();
    when(authClient.getAuthHeader(3L, "repo")).thenThrow(new IllegalStateException("no token"));

    assertTrue(check.skipFor(req).isEmpty());
  }
}
