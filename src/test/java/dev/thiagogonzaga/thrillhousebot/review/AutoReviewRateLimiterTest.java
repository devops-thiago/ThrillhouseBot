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

    clock.advance(INTERVAL_MS + 1);
    assertFalse(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void prStillThrottledJustBeforeIntervalElapses() {
    var clock = new MutableClock(0);
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, clock);

    limiter.recordCompletion("owner", "repo", 1);
    clock.advance(INTERVAL_MS - 1);
    assertTrue(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void prOpensExactlyAtIntervalBoundary() {
    var clock = new MutableClock(0);
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, clock);

    limiter.recordCompletion("owner", "repo", 1);
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

    limiter.recordCompletion("owner", "repo", 1);
    clock.advance(INTERVAL_MS - 1);
    assertTrue(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void zeroIntervalDisablesThrottlingAndRecordsNothing() {
    var limiter = new AutoReviewRateLimiter(0, BIG_THRESHOLD, new MutableClock(0));

    limiter.recordCompletion("owner", "repo", 1);

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

    for (int i = 0; i < threshold; i++) {
      limiter.recordCompletion("owner", "repo", i);
    }
    assertEquals(threshold, limiter.deadlines.size());

    clock.advance(INTERVAL_MS + 1);
    limiter.recordCompletion("owner", "repo", 100);

    assertEquals(1, limiter.deadlines.size());
  }

  @Test
  void liveEntriesAreNeverSweptAndStayThrottled() {
    var clock = new MutableClock(0);
    int threshold = 10;
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, threshold, clock);

    int count = threshold * 3;
    for (int i = 0; i < count; i++) {
      limiter.recordCompletion("owner", "repo", i);
    }

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
