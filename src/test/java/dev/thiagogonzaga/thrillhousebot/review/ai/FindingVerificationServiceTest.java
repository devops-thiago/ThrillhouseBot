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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class FindingVerificationServiceTest {

  @Mock private FindingVerifier verifier;

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  private FindingVerificationService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.verifierEnabled()).thenReturn(true);
    service = new FindingVerificationService(verifier, config, new ObjectMapper());
  }

  private static ReviewResponse.Finding finding(String risk, String confidence, String title) {
    return new ReviewResponse.Finding(
        risk, confidence, "src/Main.java", 10, title, "desc", "old", "new");
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    return new ReviewResponse(
        List.of(findings),
        List.of(),
        new ReviewResponse.Summary(
            findings.length, 0, 0, 0, 0, "assessment", "purpose", List.of("gap")));
  }

  @Test
  void shouldDemoteHedgedBlockingFindingsToMediumConfidence() {
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "high",
                "high",
                "f",
                1,
                "Underscore variable may not compile",
                "If the project targets Java 17, compilation could fail.",
                null,
                null));

    var result = service.verify(original, "diff", "stack", "");

    assertEquals("medium", result.findings().get(0).confidence());
    assertEquals("high", result.findings().get(0).risk());
  }

  @Test
  void shouldDemoteWhenOnlyDescriptionHedgesOrAFieldIsNull() {
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "critical",
                "high",
                "f",
                1,
                "Breaks startup",
                "This could fail under load.",
                null,
                null),
            new ReviewResponse.Finding("high", "high", "g", 2, null, "Possibly wrong.", null, null),
            new ReviewResponse.Finding("high", "high", "h", 3, "May break", null, null, null),
            new ReviewResponse.Finding("high", "high", "i", 4, "Breaks startup", null, null, null));

    var result = service.verify(original, "diff", "stack", "");

    assertEquals("medium", result.findings().get(0).confidence());
    assertEquals("medium", result.findings().get(1).confidence());
    assertEquals("medium", result.findings().get(2).confidence());
    // Assertive title with no description has nothing hedged — stays blocking
    assertEquals("high", result.findings().get(3).confidence());
  }

  @Test
  void shouldNotDemoteAssertiveBlockingFindingsOrHedgedNonBlockingOnes() {
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "critical",
                "high",
                "f",
                1,
                "Will fail at runtime",
                "Throws on startup.",
                null,
                null),
            new ReviewResponse.Finding(
                "low", "high", "g", 2, "Might be slow", "Could matter at scale.", null, null),
            new ReviewResponse.Finding(
                "critical", "medium", "h", 3, "May break", "Possibly wrong.", null, null));

    var result = service.verify(original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void shouldSkipVerificationWhenDisabled() {
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original = response(finding("critical", "high", "Bug"));

    var result = service.verify(original, "diff", "stack", "");

    assertSame(original, result);
    verifyNoInteractions(verifier);
  }

  @Test
  void shouldSkipVerificationWhenNoFindings() {
    var original = new ReviewResponse(List.of(), List.of(), null);

    var result = service.verify(original, "diff", "stack", "");

    assertSame(original, result);
    verifyNoInteractions(verifier);
  }

  @Test
  void shouldKeepResponseUntouchedWhenAllFindingsConfirmed() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [{"id": 1, "verdict": "confirmed", "risk": "critical",
            "confidence": "high", "reason": "verified"}]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void shouldDropRejectedFindingsAndRecountSummary() {
    ReviewResponse original =
        response(
            finding("critical", "high", "Hallucinated API claim"),
            finding("low", "high", "Real nit"),
            finding("critical", "high", "Real injection"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [
              {"id": 1, "verdict": "rejected", "reason": "framework idiom, suggestion is a no-op"},
              {"id": 2, "verdict": "confirmed", "risk": "low", "confidence": "high", "reason": "ok"},
              {"id": 3, "verdict": "confirmed", "risk": "critical", "confidence": "high", "reason": "ok"}
            ]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    assertEquals(2, result.findings().size());
    assertEquals("Real nit", result.findings().get(0).title());
    assertEquals("Real injection", result.findings().get(1).title());
    assertEquals(2, result.summary().totalFindings());
    assertEquals(1, result.summary().critical());
    assertEquals(1, result.summary().low());
    // Prose fields survive the recount
    assertEquals("assessment", result.summary().overallAssessment());
    assertEquals("purpose", result.summary().prPurpose());
    assertEquals(List.of("gap"), result.summary().descriptionGaps());
  }

  @Test
  void shouldDowngradeRiskAndConfidence() {
    ReviewResponse original = response(finding("critical", "high", "Speculative"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [{"id": 1, "verdict": "downgraded", "risk": "medium",
            "confidence": "low", "reason": "not verifiable from the diff"}]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    assertEquals(1, result.findings().size());
    var downgraded = result.findings().get(0);
    assertEquals("medium", downgraded.risk());
    assertEquals("low", downgraded.confidence());
    assertEquals(1, result.summary().medium());
    assertEquals(0, result.summary().critical());
  }

  @Test
  void downgradeShouldNeverRaiseRiskOrConfidence() {
    ReviewResponse original = response(finding("medium", "low", "Already modest"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [{"id": 1, "verdict": "downgraded", "risk": "critical",
            "confidence": "high", "reason": "tries to escalate"}]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    var kept = result.findings().get(0);
    assertEquals("medium", kept.risk());
    assertEquals("low", kept.confidence());
  }

  @Test
  void downgradeWithoutRatingsShouldKeepOriginalValues() {
    ReviewResponse original = response(finding("high", "medium", "Unrated downgrade"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [{"id": 1, "verdict": "downgraded", "reason": "no ratings given"}]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    var kept = result.findings().get(0);
    assertEquals("high", kept.risk());
    assertEquals("medium", kept.confidence());
  }

  @Test
  void downgradeWithGarbledRatingsShouldKeepOriginalValues() {
    ReviewResponse original =
        response(
            finding("critical", "high", "Garbled both"),
            finding("critical", "high", "To low/medium"),
            finding("critical", "high", "To high/low"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [
              {"id": 1, "verdict": "downgraded", "risk": "moderate", "confidence": "very low", "reason": "r"},
              {"id": 2, "verdict": "downgraded", "risk": "low", "confidence": "medium", "reason": "r"},
              {"id": 3, "verdict": "downgraded", "risk": "high", "confidence": "low", "reason": "r"}
            ]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    // Garbled labels must not collapse the rating to the lenient-parse default
    var garbled = result.findings().get(0);
    assertEquals("critical", garbled.risk());
    assertEquals("high", garbled.confidence());
    var lowMedium = result.findings().get(1);
    assertEquals("low", lowMedium.risk());
    assertEquals("medium", lowMedium.confidence());
    var highLow = result.findings().get(2);
    assertEquals("high", highLow.risk());
    assertEquals("low", highLow.confidence());
  }

  @Test
  void shouldKeepFindingWhenVerdictDecisionFieldIsMissing() {
    ReviewResponse original = response(finding("high", "high", "No decision"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [{"id": 1, "reason": "verdict field omitted"}]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void downgradeWithBlankRatingsShouldKeepOriginalValues() {
    ReviewResponse original =
        response(finding("critical", "high", "Blank risk"), finding("high", "high", "Blank conf"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [
              {"id": 1, "verdict": "downgraded", "risk": "", "confidence": "low", "reason": "r"},
              {"id": 2, "verdict": "downgraded", "risk": "low", "confidence": "", "reason": "r"}
            ]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    var blankRisk = result.findings().get(0);
    assertEquals("critical", blankRisk.risk());
    assertEquals("low", blankRisk.confidence());
    var blankConfidence = result.findings().get(1);
    assertEquals("low", blankConfidence.risk());
    assertEquals("high", blankConfidence.confidence());
  }

  @Test
  void shouldKeepFindingsWithoutVerdictOrWithUnknownVerdict() {
    ReviewResponse original =
        response(finding("high", "high", "No verdict"), finding("low", "high", "Weird verdict"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [{"id": 2, "verdict": "shrug", "reason": "?"}]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void shouldUseFirstVerdictWhenIdsAreDuplicated() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [
              {"id": 1, "verdict": "rejected", "reason": "first wins"},
              {"id": 1, "verdict": "confirmed", "reason": "ignored"}
            ]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    assertTrue(result.findings().isEmpty());
  }

  @Test
  void shouldParseFencedVerifierOutput() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            ```json
            {"verdicts": [{"id": 1, "verdict": "rejected", "reason": "fp"}]}
            ```
            """);

    var result = service.verify(original, "diff", "stack", "");

    assertTrue(result.findings().isEmpty());
  }

  @Test
  void shouldFailOpenWhenVerifierThrows() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("model unavailable"));

    var result = service.verify(original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void shouldFailOpenWhenVerifierReturnsInvalidJson() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("not json at all");

    var result = service.verify(original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void shouldHandleNullSummaryWhenRecounting() {
    var original = new ReviewResponse(List.of(finding("critical", "high", "Bug")), List.of(), null);
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            """
            {"verdicts": [{"id": 1, "verdict": "rejected", "reason": "fp"}]}
            """);

    var result = service.verify(original, "diff", "stack", "");

    assertTrue(result.findings().isEmpty());
    assertNull(result.summary());
  }

  @Test
  void shouldSendEscapedCandidatesWithIdsAndPassThroughContext() {
    ReviewResponse original = response(finding("critical", "high", "Brace {bug}"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("{\"verdicts\": []}");

    service.verify(original, "the-diff", "the-stack", "prior context");

    var candidates = ArgumentCaptor.forClass(String.class);
    verify(verifier)
        .verify(candidates.capture(), eq("the-diff"), eq("the-stack"), eq("prior context"));
    assertTrue(candidates.getValue().startsWith("{|"));
    assertTrue(candidates.getValue().contains("\"id\" : 1"));
    assertTrue(candidates.getValue().contains("Brace {bug}"));
    assertTrue(candidates.getValue().contains("suggestion_old"));
  }

  @Test
  void shouldPassEmptyPreviousFindingsWhenNull() {
    ReviewResponse original = response(finding("critical", "high", "Title"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("{\"verdicts\": []}");

    service.verify(original, "the-diff", "the-stack", null);

    verify(verifier).verify(anyString(), eq("the-diff"), eq("the-stack"), eq(""));
  }
}
