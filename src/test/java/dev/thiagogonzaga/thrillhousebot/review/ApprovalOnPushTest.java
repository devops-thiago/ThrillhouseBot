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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.CapturedLog;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGraphQLClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalOnPushTest {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String BOT = "thrillhousebot";
  private static final ApprovalOnPush.Push PUSH = new ApprovalOnPush.Push("o", "r", 1, "sha2", 77L);

  private final GitHubAuthClient authClient = mock(GitHubAuthClient.class);
  private final GitHubReviewClient reviewClient = mock(GitHubReviewClient.class);
  private final GitHubPullRequestClient prClient = mock(GitHubPullRequestClient.class);
  private final GitHubGraphQLClient graphQLClient = mock(GitHubGraphQLClient.class);
  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);
  private final ThrillhouseConfig.GitHubConfig githubConfig =
      mock(ThrillhouseConfig.GitHubConfig.class);

  private final ReviewPublisher publisher =
      new ReviewPublisher(
          reviewClient,
          mock(GitHubCommentClient.class),
          mock(ReviewThreadService.class),
          mock(SuggestionFormatter.class),
          mock(FollowUpAnalyzer.class),
          mock(PrLabeler.class),
          config,
          BotIdentity.of(BOT),
          new PendingReviewGuard());

  private static final long WINDOW_MS = 60_000;
  private final AtomicLong now = new AtomicLong(1_000);
  private final AutoReviewRateLimiter limiter =
      new AutoReviewRateLimiter(WINDOW_MS, 10_000, now::get);

  private final ApprovalOnPush approvalOnPush =
      new ApprovalOnPush(
          authClient,
          new ReviewListing(reviewClient, graphQLClient, BotIdentity.of(BOT)),
          prClient,
          publisher,
          limiter);

  @BeforeEach
  void stubs() {
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofMinutes(5));
    when(authClient.getAuthHeader(77L, "r")).thenReturn("auth");
    headIs("sha2");
  }

  private void headIs(String sha) {
    when(prClient.getPullRequest("auth", ACCEPT, "o", "r", 1))
        .thenReturn(
            new GitHubPullRequestClient.PullRequestDetails(
                "t", "b", new GitHubPullRequestClient.Ref(sha), null));
  }

  private void reviewsAre(GitHubReviewClient.ReviewResponse... reviews) {
    when(reviewClient.listReviews("auth", ACCEPT, "o", "r", 1)).thenReturn(List.of(reviews));
  }

  private static GitHubReviewClient.ReviewResponse review(
      long id, String state, String commitSha, String login) {
    return new GitHubReviewClient.ReviewResponse(
        id, "", state, commitSha, new GitHubReviewClient.ReviewResponse.User(login));
  }

  private void verifyDismissed(long reviewId, String headSha) {
    verify(reviewClient)
        .dismissReview(
            "auth",
            ACCEPT,
            "o",
            "r",
            1,
            reviewId,
            ApprovalWithdrawals.approvalWithdrawnOnPushMessage(headSha));
  }

  private void verifyNothingDismissed() {
    verify(reviewClient, never())
        .dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString());
  }

  @Test
  void pushAfterAnApprovalDismissesItWithoutPostingAReview() {
    reviewsAre(review(1L, "APPROVED", "sha1", BOT), review(2L, "COMMENTED", "sha1", "maintainer"));

    approvalOnPush.withdraw(PUSH);

    verifyDismissed(1L, "sha2");
    verify(reviewClient, never())
        .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void dismissalMakesTheRateWindowForgetTheHeadItsReviewCovered() {
    limiter.recordCompletion("o", "r", 1, "sha1");
    reviewsAre(review(1L, "APPROVED", "sha1", BOT));

    approvalOnPush.withdraw(PUSH);

    var hold = limiter.hold("o", "r", 1, "sha1");
    assertTrue(hold.throttled(), "the window itself stays");
    assertEquals(
        OptionalLong.of(1_000 + WINDOW_MS),
        hold.reviewDueAt(),
        "a return to sha1 is reviewed when the window closes");
  }

  @Test
  void pushThatDismissesNothingLeavesTheCoveredHeadCovered() {
    limiter.recordCompletion("o", "r", 1, "sha1");
    reviewsAre(review(1L, "APPROVED", "sha1", BOT));
    when(reviewClient.dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
        .thenThrow(new WebApplicationException(502));

    approvalOnPush.withdraw(PUSH);

    // The approval of sha1 still stands, so a return to sha1 needs no review.
    assertEquals(AutoReviewRateLimiter.Hold.SKIP, limiter.hold("o", "r", 1, "sha1"));
  }

  @Test
  void headForcedBackToTheApprovedCommitInsideTheWindowIsReviewedWhenTheWindowCloses()
      throws InterruptedException {
    var orchestrator = mock(ReviewOrchestrator.class);
    when(orchestrator.review(any())).thenReturn(true);
    var deferred = new DeferredReviews(100, now::get);
    var executor = Executors.newSingleThreadExecutor();
    var dispatcher =
        new ReviewDispatcher(
            executor,
            orchestrator,
            limiter,
            new AutoReviewSlots(4, 50),
            deferred,
            mock(ReviewSkipEmitter.class),
            mock(CiHoldRevisit.class),
            approvalOnPush,
            mock(RunningReviewCoverage.class),
            OpenTelemetry.noop());
    try {
      // The review of A approves it and opens the rate window.
      dispatcher.dispatch(request("shaA"));
      verify(orchestrator, timeout(5000)).review(request("shaA"));
      awaitIdle(dispatcher);
      reviewsAre(review(1L, "APPROVED", "shaA", BOT));

      // B is pushed inside the window: the approval of A goes at once, B waits for the window.
      headIs("shaB");
      assertTrue(dispatcher.dispatchApprovalWithdrawal(push("shaB")));
      assertEquals(
          ReviewDispatcher.RateWindowHold.KEPT, dispatcher.holdForRateWindow(request("shaB")));
      verify(reviewClient, timeout(5000))
          .dismissReview(
              "auth",
              ACCEPT,
              "o",
              "r",
              1,
              1L,
              ApprovalWithdrawals.approvalWithdrawnOnPushMessage("shaB"));
      awaitIdle(dispatcher);
      reviewsAre(review(1L, "DISMISSED", "shaA", BOT));

      // The branch is forced back to A, still inside the window. A has no approval left, so the
      // event is kept in place of B's instead of being skipped as already reviewed.
      headIs("shaA");
      assertTrue(dispatcher.dispatchApprovalWithdrawal(push("shaA")));
      assertEquals(
          ReviewDispatcher.RateWindowHold.KEPT, dispatcher.holdForRateWindow(request("shaA")));
      awaitIdle(dispatcher);

      now.addAndGet(WINDOW_MS + 1);
      var due = deferred.takeDue();
      assertEquals(List.of(request("shaA")), due);
      due.forEach(dispatcher::dispatchDeferred);

      verify(orchestrator, timeout(5000).times(2)).review(request("shaA"));
      verify(orchestrator, never()).review(request("shaB"));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static ApprovalOnPush.Push push(String sha) {
    return new ApprovalOnPush.Push("o", "r", 1, sha, 77L);
  }

  private static ReviewOrchestrator.ReviewRequest request(String sha) {
    return new ReviewOrchestrator.ReviewRequest(
        "o", "r", 1, sha, "title", "", "base", "main", 77L, 7L, false, "main");
  }

  private static void awaitIdle(ReviewDispatcher dispatcher) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (dispatcher.stateCount() > 0 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(0, dispatcher.stateCount());
  }

  @Test
  void pushToAPullRequestTheBotNeverApprovedCostsOneListingAndNoWrite() {
    reviewsAre(
        review(1L, "COMMENTED", "sha1", BOT),
        review(2L, "APPROVED", "sha1", "maintainer"),
        review(3L, "DISMISSED", "sha1", BOT));

    approvalOnPush.withdraw(PUSH);

    verify(reviewClient).listReviews("auth", ACCEPT, "o", "r", 1);
    verifyNothingDismissed();
    verifyNoInteractions(prClient);
  }

  @Test
  void pushBackToTheApprovedCommitKeepsTheApprovalAndReadsNoHead() {
    reviewsAre(review(1L, "APPROVED", "SHA2", BOT));

    approvalOnPush.withdraw(PUSH);

    verifyNothingDismissed();
    verifyNoInteractions(prClient);
  }

  @Test
  void approvalOfTheHeadKeepsAnOlderOneBesideIt() {
    // The older approval counts for nothing more than the one for the head already does; the
    // next push that leaves the head unapproved takes both.
    reviewsAre(review(1L, "APPROVED", "sha2", BOT), review(2L, "APPROVED", "sha1", BOT));

    approvalOnPush.withdraw(PUSH);

    verifyNothingDismissed();
  }

  @Test
  void approvalWithNoCommitIsNotTakenForTheHead() {
    reviewsAre(review(1L, "APPROVED", null, BOT));

    approvalOnPush.withdraw(PUSH);

    verifyDismissed(1L, "sha2");
  }

  @Test
  void lateDeliveryForAnOlderPushKeepsTheApprovalOfTheHeadThePullRequestHasNow() {
    // The event reports sha2, but the head moved to sha3 and the bot approved that since.
    reviewsAre(review(1L, "APPROVED", "sha3", BOT));
    headIs("sha3");

    try (var log = CapturedLog.of(ApprovalOnPush.class)) {
      approvalOnPush.withdraw(PUSH);

      verifyNothingDismissed();
      assertEquals(1, log.at(Level.INFO).size(), log.at(Level.INFO).toString());
    }
  }

  @Test
  void lateDeliveryForAnOlderPushNamesTheHeadThePullRequestHasNow() {
    reviewsAre(review(1L, "APPROVED", "sha1", BOT));
    headIs("sha3");

    approvalOnPush.withdraw(PUSH);

    verifyDismissed(1L, "sha3");
  }

  @Test
  void headThatCannotBeReadFallsBackToThePushedCommit() {
    reviewsAre(review(1L, "APPROVED", "sha1", BOT));
    when(prClient.getPullRequest("auth", ACCEPT, "o", "r", 1))
        .thenThrow(new WebApplicationException(502));

    try (var log = CapturedLog.of(ApprovalOnPush.class)) {
      approvalOnPush.withdraw(PUSH);

      verifyDismissed(1L, "sha2");
      assertEquals(1, log.at(Level.WARNING).size(), log.at(Level.WARNING).toString());
    }
  }

  @Test
  void headReadThatNamesNoCommitFallsBackToThePushedCommit() {
    reviewsAre(review(1L, "APPROVED", "sha1", BOT));
    headIs(" ");

    approvalOnPush.withdraw(PUSH);

    verifyDismissed(1L, "sha2");
  }

  @Test
  void reviewsThatCannotBeListedDismissNothingAndSaySo() {
    when(reviewClient.listReviews("auth", ACCEPT, "o", "r", 1))
        .thenThrow(new WebApplicationException(502));

    try (var log = CapturedLog.of(ApprovalOnPush.class)) {
      approvalOnPush.withdraw(PUSH);

      verifyNothingDismissed();
      var warnings = log.at(Level.WARNING);
      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(warnings.getFirst().contains("could not be checked"), warnings.getFirst());
    }
  }

  @Test
  void approvalOlderThanTheListedReviewsIsStillDismissedOnAPush() throws Exception {
    when(reviewClient.listReviews("auth", ACCEPT, "o", "r", 1))
        .thenReturn(
            Collections.nCopies(
                ReviewListing.MAX_LISTED_REVIEWS, review(9L, "COMMENTED", "sha1", "someone")));
    when(graphQLClient.execute(eq("auth"), any()))
        .thenReturn(
            new ObjectMapper()
                .readTree(
                    """
                    {"data":{"repository":{"pullRequest":{"reviews":{"nodes":[
                      {"databaseId":1,"commit":{"oid":"sha1"}}]}}}}}"""));

    approvalOnPush.withdraw(PUSH);

    verifyDismissed(1L, "sha2");
  }

  @Test
  void dismissalGitHubRefusesIsLoggedAndDoesNotEscape() {
    reviewsAre(review(1L, "APPROVED", "sha1", BOT), review(2L, "APPROVED", "sha1", BOT));
    when(reviewClient.dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
        .thenThrow(new WebApplicationException(403));

    try (var log = CapturedLog.of(ApprovalWithdrawals.class)) {
      approvalOnPush.withdraw(PUSH);

      verifyDismissed(2L, "sha2");
      verify(reviewClient, never())
          .dismissReview(
              anyString(), anyString(), anyString(), anyString(), anyInt(), eq(1L), anyString());
      assertEquals(1, log.at(Level.WARNING).size(), log.at(Level.WARNING).toString());
    }
  }
}
