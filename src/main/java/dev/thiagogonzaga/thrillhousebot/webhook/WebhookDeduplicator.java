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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Remembers recently processed {@code X-GitHub-Delivery} ids so redelivered webhooks — manual
 * redelivery from the GitHub App's Advanced settings, or GitHub's automatic retries — are dropped
 * instead of starting a second review.
 *
 * <p>Two layers. The in-memory map is the fast path and the in-process arbiter: each sighting
 * records an expiry {@code ttlMillis} into the future via a single atomic {@code put}, and a
 * sighting is a duplicate while an earlier, unexpired record already exists. A sighting the map
 * reads as new then claims the id in the database ({@link SeenDeliveries}, #1374), which is what
 * keeps the protection across a restart and across instances sharing the database: the first
 * instance to insert the id handles the delivery, and every other sighting within the window is a
 * duplicate. The map keeps only the ids this process claimed, so the map and the table agree; a
 * {@link #forget} after a failed dispatch clears both.
 *
 * <p>A database failure fails open to the map's verdict and is logged, so an outage of the database
 * does not stop webhooks. An id longer than the table's column is kept in memory only.
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
  private final SeenDeliveries stored;

  @Inject
  public WebhookDeduplicator(ThrillhouseConfig config, SeenDeliveries stored) {
    this(
        config.webhook().dedupTtl().toMillis(), SWEEP_THRESHOLD, System::currentTimeMillis, stored);
  }

  /** Visible for tests: allows controlling the dedup clock and sweep threshold. */
  WebhookDeduplicator(
      long ttlMillis, int sweepThreshold, LongSupplier clock, SeenDeliveries stored) {
    this.ttlMillis = ttlMillis;
    this.sweepThreshold = Math.max(1, sweepThreshold);
    this.clock = clock;
    this.stored = stored;
  }

  /**
   * Records {@code deliveryId} as processed and reports whether an unexpired record already
   * existed, in this process or in the database. The in-process record-and-read is a single atomic
   * {@code put}, so concurrent sightings of the same id in one process yield at most one database
   * claim, and the database's insert lets exactly one instance claim it.
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
    long deadline = now + ttlMillis;
    Long previousDeadline = seen.put(deliveryId, deadline);
    if (previousDeadline != null && previousDeadline > now) {
      return true;
    }
    sweepExpired(now);
    if (claimed(deliveryId, now, deadline)) {
      return false;
    }
    // Another process or an earlier run claimed it: keep only this process's claims in memory.
    seen.remove(deliveryId, deadline);
    return true;
  }

  /** Whether the database lets this process handle the delivery; open on a database failure. */
  private boolean claimed(String deliveryId, long now, long deadline) {
    if (deliveryId.length() > SeenDeliveries.ID_LENGTH) {
      return true;
    }
    try {
      return stored.claim(deliveryId, Instant.ofEpochMilli(now), Instant.ofEpochMilli(deadline));
    } catch (RuntimeException e) {
      if (SeenDeliveries.isUniqueViolation(e)) {
        return false;
      }
      Log.warnf(
          "Could not record webhook delivery %s in the database; deduplicating it in memory only:"
              + " %s",
          LogSafe.oneLine(deliveryId), LogSafe.oneLine(e.toString()));
      return true;
    }
  }

  /**
   * Removes the dedup record for {@code deliveryId}, in memory and in the database, allowing a
   * future delivery with the same id to be processed. Called when dispatch fails so that manual
   * redelivery can retry. Never throws: a failed database delete is logged.
   */
  public void forget(String deliveryId) {
    if (deliveryId == null) {
      return;
    }
    seen.remove(deliveryId);
    if (deliveryId.isBlank() || deliveryId.length() > SeenDeliveries.ID_LENGTH) {
      return;
    }
    try {
      stored.release(deliveryId);
    } catch (RuntimeException e) {
      Log.warnf(
          "Could not delete webhook delivery %s from the database; a redelivery of it is dropped"
              + " until it expires: %s",
          LogSafe.oneLine(deliveryId), LogSafe.oneLine(e.toString()));
    }
  }

  /**
   * {@link #isDuplicate} refreshes an existing key's deadline but never shrinks the map, so ids
   * that are never redelivered would accumulate forever. Sweep expired entries once the map is
   * large enough that the scan is worthwhile; live entries are never removed, so a still-remembered
   * redelivery can never be reprocessed. The database rows are purged by {@link DeliveryPurge}.
   */
  void sweepExpired(long now) {
    if (seen.size() < sweepThreshold) {
      return;
    }
    seen.values().removeIf(deadline -> deadline <= now);
  }
}
