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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardSessionValidatorTest {

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-06-09T12:00:00Z"));

  private DashboardSessionStore sessionStore;
  private DashboardAccessChecker accessChecker;
  private DashboardSessionValidator validator;

  @BeforeEach
  void setUp() {
    sessionStore = new DashboardSessionStore(now::get);
    accessChecker = mock(DashboardAccessChecker.class);
    when(accessChecker.hasAccess(anyString())).thenReturn(true);
    validator = new DashboardSessionValidator(sessionStore, accessChecker);
  }

  @Test
  void shouldResolveLoginFromSessionStore() {
    var sessionId = sessionStore.createSession("octocat", "gho_secret", "https://a.co", "Octocat");

    assertEquals(Optional.of("octocat"), validator.resolveLogin(sessionId));
    assertTrue(validator.isValidSession(sessionId));
  }

  @Test
  void shouldRejectUnknownSessionId() {
    assertTrue(validator.resolveLogin("unknown").isEmpty());
    assertFalse(validator.isValidSession("unknown"));
  }

  @Test
  void shouldRejectNullAndBlankSessionIds() {
    assertTrue(validator.resolveLogin(null).isEmpty());
    assertTrue(validator.resolveLogin("   ").isEmpty());
    assertFalse(validator.isValidSession(null));
  }

  @Test
  void shouldRejectExpiredSession() {
    var sessionId = sessionStore.createSession("octocat", "gho_secret", "https://a.co", "Octocat");
    now.set(now.get().plus(DashboardSessionStore.SESSION_TTL).plusMillis(1));

    assertTrue(validator.resolveLogin(sessionId).isEmpty());
  }

  @Test
  void shouldRejectWhenAccessCheckerDenies() {
    var sessionId = sessionStore.createSession("outsider", "gho_secret", "https://a.co", "Out");
    when(accessChecker.hasAccess("outsider")).thenReturn(false);

    assertTrue(validator.resolveLogin(sessionId).isEmpty());
  }

  @Test
  void shouldRejectAfterSessionInvalidated() {
    var sessionId = sessionStore.createSession("octocat", "gho_secret", "https://a.co", "Octocat");
    sessionStore.invalidate(sessionId);

    assertTrue(validator.resolveLogin(sessionId).isEmpty());
  }
}
