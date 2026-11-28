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
package dev.thiagogonzaga.thrillhousebot.webhook;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.review.DeferredReviews;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator;
import dev.thiagogonzaga.thrillhousebot.review.ReviewSkipEmitter;
import dev.thiagogonzaga.thrillhousebot.review.ReviewSkipReason;
import io.quarkiverse.githubapp.runtime.github.GitHubService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GitHub;

/**
 * Posts {@code pull_request} deliveries to the webhook path, so they reach {@link WebhookEvents}
 * the way GitHub's do: through the GitHub App extension's own dispatch. A test that calls an
 * observer method directly passes whether or not anything delivers the action to it.
 */
@QuarkusTest
@TestProfile(WebhookDraftConversionDeliveryTest.DraftsSkippedProfile.class)
class WebhookDraftConversionDeliveryTest {

  private static final long INSTALLATION_ID = 12345L;
  private static final long REPOSITORY_ID = 7L;
  private static final long TIMEOUT_MS = 10_000;

  @Inject DeferredReviews deferredReviews;

  @InjectSpy ReviewSkipEmitter skipEmitter;

  /** The extension builds an installation client for a delivery; this one makes no call. */
  @InjectMock GitHubService gitHubService;

  @BeforeEach
  void installationClientWithoutNetwork() {
    when(gitHubService.getInstallationClient(INSTALLATION_ID)).thenReturn(GitHub.offline());
  }

  @AfterEach
  void forgetHeldReviews() {
    deferredReviews.clear();
  }

  @Test
  void aConversionToDraftDropsTheReviewHeldForThePullRequest() {
    assertTrue(deferredReviews.defer(heldReview(55), Long.MAX_VALUE));

    deliver("to-draft-55", pullRequestPayload("converted_to_draft", 55, true));

    verify(skipEmitter, timeout(TIMEOUT_MS))
        .recordSkip(
            eq(ReviewSkipReason.DRAFT),
            eq(INSTALLATION_ID),
            eq("owner"),
            eq("repo"),
            eq(55),
            anyString());
    assertTrue(deferredReviews.isEmpty());
    // One observer took the delivery: a second one would find its id already recorded.
    verifyNoDuplicateDelivery();
  }

  @Test
  void anActionWithAQualifierOfItsOwnIsStillDeliveredOnce() {
    assertTrue(deferredReviews.defer(heldReview(56), Long.MAX_VALUE));

    deliver("opened-56", pullRequestPayload("opened", 56, true));

    verify(skipEmitter, timeout(TIMEOUT_MS))
        .recordSkip(
            eq(ReviewSkipReason.DRAFT),
            eq(INSTALLATION_ID),
            eq("owner"),
            eq("repo"),
            eq(56),
            anyString());
    verifyNoDuplicateDelivery();
  }

  @Test
  void anActionNobodyObservesLeavesTheHeldReviewAlone() {
    assertTrue(deferredReviews.defer(heldReview(57), Long.MAX_VALUE));

    deliver("edited-57", pullRequestPayload("edited", 57, true));
    // A delivery that is observed, after it: once this one is handled, the first has been too.
    deliver("opened-58", pullRequestPayload("opened", 58, true));

    verify(skipEmitter, timeout(TIMEOUT_MS))
        .recordSkip(
            eq(ReviewSkipReason.DRAFT),
            eq(INSTALLATION_ID),
            eq("owner"),
            eq("repo"),
            eq(58),
            anyString());
    assertFalse(deferredReviews.isEmpty());
  }

  private void verifyNoDuplicateDelivery() {
    verify(skipEmitter, never())
        .recordSkip(
            eq(ReviewSkipReason.DUPLICATE_DELIVERY),
            anyLong(),
            anyString(),
            anyString(),
            anyInt(),
            anyString());
  }

  private static void deliver(String deliveryId, String body) {
    given()
        .header("X-GitHub-Event", "pull_request")
        .header("X-GitHub-Delivery", deliveryId)
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/webhook")
        .then()
        .statusCode(200);
  }

  private static ReviewOrchestrator.ReviewRequest heldReview(int prNumber) {
    return new ReviewOrchestrator.ReviewRequest(
        "owner",
        "repo",
        prNumber,
        "sha" + prNumber,
        "Title",
        "",
        "basesha",
        "main",
        INSTALLATION_ID,
        REPOSITORY_ID,
        false,
        "main");
  }

  private static String pullRequestPayload(String action, int prNumber, boolean draft) {
    return """
        {
          "action": "%s",
          "pull_request": {
            "number": %d,
            "title": "Title",
            "draft": %s,
            "head": {"sha": "sha%d", "ref": "feature"},
            "base": {"sha": "basesha", "ref": "main"},
            "user": {"login": "octocat", "id": 1}
          },
          "repository": {
            "id": %d,
            "full_name": "owner/repo",
            "name": "repo",
            "default_branch": "main",
            "owner": {"login": "owner", "id": 2}
          },
          "installation": {"id": %d}
        }
        """
        .formatted(action, prNumber, draft, prNumber, REPOSITORY_ID, INSTALLATION_ID);
  }

  /** Drafts are reviewed by default; the held review is dropped only when they are skipped. */
  public static class DraftsSkippedProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("thrillhousebot.webhook.triggers.skip-drafts", "true");
    }
  }
}
