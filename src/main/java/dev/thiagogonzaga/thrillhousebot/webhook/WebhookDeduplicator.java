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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Remembers recently processed {@code X-GitHub-Delivery} ids so redelivered webhooks — manual
 * redelivery from the GitHub App's Advanced settings, or GitHub's automatic retries — are dropped
 * instead of starting a second review.
 *
 * <p>Each sighting records an expiry {@code ttlMillis} into the future via a single atomic {@code
 * put}; a sighting is a duplicate only while an earlier, unexpired record already exists. State is
 * in-memory per replica, like {@link dev.thiagogonzaga.thrillhousebot.review.ReviewDispatcher}.
 */
@ApplicationScoped
public class WebhookDeduplicator {

  /** Sweep expired ids once the map reaches this size, mirroring the other in-memory TTL caches. */
  static final int SWEEP_THRESHOLD = 10_000;

  /** Delivery id → epoch-millis deadline after which the redelivery window has lapsed. */
  final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

  private final long ttlMillis;
  private final int sweepThreshold;
  private final LongSupplier clock;

  @Inject
  public WebhookDeduplicator(ThrillhouseConfig config) {
    this(config.webhook().dedupTtl().toMillis(), SWEEP_THRESHOLD, System::currentTimeMillis);
  }

  /** Visible for tests: allows controlling the dedup clock and sweep threshold. */
  WebhookDeduplicator(long ttlMillis, int sweepThreshold, LongSupplier clock) {
    this.ttlMillis = ttlMillis;
    this.sweepThreshold = Math.max(1, sweepThreshold);
    this.clock = clock;
  }

  /**
   * Records {@code deliveryId} as processed and reports whether an unexpired record already
   * existed. The record-and-read is a single atomic {@code put}, so concurrent sightings of the
   * same id yield exactly one "not duplicate".
   *
   * @return {@code true} if this delivery was already processed within the TTL (a redelivery to
   *     drop); {@code false} if it is newly recorded or its prior record had expired. A null or
   *     blank id is never treated as a duplicate, so deliveries missing the header fail open.
   */
  public boolean isDuplicate(String deliveryId) {
    if (deliveryId == null || deliveryId.isBlank()) {
      return false;
    }
    long now = clock.getAsLong();
    Long previousDeadline = seen.put(deliveryId, now + ttlMillis);
    boolean duplicate = previousDeadline != null && previousDeadline > now;
    if (!duplicate) {
      sweepExpired(now);
    }
    return duplicate;
  }

  /**
   * Removes the dedup record for {@code deliveryId}, allowing a future delivery with the same id to
   * be processed. Called when dispatch fails so that manual redelivery can retry.
   */
  public void forget(String deliveryId) {
    if (deliveryId != null) {
      seen.remove(deliveryId);
    }
  }

  /**
   * {@link #isDuplicate} refreshes an existing key's deadline but never shrinks the map, so ids
   * that are never redelivered would accumulate forever. Sweep expired entries once the map is
   * large enough that the scan is worthwhile; live entries are never removed, so a still-remembered
   * redelivery can never be reprocessed.
   */
  void sweepExpired(long now) {
    if (seen.size() < sweepThreshold) {
      return;
    }
    seen.values().removeIf(deadline -> deadline <= now);
  }
}
