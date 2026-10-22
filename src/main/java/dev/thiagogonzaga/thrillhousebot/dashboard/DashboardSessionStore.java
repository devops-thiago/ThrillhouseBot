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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Server-side store for opaque dashboard sessions. GitHub tokens never leave the server. */
@ApplicationScoped
public class DashboardSessionStore {

  static final Duration SESSION_TTL = Duration.ofHours(8);
  static final int SESSION_MAX_AGE_SECONDS = (int) SESSION_TTL.toSeconds();
  static final int SWEEP_THRESHOLD = 1_000;

  /**
   * How long a WebSocket ticket can wait for its socket. The dashboard opens the socket right after
   * fetching the ticket, so this only has to cover one round trip (#1223).
   */
  static final Duration SOCKET_TICKET_TTL = Duration.ofSeconds(30);

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  record DashboardSession(
      String login, String accessToken, String avatarUrl, String name, Instant expiresAt) {}

  /** A single-use ticket that hands one WebSocket handshake the session that fetched it. */
  private record SocketTicket(String sessionId, Instant expiresAt) {}

  private final ConcurrentHashMap<String, DashboardSession> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, SocketTicket> socketTickets = new ConcurrentHashMap<>();
  private final Supplier<Instant> clock;

  @Inject
  public DashboardSessionStore() {
    this(Instant::now);
  }

  /** Visible for tests: allows controlling session expiry. */
  DashboardSessionStore(Supplier<Instant> clock) {
    this.clock = clock;
  }

  public String createSession(
      String login, String accessToken, String avatarUrl, String displayName) {
    var sessionId = randomToken();
    var expiresAt = clock.get().plus(SESSION_TTL);
    String safeAvatar = avatarUrl != null ? avatarUrl : "";
    String safeName = displayName != null && !displayName.isBlank() ? displayName : login;
    sessions.put(
        sessionId, new DashboardSession(login, accessToken, safeAvatar, safeName, expiresAt));
    sweepExpired();
    return sessionId;
  }

  public Optional<DashboardSession> findSession(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    var session = sessions.get(sessionId);
    if (session == null) {
      return Optional.empty();
    }
    if (!session.expiresAt().isAfter(clock.get())) {
      sessions.remove(sessionId);
      return Optional.empty();
    }
    return Optional.of(session);
  }

  /**
   * Issues a single-use ticket for the WebSocket handshake of a live session. A browser cannot set
   * headers on a WebSocket, and the endpoint configuration is shared by every handshake, so the
   * socket proves its session with this ticket in its query string instead of a cookie copied
   * through shared state (#1223). The caller has found the session; the socket checks it again when
   * it redeems the ticket, so a session that ends in between opens nothing.
   */
  public String issueSocketTicket(String sessionId) {
    var ticket = randomToken();
    socketTickets.put(ticket, new SocketTicket(sessionId, clock.get().plus(SOCKET_TICKET_TTL)));
    sweepExpiredTickets();
    return ticket;
  }

  /**
   * Redeems a ticket for the session that requested it. The ticket is removed on first use, so a
   * second socket presenting it gets nothing, and an expired ticket is refused.
   */
  public Optional<String> redeemSocketTicket(String ticket) {
    if (ticket == null || ticket.isBlank()) {
      return Optional.empty();
    }
    var issued = socketTickets.remove(ticket);
    if (issued == null || !issued.expiresAt().isAfter(clock.get())) {
      return Optional.empty();
    }
    return Optional.of(issued.sessionId());
  }

  /** Visible for tests that assert the ticket map stays bounded. */
  int socketTicketCount() {
    return socketTickets.size();
  }

  public void invalidate(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      sessions.remove(sessionId);
    }
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private void sweepExpiredTickets() {
    if (socketTickets.size() < SWEEP_THRESHOLD) {
      return;
    }
    var now = clock.get();
    socketTickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
  }

  private void sweepExpired() {
    if (sessions.size() < SWEEP_THRESHOLD) {
      return;
    }
    var now = clock.get();
    sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
  }
}
