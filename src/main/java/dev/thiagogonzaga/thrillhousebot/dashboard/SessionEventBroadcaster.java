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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SessionEventBroadcaster {

  private static final Logger log = LoggerFactory.getLogger(SessionEventBroadcaster.class);
  private static final String KEY_STATUS = "status";
  private static final String KEY_ATTEMPT = "attempt";
  private static final int MAX_BUFFERED_EVENTS_PER_SESSION = 500;
  static final Duration MAX_SESSION_BUFFER_AGE = Duration.ofHours(1);

  private Clock clock = Clock.systemUTC();

  private final Set<jakarta.websocket.Session> sessions = ConcurrentHashMap.newKeySet();

  private final ConcurrentHashMap<Long, SessionState> sessionStateById = new ConcurrentHashMap<>();

  private final ObjectMapper mapper;

  @Inject
  public SessionEventBroadcaster(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void addSession(jakarta.websocket.Session session) {
    sessions.add(session);
    replayActiveSessions(session);
    log.debug("WebSocket client connected (total: {})", sessions.size());
  }

  public void removeSession(jakarta.websocket.Session session) {
    sessions.remove(session);
    log.debug("WebSocket client disconnected (total: {})", sessions.size());
  }

  public void broadcast(SessionEvent event) {
    var json = serialize(event);
    if (json == null) {
      return;
    }

    bufferEvent(event, json);

    if (sessions.isEmpty()) {
      return;
    }

    sendTextToAll(json);
  }

  /** Application-level heartbeat for connected dashboard clients. */
  public void sendKeepAlive() {
    if (sessions.isEmpty()) {
      return;
    }
    sendTextToAll("{\"type\":\"keepalive\"}");
  }

  /** Evicts replay buffers for sessions with no recent activity and no terminal event. */
  public void evictStaleSessionBuffers() {
    var cutoff = Instant.now(clock).minus(MAX_SESSION_BUFFER_AGE);
    sessionStateById.entrySet().removeIf(entry -> entry.getValue().lastActivityAt.isBefore(cutoff));
  }

  private String serialize(SessionEvent event) {
    try {
      return mapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize session event", e);
      return null;
    }
  }

  private void bufferEvent(SessionEvent event, String json) {
    if (!event.type().startsWith("review.")) {
      return;
    }

    Instant now = Instant.now(clock);
    var state = sessionStateById.computeIfAbsent(event.sessionId(), id -> new SessionState(now));
    state.latestEventType = event.type();
    state.lastActivityAt = now;

    if (SessionEvent.TYPE_STREAM.equals(event.type())) {
      state.latestStreamJson = json;
      return;
    }

    if (SessionEvent.TYPE_RETRY.equals(event.type())
        || SessionEvent.TYPE_STREAM_FAILED.equals(event.type())) {
      state.latestStreamJson = null;
    }

    Deque<String> buffer = state.buffer;
    synchronized (buffer) {
      buffer.addLast(json);
      while (buffer.size() > MAX_BUFFERED_EVENTS_PER_SESSION) {
        buffer.removeFirst();
      }
    }

    if (isTerminalReviewEvent(event.type())) {
      sessionStateById.remove(event.sessionId());
    }
  }

  private static boolean isTerminalReviewEvent(String eventType) {
    return SessionEvent.TYPE_COMPLETED.equals(eventType)
        || SessionEvent.TYPE_FAILED.equals(eventType);
  }

  private void replayActiveSessions(jakarta.websocket.Session wsSession) {
    if (!wsSession.isOpen()) {
      return;
    }

    for (var entry : sessionStateById.entrySet()) {
      var state = entry.getValue();
      var latestType = state.latestEventType;
      if (latestType == null || isTerminalReviewEvent(latestType)) {
        continue;
      }

      Deque<String> buffer = state.buffer;
      synchronized (buffer) {
        for (String json : buffer) {
          sendText(wsSession, json);
        }
      }

      if (state.latestStreamJson != null) {
        sendText(wsSession, state.latestStreamJson);
      }
    }
  }

  private void sendTextToAll(String json) {
    sessions.removeIf(
        session -> {
          if (!session.isOpen()) {
            return true;
          }
          sendText(session, json);
          return false;
        });
  }

  private void sendText(jakarta.websocket.Session session, String json) {
    session
        .getAsyncRemote()
        .sendText(
            json,
            result -> {
              if (result.getException() != null) {
                log.debug("Failed to send WS message: {}", result.getException().getMessage());
              }
            });
  }

  public int getConnectedCount() {
    return sessions.size();
  }

  /** Pins activity timestamps and the eviction cutoff to a deterministic clock for tests. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  /** Seeds replay buffer state for tests without exposing the backing map. */
  void seedReplayState(
      long sessionId,
      String latestEventType,
      Instant lastActivityAt,
      List<String> bufferedEventJson,
      String latestStreamJson) {
    var state = new SessionState(lastActivityAt);
    state.latestEventType = latestEventType;
    state.lastActivityAt = lastActivityAt;
    state.latestStreamJson = latestStreamJson;
    Deque<String> buffer = state.buffer;
    synchronized (buffer) {
      buffer.addAll(bufferedEventJson);
    }
    sessionStateById.put(sessionId, state);
  }

  /** Visible for tests that assert replay buffer lifecycle. */
  int replayStateCount() {
    return sessionStateById.size();
  }

  /** Per-session replay state tracked while a review is in progress. */
  static final class SessionState {
    final Deque<String> buffer = new ArrayDeque<>();
    String latestEventType;
    Instant lastActivityAt;
    String latestStreamJson;

    SessionState(Instant createdAt) {
      this.lastActivityAt = createdAt;
    }
  }

  /** Events broadcast to WebSocket clients. */
  @RegisterForReflection
  public record SessionEvent(
      String type, // review.started, review.stream, review.retry, review.completed, review.failed
      long sessionId,
      String repository,
      int prNumber,
      String prTitle,
      String commitSha,
      String timestamp,
      Map<String, Object> data) {
    public static final String TYPE_STREAM = "review.stream";
    public static final String TYPE_RETRY = "review.retry";
    public static final String TYPE_STREAM_FAILED = "review.stream.failed";
    public static final String TYPE_COMPLETED = "review.completed";
    public static final String TYPE_FAILED = "review.failed";

    public SessionEvent {
      data = Map.copyOf(data);
    }

    public static SessionEvent started(ReviewSession session) {
      return new SessionEvent(
          "review.started",
          session.id,
          session.getRepository(),
          session.getPrNumber(),
          session.getPrTitle(),
          session.getCommitSha(),
          session.getTimestamp().toString(),
          Map.of(KEY_STATUS, "in_progress"));
    }

    public static SessionEvent stream(
        ReviewSession session,
        String chunk,
        String tail,
        int totalChars,
        int chunkIndex,
        int attempt) {
      return new SessionEvent(
          TYPE_STREAM,
          session.id,
          session.getRepository(),
          session.getPrNumber(),
          session.getPrTitle(),
          session.getCommitSha(),
          session.getTimestamp().toString(),
          Map.of(
              "chunk",
              chunk,
              "tail",
              tail,
              "totalChars",
              totalChars,
              "chunkIndex",
              chunkIndex,
              KEY_ATTEMPT,
              attempt,
              KEY_STATUS,
              "streaming"));
    }

    public static SessionEvent retry(
        ReviewSession session, int attempt, int maxAttempts, String reason) {
      return new SessionEvent(
          TYPE_RETRY,
          session.id,
          session.getRepository(),
          session.getPrNumber(),
          session.getPrTitle(),
          session.getCommitSha(),
          session.getTimestamp().toString(),
          Map.of(
              KEY_ATTEMPT,
              attempt,
              "maxAttempts",
              maxAttempts,
              "reason",
              reason,
              KEY_STATUS,
              "retrying"));
    }

    public static SessionEvent streamFailed(
        ReviewSession session, int attempt, int maxAttempts, String reason) {
      return new SessionEvent(
          TYPE_STREAM_FAILED,
          session.id,
          session.getRepository(),
          session.getPrNumber(),
          session.getPrTitle(),
          session.getCommitSha(),
          session.getTimestamp().toString(),
          Map.of(
              KEY_ATTEMPT,
              attempt,
              "maxAttempts",
              maxAttempts,
              "reason",
              reason,
              KEY_STATUS,
              "stream_failed"));
    }

    public static SessionEvent progress(ReviewSession session, int findingsSoFar, long tokensUsed) {
      return new SessionEvent(
          "review.progress",
          session.id,
          session.getRepository(),
          session.getPrNumber(),
          session.getPrTitle(),
          session.getCommitSha(),
          session.getTimestamp().toString(),
          Map.of(
              "findingsSoFar", findingsSoFar, "tokensUsed", tokensUsed, KEY_STATUS, "in_progress"));
    }

    public static SessionEvent completed(ReviewSession session) {
      return new SessionEvent(
          TYPE_COMPLETED,
          session.id,
          session.getRepository(),
          session.getPrNumber(),
          session.getPrTitle(),
          session.getCommitSha(),
          session.getTimestamp().toString(),
          Map.of(
              "critical",
              session.getCriticalFindings(),
              "high",
              session.getHighFindings(),
              "medium",
              session.getMediumFindings(),
              "low",
              session.getLowFindings(),
              "totalTokens",
              session.getInputTokens() + session.getOutputTokens(),
              "inputTokens",
              session.getInputTokens(),
              "outputTokens",
              session.getOutputTokens(),
              "cost",
              session.getCost(),
              "durationMs",
              session.getDurationMs(),
              KEY_STATUS,
              "completed"));
    }

    public static SessionEvent failed(ReviewSession session) {
      return new SessionEvent(
          TYPE_FAILED,
          session.id,
          session.getRepository(),
          session.getPrNumber(),
          session.getPrTitle(),
          session.getCommitSha(),
          session.getTimestamp().toString(),
          Map.of(
              "errorType",
              session.getErrorMessage() != null ? session.getErrorMessage() : "Unknown",
              "durationMs",
              session.getDurationMs(),
              KEY_STATUS,
              "failed"));
    }
  }
}
