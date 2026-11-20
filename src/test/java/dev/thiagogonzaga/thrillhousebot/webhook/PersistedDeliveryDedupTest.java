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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Webhook delivery ids kept in the database (#1374): a redelivery after a restart is dropped, two
 * instances sharing the database handle a delivery once, a failed dispatch can be redelivered, and
 * expired rows are reclaimed and purged.
 */
@QuarkusTest
class PersistedDeliveryDedupTest {

  private static final long TTL_MS = 60_000L;
  private static final long START = 1_700_000_000_000L;

  @Inject SeenDeliveries deliveries;
  @Inject UserTransaction tx;

  private final AtomicLong clock = new AtomicLong(START);

  @AfterEach
  void cleanup() throws Exception {
    tx.begin();
    SeenDelivery.deleteAll();
    tx.commit();
  }

  /** A fresh process: its own empty memory, the shared table. */
  private WebhookDeduplicator process() {
    return new WebhookDeduplicator(
        TTL_MS, WebhookDeduplicator.SWEEP_THRESHOLD, clock::get, deliveries);
  }

  @Test
  void aRedeliveryAfterARestartWithinTheWindowIsADuplicate() {
    assertFalse(process().isDuplicate("delivery-restart"));

    clock.addAndGet(TTL_MS - 1);
    var restarted = process();

    assertTrue(restarted.isDuplicate("delivery-restart"));
    assertEquals(1, SeenDelivery.count());
  }

  @Test
  void aRedeliveryAfterTheWindowIsHandledAgainAndReplacesTheExpiredRow() {
    assertFalse(process().isDuplicate("delivery-old"));

    clock.addAndGet(TTL_MS);
    var later = process();

    assertFalse(later.isDuplicate("delivery-old"));
    assertTrue(process().isDuplicate("delivery-old"));
    SeenDelivery row = SeenDelivery.findById("delivery-old");
    assertEquals(Instant.ofEpochMilli(START + 2 * TTL_MS), row.expiresAt);
  }

  @Test
  void twoInstancesSharingTheDatabaseHandleADeliveryOnce() throws Exception {
    var first = process();
    var second = process();
    int threads = 16;
    var start = new CountDownLatch(1);
    var handled = new AtomicLong();
    var error = new AtomicReference<Throwable>();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        var instance = i % 2 == 0 ? first : second;
        pool.execute(
            () -> {
              try {
                start.await();
                if (!instance.isDuplicate("delivery-race")) {
                  handled.incrementAndGet();
                }
              } catch (Throwable t) {
                error.compareAndSet(null, t);
              }
            });
      }
      start.countDown();
    } finally {
      pool.shutdown();
      assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
    }

    assertNull(error.get());
    assertEquals(1, handled.get());
    assertEquals(1, SeenDelivery.count());
  }

  @Test
  void aDeliveryWhoseDispatchFailedIsHandledWhenRedeliveredToAnyInstance() {
    var original = process();
    assertFalse(original.isDuplicate("delivery-failed"));

    original.forget("delivery-failed");

    assertEquals(0, SeenDelivery.count());
    assertFalse(process().isDuplicate("delivery-failed"));
    assertTrue(original.isDuplicate("delivery-failed"));
  }

  @Test
  void thePurgeDeletesExpiredRowsInBoundedBatchesAndKeepsLiveOnes() {
    var now = Instant.ofEpochMilli(START);
    for (int i = 0; i < 5; i++) {
      deliveries.claim("expired-" + i, now.minusSeconds(120), now.minusSeconds(60 - i));
    }
    deliveries.claim("live", now, now.plusSeconds(60));

    assertEquals(2, deliveries.purgeExpired(now, 2));
    // Oldest first.
    assertNull(SeenDelivery.findById("expired-0"));
    assertNull(SeenDelivery.findById("expired-1"));
    assertNotNull(SeenDelivery.findById("expired-2"));

    var purge = new DeliveryPurge(deliveries, mock(Vertx.class), () -> now, Runnable::run);
    assertEquals(3, purge.purge());
    assertEquals(0, purge.purge());
    assertEquals(1, SeenDelivery.count());
    assertNotNull(SeenDelivery.findById("live"));
  }

  @Test
  void aRunPurgesAtMostItsBatches() {
    var store = mock(SeenDeliveries.class);
    when(store.purgeExpired(any(), anyInt())).thenReturn(DeliveryPurge.BATCH);
    var purge = new DeliveryPurge(store, mock(Vertx.class), Instant::now, Runnable::run);

    assertEquals(DeliveryPurge.BATCH * DeliveryPurge.MAX_BATCHES, purge.purge());
    verify(store, times(DeliveryPurge.MAX_BATCHES)).purgeExpired(any(), eq(DeliveryPurge.BATCH));
  }

  @Test
  void aFailedPurgeIsLoggedAndKeepsWhatItDeleted() {
    var store = mock(SeenDeliveries.class);
    when(store.purgeExpired(any(), anyInt()))
        .thenReturn(DeliveryPurge.BATCH)
        .thenThrow(new IllegalStateException("db down"));
    var purge = new DeliveryPurge(store, mock(Vertx.class), Instant::now, Runnable::run);

    assertEquals(DeliveryPurge.BATCH, purge.purge());
  }

  @Test
  void thePurgeRunsAtStartupAndHourlyAndStopsOnShutdown() {
    var vertx = mock(Vertx.class);
    var timer = new AtomicReference<Handler<Long>>();
    when(vertx.setPeriodic(
            eq(DeliveryPurge.INTERVAL.toMillis()), ArgumentMatchers.<Handler<Long>>any()))
        .thenAnswer(
            inv -> {
              timer.set(inv.getArgument(1));
              return 7L;
            });
    var store = mock(SeenDeliveries.class);
    var runs = new ArrayList<Runnable>();
    var purge = new DeliveryPurge(store, vertx, Instant::now, runs::add);

    purge.onStart(new StartupEvent());
    assertTrue(purge.running());
    // A tick while a run is pending starts no second one.
    timer.get().handle(7L);
    assertEquals(1, runs.size());
    runs.getFirst().run();
    assertFalse(purge.running());
    timer.get().handle(7L);
    assertEquals(2, runs.size());

    purge.onStop(new ShutdownEvent());
    verify(vertx).cancelTimer(7L);
  }

  @Test
  void aPurgeThatCannotBeScheduledLeavesTheNextOneFree() {
    var purge =
        new DeliveryPurge(
            mock(SeenDeliveries.class),
            mock(Vertx.class),
            Instant::now,
            task -> {
              throw new RejectedExecutionException("shut down");
            });

    purge.schedule();

    assertFalse(purge.running());
  }

  @Test
  void stoppingBeforeStartingCancelsNothingAndShutsTheExecutorDown() {
    var vertx = mock(Vertx.class);
    var executor = Executors.newSingleThreadExecutor();
    var purge = new DeliveryPurge(mock(SeenDeliveries.class), vertx, Instant::now, executor);

    purge.onStop(new ShutdownEvent());

    verify(vertx, never()).cancelTimer(anyLong());
    assertTrue(executor.isShutdown());
  }

  @Test
  void aUniqueViolationIsFoundAnywhereInTheCauseChain() {
    var violation = new SQLException("duplicate key", "23505");

    assertTrue(SeenDeliveries.isUniqueViolation(violation));
    assertTrue(
        SeenDeliveries.isUniqueViolation(
            new IllegalStateException("outer", new RuntimeException("mid", violation))));
    assertFalse(
        SeenDeliveries.isUniqueViolation(
            new IllegalStateException("outer", new SQLException("down", "08006"))));
    assertFalse(SeenDeliveries.isUniqueViolation(new IllegalStateException("no cause")));
  }
}
