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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import dev.thiagogonzaga.thrillhousebot.webhook.PrPauseService;
import dev.thiagogonzaga.thrillhousebot.webhook.ReviewTriggerFilter;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeferredReviewReleaseTest {

  private static final String DATABASE_DOWN = "database unavailable";
  private static final String SHA_A = "sha-a";
  private static final String SHA_B = "sha-b";
  private static final ReviewTriggerFilter.Skip DRAFT_SKIP =
      new ReviewTriggerFilter.Skip(ReviewSkipReason.DRAFT, "PR is a draft");

  private final AtomicLong now = new AtomicLong(0);
  private final DeferredReviews deferred = new DeferredReviews(10, now::get);
  private final ReviewDispatcher dispatcher = mock(ReviewDispatcher.class);
  private final PrPauseService pauses = mock(PrPauseService.class);
  private final HeldReviewDraftCheck draftCheck = mock(HeldReviewDraftCheck.class);
  private final ReviewSkipEmitter skipEmitter = mock(ReviewSkipEmitter.class);
  private final Vertx vertx = mock(Vertx.class);
  private final ExecutorService executor = mock(ExecutorService.class);
  private final List<Runnable> submitted = new ArrayList<>();

  private final DeferredReviewRelease release =
      new DeferredReviewRelease(
          deferred, dispatcher, pauses, draftCheck, skipEmitter, vertx, executor);

  /** Keeps what the release submits, so a test decides when it runs. */
  private void captureSubmittedWork() {
    doAnswer(
            invocation -> {
              submitted.add(invocation.getArgument(0));
              return null;
            })
        .when(executor)
        .execute(any());
  }

  @Test
  void oneTimerHandsDueReviewsBackUntilShutdown() {
    captureSubmittedWork();
    var tick = new ArrayList<Handler<Long>>();
    when(vertx.setPeriodic(eq(DeferredReviewRelease.TICK.toMillis()), any()))
        .thenAnswer(
            invocation -> {
              tick.add(invocation.getArgument(1));
              return 42L;
            });
    when(dispatcher.dispatchDeferred(any())).thenReturn(true);
    deferred.defer(request(1, SHA_A), 0);
    deferred.defer(request(2, SHA_B), 1_000);

    release.onStart(null);
    tick.getFirst().handle(42L);
    submitted.removeFirst().run();

    // Only the review that is due goes back to the dispatcher.
    verify(dispatcher).dispatchDeferred(request(1, SHA_A));
    verify(dispatcher, never()).dispatchDeferred(request(2, SHA_B));
    verifyNoInteractions(skipEmitter);

    release.onStop(null);
    verify(vertx).cancelTimer(42L);
    assertTrue(deferred.isEmpty());
  }

  @Test
  void stoppingBeforeStartingCancelsNoTimer() {
    release.onStop(null);

    verify(vertx, never()).cancelTimer(anyLong());
  }

  @Test
  void aTickWithNothingDeferredSubmitsNoWork() {
    release.schedule();

    verifyNoInteractions(executor);
  }

  @Test
  void aTickWhileAnotherIsStillRunningSubmitsNoSecondRun() {
    captureSubmittedWork();
    deferred.defer(request(1, SHA_A), 1_000);

    release.schedule();
    release.schedule();
    assertEquals(1, submitted.size());

    // Once the first run ends, the next tick runs again.
    submitted.removeFirst().run();
    release.schedule();
    assertEquals(1, submitted.size());
  }

  @Test
  void anExecutorThatRefusesWorkLeavesTheNextTickFreeToRun() {
    deferred.defer(request(1, SHA_A), 1_000);
    doThrow(new RejectedExecutionException("shutting down")).when(executor).execute(any());

    release.schedule();
    release.schedule();

    verify(executor, times(2)).execute(any());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void aPullRequestPausedWhileItsReviewWaitedIsNotReviewed() {
    when(pauses.isPaused(any(), eq("owner"), eq("repo"), eq(1))).thenReturn(true);
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    verify(dispatcher, never()).dispatchDeferred(any());
    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.PAUSED),
            eq(TestScopes.INSTALLATION_ID),
            eq("owner"),
            eq("repo"),
            eq(1),
            anyString());
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aPullRequestConvertedToDraftWhileItsReviewWaitedIsNotReviewed() {
    when(draftCheck.skipFor(request(1, SHA_A))).thenReturn(Optional.of(DRAFT_SKIP));
    when(dispatcher.dispatchDeferred(any())).thenReturn(true);
    deferred.defer(request(1, SHA_A), 0);
    deferred.defer(request(2, SHA_B), 0);

    release.release();

    // Only the pull request that is a draft now loses its review.
    verify(dispatcher, never()).dispatchDeferred(request(1, SHA_A));
    verify(dispatcher).dispatchDeferred(request(2, SHA_B));
    verify(skipEmitter)
        .recordSkip(
            ReviewSkipReason.DRAFT,
            TestScopes.INSTALLATION_ID,
            "owner",
            "repo",
            1,
            "PR is a draft; the review a limit held back for it is dropped");
    deferred.forget(request(2, SHA_B));
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aHeadThatComesBackIsNotReadAgainToSeeWhetherItIsADraft() {
    when(dispatcher.dispatchDeferred(any())).thenReturn(true);
    deferred.defer(request(1, SHA_A), 0);
    release.release();

    // A full repository queue turns the review away again, and it is handed back again.
    deferred.requeue(request(1, SHA_A), 30_000);
    now.set(30_000);
    release.release();

    verify(dispatcher, times(2)).dispatchDeferred(request(1, SHA_A));
    verify(draftCheck, times(1)).skipFor(request(1, SHA_A));

    // A new head of the pull request is read for, once.
    deferred.defer(request(1, SHA_B), 30_000);
    release.release();
    verify(draftCheck, times(1)).skipFor(request(1, SHA_B));
  }

  @Test
  void aHandBackThatFailedBeforeTheDraftReadStillReadsOnItsRetry() {
    when(pauses.isPaused(any(), any(), any(), eq(1)))
        .thenThrow(new IllegalStateException(DATABASE_DOWN))
        .thenReturn(false);
    when(draftCheck.skipFor(request(1, SHA_A))).thenReturn(Optional.of(DRAFT_SKIP));
    deferred.defer(request(1, SHA_A), 0);

    // The pause lookup fails, so the review waits again without the pull request being read.
    release.release();
    verifyNoInteractions(draftCheck);

    now.set(DeferredReviewRelease.RETRY.toMillis());
    release.release();

    verify(draftCheck, times(1)).skipFor(request(1, SHA_A));
    verify(dispatcher, never()).dispatchDeferred(any());
    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.DRAFT),
            eq(TestScopes.INSTALLATION_ID),
            eq("owner"),
            eq("repo"),
            eq(1),
            anyString());
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aDraftReadThatAnsweredNothingIsNotRepeatedWhenTheHandBackFails() {
    // The read is done, and then the dispatcher throws: the retry does not read again.
    when(dispatcher.dispatchDeferred(any()))
        .thenThrow(new IllegalStateException("boom"))
        .thenReturn(true);
    deferred.defer(request(1, SHA_A), 0);

    release.release();
    now.set(DeferredReviewRelease.RETRY.toMillis());
    release.release();

    verify(dispatcher, times(2)).dispatchDeferred(request(1, SHA_A));
    verify(draftCheck, times(1)).skipFor(request(1, SHA_A));
  }

  @Test
  void aPausedPullRequestIsNotReadToSeeWhetherItIsADraft() {
    when(pauses.isPaused(any(), eq("owner"), eq("repo"), eq(1))).thenReturn(true);
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    verifyNoInteractions(draftCheck);
  }

  @Test
  void aDraftFoundAtHandBackLeavesTheNewerHeadDeferredMeanwhileToItsOwnHandBack() {
    // The pull request was marked ready and pushed to between the read and its answer.
    when(draftCheck.skipFor(any()))
        .thenAnswer(
            invocation -> {
              deferred.defer(request(1, SHA_B), 1_000);
              return Optional.of(DRAFT_SKIP);
            })
        .thenReturn(Optional.empty());
    when(dispatcher.dispatchDeferred(any())).thenReturn(true);
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    verifyNoInteractions(skipEmitter);
    verify(dispatcher, never()).dispatchDeferred(any());
    now.set(1_000);
    release.release();
    verify(dispatcher).dispatchDeferred(request(1, SHA_B));
  }

  @Test
  void aReviewTheExecutorRejectsIsCountedAndNotDeferredAgain() {
    when(dispatcher.dispatchDeferred(any())).thenReturn(false);
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.DISPATCH_REJECTED),
            eq(TestScopes.INSTALLATION_ID),
            eq("owner"),
            eq("repo"),
            eq(1),
            anyString());
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aReviewThatCouldNotBeHandedBackWaitsAgainAndTheOthersStillGo() {
    when(pauses.isPaused(any(), any(), any(), eq(1)))
        .thenThrow(new IllegalStateException(DATABASE_DOWN));
    when(dispatcher.dispatchDeferred(any())).thenReturn(true);
    deferred.defer(request(1, SHA_A), 0);
    deferred.defer(request(2, SHA_B), 0);

    release.release();

    verify(dispatcher).dispatchDeferred(request(2, SHA_B));
    verify(dispatcher, never()).dispatchDeferred(request(1, SHA_A));
    verify(skipEmitter, never()).recordSkip(any(), anyLong(), any(), any(), anyInt(), any());

    // It is due again after the retry delay.
    now.set(DeferredReviewRelease.RETRY.toMillis());
    assertEquals(List.of(request(1, SHA_A)), deferred.takeDue());
  }

  @Test
  void aReviewThatCouldNotBeHandedBackDoesNotDisplaceANewerHeadDeferredMeanwhile() {
    // A push arrives after the review was handed out and before the hand-back fails.
    when(pauses.isPaused(any(), any(), any(), eq(1)))
        .thenAnswer(
            invocation -> {
              deferred.defer(request(1, SHA_B), 1_000);
              throw new IllegalStateException(DATABASE_DOWN);
            });
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    verify(skipEmitter, never()).recordSkip(any(), anyLong(), any(), any(), anyInt(), any());
    now.set(DeferredReviewRelease.RETRY.toMillis());
    assertEquals(List.of(request(1, SHA_B)), deferred.takeDue());
  }

  @Test
  void aSkipThatCannotBeRecordedDoesNotStrandTheOtherDueReviews() {
    when(pauses.isPaused(any(), any(), any(), anyInt()))
        .thenThrow(new IllegalStateException(DATABASE_DOWN));
    for (int pr = 1; pr <= 3; pr++) {
      deferred.defer(request(pr, "sha-" + pr), 0);
    }
    release.release();
    // An hour on every review is given up, and counting the skip fails too.
    doThrow(new IllegalStateException("metrics unavailable"))
        .when(skipEmitter)
        .recordSkip(any(), anyLong(), any(), any(), anyInt(), any());
    now.set(DeferredReviews.GIVE_UP_AFTER.toMillis() + DeferredReviewRelease.RETRY.toMillis());

    release.release();

    // Each one was still taken in turn; the first failure did not end the pass.
    verify(skipEmitter, times(3))
        .recordSkip(
            eq(ReviewSkipReason.DISPATCH_REJECTED),
            eq(TestScopes.INSTALLATION_ID),
            eq("owner"),
            eq("repo"),
            anyInt(),
            anyString());
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aReviewThatCouldNotBeHandedBackForAnHourIsCountedAsSkipped() {
    when(pauses.isPaused(any(), any(), any(), eq(1)))
        .thenThrow(new IllegalStateException(DATABASE_DOWN));
    deferred.defer(request(1, SHA_A), 0);

    // First handed out at 0: it waits again after every failure until the hour is up.
    release.release();
    now.set(DeferredReviews.GIVE_UP_AFTER.toMillis() - 1);
    release.release();
    verify(skipEmitter, never()).recordSkip(any(), anyLong(), any(), any(), anyInt(), any());
    assertFalse(deferred.isEmpty());

    now.set(DeferredReviews.GIVE_UP_AFTER.toMillis() + DeferredReviewRelease.RETRY.toMillis());
    release.release();

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.DISPATCH_REJECTED),
            eq(TestScopes.INSTALLATION_ID),
            eq("owner"),
            eq("repo"),
            eq(1),
            anyString());
    verify(dispatcher, never()).dispatchDeferred(any());
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aReviewTheDispatcherFailedToTakeWaitsAgain() {
    when(dispatcher.dispatchDeferred(any())).thenThrow(new IllegalStateException("no tracer"));
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    verifyNoInteractions(skipEmitter);
    assertEquals(List.of(), deferred.takeDue());
    now.set(DeferredReviewRelease.RETRY.toMillis());
    assertEquals(List.of(request(1, SHA_A)), deferred.takeDue());
  }

  @Test
  void anExecutorRefusalLeavesTheNewerHeadDeferredMeanwhileInPlace() {
    // A push arrives after the review was handed out, and the executor refuses work.
    when(pauses.isPaused(any(), any(), any(), eq(1)))
        .thenAnswer(
            invocation -> {
              deferred.defer(request(1, SHA_B), 1_000);
              return false;
            });
    when(dispatcher.dispatchDeferred(any())).thenReturn(false);
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    // The older request gave way to the newer head, which still waits: nothing is skipped.
    verifyNoInteractions(skipEmitter);
    verify(dispatcher, never()).dispatchDeferred(any());
    now.set(1_000);
    assertEquals(List.of(request(1, SHA_B)), deferred.takeDue());
  }

  @Test
  void aPauseFoundAtHandBackLeavesTheNewerHeadDeferredMeanwhileToItsOwnHandBack() {
    // The pull request was resumed and pushed to between the pause lookup and its answer.
    when(pauses.isPaused(any(), any(), any(), eq(1)))
        .thenAnswer(
            invocation -> {
              deferred.defer(request(1, SHA_B), 1_000);
              return true;
            })
        .thenReturn(false);
    when(dispatcher.dispatchDeferred(any())).thenReturn(true);
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    verifyNoInteractions(skipEmitter);
    verify(dispatcher, never()).dispatchDeferred(any());
    now.set(1_000);
    release.release();
    verify(dispatcher).dispatchDeferred(request(1, SHA_B));
  }

  @Test
  void aReviewThatCouldNotBeHandedBackDoesNotDisplaceANewerHeadTheWorkerPutBack() {
    // A newer push was dispatched, which drops the entry, and its worker then put it back.
    when(pauses.isPaused(any(), any(), any(), eq(1)))
        .thenAnswer(
            invocation -> {
              deferred.forget(request(1, SHA_A));
              deferred.requeue(request(1, SHA_B), 1_000);
              throw new IllegalStateException(DATABASE_DOWN);
            });
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    verifyNoInteractions(skipEmitter);
    now.set(1_000 + DeferredReviewRelease.RETRY.toMillis());
    assertEquals(List.of(request(1, SHA_B)), deferred.takeDue());
  }

  @Test
  void aReviewThatCouldNotBeHandedBackIsNotKeptAgainOnceItsPullRequestWasForgotten() {
    // The pull request is closed between the hand-out and the failed hand-back.
    when(pauses.isPaused(any(), any(), any(), eq(1)))
        .thenAnswer(
            invocation -> {
              deferred.forget(request(1, SHA_A).scope(), 1);
              throw new IllegalStateException(DATABASE_DOWN);
            });
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    verifyNoInteractions(skipEmitter);
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aReviewWhoseEntryANewerEventDroppedIsNotHandedBack() {
    // A newer push is dispatched between the hand-out and the hand-back, which drops the entry.
    when(pauses.isPaused(any(), any(), any(), eq(1)))
        .thenAnswer(
            invocation -> {
              deferred.forget(request(1, SHA_A));
              return false;
            });
    deferred.defer(request(1, SHA_A), 0);

    release.release();

    verifyNoInteractions(dispatcher, skipEmitter);
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aDroppedReviewWhoseSkipCannotBeRecordedIsNotKeptAgain() {
    when(pauses.isPaused(any(), any(), any(), eq(1))).thenReturn(true);
    when(dispatcher.dispatchDeferred(request(2, SHA_B))).thenReturn(false);
    doThrow(new IllegalStateException("metrics unavailable"))
        .when(skipEmitter)
        .recordSkip(any(), anyLong(), any(), any(), anyInt(), any());
    deferred.defer(request(1, SHA_A), 0);
    deferred.defer(request(2, SHA_B), 0);

    release.release();

    // Both were dropped on purpose; failing to count that does not put them back.
    assertTrue(deferred.isEmpty());
  }

  private static ReviewOrchestrator.ReviewRequest request(int prNumber, String sha) {
    return new ReviewOrchestrator.ReviewRequest(
        "owner",
        "repo",
        prNumber,
        sha,
        "title",
        "",
        "base",
        "main",
        TestScopes.INSTALLATION_ID,
        TestScopes.REPOSITORY_ID,
        false);
  }
}
