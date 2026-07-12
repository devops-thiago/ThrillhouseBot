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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SessionCostBackfillTest extends ReviewSessionTestSupport {

  @Inject SessionCostBackfill backfill;

  @Test
  void shouldRecomputeCostForZeroCostSessionWithKnownModel() throws Exception {
    var id = persistUsage("deepseek-v4-pro", 1000, 1000, 0.0);

    var updated = backfill.backfill();

    assertTrue(updated >= 1, "at least the zero-cost session should be backfilled");
    ReviewSession loaded = ReviewSession.findById(id);
    assertEquals(0.00522, loaded.getCost(), 1e-9);
  }

  @Test
  void shouldNotTouchSessionsThatAlreadyHaveCost() throws Exception {
    var id = persistUsage("deepseek-v4-pro", 1000, 1000, 0.99);

    backfill.backfill();

    ReviewSession loaded = ReviewSession.findById(id);
    assertEquals(0.99, loaded.getCost(), 1e-9);
  }

  @Test
  void onStartShouldNoOpWhenNothingToBackfill() {
    assertDoesNotThrow(() -> backfill.onStart(mock(StartupEvent.class)));
  }

  @Test
  void shouldBackfillOnStartup() throws Exception {
    var id = persistUsage("deepseek-v4-pro", 1000, 1000, 0.0);

    backfill.onStart(mock(StartupEvent.class));

    ReviewSession loaded = ReviewSession.findById(id);
    assertEquals(0.00522, loaded.getCost(), 1e-9);
  }

  @Test
  void shouldLeaveUnknownModelsAtZero() throws Exception {
    var id = persistUsage("totally-unknown-model", 1000, 1000, 0.0);

    backfill.backfill();

    ReviewSession loaded = ReviewSession.findById(id);
    assertEquals(0.0, loaded.getCost(), 1e-9);
    assertTrue(loaded.isPricingMissing());
  }

  @Test
  void shouldClearTheMissingPricingFlagWhenTheCostIsBackfilled() throws Exception {
    var id = persistUsage("deepseek-v4-pro", 1000, 1000, 0.0, true);

    backfill.backfill();

    ReviewSession loaded = ReviewSession.findById(id);
    assertEquals(0.00522, loaded.getCost(), 1e-9);
    assertFalse(loaded.isPricingMissing());
  }

  @Test
  void shouldKeepTheMissingPricingFlagWhenTheModelIsStillUnpriced() throws Exception {
    var id = persistUsage("totally-unknown-model", 1000, 1000, 0.0, true);

    backfill.backfill();

    ReviewSession loaded = ReviewSession.findById(id);
    assertTrue(loaded.isPricingMissing());
  }

  private long persistUsage(String model, int input, int output, double cost) throws Exception {
    return persistUsage(model, input, output, cost, false);
  }

  private long persistUsage(
      String model, int input, int output, double cost, boolean pricingMissing) throws Exception {
    tx.begin();
    ReviewSession session = ReviewSession.create("owner/repo", 1, "PR", "sha");
    session.setModel(model);
    session.setInputTokens(input);
    session.setOutputTokens(output);
    session.setCost(cost);
    session.setPricingMissing(pricingMissing);
    session.setStatus(ReviewSession.STATUS_COMPLETED);
    session.persist();
    session.flush();
    tx.commit();
    return session.id;
  }
}
