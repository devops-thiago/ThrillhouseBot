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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.config.ReviewExecutor;
import dev.thiagogonzaga.thrillhousebot.webhook.PrPauseService;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands the reviews in {@link DeferredReviews} back to {@link ReviewDispatcher} once they are due.
 * One periodic timer serves every pull request, so nothing is scheduled per deferred review. A
 * handed-back request goes through the dispatcher like any other: it is serialized with the pull
 * request's other work, takes a repository slot, and has the rate window read again on its worker,
 * so a pull request still gets at most one automatic review per window.
 *
 * <p>The request passed the pause, draft, branch and label filters when its event arrived. A pull
 * request paused since then is not reviewed; the labels and the head are read again by the review
 * itself before its first write, and an event the filters reject drops the deferred request when it
 * arrives.
 */
@ApplicationScoped
public class DeferredReviewRelease {

  /** How often due reviews are handed back, which is also how late one can start. */
  static final Duration TICK = Duration.ofSeconds(5);

  /** How long a request waits again when it could not be handed back. */
  static final Duration RETRY = Duration.ofSeconds(30);

  private final DeferredReviews deferred;
  private final ReviewDispatcher dispatcher;
  private final PrPauseService pauses;
  private final ReviewSkipEmitter skipEmitter;
  private final Vertx vertx;
  private final ExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile long timerId = -1;

  @Inject
  public DeferredReviewRelease(
      DeferredReviews deferred,
      ReviewDispatcher dispatcher,
      PrPauseService pauses,
      ReviewSkipEmitter skipEmitter,
      Vertx vertx,
      @ReviewExecutor ExecutorService executor) {
    this.deferred = deferred;
    this.dispatcher = dispatcher;
    this.pauses = pauses;
    this.skipEmitter = skipEmitter;
    this.vertx = vertx;
    this.executor = executor;
  }

  void onStart(@Observes StartupEvent event) {
    timerId = vertx.setPeriodic(TICK.toMillis(), id -> schedule());
  }

  void onStop(@Observes ShutdownEvent event) {
    if (timerId >= 0) {
      vertx.cancelTimer(timerId);
    }
    deferred.clear();
  }

  /**
   * Runs {@link #release} off the event loop, where the timer fires and where the pause lookup must
   * not run; skipped while one is running, and when nothing is deferred.
   */
  void schedule() {
    if (deferred.isEmpty() || !running.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.execute(
          () -> {
            try {
              release();
            } finally {
              running.set(false);
            }
          });
    } catch (RuntimeException e) {
      // The executor refuses work while it shuts down; nothing is handed back then.
      running.set(false);
      Log.debugf("Could not hand back deferred reviews: %s", LogSafe.oneLine(e.toString()));
    }
  }

  /**
   * Hands every due request back to the dispatcher. One that could not be handed back waits again,
   * and is counted as skipped once {@link DeferredReviews} refuses to keep it any longer. Whatever
   * one request throws, the rest of the due requests are still handed back: {@link
   * DeferredReviews#takeDue} hands each out once, so a request this pass left behind would wait
   * until it is given up.
   */
  void release() {
    for (var request : deferred.takeDue()) {
      try {
        handBackOrWaitAgain(request);
      } catch (RuntimeException e) {
        Log.errorf(
            "Could not record what became of the deferred review of %s/%s #%d: %s",
            request.owner(), request.repo(), request.prNumber(), LogSafe.oneLine(e.toString()));
      }
    }
  }

  private void handBackOrWaitAgain(ReviewOrchestrator.ReviewRequest request) {
    try {
      handBack(request);
    } catch (RuntimeException e) {
      // Put back without displacing a newer head an event deferred since this one was handed out.
      boolean waitsAgain = deferred.requeueFor(request, RETRY);
      Log.warnf(
          "Could not hand back the deferred review of %s/%s #%d, %s: %s",
          request.owner(),
          request.repo(),
          request.prNumber(),
          waitsAgain ? "trying again" : "giving it up",
          LogSafe.oneLine(e.toString()));
      if (!waitsAgain) {
        skipEmitter.recordSkip(
            ReviewSkipReason.DISPATCH_REJECTED,
            request.installationId(),
            request.owner(),
            request.repo(),
            request.prNumber(),
            "the deferred review could not be handed back and is no longer kept; a later push"
                + " or a manual /review reviews it");
      }
    }
  }

  private void handBack(ReviewOrchestrator.ReviewRequest request) {
    if (pauses.isPaused(request.scope(), request.owner(), request.repo(), request.prNumber())) {
      deferred.forget(request);
      skipEmitter.recordSkip(
          ReviewSkipReason.PAUSED,
          request.installationId(),
          request.owner(),
          request.repo(),
          request.prNumber(),
          "PR was paused via /pause while its review waited for a limit");
      return;
    }
    if (!dispatcher.dispatchDeferred(request)) {
      // The executor is saturated or shutting down. Not deferred again: a shutdown would loop.
      deferred.forget(request);
      skipEmitter.recordSkip(
          ReviewSkipReason.DISPATCH_REJECTED,
          request.installationId(),
          request.owner(),
          request.repo(),
          request.prNumber(),
          "review executor rejected the deferred review; a later push or a manual /review"
              + " reviews it");
    }
  }
}
