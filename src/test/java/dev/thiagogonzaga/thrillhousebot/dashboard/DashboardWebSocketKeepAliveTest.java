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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DashboardWebSocketKeepAliveTest {

  /** Periodic timers a mocked Vert.x was asked for, by the interval each was scheduled at. */
  private static Map<Long, Handler<Long>> timersOf(Vertx vertx) {
    var timers = new LinkedHashMap<Long, Handler<Long>>();
    var ids = new AtomicLong(40);
    when(vertx.setPeriodic(anyLong(), any()))
        .thenAnswer(
            invocation -> {
              timers.put(invocation.getArgument(0), invocation.getArgument(1));
              return ids.incrementAndGet();
            });
    return timers;
  }

  @Test
  void shouldScheduleKeepAliveAndEvictionOnStart() {
    Vertx vertx = mock(Vertx.class);
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    when(config.websocketKeepAliveMs()).thenReturn(10_000L);
    var timers = timersOf(vertx);

    var keepAlive = new DashboardWebSocketKeepAlive(vertx, broadcaster, config);
    keepAlive.onStart(mock(StartupEvent.class));
    timers.values().forEach(handler -> handler.handle(1L));

    verify(vertx).setPeriodic(eq(10_000L), any());
    verify(broadcaster).sendKeepAlive();
    verify(broadcaster).evictStaleSessionBuffers();
    verify(broadcaster, timeout(5_000)).refreshReaders();
    keepAlive.onStop(mock(ShutdownEvent.class));
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L})
  void accessIsStillRecheckedOnATimerWhenTheKeepAliveIsOff(long keepAliveMs) {
    Vertx vertx = mock(Vertx.class);
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    when(config.websocketKeepAliveMs()).thenReturn(keepAliveMs);
    var timers = timersOf(vertx);

    var keepAlive = new DashboardWebSocketKeepAlive(vertx, broadcaster, config);
    keepAlive.onStart(mock(StartupEvent.class));

    assertEquals(1, timers.size(), "the access re-check is the only timer");
    timers.values().forEach(handler -> handler.handle(1L));
    verify(broadcaster, timeout(5_000)).refreshReaders();
    verify(broadcaster, never()).sendKeepAlive();
    verify(broadcaster, never()).evictStaleSessionBuffers();
    keepAlive.onStop(mock(ShutdownEvent.class));
    verify(vertx).cancelTimer(41L);
  }

  @Test
  void accessRecheckDoesNotWaitForALongKeepAlive() {
    Vertx vertx = mock(Vertx.class);
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    var hour = TimeUnit.HOURS.toMillis(1);
    when(config.websocketKeepAliveMs()).thenReturn(hour);
    var timers = timersOf(vertx);

    var keepAlive = new DashboardWebSocketKeepAlive(vertx, broadcaster, config);
    keepAlive.onStart(mock(StartupEvent.class));

    var recheck =
        timers.entrySet().stream()
            .filter(timer -> timer.getKey() <= TimeUnit.MINUTES.toMillis(1))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no timer shorter than the keepalive"));
    recheck.handle(1L);
    verify(broadcaster, timeout(5_000)).refreshReaders();
    verify(broadcaster, never()).sendKeepAlive();
    // The keepalive tick keeps its own work and leaves the re-check to the other timer.
    timers.get(hour).handle(2L);
    verify(broadcaster).sendKeepAlive();
    verify(broadcaster).evictStaleSessionBuffers();
    keepAlive.onStop(mock(ShutdownEvent.class));
  }

  @Test
  void aRefreshStillRunningIsNotStartedTwiceAndAFailedOneIsRetried() throws Exception {
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                throw new IllegalStateException("GitHub is down");
              }
              return null;
            })
        .when(broadcaster)
        .refreshReaders();
    var keepAlive =
        new DashboardWebSocketKeepAlive(
            mock(Vertx.class), broadcaster, mock(ThrillhouseConfig.class));

    keepAlive.refreshReaders();
    assertTrue(started.await(5, TimeUnit.SECONDS));
    keepAlive.refreshReaders();
    release.countDown();

    verify(broadcaster, timeout(5_000).times(1)).refreshReaders();
    // Once the failed refresh has finished, the next tick runs again.
    await(
        () -> {
          keepAlive.refreshReaders();
          return calls.get() >= 2;
        });
    keepAlive.onStop(mock(ShutdownEvent.class));
  }

  @Test
  void anInstallationChangeRefreshesTheConnectionsAtOnce() {
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    var keepAlive =
        new DashboardWebSocketKeepAlive(
            mock(Vertx.class), broadcaster, mock(ThrillhouseConfig.class));

    keepAlive.onInstallationChange(
        new dev.thiagogonzaga.thrillhousebot.github.InstallationChange(
            dev.thiagogonzaga.thrillhousebot.github.InstallationChange.Kind.GONE,
            10L,
            java.util.List.of()));

    verify(broadcaster, timeout(5_000)).refreshReaders();
    keepAlive.onStop(mock(ShutdownEvent.class));
  }

  @Test
  void anInstallationChangeDuringARunningRefreshMakesItPassAgain() throws Exception {
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
              }
              return null;
            })
        .when(broadcaster)
        .refreshReaders();
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    when(config.websocketKeepAliveMs()).thenReturn(0L);
    var keepAlive = new DashboardWebSocketKeepAlive(mock(Vertx.class), broadcaster, config);
    // A dropped change would otherwise wait for the next re-check tick.
    keepAlive.onStart(mock(StartupEvent.class));

    keepAlive.refreshReaders();
    assertTrue(started.await(5, TimeUnit.SECONDS));
    keepAlive.onInstallationChange(
        new dev.thiagogonzaga.thrillhousebot.github.InstallationChange(
            dev.thiagogonzaga.thrillhousebot.github.InstallationChange.Kind.GONE,
            20L,
            java.util.List.of()));
    release.countDown();

    verify(broadcaster, timeout(5_000).times(2)).refreshReaders();
    // The change is consumed by that second pass, so no third one follows.
    verify(broadcaster, after(200).times(2)).refreshReaders();
    keepAlive.onStop(mock(ShutdownEvent.class));
  }

  @Test
  void aRefreshAfterShutdownIsSkippedQuietly() {
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    var keepAlive =
        new DashboardWebSocketKeepAlive(
            mock(Vertx.class), broadcaster, mock(ThrillhouseConfig.class));
    keepAlive.onStop(mock(ShutdownEvent.class));

    keepAlive.refreshReaders();
    keepAlive.refreshReaders();

    verify(broadcaster, never()).refreshReaders();
  }

  /** Polls {@code condition} for up to five seconds, failing if it never holds. */
  private static void await(java.util.function.BooleanSupplier condition) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition never held");
      }
      Thread.onSpinWait();
    }
  }

  @Test
  void shouldCancelBothTimersOnStop() {
    Vertx vertx = mock(Vertx.class);
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    when(config.websocketKeepAliveMs()).thenReturn(10_000L);
    timersOf(vertx);

    var keepAlive = new DashboardWebSocketKeepAlive(vertx, broadcaster, config);
    keepAlive.onStart(mock(StartupEvent.class));
    keepAlive.onStop(mock(ShutdownEvent.class));

    verify(vertx).cancelTimer(41L);
    verify(vertx).cancelTimer(42L);
  }

  @Test
  void stoppingBeforeStartCancelsNothing() {
    Vertx vertx = mock(Vertx.class);
    var keepAlive =
        new DashboardWebSocketKeepAlive(
            vertx, mock(SessionEventBroadcaster.class), mock(ThrillhouseConfig.class));

    keepAlive.onStop(mock(ShutdownEvent.class));

    verify(vertx, never()).cancelTimer(anyLong());
  }
}
