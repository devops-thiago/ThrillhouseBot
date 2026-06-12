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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardSessionStoreTest {

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-06-09T12:00:00Z"));

  private DashboardSessionStore store;

  @BeforeEach
  void setUp() {
    store = new DashboardSessionStore(now::get);
  }

  @Test
  void createSessionShouldReturnOpaqueId() {
    var sessionId = store.createSession("octocat", "gho_secret", "https://a.co", "Octocat");

    assertNotNull(sessionId);
    assertFalse(sessionId.isBlank());
    assertNotEquals("gho_secret", sessionId);
  }

  @Test
  void findSessionShouldReturnStoredProfile() {
    var sessionId = store.createSession("octocat", "gho_secret", "https://a.co", "Octocat");

    var session = store.findSession(sessionId);
    assertTrue(session.isPresent());
    assertEquals("octocat", session.get().login());
    assertEquals("gho_secret", session.get().accessToken());
    assertEquals("https://a.co", session.get().avatarUrl());
    assertEquals("Octocat", session.get().name());
  }

  @Test
  void findSessionShouldRejectUnknownId() {
    assertTrue(store.findSession("unknown-id").isEmpty());
  }

  @Test
  void findSessionShouldRejectNullAndBlankIds() {
    assertTrue(store.findSession(null).isEmpty());
    assertTrue(store.findSession("   ").isEmpty());
  }

  @Test
  void findSessionShouldRejectExpiredSessions() {
    var sessionId = store.createSession("octocat", "gho_secret", "https://a.co", "Octocat");
    now.set(now.get().plus(DashboardSessionStore.SESSION_TTL).plusMillis(1));

    assertTrue(store.findSession(sessionId).isEmpty());
    assertTrue(store.findSession(sessionId).isEmpty());
  }

  @Test
  void invalidateShouldRemoveSession() {
    var sessionId = store.createSession("octocat", "gho_secret", "https://a.co", "Octocat");

    store.invalidate(sessionId);

    assertTrue(store.findSession(sessionId).isEmpty());
  }

  @Test
  void invalidateShouldIgnoreNullAndBlankIds() {
    var sessionId = store.createSession("octocat", "gho_secret", "https://a.co", "Octocat");

    store.invalidate(null);
    store.invalidate("   ");

    // Existing sessions must survive a no-op invalidation
    assertTrue(store.findSession(sessionId).isPresent());
  }

  @Test
  void productionConstructorShouldUseSystemClock() {
    var productionStore = new DashboardSessionStore();
    var sessionId = productionStore.createSession("user", "token", "https://a.co", "User");

    assertTrue(productionStore.findSession(sessionId).isPresent());
  }

  @Test
  void createSessionShouldSweepExpiredEntriesWhenThresholdReached() {
    var half = DashboardSessionStore.SWEEP_THRESHOLD / 2;
    for (var i = 0; i < half; i++) {
      store.createSession("old-" + i, "token", "https://a.co", "Old");
    }
    now.set(now.get().plus(DashboardSessionStore.SESSION_TTL).plusMillis(1));
    String freshSessionId = null;
    for (var i = 0; i < half; i++) {
      freshSessionId = store.createSession("fresh-" + i, "token", "https://a.co", "Fresh");
    }

    store.createSession("trigger", "token", "https://a.co", "Trigger");

    assertTrue(store.findSession(freshSessionId).isPresent());
  }
}
