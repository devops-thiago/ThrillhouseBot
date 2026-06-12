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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends periodic keepalive frames so reverse proxies do not idle-close dashboard sockets. */
@ApplicationScoped
public class DashboardWebSocketKeepAlive {

  private static final Logger log = LoggerFactory.getLogger(DashboardWebSocketKeepAlive.class);

  private final Vertx vertx;

  private final SessionEventBroadcaster broadcaster;

  private final ThrillhouseConfig config;

  private long timerId = -1;

  @Inject
  public DashboardWebSocketKeepAlive(
      Vertx vertx, SessionEventBroadcaster broadcaster, ThrillhouseConfig config) {
    this.vertx = vertx;
    this.broadcaster = broadcaster;
    this.config = config;
  }

  void onStart(@Observes StartupEvent event) {
    var intervalMs = config.websocketKeepAliveMs();
    // vertx.setPeriodic rejects delays < 1 with an exception that would abort startup
    if (intervalMs <= 0) {
      log.warn(
          "WEBSOCKET_KEEPALIVE_MS={} — keepalive disabled; stale session replay buffers "
              + "will not be evicted either",
          intervalMs);
      return;
    }
    timerId =
        vertx.setPeriodic(
            intervalMs,
            id -> {
              broadcaster.sendKeepAlive();
              broadcaster.evictStaleSessionBuffers();
            });
  }

  void onStop(@Observes ShutdownEvent event) {
    if (timerId >= 0) {
      vertx.cancelTimer(timerId);
    }
  }
}
