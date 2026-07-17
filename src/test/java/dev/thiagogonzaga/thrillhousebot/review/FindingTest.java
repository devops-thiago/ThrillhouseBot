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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import org.junit.jupiter.api.Test;

class FindingTest {

  @Test
  void fromAiResponseShouldMapAllFields() {
    var ai =
        new ReviewResponse.Finding(
            "high",
            "src/Foo.java",
            42,
            "SQL Injection",
            "Unsafe query construction",
            "old code",
            "new code");

    Finding finding = Finding.fromAiResponse(ai);

    assertEquals(RiskLevel.HIGH, finding.risk());
    assertEquals("src/Foo.java", finding.file());
    assertEquals(42, finding.line());
    assertEquals("SQL Injection", finding.title());
    assertEquals("Unsafe query construction", finding.description());
    assertEquals("old code", finding.suggestionOld());
    assertEquals("new code", finding.suggestionNew());
  }

  @Test
  void fromAiResponseShouldDefaultUnknownRiskToLow() {
    var ai = new ReviewResponse.Finding("unknown", "f", 1, "t", "d", null, null);

    Finding finding = Finding.fromAiResponse(ai);

    assertEquals(RiskLevel.LOW, finding.risk());
  }

  @Test
  void fromAiResponseShouldMapConfidence() {
    var ai = new ReviewResponse.Finding("high", "medium", "f", 1, "t", "d", null, null);

    Finding finding = Finding.fromAiResponse(ai);

    assertEquals(Confidence.MEDIUM, finding.confidence());
  }

  @Test
  void fromAiResponseShouldDefaultAbsentConfidenceToHigh() {
    var ai = new ReviewResponse.Finding("high", "f", 1, "t", "d", null, null);

    Finding finding = Finding.fromAiResponse(ai);

    assertEquals(Confidence.HIGH, finding.confidence());
  }

  @Test
  void canonicalConstructorShouldNormalizeNullConfidenceToHigh() {
    var finding = new Finding(RiskLevel.HIGH, null, "f", 1, "t", "d", null, null);

    assertEquals(Confidence.HIGH, finding.confidence());
  }

  @Test
  void convenienceConstructorShouldDefaultConfidenceToHigh() {
    var finding = new Finding(RiskLevel.HIGH, "f", 1, "t", "d", "old", "new");

    assertEquals(Confidence.HIGH, finding.confidence());
  }

  @Test
  void hasSuggestionShouldReturnTrueWhenBothPresent() {
    var finding = new Finding(RiskLevel.HIGH, "f", 1, "t", "d", "old", "new");

    assertTrue(finding.hasSuggestion());
  }

  @Test
  void hasSuggestionShouldReturnFalseWhenOldIsNull() {
    var finding = new Finding(RiskLevel.HIGH, "f", 1, "t", "d", null, "new");

    assertFalse(finding.hasSuggestion());
  }

  @Test
  void hasSuggestionShouldReturnFalseWhenNewIsNull() {
    var finding = new Finding(RiskLevel.HIGH, "f", 1, "t", "d", "old", null);

    assertFalse(finding.hasSuggestion());
  }

  @Test
  void hasSuggestionShouldReturnFalseWhenOldIsBlank() {
    var finding = new Finding(RiskLevel.HIGH, "f", 1, "t", "d", "  ", "new");

    assertFalse(finding.hasSuggestion());
  }

  @Test
  void hasSuggestionShouldReturnFalseWhenNewIsBlank() {
    var finding = new Finding(RiskLevel.HIGH, "f", 1, "t", "d", "old", "");

    assertFalse(finding.hasSuggestion());
  }

  @Test
  void postsInlineShouldBeFalseForLowConfidenceMediumRisk() {
    var finding = new Finding(RiskLevel.MEDIUM, Confidence.LOW, "f", 1, "t", "d", null, null);

    assertFalse(finding.postsInline());
  }

  @Test
  void postsInlineShouldBeTrueForMediumConfidence() {
    var finding = new Finding(RiskLevel.MEDIUM, Confidence.MEDIUM, "f", 1, "t", "d", null, null);

    assertTrue(finding.postsInline());
  }

  @Test
  void postsInlineShouldBeTrueForHighRiskEvenAtLowConfidence() {
    var finding = new Finding(RiskLevel.HIGH, Confidence.LOW, "f", 1, "t", "d", null, null);

    assertTrue(finding.postsInline());
  }

  @Test
  void postsInlineShouldBeTrueForCriticalRiskEvenAtLowConfidence() {
    var finding = new Finding(RiskLevel.CRITICAL, Confidence.LOW, "f", 1, "t", "d", null, null);

    assertTrue(finding.postsInline());
  }
}
