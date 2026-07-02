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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class AutoReviewRateLimiterTest {

  private static final long INTERVAL_MS = 3_600_000L;
  private static final int BIG_THRESHOLD = 10_000;

  @Test
  void injectionConstructorReadsIntervalFromConfig() {
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.autoReviewMinInterval()).thenReturn(Duration.ofHours(1));

    var limiter = new AutoReviewRateLimiter(config);

    // The config-wired bean behaves like any other: throttled right after a completion (well
    // within the 1h interval), open before any completion was recorded.
    assertFalse(limiter.isThrottled("owner", "repo", 1));
    limiter.recordCompletion("owner", "repo", 1);
    assertTrue(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void prWithoutRecordedReviewIsNotThrottled() {
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, new MutableClock(0));

    assertFalse(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void prIsThrottledWithinIntervalEvenAcrossRepeatedChecks() {
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, new MutableClock(0));

    limiter.recordCompletion("owner", "repo", 1);

    // Unlike the webhook deduplicator, checking never extends the window — only completions do.
    assertTrue(limiter.isThrottled("owner", "repo", 1));
    assertTrue(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void distinctPrsAreThrottledIndependently() {
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, new MutableClock(0));

    limiter.recordCompletion("owner", "repo", 1);

    assertTrue(limiter.isThrottled("owner", "repo", 1));
    assertFalse(limiter.isThrottled("owner", "repo", 2));
    assertFalse(limiter.isThrottled("owner", "other-repo", 1));
    assertFalse(limiter.isThrottled("other-owner", "repo", 1));
  }

  @Test
  void prOpensAgainAfterIntervalElapses() {
    var clock = new MutableClock(0);
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, clock);

    limiter.recordCompletion("owner", "repo", 1);
    assertTrue(limiter.isThrottled("owner", "repo", 1));

    // Step past the interval: the window has lapsed and the next event may review again.
    clock.advance(INTERVAL_MS + 1);
    assertFalse(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void prStillThrottledJustBeforeIntervalElapses() {
    var clock = new MutableClock(0);
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, clock);

    limiter.recordCompletion("owner", "repo", 1);
    // One tick before the deadline the window is still closed.
    clock.advance(INTERVAL_MS - 1);
    assertTrue(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void prOpensExactlyAtIntervalBoundary() {
    var clock = new MutableClock(0);
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, clock);

    limiter.recordCompletion("owner", "repo", 1);
    // At exactly the deadline the window has lapsed (the deadline is the first open instant),
    // pinning the `deadline > now` boundary.
    clock.advance(INTERVAL_MS);
    assertFalse(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void newCompletionRestartsTheWindow() {
    var clock = new MutableClock(0);
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, clock);

    limiter.recordCompletion("owner", "repo", 1);
    clock.advance(INTERVAL_MS + 1);
    assertFalse(limiter.isThrottled("owner", "repo", 1));

    // The next completed review starts a fresh full-length window from its completion time.
    limiter.recordCompletion("owner", "repo", 1);
    clock.advance(INTERVAL_MS - 1);
    assertTrue(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void zeroIntervalDisablesThrottlingAndRecordsNothing() {
    var limiter = new AutoReviewRateLimiter(0, BIG_THRESHOLD, new MutableClock(0));

    limiter.recordCompletion("owner", "repo", 1);

    // Interval 0 keeps the pre-#328 behavior: every event reviews, and no state accumulates.
    assertFalse(limiter.isThrottled("owner", "repo", 1));
    assertEquals(0, limiter.deadlines.size());
  }

  @Test
  void negativeIntervalDisablesThrottlingAndRecordsNothing() {
    var limiter = new AutoReviewRateLimiter(-1, BIG_THRESHOLD, new MutableClock(0));

    limiter.recordCompletion("owner", "repo", 1);

    assertFalse(limiter.isThrottled("owner", "repo", 1));
    assertEquals(0, limiter.deadlines.size());
  }

  @Test
  void expiredEntriesAreReclaimedOnceThresholdReached() {
    var clock = new MutableClock(0);
    int threshold = 4;
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, threshold, clock);

    // Fill exactly to the sweep threshold; nothing has expired yet so nothing is reclaimed.
    for (int i = 0; i < threshold; i++) {
      limiter.recordCompletion("owner", "repo", i);
    }
    assertEquals(threshold, limiter.deadlines.size());

    // Let every window lapse, then record one more completion: that record crosses the threshold
    // and triggers the sweep, which must reclaim all four expired PRs and leave only the new one.
    clock.advance(INTERVAL_MS + 1);
    limiter.recordCompletion("owner", "repo", 100);

    // Fails if the expired sweep is removed — the 4 stale PRs would still be in the map.
    assertEquals(1, limiter.deadlines.size());
  }

  @Test
  void liveEntriesAreNeverSweptAndStayThrottled() {
    var clock = new MutableClock(0);
    int threshold = 10;
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, threshold, clock);

    // Record far more than the threshold of distinct, still-live PRs within the interval.
    int count = threshold * 3;
    for (int i = 0; i < count; i++) {
      limiter.recordCompletion("owner", "repo", i);
    }

    // Sweep-on-expiry set, not a hard cap: live PRs are retained past the threshold (no forced
    // eviction), so every one of them stays throttled.
    assertEquals(count, limiter.deadlines.size());
    for (int i = 0; i < count; i++) {
      assertTrue(limiter.isThrottled("owner", "repo", i), "live PR #" + i + " was forgotten");
    }
  }

  /** A {@link LongSupplier} clock whose epoch-millis value can be advanced by tests. */
  private static final class MutableClock implements LongSupplier {
    private final AtomicLong millis;

    MutableClock(long startMillis) {
      this.millis = new AtomicLong(startMillis);
    }

    void advance(long byMillis) {
      millis.addAndGet(byMillis);
    }

    @Override
    public long getAsLong() {
      return millis.get();
    }
  }
}
