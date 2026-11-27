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
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeferredReviewReleaseTest {

  private final AtomicLong now = new AtomicLong(0);
  private final DeferredReviews deferred = new DeferredReviews(10, now::get);
  private final ReviewDispatcher dispatcher = mock(ReviewDispatcher.class);
  private final PrPauseService pauses = mock(PrPauseService.class);
  private final ReviewSkipEmitter skipEmitter = mock(ReviewSkipEmitter.class);
  private final Vertx vertx = mock(Vertx.class);
  private final ExecutorService executor = mock(ExecutorService.class);
  private final List<Runnable> submitted = new ArrayList<>();

  private final DeferredReviewRelease release =
      new DeferredReviewRelease(deferred, dispatcher, pauses, skipEmitter, vertx, executor);

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
    deferred.defer(request(1, "sha-a"), 0);
    deferred.defer(request(2, "sha-b"), 1_000);

    release.onStart(null);
    tick.getFirst().handle(42L);
    submitted.removeFirst().run();

    // Only the review that is due goes back to the dispatcher.
    verify(dispatcher).dispatchDeferred(request(1, "sha-a"));
    verify(dispatcher, never()).dispatchDeferred(request(2, "sha-b"));
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
    deferred.defer(request(1, "sha-a"), 1_000);

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
    deferred.defer(request(1, "sha-a"), 1_000);
    doThrow(new RejectedExecutionException("shutting down")).when(executor).execute(any());

    release.schedule();
    release.schedule();

    verify(executor, times(2)).execute(any());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void aPullRequestPausedWhileItsReviewWaitedIsNotReviewed() {
    when(pauses.isPaused(any(), eq("owner"), eq("repo"), eq(1))).thenReturn(true);
    deferred.defer(request(1, "sha-a"), 0);

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
  void aReviewTheExecutorRejectsIsCountedAndNotDeferredAgain() {
    when(dispatcher.dispatchDeferred(any())).thenReturn(false);
    deferred.defer(request(1, "sha-a"), 0);

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
        .thenThrow(new IllegalStateException("database unavailable"));
    when(dispatcher.dispatchDeferred(any())).thenReturn(true);
    deferred.defer(request(1, "sha-a"), 0);
    deferred.defer(request(2, "sha-b"), 0);

    release.release();

    verify(dispatcher).dispatchDeferred(request(2, "sha-b"));
    verify(dispatcher, never()).dispatchDeferred(request(1, "sha-a"));
    verify(skipEmitter, never()).recordSkip(any(), anyLong(), any(), any(), anyInt(), any());

    // It is due again after the retry delay.
    now.set(DeferredReviewRelease.RETRY.toMillis());
    assertEquals(List.of(request(1, "sha-a")), deferred.takeDue());
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
