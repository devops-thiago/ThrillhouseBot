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

import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ReviewSessionUpdaterTest extends ReviewSessionTestSupport {

  @Inject ReviewSessionUpdater updater;

  @Test
  void shouldRecordModelUsageOnExistingSession() throws Exception {
    var session = persistSession();

    updater.recordModelUsage(session.id, "deepseek-chat", 120, 80, 0.42, false);

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertEquals("deepseek-chat", loaded.getModel());
    assertEquals(120, loaded.getInputTokens());
    assertEquals(80, loaded.getOutputTokens());
    assertEquals(0.42, loaded.getCost(), 0.001);
    assertFalse(loaded.isPricingMissing());
    assertEquals(0, loaded.getDurationMs(), "a call's time is not the review's (#1417)");
  }

  @Test
  void recordingUsageReturnsTheSessionWithItsRunningTotals() throws Exception {
    var session = persistSession();
    updater.recordModelUsage(session.id, "deepseek-chat", 100, 20, 0.01, false);

    var updated = updater.recordModelUsage(session.id, "deepseek-chat", 50, 10, 0.02, true);

    assertEquals(150, updated.getInputTokens());
    assertEquals(30, updated.getOutputTokens());
    assertEquals(0.03, updated.getCost(), 0.001);
    assertTrue(updated.isPricingMissing());
  }

  @Test
  void recordingUsageForAMissingSessionReturnsNothing() {
    assertNull(updater.recordModelUsage(-404L, "deepseek-chat", 1, 1, 0.0, false));
  }

  @Test
  void shouldAccumulateModelUsageAcrossMultipleCalls() throws Exception {
    var session = persistSession();

    updater.recordModelUsage(session.id, "deepseek-chat", 1000, 200, 0.10, false);
    updater.recordModelUsage(session.id, "deepseek-chat", 800, 150, 0.08, false);
    updater.recordModelUsage(session.id, "deepseek-chat", 100, 50, 0.01, false);

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertEquals("deepseek-chat", loaded.getModel());
    assertEquals(1900, loaded.getInputTokens());
    assertEquals(400, loaded.getOutputTokens());
    assertEquals(0.19, loaded.getCost(), 0.001);
    assertFalse(loaded.isPricingMissing());
    // #1417: parallel and retried calls overlap, so their times are never summed into the row.
    assertEquals(0, loaded.getDurationMs());
  }

  @Test
  void shouldKeepPricingMissingFlagWhenAnyCallLacksPricing() throws Exception {
    var session = persistSession();

    updater.recordModelUsage(session.id, "deepseek-chat", 100, 50, 0.05, false);
    updater.recordModelUsage(session.id, "unknown-model", 120, 80, 0.0, true);
    updater.recordModelUsage(session.id, "deepseek-chat", 50, 20, 0.02, false);

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertTrue(loaded.isPricingMissing());
    assertEquals(270, loaded.getInputTokens());
    assertEquals(150, loaded.getOutputTokens());
    assertEquals(0.07, loaded.getCost(), 0.001);
  }

  @Test
  void shouldFlagMissingPricingOnTheSession() throws Exception {
    var session = persistSession();

    updater.recordModelUsage(session.id, "unknown-model", 120, 80, 0.0, true);

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertTrue(loaded.isPricingMissing());
    assertEquals(0.0, loaded.getCost(), 0.001);
    assertEquals(120, loaded.getInputTokens());
    assertEquals(80, loaded.getOutputTokens());
  }

  @Test
  void aFailedCallIsCountedWithoutFailingTheSession() throws Exception {
    // #1417: the call may be retried and the review may still complete, so a failed call leaves
    // the status, the message and the duration to the review's final outcome.
    var session = persistSession();

    updater.recordFailedModelCall(session.id);

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertEquals(ReviewSession.STATUS_IN_PROGRESS, loaded.getStatus());
    assertNull(loaded.getErrorMessage());
    assertEquals(0, loaded.getDurationMs());
    assertEquals(1, loaded.getFailedModelCalls());
  }

  @Test
  void failedCallsAccumulateBetweenSuccessfulOnes() throws Exception {
    var session = persistSession();

    updater.recordModelUsage(session.id, "deepseek-chat", 500, 100, 0.05, false);
    updater.recordFailedModelCall(session.id);
    updater.recordModelUsage(session.id, "deepseek-chat", 300, 50, 0.03, false);
    updater.recordFailedModelCall(session.id);

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertEquals(ReviewSession.STATUS_IN_PROGRESS, loaded.getStatus());
    assertNull(loaded.getErrorMessage());
    assertEquals(2, loaded.getFailedModelCalls());
    assertEquals(800, loaded.getInputTokens());
  }

  @Test
  void aFailedCallCountedAfterTheReviewCompletedLeavesItCompleted() throws Exception {
    // #1417: the listener writes on a worker thread, so a failed call's write can land after the
    // review's final status. It must not turn the completed row into a failed one.
    var session = persistSession();
    tx.begin();
    ReviewSession completed = ReviewSession.findById(session.id);
    completed.setStatus(ReviewSession.STATUS_COMPLETED);
    completed.setDurationMs(4_000L);
    tx.commit();

    updater.recordFailedModelCall(session.id);

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertEquals(ReviewSession.STATUS_COMPLETED, loaded.getStatus());
    assertNull(loaded.getErrorMessage());
    assertEquals(4_000L, loaded.getDurationMs());
    assertEquals(1, loaded.getFailedModelCalls());
  }

  @Test
  void shouldIgnoreMissingSessionId() {
    assertDoesNotThrow(() -> updater.recordModelUsage(999_999L, "model", 1, 1, 0.0, false));
    assertDoesNotThrow(() -> updater.recordFailedModelCall(999_999L));

    assertEquals(0, ReviewSession.count());
  }

  private ReviewSession persistSession() throws Exception {
    tx.begin();
    ReviewSession session =
        ReviewSession.create(TestScopes.scope("owner/repo"), "owner/repo", 3, "PR", "abc");
    session.persist();
    session.flush();
    tx.commit();
    return session;
  }
}
