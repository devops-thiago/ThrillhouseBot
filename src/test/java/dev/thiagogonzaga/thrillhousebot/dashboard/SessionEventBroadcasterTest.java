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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.RemoteEndpoint;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionEventBroadcasterTest {

  private static final java.time.Instant FIXED_NOW =
      java.time.Instant.parse("2025-06-01T12:00:00Z");

  private SessionEventBroadcaster broadcaster;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    broadcaster = new SessionEventBroadcaster(mapper);
  }

  @Test
  void shouldAddAndRemoveSessions() {
    var session1 = mock(jakarta.websocket.Session.class);
    var session2 = mock(jakarta.websocket.Session.class);

    assertEquals(0, broadcaster.getConnectedCount());

    broadcaster.addSession(session1);
    assertEquals(1, broadcaster.getConnectedCount());

    broadcaster.addSession(session2);
    assertEquals(2, broadcaster.getConnectedCount());

    broadcaster.removeSession(session1);
    assertEquals(1, broadcaster.getConnectedCount());

    broadcaster.removeSession(session2);
    assertEquals(0, broadcaster.getConnectedCount());
  }

  @Test
  void shouldBufferEventWhenNoSessionsConnected() {
    var reviewSession = createReviewSession();
    var event = SessionEventBroadcaster.SessionEvent.started(reviewSession);

    broadcaster.broadcast(event);

    // The event must still be buffered so a late subscriber receives it on connect
    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);

    var payload = ArgumentCaptor.forClass(String.class);
    verify(asyncRemote).sendText(payload.capture(), any());
    assertTrue(payload.getValue().contains("\"type\":\"review.started\""));
  }

  @Test
  void shouldReplayBufferedEventsWhenClientConnectsLate() {
    var reviewSession = createReviewSession();
    reviewSession.id = 99L;
    var started = SessionEventBroadcaster.SessionEvent.started(reviewSession);
    var stream =
        SessionEventBroadcaster.SessionEvent.stream(
            reviewSession, "tok", "{\"findings\"", 12, 1, 1);

    broadcaster.broadcast(started);
    broadcaster.broadcast(stream);

    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);

    var payload = ArgumentCaptor.forClass(String.class);
    verify(asyncRemote, times(2)).sendText(payload.capture(), any());
    assertTrue(payload.getAllValues().get(0).contains("\"type\":\"review.started\""));
    assertTrue(payload.getAllValues().get(1).contains("\"type\":\"review.stream\""));
  }

  @Test
  void shouldNotReplayToClosedWebSocketSession() {
    var reviewSession = createReviewSession();
    reviewSession.id = 77L;
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(reviewSession));

    var wsSession = mock(jakarta.websocket.Session.class);
    when(wsSession.isOpen()).thenReturn(false);

    broadcaster.addSession(wsSession);

    verify(wsSession, never()).getAsyncRemote();
  }

  @Test
  void shouldNotReplayFailedSessionsOnConnect() {
    var reviewSession = createReviewSession();
    reviewSession.id = 66L;
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(reviewSession));
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.failed(reviewSession));

    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);

    verify(asyncRemote, never()).sendText(anyString(), any());
  }

  @Test
  void shouldTrimBufferedEventsPerSession() {
    var reviewSession = createReviewSession();
    reviewSession.id = 55L;

    for (var i = 0; i < 505; i++) {
      broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.progress(reviewSession, i, i));
    }

    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);

    var payload = ArgumentCaptor.forClass(String.class);
    verify(asyncRemote, times(500)).sendText(payload.capture(), any());
  }

  @Test
  void shouldKeepOnlyLatestStreamEventInReplayBuffer() {
    var reviewSession = createReviewSession();
    reviewSession.id = 54L;

    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(reviewSession));
    for (var i = 0; i < 5; i++) {
      broadcaster.broadcast(
          SessionEventBroadcaster.SessionEvent.stream(reviewSession, "t", "tail-" + i, i, i, 1));
    }

    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);

    var payload = ArgumentCaptor.forClass(String.class);
    verify(asyncRemote, times(2)).sendText(payload.capture(), any());
    assertTrue(payload.getAllValues().get(0).contains("\"type\":\"review.started\""));
    assertTrue(payload.getAllValues().get(1).contains("\"tail\":\"tail-4\""));
  }

  @Test
  void shouldNotReplayStaleStreamAfterRetry() {
    var reviewSession = createReviewSession();
    reviewSession.id = 53L;

    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(reviewSession));
    broadcaster.broadcast(
        SessionEventBroadcaster.SessionEvent.stream(reviewSession, "old", "stale-tail", 100, 1, 1));
    broadcaster.broadcast(
        SessionEventBroadcaster.SessionEvent.retry(reviewSession, 2, 3, "timed out"));

    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);

    var payload = ArgumentCaptor.forClass(String.class);
    verify(asyncRemote, times(2)).sendText(payload.capture(), any());
    assertTrue(payload.getAllValues().get(0).contains("\"type\":\"review.started\""));
    assertTrue(payload.getAllValues().get(1).contains("\"type\":\"review.retry\""));
  }

  @Test
  void shouldNotReplayStaleStreamAfterStreamFailed() {
    var reviewSession = createReviewSession();
    reviewSession.id = 52L;

    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(reviewSession));
    broadcaster.broadcast(
        SessionEventBroadcaster.SessionEvent.stream(reviewSession, "old", "stale-tail", 100, 1, 1));
    broadcaster.broadcast(
        SessionEventBroadcaster.SessionEvent.streamFailed(reviewSession, 1, 3, "bad json"));

    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);

    var payload = ArgumentCaptor.forClass(String.class);
    verify(asyncRemote, times(2)).sendText(payload.capture(), any());
    assertTrue(payload.getAllValues().get(0).contains("\"type\":\"review.started\""));
    assertTrue(payload.getAllValues().get(1).contains("\"type\":\"review.stream.failed\""));
  }

  @Test
  void shouldNotBufferNonReviewEvents() {
    var event =
        new SessionEventBroadcaster.SessionEvent(
            "keepalive", 1L, "owner/repo", 1, "title", "sha", "2025-06-01T12:00:00Z", Map.of());

    assertDoesNotThrow(() -> broadcaster.broadcast(event));

    var reviewSession = createReviewSession();
    reviewSession.id = 1L;
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(reviewSession));

    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);

    verify(asyncRemote, times(1)).sendText(anyString(), any());
  }

  @Test
  void shouldRemoveSessionStateOnTerminalEvents() {
    var reviewSession = createReviewSession();
    reviewSession.id = 42L;

    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(reviewSession));
    assertEquals(1, broadcaster.replayStateCount());

    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.completed(reviewSession));
    assertEquals(0, broadcaster.replayStateCount());
  }

  @Test
  void shouldEvictStaleSessionBuffers() {
    broadcaster.setClock(java.time.Clock.fixed(FIXED_NOW, java.time.ZoneOffset.UTC));
    var staleAt = FIXED_NOW.minus(SessionEventBroadcaster.MAX_SESSION_BUFFER_AGE).minusSeconds(1);
    broadcaster.seedReplayState(
        91L, "review.started", staleAt, java.util.List.of("{\"type\":\"review.started\"}"), null);

    broadcaster.evictStaleSessionBuffers();

    assertEquals(0, broadcaster.replayStateCount());
  }

  @Test
  void shouldNotEvictActiveSessionWithRecentActivity() {
    var reviewSession = createReviewSession();
    reviewSession.id = 92L;
    broadcaster.setClock(java.time.Clock.fixed(FIXED_NOW, java.time.ZoneOffset.UTC));
    var staleAt = FIXED_NOW.minus(SessionEventBroadcaster.MAX_SESSION_BUFFER_AGE).minusSeconds(1);
    broadcaster.seedReplayState(92L, "review.started", staleAt, java.util.List.of(), null);

    broadcaster.broadcast(
        SessionEventBroadcaster.SessionEvent.stream(reviewSession, "tok", "tail", 4, 1, 1));
    broadcaster.evictStaleSessionBuffers();

    assertEquals(1, broadcaster.replayStateCount());
  }

  @Test
  void shouldSkipReplayWhenLatestStatusIsTerminalButBufferRemains() {
    injectBufferedEvent(44L, "review.failed", "{\"type\":\"review.stream\"}");

    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);

    verify(asyncRemote, never()).sendText(anyString(), any());
  }

  @Test
  void shouldIgnoreSendFailuresDuringReplay() {
    var reviewSession = createReviewSession();
    reviewSession.id = 33L;
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(reviewSession));

    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);
    doAnswer(
            invocation -> {
              jakarta.websocket.SendHandler handler = invocation.getArgument(1);
              handler.onResult(
                  new jakarta.websocket.SendResult(new RuntimeException("send failed")));
              return null;
            })
        .when(asyncRemote)
        .sendText(anyString(), any());

    assertDoesNotThrow(() -> broadcaster.addSession(wsSession));
    verify(asyncRemote).sendText(anyString(), any());
  }

  @Test
  void shouldNotReplayCompletedSessionsOnConnect() {
    var reviewSession = createReviewSession();
    reviewSession.id = 88L;
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(reviewSession));
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.completed(reviewSession));

    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);
    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);

    verify(asyncRemote, never()).sendText(anyString(), any());
  }

  @Test
  void shouldBroadcastToConnectedSession() {
    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);

    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);
    assertEquals(1, broadcaster.getConnectedCount());

    var reviewSession = createReviewSession();
    var event = SessionEventBroadcaster.SessionEvent.started(reviewSession);

    assertDoesNotThrow(() -> broadcaster.broadcast(event));
    verify(asyncRemote).sendText(anyString(), any());
  }

  @Test
  void shouldNoOpSendKeepAliveWhenNoSessionsConnected() {
    assertDoesNotThrow(() -> broadcaster.sendKeepAlive());
    assertEquals(0, broadcaster.getConnectedCount());
  }

  @Test
  void shouldIgnoreWebSocketSendFailures() {
    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);

    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);
    doAnswer(
            invocation -> {
              jakarta.websocket.SendHandler handler = invocation.getArgument(1);
              handler.onResult(
                  new jakarta.websocket.SendResult(new RuntimeException("send failed")));
              return null;
            })
        .when(asyncRemote)
        .sendText(anyString(), any());

    broadcaster.addSession(wsSession);
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(createReviewSession()));

    // An async send failure must not disconnect the (still open) session
    assertEquals(1, broadcaster.getConnectedCount());
  }

  @Test
  void shouldSendKeepAliveToConnectedSessions() {
    var wsSession = mock(jakarta.websocket.Session.class);
    var asyncRemote = mock(RemoteEndpoint.Async.class);

    when(wsSession.isOpen()).thenReturn(true);
    when(wsSession.getAsyncRemote()).thenReturn(asyncRemote);

    broadcaster.addSession(wsSession);
    broadcaster.sendKeepAlive();

    var payload = ArgumentCaptor.forClass(String.class);
    verify(asyncRemote).sendText(payload.capture(), any());
    assertEquals("{\"type\":\"keepalive\"}", payload.getValue());
  }

  @Test
  void shouldRemoveClosedSessionsDuringBroadcast() {
    var openSession = mock(jakarta.websocket.Session.class);
    var openAsync = mock(RemoteEndpoint.Async.class);
    when(openSession.isOpen()).thenReturn(true);
    when(openSession.getAsyncRemote()).thenReturn(openAsync);

    var closedSession = mock(jakarta.websocket.Session.class);
    when(closedSession.isOpen()).thenReturn(false);

    broadcaster.addSession(openSession);
    broadcaster.addSession(closedSession);
    assertEquals(2, broadcaster.getConnectedCount());

    var reviewSession = createReviewSession();
    var event = SessionEventBroadcaster.SessionEvent.started(reviewSession);

    broadcaster.broadcast(event);

    // Closed session should be removed
    assertEquals(1, broadcaster.getConnectedCount());
  }

  @Test
  void shouldReturnZeroForInitialConnectedCount() {
    assertEquals(0, broadcaster.getConnectedCount());
  }

  @Test
  void shouldCreateSessionEventStarted() {
    var session = createReviewSession();
    session.id = 42L;
    session.setRepository("owner/repo");
    session.setPrNumber(7);
    session.setPrTitle("Fix bug");
    session.setCommitSha("abc123");
    session.setTimestamp(java.time.Instant.parse("2025-06-01T12:00:00Z"));

    var event = SessionEventBroadcaster.SessionEvent.started(session);

    assertEquals("review.started", event.type());
    assertEquals(42L, event.sessionId());
    assertEquals("owner/repo", event.repository());
    assertEquals(7, event.prNumber());
    assertEquals("Fix bug", event.prTitle());
    assertEquals("abc123", event.commitSha());
    assertEquals("2025-06-01T12:00:00Z", event.timestamp());
    assertEquals("in_progress", event.data().get("status"));
  }

  @Test
  void shouldCreateSessionEventProgress() {
    var session = createReviewSession();
    session.id = 10L;

    var event = SessionEventBroadcaster.SessionEvent.progress(session, 5, 1500);

    assertEquals("review.progress", event.type());
    assertEquals(10L, event.sessionId());
    assertEquals(5, event.data().get("findingsSoFar"));
    assertEquals(1500L, event.data().get("tokensUsed"));
    assertEquals("in_progress", event.data().get("status"));
  }

  @Test
  void shouldCreateSessionEventStream() {
    var session = createReviewSession();
    session.id = 5L;

    var event =
        SessionEventBroadcaster.SessionEvent.stream(session, "tok", "{\"findings\"", 12, 3, 2);

    assertEquals("review.stream", event.type());
    assertEquals(5L, event.sessionId());
    assertEquals("tok", event.data().get("chunk"));
    assertEquals("{\"findings\"", event.data().get("tail"));
    assertEquals(12, event.data().get("totalChars"));
    assertEquals(3, event.data().get("chunkIndex"));
    assertEquals(2, event.data().get("attempt"));
    assertEquals("streaming", event.data().get("status"));
  }

  @Test
  void shouldCreateSessionEventRetry() {
    var session = createReviewSession();
    session.id = 6L;

    var event = SessionEventBroadcaster.SessionEvent.retry(session, 2, 5, "timed out");

    assertEquals("review.retry", event.type());
    assertEquals(6L, event.sessionId());
    assertEquals(2, event.data().get("attempt"));
    assertEquals(5, event.data().get("maxAttempts"));
    assertEquals("timed out", event.data().get("reason"));
    assertEquals("retrying", event.data().get("status"));
  }

  @Test
  void shouldCreateSessionEventStreamFailed() {
    var session = createReviewSession();
    session.id = 7L;

    var event = SessionEventBroadcaster.SessionEvent.streamFailed(session, 1, 5, "bad json");

    assertEquals("review.stream.failed", event.type());
    assertEquals(7L, event.sessionId());
    assertEquals(1, event.data().get("attempt"));
    assertEquals(5, event.data().get("maxAttempts"));
    assertEquals("bad json", event.data().get("reason"));
    assertEquals("stream_failed", event.data().get("status"));
  }

  @Test
  void shouldCreateSessionEventCompleted() {
    var session = createReviewSession();
    session.id = 100L;
    session.setCriticalFindings(2);
    session.setHighFindings(3);
    session.setMediumFindings(5);
    session.setLowFindings(1);
    session.setInputTokens(1000);
    session.setOutputTokens(500);
    session.setCost(0.42);
    session.setDurationMs(30000L);

    var event = SessionEventBroadcaster.SessionEvent.completed(session);

    assertEquals("review.completed", event.type());
    assertEquals(100L, event.sessionId());
    assertEquals(2, event.data().get("critical"));
    assertEquals(3, event.data().get("high"));
    assertEquals(5, event.data().get("medium"));
    assertEquals(1, event.data().get("low"));
    assertEquals(1500, event.data().get("totalTokens"));
    assertEquals(1000, event.data().get("inputTokens"));
    assertEquals(500, event.data().get("outputTokens"));
    assertEquals(0.42, event.data().get("cost"));
    assertEquals(30000L, event.data().get("durationMs"));
    assertEquals("completed", event.data().get("status"));
  }

  @Test
  void shouldCreateSessionEventFailed() {
    var session = createReviewSession();
    session.id = 200L;
    session.setErrorMessage("Rate limit exceeded");
    session.setDurationMs(15000L);

    var event = SessionEventBroadcaster.SessionEvent.failed(session);

    assertEquals("review.failed", event.type());
    assertEquals(200L, event.sessionId());
    assertEquals("Rate limit exceeded", event.data().get("errorType"));
    assertEquals(15000L, event.data().get("durationMs"));
    assertEquals("failed", event.data().get("status"));
  }

  @Test
  void shouldCreateSessionEventFailedWithNullErrorMessage() {
    var session = createReviewSession();
    session.id = 300L;
    session.setErrorMessage(null);
    session.setDurationMs(5000L);

    var event = SessionEventBroadcaster.SessionEvent.failed(session);

    assertEquals("review.failed", event.type());
    assertEquals("Unknown", event.data().get("errorType"));
    assertEquals("failed", event.data().get("status"));
  }

  @Test
  void sessionEventDataShouldBeUnmodifiable() {
    var session = createReviewSession();
    var event = SessionEventBroadcaster.SessionEvent.started(session);

    var data = event.data();
    assertThrows(UnsupportedOperationException.class, () -> data.put("key", "value"));
  }

  @Test
  void shouldHandleJsonProcessingException() throws Exception {
    // Use a spy on ObjectMapper to simulate serialization failure
    ObjectMapper brokenMapper = spy(new ObjectMapper());
    doThrow(new com.fasterxml.jackson.core.JsonProcessingException("simulated") {})
        .when(brokenMapper)
        .writeValueAsString(any());

    var broadcasterWithBrokenMapper = new SessionEventBroadcaster(brokenMapper);

    var wsSession = mock(jakarta.websocket.Session.class);
    when(wsSession.isOpen()).thenReturn(true);
    broadcasterWithBrokenMapper.addSession(wsSession);

    var session = createReviewSession();
    var event = SessionEventBroadcaster.SessionEvent.started(session);

    // Error is logged, not propagated — and nothing is sent to connected clients
    assertDoesNotThrow(() -> broadcasterWithBrokenMapper.broadcast(event));
    verify(wsSession, never()).getAsyncRemote();
  }

  @Test
  void shouldSerializeSessionEventToJson() throws Exception {
    var session = createReviewSession();
    session.id = 42L;
    var event = SessionEventBroadcaster.SessionEvent.started(session);

    var json = mapper.writeValueAsString(event);

    assertTrue(json.contains("\"type\":\"review.started\""));
    assertTrue(json.contains("\"sessionId\":42"));
  }

  private void injectBufferedEvent(long sessionId, String latestType, String json) {
    broadcaster.seedReplayState(sessionId, latestType, FIXED_NOW, java.util.List.of(json), null);
  }

  private ReviewSession createReviewSession() {
    var session = new ReviewSession();
    session.id = 1L;
    session.setRepository("test/repo");
    session.setPrNumber(1);
    session.setPrTitle("Test");
    session.setCommitSha("sha");
    session.setTimestamp(java.time.Instant.parse("2025-06-01T12:00:00Z"));
    session.setModel("test-model");
    session.setStatus(ReviewSession.STATUS_IN_PROGRESS);
    return session;
  }
}
