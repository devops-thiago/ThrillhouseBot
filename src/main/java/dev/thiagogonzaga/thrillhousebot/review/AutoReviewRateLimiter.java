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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Throttles automatic (webhook-triggered) reviews per PR: while the last automatic review of a PR
 * completed less than {@code thrillhousebot.review.auto-review-min-interval} ago, new {@code
 * pull_request} events for it are skipped — even when the head SHA changed — so noisy repositories
 * that push frequently do not burn AI spend and GitHub API budget on every commit.
 *
 * <p>Only the automatic path consults this limiter; a manual {@code /review} (or {@code
 * @thrillhousebot review}) never checks it and never records into it, so explicit triggers always
 * run and do not shift the automatic window. State is in-memory per replica, like {@link
 * ReviewDispatcher}.
 *
 * <p>Marking a draft ready lifts the window once for a head: a second {@code ready_for_review} of
 * the same head is throttled like any other event, so converting a pull request to draft and back
 * cannot start one review after another. Only the last such head of a pull request is remembered.
 */
@ApplicationScoped
public class AutoReviewRateLimiter {

  /** Sweep expired PRs once the map reaches this size, mirroring the other in-memory TTL caches. */
  static final int SWEEP_THRESHOLD = 10_000;

  /** PR key → epoch-millis deadline until which automatic reviews of that PR are throttled. */
  final ConcurrentHashMap<String, Long> deadlines = new ConcurrentHashMap<>();

  /** PR key → the head whose ready-for-review last lifted the window, and when it did. */
  final ConcurrentHashMap<String, ReadyHead> readyHeads = new ConcurrentHashMap<>();

  /** The head a ready-for-review lifted the window for, and the epoch millis it happened at. */
  record ReadyHead(String sha, long at) {}

  private final long intervalMillis;
  private final int sweepThreshold;
  private final LongSupplier clock;

  @Inject
  public AutoReviewRateLimiter(ThrillhouseConfig config) {
    this(
        config.review().autoReviewMinInterval().toMillis(),
        SWEEP_THRESHOLD,
        System::currentTimeMillis);
  }

  /** Visible for tests: allows controlling the interval, clock, and sweep threshold. */
  AutoReviewRateLimiter(long intervalMillis, int sweepThreshold, LongSupplier clock) {
    this.intervalMillis = intervalMillis;
    this.sweepThreshold = Math.max(1, sweepThreshold);
    this.clock = clock;
  }

  /**
   * @return {@code true} if an automatic review of this PR completed within the configured
   *     interval, so the caller should skip dispatching a new automatic review. Always {@code
   *     false} when the interval is zero or negative (throttling disabled).
   */
  public boolean isThrottled(String owner, String repo, int prNumber) {
    if (intervalMillis <= 0) {
      return false;
    }
    Long deadline = deadlines.get(key(owner, repo, prNumber));
    return deadline != null && deadline > clock.getAsLong();
  }

  /**
   * Lifts the throttle window for a draft marked ready ({@code ready_for_review}), once per head:
   * that transition should start a review even when a recent {@code synchronize} fell inside the
   * interval, but only the first time for a given head.
   *
   * @return {@code true} when the window was lifted, so the caller dispatches without asking {@link
   *     #isThrottled}; {@code false} when this head already had its window lifted, the head is
   *     unknown, or throttling is disabled, in which case the caller asks {@link #isThrottled} as
   *     for any other event
   */
  public boolean clearForReadyHead(String owner, String repo, int prNumber, String headSha) {
    if (intervalMillis <= 0 || headSha == null || headSha.isBlank()) {
      return false;
    }
    var key = key(owner, repo, prNumber);
    long now = clock.getAsLong();
    var lifted = new boolean[1];
    readyHeads.compute(
        key,
        (ignored, previous) -> {
          if (previous != null && previous.sha().equalsIgnoreCase(headSha)) {
            return previous;
          }
          lifted[0] = true;
          return new ReadyHead(headSha, now);
        });
    if (lifted[0]) {
      deadlines.remove(key);
      sweepExpired(now);
    }
    return lifted[0];
  }

  /**
   * Records that an automatic review of this PR just completed, starting a fresh throttle window.
   * Callers must not record manual reviews — an explicit {@code /review} should not delay the next
   * automatic review. No-op when throttling is disabled.
   */
  public void recordCompletion(String owner, String repo, int prNumber) {
    if (intervalMillis <= 0) {
      return;
    }
    long now = clock.getAsLong();
    deadlines.put(key(owner, repo, prNumber), now + intervalMillis);
    sweepExpired(now);
  }

  /**
   * {@link #recordCompletion} refreshes a PR's deadline but never shrinks the map, so PRs that stop
   * receiving pushes would accumulate forever. Sweep expired entries once a map is large enough
   * that the scan is worthwhile; live entries are never removed, so a throttled PR can never slip
   * through.
   */
  void sweepExpired(long now) {
    if (deadlines.size() >= sweepThreshold) {
      deadlines.values().removeIf(deadline -> deadline <= now);
    }
    if (readyHeads.size() >= sweepThreshold) {
      // A remembered head is dropped once the interval has passed since the lift and the PR has
      // no live deadline. Live entries stay, so a repeated ready-for-review cannot lift a window.
      readyHeads
          .entrySet()
          .removeIf(
              entry ->
                  entry.getValue().at() + intervalMillis <= now
                      && deadlines.getOrDefault(entry.getKey(), 0L) <= now);
    }
  }

  private static String key(String owner, String repo, int prNumber) {
    return owner + "/" + repo + "#" + prNumber;
  }
}
