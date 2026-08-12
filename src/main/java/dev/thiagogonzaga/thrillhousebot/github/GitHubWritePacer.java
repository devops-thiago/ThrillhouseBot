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
package dev.thiagogonzaga.thrillhousebot.github;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the bot's content-creating GitHub calls inside the secondary-rate-limit envelope instead of
 * discovering it by rejection.
 *
 * <p>#579: {@link GitHubWriteRetry} makes a throttled post survivable, but it is reactive — it only
 * acts once GitHub has already refused. In the run behind #568, 56 commands across 8 PRs created
 * content faster than GitHub would accept it and drew 29 rejections. Spacing the requests out
 * instead is strictly better: no wasted round trip, no retry budget spent, and no dispatcher slot
 * held while a refused call backs off.
 *
 * <p>The bot reaches this state without anyone doing anything unusual — a review posting many
 * inline findings, or several PRs reviewed concurrently, both create comments in rapid succession —
 * so the limiter is process-wide and shared by every content-creating call, not per review and not
 * per PR. It sits on the same seam as the backoff ({@link GitHubWriteRetry#call}), which is exactly
 * the set of calls GitHub counts as content creation: comments, review comments, thread replies and
 * reviews.
 *
 * <h2>How a slot is claimed</h2>
 *
 * Each caller atomically claims the next free instant and advances the shared cursor by {@link
 * #MIN_INTERVAL_KEY}, then sleeps until the instant it claimed. Claiming is a single atomic update
 * with no lock held across the HTTP call, so a slow request never blocks the queue behind it, and
 * callers go out in the order they arrived rather than in a thundering herd when the interval
 * elapses. GitHub's published guidance — no more than one content-creating request per second — is
 * the default envelope, and both numbers are knobs rather than constants:
 *
 * <ul>
 *   <li>{@value #MIN_INTERVAL_KEY} (default {@code 1s}): spacing between two content-creating
 *       requests. Zero or negative disables pacing entirely.
 *   <li>{@value #MAX_WAIT_KEY} (default {@code 60s}): ceiling on how long one caller waits for its
 *       slot, so a queue that has grown past the ceiling degrades to the {@link GitHubWriteRetry}
 *       backoff — which is where the bot was before #579 — rather than parking a command for
 *       minutes while it holds its per-PR dispatcher slot.
 * </ul>
 *
 * <p>Waiting never costs the payload: a pacing wait that is interrupted proceeds with the call
 * rather than failing it, because the content on its way out has already been paid for and the
 * worst this limiter should ever do is let a burst through.
 */
public final class GitHubWritePacer {

  private static final Logger log = LoggerFactory.getLogger(GitHubWritePacer.class);

  /** Spacing between two content-creating requests; zero or negative disables pacing. */
  public static final String MIN_INTERVAL_KEY = "thrillhousebot.github.write-min-interval";

  /** Ceiling on a single caller's wait for its slot. */
  public static final String MAX_WAIT_KEY = "thrillhousebot.github.write-max-wait";

  /** GitHub's published guidance: no more than one content-creating request per second. */
  static final Duration DEFAULT_MIN_INTERVAL = Duration.ofSeconds(1);

  /** Matches the total {@link GitHubWriteRetry} budget: no wait here is worse than a refusal. */
  static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(60);

  /** Parks the current thread; the seam that lets the tests run the pacing without waiting. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration delay) throws InterruptedException;
  }

  /** Real waiting, on the current thread — a virtual one wherever the bot does its work. */
  private static final Sleeper PARK = delay -> Thread.sleep(delay.toMillis());

  /**
   * The instance production uses. The knobs are read once, when the first GitHub write loads this
   * class, so pacing costs one atomic update per call and no config lookup.
   */
  static final GitHubWritePacer DEFAULT =
      new GitHubWritePacer(
          configured(MIN_INTERVAL_KEY, DEFAULT_MIN_INTERVAL),
          configured(MAX_WAIT_KEY, DEFAULT_MAX_WAIT),
          PARK,
          System::nanoTime);

  /** No pacing at all, for the unit tests that pin the backoff rather than the spacing. */
  static final GitHubWritePacer NONE =
      new GitHubWritePacer(Duration.ZERO, Duration.ZERO, PARK, System::nanoTime);

  private final long intervalNanos;
  private final long maxWaitNanos;
  private final Sleeper sleeper;
  private final LongSupplier nanoClock;
  private final AtomicLong nextSlot;

  GitHubWritePacer(
      Duration minInterval, Duration maxWait, Sleeper sleeper, LongSupplier nanoClock) {
    this.intervalNanos = minInterval.toNanos();
    this.maxWaitNanos = Math.max(0L, maxWait.toNanos());
    this.sleeper = sleeper;
    this.nanoClock = nanoClock;
    this.nextSlot = new AtomicLong(nanoClock.getAsLong());
  }

  /** The configured duration for {@code key}, or {@code fallback} when the key is not set. */
  static Duration configured(String key, Duration fallback) {
    return ConfigProvider.getConfig().getOptionalValue(key, Duration.class).orElse(fallback);
  }

  /**
   * Waits, if it has to, until this call's turn to create content on GitHub. Returns immediately
   * when pacing is disabled or the queue is empty, so a lone comment on a quiet repo is not slowed
   * down at all — the wait only appears once calls are actually arriving faster than the envelope.
   *
   * @param operation what is being posted, for the log — never credentials or comment text
   */
  public void acquire(String operation) {
    if (intervalNanos <= 0) {
      return;
    }
    long now = nanoClock.getAsLong();
    // The claimed instant is the later of "the cursor" and "now"; the cursor then moves one
    // interval past it, so the next caller queues behind this one instead of racing it.
    long slot = nextSlot.accumulateAndGet(now, this::claim) - intervalNanos;
    long waitNanos = slot - now;
    if (waitNanos <= 0) {
      return;
    }
    var wait = Duration.ofNanos(Math.min(waitNanos, maxWaitNanos));
    log.debug("Pacing {} — waiting {}ms for a content-creation slot", operation, wait.toMillis());
    try {
      sleeper.sleep(wait);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      // Never lose the payload to the limiter: the call goes out and the backoff covers a refusal.
      log.debug("Interrupted while pacing {} — sending it without waiting", operation);
    }
  }

  /** Subtraction rather than {@code >} so the comparison survives a {@code nanoTime} wraparound. */
  private long claim(long cursor, long now) {
    return (cursor - now > 0 ? cursor : now) + intervalNanos;
  }
}
