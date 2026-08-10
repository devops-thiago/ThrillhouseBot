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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReviewTokenLedger} — the per-review token spend accumulator and the {@code
 * REVIEW_MAX_TOKENS_PER_REVIEW} ceiling's enforcement point (#499).
 */
class ReviewTokenLedgerTest {

  private static ReviewTokenLedger ledger(long maxTokensPerReview) {
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(review);
    when(review.maxTokensPerReview()).thenReturn(maxTokensPerReview);
    return new ReviewTokenLedger(config);
  }

  @Test
  void accumulatesInputAndOutputTokensPerOpenSession() {
    var ledger = ledger(0);
    ledger.open(1L);
    ledger.open(2L);

    ledger.record(1L, 100, 50);
    ledger.record(1L, 200, 25);
    ledger.record(2L, 7, 3);

    assertEquals(375L, ledger.tokensSpent(1L));
    assertEquals(10L, ledger.tokensSpent(2L));
  }

  @Test
  void treatsNullUsageCountsAsZero() {
    var ledger = ledger(0);
    ledger.open(1L);

    ledger.record(1L, null, 40);
    ledger.record(1L, 60, null);

    assertEquals(100L, ledger.tokensSpent(1L));
  }

  @Test
  void dropsUsageForASessionThatWasNeverOpenedOrAlreadyCleared() {
    // A stale provider callback landing after its review finished must not re-create a ledger row
    // that nothing would ever clean up.
    var ledger = ledger(0);
    ledger.record(9L, 100, 50);
    assertEquals(0L, ledger.tokensSpent(9L));

    ledger.open(9L);
    ledger.record(9L, 100, 50);
    ledger.clear(9L);
    ledger.record(9L, 100, 50);
    assertEquals(0L, ledger.tokensSpent(9L));
  }

  @Test
  void ceilingIsReachedAtOrAboveTheConfiguredValue() {
    var ledger = ledger(1000);
    ledger.open(1L);

    ledger.record(1L, 600, 300);
    assertFalse(ledger.ceilingReached(1L), "900 < 1000 must not trip the ceiling");

    ledger.record(1L, 100, 0);
    assertTrue(ledger.ceilingReached(1L), "exactly the ceiling means the budget is consumed");

    ledger.record(1L, 5000, 0);
    assertTrue(ledger.ceilingReached(1L));
  }

  @Test
  void aDisabledCeilingIsNeverReached() {
    var ledger = ledger(0);
    ledger.open(1L);
    ledger.record(1L, 900_000, 100_000);

    assertFalse(ledger.ceilingReached(1L));
    assertDoesNotThrow(() -> ledger.ensureCallAllowed(1L));
  }

  @Test
  void ensureCallAllowedThrowsTheTypedErrorNamingTheKnobOnceReached() {
    var ledger = ledger(1000);
    ledger.open(1L);
    ledger.record(1L, 800, 200);

    var thrown =
        assertThrows(TokenSpendCeilingExceededException.class, () -> ledger.ensureCallAllowed(1L));

    assertEquals(1000L, thrown.tokensSpent());
    assertEquals(1000L, thrown.ceiling());
    assertTrue(thrown.getMessage().contains("REVIEW_MAX_TOKENS_PER_REVIEW"), thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains("thrillhousebot.review.max-tokens-per-review"),
        thrown.getMessage());
  }

  @Test
  void ensureCallAllowedIsANoOpBelowTheCeiling() {
    var ledger = ledger(1000);
    ledger.open(1L);
    ledger.record(1L, 500, 400);

    assertDoesNotThrow(() -> ledger.ensureCallAllowed(1L));
    assertEquals(1000L, ledger.ceiling());
  }
}
