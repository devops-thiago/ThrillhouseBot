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
import dev.thiagogonzaga.thrillhousebot.review.VerificationCoverage;
import java.util.ArrayList;
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
  void reportsFullCoverageWhenEveryCandidateReceivesAVerdict() {
    // #623: a verification that ran to completion reports full coverage, so the posted review
    // renders no verification clause at all.
    ReviewResponse original =
        response(finding("critical", "high", "Bug"), finding("high", "high", "Speculative"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [
              {"id": 1, "verdict": "confirmed", "reason": "real"},
              {"id": 2, "verdict": "rejected", "reason": "framework idiom"}]}
            """));
    var reported = new ArrayList<VerificationCoverage>();

    service.verify(SESSION, original, "diff", "stack", "", reported::add);

    assertEquals(List.of(new VerificationCoverage(2, 2)), reported);
    assertFalse(reported.get(0).disclosed());
  }

  @Test
  void reportsPartialCoverageWhenACompleteResponseOmitsAVerdict() {
    // A complete, parseable body that simply skips a candidate is PARTIAL by count alone — the
    // rendered clause states the X-of-Y and no cause, because "response did not complete" would
    // be false here.
    ReviewResponse original =
        response(finding("critical", "high", "Ruled on"), finding("high", "high", "Skipped"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                "{\"verdicts\": [{\"id\": 1, \"verdict\": \"confirmed\", \"reason\": \"real\"}]}"));
    var reported = new ArrayList<VerificationCoverage>();

    var result = service.verify(SESSION, original, "diff", "stack", "", reported::add);

    assertEquals(2, result.findings().size());
    assertEquals(List.of(new VerificationCoverage(2, 1)), reported);
    assertEquals(VerificationCoverage.Outcome.PARTIAL, reported.get(0).outcome());
  }

  @Test
  void reportsZeroCoverageWhenTheModelReturnsNoBody() {
    // #623: the empty-body soft failure keeps the findings (fail open, unchanged) but must no
    // longer be indistinguishable from a verified set — the coverage says none were verified.
    ReviewResponse original =
        response(finding("critical", "high", "Bug"), finding("high", "high", "Nit"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiNoContent());
    var reported = new ArrayList<VerificationCoverage>();

    var result = service.verify(SESSION, original, "diff", "stack", "", reported::add);

    assertEquals(2, result.findings().size());
    assertEquals(List.of(new VerificationCoverage(2, 0)), reported);
    assertEquals(VerificationCoverage.Outcome.NONE, reported.get(0).outcome());
  }

  @Test
  void reportsPartialCoverageWhenTheBodyIsCutMidJson() {
    // #623 + #546/#617: the mid-JSON cut salvages the verdicts that closed, so the coverage says
    // exactly how many candidates the audit reached — X of Y, the honest partial state.
    ReviewResponse original =
        response(
            finding("critical", "high", "Hallucinated API claim"),
            finding("high", "high", "Speculative"),
            finding("high", "high", "Verdict cut off"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiOk(
                """
            {"verdicts": [
              {"id": 1, "verdict": "rejected", "reason": "framework idiom"},
              {"id": 2, "verdict": "downgraded", "risk": "low", "confidence": "low", "reason": "r"},
              {"id": 3, "verdict": "reje"""));
    var reported = new ArrayList<VerificationCoverage>();

    service.verify(SESSION, original, "diff", "stack", "", reported::add);

    assertEquals(List.of(new VerificationCoverage(3, 2)), reported);
    assertEquals(VerificationCoverage.Outcome.PARTIAL, reported.get(0).outcome());
  }

  @Test
  void reportsPartialCoverageWhenTheLengthCapCutTheBody() {
    // The reported-length-stop flavor of the same cut (#599): salvage applies the closed verdicts
    // and the coverage carries the same X-of-Y the parse-failure lane reports.
    ReviewResponse original =
        response(
            finding("critical", "high", "Hallucinated API claim"),
            finding("high", "high", "Verdict cut off"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            aiTruncated(
                """
            {"verdicts": [
              {"id": 1, "verdict": "rejected", "reason": "framework idiom"},
              {"id": 2, "verdict": "confi"""));
    var reported = new ArrayList<VerificationCoverage>();

    service.verify(SESSION, original, "diff", "stack", "", reported::add);

    assertEquals(List.of(new VerificationCoverage(2, 1)), reported);
  }

  @Test
  void reportsZeroCoverageWhenTheCutLeavesNoCompleteVerdict() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiTruncated("{\"verdicts\": [{\"id\": 1, \"verdict\": \"reje"));
    var reported = new ArrayList<VerificationCoverage>();

    service.verify(SESSION, original, "diff", "stack", "", reported::add);

    assertEquals(List.of(new VerificationCoverage(1, 0)), reported);
  }

  @Test
  void reportsZeroCoverageWhenTheVerifierCallFails() {
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    when(verifier.verify(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("provider down"));
    var reported = new ArrayList<VerificationCoverage>();

    var result = service.verify(SESSION, original, "diff", "stack", "", reported::add);

    assertEquals(1, result.findings().size());
    assertEquals(List.of(new VerificationCoverage(1, 0)), reported);
  }

  @Test
  void reportsZeroCoverageWhenTheCallIsSkippedAtTheSpendCeiling() {
    when(tokenLedger.ceilingReached(SESSION)).thenReturn(true);
    ReviewResponse original = response(finding("critical", "high", "Bug"));
    var reported = new ArrayList<VerificationCoverage>();

    service.verify(SESSION, original, "diff", "stack", "", reported::add);

    verify(verifier, never()).verify(anyString(), anyString(), anyString(), anyString());
    assertEquals(List.of(new VerificationCoverage(1, 0)), reported);
  }

  @Test
  void reportsNothingWhenTheVerifierIsDisabledOrThereIsNothingToVerify() {
    // No verification was attempted, so there is no coverage to disclose — the operator disabled
    // the audit knowingly, and an empty finding set has nothing to screen.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    var reported = new ArrayList<VerificationCoverage>();

    service.verify(
        SESSION, response(finding("critical", "high", "Bug")), "diff", "stack", "", reported::add);

    when(reviewConfig.verifierEnabled()).thenReturn(true);
    service.verify(SESSION, response(), "diff", "stack", "", reported::add);

    assertTrue(reported.isEmpty());
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
  void doesNotFloorAFindingThatDeniesTheExposureRatherThanTheSinkByName() {
    // The denial the finding words about the exposure ("not exploitable") instead of about the
    // class ("no SQL injection"): it names no sink of its own, so it is the wording most easily
    // lost when the denials are read as two patterns rather than one alternation.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/main/java/Report.java",
                31,
                "Unescaped title in the offline CSV export",
                "The value reaches document.write with no escaping, but the sheet is rendered by a"
                    + " build step and served to nobody, so this is not exploitable.",
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

  /**
   * Round 4's React half of the planted pair, verbatim from ThrillhouseBot-test #38: it names the
   * unshown layer to verify exactly as the review prompt requires, then rejects that hypothesis in
   * the same sentence and concludes the class is critical.
   */
  private static ReviewResponse.Finding reactConditionalXss(String risk, String confidence) {
    return new ReviewResponse.Finding(
        risk,
        confidence,
        "src/components/FeedbackItem.tsx",
        37,
        "Stored XSS: feedback body rendered via dangerouslySetInnerHTML with no sanitization",
        "No sanitization, escaping, or validation of body is visible anywhere in the provided"
            + " material. This is the stored-XSS defect class: user-authored stored content"
            + " rendered back to other users via a raw-HTML sink. If the feedback API sanitizes"
            + " body on write, the exploit is neutralized — verify that layer — but a"
            + " sanitizer you cannot see is not a sanitizer, so severity stays at critical while"
            + " confidence is medium.",
        null,
        null);
  }

  @Test
  void floorsAnInjectionSinkFindingWhoseOnlySanitizerMentionIsAConditionalItRejects() {
    // #608: the finding argues its own severity is critical and publishes MEDIUM, because
    // MITIGATION_ASSERTED_SUBJECT read "API sanitizes" out of the conditional "If the feedback API
    // sanitizes body on write" — a hypothesis the very next clause rejects. #575's prompt REQUIRES
    // that clause on this class, so the defeater fired on wording the prompt guarantees is there.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original = response(reactConditionalXss("medium", "medium"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("medium", result.findings().get(0).confidence());
    assertEquals(1, result.summary().high());
    assertEquals(0, result.summary().medium());
  }

  @Test
  void ratesTheConditionallyHedgedHalfOfThePlantedPairLikeItsTwin() {
    // #608's acceptance case: the same class in two frameworks, one of them phrased with the
    // mandated verification clause, must publish at the same severity and both inline.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(angularXss("critical", "high"), reactConditionalXss("medium", "medium"));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertTrue(Finding.fromAiResponse(result.findings().get(0)).postsInline());
    assertTrue(Finding.fromAiResponse(result.findings().get(1)).postsInline());
    assertEquals("critical", result.findings().get(0).risk());
    assertEquals("high", result.findings().get(1).risk());
  }

  @Test
  void stillHonoursAMitigationAssertedOutsideTheConditionalClause() {
    // The scoping is the conditional clause itself, not the sentence carrying it: over-firing the
    // floor is the dangerous direction (#594), so a finding whose conditional is incidental and
    // which then states the mitigation as fact must still defeat the floor. The consequent is
    // recognised by the coordinator that opens it, which is what survives the clause scan.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Profile.tsx",
                12,
                "dangerouslySetInnerHTML receives an unsanitized note",
                "If it were stored as plain text this would be moot, but React escapes it at"
                    + " render, so the exposure is limited.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void floorsAnInjectionSinkFindingWhoseConditionalCarriesItsOwnCommas() {
    // A protasis carries its own commas, so ending the conditional at the FIRST one left the
    // mitigation verb ("always sanitizes") in text read as asserted and the floor stayed suppressed
    // — the #608 failure reached through a sentence one comma away from the one it names.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "medium",
                "src/components/FeedbackItem.tsx",
                37,
                "Stored XSS: feedback body rendered via dangerouslySetInnerHTML",
                "No sanitization or escaping of body is visible in the provided material. If the"
                    + " feedback API, per its own contract, always sanitizes the body on write, the"
                    + " exploit is neutralized.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("medium", result.findings().get(0).confidence());
  }

  @Test
  void doesNotReadAnAssertiveShouldFrameAsAConditional() {
    // "should" is an ordinary auxiliary in an assertive frame, so treating it as a hypothesis hid a
    // genuine mitigation from the defeater and floored a finding that says the value IS sanitized.
    // Only inverted "Should the API sanitize ..." is a hypothesis, and its bare infinitive matches
    // none of the defeater's verb forms — so the marker only ever cost accuracy.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/FeedbackItem.tsx",
                37,
                "Feedback body reaches dangerouslySetInnerHTML unsanitized in this component",
                "It should be noted that the API sanitizes body on write.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void floorsAFindingWhoseAbsenceClaimIsWordedAsNothingSanitizes() {
    // #676 gap 1: MITIGATION_ABSENT's negators stopped at "no|not|never|...", so the
    // subject-worded absence "Nothing sanitizes ..." never registered as an absence claim and the
    // floor stayed silent on a named sink.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing sanitizes the value before innerHTML receives it.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void floorsAPronounAbsenceEvenWhenAnEarlierParticipleHasNoSubjectAtAll() {
    // "HTML-escaped" is a neutralizing-verb token with no pronoun subject before it and no copula
    // either — not an absence claim and not an asserted mitigation. The pronoun-subject scan must
    // step over it and still read the "nothing sanitizes" that follows.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "User text, HTML-escaped only in unit fixtures, reaches innerHTML; nothing"
                    + " sanitizes it in production.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void floorsAFindingWhoseAbsenceClaimIsWordedAsNothingIsSanitized() {
    // The auxiliary-order twin of the pronoun-subject absence: MITIGATION_ASSERTED_BE starts at
    // "is" and never sees the negating subject, so "Nothing is sanitized"
    // read as a mitigation and defeated the very floor the pronoun negators enable.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing is sanitized before the value reaches innerHTML.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void doesNotFloorWhenADoSupportedMitigationFollowsThePronounAbsence() {
    // "does escape" is emphatic but still a statement of fact, invisible to the
    // MITIGATION_ASSERTED_* copula and subject-slot shapes. With the pronoun absence match dropped
    // by NEGATING_SUBJECT,
    // this text would otherwise floor although it says another layer neutralizes the value —
    // the over-fire direction (#594).
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing is sanitized before innerHTML receives it, but the framework does escape"
                    + " the value at compile time.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorWhenANegatedAbsenceIsFollowedByASubjectSlotMitigation() {
    // The mitigation-asserted union walked leftmost-first: the negated "Nothing is sanitized"
    // match is stepped over and the later "React escapes" subject-slot assertion still defeats
    // the floor — the same reading the single-alternation pattern gave this text.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing is sanitized on write, yet React escapes the value at render, so the"
                    + " sink never sees live markup.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorWhenNothingEscapesParameterization() {
    // #696 item 1: the defense-noun lookahead omitted "parameteriz" although the verb group has
    // it, so "Nothing escapes parameterization" — a mitigation saying every value IS
    // parameterized — read as an absence claim and over-fired the floor.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/InvoiceExporter/Data/InvoiceRepository.cs",
                27,
                "Channel value concatenated into the SQL query text",
                "Nothing escapes parameterization before the query runs.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorWhenTheSubjectSlotMitigationPrecedesTheCopulaOne() {
    // Same union, opposite order: the subject-slot match sits before the copula match, so the
    // leftmost pick has to displace the copula candidate rather than keep it.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "React escapes the value at render because it is sanitized by the framework, so"
                    + " unsanitized markup never reaches the sink.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void floorsWhenTheAbsenceClaimTakesAModal() {
    // #696 item 2: the suffix group admitted only finite forms, so the bare infinitive a modal
    // takes ("Nothing can sanitize ...") never registered as an absence claim and the floor
    // stayed silent on a named sink.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing can sanitize the value before innerHTML receives it.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void doesNotFloorWhenTheModalAbsenceObjectIsADefenseNoun() {
    // The modal wording carries the same flipped reading: "Nothing can escape validation" says
    // every value IS validated, and must not register as an absence claim — over-firing is the
    // dangerous direction (#594).
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing can escape validation before the value reaches innerHTML.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorWhenTheDoSupportedMitigationCarriesAnAdverb() {
    // #696 item 3: MITIGATION_DO_SUPPORTED required the verb directly after the auxiliary, so
    // "does always escape" was invisible and the floor over-fired despite an asserted mitigation.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing is sanitized here, but the framework does always escape the value at"
                    + " render.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void floorsWhenDoSupportIsNegatedThroughTheAdverbGap() {
    // The do-support gap word must never be a negator: "does not escape" is the absence claim,
    // and reading it as a mitigation through the new adverb gap would silence the floor on a
    // demonstrated sink.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "The template engine does not escape the value before innerHTML receives it.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void doesNotFloorWhenPunctuationSeparatesTheDefenseNounObject() {
    // #696 item 4: the defense-noun lookahead's separator was whitespace-only, so "Nothing
    // escapes; the sanitizer runs on render" stopped at the semicolon and read as a bare absence
    // claim. Clause-break punctuation now counts as separation, so the same-sentence defense noun
    // still flips the reading.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes; the sanitizer runs on render.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void floorsWhenTheModalAbsenceCarriesAnAdverb() {
    // The modal wording admits one non-negator gap word, mirroring the do-support adverb gap:
    // "Nothing can ever sanitize ..." is the same absence claim and must still floor.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing can ever sanitize the value before innerHTML receives it.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void floorsWhenTheModalAbsenceSubjectCarriesAPhrase() {
    // The modal token shares the finite wording's subject prefix, so a phrase-modified subject
    // ("Nothing in this template can sanitize ...") registers just like the finite shape does.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing in this template can sanitize the value before innerHTML receives it.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void floorsWhenTheDefenseAcrossTheCommaIsDenied() {
    // The defense-noun separator deliberately excludes the comma: "Nothing escapes, but the
    // sanitizer is disabled" DENIES the defense it names, and letting the comma carry the
    // lookahead across would silence the floor on a stated absence claim.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes, but the sanitizer is disabled in this diff.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void floorsWhenTheAbsenceVerbsAreCoordinatedByCommas() {
    // Comma-coordinated absence verbs must not flip their own claim: in "Nothing escapes,
    // sanitizes, or validates the value" each continuation verb is part of the absence claim,
    // not a defense-noun object of the verb before it.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes, sanitizes, or validates the value before innerHTML receives it.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void doesNotFloorWhenAnAssertedMitigationFollowsTheNegatedAuxiliaryOrder() {
    // The mitigation-asserted scan must resume past a negated match: "Nothing is sanitized" is
    // dropped by NEGATING_SUBJECT, but the subject-slot assertion later in the same text still
    // defeats the floor — over-firing is the dangerous direction (#594).
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing is sanitized on write, yet the renderer escapes the value on render.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorWhenTheAssertedMitigationPrecedesTheNegatedAuxiliaryOrder() {
    // The mirror ordering: the subject-slot assertion sits BEFORE the negated auxiliary-order
    // match, so the leftmost-match walk must pick the earlier subject-slot wording over the
    // later-starting copula match, and the absence scan must step past the non-negated
    // "renderer escapes" token to register "nothing is sanitized".
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "The renderer escapes the value on render, although nothing is sanitized on"
                    + " write.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void floorsWhenALaterAbsenceVerbFollowsADefenseNounObject() {
    // The pronoun-subject scan must resume past a flipped verb: "Nothing escapes validation" is
    // the mitigated reading, but the second clause's "nothing sanitizes the value" is a real
    // absence claim and must still floor.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes validation and nothing sanitizes the value before innerHTML"
                    + " receives it.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void doesNotFloorWhenTheCommaCoordinatedFollowUpAssertsTheDefenseRuns() {
    // The comma keeps the absence claim registered (it must, for the denial cases), so the
    // follow-up clause "the sanitizer runs on render" — no auxiliary, verb not in the
    // subject-slot list — needs its own mitigation reading, or the floor over-fires on a
    // sentence that asserts the defense operates.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes, but the sanitizer runs on render.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorWhenAConjoinedVerbPairAssertsTheMitigation() {
    // A conjoined verb pair whose first verb is not defense-listed has "and escapes" as its ONLY
    // subject-verb match, so the coordinator must stay in the subject slot: only a coordinator
    // that continues a pronoun-negated verb chain is dropped, and "the output" before "and" is
    // no such chain.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes, but the framework renders the output and escapes it at render.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorWhenTheDefenseActionCarriesAnAdverb() {
    // The defense-action reading admits one non-negator gap word, mirroring the do-support
    // adverb gap: "the sanitizer always runs" asserts the defense operates and must defeat the
    // floor just like the unmodified "the sanitizer runs".
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes, but the sanitizer always runs on render.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorWhenTheMitigationFollowsAnUnbrokenTokenLongerThanTheNegationWindow() {
    // The negation window opens on a whitespace character; when the whole window is one unbroken
    // token (a long URL), there is none to open on, the region is empty, and the match counts as
    // unnegated — the same reading the full-prefix scan gave it.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing is sanitized before innerHTML receives it, see docs#"
                    + "a".repeat(120)
                    + ";renderer escapes the value at render.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void floorsWhenNoSanitizerRuns() {
    // The defense-action reading must not swallow its own denial: "no sanitizer runs" states the
    // defense does NOT operate, and the negating determiner before the match keeps it an absence
    // statement.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "The value reaches innerHTML and no sanitizer runs before it, nothing escapes it.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void floorsWhenTheSanitizerNeverRuns() {
    // The defense-action verb must follow its subject directly: "the sanitizer never runs" is an
    // absence statement, and the adverb gap that would read it as a mitigation is deliberately
    // absent.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes the value, and the sanitizer never runs on this path.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void floorsWhenTheDefenseNounSitsInTheNextSentence() {
    // Sentence-ending punctuation is excluded from the lookahead's separator on purpose: a
    // defense noun in the NEXT sentence is a different claim and must not defuse this sentence's
    // absence claim.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes. The sanitizer was removed in this diff.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void doesNotReadDoesNotProhibitSqlInjectionAsASinkDenial() {
    // "prohibit" belongs to the same warding-off family as "prevent": the negation targets the
    // missing defense, so the sentence asserts the defect and must not defuse the floor.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/InvoiceExporter/Data/InvoiceRepository.cs",
                27,
                "Unsanitized channel value concatenated into the query text",
                "The helper does not prohibit SQL injection; the value goes straight into the"
                    + " command string.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void doesNotFloorWhenTheDefenseNounIsTheObjectOfNothingEscapes() {
    // "Nothing escapes validation" says every value IS validated — a mitigation, not an absence.
    // The defense-noun object must not read as a pronoun-subject absence claim, and the noun must
    // not re-match as the verb through the word gap; over-firing is the dangerous direction
    // (#594).
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes validation before the value reaches innerHTML.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorWhenTheDefenseNounObjectCarriesAModifier() {
    // The defense-noun rejection must see through a modifier: "Nothing escapes heavy validation"
    // still says every value IS validated.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes heavy validation before the value reaches innerHTML.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void doesNotFloorWhenTheObjectNamesTheDefenseTool() {
    // Tool-named objects belong to the same mitigated reading: "Nothing escapes the sanitizer"
    // says the sanitizer catches everything.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes the sanitizer before the value reaches innerHTML.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void floorsWhenNothingEscapesTheValueItself() {
    // The other reading of the same shape: "nothing escapes the value" is the absence claim, and
    // the defense-noun rejection must not swallow it.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/components/Comment.tsx",
                14,
                "User comment written to innerHTML",
                "Nothing escapes the value before it reaches innerHTML.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void doesNotReadDoesNotPreventSqlInjectionAsASinkDenial() {
    // #676 gap 2: SINK_DENIED's one-word gap admitted bridge verbs, so "does not prevent SQL
    // injection" — an assertion of the defect — read as a denial of the sink and suppressed the
    // floor on a demonstrated string-built query.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/InvoiceExporter/Data/InvoiceRepository.cs",
                27,
                "Unsanitized channel value concatenated into the query text",
                "The helper does not prevent SQL injection; the value goes straight into the"
                    + " command string.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void doesNotReadDoesNotMitigateSqlInjectionAsASinkDenial() {
    // The bridge-verb set is the defense-verb family, not just "prevent": "does not mitigate SQL
    // injection" targets the missing defense the same way and must not read as a denial.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "low",
                "src/InvoiceExporter/Data/InvoiceRepository.cs",
                27,
                "Unsanitized channel value concatenated into the query text",
                "The library does not mitigate SQL injection on this path.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void floorsAConditionalWhoseCoordinatorSitsInsideTheProtasis() {
    // #676 gap 3: the conditional span ended at ANY coordinator, so the parenthetical "however"
    // inside "If, however, the API always sanitizes ..." truncated the protasis and left
    // "sanitizes" in text read as asserted, defeating the floor on a hypothesis the finding
    // rejects in the same sentence.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "medium",
                "medium",
                "src/components/FeedbackItem.tsx",
                37,
                "Stored XSS: feedback body rendered via dangerouslySetInnerHTML",
                "Nothing sanitizes the body in the provided material. If, however, the"
                    + " API always sanitizes the body on write, this is moot — but nothing"
                    + " shown does.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertEquals("high", result.findings().get(0).risk());
    assertEquals("medium", result.findings().get(0).confidence());
  }

  @Test
  void stillHonoursADenialWhoseGapWordIsNotABridgeVerb() {
    // The bridge exclusion is verb-shaped only: an adjective or quantifier in the one-word gap
    // ("no possible SQL injection") is still a denial and must keep suppressing the floor.
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/InvoiceExporter/Data/InvoiceRepository.cs",
                27,
                "Tenant filter naming is unvalidated by convention checks",
                "The query is built by the ORM, so there is no possible SQL injection here.",
                null,
                null));

    var result = service.verify(SESSION, original, "diff", "stack", "");

    assertSame(original, result);
    assertEquals("low", result.findings().get(0).risk());
  }

  @Test
  void stillHonoursADenialWithNoWordBetweenTheNegatorAndTheSink() {
    // The bridge-verb exclusion must not narrow the plain denial: "no XSS here" keeps
    // suppressing the floor, since over-firing is the dangerous direction (#594).
    when(reviewConfig.verifierEnabled()).thenReturn(false);
    ReviewResponse original =
        response(
            new ReviewResponse.Finding(
                "low",
                "high",
                "src/components/Banner.tsx",
                9,
                "innerHTML assignment on a constant unvalidated at build time",
                "The string is a compile-time literal, so there is no XSS here.",
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
