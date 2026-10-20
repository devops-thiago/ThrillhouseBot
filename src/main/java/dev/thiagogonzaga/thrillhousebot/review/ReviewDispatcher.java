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
 * completes. A CI re-evaluation of a held verdict (#825) rides the same per-PR worker, after any
 * review in flight, so it can never race a review of the pull request.
 *
 * <p>A manual {@code /review} that arrives while a review is running reads the pull request's head
 * first (#1152). When it is the head under review, the running review already covers it: the
 * command gets a reply and no second run is queued. A moved head, a head the dispatcher cannot
 * read, or a running review whose head is not known yet queues the command as before.
 */
@ApplicationScoped
public final class ReviewDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ReviewDispatcher.class);

  private final ExecutorService reviewExecutor;
  private final ReviewOrchestrator orchestrator;
  private final AutoReviewRateLimiter autoReviewRateLimiter;
  private final ReviewSkipEmitter skipEmitter;
  private final CiHoldRevisit ciHoldRevisit;
  private final RunningReviewCoverage coverage;

  private final ConcurrentHashMap<PrKey, PerPrState> states = new ConcurrentHashMap<>();

  @Inject
  public ReviewDispatcher(
      @ReviewExecutor ExecutorService reviewExecutor,
      ReviewOrchestrator orchestrator,
      AutoReviewRateLimiter autoReviewRateLimiter,
      ReviewSkipEmitter skipEmitter,
      CiHoldRevisit ciHoldRevisit,
      RunningReviewCoverage coverage) {
    this.reviewExecutor = reviewExecutor;
    this.orchestrator = orchestrator;
    this.autoReviewRateLimiter = autoReviewRateLimiter;
    this.skipEmitter = skipEmitter;
    this.ciHoldRevisit = ciHoldRevisit;
    this.coverage = coverage;
  }

  /**
   * Queues a review for the request's PR, coalescing rapid same-PR events into the in-flight run. A
   * manual {@code /review} for the head a running review covers is answered instead (#1152).
   *
   * @return {@code true} if the review was queued, coalesced into a running worker, or covered by
   *     the running review; {@code false} if the executor rejected the task (e.g. it is saturated
   *     or shutting down) so no review will run. Callers use this to roll back webhook dedup state
   *     so a redelivery can retry.
   */
  public boolean dispatch(ReviewOrchestrator.ReviewRequest req) {
    var key = new PrKey(req.owner(), req.repo(), req.prNumber());
    var coveredHead = req.isManualTrigger() ? headUnderReview(key, req) : null;

    while (true) {
      var state = states.computeIfAbsent(key, ignored -> new PerPrState());
      var result = state.onDispatch(req, coveredHead);
      if (result.removeAndRetry()) {
        states.remove(key, state);
        continue;
      }
      if (result.covered()) {
        answerCovered(req, coveredHead);
        return true;
      }
      if (result.coalescedCount() > 0) {
        logCoalesced(req, result.coalescedCount());
      } else {
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

  /**
   * The head a running review of the PR is reviewing, when the PR's current head is that same
   * commit; {@code null} when no review is running, its head is not known, the head moved, or the
   * head could not be read. The read happens outside the state lock, so {@link
   * PerPrState#onDispatch(ReviewOrchestrator.ReviewRequest, String)} checks the running head again
   * before dropping the request.
   */
  private String headUnderReview(PrKey key, ReviewOrchestrator.ReviewRequest req) {
    var state = states.get(key);
    var reviewing = state != null ? state.reviewingSha() : null;
    if (reviewing == null) {
      return null;
    }
    if (reviewing.isBlank()) {
      log.info(
          "A review of {}/{} #{} is running on a head not resolved yet — queueing the /review",
          req.owner(),
          req.repo(),
          req.prNumber());
      return null;
    }
    var head = coverage.currentHead(req);
    if (head.isEmpty()) {
      return null;
    }
    if (!head.get().equalsIgnoreCase(reviewing)) {
      if (log.isInfoEnabled()) {
        log.info(
            "Head of {}/{} #{} moved to {} from {} under review — queueing the /review",
            req.owner(),
            req.repo(),
            req.prNumber(),
            abbreviateSha(head.get()),
            abbreviateSha(reviewing));
      }
      return null;
    }
    return reviewing;
  }

  /**
   * Answers a {@code /review} the running review of {@code head} covers, instead of queueing it.
   */
  private void answerCovered(ReviewOrchestrator.ReviewRequest req, String head) {
    if (log.isInfoEnabled()) {
      log.info(
          "Not queueing /review for {}/{} #{} — the running review covers its head {}",
          req.owner(),
          req.repo(),
          req.prNumber(),
          abbreviateSha(head));
    }
    coverage.notifyCovered(req, head);
  }

  /**
   * Queues a CI re-evaluation for the pull request's held verdict (#825), serialized with its
   * reviews: the worker runs every queued review first and the re-evaluation after them. It is
   * never dropped for a queued review, because a queued review is not guaranteed to read the CI
   * gate — the rate-limit re-check in {@link #runReviewBatch} can skip it, and a review can fail
   * before its gate read — and a re-evaluation that finds the review already released or replaced
   * the hold is a cheap no-op. Repeated completions for the same pull request collapse into one
   * pending re-evaluation.
   *
   * @return {@code true} if the re-evaluation was queued or coalesced; {@code false} if the
   *     executor rejected the task, so the caller can roll back the webhook dedup state and a
   *     redelivery can retry.
   */
  public boolean dispatchCiRecheck(CiHoldRevisit.Recheck task) {
    var key = new PrKey(task.owner(), task.repo(), task.prNumber());

    while (true) {
      var state = states.computeIfAbsent(key, ignored -> new PerPrState());
      var outcome = state.onRecheck(task);
      if (outcome == PerPrState.RecheckOutcome.RETRY) {
        states.remove(key, state);
        continue;
      }
      if (log.isInfoEnabled()) {
        log.info(
            "CI re-evaluation for {}/{} #{} (sha: {}): {}",
            task.owner(),
            task.repo(),
            task.prNumber(),
            abbreviateSha(task.headSha()),
            outcome);
      }
      if (outcome == PerPrState.RecheckOutcome.STARTED) {
        try {
          reviewExecutor.execute(() -> runSerialized(key, state));
        } catch (RejectedExecutionException e) {
          log.warn(
              "Failed to queue CI re-evaluation for {}/{} #{} — executor rejected task",
              task.owner(),
              task.repo(),
              task.prNumber(),
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
      // Even when an Error propagates, the state must be retired or later dispatches for this
      // PR would be coalesced forever.
      if (!completed) {
        log.error(
            "Review worker aborted fatally for {}/{} #{}", key.owner(), key.repo(), key.prNumber());
        retire(key, state);
      }
    }
  }

  private void drainReviews(PrKey key, PerPrState state) {
    while (true) {
      var next = state.takeNext();
      if (next.recheck() != null) {
        recheckCi(next.recheck());
      } else {
        runReviewBatch(state, next.review());
      }

      if (state.tryRetireIfIdle()) {
        states.remove(key, state);
        return;
      }
    }
  }

  private void runReviewBatch(PerPrState state, PerPrState.ReviewBatch batch) {
    // Re-check after dispatch: a request can pass the controller gate then reach here once the
    // window closed (coalesce or recordCompletion gap).
    var req = batch.request();
    if (!req.isManualTrigger()
        && autoReviewRateLimiter.isThrottled(req.owner(), req.repo(), req.prNumber())) {
      skipEmitter.recordSkip(
          ReviewSkipReason.RATE_LIMITED,
          req.owner(),
          req.repo(),
          req.prNumber(),
          "within the auto-review rate window after dispatch (manual /review bypasses)");
    } else {
      reviewBatch(state, batch);
    }
  }

  private void recheckCi(CiHoldRevisit.Recheck task) {
    try {
      ciHoldRevisit.revisit(task);
    } catch (RuntimeException e) {
      log.error(
          "CI re-evaluation failed for {}/{} #{}", task.owner(), task.repo(), task.prNumber(), e);
    }
  }

  private void reviewBatch(PerPrState state, PerPrState.ReviewBatch batch) {
    logReviewStart(batch.request(), batch.coalesced(), batch.queueWaitMs());
    state.beginReview(batch.request().commitSha());
    try {
      var surfaced = orchestrator.review(batch.request());
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
    } finally {
      state.endReview();
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

    /** At most one CI re-evaluation waits per PR; it runs after every queued review (#825). */
    CiHoldRevisit.Recheck pendingRecheck;

    /**
     * The head the orchestrator is reviewing right now, blank for a manual run that resolves its
     * head itself, and {@code null} while no review runs — a CI re-evaluation or a rate-limit skip
     * reviews nothing (#1152).
     */
    String reviewingSha;

    boolean running = false;
    boolean retired = false;
    int coalesced = 0;
    long queuedAtMs;

    record DispatchResult(
        boolean removeAndRetry, boolean startWorker, int coalescedCount, boolean covered) {}

    record ReviewBatch(ReviewOrchestrator.ReviewRequest request, int coalesced, long queueWaitMs) {}

    /** What the worker runs next: a review batch, or a CI re-evaluation when no review waits. */
    record Next(ReviewBatch review, CiHoldRevisit.Recheck recheck) {}

    enum RecheckOutcome {
      /** The state was retired under the caller; look the PR up again. */
      RETRY,
      /** No worker was running; the caller starts one. */
      STARTED,
      /** A worker is running; the re-evaluation runs after it and any review it has queued. */
      QUEUED
    }

    DispatchResult onDispatch(ReviewOrchestrator.ReviewRequest req) {
      synchronized (lock) {
        // A finishing worker can retire the state between the map lookup and this
        // lock — reviving it would let two workers run for the same PR.
        if (retired) {
          return new DispatchResult(true, false, 0, false);
        }
        latestRequest = req;
        if (running) {
          coalesced++;
          return new DispatchResult(false, false, coalesced, false);
        }
        running = true;
        queuedAtMs = System.currentTimeMillis();
        return new DispatchResult(false, true, 0, false);
      }
    }

    /**
     * Drops the request when {@code coveredHead} is set, the review running now is still of that
     * head and nothing else waits; otherwise dispatches it like any other. The head was read
     * outside this lock, so a review that finished in between leaves the request to run.
     */
    DispatchResult onDispatch(ReviewOrchestrator.ReviewRequest req, String coveredHead) {
      synchronized (lock) {
        if (coveredHead != null
            && !retired
            && latestRequest == null
            && reviewingSha != null
            && reviewingSha.equalsIgnoreCase(coveredHead)) {
          return new DispatchResult(false, false, 0, true);
        }
        return onDispatch(req);
      }
    }

    String reviewingSha() {
      synchronized (lock) {
        return reviewingSha;
      }
    }

    void beginReview(String headSha) {
      synchronized (lock) {
        reviewingSha = headSha != null ? headSha : "";
      }
    }

    void endReview() {
      synchronized (lock) {
        reviewingSha = null;
      }
    }

    RecheckOutcome onRecheck(CiHoldRevisit.Recheck task) {
      synchronized (lock) {
        if (retired) {
          return RecheckOutcome.RETRY;
        }
        pendingRecheck = task;
        if (running) {
          return RecheckOutcome.QUEUED;
        }
        running = true;
        queuedAtMs = System.currentTimeMillis();
        return RecheckOutcome.STARTED;
      }
    }

    Next takeNext() {
      synchronized (lock) {
        if (latestRequest == null) {
          var task = pendingRecheck;
          pendingRecheck = null;
          return new Next(null, task);
        }
        var req = latestRequest;
        latestRequest = null;
        var batch = new ReviewBatch(req, coalesced, System.currentTimeMillis() - queuedAtMs);
        coalesced = 0;
        queuedAtMs = System.currentTimeMillis();
        return new Next(batch, null);
      }
    }

    boolean tryRetireIfIdle() {
      synchronized (lock) {
        if (latestRequest != null || pendingRecheck != null) {
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
