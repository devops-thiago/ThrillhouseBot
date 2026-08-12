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

import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiNoContent;
import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiOk;
import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiTruncated;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.Finding;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class FindingVerificationServiceTest {

  /** The review's ledger key, as {@link ReviewTokenLedger#keyFor} would produce it. */
  private static final long SESSION = 42L;

  @Mock private FindingVerifier verifier;

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  @Mock private ReviewTokenLedger tokenLedger;

  private FindingVerificationService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.verifierEnabled()).thenReturn(true);
    service = serviceWith(tokenLedger);
  }

  /** Swaps in a real, opened ledger so a test can observe the spend the service records. */
  private ReviewTokenLedger realLedger(long ceiling) {
    when(reviewConfig.maxTokensPerReview()).thenReturn(ceiling);
    var ledger = new ReviewTokenLedger(config);
    ledger.open(SESSION);
    service = serviceWith(ledger);
    return ledger;
  }

  private FindingVerificationService serviceWith(ReviewTokenLedger ledger) {
    var mapper = new ObjectMapper();
    return new FindingVerificationService(
        verifier, config, mapper, ledger, new TruncatedResponseSalvager(mapper));
  }

  private static Result<String> aiOkWithUsage(String text, int inputTokens, int outputTokens) {
    return Result.<String>builder()
        .content(text)
        .finishReason(FinishReason.STOP)
        .tokenUsage(new TokenUsage(inputTokens, outputTokens))
        .build();
  }

  private static Result<String> aiTruncatedWithUsage(
      String partialText, int inputTokens, int outputTokens) {
    return Result.<String>builder()
        .content(partialText)
        .finishReason(FinishReason.LENGTH)
        .tokenUsage(new TokenUsage(inputTokens, outputTokens))
        .build();
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

    var result = service.verify(SESSION, original, "diff", "stack", "");

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

    var result = service.verify(SESSION, original, "diff", "stack", "");

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

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void shouldSkipVerificationWhenDisabled() {
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original = response(finding("critical", "high", "Bug"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    verifyNoInteractions(verifier);
  }

  @Test
  void shouldSkipVerificationWhenNoFindings() {
    var original = new ReviewResponse(List.of(), List.of(), null);

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    verifyNoInteractions(verifier);
  }

  @Test
  void skipsTheVerifierAiCallOnceTheSpendCeilingIsReached() {
    // #514: the verifier is a billed review-path call, so "once reached no further call is made"
    // applies to it too. The skip fails open — the unverified findings are kept, never lost.
    when(tokenLedger.ceilingReached(SESSION)).thenReturn(true);
    ReviewResponse original = response(finding("critical", "high", "Bug"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    verifyNoInteractions(verifier);
  }

  @Test
  void hedgedDemotionStillRunsWhenTheCeilingSkipsTheVerifier() {
    // The deterministic hedging guard costs no tokens, so the ceiling must not switch it off.
    when(tokenLedger.ceilingReached(SESSION)).thenReturn(true);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "high", "high", "f", 1, "May break", "This could fail.", null, null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("medium", result.findings().get(0).confidence());
    verifyNoInteractions(verifier);
  }

  @Test
  void recordsTheVerifierCallsUsageInTheReviewsLedger() {
    // #514: the verifier's spend is real billed usage; the ledger must increase by the
    // provider-reported input+output of the call.
    var ledger = realLedger(100_000L);
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiOkWithUsage("{\"verdicts\": []}", 1200, 34));

    service.verify(SESSION, original, "diff", "stack", "");

    assertEquals(1234L, ledger.tokensSpent(SESSION));
  }

  @Test
  void recordsUsageEvenWhenTheVerifierResponseIsCutShort() {
    // A truncated verifier response was still billed: its usage lands in the ledger before the
    // truncation is turned into the fail-open path.
    var ledger = realLedger(100_000L);
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiTruncatedWithUsage("{\"verdicts\": [{\"id", 900, 100));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result); // fails open, findings kept
    assertEquals(1000L, ledger.tokensSpent(SESSION));
  }

  @Test
  void recordsNothingWhenTheProviderReportsNoUsage() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiOk("{\"verdicts\": []}"));

    service.verify(SESSION, original, "diff", "stack", "");

    verify(tokenLedger, never()).recordUsage(anyLong(), any(), any());
  }

  @Test
  void failsOpenAndRecordsNothingWhenTheVerifierReturnsNoResult() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    verify(tokenLedger, never()).recordUsage(anyLong(), any(), any());
  }

  @Test
  void shouldKeepResponseUntouchedWhenAllFindingsConfirmed() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [{"id": 1, "verdict": "confirmed", "risk": "critical",
            "confidence": "high", "reason": "verified"}]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

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
            aiOk(
                """
            {"verdicts": [
              {"id": 1, "verdict": "rejected", "reason": "framework idiom, suggestion is a no-op"},
              {"id": 2, "verdict": "confirmed", "risk": "low", "confidence": "high", "reason": "ok"},
              {"id": 3, "verdict": "confirmed", "risk": "critical", "confidence": "high", "reason": "ok"}
            ]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

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
            aiOk(
                """
            {"verdicts": [{"id": 1, "verdict": "downgraded", "risk": "medium",
            "confidence": "low", "reason": "not verifiable from the diff"}]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals(1, result.findings().size());
    var downgraded = result.findings().get(0);
    assertEquals("medium", downgraded.risk());
    assertEquals("low", downgraded.confidence());
    assertEquals(1, result.summary().medium());
    assertEquals(0, result.summary().critical());
  }

  @Test
  void keepsFindingsUnchangedWhenTheVerifierResponseIsCutShort() {
    // Characterization, not red/green: the verifier fails open on a truncation exactly as it does
    // on malformed JSON, so this passes with detection disabled too. It is kept because that
    // fail-open contract is what must NOT change as truncation grows its own handling — the
    // difference detection buys here is the log line, which is not worth asserting on. The
    // red/green proof for detection itself lives in AiResponsesTest.
    ReviewResponse original = response(finding("critical", "high", "Speculative"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiTruncated("{\"verdicts\": [{\"id\": 1, \"verdict\": \"downgr"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    // Fails open: the unverified finding survives at its original risk/confidence.
    assertEquals(1, result.findings().size());
    assertEquals("critical", result.findings().get(0).risk());
    assertEquals("high", result.findings().get(0).confidence());
  }

  @Test
  void appliesTheVerdictsThatClosedBeforeTheResponseLengthCapCutTheBody() {
    // #599: the length-stop lane discarded the response whole. Since #592 the cut body travels on
    // the truncation as its partial body, so the verdicts that closed before the cut are
    // recoverable — the same salvage the no-finish-reason lane already runs. Every one of them was
    // generated and billed; throwing them away bought nothing.
    ReviewResponse original =
        response(
            finding("critical", "high", "Hallucinated API claim"),
            finding("high", "high", "Speculative"),
            finding("high", "high", "Verdict cut off"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiTruncated(
                """
            {"verdicts": [
              {"id": 1, "verdict": "rejected", "reason": "framework idiom"},
              {"id": 2, "verdict": "downgraded", "risk": "low", "confidence": "low", "reason": "r"},
              {"id": 3, "verdict": "confi"""));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals(2, result.findings().size());
    assertEquals("Speculative", result.findings().get(0).title());
    assertEquals("low", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
    // The candidate whose verdict was on the far side of the cut stays untouched, never rejected.
    var unverified = result.findings().get(1);
    assertEquals("Verdict cut off", unverified.title());
    assertEquals("high", unverified.risk());
    assertEquals("high", unverified.confidence());
    assertEquals(2, result.summary().totalFindings());
  }

  @Test
  void keepsEveryFindingWhenTheCapCutTheBodyBeforeAnyVerdictClosed() {
    // The fail-open contract is unchanged where there is nothing to salvage: a cut landing inside
    // the first verdict, and a truncation carrying no partial body at all, both keep every finding.
    ReviewResponse original =
        response(finding("critical", "high", "Bug"), finding("low", "high", "Nit"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiTruncated("{\"verdicts\": [{\"id\": 1, \"verdict\": \"reje"), aiTruncated(null));

    assertSame(original, service.verify(SESSION, original, "diff", "stack", ""));
    assertSame(original, service.verify(SESSION, original, "diff", "stack", ""));
  }

  @Test
  void keepsUnverifiedFindingsWithoutParsingWhenTheModelReturnsNoBody() {
    // #534: with the whole output budget spent on reasoning tokens the provider returns a completed
    // response with no content body. That is the unwrap helper's documented "no response" soft
    // failure, but the body went straight into the JSON extraction, which raised "Cannot invoke
    // String.strip() because raw is null". The findings survive either way — the fail-open catch
    // caught the NPE — so what this pins is that an absent body never reaches the extraction.
    ReviewResponse original = response(finding("critical", "high", "Unverified"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiNoContent());

    try (var parser = mockStatic(ReviewResponseParser.class)) {
      var result = service.verify(SESSION, original, "diff", "stack", "");

      assertEquals(1, result.findings().size());
      assertEquals("critical", result.findings().get(0).risk());
      assertEquals("high", result.findings().get(0).confidence());
      parser.verify(() -> ReviewResponseParser.extractJson(any()), never());
    }
  }

  @Test
  void keepsUnverifiedFindingsWithoutParsingWhenTheModelReturnsABlankBody() {
    // Same soft failure as an absent body, and it must not be reported as a parse fault either:
    // whitespace is what the provider returns when the content field came back empty rather than
    // missing.
    ReviewResponse original = response(finding("high", "high", "Unverified"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiOk("   "));

    try (var parser = mockStatic(ReviewResponseParser.class)) {
      var result = service.verify(SESSION, original, "diff", "stack", "");

      assertEquals(1, result.findings().size());
      assertEquals("high", result.findings().get(0).risk());
      parser.verify(() -> ReviewResponseParser.extractJson(any()), never());
    }
  }

  @Test
  void appliesTheVerdictsThatCompletedWhenTheBodyIsCutMidJson() {
    // #546: production has seen the verifier body arrive cut mid-JSON with no finish_reason=length
    // (STOP, as built here), so it never reaches the truncation lane — before the salvage, the
    // whole pass was discarded and every complete verdict thrown away. The three verdicts that
    // closed must be applied; the candidate whose verdict was on the far side of the cut stays
    // unverified, never rejected.
    ReviewResponse original =
        response(
            finding("critical", "high", "Hallucinated API claim"),
            finding("high", "high", "Speculative"),
            finding("critical", "high", "Real injection"),
            finding("high", "high", "Verdict cut off"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [
              {"id": 1, "verdict": "rejected", "reason": "framework idiom"},
              {"id": 2, "verdict": "downgraded", "risk": "low", "confidence": "low", "reason": "r"},
              {"id": 3, "verdict": "confirmed", "risk": "critical", "confidence": "high", "reason": "ok"},
              {"id": 4, "verdict": "reje"""));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals(3, result.findings().size());
    assertEquals("Speculative", result.findings().get(0).title());
    assertEquals("low", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
    assertEquals("Real injection", result.findings().get(1).title());
    // The unverified candidate survives untouched — a missing verdict never rejects or downgrades.
    var unverified = result.findings().get(2);
    assertEquals("Verdict cut off", unverified.title());
    assertEquals("high", unverified.risk());
    assertEquals("high", unverified.confidence());
    assertEquals(3, result.summary().totalFindings());
  }

  @Test
  void keepsEveryFindingWhenTheCutLeavesNoCompleteVerdict() {
    // Nothing recoverable: the salvage adds nothing, and the service fails open exactly as it did
    // before — the parse failure is rethrown into the fail-open catch.
    ReviewResponse original =
        response(finding("critical", "high", "Bug"), finding("low", "high", "Nit"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiOk("{\"verdicts\": [{\"id\": 1, \"verdict\": \"reje"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void ignoresSalvagedVerdictsWhoseIdMatchesNoCandidate() {
    // Salvage is best-effort over model output, so a verdict can carry an id outside the candidate
    // range. It must not reject anything, while the in-range verdict beside it still applies.
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [
              {"id": 0, "verdict": "rejected", "reason": "no such candidate"},
              {"id": 9, "verdict": "rejected", "reason": "no such candidate either"},
              {"id": 1, "verdict": "downgraded", "risk": "low", "confidence": "low", "reason": "r"},
              {"id": 2, "verdict": "conf"""));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals(1, result.findings().size());
    assertEquals("low", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void shouldTolerateRawControlCharsInVerifierResponse() {
    // The verifier sometimes echoes code in its reason with a raw tab/newline left unescaped; the
    // verdict must still be applied (the downgrade below only lands if the response parsed).
    ReviewResponse original = response(finding("critical", "high", "Speculative"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                "{\"verdicts\": [{\"id\": 1, \"verdict\": \"downgraded\", \"risk\": \"medium\","
                    + " \"confidence\": \"low\", \"reason\": \"guard\tmissing\nhere\"}]}"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("medium", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void downgradeShouldNeverRaiseRiskOrConfidence() {
    ReviewResponse original = response(finding("medium", "low", "Already modest"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [{"id": 1, "verdict": "downgraded", "risk": "critical",
            "confidence": "high", "reason": "tries to escalate"}]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    var kept = result.findings().get(0);
    assertEquals("medium", kept.risk());
    assertEquals("low", kept.confidence());
  }

  @Test
  void downgradeWithoutRatingsShouldKeepOriginalValues() {
    ReviewResponse original = response(finding("high", "medium", "Unrated downgrade"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [{"id": 1, "verdict": "downgraded", "reason": "no ratings given"}]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

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
            aiOk(
                """
            {"verdicts": [
              {"id": 1, "verdict": "downgraded", "risk": "moderate", "confidence": "very low", "reason": "r"},
              {"id": 2, "verdict": "downgraded", "risk": "low", "confidence": "medium", "reason": "r"},
              {"id": 3, "verdict": "downgraded", "risk": "high", "confidence": "low", "reason": "r"}
            ]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

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
            aiOk(
                """
            {"verdicts": [{"id": 1, "reason": "verdict field omitted"}]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void downgradeWithBlankRatingsShouldKeepOriginalValues() {
    ReviewResponse original =
        response(finding("critical", "high", "Blank risk"), finding("high", "high", "Blank conf"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [
              {"id": 1, "verdict": "downgraded", "risk": "", "confidence": "low", "reason": "r"},
              {"id": 2, "verdict": "downgraded", "risk": "low", "confidence": "", "reason": "r"}
            ]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

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
            aiOk(
                """
            {"verdicts": [{"id": 2, "verdict": "shrug", "reason": "?"}]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void shouldUseFirstVerdictWhenIdsAreDuplicated() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [
              {"id": 1, "verdict": "rejected", "reason": "first wins"},
              {"id": 1, "verdict": "confirmed", "reason": "ignored"}
            ]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertTrue(result.findings().isEmpty());
  }

  @Test
  void shouldParseFencedVerifierOutput() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            ```json
            {"verdicts": [{"id": 1, "verdict": "rejected", "reason": "fp"}]}
            ```
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertTrue(result.findings().isEmpty());
  }

  @Test
  void shouldFailOpenWhenVerifierThrows() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("model unavailable"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void shouldFailOpenWhenVerifierReturnsInvalidJson() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiOk("not json at all"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
  }

  @Test
  void shouldHandleNullSummaryWhenRecounting() {
    var original = new ReviewResponse(List.of(finding("critical", "high", "Bug")), List.of(), null);
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [{"id": 1, "verdict": "rejected", "reason": "fp"}]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertTrue(result.findings().isEmpty());
    assertNull(result.summary());
  }

  @Test
  void shouldSendEscapedCandidatesWithIdsAndPassThroughContext() {
    ReviewResponse original =
        response(finding("critical", "high", "Brace {bug} <<<DIFF_END>>> tail"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiOk("{\"verdicts\": []}"));

    service.verify(SESSION, original, "the-diff", "the-stack", "prior context");

    var candidates = ArgumentCaptor.forClass(String.class);
    verify(verifier)
        .verify(candidates.capture(), eq("the-diff"), eq("the-stack"), eq("prior context"));
    // Escaping is applied: a spoofed diff-section delimiter is neutralized...
    assertTrue(candidates.getValue().contains("<<DIFF_END>> tail"));
    assertFalse(candidates.getValue().contains("<<<DIFF_END>>>"));
    // ...while ordinary brace content passes through byte-exact (no unparsed-section wrapper).
    assertTrue(candidates.getValue().contains("Brace {bug}"));
    assertFalse(candidates.getValue().startsWith("{|"));
    assertTrue(candidates.getValue().contains("\"id\" : 1"));
    assertTrue(candidates.getValue().contains("suggestion_old"));
  }

  @Test
  void shouldPassEmptyPreviousFindingsWhenNull() {
    ReviewResponse original = response(finding("critical", "high", "Title"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiOk("{\"verdicts\": []}"));

    service.verify(SESSION, original, "the-diff", "the-stack", null);

    verify(verifier).verify(anyString(), eq("the-diff"), eq("the-stack"), eq(""));
  }

  /**
   * #570's controlled pair, as the dogfood corpus plants it: the same stored-XSS class behind two
   * frameworks' HTML-injection escape hatches, each finding stating that no sanitizer is present.
   */
  private static ReviewResponse.Finding angularXss(String risk, String confidence) {
    return new ReviewResponse.Finding(
        risk,
        confidence,
        "src/app/incident-timeline/event-item/event-item.component.ts",
        16,
        "Stored XSS: responder note rendered via bypassSecurityTrustHtml without sanitization",
        "The responder note is user-authored and reaches the sink with no sanitizer in the diff.",
        null,
        null);
  }

  private static ReviewResponse.Finding reactXss(String risk, String confidence) {
    return new ReviewResponse.Finding(
        risk,
        confidence,
        "src/components/ResultItem.tsx",
        23,
        "Unsanitized API-supplied snippetHtml injected via dangerouslySetInnerHTML (XSS)",
        "snippetHtml comes straight from the API response and is rendered as raw HTML.",
        null,
        null);
  }

  @Test
  void floorsAnUnsanitizedInjectionSinkFindingTheModelRatedBelowHigh() {
    // #570: the React half of the planted pair published MEDIUM while the Angular half published
    // CRITICAL. Severity is a property of the defect class, so the class floors at high; the
    // model's uncertainty stays where it belongs — on confidence.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original = response(reactXss("medium", "low"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
    assertEquals(1, result.summary().high());
    assertEquals(0, result.summary().medium());
  }

  @Test
  void floorsStringBuiltSqlNamedByItsShapeRatherThanTheWordsSqlInjection() {
    // #570's second round-3 instance: the C# tenant filter. Its title never says "SQL injection",
    // it says the value is concatenated into SQL with no parameterization — the same class.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/InvoiceExporter/Data/InvoiceRepository.cs",
                27,
                "Tenant filter is concatenated into SQL with no parameterization",
                "The account code is interpolated into the command text.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void keepsAnInjectionSinkFindingAtHighRiskWhenTheVerifierDowngradesIt() {
    // #570: the verifier's "remembered framework / rendering semantics" ground caps such a claim at
    // medium risk with low confidence, which is what routed a demonstrated XSS into the collapsed
    // block. The verifier may still take the confidence down; it may not take the class below high.
    ReviewResponse original = response(reactXss("high", "high"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [{"id": 1, "verdict": "downgraded", "risk": "medium",
            "confidence": "low", "reason": "rendering semantics are not verifiable here"}]}
            """));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
    assertEquals(1, result.summary().high());
    assertEquals(0, result.summary().medium());
  }

  @Test
  void ratesBothHalvesOfThePlantedXssPairTheSameAndKeepsBothInline() {
    // The regression test #570 asks for: the same class in two frameworks must publish at the same
    // severity AND the same placement. Placement follows from severity — a high finding opens an
    // inline thread however low its confidence is.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original = response(angularXss("high", "high"), reactXss("medium", "low"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals(result.findings().get(0).risk(), result.findings().get(1).risk());
    assertTrue(Finding.fromAiResponse(result.findings().get(0)).postsInline());
    assertTrue(Finding.fromAiResponse(result.findings().get(1)).postsInline());
  }

  @Test
  void keepsBlockingConfidenceWhenTheOnlyHedgeSitsInAVerificationRequest() {
    // #570: the review prompt REQUIRES a demonstrated-sink finding to name the unshown layer to
    // verify, which puts a hedge word into the description of every such finding by construction.
    // Reading that mandated clause as a hedge of the claim strips the finding's blocking
    // confidence (BlockingStrictness.BALANCED needs high) and stamps a "verify before acting"
    // disclaimer on a defect the diff demonstrates.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "high",
                "high",
                "src/main/java/Repo.java",
                27,
                "SQL injection in findPendingByChannel via unsanitized channel",
                "The channel value is concatenated into the query text. Verify whether a layer"
                    + " above this method might sanitize it; that layer is not shown here.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("high", result.findings().get(0).confidence());
  }

  @Test
  void stillDemotesWhenAHedgeSitsOutsideTheVerificationRequest() {
    // The narrowing is clause-scoped, not a blanket exemption: a finding that hedges the claim
    // itself still loses its blocking confidence even when it also asks for a verification.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "high",
                "high",
                "f",
                1,
                "Query build",
                "This could fail under load. Verify whether the pool size might be the cause.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("medium", result.findings().get(0).confidence());
  }

  @Test
  void doesNotFloorAFindingThatDeniesTheSinkItNames() {
    // The floor's two signals were matched independently anywhere in the text, so a finding that
    // RULED OUT the sink still satisfied both: "sql injection" inside "no SQL injection", and the
    // no-mitigation group inside "not concatenated but parameterized" — the very mitigation the
    // sentence asserts. Raising such a finding to high is this floor's own failure mode pointed
    // the other way, and at high confidence it would go on to block the merge.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/InvoiceExporter/Data/InvoiceRepository.cs",
                27,
                "Tenant filter reads awkwardly",
                "The user input is not concatenated but parameterized, so no SQL injection is"
                    + " possible; the naming is just hard to follow.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorAFindingThatDescribesTheMitigationAsPresent() {
    // The other half of the same defect: no negated sink here, but the finding affirmatively
    // states the value IS escaped. An absence claim about one layer ("unvalidated" at storage)
    // must not floor the class when the text also says another layer neutralizes it.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Profile.tsx",
                12,
                "Profile names are unvalidated before storage",
                "React escapes them at render, so the XSS exposure is limited to the stored value"
                    + " itself.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void leavesCriticalInjectionFindingsAndUnrelatedRatingsAlone() {
    // The floor only lifts, so a critical keeps its level; and it never fires unless the finding
    // states both halves — a named sink AND the absence of any sanitization.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            angularXss("critical", "high"),
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/ResultItem.tsx",
                23,
                "dangerouslySetInnerHTML renders a constant template",
                "The markup is a literal in this file, so nothing user-authored reaches it.",
                null,
                null),
            new ReviewResponse.Finding(
                "medium",
                "high",
                "docs/config.md",
                4,
                "Documented key omits its separator semantics with no validation of the list form",
                "The table shows one value and never says the binding is comma-separated.",
                null,
                null),
            // Names SQL, but as a query whose RESULT is unsanitized on the way out — nothing here
            // builds the statement from a value, so this is not the string-built-SQL shape.
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/Report.java",
                8,
                "Unsanitized SQL row count is written straight into the report header",
                null,
                null,
                null),
            new ReviewResponse.Finding(
                "low", "high", "src/Report.java", 12, null, "Totals are unvalidated.", null, null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
  }
}
