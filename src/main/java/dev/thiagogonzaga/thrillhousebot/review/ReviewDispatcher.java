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

import dev.thiagogonzaga.thrillhousebot.config.ReviewExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches PR reviews on a dedicated executor with per-PR serialization. Rapid webhook events for
 * the same PR are coalesced so only the latest head SHA is reviewed after the in-flight run
 * completes.
 */
@ApplicationScoped
public final class ReviewDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ReviewDispatcher.class);

  private final ExecutorService reviewExecutor;
  private final ReviewOrchestrator orchestrator;
  private final AutoReviewRateLimiter autoReviewRateLimiter;

  private final ConcurrentHashMap<PrKey, PerPrState> states = new ConcurrentHashMap<>();

  @Inject
  public ReviewDispatcher(
      @ReviewExecutor ExecutorService reviewExecutor,
      ReviewOrchestrator orchestrator,
      AutoReviewRateLimiter autoReviewRateLimiter) {
    this.reviewExecutor = reviewExecutor;
    this.orchestrator = orchestrator;
    this.autoReviewRateLimiter = autoReviewRateLimiter;
  }

  /**
   * Queues a review for the request's PR, coalescing rapid same-PR events into the in-flight run.
   *
   * @return {@code true} if the review was queued or coalesced into a running worker; {@code false}
   *     if the executor rejected the task (e.g. it is saturated or shutting down) so no review will
   *     run. Callers use this to roll back webhook dedup state so a redelivery can retry.
   */
  public boolean dispatch(ReviewOrchestrator.ReviewRequest req) {
    var key = new PrKey(req.owner(), req.repo(), req.prNumber());

    while (true) {
      var state = states.computeIfAbsent(key, ignored -> new PerPrState());
      var result = state.onDispatch(req);
      if (result.removeAndRetry()) {
        states.remove(key, state);
        continue;
      }
      if (result.coalescedCount() > 0) {
        logCoalesced(req, result.coalescedCount());
      } else if (result.startWorker()) {
        logQueued(req);
      }

      if (result.startWorker()) {
        try {
          reviewExecutor.execute(() -> runSerialized(key, state));
        } catch (RejectedExecutionException e) {
          log.warn(
              "Failed to queue review for {}/{} #{} — executor rejected task",
              req.owner(),
              req.repo(),
              req.prNumber(),
              e);
          retire(key, state);
          return false;
        }
      }
      return true;
    }
  }

  private void runSerialized(PrKey key, PerPrState state) {
    var completed = false;
    try {
      drainReviews(key, state);
      completed = true;
    } catch (Exception e) {
      completed = true;
      log.error("Review worker aborted for {}/{} #{}", key.owner(), key.repo(), key.prNumber(), e);
      retire(key, state);
    } finally {
      // Errors (OOM, assertion failures) propagate to the executor thread, but the state
      // must still be retired so later dispatches for this PR are not coalesced forever
      if (!completed) {
        log.error(
            "Review worker aborted fatally for {}/{} #{}", key.owner(), key.repo(), key.prNumber());
        retire(key, state);
      }
    }
  }

  private void drainReviews(PrKey key, PerPrState state) {
    while (true) {
      var batch = state.takeNextReview();

      // The controller's rate-limit gate ran before dispatch, but a request coalesced behind an
      // in-flight review passed that gate before the window opened — re-check here so it cannot
      // run a second automatic review right after the one that just recorded its completion.
      var req = batch.request();
      if (!req.isManualTrigger()
          && autoReviewRateLimiter.isThrottled(req.owner(), req.repo(), req.prNumber())) {
        log.info(
            "Skipping coalesced automatic review for {}/{} #{} — within the auto-review "
                + "rate window (manual /review bypasses)",
            req.owner(),
            req.repo(),
            req.prNumber());
      } else {
        reviewBatch(batch);
      }

      if (state.tryRetireIfIdle()) {
        states.remove(key, state);
        return;
      }
    }
  }

  private void reviewBatch(PerPrState.ReviewBatch batch) {
    logReviewStart(batch.request(), batch.coalesced(), batch.queueWaitMs());
    try {
      var surfaced = orchestrator.review(batch.request());
      // Start the throttle window only when a review was actually posted, and at completion
      // (not dispatch) so a long-running review does not eat into it. A failed review must not
      // block the retry on the next push, and manual reviews never shift the automatic window.
      if (surfaced && !batch.request().isManualTrigger()) {
        autoReviewRateLimiter.recordCompletion(
            batch.request().owner(), batch.request().repo(), batch.request().prNumber());
      }
    } catch (RuntimeException e) {
      log.error(
          "Review failed for {}/{} #{}",
          batch.request().owner(),
          batch.request().repo(),
          batch.request().prNumber(),
          e);
    }
  }

  private void logCoalesced(ReviewOrchestrator.ReviewRequest req, int coalesced) {
    if (log.isInfoEnabled()) {
      log.info(
          "Coalescing review for {}/{} #{} — {} superseded request(s), latest sha: {}",
          req.owner(),
          req.repo(),
          req.prNumber(),
          coalesced,
          abbreviateSha(req.commitSha()));
    }
  }

  private void logQueued(ReviewOrchestrator.ReviewRequest req) {
    if (log.isInfoEnabled()) {
      log.info(
          "Queued review for {}/{} #{} (sha: {})",
          req.owner(),
          req.repo(),
          req.prNumber(),
          abbreviateSha(req.commitSha()));
    }
  }

  private void logReviewStart(
      ReviewOrchestrator.ReviewRequest req, int coalesced, long queueWaitMs) {
    if (log.isInfoEnabled()) {
      if (coalesced > 0) {
        log.info(
            "Starting review for {}/{} #{} after coalescing {} superseded request(s), "
                + "dispatch latency: {}ms, sha: {}",
            req.owner(),
            req.repo(),
            req.prNumber(),
            coalesced,
            queueWaitMs,
            abbreviateSha(req.commitSha()));
      } else if (queueWaitMs > 0) {
        log.info(
            "Starting review for {}/{} #{} — dispatch latency: {}ms, sha: {}",
            req.owner(),
            req.repo(),
            req.prNumber(),
            queueWaitMs,
            abbreviateSha(req.commitSha()));
      }
    }
  }

  /** Marks the state dead so a racing dispatch never revives it, then unmaps it. */
  private void retire(PrKey key, PerPrState state) {
    state.markRetired();
    states.remove(key, state);
  }

  /** Seeds a per-PR state for tests that simulate dispatch races, without exposing the map. */
  void seedState(PrKey key, PerPrState state) {
    states.put(key, state);
  }

  /** Visible for tests that assert dispatcher state lifecycle. */
  int stateCount() {
    return states.size();
  }

  private static String abbreviateSha(String sha) {
    if (sha == null || sha.isEmpty()) {
      return "(manual)";
    }
    return sha.length() > 7 ? sha.substring(0, 7) : sha;
  }

  record PrKey(String owner, String repo, int prNumber) {}

  static final class PerPrState {
    private final Object lock = new Object();
    ReviewOrchestrator.ReviewRequest latestRequest;
    boolean running = false;
    boolean retired = false;
    int coalesced = 0;
    long queuedAtMs;

    record DispatchResult(boolean removeAndRetry, boolean startWorker, int coalescedCount) {}

    record ReviewBatch(ReviewOrchestrator.ReviewRequest request, int coalesced, long queueWaitMs) {}

    DispatchResult onDispatch(ReviewOrchestrator.ReviewRequest req) {
      synchronized (lock) {
        // A finishing worker can retire the state between the map lookup above and this
        // lock; reviving a retired state would let two workers run for the same PR, so
        // drop the dead mapping and retry with a fresh state.
        if (retired) {
          return new DispatchResult(true, false, 0);
        }
        latestRequest = req;
        if (running) {
          coalesced++;
          return new DispatchResult(false, false, coalesced);
        }
        running = true;
        queuedAtMs = System.currentTimeMillis();
        return new DispatchResult(false, true, 0);
      }
    }

    ReviewBatch takeNextReview() {
      synchronized (lock) {
        var req = latestRequest;
        latestRequest = null;
        var batch = new ReviewBatch(req, coalesced, System.currentTimeMillis() - queuedAtMs);
        coalesced = 0;
        queuedAtMs = System.currentTimeMillis();
        return batch;
      }
    }

    boolean tryRetireIfIdle() {
      synchronized (lock) {
        if (latestRequest != null) {
          return false;
        }
        retired = true;
        running = false;
        return true;
      }
    }

    void markRetired() {
      synchronized (lock) {
        retired = true;
        running = false;
      }
    }
  }
}
