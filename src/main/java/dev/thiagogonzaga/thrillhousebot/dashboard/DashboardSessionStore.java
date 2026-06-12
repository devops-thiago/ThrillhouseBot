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

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  record DashboardSession(
      String login, String accessToken, String avatarUrl, String name, Instant expiresAt) {}

  private final ConcurrentHashMap<String, DashboardSession> sessions = new ConcurrentHashMap<>();
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
    byte[] idBytes = new byte[32];
    SECURE_RANDOM.nextBytes(idBytes);
    var sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes);
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

  public void invalidate(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      sessions.remove(sessionId);
    }
  }

  private void sweepExpired() {
    if (sessions.size() < SWEEP_THRESHOLD) {
      return;
    }
    var now = clock.get();
    sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
  }
}
