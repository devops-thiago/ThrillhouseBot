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

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ReviewSessionUpdaterTest extends ReviewSessionTestSupport {

  @Inject ReviewSessionUpdater updater;

  @Test
  void shouldRecordModelUsageOnExistingSession() throws Exception {
    var session = persistSession();

    updater.recordModelUsage(session.id, "deepseek-chat", 120, 80, 0.42, false, 1500);

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertEquals("deepseek-chat", loaded.getModel());
    assertEquals(120, loaded.getInputTokens());
    assertEquals(80, loaded.getOutputTokens());
    assertEquals(0.42, loaded.getCost(), 0.001);
    assertFalse(loaded.isPricingMissing());
    assertEquals(1500, loaded.getDurationMs());
  }

  @Test
  void shouldFlagMissingPricingOnTheSession() throws Exception {
    var session = persistSession();

    updater.recordModelUsage(session.id, "unknown-model", 120, 80, 0.0, true, 1500);

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertTrue(loaded.isPricingMissing());
    assertEquals(0.0, loaded.getCost(), 0.001);
    assertEquals(120, loaded.getInputTokens());
    assertEquals(80, loaded.getOutputTokens());
  }

  @Test
  void shouldRecordFailureOnExistingSession() throws Exception {
    var session = persistSession();

    updater.recordFailure(session.id, "rate limited", 900);

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertEquals(ReviewSession.STATUS_FAILED, loaded.getStatus());
    assertEquals("rate limited", loaded.getErrorMessage());
    assertEquals(900, loaded.getDurationMs());
  }

  @Test
  void shouldIgnoreMissingSessionId() {
    assertDoesNotThrow(() -> updater.recordModelUsage(999_999L, "model", 1, 1, 0.0, false, 1));
    assertDoesNotThrow(() -> updater.recordFailure(999_999L, "gone", 1));

    assertEquals(0, ReviewSession.count());
  }

  private ReviewSession persistSession() throws Exception {
    tx.begin();
    ReviewSession session = ReviewSession.create("owner/repo", 3, "PR", "abc");
    session.persist();
    session.flush();
    tx.commit();
    return session;
  }
}
