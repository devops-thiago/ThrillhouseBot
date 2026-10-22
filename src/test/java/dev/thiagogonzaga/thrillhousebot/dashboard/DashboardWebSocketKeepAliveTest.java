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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DashboardWebSocketKeepAliveTest {

  @Test
  void shouldScheduleKeepAliveAndEvictionOnStart() {
    Vertx vertx = mock(Vertx.class);
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    when(config.websocketKeepAliveMs()).thenReturn(25_000L);
    when(vertx.setPeriodic(anyLong(), any()))
        .thenAnswer(
            invocation -> {
              Handler<Long> handler = invocation.getArgument(1);
              handler.handle(1L);
              return 99L;
            });

    var keepAlive = new DashboardWebSocketKeepAlive(vertx, broadcaster, config);
    keepAlive.onStart(mock(StartupEvent.class));

    verify(vertx).setPeriodic(eq(25_000L), any());
    verify(broadcaster).sendKeepAlive();
    verify(broadcaster).evictStaleSessionBuffers();
    verify(broadcaster, timeout(5_000)).refreshReaders();
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
  void shouldDisableKeepAliveWhenIntervalIsZero() {
    Vertx vertx = mock(Vertx.class);
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    when(config.websocketKeepAliveMs()).thenReturn(0L);

    var keepAlive = new DashboardWebSocketKeepAlive(vertx, broadcaster, config);
    keepAlive.onStart(mock(StartupEvent.class));
    keepAlive.onStop(mock(ShutdownEvent.class));

    verify(vertx, never()).setPeriodic(anyLong(), any());
    verify(vertx, never()).cancelTimer(anyLong());
  }

  @Test
  void shouldDisableKeepAliveWhenIntervalIsNegative() {
    Vertx vertx = mock(Vertx.class);
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    when(config.websocketKeepAliveMs()).thenReturn(-1L);

    var keepAlive = new DashboardWebSocketKeepAlive(vertx, broadcaster, config);
    keepAlive.onStart(mock(StartupEvent.class));

    verify(vertx, never()).setPeriodic(anyLong(), any());
  }

  @Test
  void shouldCancelTimerOnStop() {
    Vertx vertx = mock(Vertx.class);
    SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    when(config.websocketKeepAliveMs()).thenReturn(25_000L);
    when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);

    var keepAlive = new DashboardWebSocketKeepAlive(vertx, broadcaster, config);
    keepAlive.onStart(mock(StartupEvent.class));
    keepAlive.onStop(mock(ShutdownEvent.class));

    verify(vertx).cancelTimer(42L);
  }
}
