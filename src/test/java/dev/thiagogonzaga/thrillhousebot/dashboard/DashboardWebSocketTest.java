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
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DashboardWebSocketTest {

  private static final String TICKET_PREFIX = "ticket-for-";

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
    when(sessionValidator.repositoryScope(anyString())).thenReturn(RepositoryScope.NONE);
    // A ticket "ticket-for-<token>" redeems to <token>; anything else redeems to nothing.
    when(sessionValidator.redeemSocketTicket(any()))
        .thenAnswer(
            inv -> {
              String ticket = inv.getArgument(0);
              return ticket != null && ticket.startsWith(TICKET_PREFIX)
                  ? Optional.of(ticket.substring(TICKET_PREFIX.length()))
                  : Optional.empty();
            });
    webSocket = new DashboardWebSocket(broadcaster, sessionValidator);
  }

  private static Session sessionWithParameters(String id, Map<String, List<String>> parameters) {
    var session = mock(Session.class);
    when(session.getId()).thenReturn(id);
    when(session.getRequestParameterMap()).thenReturn(parameters);
    return session;
  }

  private static Session sessionWithTicket(String id, String sessionToken) {
    return sessionWithParameters(
        id, Map.of(DashboardWebSocket.TICKET_PARAMETER, List.of(TICKET_PREFIX + sessionToken)));
  }

  @Test
  void onOpenShouldAddSessionForARedeemedTicket() {
    webSocket.onOpen(sessionWithTicket("ws-session-1", "valid-token"));

    assertEquals(1, broadcaster.getConnectedCount());
    verify(sessionValidator).redeemSocketTicket(TICKET_PREFIX + "valid-token");
  }

  @Test
  void onCloseShouldRemoveSession() {
    var session = sessionWithTicket("ws-session-3", "valid-token");

    webSocket.onOpen(session);
    assertEquals(1, broadcaster.getConnectedCount());

    webSocket.onClose(session);
    assertEquals(0, broadcaster.getConnectedCount());
  }

  @Test
  void onCloseShouldHandleSessionNotInBroadcaster() {
    var session = mock(Session.class);
    when(session.getId()).thenReturn("ws-session-unknown");

    assertDoesNotThrow(() -> webSocket.onClose(session));
    assertEquals(0, broadcaster.getConnectedCount());
  }

  /**
   * Nothing but a redeemable ticket opens a socket: no parameters, an unknown ticket, two tickets,
   * and the session cookie passed as a query parameter, which used to be accepted (#1223).
   */
  static Stream<Arguments> unauthenticatedParameters() {
    return Stream.of(
        Arguments.of(Map.of()),
        Arguments.of(Map.of(DashboardWebSocket.TICKET_PARAMETER, List.of("forged"))),
        Arguments.of(
            Map.of(
                DashboardWebSocket.TICKET_PARAMETER,
                List.of(TICKET_PREFIX + "valid-token", TICKET_PREFIX + "other-token"))),
        Arguments.of(Map.of("Cookie", List.of("thrillhouse_session=valid-token"))));
  }

  @ParameterizedTest
  @MethodSource("unauthenticatedParameters")
  void onOpenShouldCloseSessionWithoutARedeemableTicket(Map<String, List<String>> parameters)
      throws Exception {
    var session = sessionWithParameters("ws-close-test", parameters);

    webSocket.onOpen(session);

    verify(session)
        .close(
            argThat((CloseReason r) -> r.getCloseCode() == CloseReason.CloseCodes.VIOLATED_POLICY));
    assertEquals(0, broadcaster.getConnectedCount());
  }

  @Test
  void onOpenShouldNeverReadTheSharedUserProperties() {
    var session = sessionWithParameters("ws-user-properties", Map.of());
    when(session.getUserProperties())
        .thenReturn(Map.of("Cookie", (Object) "thrillhouse_session=valid-token"));

    webSocket.onOpen(session);

    assertEquals(0, broadcaster.getConnectedCount());
    verify(session, never()).getUserProperties();
  }

  @Test
  void onOpenShouldNotThrowWhenSessionCloseFails() throws Exception {
    var session = sessionWithParameters("ws-close-fails", Map.of());
    doThrow(new RuntimeException("close failed")).when(session).close(any(CloseReason.class));

    assertDoesNotThrow(() -> webSocket.onOpen(session));
    assertEquals(0, broadcaster.getConnectedCount());
  }

  @Test
  void liveEventsReachAConnectionOnlyForItsReadableRepositoriesWhileTheSessionIsOpen() {
    when(sessionValidator.repositoryScope("scoped-token"))
        .thenReturn(TestScopes.readable(false, "acme/allowed"));
    when(sessionValidator.isSessionOpen("scoped-token")).thenReturn(true);
    var remote = mock(jakarta.websocket.RemoteEndpoint.Async.class);
    var session = sessionWithTicket("ws-scoped", "scoped-token");
    when(session.isOpen()).thenReturn(true);
    when(session.getAsyncRemote()).thenReturn(remote);
    webSocket.onOpen(session);

    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(review(1L, "acme/secret")));
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(review(2L, "Acme/Allowed")));
    when(sessionValidator.isSessionOpen("scoped-token")).thenReturn(false);
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(review(3L, "acme/allowed")));

    var payload = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(remote).sendText(payload.capture(), any());
    assertTrue(payload.getValue().contains("\"sessionId\":2"));
  }

  private static ReviewSession review(long id, String repository) {
    var review = ReviewSession.create(TestScopes.scope(repository), repository, 7, "PR", "sha");
    review.id = id;
    return review;
  }
}
