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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * #645 — {@link BlockingStrictness#withheldByConfidence}, the predicate that names the case where a
 * finding's severity cleared the mode's bar and only its confidence kept it from blocking. The
 * gate's own outcomes are asserted alongside it, because the point of the predicate is that it
 * partitions the non-blocking findings without moving any of them.
 */
class BlockingStrictnessTest {

  private static Finding finding(RiskLevel risk, Confidence confidence) {
    return new Finding(risk, confidence, "a.java", 1, "t", "d", null, null);
  }

  @ParameterizedTest
  @CsvSource({
    // Hedged severity — the #645 case: would block on risk alone, confidence removes it.
    "BALANCED, CRITICAL, MEDIUM, true",
    "BALANCED, CRITICAL, LOW, true",
    "BALANCED, HIGH, MEDIUM, true",
    "BALANCED, HIGH, LOW, true",
    "LENIENT, CRITICAL, MEDIUM, true",
    "LENIENT, CRITICAL, LOW, true",
    // Confident enough to block: nothing was withheld.
    "BALANCED, CRITICAL, HIGH, false",
    "BALANCED, HIGH, HIGH, false",
    "LENIENT, CRITICAL, HIGH, false",
    // Severity, not confidence, is why these do not block.
    "BALANCED, MEDIUM, MEDIUM, false",
    "BALANCED, LOW, LOW, false",
    "LENIENT, HIGH, MEDIUM, false",
    "LENIENT, HIGH, HIGH, false",
    // STRICT has no confidence gate to withhold on.
    "STRICT, HIGH, LOW, false",
    "STRICT, CRITICAL, MEDIUM, false",
    "STRICT, MEDIUM, LOW, false"
  })
  void withheldByConfidenceIsolatesTheHedgeAsTheReason(
      BlockingStrictness mode, RiskLevel risk, Confidence confidence, boolean withheld) {
    var f = finding(risk, confidence);
    assertTrue(withheld == mode.withheldByConfidence(f), mode + " " + risk + "/" + confidence);
    // Withheld and blocking are mutually exclusive by construction: a withheld finding is one the
    // gate rejected, so a mode can never claim both about the same finding.
    assertFalse(mode.withheldByConfidence(f) && mode.isBlocking(f));
  }

  /**
   * The refactor that split the severity and confidence gates must not move any verdict: these are
   * the same outcomes the enum produced when each mode carried its own combined expression.
   */
  @ParameterizedTest
  @CsvSource({
    "BALANCED, CRITICAL, HIGH, true",
    "BALANCED, HIGH, HIGH, true",
    "BALANCED, HIGH, MEDIUM, false",
    "BALANCED, MEDIUM, HIGH, false",
    "STRICT, HIGH, LOW, true",
    "STRICT, CRITICAL, LOW, true",
    "STRICT, MEDIUM, HIGH, false",
    "LENIENT, CRITICAL, HIGH, true",
    "LENIENT, CRITICAL, LOW, false",
    "LENIENT, HIGH, HIGH, false"
  })
  void isBlockingKeepsItsPreSplitOutcomes(
      BlockingStrictness mode, RiskLevel risk, Confidence confidence, boolean blocks) {
    assertTrue(blocks == mode.isBlocking(finding(risk, confidence)));
  }

  @ParameterizedTest
  @EnumSource(BlockingStrictness.class)
  void neitherPredicateThrowsOnANullFinding(BlockingStrictness mode) {
    assertFalse(mode.isBlocking(null));
    assertFalse(mode.withheldByConfidence(null));
  }

  /** A finding predating the confidence field defaults to HIGH, so it still blocks (#105). */
  @Test
  void aPreConfidenceFindingIsNotTreatedAsWithheld() {
    var legacy = new Finding(RiskLevel.HIGH, "a.java", 1, "t", "d", null, null);
    assertTrue(BlockingStrictness.BALANCED.isBlocking(legacy));
    assertFalse(BlockingStrictness.BALANCED.withheldByConfidence(legacy));
  }
}
