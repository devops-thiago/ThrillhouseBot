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
public class ReviewDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ReviewDispatcher.class);

  private final ExecutorService reviewExecutor;
  private final ReviewOrchestrator orchestrator;

  private final ConcurrentHashMap<PrKey, PerPrState> states = new ConcurrentHashMap<>();

  @Inject
  public ReviewDispatcher(
      @ReviewExecutor ExecutorService reviewExecutor, ReviewOrchestrator orchestrator) {
    this.reviewExecutor = reviewExecutor;
    this.orchestrator = orchestrator;
  }

  public void dispatch(ReviewOrchestrator.ReviewRequest req) {
    var key = new PrKey(req.owner(), req.repo(), req.prNumber());

    while (true) {
      var state = states.computeIfAbsent(key, ignored -> new PerPrState());
      var startWorker = false;
      synchronized (state.lock) {
        // A finishing worker can retire the state between the map lookup above and this
        // lock; reviving a retired state would let two workers run for the same PR, so
        // drop the dead mapping and retry with a fresh state.
        if (state.retired) {
          states.remove(key, state);
          continue;
        }
        if (state.running) {
          state.coalesced++;
          logCoalesced(req, state.coalesced);
        } else {
          logQueued(req);
          state.running = true;
          state.queuedAtMs = System.currentTimeMillis();
          startWorker = true;
        }
        state.latestRequest = req;
      }

      if (startWorker) {
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
        }
      }
      return;
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
      ReviewOrchestrator.ReviewRequest req;
      int coalesced;
      long queueWaitMs;
      synchronized (state.lock) {
        req = state.latestRequest;
        state.latestRequest = null;
        coalesced = state.coalesced;
        state.coalesced = 0;
        queueWaitMs = System.currentTimeMillis() - state.queuedAtMs;
        state.queuedAtMs = System.currentTimeMillis();
      }

      logReviewStart(req, coalesced, queueWaitMs);

      try {
        orchestrator.review(req);
      } catch (RuntimeException e) {
        log.error("Review failed for {}/{} #{}", req.owner(), req.repo(), req.prNumber(), e);
      }

      synchronized (state.lock) {
        if (state.latestRequest == null) {
          state.retired = true;
          state.running = false;
          states.remove(key, state);
          return;
        }
      }
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
    synchronized (state.lock) {
      state.retired = true;
      state.running = false;
      states.remove(key, state);
    }
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
    final Object lock = new Object();
    ReviewOrchestrator.ReviewRequest latestRequest;
    boolean running = false;
    boolean retired = false;
    int coalesced = 0;
    long queuedAtMs;
  }
}
