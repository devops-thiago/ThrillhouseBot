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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Opens real sockets against the running endpoint (#1223). The endpoint configuration is shared by
 * every handshake, so a socket must be authenticated only by the single-use ticket in its own query
 * string, never by anything an earlier handshake left behind.
 */
@QuarkusTest
class DashboardWebSocketHandshakeTest {

  private static final String SESSION_COOKIE = "thrillhouse_session";
  private static final String TICKET_PATH = "/api/auth/socket-ticket";

  @TestHTTPResource("/ws/dashboard")
  URI endpoint;

  @Inject DashboardSessionStore sessionStore;
  @Inject SessionEventBroadcaster broadcaster;

  @InjectMock DashboardAccessChecker accessChecker;

  private final List<Session> opened = new ArrayList<>();

  @BeforeEach
  void setUp() {
    when(accessChecker.hasAccess(anyString())).thenReturn(true);
    when(accessChecker.checkAccess(anyString()))
        .thenReturn(DashboardAccessChecker.AccessDecision.ALLOWED);
    when(accessChecker.repositoryScope("alice"))
        .thenReturn(TestScopes.readable(false, "acme/alice-repo"));
    when(accessChecker.repositoryScope("bob"))
        .thenReturn(TestScopes.readable(false, "acme/bob-repo"));
  }

  @AfterEach
  void closeSockets() throws Exception {
    for (var session : opened) {
      if (session.isOpen()) {
        session.close();
      }
    }
  }

  @Test
  void aSocketWithoutATicketIsRejectedEvenAfterAnAuthenticatedHandshake() throws Exception {
    var aliceSession = sessionFor("alice");
    var authenticated = connect(ticketFor(aliceSession), null);
    var anonymous = connect(null, SESSION_COOKIE + "=" + aliceSession);

    assertEquals(
        CloseReason.CloseCodes.VIOLATED_POLICY,
        anonymous.closed.get(5, TimeUnit.SECONDS).getCloseCode());
    assertFalse(authenticated.closed.isDone());
  }

  @Test
  void aTicketOpensOneSocketOnly() throws Exception {
    var ticket = ticketFor(sessionFor("alice"));
    var first = connect(ticket, null);
    var second = connect(ticket, null);

    assertEquals(
        CloseReason.CloseCodes.VIOLATED_POLICY,
        second.closed.get(5, TimeUnit.SECONDS).getCloseCode());
    assertFalse(first.closed.isDone());
  }

  @Test
  void eachSocketReceivesOnlyTheEventsOfItsOwnUser() throws Exception {
    // A started session is replayed to a socket that registers after the broadcast, so no event
    // is lost to the time the endpoint takes to register the new socket.
    var alice = connect(ticketFor(sessionFor("alice")), null);
    var bob = connect(ticketFor(sessionFor("bob")), null);

    broadcaster.broadcast(
        SessionEventBroadcaster.SessionEvent.started(review(1L, "acme/alice-repo")));
    broadcaster.broadcast(
        SessionEventBroadcaster.SessionEvent.started(review(2L, "acme/bob-repo")));

    assertTrue(alice.awaitMessage("\"sessionId\":1"));
    assertTrue(bob.awaitMessage("\"sessionId\":2"));
    assertTrue(alice.seen.stream().noneMatch(m -> m.contains("\"sessionId\":2")));
    assertTrue(bob.seen.stream().noneMatch(m -> m.contains("\"sessionId\":1")));
  }

  @Test
  void theTicketEndpointRequiresASession() {
    given().when().post(TICKET_PATH).then().statusCode(401);
    given().cookie(SESSION_COOKIE, "unknown").when().post(TICKET_PATH).then().statusCode(401);
  }

  @Test
  void theTicketEndpointDeniesALoginWithoutAccess() {
    when(accessChecker.checkAccess("mallory"))
        .thenReturn(DashboardAccessChecker.AccessDecision.DENIED);

    given()
        .cookie(SESSION_COOKIE, sessionFor("mallory"))
        .when()
        .post(TICKET_PATH)
        .then()
        .statusCode(403);
  }

  @Test
  void theTicketEndpointIssuesAnUncachedTicket() {
    given()
        .cookie(SESSION_COOKIE, sessionFor("alice"))
        .when()
        .post(TICKET_PATH)
        .then()
        .statusCode(200)
        .header("Cache-Control", equalTo("no-store"))
        .body("ticket", notNullValue());
  }

  private String sessionFor(String login) {
    return sessionStore.createSession(login, "token", "", "");
  }

  private static String ticketFor(String session) {
    return given()
        .cookie(SESSION_COOKIE, session)
        .when()
        .post(TICKET_PATH)
        .then()
        .statusCode(200)
        .extract()
        .path("ticket");
  }

  private static ReviewSession review(long id, String repository) {
    var review = ReviewSession.create(TestScopes.scope(repository), repository, 7, "PR", "sha");
    review.id = id;
    return review;
  }

  private Connection connect(String ticket, String cookie) throws Exception {
    var connection = new Connection();
    var config =
        ClientEndpointConfig.Builder.create()
            .configurator(
                new ClientEndpointConfig.Configurator() {
                  @Override
                  public void beforeRequest(Map<String, List<String>> headers) {
                    if (cookie != null) {
                      headers.put("Cookie", new ArrayList<>(List.of(cookie)));
                    }
                  }
                })
            .build();
    var target = ticket == null ? endpoint : URI.create(endpoint + "?ticket=" + ticket);
    var session =
        ContainerProvider.getWebSocketContainer()
            .connectToServer(
                new Endpoint() {
                  @Override
                  public void onOpen(Session session, EndpointConfig endpointConfig) {
                    session.addMessageHandler(
                        String.class, (MessageHandler.Whole<String>) connection.messages::add);
                  }

                  @Override
                  public void onClose(Session session, CloseReason closeReason) {
                    connection.closed.complete(closeReason);
                  }
                },
                config,
                target);
    opened.add(session);
    return connection;
  }

  private static final class Connection {
    private final CompletableFuture<CloseReason> closed = new CompletableFuture<>();
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final List<String> seen = new ArrayList<>();

    /** Waits up to five seconds for a message containing {@code fragment}, keeping all it read. */
    private boolean awaitMessage(String fragment) throws InterruptedException {
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      for (var left = deadline - System.nanoTime(); left > 0; left = deadline - System.nanoTime()) {
        var message = messages.poll(left, TimeUnit.NANOSECONDS);
        if (message == null) {
          return false;
        }
        seen.add(message);
        if (message.contains(fragment)) {
          messages.drainTo(seen);
          return true;
        }
      }
      return false;
    }
  }
}
