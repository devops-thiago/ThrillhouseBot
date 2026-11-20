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
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Deletes the expired webhook delivery ids (#1374) at startup and then every {@link #INTERVAL}, at
 * most {@link #BATCH} rows per transaction and {@link #MAX_BATCHES} transactions per run, so one
 * run stays short however far behind it is and the table stays bounded by the deliveries of one
 * dedup window. A failed run is logged and the next one carries on.
 */
@ApplicationScoped
public class DeliveryPurge {

  /** How often the purge runs. */
  static final Duration INTERVAL = Duration.ofHours(1);

  /** Rows deleted per transaction. */
  static final int BATCH = 1_000;

  /** Transactions per run. */
  static final int MAX_BATCHES = 20;

  private final SeenDeliveries deliveries;
  private final Vertx vertx;
  private final Supplier<Instant> clock;
  private final Executor purger;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile long timerId = -1;

  @Inject
  public DeliveryPurge(SeenDeliveries deliveries, Vertx vertx) {
    this(
        deliveries,
        vertx,
        Instant::now,
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("delivery-purge-", 0).factory()));
  }

  /** Visible for tests: a controllable clock, and where the purge runs. */
  DeliveryPurge(SeenDeliveries deliveries, Vertx vertx, Supplier<Instant> clock, Executor purger) {
    this.deliveries = deliveries;
    this.vertx = vertx;
    this.clock = clock;
    this.purger = purger;
  }

  void onStart(@Observes StartupEvent event) {
    timerId = vertx.setPeriodic(INTERVAL.toMillis(), id -> schedule());
    schedule();
  }

  void onStop(@Observes ShutdownEvent event) {
    if (timerId >= 0) {
      vertx.cancelTimer(timerId);
    }
    if (purger instanceof ExecutorService service) {
      service.shutdownNow();
    }
  }

  /** Runs the purge off the event loop, where the timer fires; skipped while one is running. */
  void schedule() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      purger.execute(
          () -> {
            try {
              purge();
            } finally {
              running.set(false);
            }
          });
    } catch (RuntimeException e) {
      running.set(false);
      Log.warn("Could not schedule the webhook delivery purge", e);
    }
  }

  /** Whether a purge is running. Visible for tests. */
  boolean running() {
    return running.get();
  }

  /**
   * Deletes expired rows, a batch at a time, until a batch comes back short or the run's batches
   * are used up.
   *
   * @return how many rows it deleted
   */
  int purge() {
    var now = clock.get();
    var total = 0;
    try {
      for (var batch = 0; batch < MAX_BATCHES; batch++) {
        var deleted = deliveries.purgeExpired(now, BATCH);
        total += deleted;
        if (deleted < BATCH) {
          break;
        }
      }
    } catch (RuntimeException e) {
      Log.warnf("The webhook delivery purge failed: %s", LogSafe.oneLine(e.toString()));
    }
    if (total > 0) {
      Log.infof("Deleted %d expired webhook delivery id(s)", total);
    }
    return total;
  }
}
