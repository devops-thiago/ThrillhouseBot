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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReviewStateTest {

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
    assertEquals(ReviewState.APPROVE, ReviewState.fromFindings(java.util.List.of()));
  }

  @Test
  void fromFindingsShouldRequestChangesForHighConfidenceCritical() {
    var finding = new Finding(RiskLevel.CRITICAL, Confidence.HIGH, "f", 1, "t", "d", null, null);
    assertEquals(ReviewState.REQUEST_CHANGES, ReviewState.fromFindings(java.util.List.of(finding)));
  }

  @Test
  void fromFindingsShouldRequestChangesForHighConfidenceHigh() {
    var finding = new Finding(RiskLevel.HIGH, Confidence.HIGH, "f", 1, "t", "d", null, null);
    assertEquals(ReviewState.REQUEST_CHANGES, ReviewState.fromFindings(java.util.List.of(finding)));
  }

  @Test
  void fromFindingsShouldOnlyCommentForLowConfidenceCritical() {
    var finding = new Finding(RiskLevel.CRITICAL, Confidence.MEDIUM, "f", 1, "t", "d", null, null);
    assertEquals(ReviewState.COMMENT, ReviewState.fromFindings(java.util.List.of(finding)));
  }

  @Test
  void fromFindingsShouldCommentForHighConfidenceMedium() {
    var finding = new Finding(RiskLevel.MEDIUM, Confidence.HIGH, "f", 1, "t", "d", null, null);
    assertEquals(ReviewState.COMMENT, ReviewState.fromFindings(java.util.List.of(finding)));
  }

  @Test
  void fromFindingsShouldBlockWhenAnyFindingIsBlockingAmongNonBlocking() {
    var speculative = new Finding(RiskLevel.CRITICAL, Confidence.LOW, "f", 1, "t", "d", null, null);
    var verified = new Finding(RiskLevel.HIGH, Confidence.HIGH, "g", 2, "t", "d", null, null);
    assertEquals(
        ReviewState.REQUEST_CHANGES,
        ReviewState.fromFindings(java.util.List.of(speculative, verified)));
  }
}
