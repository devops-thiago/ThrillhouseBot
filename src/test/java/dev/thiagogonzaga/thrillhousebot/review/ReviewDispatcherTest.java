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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ReviewDispatcherTest {

  @Mock private ReviewOrchestrator orchestrator;
  @Mock private AutoReviewRateLimiter rateLimiter;
  @Mock private ReviewSkipEmitter skipEmitter;
  @Mock private CiHoldRevisit ciHoldRevisit;
  @Mock private ApprovalOnPush approvalOnPush;
  @Mock private RunningReviewCoverage coverage;

  /** The shipped per-repository limits; the tests of the limit build their own. */
  private final AutoReviewSlots slots = new AutoReviewSlots(4, 50);

  private static final long WINDOW_MS = 60_000;

  private final AtomicLong now = new AtomicLong(1_000);
  private final DeferredReviews deferred = new DeferredReviews(100, now::get);

  private ExecutorService reviewExecutor;
  private ReviewDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(rateLimiter.hold(anyString(), anyString(), anyInt(), any()))
        .thenReturn(AutoReviewRateLimiter.Hold.NONE);
    reviewExecutor = Executors.newSingleThreadExecutor();
    dispatcher =
        new ReviewDispatcher(
            reviewExecutor,
            orchestrator,
            rateLimiter,
            slots,
            deferred,
            skipEmitter,
            ciHoldRevisit,
            approvalOnPush,
            coverage,
            OpenTelemetry.noop());
  }

  private static CiHoldRevisit.Recheck recheck(int prNumber, String sha) {
    return new CiHoldRevisit.Recheck("owner", "repo", prNumber, sha, 1L, TestScopes.REPOSITORY_ID);
  }

  /** Blocks the orchestrator's review until {@code release} is counted down. */
  private void blockReviewUntil(CountDownLatch started, CountDownLatch release) {
    doAnswer(
            invocation -> {
              started.countDown();
              release.await(5, TimeUnit.SECONDS);
              return true;
            })
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));
  }

  private static ApprovalOnPush.Push push(int prNumber, String sha) {
    return new ApprovalOnPush.Push("owner", "repo", prNumber, sha, 1L);
  }

  @Test
  void shouldWithdrawTheApprovalOnAPushWithNoReviewToFollow() {
    var push = push(42, "sha2");

    assertTrue(dispatcher.dispatchApprovalWithdrawal(push));

    verify(approvalOnPush, timeout(2000)).withdraw(push);
    verify(orchestrator, never()).review(any());
    awaitEmptyDispatcherState(dispatcher, 2, TimeUnit.SECONDS);
  }

  @Test
  void shouldWithdrawTheApprovalBeforeTheReviewThePushQueued() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    var push = push(42, "sha2");
    var review = reviewRequest("owner", "repo", 42, "sha2");

    // The review of sha1 is in flight when sha2 is pushed: the withdrawal waits for it, so an
    // approval it posts is seen, and runs ahead of the review of sha2.
    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    started.await(2, TimeUnit.SECONDS);
    assertTrue(dispatcher.dispatchApprovalWithdrawal(push));
    dispatcher.dispatch(review);
    verify(approvalOnPush, after(200).never()).withdraw(any());

    release.countDown();

    verify(orchestrator, timeout(5000)).review(review);
    var order = inOrder(orchestrator, approvalOnPush);
    order.verify(orchestrator).review(reviewRequest("owner", "repo", 42, "sha1"));
    order.verify(approvalOnPush).withdraw(push);
    order.verify(orchestrator).review(review);
  }

  @Test
  void shouldWithdrawTheApprovalWhenTheRateWindowHoldsTheReviewBack() {
    when(rateLimiter.hold("owner", "repo", 42, "sha2"))
        .thenReturn(new AutoReviewRateLimiter.Hold(true, OptionalLong.of(now.get() + WINDOW_MS)));
    var push = push(42, "sha2");

    assertTrue(dispatcher.dispatchApprovalWithdrawal(push));
    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha2"));

    verify(approvalOnPush, timeout(2000)).withdraw(push);
    awaitEmptyDispatcherState(dispatcher, 2, TimeUnit.SECONDS);
    verify(orchestrator, never()).review(any());
    assertFalse(deferred.isEmpty(), "the review of the new head waits for the window");
  }

  @Test
  void shouldRunTheReviewWhenTheApprovalWithdrawalFails() {
    doThrow(new IllegalStateException("no token")).when(approvalOnPush).withdraw(any());
    var review = reviewRequest("owner", "repo", 42, "sha2");

    assertTrue(dispatcher.dispatchApprovalWithdrawal(push(42, "sha2")));
    assertTrue(dispatcher.dispatch(review));

    verify(orchestrator, timeout(2000)).review(review);
    verify(approvalOnPush).withdraw(push(42, "sha2"));
  }

  @Test
  void shouldWithdrawOnlyForTheLatestPushWhileOneWaits() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);

    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    started.await(2, TimeUnit.SECONDS);
    dispatcher.dispatchApprovalWithdrawal(push(42, "sha2"));
    dispatcher.dispatchApprovalWithdrawal(push(42, "sha3"));

    release.countDown();

    verify(approvalOnPush, timeout(5000)).withdraw(push(42, "sha3"));
    verify(approvalOnPush, after(300).times(1)).withdraw(any());
  }

  @Test
  void shouldRunTheApprovalWithdrawalAheadOfAQueuedCiRecheck() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);

    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    started.await(2, TimeUnit.SECONDS);
    dispatcher.dispatchCiRecheck(recheck(42, "sha1"));
    dispatcher.dispatchApprovalWithdrawal(push(42, "sha2"));

    release.countDown();

    verify(ciHoldRevisit, timeout(5000)).revisit(recheck(42, "sha1"));
    var order = inOrder(approvalOnPush, ciHoldRevisit);
    order.verify(approvalOnPush).withdraw(push(42, "sha2"));
    order.verify(ciHoldRevisit).revisit(recheck(42, "sha1"));
  }

  @Test
  void shouldReturnFalseWhenExecutorRejectsTheApprovalWithdrawal() {
    var rejecting = mock(ExecutorService.class);
    doThrow(new RejectedExecutionException("saturated")).when(rejecting).execute(any());
    var saturated =
        new ReviewDispatcher(
            rejecting,
            orchestrator,
            rateLimiter,
            slots,
            deferred,
            skipEmitter,
            ciHoldRevisit,
            approvalOnPush,
            coverage,
            OpenTelemetry.noop());

    assertFalse(saturated.dispatchApprovalWithdrawal(push(42, "sha2")));
    assertEquals(0, saturated.stateCount());
  }

  @Test
  void shouldNotReviveRetiredStateOnApprovalWithdrawal() {
    var key = new ReviewDispatcher.PrKey("owner", "repo", 9);
    var retired = new ReviewDispatcher.PerPrState();
    retired.markRetired();
    dispatcher.seedState(key, retired);

    assertTrue(dispatcher.dispatchApprovalWithdrawal(push(9, "sha2")));

    verify(approvalOnPush, timeout(2000)).withdraw(push(9, "sha2"));
  }

  @Test
  void reviewQueuedBehindOnlyAnApprovalWithdrawalIsNotCountedAsCoalesced() {
    var state = new ReviewDispatcher.PerPrState();
    var trigger = SpanContext.getInvalid();

    state.onWithdrawal(push(42, "sha2"), trigger);
    // The worker took the withdrawal; the push's review arrives while it runs.
    assertEquals(push(42, "sha2"), state.takeNext().withdrawal());
    var first = state.onDispatch(reviewRequest("owner", "repo", 42, "sha2"), trigger);
    var second = state.onDispatch(reviewRequest("owner", "repo", 42, "sha3"), trigger);

    assertEquals(0, first.coalescedCount(), "nothing was superseded by the push's own review");
    assertEquals(1, second.coalescedCount());
  }

  @Test
  void reviewQueuedBehindAReviewInFlightIsStillCountedAsCoalesced() {
    var state = new ReviewDispatcher.PerPrState();
    var trigger = SpanContext.getInvalid();

    state.onDispatch(reviewRequest("owner", "repo", 42, "sha1"), trigger);
    state.takeNext();
    state.onWithdrawal(push(42, "sha2"), trigger);
    var queued = state.onDispatch(reviewRequest("owner", "repo", 42, "sha2"), trigger);

    assertEquals(1, queued.coalescedCount());
    assertFalse(state.tryRetireIfIdle(), "the withdrawal still waits");
  }

  @Test
  void shouldRunCiRecheckOnItsOwnWhenNoReviewIsInFlight() {
    var task = recheck(42, "sha1");

    assertTrue(dispatcher.dispatchCiRecheck(task));

    verify(ciHoldRevisit, timeout(2000)).revisit(task);
    verify(orchestrator, never()).review(any());
  }

  @Test
  void shouldRunCiRecheckAfterTheInFlightReview() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    var task = recheck(42, "sha1");

    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    started.await(2, TimeUnit.SECONDS);
    assertTrue(dispatcher.dispatchCiRecheck(task));
    verify(ciHoldRevisit, after(200).never()).revisit(any());

    release.countDown();

    verify(ciHoldRevisit, timeout(5000)).revisit(task);
    var order = inOrder(orchestrator, ciHoldRevisit);
    order.verify(orchestrator).review(any(ReviewOrchestrator.ReviewRequest.class));
    order.verify(ciHoldRevisit).revisit(task);
  }

  @Test
  void shouldRunCiRecheckAfterTheQueuedReviewToo() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    var task = recheck(42, "sha1");

    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    started.await(2, TimeUnit.SECONDS);
    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha2"));
    assertTrue(dispatcher.dispatchCiRecheck(task));

    release.countDown();

    verify(ciHoldRevisit, timeout(5000)).revisit(task);
    var order = inOrder(orchestrator, ciHoldRevisit);
    order.verify(orchestrator, times(2)).review(any(ReviewOrchestrator.ReviewRequest.class));
    order.verify(ciHoldRevisit).revisit(task);
  }

  @Test
  void shouldKeepQueuedCiRecheckWhenAReviewArrivesBehindIt() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    var task = recheck(42, "sha1");

    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    started.await(2, TimeUnit.SECONDS);
    assertTrue(dispatcher.dispatchCiRecheck(task));
    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha2"));

    release.countDown();

    verify(ciHoldRevisit, timeout(5000)).revisit(task);
    var order = inOrder(orchestrator, ciHoldRevisit);
    order.verify(orchestrator, times(2)).review(any(ReviewOrchestrator.ReviewRequest.class));
    order.verify(ciHoldRevisit).revisit(task);
  }

  @Test
  void shouldRunCiRecheckWhenTheReviewAheadOfItIsRateLimited() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    var task = recheck(42, "sha1");

    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    started.await(2, TimeUnit.SECONDS);
    // The window closes while the first review runs, so the queued review is skipped at drain.
    when(rateLimiter.hold(eq("owner"), eq("repo"), eq(42), any()))
        .thenReturn(AutoReviewRateLimiter.Hold.SKIP);
    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha2"));
    assertTrue(dispatcher.dispatchCiRecheck(task));

    release.countDown();

    verify(ciHoldRevisit, timeout(5000)).revisit(task);
    verify(orchestrator, times(1)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.RATE_LIMITED), anyLong(), eq("owner"), eq("repo"), eq(42), any());
  }

  @Test
  void shouldKeepOnlyTheLatestQueuedCiRecheck() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);

    dispatcher.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    started.await(2, TimeUnit.SECONDS);
    dispatcher.dispatchCiRecheck(recheck(42, "sha1"));
    dispatcher.dispatchCiRecheck(recheck(42, "sha1"));

    release.countDown();

    verify(ciHoldRevisit, timeout(5000)).revisit(recheck(42, "sha1"));
    verify(ciHoldRevisit, after(300).times(1)).revisit(any());
  }

  /**
   * The worker's own contract, whatever the collaborator does: a recheck that throws never stops
   * the queue. {@code CiHoldRevisit.revisit} catches its own failures today, so production does not
   * reach the dispatcher's catch; the stub stands in for any later path that escapes it (#833
   * review).
   */
  @Test
  void shouldContinueAfterCiRecheckFailure() {
    doThrow(new RuntimeException("boom")).when(ciHoldRevisit).revisit(any());

    dispatcher.dispatchCiRecheck(recheck(42, "sha1"));
    verify(ciHoldRevisit, timeout(2000)).revisit(any());

    ReviewOrchestrator.ReviewRequest req = reviewRequest("owner", "repo", 42, "sha2");
    dispatcher.dispatch(req);
    verify(orchestrator, timeout(2000)).review(req);
  }

  @Test
  void shouldReturnFalseWhenExecutorRejectsCiRecheck() {
    var rejecting = mock(ExecutorService.class);
    doThrow(new RejectedExecutionException("saturated")).when(rejecting).execute(any());
    var saturated =
        new ReviewDispatcher(
            rejecting,
            orchestrator,
            rateLimiter,
            slots,
            deferred,
            skipEmitter,
            ciHoldRevisit,
            approvalOnPush,
            coverage,
            OpenTelemetry.noop());

    assertFalse(saturated.dispatchCiRecheck(recheck(42, "sha1")));
    assertEquals(0, saturated.stateCount());
  }

  @Test
  void shouldNotReviveRetiredStateOnCiRecheck() {
    var key = new ReviewDispatcher.PrKey("owner", "repo", 9);
    var retired = new ReviewDispatcher.PerPrState();
    retired.retired = true;
    dispatcher.seedState(key, retired);
    var task = recheck(9, "sha1");

    dispatcher.dispatchCiRecheck(task);

    verify(ciHoldRevisit, timeout(2000)).revisit(task);
    assertEquals(false, retired.running);
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
            "owner", "repo", 3, "", "(manual)", "", "", "main", 1L, TestScopes.REPOSITORY_ID, true);

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
    dispatcher =
        new ReviewDispatcher(
            executor,
            orchestrator,
            rateLimiter,
            slots,
            deferred,
            skipEmitter,
            ciHoldRevisit,
            approvalOnPush,
            coverage,
            OpenTelemetry.noop());

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

  @Test
  void shouldDispatchCiRecheckWithInfoLoggingDisabled() {
    var julLogger = java.util.logging.Logger.getLogger(ReviewDispatcher.class.getName());
    var originalLevel = julLogger.getLevel();
    julLogger.setLevel(java.util.logging.Level.WARNING);
    try {
      var task = recheck(21, "sha1");

      assertTrue(dispatcher.dispatchCiRecheck(task));

      verify(ciHoldRevisit, timeout(2000)).revisit(task);
    } finally {
      julLogger.setLevel(originalLevel);
    }
  }

  private static void awaitStateCount(ReviewDispatcher dispatcher, int expected) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (dispatcher.stateCount() != expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, dispatcher.stateCount());
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
    dispatcher =
        new ReviewDispatcher(
            executor,
            orchestrator,
            rateLimiter,
            slots,
            deferred,
            skipEmitter,
            ciHoldRevisit,
            approvalOnPush,
            coverage,
            OpenTelemetry.noop());

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

    dispatcher =
        new ReviewDispatcher(
            executor,
            orchestrator,
            rateLimiter,
            slots,
            deferred,
            skipEmitter,
            ciHoldRevisit,
            approvalOnPush,
            coverage,
            OpenTelemetry.noop());

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

    // The throttle window starts when the automatic review finishes, keyed by the PR, and
    // remembers the head the review covered.
    verify(orchestrator, timeout(2000)).review(req);
    verify(rateLimiter, timeout(2000)).recordCompletion("owner", "repo", 13, "sha1");
  }

  @Test
  void shouldNotRecordCompletionForManualReview() {
    var req =
        new ReviewOrchestrator.ReviewRequest(
            "owner",
            "repo",
            14,
            "",
            "(manual)",
            "",
            "",
            "main",
            1L,
            TestScopes.REPOSITORY_ID,
            true);
    when(orchestrator.review(req)).thenReturn(true);

    dispatcher.dispatch(req);

    // A manual /review must not shift the automatic throttle window.
    verify(orchestrator, timeout(2000)).review(req);
    verify(rateLimiter, after(500).never())
        .recordCompletion(anyString(), anyString(), anyInt(), any());
  }

  @Test
  void shouldSkipCoalescedAutomaticReviewWithinWindow() throws InterruptedException {
    // Simulate the real limiter: throttled from the moment a completion is recorded.
    var throttled = new java.util.concurrent.atomic.AtomicBoolean(false);
    when(rateLimiter.hold(anyString(), anyString(), anyInt(), any()))
        .thenAnswer(
            invocation ->
                throttled.get()
                    ? AutoReviewRateLimiter.Hold.SKIP
                    : AutoReviewRateLimiter.Hold.NONE);
    doAnswer(
            invocation -> {
              throttled.set(true);
              return null;
            })
        .when(rateLimiter)
        .recordCompletion(anyString(), anyString(), anyInt(), any());

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
    verify(rateLimiter, times(1)).recordCompletion(eq("owner"), eq("repo"), eq(17), any());
  }

  @Test
  void shouldSkipAutomaticReviewWhenWindowClosedAfterDispatchGate() {
    // Pins the dispatch race: the controller gate passed before a prior worker recorded its
    // completion, so this fresh worker's very first batch must hit the re-check and skip.
    when(rateLimiter.hold(eq("owner"), eq("repo"), eq(19), any()))
        .thenReturn(AutoReviewRateLimiter.Hold.SKIP);

    dispatcher.dispatch(reviewRequest("owner", "repo", 19, "sha1"));

    awaitEmptyDispatcherState(dispatcher, 2, TimeUnit.SECONDS);
    verify(orchestrator, never()).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(rateLimiter, never()).recordCompletion(anyString(), anyString(), anyInt(), any());
    // The async skip still surfaces as a structured event.
    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.RATE_LIMITED),
            anyLong(),
            eq("owner"),
            eq("repo"),
            eq(19),
            anyString());
  }

  @Test
  void shouldRunCoalescedManualReviewWithinWindow() throws InterruptedException {
    // Even a closed window must not hold back an explicit /review that coalesced behind an
    // in-flight automatic run.
    when(rateLimiter.hold(anyString(), anyString(), anyInt(), any()))
        .thenReturn(AutoReviewRateLimiter.Hold.SKIP);

    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var manualReq =
        new ReviewOrchestrator.ReviewRequest(
            "owner",
            "repo",
            18,
            "",
            "(manual)",
            "",
            "",
            "main",
            1L,
            TestScopes.REPOSITORY_ID,
            true);
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
            "owner",
            "repo",
            18,
            "",
            "(manual)",
            "",
            "",
            "main",
            1L,
            TestScopes.REPOSITORY_ID,
            true));
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
    verify(rateLimiter, after(500).never())
        .recordCompletion(anyString(), anyString(), anyInt(), any());
  }

  @Test
  void shouldNotRecordCompletionWhenReviewThrows() {
    doThrow(new RuntimeException("boom"))
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    dispatcher.dispatch(reviewRequest("owner", "repo", 15, "sha1"));

    // An unexpected failure means no review was surfaced, so the next event may retry immediately.
    verify(orchestrator, timeout(2000)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(rateLimiter, after(500).never())
        .recordCompletion(anyString(), anyString(), anyInt(), any());
  }

  @Test
  void shouldAnswerManualReviewOfTheHeadUnderReviewWithoutASecondRun() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    var manual = manualRequest(30);
    when(coverage.currentHead(manual)).thenReturn(Optional.of("SHA1"));

    dispatcher.dispatch(reviewRequest("owner", "repo", 30, "sha1"));
    assertTrue(started.await(2, TimeUnit.SECONDS));
    assertTrue(dispatcher.dispatch(manual));
    release.countDown();

    verify(coverage).notifyCovered(manual, "sha1");
    awaitEmptyDispatcherState(dispatcher, 5, TimeUnit.SECONDS);
    verify(orchestrator, after(300).times(1)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(orchestrator, never()).review(manual);
  }

  @Test
  void shouldQueueManualReviewOfTheNewHeadWhenAnOlderHeadIsUnderReview()
      throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    var manual = manualRequest(31);
    when(coverage.currentHead(manual)).thenReturn(Optional.of("sha2"));

    dispatcher.dispatch(reviewRequest("owner", "repo", 31, "sha1"));
    assertTrue(started.await(2, TimeUnit.SECONDS));
    assertTrue(dispatcher.dispatch(manual));
    release.countDown();

    verify(orchestrator, timeout(5000)).review(manual);
    verify(coverage, never()).notifyCovered(any(), anyString());
  }

  @Test
  void shouldRunManualReviewOnAnIdlePrEvenOnAnUnchangedHead() {
    var auto = reviewRequest("owner", "repo", 32, "sha1");
    dispatcher.dispatch(auto);
    verify(orchestrator, timeout(2000)).review(auto);
    awaitEmptyDispatcherState(dispatcher, 2, TimeUnit.SECONDS);
    var manual = manualRequest(32);

    assertTrue(dispatcher.dispatch(manual));

    verify(orchestrator, timeout(2000)).review(manual);
    verify(coverage, never()).currentHead(any());
    verify(coverage, never()).notifyCovered(any(), anyString());
  }

  @Test
  void shouldQueueManualReviewWhenTheHeadCannotBeRead() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    var manual = manualRequest(33);
    when(coverage.currentHead(manual)).thenReturn(Optional.empty());

    dispatcher.dispatch(reviewRequest("owner", "repo", 33, "sha1"));
    assertTrue(started.await(2, TimeUnit.SECONDS));
    assertTrue(dispatcher.dispatch(manual));
    release.countDown();

    verify(orchestrator, timeout(5000)).review(manual);
    verify(coverage).currentHead(manual);
    verify(coverage, never()).notifyCovered(any(), anyString());
  }

  @Test
  void shouldQueueManualReviewWhenTheRunningReviewHasNotResolvedItsHead()
      throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    var second = manualRequest(34);

    dispatcher.dispatch(manualRequest(34));
    assertTrue(started.await(2, TimeUnit.SECONDS));
    assertTrue(dispatcher.dispatch(second));
    release.countDown();

    verify(orchestrator, timeout(5000).times(2)).review(second);
    verify(coverage, never()).currentHead(any());
  }

  @Test
  void shouldRunManualReviewThatArrivesDuringACiRecheck() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              started.countDown();
              release.await(5, TimeUnit.SECONDS);
              return null;
            })
        .when(ciHoldRevisit)
        .revisit(any());
    var manual = manualRequest(35);

    dispatcher.dispatchCiRecheck(recheck(35, "sha1"));
    assertTrue(started.await(2, TimeUnit.SECONDS));
    assertTrue(dispatcher.dispatch(manual));
    release.countDown();

    verify(orchestrator, timeout(5000)).review(manual);
    verify(coverage, never()).currentHead(any());
  }

  @Test
  void shouldRunManualReviewWhenTheRunningReviewEndedAfterTheHeadWasRead() {
    var state = new ReviewDispatcher.PerPrState();
    beginReview(state, "sha1");
    state.endReview();

    var result = state.onDispatch(manualRequest(36), "sha1", SpanContext.getInvalid());

    assertFalse(result.covered());
    assertTrue(result.startWorker());
  }

  @Test
  void shouldQueueManualReviewWhenANewerHeadStartedReviewAfterTheHeadWasRead() {
    var state = new ReviewDispatcher.PerPrState();
    state.onDispatch(reviewRequest("owner", "repo", 39, "sha2"), SpanContext.getInvalid());
    state.takeNext();
    beginReview(state, "sha2");

    var result = state.onDispatch(manualRequest(39), "sha1", SpanContext.getInvalid());

    assertFalse(result.covered());
    assertEquals(1, result.coalescedCount());
  }

  @Test
  void shouldAnswerOrQueueManualReviewWithInfoLoggingDisabled() throws InterruptedException {
    var julLogger = java.util.logging.Logger.getLogger(ReviewDispatcher.class.getName());
    var originalLevel = julLogger.getLevel();
    julLogger.setLevel(java.util.logging.Level.WARNING);
    try {
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      blockReviewUntil(started, release);
      var manual = manualRequest(40);
      when(coverage.currentHead(manual))
          .thenReturn(Optional.of("sha1"))
          .thenReturn(Optional.of("sha2"));

      dispatcher.dispatch(reviewRequest("owner", "repo", 40, "sha1"));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      assertTrue(dispatcher.dispatch(manual));
      assertTrue(dispatcher.dispatch(manual));
      release.countDown();

      verify(coverage).notifyCovered(manual, "sha1");
      verify(orchestrator, timeout(5000)).review(manual);
    } finally {
      julLogger.setLevel(originalLevel);
    }
  }

  @Test
  void shouldDispatchNormallyWithoutACoveredHead() {
    var state = new ReviewDispatcher.PerPrState();

    var result = state.onDispatch(manualRequest(41), null, SpanContext.getInvalid());

    assertFalse(result.covered());
    assertTrue(result.startWorker());
  }

  @Test
  void shouldCoalesceManualReviewWhenAnotherRequestAlreadyWaits() {
    var state = new ReviewDispatcher.PerPrState();
    state.onDispatch(reviewRequest("owner", "repo", 37, "sha1"), SpanContext.getInvalid());
    state.takeNext();
    beginReview(state, "sha1");
    state.onDispatch(reviewRequest("owner", "repo", 37, "sha2"), SpanContext.getInvalid());

    var result = state.onDispatch(manualRequest(37), "sha1", SpanContext.getInvalid());

    assertFalse(result.covered());
    assertEquals(2, result.coalescedCount());
  }

  @Test
  void shouldRetryManualReviewOnARetiredState() {
    var state = new ReviewDispatcher.PerPrState();
    beginReview(state, "sha1");
    state.markRetired();

    var result = state.onDispatch(manualRequest(38), "sha1", SpanContext.getInvalid());

    assertFalse(result.covered());
    assertTrue(result.removeAndRetry());
  }

  @Test
  void shouldTreatAMissingHeadUnderReviewAsUnresolved() {
    var state = new ReviewDispatcher.PerPrState();
    beginReview(state, null);

    assertEquals("", state.reviewingSha());
  }

  /** A dispatcher on virtual threads, as in production, with the given per-repository limits. */
  private ReviewDispatcher limitedDispatcher(AutoReviewSlots limits) {
    return limitedDispatcher(limits, deferred);
  }

  /** A dispatcher whose rate window is the real one, read on the test's clock. */
  private ReviewDispatcher dispatcherWithRealWindow() {
    return dispatcherWithRealWindow(deferred);
  }

  private ReviewDispatcher dispatcherWithRealWindow(DeferredReviews kept) {
    return new ReviewDispatcher(
        reviewExecutor,
        orchestrator,
        new AutoReviewRateLimiter(WINDOW_MS, 10_000, now::get),
        slots,
        kept,
        skipEmitter,
        ciHoldRevisit,
        approvalOnPush,
        coverage,
        OpenTelemetry.noop());
  }

  private void awaitDeferred(int expected) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (deferred.pending.size() != expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, deferred.pending.size());
  }

  private ReviewDispatcher limitedDispatcher(AutoReviewSlots limits, DeferredReviews kept) {
    reviewExecutor.shutdownNow();
    reviewExecutor = Executors.newVirtualThreadPerTaskExecutor();
    return new ReviewDispatcher(
        reviewExecutor,
        orchestrator,
        rateLimiter,
        limits,
        kept,
        skipEmitter,
        ciHoldRevisit,
        approvalOnPush,
        coverage,
        OpenTelemetry.noop());
  }

  private static void awaitWaiting(AutoReviewSlots limits, int expected) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (limits.waiting(1L, TestScopes.REPOSITORY_ID) != expected
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, limits.waiting(1L, TestScopes.REPOSITORY_ID));
  }

  /** Spins until no review waits for a slot; the caller's assertions cover a timeout. */
  private static void awaitNoneWaiting(AutoReviewSlots limits) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (limits.waiting(1L, TestScopes.REPOSITORY_ID) != 0 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
  }

  @Test
  void automaticReviewsOfOneRepositoryRunOnlyAsManyAtOnceAsTheLimit() throws InterruptedException {
    var limits = new AutoReviewSlots(2, 10);
    var limited = limitedDispatcher(limits);
    var running = new AtomicInteger();
    var mostAtOnce = new AtomicInteger();
    var started = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
              started.countDown();
              release.await(5, TimeUnit.SECONDS);
              running.decrementAndGet();
              return true;
            })
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    // Six pull requests of one repository are opened at once.
    for (int pr = 1; pr <= 6; pr++) {
      assertTrue(limited.dispatch(reviewRequest("owner", "repo", pr, "sha" + pr)));
    }

    assertTrue(started.await(5, TimeUnit.SECONDS));
    awaitWaiting(limits, 4);
    assertEquals(2, running.get());

    release.countDown();

    // Every pull request is still reviewed, two at a time.
    verify(orchestrator, timeout(5000).times(6))
        .review(any(ReviewOrchestrator.ReviewRequest.class));
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);
    assertEquals(2, mostAtOnce.get());
    assertEquals(0, limits.trackedRepositories());
    verify(skipEmitter, never()).recordSkip(any(), anyLong(), any(), any(), anyInt(), any());
  }

  @Test
  void anAutomaticReviewPastTheQueueLimitIsKeptAndReviewedLater() throws InterruptedException {
    var limits = new AutoReviewSlots(1, 1);
    var limited = limitedDispatcher(limits);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);

    limited.dispatch(reviewRequest("owner", "repo", 1, "sha1"));
    assertTrue(started.await(5, TimeUnit.SECONDS));
    limited.dispatch(reviewRequest("owner", "repo", 2, "sha2"));
    awaitWaiting(limits, 1);

    // Turning the review away is a decision, not a rejected dispatch: the delivery is not rolled
    // back.
    assertTrue(limited.dispatch(reviewRequest("owner", "repo", 3, "sha3")));
    awaitDeferred(1);
    release.countDown();
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);

    // It is not reviewed while the queue is full, and it is not dropped either.
    verify(orchestrator, times(2)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(orchestrator, never()).review(reviewRequest("owner", "repo", 3, "sha3"));
    verify(skipEmitter, never()).recordSkip(any(), anyLong(), any(), any(), anyInt(), any());
    assertEquals(List.of(), deferred.takeDue());

    now.addAndGet(ReviewDispatcher.QUEUE_FULL_RETRY.toMillis());
    var due = deferred.takeDue();
    assertEquals(List.of(reviewRequest("owner", "repo", 3, "sha3")), due);
    assertTrue(limited.dispatchDeferred(due.getFirst()));

    verify(orchestrator, timeout(5000)).review(reviewRequest("owner", "repo", 3, "sha3"));
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);
    assertEquals(0, limits.trackedRepositories());
  }

  @Test
  void anAutomaticReviewPastTheQueueLimitIsSkippedAndCountedWhenItCannotBeKept()
      throws InterruptedException {
    var limits = new AutoReviewSlots(1, 1);
    var limited = limitedDispatcher(limits, new DeferredReviews(0, now::get));
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);

    limited.dispatch(reviewRequest("owner", "repo", 1, "sha1"));
    assertTrue(started.await(5, TimeUnit.SECONDS));
    limited.dispatch(reviewRequest("owner", "repo", 2, "sha2"));
    awaitWaiting(limits, 1);

    assertTrue(limited.dispatch(reviewRequest("owner", "repo", 3, "sha3")));

    verify(skipEmitter, timeout(5000))
        .recordSkip(
            eq(ReviewSkipReason.REPOSITORY_QUEUE_FULL),
            eq(1L),
            eq("owner"),
            eq("repo"),
            eq(3),
            anyString());
    release.countDown();
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);
    verify(orchestrator, times(2)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(orchestrator, never()).review(reviewRequest("owner", "repo", 3, "sha3"));
    verify(rateLimiter, never()).recordCompletion(eq("owner"), eq("repo"), eq(3), any());
    assertEquals(0, limits.trackedRepositories());
  }

  @Test
  void aPushCoalescedBehindAReviewIsReviewedWhenTheWindowCloses() throws InterruptedException {
    var windowed = dispatcherWithRealWindow();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);

    windowed.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    assertTrue(started.await(2, TimeUnit.SECONDS));
    // The push lands while the review of the previous head is being published.
    windowed.dispatch(reviewRequest("owner", "repo", 42, "sha2"));
    release.countDown();
    awaitEmptyDispatcherState(windowed, 5, TimeUnit.SECONDS);

    // Inside the window the new head is not reviewed, and it is not dropped either.
    verify(orchestrator, times(1)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(skipEmitter, never()).recordSkip(any(), anyLong(), any(), any(), anyInt(), any());
    assertEquals(List.of(), deferred.takeDue());

    now.addAndGet(WINDOW_MS);
    var due = deferred.takeDue();
    assertEquals(List.of(reviewRequest("owner", "repo", 42, "sha2")), due);
    assertTrue(windowed.dispatchDeferred(due.getFirst()));

    verify(orchestrator, timeout(5000)).review(reviewRequest("owner", "repo", 42, "sha2"));
    awaitEmptyDispatcherState(windowed, 5, TimeUnit.SECONDS);
  }

  @Test
  void aDeferredReviewHandedBackInsideANewWindowWaitsForThatWindow() {
    var windowed = dispatcherWithRealWindow();
    when(orchestrator.review(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(true);
    windowed.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    awaitEmptyDispatcherState(windowed, 5, TimeUnit.SECONDS);

    // Handed back while the window of the review that just ran is open.
    assertTrue(windowed.dispatchDeferred(reviewRequest("owner", "repo", 42, "sha2")));
    awaitEmptyDispatcherState(windowed, 5, TimeUnit.SECONDS);

    // One automatic review per window: the second head waits for the window to close.
    verify(orchestrator, times(1)).review(any(ReviewOrchestrator.ReviewRequest.class));
    now.addAndGet(WINDOW_MS - 1);
    assertEquals(List.of(), deferred.takeDue());
    now.addAndGet(1);
    assertEquals(List.of(reviewRequest("owner", "repo", 42, "sha2")), deferred.takeDue());
  }

  @Test
  void aPushThatArrivesAsTheWindowClosesIsKeptAndNotSkipped() {
    // The clock passes the end of the window right after the first time it is read.
    var readings = new AtomicLong(WINDOW_MS - 1);
    var limiter = new AutoReviewRateLimiter(WINDOW_MS, 10_000, readings::getAndIncrement);
    limiter.deadlines.put("owner/repo#42", new AutoReviewRateLimiter.Window(WINDOW_MS, "sha1"));
    var atTheBoundary =
        new ReviewDispatcher(
            reviewExecutor,
            orchestrator,
            limiter,
            slots,
            deferred,
            skipEmitter,
            ciHoldRevisit,
            approvalOnPush,
            coverage,
            OpenTelemetry.noop());
    var push = reviewRequest("owner", "repo", 42, "sha2");

    assertEquals(ReviewDispatcher.RateWindowHold.KEPT, atTheBoundary.holdForRateWindow(push));

    // The head the last review did not cover is reviewed when the window closes.
    now.set(WINDOW_MS);
    assertEquals(List.of(push), deferred.takeDue());
    // The next event finds the window closed and is reviewed at once.
    assertEquals(
        ReviewDispatcher.RateWindowHold.NOT_HELD,
        atTheBoundary.holdForRateWindow(reviewRequest("owner", "repo", 42, "sha3")));
  }

  @Test
  void anEventForTheHeadJustReviewedIsSkippedAndNotKept() {
    var windowed = dispatcherWithRealWindow();
    when(orchestrator.review(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(true);
    windowed.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    awaitEmptyDispatcherState(windowed, 5, TimeUnit.SECONDS);

    // A reopen of the unchanged head, inside the window.
    windowed.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    awaitEmptyDispatcherState(windowed, 5, TimeUnit.SECONDS);

    verify(orchestrator, times(1)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.RATE_LIMITED), anyLong(), eq("owner"), eq("repo"), eq(42), any());
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aPushInsideTheWindowIsSkippedAndCountedWhenItCannotBeKept() {
    var windowed = dispatcherWithRealWindow(new DeferredReviews(0, now::get));
    when(orchestrator.review(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(true);
    windowed.dispatch(reviewRequest("owner", "repo", 42, "sha1"));
    awaitEmptyDispatcherState(windowed, 5, TimeUnit.SECONDS);

    windowed.dispatch(reviewRequest("owner", "repo", 42, "sha2"));
    awaitEmptyDispatcherState(windowed, 5, TimeUnit.SECONDS);

    verify(orchestrator, times(1)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.RATE_LIMITED), anyLong(), eq("owner"), eq("repo"), eq(42), any());
  }

  @Test
  void aNewerAutomaticEventReplacesTheDeferredReviewAndAManualOneLeavesIt() {
    deferred.defer(reviewRequest("owner", "repo", 7, "sha1"), Long.MAX_VALUE);

    dispatcher.dispatch(
        new ReviewOrchestrator.ReviewRequest(
            "owner",
            "repo",
            7,
            "",
            "(manual)",
            "",
            "",
            "main",
            1L,
            TestScopes.REPOSITORY_ID,
            true));
    assertFalse(deferred.isEmpty());

    dispatcher.dispatch(reviewRequest("owner", "repo", 7, "sha2"));
    assertTrue(deferred.isEmpty());
  }

  @Test
  void theDeferredReviewOfAPullRequestCanBeDroppedByScope() {
    deferred.defer(reviewRequest("owner", "repo", 7, "sha1"), Long.MAX_VALUE);

    dispatcher.forgetDeferred(new RepoScope(1L, TestScopes.REPOSITORY_ID), 8);
    assertFalse(deferred.isEmpty());

    dispatcher.forgetDeferred(new RepoScope(1L, TestScopes.REPOSITORY_ID), 7);
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aManualReviewAndAnotherRepositoryNeverWaitForTheSlot() throws InterruptedException {
    var limits = new AutoReviewSlots(1, 1);
    var limited = limitedDispatcher(limits);
    var started = new CountDownLatch(3);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    var otherRepository =
        new ReviewOrchestrator.ReviewRequest(
            "owner",
            "other",
            1,
            "sha",
            "title",
            "",
            "base",
            "main",
            1L,
            TestScopes.REPOSITORY_ID + 1,
            false);

    limited.dispatch(reviewRequest("owner", "repo", 1, "sha1"));
    limited.dispatch(manualRequest(2));
    limited.dispatch(otherRepository);

    // All three run at once although the repository's one slot is taken.
    assertTrue(started.await(5, TimeUnit.SECONDS));
    assertEquals(0, limits.waiting(1L, TestScopes.REPOSITORY_ID));
    assertEquals(1, limits.running(1L, TestScopes.REPOSITORY_ID));

    release.countDown();
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);
    assertEquals(0, limits.trackedRepositories());
  }

  @Test
  void aManualReviewReplacesItsPullRequestsWaitingAutomaticReviewAndRunsAtOnce()
      throws InterruptedException {
    var limits = new AutoReviewSlots(1, 5);
    var limited = limitedDispatcher(limits);
    var firstStarted = new CountDownLatch(1);
    var manualStarted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var manual = manualRequest(2);
    doAnswer(
            invocation -> {
              firstStarted.countDown();
              if (manual.equals(invocation.getArgument(0))) {
                manualStarted.countDown();
              }
              release.await(5, TimeUnit.SECONDS);
              return true;
            })
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    limited.dispatch(reviewRequest("owner", "repo", 1, "sha1"));
    assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
    var waiting = reviewRequest("owner", "repo", 2, "sha2");
    limited.dispatch(waiting);
    awaitWaiting(limits, 1);

    // A maintainer asks for a review of the pull request whose automatic review is queued.
    assertTrue(limited.dispatch(manual));

    // It runs while the repository's one slot is still held by the first review.
    assertTrue(manualStarted.await(5, TimeUnit.SECONDS));
    assertEquals(0, limits.waiting(1L, TestScopes.REPOSITORY_ID));
    assertEquals(1, limits.running(1L, TestScopes.REPOSITORY_ID));

    release.countDown();
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);
    verify(orchestrator, never()).review(waiting);
    assertEquals(0, limits.trackedRepositories());
  }

  @Test
  void theSlotIsReturnedWhenTheReviewFails() {
    var limits = new AutoReviewSlots(1, 1);
    var limited = limitedDispatcher(limits);
    when(orchestrator.review(any(ReviewOrchestrator.ReviewRequest.class)))
        .thenThrow(new IllegalStateException("model unavailable"))
        .thenReturn(true);

    limited.dispatch(reviewRequest("owner", "repo", 1, "sha1"));
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);
    assertEquals(0, limits.trackedRepositories());

    limited.dispatch(reviewRequest("owner", "repo", 2, "sha2"));

    verify(orchestrator, timeout(5000)).review(reviewRequest("owner", "repo", 2, "sha2"));
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);
    assertEquals(0, limits.trackedRepositories());
  }

  @Test
  void theRateWindowIsReadAgainOnceAWaitingReviewGetsItsSlot() throws InterruptedException {
    var limits = new AutoReviewSlots(1, 1);
    var limited = limitedDispatcher(limits);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);

    limited.dispatch(reviewRequest("owner", "repo", 1, "sha1"));
    assertTrue(started.await(5, TimeUnit.SECONDS));
    limited.dispatch(reviewRequest("owner", "repo", 2, "sha2"));
    awaitWaiting(limits, 1);
    // While it waited, a manual review of the same pull request completed and opened its window.
    when(rateLimiter.hold(eq("owner"), eq("repo"), eq(2), any()))
        .thenReturn(AutoReviewRateLimiter.Hold.SKIP);

    release.countDown();

    verify(skipEmitter, timeout(5000))
        .recordSkip(
            eq(ReviewSkipReason.RATE_LIMITED), anyLong(), eq("owner"), eq("repo"), eq(2), any());
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);
    verify(orchestrator, times(1)).review(any(ReviewOrchestrator.ReviewRequest.class));
    assertEquals(0, limits.trackedRepositories());
  }

  @Test
  void aHandedBackReviewHeldAgainAtItsSlotLeavesTheNewerHeadDeferredMeanwhile()
      throws InterruptedException {
    var limits = new AutoReviewSlots(1, 1);
    var limited = limitedDispatcher(limits);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    limited.dispatch(reviewRequest("owner", "repo", 1, "sha1"));
    assertTrue(started.await(5, TimeUnit.SECONDS));

    // A deferred review is handed back and waits for a slot of the busy repository.
    assertTrue(limited.dispatchDeferred(reviewRequest("owner", "repo", 2, "sha-old")));
    awaitWaiting(limits, 1);
    // A push arrives inside the rate window meanwhile, and its head is kept.
    long windowEnd = now.get() + WINDOW_MS;
    when(rateLimiter.hold(eq("owner"), eq("repo"), eq(2), any()))
        .thenReturn(new AutoReviewRateLimiter.Hold(true, OptionalLong.of(windowEnd)));
    assertEquals(
        ReviewDispatcher.RateWindowHold.KEPT,
        limited.holdForRateWindow(reviewRequest("owner", "repo", 2, "sha-new")));

    // The older request gets its slot, reads the window again and is held too.
    release.countDown();
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);

    verify(orchestrator, times(1)).review(any(ReviewOrchestrator.ReviewRequest.class));
    verify(skipEmitter, never()).recordSkip(any(), anyLong(), any(), any(), anyInt(), any());
    now.set(windowEnd);
    assertEquals(List.of(reviewRequest("owner", "repo", 2, "sha-new")), deferred.takeDue());
  }

  @Test
  void aHandedBackReviewTurnedAwayByAFullQueueLeavesTheNewerHeadDeferredMeanwhile()
      throws InterruptedException {
    var limits = new AutoReviewSlots(1, 1);
    var limited = limitedDispatcher(limits);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    blockReviewUntil(started, release);
    limited.dispatch(reviewRequest("owner", "repo", 1, "sha1"));
    assertTrue(started.await(5, TimeUnit.SECONDS));
    limited.dispatch(reviewRequest("owner", "repo", 2, "sha2"));
    awaitWaiting(limits, 1);

    // A newer head of the third pull request is deferred before its older one comes back.
    deferred.defer(reviewRequest("owner", "repo", 3, "sha-new"), now.get() + WINDOW_MS);
    assertTrue(limited.dispatchDeferred(reviewRequest("owner", "repo", 3, "sha-old")));
    // Its worker ends once the full queue has turned it away; the other two are still busy.
    awaitStateCount(limited, 2);
    release.countDown();
    awaitEmptyDispatcherState(limited, 5, TimeUnit.SECONDS);

    verify(orchestrator, never()).review(reviewRequest("owner", "repo", 3, "sha-old"));
    verify(skipEmitter, never()).recordSkip(any(), anyLong(), any(), any(), anyInt(), any());
    now.addAndGet(WINDOW_MS);
    assertEquals(List.of(reviewRequest("owner", "repo", 3, "sha-new")), deferred.takeDue());
  }

  @Test
  void aReviewInterruptedWhileWaitingForItsSlotIsNotRun() throws InterruptedException {
    var limits = new AutoReviewSlots(1, 1);
    var limited = limitedDispatcher(limits);
    var started = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              started.countDown();
              try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
              } catch (InterruptedException _) {
                // The shutdown interrupts the workers one after the other. The slot is kept until
                // the waiting review has been interrupted too, so a freed slot cannot reach it
                // first.
                awaitNoneWaiting(limits);
                Thread.currentThread().interrupt();
              }
              return true;
            })
        .when(orchestrator)
        .review(any(ReviewOrchestrator.ReviewRequest.class));

    limited.dispatch(reviewRequest("owner", "repo", 1, "sha1"));
    assertTrue(started.await(5, TimeUnit.SECONDS));
    limited.dispatch(reviewRequest("owner", "repo", 2, "sha2"));
    awaitWaiting(limits, 1);

    reviewExecutor.shutdownNow();

    assertTrue(reviewExecutor.awaitTermination(5, TimeUnit.SECONDS));
    verify(orchestrator, never()).review(reviewRequest("owner", "repo", 2, "sha2"));
    assertEquals(0, limits.trackedRepositories());
    assertEquals(0, limited.stateCount());
  }

  /** Marks a review of {@code headSha} running, with no span and no requests counted. */
  private static void beginReview(ReviewDispatcher.PerPrState state, String headSha) {
    state.beginReview(
        headSha, Span.getInvalid(), new ReviewDispatcher.PerPrState.Served(List.of(), 0));
  }

  private static ReviewOrchestrator.ReviewRequest manualRequest(int prNumber) {
    return new ReviewOrchestrator.ReviewRequest(
        "owner",
        "repo",
        prNumber,
        "",
        "(manual review)",
        "",
        "",
        "main",
        1L,
        TestScopes.REPOSITORY_ID,
        true);
  }

  private static ReviewOrchestrator.ReviewRequest reviewRequest(
      String owner, String repo, int prNumber, String sha) {
    return new ReviewOrchestrator.ReviewRequest(
        owner,
        repo,
        prNumber,
        sha,
        "title",
        "",
        "base",
        "main",
        1L,
        TestScopes.REPOSITORY_ID,
        false);
  }
}
