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
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Throttles automatic (webhook-triggered) reviews per PR: while the last automatic review of a PR
 * completed less than {@code thrillhousebot.review.auto-review-min-interval} ago, new {@code
 * pull_request} events for it do not start a review — even when the head SHA changed — so noisy
 * repositories that push frequently do not burn AI spend and GitHub API budget on every commit. An
 * event for the head that review covered is skipped. One for another head is kept to be reviewed
 * when the window closes ({@link #hold}, {@link DeferredReviews}), so a push inside the window is
 * reviewed late instead of skipped, unless {@link DeferredReviews} cannot keep it or loses it.
 *
 * <p>Only the automatic path consults this limiter; a manual {@code /review} (or {@code
 * @thrillhousebot review}) never checks it and never records into it, so explicit triggers always
 * run and do not shift the automatic window. State is in-memory per replica, like {@link
 * ReviewDispatcher}.
 *
 * <p>Marking a draft ready lifts the window once for a head: a second {@code ready_for_review} of
 * the same head is throttled like any other event, so converting a pull request to draft and back
 * cannot start one review after another. The last {@value #MAX_READY_HEADS} such heads of a pull
 * request are remembered, so moving the branch between a few commits lifts nothing either.
 */
@ApplicationScoped
public class AutoReviewRateLimiter {

  /** Sweep expired PRs once the map reaches this size, mirroring the other in-memory TTL caches. */
  static final int SWEEP_THRESHOLD = 10_000;

  /** How many heads a ready-for-review lifted the window for are remembered per PR. */
  static final int MAX_READY_HEADS = 8;

  /** PR key → the window its last automatic review opened. */
  final ConcurrentHashMap<String, Window> deadlines = new ConcurrentHashMap<>();

  /** PR key → the heads a ready-for-review lifted the window for, and when the last one did. */
  final ConcurrentHashMap<String, ReadyHeads> readyHeads = new ConcurrentHashMap<>();

  /**
   * The epoch-millis deadline until which automatic reviews of a PR are throttled, and the head the
   * review that opened the window covered; {@code null} once {@link #forgetCoveredHead} dropped it.
   */
  record Window(long deadline, String sha) {}

  /**
   * The heads a ready-for-review lifted the window for, oldest first and at most {@link
   * #MAX_READY_HEADS}, and the epoch millis of the last lift.
   */
  record ReadyHeads(List<String> shas, long at) {

    boolean contains(String headSha) {
      return shas.stream().anyMatch(headSha::equalsIgnoreCase);
    }

    /** These heads and {@code headSha}, without the oldest once there are too many. */
    ReadyHeads with(String headSha, long now) {
      var next = new ArrayList<>(shas);
      if (next.size() >= MAX_READY_HEADS) {
        next.removeFirst();
      }
      next.add(headSha);
      return new ReadyHeads(List.copyOf(next), now);
    }
  }

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
   * What the window says about one event.
   *
   * @param throttled whether an automatic review of the PR completed within the configured
   *     interval, so no automatic review starts now
   * @param reviewDueAt the epoch millis the window closes at, when the event is throttled and its
   *     head is not the one that review covered, so its review is due then; empty when the event is
   *     not throttled, when its head is unknown, and when that review already covered the head, in
   *     which case the event needs no review at all
   */
  public record Hold(boolean throttled, OptionalLong reviewDueAt) {

    /** The window does not hold the event back. */
    static final Hold NONE = new Hold(false, OptionalLong.empty());

    /** The window holds the event back and its head needs no review. */
    static final Hold SKIP = new Hold(true, OptionalLong.empty());
  }

  /**
   * Whether the window holds an event for {@code headSha} back, and until when. The clock and the
   * window are each read once, so an event that arrives as the window closes is either held with a
   * time to review it at or not held at all. Never throttled when the interval is zero or negative
   * (throttling disabled).
   */
  public Hold hold(String owner, String repo, int prNumber, String headSha) {
    if (intervalMillis <= 0) {
      return Hold.NONE;
    }
    var window = deadlines.get(key(owner, repo, prNumber));
    if (window == null || window.deadline() <= clock.getAsLong()) {
      return Hold.NONE;
    }
    if (headSha == null || headSha.isBlank() || headSha.equalsIgnoreCase(window.sha())) {
      return Hold.SKIP;
    }
    return new Hold(true, OptionalLong.of(window.deadline()));
  }

  private boolean windowOpen(String key, long now) {
    var window = deadlines.get(key);
    return window != null && window.deadline() > now;
  }

  /**
   * Forgets which head the PR's last automatic review covered, leaving its window as it is. Called
   * when the approval that review gave is withdrawn on a push ({@link ApprovalOnPush}): if the head
   * then returns to that commit inside the window, the event is no longer one that "needs no review
   * at all", so {@link #hold} keeps it for the window's end and the commit is reviewed, and can be
   * approved, again. The heads a ready-for-review lifted the window for are not touched.
   */
  public void forgetCoveredHead(String owner, String repo, int prNumber) {
    deadlines.computeIfPresent(
        key(owner, repo, prNumber), (ignored, window) -> new Window(window.deadline(), null));
  }

  /**
   * Lifts the throttle window for a draft marked ready ({@code ready_for_review}), once per head:
   * that transition should start a review even when a recent {@code synchronize} fell inside the
   * interval, but only the first time for a given head.
   *
   * @return {@code true} when the window was lifted, so the caller dispatches without asking {@link
   *     #hold}; {@code false} when this head already had its window lifted, the head is unknown, or
   *     throttling is disabled, in which case the caller asks {@link #hold} as for any other event
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
          if (previous != null && previous.contains(headSha)) {
            return previous;
          }
          lifted[0] = true;
          return previous == null
              ? new ReadyHeads(List.of(headSha), now)
              : previous.with(headSha, now);
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
   *
   * @param headSha the head the review covered; an event for another head inside the window is held
   *     back with the time the window closes at, for a review then
   */
  public void recordCompletion(String owner, String repo, int prNumber, String headSha) {
    if (intervalMillis <= 0) {
      return;
    }
    long now = clock.getAsLong();
    deadlines.put(key(owner, repo, prNumber), new Window(now + intervalMillis, headSha));
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
      deadlines.values().removeIf(window -> window.deadline() <= now);
    }
    if (readyHeads.size() >= sweepThreshold) {
      // A PR's remembered heads are dropped once the interval has passed since the last lift and
      // the PR has no live deadline. Live entries stay, so a repeated ready-for-review cannot
      // lift a window.
      readyHeads
          .entrySet()
          .removeIf(
              entry ->
                  entry.getValue().at() + intervalMillis <= now
                      && !windowOpen(entry.getKey(), now));
    }
  }

  private static String key(String owner, String repo, int prNumber) {
    return owner + "/" + repo + "#" + prNumber;
  }
}
