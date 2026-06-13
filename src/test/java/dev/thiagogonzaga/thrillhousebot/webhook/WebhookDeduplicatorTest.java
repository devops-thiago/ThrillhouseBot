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
package dev.thiagogonzaga.thrillhousebot.webhook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class WebhookDeduplicatorTest {

  private static final long TTL_MS = 60_000L;
  private static final int BIG_THRESHOLD = 10_000;

  @Test
  void injectionConstructorReadsTtlFromConfig() {
    var config = mock(ThrillhouseConfig.class);
    var webhookConfig = mock(ThrillhouseConfig.WebhookConfig.class);
    when(config.webhook()).thenReturn(webhookConfig);
    when(webhookConfig.dedupTtl()).thenReturn(Duration.ofHours(24));

    var dedup = new WebhookDeduplicator(config);

    // The config-wired bean behaves like any other: a first sighting is new, an immediate repeat
    // (well within the 24h TTL) is a duplicate.
    assertFalse(dedup.isDuplicate("delivery-1"));
    assertTrue(dedup.isDuplicate("delivery-1"));
  }

  @Test
  void firstDeliveryIsNotDuplicate() {
    var dedup = new WebhookDeduplicator(TTL_MS, BIG_THRESHOLD, new MutableClock(0));

    assertFalse(dedup.isDuplicate("delivery-1"));
  }

  @Test
  void repeatDeliveryWithinTtlIsDuplicate() {
    var dedup = new WebhookDeduplicator(TTL_MS, BIG_THRESHOLD, new MutableClock(0));

    assertFalse(dedup.isDuplicate("delivery-1"));
    assertTrue(dedup.isDuplicate("delivery-1"));
    assertTrue(dedup.isDuplicate("delivery-1"));
  }

  @Test
  void distinctDeliveriesAreIndependent() {
    var dedup = new WebhookDeduplicator(TTL_MS, BIG_THRESHOLD, new MutableClock(0));

    assertFalse(dedup.isDuplicate("a"));
    assertFalse(dedup.isDuplicate("b"));
    assertTrue(dedup.isDuplicate("a"));
    assertTrue(dedup.isDuplicate("b"));
  }

  @Test
  void deliveryIsForgottenAfterTtlExpires() {
    var clock = new MutableClock(0);
    var dedup = new WebhookDeduplicator(TTL_MS, BIG_THRESHOLD, clock);

    assertFalse(dedup.isDuplicate("delivery-1"));
    assertTrue(dedup.isDuplicate("delivery-1"));

    // Step past the TTL: the prior record has lapsed and the next sighting is first-seen again...
    clock.advance(TTL_MS + 1);
    assertFalse(dedup.isDuplicate("delivery-1"));
    // ...and is remembered again from that point.
    assertTrue(dedup.isDuplicate("delivery-1"));
  }

  @Test
  void deliveryStillDeduplicatedJustBeforeTtl() {
    var clock = new MutableClock(0);
    var dedup = new WebhookDeduplicator(TTL_MS, BIG_THRESHOLD, clock);

    assertFalse(dedup.isDuplicate("delivery-1"));
    // One tick before the deadline the record is still live.
    clock.advance(TTL_MS - 1);
    assertTrue(dedup.isDuplicate("delivery-1"));
  }

  @Test
  void deliveryExpiresExactlyAtTtlBoundary() {
    var clock = new MutableClock(0);
    var dedup = new WebhookDeduplicator(TTL_MS, BIG_THRESHOLD, clock);

    assertFalse(dedup.isDuplicate("delivery-1"));
    // At exactly the deadline the record has expired (deadline is the first lapsed instant), so the
    // sighting is treated as first-seen — this pins the `previousDeadline > now` boundary.
    clock.advance(TTL_MS);
    assertFalse(dedup.isDuplicate("delivery-1"));
  }

  @Test
  void nullOrBlankDeliveryIdFailsOpen() {
    var dedup = new WebhookDeduplicator(TTL_MS, BIG_THRESHOLD, new MutableClock(0));

    // Missing/blank ids are never deduped (and never stored), so they always process.
    assertFalse(dedup.isDuplicate(null));
    assertFalse(dedup.isDuplicate(null));
    assertFalse(dedup.isDuplicate(""));
    assertFalse(dedup.isDuplicate("   "));
    assertEquals(0, dedup.seen.size());
  }

  @Test
  void expiredEntriesAreReclaimedOnceThresholdReached() {
    var clock = new MutableClock(0);
    int threshold = 4;
    var dedup = new WebhookDeduplicator(TTL_MS, threshold, clock);

    // Fill exactly to the sweep threshold; nothing has expired yet so nothing is reclaimed.
    for (int i = 0; i < threshold; i++) {
      dedup.isDuplicate("old-" + i);
    }
    assertEquals(threshold, dedup.seen.size());

    // Let every recorded id lapse, then record one more id: that sighting crosses the threshold and
    // triggers the sweep, which must reclaim all four expired ids and leave only the new one.
    clock.advance(TTL_MS + 1);
    dedup.isDuplicate("new-0");

    // Fails if the expired sweep is removed — the 4 stale ids would still be in the map.
    assertEquals(1, dedup.seen.size());
  }

  @Test
  void liveEntriesAreNeverSweptAndStayDeduplicated() {
    var clock = new MutableClock(0);
    int threshold = 10;
    var dedup = new WebhookDeduplicator(TTL_MS, threshold, clock);

    // Record far more than the threshold of distinct, still-live ids within the TTL window.
    int count = threshold * 3;
    for (int i = 0; i < count; i++) {
      assertFalse(dedup.isDuplicate("id-" + i));
    }

    // Sweep-on-expiry set, not a hard cap: live ids are retained past the threshold (no forced
    // eviction), so a redelivery of any of them is still recognised as a duplicate.
    assertEquals(count, dedup.seen.size());
    for (int i = 0; i < count; i++) {
      assertTrue(dedup.isDuplicate("id-" + i), "live id-" + i + " was forgotten");
    }
  }

  @Test
  void concurrentFirstSightingOfSameDeliveryHappensExactlyOnce() throws InterruptedException {
    var dedup = new WebhookDeduplicator(TTL_MS, BIG_THRESHOLD, new MutableClock(0));

    // Exactly one of many threads may observe a brand-new id as new; the rest see a duplicate.
    assertEquals(1, raceFirstSightings(dedup, "racing-id", 32));
  }

  @Test
  void concurrentRedeliveryOfExpiredIdReprocessesExactlyOnce() throws InterruptedException {
    var clock = new MutableClock(0);
    var dedup = new WebhookDeduplicator(TTL_MS, BIG_THRESHOLD, clock);

    dedup.isDuplicate("id"); // record it...
    clock.advance(TTL_MS + 1); // ...then let it expire.

    // Many threads redeliver the expired id at once: exactly one may reprocess it, never two.
    assertEquals(1, raceFirstSightings(dedup, "id", 32));
  }

  /**
   * Runs {@code threads} concurrent {@code isDuplicate(id)} calls and returns how many observed the
   * id as new. Any throwable from a worker is surfaced as a test failure rather than silently
   * skewing the count.
   */
  private long raceFirstSightings(WebhookDeduplicator dedup, String id, int threads)
      throws InterruptedException {
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(threads);
    var firstSightings = new AtomicLong(0);
    var error = new AtomicReference<Throwable>();

    for (int t = 0; t < threads; t++) {
      Thread thread =
          new Thread(
              () -> {
                try {
                  start.await();
                  if (!dedup.isDuplicate(id)) {
                    firstSightings.incrementAndGet();
                  }
                } catch (InterruptedException _) {
                  Thread.currentThread().interrupt();
                } catch (Throwable t2) {
                  error.compareAndSet(null, t2);
                } finally {
                  done.countDown();
                }
              });
      thread.start();
    }
    start.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS), "threads did not finish");
    if (error.get() != null) {
      throw new AssertionError("isDuplicate threw on a worker thread", error.get());
    }
    return firstSightings.get();
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
