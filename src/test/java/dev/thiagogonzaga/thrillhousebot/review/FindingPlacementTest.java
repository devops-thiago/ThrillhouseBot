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
package dev.thiagogonzaga.thrillhousebot.review;

import static dev.thiagogonzaga.thrillhousebot.review.FindingPlacement.INLINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #1077: the one severity-by-confidence table that places a finding inline or under "Things to
 * double-check", every cell of it, and that {@link Finding#postsInline} reads it.
 */
class FindingPlacementTest {

  @ParameterizedTest(name = "{0} risk at {1} confidence is {2}")
  @CsvSource({
    "CRITICAL, HIGH,   INLINE",
    "CRITICAL, MEDIUM, INLINE",
    "CRITICAL, LOW,    INLINE",
    "HIGH,     HIGH,   INLINE",
    "HIGH,     MEDIUM, INLINE",
    "HIGH,     LOW,    INLINE",
    "MEDIUM,   HIGH,   INLINE",
    "MEDIUM,   MEDIUM, INLINE",
    "MEDIUM,   LOW,    DOUBLE_CHECK",
    "LOW,      HIGH,   INLINE",
    "LOW,      MEDIUM, INLINE",
    "LOW,      LOW,    DOUBLE_CHECK",
  })
  void everyCellOfTheTable(RiskLevel risk, Confidence confidence, FindingPlacement expected) {
    assertEquals(expected, FindingPlacement.of(risk, confidence));
    var finding = new Finding(risk, confidence, "a.java", 1, "t", "d", null, null);
    assertEquals(expected == INLINE, finding.postsInline());
  }

  /** A finding with no risk is placed as a low-risk one, the level a missing label parses to. */
  @Test
  void aFindingWithNoRiskIsPlacedAsLowRisk() {
    assertFalse(new Finding(null, Confidence.LOW, "a.java", 1, "t", "d", null, null).postsInline());
    assertTrue(
        new Finding(null, Confidence.MEDIUM, "a.java", 1, "t", "d", null, null).postsInline());
  }
}
