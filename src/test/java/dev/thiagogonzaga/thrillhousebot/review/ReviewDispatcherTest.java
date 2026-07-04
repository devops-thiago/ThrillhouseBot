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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ReviewDispatcherTest {

  @Mock private ReviewOrchestrator orchestrator;
  @Mock private AutoReviewRateLimiter rateLimiter;

  private ExecutorService reviewExecutor;
  private ReviewDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    reviewExecutor = Executors.newSingleThreadExecutor();
    dispatcher = new ReviewDispatcher(reviewExecutor, orchestrator, rateLimiter);
  }

  @AfterEach
  void tearDown() {
    reviewExecutor.shutdownNow();
  }

  @Test
  void shouldDispatchSingleReview() {
    ReviewOrchestrator.ReviewRequest req = reviewRequest("owner", "repo", 1, "sha1");

    dispatcher.dispatch(req);

    verify(orchestrator, timeout(2000)).review(req);
  }

  @Test
  void shouldDispatchManualReviewWithEmptySha() {
    var req =
        new ReviewOrchestrator.ReviewRequest(
            "owner", "repo", 3, "", "(manual)", "", "", "main", 1L, true);

    dispatcher.dispatch(req);

    verify(orchestrator, timeout(2000)).review(req);
  }

  @Test
  void shouldDispatchReviewWithShortSha() {
    ReviewOrchestrator.ReviewRequest req = reviewRequest("owner", "repo", 4, "abc");

    dispatcher.dispatch(req);

    verify(orchestrator, timeout(2000)).review(req);
  }

  @Test
  void shouldCoalesceConcurrentReviewsForSamePr() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var reviewed = new ArrayList<ReviewOrchestrator.ReviewRequest>();

    doAnswer(
            invocation -> {
              ReviewOrchestrator.ReviewRequest req = invocation.getArgument(0);
              reviewed.add(req);
              started.countDown();
              release.await(5, TimeUnit.SECONDS);
              return true;
            })
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    started.await(2, TimeUnit.SECONDS);

    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha2"));
    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha3"));

    release.countDown();

    verify(orchestrator, timeout(5000).times(2))
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    assertEquals(2, reviewed.size());
    assertEquals("sha1", reviewed.get(0).commitSha());
    assertEquals("sha3", reviewed.get(1).commitSha());
  }

  @Test
  void shouldNotCoalesceReviewsForDifferentPrs() {
    ReviewOrchestrator.ReviewRequest req1 = reviewRequest("owner", "repo", 1, "sha1");
    ReviewOrchestrator.ReviewRequest req2 = reviewRequest("owner", "repo", 2, "sha2");

    dispatcher.dispatch(req1);
    dispatcher.dispatch(req2);

    verify(orchestrator, timeout(2000)).review(req1);
    verify(orchestrator, timeout(2000)).review(req2);
  }

  @Test
  void shouldNotCoalesceReviewsForDifferentRepos() {
    ReviewOrchestrator.ReviewRequest req1 = reviewRequest("owner", "repo-a", 1, "sha1");
    ReviewOrchestrator.ReviewRequest req2 = reviewRequest("owner", "repo-b", 1, "sha2");

    dispatcher.dispatch(req1);
    dispatcher.dispatch(req2);

    verify(orchestrator, timeout(2000)).review(req1);
    verify(orchestrator, timeout(2000)).review(req2);
  }

  @Test
  void shouldContinueAfterReviewFailure() throws Exception {
    ReviewOrchestrator.ReviewRequest req1 = reviewRequest("owner", "repo", 5, "sha1");
    ReviewOrchestrator.ReviewRequest req2 = reviewRequest("owner", "repo", 5, "sha2");

    var firstReviewStarted = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              firstReviewStarted.countDown();
              throw new RuntimeException("boom");
            })
        .doAnswer(invocation -> true)
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    dispatcher.dispatch(req1);
    // Same-PR dispatches coalesce while queued — wait until req1 is being reviewed, so
    // req2 is processed as a follow-up instead of replacing it
    assertTrue(firstReviewStarted.await(2, TimeUnit.SECONDS));
    dispatcher.dispatch(req2);

    verify(orchestrator, timeout(2000)).review(req1);
    verify(orchestrator, timeout(2000)).review(req2);
  }

  @Test
  void shouldClearStateWhenExecutorRejectsWithoutRunningTask() {
    ExecutorService executor = mock(ExecutorService.class);
    doThrow(new RejectedExecutionException("shut down")).when(executor).execute(any());
    dispatcher = new ReviewDispatcher(executor, orchestrator, rateLimiter);

    dispatcher.dispatch(reviewRequest("owner", "repo", 10, "sha1"));
    verify(orchestrator, after(500).never()).review(any(ReviewOrchestrator.ReviewRequest.class));

    doAnswer(
            invocation -> {
              invocation.getArgument(0, Runnable.class).run();
              return null;
            })
        .when(executor)
        .execute(any());
    dispatcher.dispatch(reviewRequest("owner", "repo", 10, "sha2"));
    verify(orchestrator).review(reviewRequest("owner", "repo", 10, "sha2"));
  }

  @Test
  void shouldClearStateWhenWorkerThrowsNonRuntimeException() throws InterruptedException {
    var aborted = new CountDownLatch(1);
    ReviewOrchestrator.ReviewRequest req2 = reviewRequest("owner", "repo", 9, "sha2");

    doAnswer(
            invocation -> {
              ReviewOrchestrator.ReviewRequest req = invocation.getArgument(0);
              if ("sha1".equals(req.commitSha())) {
                aborted.countDown();
                throw new AssertionError("fatal");
              }
              return true;
            })
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    dispatcher.dispatch(reviewRequest("owner", "repo", 9, "sha1"));
    assertTrue(aborted.await(2, TimeUnit.SECONDS));
    awaitEmptyDispatcherState(dispatcher, 2, TimeUnit.SECONDS);

    dispatcher.dispatch(req2);
    verify(orchestrator, timeout(2000)).review(req2);
  }

  @Test
  void shouldRecoverAfterWorkerThrowsCheckedException() throws Exception {
    var aborted = new CountDownLatch(1);
    ReviewOrchestrator.ReviewRequest req2 = reviewRequest("owner", "repo", 12, "sha2");
    doAnswer(
            invocation -> {
              ReviewOrchestrator.ReviewRequest req = invocation.getArgument(0);
              if ("sha1".equals(req.commitSha())) {
                aborted.countDown();
                // Sneaks past the drain loop's RuntimeException catch to the worker guard
                throw new Exception("unexpected checked failure");
              }
              return true;
            })
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    dispatcher.dispatch(reviewRequest("owner", "repo", 12, "sha1"));
    assertTrue(aborted.await(2, TimeUnit.SECONDS));
    awaitEmptyDispatcherState(dispatcher, 2, TimeUnit.SECONDS);

    dispatcher.dispatch(req2);
    verify(orchestrator, timeout(2000)).review(req2);
  }

  @Test
  void shouldDispatchAndCoalesceWithInfoLoggingDisabled() throws Exception {
    var julLogger = java.util.logging.Logger.getLogger(ReviewDispatcher.class.getName());
    var originalLevel = julLogger.getLevel();
    julLogger.setLevel(java.util.logging.Level.WARNING);
    try {
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                return true;
              })
          .when(orchestrator)
          .review(any(ReviewOrchestrator.ReviewRequest.class));

      dispatcher.dispatch(reviewRequest("owner", "repo", 21, "sha1"));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      dispatcher.dispatch(reviewRequest("owner", "repo", 21, "sha2"));
      release.countDown();

      verify(orchestrator, timeout(5000).times(2))
          .review(any(ReviewOrchestrator.ReviewRequest.class));
    } finally {
      julLogger.setLevel(originalLevel);
    }
  }

  private static void awaitEmptyDispatcherState(
      ReviewDispatcher dispatcher, long timeout, TimeUnit unit) {
    var deadline = System.nanoTime() + unit.toNanos(timeout);
    while (dispatcher.stateCount() > 0) {
      if (System.nanoTime() >= deadline) {
        assertEquals(0, dispatcher.stateCount(), "dispatcher state not cleared in time");
        return;
      }
      Thread.onSpinWait();
    }
  }

  @Test
  void shouldReturnFalseWhenExecutorRejectsTask() {
    ExecutorService executor = mock(ExecutorService.class);
    doThrow(new RejectedExecutionException("shut down")).when(executor).execute(any());
    dispatcher = new ReviewDispatcher(executor, orchestrator, rateLimiter);

    // A rejected task means no review will run, so callers can roll back dedup state.
    assertFalse(dispatcher.dispatch(reviewRequest("owner", "repo", 10, "sha1")));
  }

  @Test
  void shouldReturnTrueWhenReviewIsQueued() {
    assertTrue(dispatcher.dispatch(reviewRequest("owner", "repo", 11, "sha1")));
    verify(orchestrator, timeout(2000)).review(reviewRequest("owner", "repo", 11, "sha1"));
  }

  @Test
  void shouldRecoverWhenExecutorRejectsTask() {
    ExecutorService executor = mock(ExecutorService.class);
    doThrow(new RejectedExecutionException("shut down"))
        .doAnswer(
            invocation -> {
              invocation.getArgument(0, Runnable.class).run();
              return null;
            })
        .when(executor)
        .execute(any());

    dispatcher = new ReviewDispatcher(executor, orchestrator, rateLimiter);

    dispatcher.dispatch(reviewRequest("owner", "repo", 8, "sha1"));
    dispatcher.dispatch(reviewRequest("owner", "repo", 8, "sha2"));

    verify(orchestrator).review(reviewRequest("owner", "repo", 8, "sha2"));
  }

  @Test
  void shouldNotReviveRetiredStateOnDispatch() {
    // Simulates the race where a finishing worker retires the state between the
    // dispatcher's computeIfAbsent and its lock acquisition
    var key = new ReviewDispatcher.PrKey("owner", "repo", 9);
    var retired = new ReviewDispatcher.PerPrState();
    retired.retired = true;
    dispatcher.seedState(key, retired);

    ReviewOrchestrator.ReviewRequest req = reviewRequest("owner", "repo", 9, "sha1");
    dispatcher.dispatch(req);

    // The review still runs — on a fresh state, never on the retired one
    verify(orchestrator, timeout(2000)).review(req);
    assertEquals(false, retired.running);
  }

  @Test
  void shouldRecordCompletionForAutomaticReview() {
    ReviewOrchestrator.ReviewRequest req = reviewRequest("owner", "repo", 13, "sha1");
    when(orchestrator.review(req)).thenReturn(true);

    dispatcher.dispatch(req);

    // The throttle window starts when the automatic review finishes, keyed by the PR.
    verify(orchestrator, timeout(2000)).review(req);
    verify(rateLimiter, timeout(2000)).recordCompletion("owner", "repo", 13);
  }

  @Test
  void shouldNotRecordCompletionForManualReview() {
    var req =
        new ReviewOrchestrator.ReviewRequest(
            "owner", "repo", 14, "", "(manual)", "", "", "main", 1L, true);
    when(orchestrator.review(req)).thenReturn(true);

    dispatcher.dispatch(req);

    // A manual /review must not shift the automatic throttle window.
    verify(orchestrator, timeout(2000)).review(req);
    verify(rateLimiter, after(500).never()).recordCompletion(anyString(), anyString(), anyInt());
  }

  @Test
  void shouldSkipCoalescedAutomaticReviewWithinWindow() throws InterruptedException {
    // Simulate the real limiter: throttled from the moment a completion is recorded.
    var throttled = new java.util.concurrent.atomic.AtomicBoolean(false);
    when(rateLimiter.isThrottled(anyString(), anyString(), anyInt()))
        .thenAnswer(invocation -> throttled.get());
    doAnswer(
            invocation -> {
              throttled.set(true);
              return null;
            })
        .when(rateLimiter)
        .recordCompletion(anyString(), anyString(), anyInt());

    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              started.countDown();
              release.await(5, TimeUnit.SECONDS);
              return true;
            })
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    dispatcher.dispatch(reviewRequest("owner", "repo", 17, "sha1"));
    assertTrue(started.await(2, TimeUnit.SECONDS));
    // A push during the in-flight review passed the controller gate before the window opened...
    dispatcher.dispatch(reviewRequest("owner", "repo", 17, "sha2"));
    release.countDown();

    awaitEmptyDispatcherState(dispatcher, 2, TimeUnit.SECONDS);

    // ...but the drain loop re-checks, so only the first review runs and only one window starts.
    verify(orchestrator, times(1)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(rateLimiter, times(1)).recordCompletion("owner", "repo", 17);
  }

  @Test
  void shouldSkipAutomaticReviewWhenWindowClosedAfterDispatchGate() {
    // Pins the dispatch race: the controller gate passed before a prior worker recorded its
    // completion, so this fresh worker's very first batch must hit the re-check and skip.
    when(rateLimiter.isThrottled("owner", "repo", 19)).thenReturn(true);

    dispatcher.dispatch(reviewRequest("owner", "repo", 19, "sha1"));

    awaitEmptyDispatcherState(dispatcher, 2, TimeUnit.SECONDS);
    verify(orchestrator, never()).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(rateLimiter, never()).recordCompletion(anyString(), anyString(), anyInt());
  }

  @Test
  void shouldRunCoalescedManualReviewWithinWindow() throws InterruptedException {
    // Even a closed window must not hold back an explicit /review that coalesced behind an
    // in-flight automatic run.
    when(rateLimiter.isThrottled(anyString(), anyString(), anyInt())).thenReturn(true);

    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var manualReq =
        new ReviewOrchestrator.ReviewRequest(
            "owner", "repo", 18, "", "(manual)", "", "", "main", 1L, true);
    doAnswer(
            invocation -> {
              started.countDown();
              release.await(5, TimeUnit.SECONDS);
              return true;
            })
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    dispatcher.dispatch(
        new ReviewOrchestrator.ReviewRequest(
            "owner", "repo", 18, "", "(manual)", "", "", "main", 1L, true));
    assertTrue(started.await(2, TimeUnit.SECONDS));
    dispatcher.dispatch(manualReq);
    release.countDown();

    verify(orchestrator, timeout(5000).times(2))
        .review(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldNotRecordCompletionWhenReviewNotSurfaced() {
    ReviewOrchestrator.ReviewRequest req = reviewRequest("owner", "repo", 16, "sha1");
    when(orchestrator.review(req)).thenReturn(false);

    dispatcher.dispatch(req);

    // The orchestrator handled a failure internally and posted nothing, so the throttle window
    // must not start — the next push may retry immediately.
    verify(orchestrator, timeout(2000)).review(req);
    verify(rateLimiter, after(500).never()).recordCompletion(anyString(), anyString(), anyInt());
  }

  @Test
  void shouldNotRecordCompletionWhenReviewThrows() {
    doThrow(new RuntimeException("boom"))
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    dispatcher.dispatch(reviewRequest("owner", "repo", 15, "sha1"));

    // An unexpected failure means no review was surfaced, so the next event may retry immediately.
    verify(orchestrator, timeout(2000)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(rateLimiter, after(500).never()).recordCompletion(anyString(), anyString(), anyInt());
  }

  private static ReviewOrchestrator.ReviewRequest reviewRequest(
      String owner, String repo, int prNumber, String sha) {
    return new ReviewOrchestrator.ReviewRequest(
        owner, repo, prNumber, sha, "title", "", "base", "main", 1L, false);
  }
}
