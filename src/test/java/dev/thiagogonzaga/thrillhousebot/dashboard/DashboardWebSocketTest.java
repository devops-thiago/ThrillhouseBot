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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DashboardWebSocketTest {

  private DashboardWebSocket webSocket;
  private SessionEventBroadcaster broadcaster;
  private DashboardSessionValidator sessionValidator;

  @BeforeEach
  void setUp() {
    broadcaster = new SessionEventBroadcaster(new ObjectMapper());
    sessionValidator = mock(DashboardSessionValidator.class);
    when(sessionValidator.isValidSession(anyString()))
        .thenAnswer(
            inv -> {
              String token = inv.getArgument(0);
              return token != null && !token.isBlank();
            });
    webSocket = new DashboardWebSocket(broadcaster, sessionValidator);
  }

  // ── onOpen adds session to broadcaster ──────────────────────────────────

  static Stream<Arguments> validCookieHeaders() {
    return Stream.of(
        Arguments.of("ws-session-1", "thrillhouse_session=valid-token; other=value"),
        Arguments.of(
            "ws-parsed-cookie", "session_id=abc; thrillhouse_session=my-token; path=/; HttpOnly"),
        Arguments.of("ws-last-cookie", "a=b; thrillhouse_session=last-token"));
  }

  @ParameterizedTest
  @MethodSource("validCookieHeaders")
  void onOpenShouldAddSessionForValidCookieHeader(String sessionId, String cookieHeader) {
    var session = mock(Session.class);
    when(session.getId()).thenReturn(sessionId);
    when(session.getRequestParameterMap()).thenReturn(Map.of("Cookie", List.of(cookieHeader)));

    webSocket.onOpen(session);

    assertEquals(1, broadcaster.getConnectedCount());
  }

  @Test
  void onOpenShouldAddSessionWhenCookieInUserProperties() {
    var session = mock(Session.class);
    when(session.getId()).thenReturn("ws-session-2");
    when(session.getRequestParameterMap()).thenReturn(Map.of());
    when(session.getUserProperties())
        .thenReturn(
            Map.of(
                DashboardWebSocketConfigurator.COOKIE_USER_PROPERTY,
                (Object) "thrillhouse_session=valid-token"));

    webSocket.onOpen(session);

    assertEquals(1, broadcaster.getConnectedCount());
  }

  // ── onClose removes session ─────────────────────────────────────────────

  @Test
  void onCloseShouldRemoveSession() {
    var session = mock(Session.class);
    when(session.getId()).thenReturn("ws-session-3");
    when(session.getRequestParameterMap())
        .thenReturn(Map.of("Cookie", List.of("thrillhouse_session=valid-token")));

    // First add via onOpen
    webSocket.onOpen(session);
    assertEquals(1, broadcaster.getConnectedCount());

    // Then remove via onClose
    webSocket.onClose(session);
    assertEquals(0, broadcaster.getConnectedCount());
  }

  @Test
  void onCloseShouldHandleSessionNotInBroadcaster() {
    var session = mock(Session.class);
    when(session.getId()).thenReturn("ws-session-unknown");

    // Should not throw even if session was never added
    assertDoesNotThrow(() -> webSocket.onClose(session));
    assertEquals(0, broadcaster.getConnectedCount());
  }

  // ── onOpen with no valid cookie → session is closed ─────────────────────

  static Stream<Arguments> missingCookieArgs() {
    return Stream.of(
        Arguments.of(Map.of(), Map.of()),
        Arguments.of(Map.of("Cookie", List.of("thrillhouse_session=;")), Map.of()),
        Arguments.of(Map.of("Cookie", List.of("other_cookie=some-value")), Map.of()));
  }

  @ParameterizedTest
  @MethodSource("missingCookieArgs")
  void onOpenShouldCloseSessionWhenMissingValidCookie(
      Map<String, List<String>> requestParams, Map<String, Object> userProperties)
      throws Exception {
    var session = mock(Session.class);
    when(session.getId()).thenReturn("ws-close-test");
    when(session.getRequestParameterMap()).thenReturn(requestParams);
    when(session.getUserProperties()).thenReturn(userProperties);

    webSocket.onOpen(session);

    verify(session)
        .close(
            argThat((CloseReason r) -> r.getCloseCode() == CloseReason.CloseCodes.VIOLATED_POLICY));
    assertEquals(0, broadcaster.getConnectedCount());
  }

  // ── Edge case: Session close throws exception ───────────────────────────

  @Test
  void onOpenShouldNotThrowWhenSessionCloseFails() throws Exception {
    var session = mock(Session.class);
    when(session.getId()).thenReturn("ws-close-fails");
    when(session.getRequestParameterMap()).thenReturn(Map.of());
    when(session.getUserProperties()).thenReturn(Map.of());
    doThrow(new RuntimeException("close failed")).when(session).close(any(CloseReason.class));

    // Should not propagate exception
    assertDoesNotThrow(() -> webSocket.onOpen(session));
    assertEquals(0, broadcaster.getConnectedCount());
  }

  @Test
  void onOpenShouldRejectEvilCookieNamePrefix() throws Exception {
    var session = mock(Session.class);
    when(session.getId()).thenReturn("ws-evil-cookie");
    when(session.getRequestParameterMap())
        .thenReturn(Map.of("Cookie", List.of("evil_thrillhouse_session=fake-token")));
    when(session.getUserProperties()).thenReturn(Map.of());

    webSocket.onOpen(session);

    verify(session)
        .close(
            argThat((CloseReason r) -> r.getCloseCode() == CloseReason.CloseCodes.VIOLATED_POLICY));
    assertEquals(0, broadcaster.getConnectedCount());
  }

  @Test
  void extractCookieValueShouldMatchExactCookieName() {
    assertEquals(
        "token",
        DashboardWebSocket.extractCookieValue(
            "evil_thrillhouse_session=fake; thrillhouse_session=token; path=/",
            "thrillhouse_session"));
    assertNull(
        DashboardWebSocket.extractCookieValue(
            "evil_thrillhouse_session=fake", "thrillhouse_session"));
    assertNull(DashboardWebSocket.extractCookieValue(null, "thrillhouse_session"));
    assertNull(DashboardWebSocket.extractCookieValue("   ", "thrillhouse_session"));
  }
}
