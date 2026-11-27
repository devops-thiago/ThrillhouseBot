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
  void theFirstReadyForReviewOfAHeadLiftsTheWindow() {
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, new MutableClock(0));

    limiter.recordCompletion("owner", "repo", 1);
    assertTrue(limiter.isThrottled("owner", "repo", 1));

    assertTrue(limiter.clearForReadyHead("owner", "repo", 1, "sha-a"));
    assertFalse(limiter.isThrottled("owner", "repo", 1));
  }

  @Test
  void aRepeatedReadyForReviewOfTheSameHeadLeavesTheWindowInPlace() {
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, new MutableClock(0));
    assertTrue(limiter.clearForReadyHead("owner", "repo", 1, "sha-a"));
    limiter.recordCompletion("owner", "repo", 1);

    assertFalse(limiter.clearForReadyHead("owner", "repo", 1, "SHA-A"));
    assertTrue(limiter.isThrottled("owner", "repo", 1));

    // A new head gets its own lift, and so does the same head on another pull request.
    assertTrue(limiter.clearForReadyHead("owner", "repo", 1, "sha-b"));
    assertFalse(limiter.isThrottled("owner", "repo", 1));
    assertTrue(limiter.clearForReadyHead("owner", "repo", 2, "sha-a"));
  }

  @Test
  void aReadyForReviewWithNoHeadLiftsNothing() {
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, BIG_THRESHOLD, new MutableClock(0));
    limiter.recordCompletion("owner", "repo", 1);

    assertFalse(limiter.clearForReadyHead("owner", "repo", 1, null));
    assertFalse(limiter.clearForReadyHead("owner", "repo", 1, " "));
    assertTrue(limiter.isThrottled("owner", "repo", 1));
    assertEquals(0, limiter.readyHeads.size());
  }

  @Test
  void withThrottlingOffAReadyForReviewRemembersNothing() {
    var limiter = new AutoReviewRateLimiter(0, BIG_THRESHOLD, new MutableClock(0));

    assertFalse(limiter.clearForReadyHead("owner", "repo", 1, "sha-a"));
    assertFalse(limiter.isThrottled("owner", "repo", 1));
    assertEquals(0, limiter.readyHeads.size());
  }

  @Test
  void rememberedReadyHeadsAreReclaimedOnlyOnceNoWindowCanFollow() {
    var clock = new MutableClock(0);
    int threshold = 3;
    var limiter = new AutoReviewRateLimiter(INTERVAL_MS, threshold, clock);

    limiter.clearForReadyHead("owner", "repo", 1, "sha-1");
    limiter.clearForReadyHead("owner", "repo", 2, "sha-2");
    // PR 2's review completes late, so its window outlives the interval since its lift.
    clock.advance(INTERVAL_MS - 1);
    limiter.recordCompletion("owner", "repo", 2);
    clock.advance(2);
    limiter.clearForReadyHead("owner", "repo", 3, "sha-3");

    // PR 1 is past the interval with no window; PR 2 still has a window; PR 3 was just lifted.
    assertEquals(2, limiter.readyHeads.size());
    assertFalse(limiter.clearForReadyHead("owner", "repo", 2, "sha-2"));
    assertTrue(limiter.isThrottled("owner", "repo", 2));
    assertFalse(limiter.clearForReadyHead("owner", "repo", 3, "sha-3"));
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
