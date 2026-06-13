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

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint(value = "/ws/dashboard", configurator = DashboardWebSocketConfigurator.class)
@RegisterForReflection
@ApplicationScoped
public class DashboardWebSocket {

  private static final Logger log = LoggerFactory.getLogger(DashboardWebSocket.class);

  private final SessionEventBroadcaster broadcaster;

  private final DashboardSessionValidator sessionValidator;

  @Inject
  public DashboardWebSocket(
      SessionEventBroadcaster broadcaster, DashboardSessionValidator sessionValidator) {
    this.broadcaster = broadcaster;
    this.sessionValidator = sessionValidator;
  }

  @OnOpen
  public void onOpen(Session session) {
    var sessionToken = extractSessionToken(session);
    if (sessionToken == null || !sessionValidator.isValidSession(sessionToken)) {
      log.warn("WebSocket connection rejected: missing/invalid session cookie");
      try {
        session.close(
            new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Authentication required"));
      } catch (Exception e) {
        log.error("Failed to close unauthorized WebSocket", e);
      }
      return;
    }

    broadcaster.addSession(session);
    log.info(
        "Dashboard WebSocket connected: {} (total: {})",
        session.getId(),
        broadcaster.getConnectedCount());
  }

  private String extractSessionToken(Session session) {
    var cookieObj =
        session.getUserProperties().get(DashboardWebSocketConfigurator.COOKIE_USER_PROPERTY);
    if (cookieObj instanceof String cookieHeader) {
      return extractCookieValue(cookieHeader, "thrillhouse_session");
    }

    var cookieHeaders = session.getRequestParameterMap().get("Cookie");
    if (cookieHeaders != null) {
      for (String cookieHeader : cookieHeaders) {
        String value = extractCookieValue(cookieHeader, "thrillhouse_session");
        if (value != null && !value.isBlank()) {
          return value;
        }
      }
    }

    return null;
  }

  static String extractCookieValue(String cookieHeader, String name) {
    if (cookieHeader == null || cookieHeader.isBlank()) {
      return null;
    }
    var prefix = name + "=";
    for (String part : cookieHeader.split(";")) {
      var trimmed = part.trim();
      if (trimmed.startsWith(prefix)) {
        return trimmed.substring(prefix.length());
      }
    }
    return null;
  }

  @OnClose
  public void onClose(Session session) {
    broadcaster.removeSession(session);
    log.info(
        "Dashboard WebSocket disconnected: {} (total: {})",
        session.getId(),
        broadcaster.getConnectedCount());
  }
}
