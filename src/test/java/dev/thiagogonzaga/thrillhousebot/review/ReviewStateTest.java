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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ReviewStateTest {

  private static Finding finding(RiskLevel risk, Confidence confidence) {
    return new Finding(risk, confidence, "f", 1, "t", "d", null, null);
  }

  @Test
  void fromHighestRiskShouldApproveForNull() {
    assertEquals(ReviewState.APPROVE, ReviewState.fromHighestRisk(null));
  }

  @Test
  void fromHighestRiskShouldRequestChangesForCritical() {
    assertEquals(ReviewState.REQUEST_CHANGES, ReviewState.fromHighestRisk(RiskLevel.CRITICAL));
  }

  @Test
  void fromHighestRiskShouldRequestChangesForHigh() {
    assertEquals(ReviewState.REQUEST_CHANGES, ReviewState.fromHighestRisk(RiskLevel.HIGH));
  }

  @Test
  void fromHighestRiskShouldCommentForMedium() {
    assertEquals(ReviewState.COMMENT, ReviewState.fromHighestRisk(RiskLevel.MEDIUM));
  }

  @Test
  void fromHighestRiskShouldCommentForLow() {
    assertEquals(ReviewState.COMMENT, ReviewState.fromHighestRisk(RiskLevel.LOW));
  }

  @Test
  void checkRunConclusionShouldMapReviewStates() {
    assertEquals("success", ReviewState.APPROVE.checkRunConclusion());
    assertEquals("failure", ReviewState.REQUEST_CHANGES.checkRunConclusion());
    assertEquals("neutral", ReviewState.COMMENT.checkRunConclusion());
  }

  @Test
  void fromFindingsShouldApproveWhenEmptyOrNull() {
    assertEquals(ReviewState.APPROVE, ReviewState.fromFindings(null));
    assertEquals(ReviewState.APPROVE, ReviewState.fromFindings(List.of()));
    assertEquals(
        ReviewState.APPROVE, ReviewState.fromFindings(List.of(), BlockingStrictness.STRICT));
  }

  @Test
  void fromFindingsDefaultOverloadUsesBalanced() {
    var mediumConfidenceCritical = finding(RiskLevel.CRITICAL, Confidence.MEDIUM);
    assertEquals(ReviewState.COMMENT, ReviewState.fromFindings(List.of(mediumConfidenceCritical)));
    assertEquals(
        ReviewState.COMMENT,
        ReviewState.fromFindings(List.of(mediumConfidenceCritical), BlockingStrictness.BALANCED));
  }

  @ParameterizedTest
  @CsvSource({
    // mode, risk, confidence, expected REQUEST_CHANGES?
    "BALANCED, CRITICAL, HIGH, true",
    "BALANCED, HIGH, HIGH, true",
    "BALANCED, CRITICAL, MEDIUM, false",
    "BALANCED, HIGH, LOW, false",
    "BALANCED, MEDIUM, HIGH, false",
    "BALANCED, LOW, HIGH, false",
    "STRICT, CRITICAL, HIGH, true",
    "STRICT, HIGH, HIGH, true",
    "STRICT, CRITICAL, MEDIUM, true",
    "STRICT, HIGH, LOW, true",
    "STRICT, MEDIUM, HIGH, false",
    "STRICT, LOW, LOW, false",
    "LENIENT, CRITICAL, HIGH, true",
    "LENIENT, CRITICAL, MEDIUM, false",
    "LENIENT, HIGH, HIGH, false",
    "LENIENT, HIGH, LOW, false",
    "LENIENT, MEDIUM, HIGH, false",
  })
  void fromFindingsHonorsModeWithMixedConfidenceAndRisk(
      BlockingStrictness mode, RiskLevel risk, Confidence confidence, boolean blocks) {
    var state = ReviewState.fromFindings(List.of(finding(risk, confidence)), mode);
    assertEquals(blocks ? ReviewState.REQUEST_CHANGES : ReviewState.COMMENT, state);
  }

  @Test
  void fromFindingsShouldBlockWhenAnyFindingIsBlockingAmongNonBlocking() {
    var speculative = finding(RiskLevel.CRITICAL, Confidence.LOW);
    var verified = finding(RiskLevel.HIGH, Confidence.HIGH);
    assertEquals(
        ReviewState.REQUEST_CHANGES,
        ReviewState.fromFindings(List.of(speculative, verified), BlockingStrictness.BALANCED));
  }

  @Test
  void strictModeBlocksLowConfidenceCriticalThatBalancedWouldComment() {
    var hedged = finding(RiskLevel.CRITICAL, Confidence.LOW);
    assertEquals(
        ReviewState.COMMENT,
        ReviewState.fromFindings(List.of(hedged), BlockingStrictness.BALANCED));
    assertEquals(
        ReviewState.REQUEST_CHANGES,
        ReviewState.fromFindings(List.of(hedged), BlockingStrictness.STRICT));
  }

  @Test
  void lenientModeCommentsOnHighConfidenceHighThatBalancedWouldBlock() {
    var highRisk = finding(RiskLevel.HIGH, Confidence.HIGH);
    assertEquals(
        ReviewState.REQUEST_CHANGES,
        ReviewState.fromFindings(List.of(highRisk), BlockingStrictness.BALANCED));
    assertEquals(
        ReviewState.COMMENT,
        ReviewState.fromFindings(List.of(highRisk), BlockingStrictness.LENIENT));
  }

  @Test
  void nullStrictnessFallsBackToBalanced() {
    var hedged = finding(RiskLevel.CRITICAL, Confidence.MEDIUM);
    assertEquals(ReviewState.COMMENT, ReviewState.fromFindings(List.of(hedged), null));
  }

  @ParameterizedTest
  @EnumSource(BlockingStrictness.class)
  void isBlockingNeverTrueForNullFinding(BlockingStrictness mode) {
    assertFalse(mode.isBlocking(null));
  }

  @Test
  void fromStringParsesAllowedModesCaseInsensitively() {
    assertEquals(
        BlockingStrictness.BALANCED, BlockingStrictness.fromString("Balanced").orElseThrow());
    assertEquals(
        BlockingStrictness.STRICT, BlockingStrictness.fromString(" STRICT ").orElseThrow());
    assertEquals(
        BlockingStrictness.LENIENT, BlockingStrictness.fromString("lenient").orElseThrow());
  }

  @Test
  void fromStringRejectsUnknownAndBlank() {
    assertTrue(BlockingStrictness.fromString("aggressive").isEmpty());
    assertTrue(BlockingStrictness.fromString("").isEmpty());
    assertTrue(BlockingStrictness.fromString(null).isEmpty());
  }
}
