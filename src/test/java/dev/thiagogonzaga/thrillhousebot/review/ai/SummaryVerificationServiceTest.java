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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.PromptTemplateEscaper;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SummaryVerificationServiceTest {

  private static final long SESSION = 42L;

  /** The kotlin ThrillhouseBot-test#252 summary: S2, R2 and D2 state the planted gap. */
  private static final ReviewResponse.Summary SUMMARY_252 =
      new ReviewResponse.Summary(
          1,
          0,
          0,
          1,
          0,
          "assessment",
          "Adds a nightly billing job that totals each tenant's usage. It writes a billing_runs"
              + " summary row for every run.",
          List.of(),
          List.of(),
          List.of(
              new ReviewResponse.FileSummary("src/BillingJob.kt", "Totals usage per tenant"),
              new ReviewResponse.FileSummary("src/BillingRepo.kt", "Writes the billing_runs row")),
          "flowchart TD\n  A[Trigger] --> B[Total usage]\n  B --> C[Record billing_runs summary]");

  private static final String DIFF =
      """
      ### src/BillingJob.kt (modified, +3 -0)
      ```diff
      +fun run() = tenants.forEach { total(it) }
      ```
      """;

  private static final ReviewResponse.Finding FINDING =
      new ReviewResponse.Finding(
          "medium",
          "high",
          "src/BillingJob.kt",
          1,
          "No billing_runs row is written",
          "The job totals usage but never records a run.",
          null,
          null);

  private final SummaryVerifier verifier = mock(SummaryVerifier.class);
  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);
  private final ThrillhouseConfig.ReviewConfig reviewConfig =
      mock(ThrillhouseConfig.ReviewConfig.class);
  private final ThrillhouseConfig.DiagramConfig diagramConfig =
      mock(ThrillhouseConfig.DiagramConfig.class);
  private final ReviewTokenLedger ledger = mock(ReviewTokenLedger.class);

  private SummaryVerificationService service;

  @BeforeEach
  void setUp() {
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.diagram()).thenReturn(diagramConfig);
    when(reviewConfig.summaryVerificationEnabled()).thenReturn(true);
    when(diagramConfig.enabled()).thenReturn(true);
    service = newService();
  }

  private SummaryVerificationService newService() {
    return new SummaryVerificationService(
        verifier,
        config,
        new ObjectMapper(),
        ledger,
        TestResponseCaps.defaults(),
        new TokenCounter());
  }

  private static SummaryVerificationService.Material material() {
    return new SummaryVerificationService.Material(
        "", List.of(DIFF), "", List.of(FINDING), Integer.MAX_VALUE);
  }

  private void answer(String json) {
    when(verifier.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiOk(json));
  }

  @Test
  void contradictedSentenceRowAndDiagramLineAreRemoved() {
    answer(
        """
        {"contradicted": [
          {"id": "S2", "evidence": "no added line writes billing_runs"},
          {"id": "r2", "evidence": "finding: No billing_runs row is written"},
          {"id": "D2", "evidence": "no added line writes billing_runs"}
        ]}
        """);

    var verified = service.verify(SESSION, SUMMARY_252, material());

    assertEquals(
        "Adds a nightly billing job that totals each tenant's usage.", verified.prPurpose());
    assertEquals(
        List.of(new ReviewResponse.FileSummary("src/BillingJob.kt", "Totals usage per tenant")),
        verified.fileSummaries());
    assertNull(verified.walkthroughDiagram());
  }

  @Test
  void anEmptyListKeepsTheSummary() {
    answer("{\"contradicted\": []}");

    var verified = service.verify(SESSION, SUMMARY_252, material());

    assertSame(SUMMARY_252, verified);
  }

  @Test
  void anEntryWithNoEvidenceOrNoIdCutsNothing() {
    answer(
        """
        {"contradicted": [{"id": "S2"}, {"id": "R2", "evidence": "  "}, {"evidence": "x"}, "S1"]}
        """);

    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material()));
  }

  @Test
  void theAnswerIsReadAfterAnyDeliberation() {
    answer(
        "Checking S2 against the diff.\n```json\n{\"contradicted\": [{\"id\": \"S2\","
            + " \"evidence\": \"nothing writes billing_runs\"}]}\n```");

    var verified = service.verify(SESSION, SUMMARY_252, material());

    assertEquals(
        "Adds a nightly billing job that totals each tenant's usage.", verified.prPurpose());
  }

  @Test
  void disabledByDefaultMakesNoCall() {
    when(reviewConfig.summaryVerificationEnabled()).thenReturn(false);
    service = newService();

    assertFalse(service.enabled());
    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material()));
    verifyNoInteractions(verifier, ledger);
  }

  @Test
  void theDisabledInstanceMakesNoCall() {
    var disabled = SummaryVerificationService.disabled();

    assertFalse(disabled.enabled());
    assertSame(SUMMARY_252, disabled.verify(SESSION, SUMMARY_252, material()));
  }

  @Test
  void aCallErrorKeepsTheSummary() {
    when(verifier.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("provider down"));

    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material()));
  }

  @Test
  void aCallThatFindsTheGateFullKeepsTheSummary() {
    when(verifier.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new AiReviewException("no model call slot within the wait", 1, null));

    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material()));
  }

  @Test
  void aMalformedAnswerKeepsTheSummary() {
    for (var raw :
        List.of(
            "not json",
            "{\"contradicted\": \"S2\"}",
            "{\"other\": []}",
            "[{\"id\": \"S2\", \"evidence\": \"x\"}]",
            "{\"contradicted\": [")) {
      answer(raw);
      assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material()), raw);
    }
  }

  @Test
  void aCutOrEmptyAnswerKeepsTheSummary() {
    when(verifier.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiTruncated("{\"contradicted\": [{\"id\": \"S2\", \"evid"));
    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material()));

    when(verifier.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiNoContent());
    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material()));

    when(verifier.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(aiOk("  "));
    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material()));

    when(verifier.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(null);
    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material()));
  }

  @Test
  void theSpendCeilingSkipsTheCall() {
    when(ledger.ceilingReached(SESSION)).thenReturn(true);

    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material()));
    verifyNoInteractions(verifier);
  }

  @Test
  void theCallsUsageIsRecordedEvenWhenTheAnswerIsCut() {
    when(verifier.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            Result.<String>builder()
                .content("{\"contra")
                .finishReason(FinishReason.LENGTH)
                .tokenUsage(new TokenUsage(1200, 80))
                .build());

    service.verify(SESSION, SUMMARY_252, material());

    verify(ledger).recordUsage(SESSION, 1200, 80);
  }

  @Test
  void aCallWithNoReportedUsageRecordsNothing() {
    answer("{\"contradicted\": []}");

    service.verify(SESSION, SUMMARY_252, material());

    verify(ledger, never()).recordUsage(anyLong(), any(), any());
  }

  @Test
  void nothingToCheckOrNothingToCheckAgainstMakesNoCall() {
    var noStatements =
        new ReviewResponse.Summary(0, 0, 0, 0, 0, "a", " ", List.of(), List.of(), List.of(), null);
    assertSame(noStatements, service.verify(SESSION, noStatements, material()));
    assertNull(service.verify(SESSION, null, material()));
    var noEvidence =
        new SummaryVerificationService.Material(
            "ctx", List.of(" ", ""), "", List.of(), Integer.MAX_VALUE);
    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, noEvidence));
    var nulls = new SummaryVerificationService.Material(null, null, null, null, Integer.MAX_VALUE);
    var nullParts =
        new SummaryVerificationService.Material(
            "", java.util.Arrays.asList(null, " "), "", List.of(), Integer.MAX_VALUE);
    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, nullParts));
    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, nulls));
    verifyNoInteractions(verifier);
  }

  @Test
  void theDiagramIsNotSentWhenItIsNotRendered() {
    when(diagramConfig.enabled()).thenReturn(false);
    service = newService();
    answer("{\"contradicted\": [{\"id\": \"D1\", \"evidence\": \"x\"}]}");

    var verified = service.verify(SESSION, SUMMARY_252, material());

    assertSame(SUMMARY_252, verified);
    var statements = ArgumentCaptor.forClass(String.class);
    verify(verifier)
        .verify(statements.capture(), anyString(), anyString(), anyString(), anyString());
    assertFalse(statements.getValue().contains("[D1]"));
  }

  @Test
  void theSlotsCarryFencedStatementsTheFindingsAndTheFencedDiff() {
    answer("{\"contradicted\": []}");
    var withContext =
        new SummaryVerificationService.Material(
            "PR_CONTEXT", List.of(DIFF, "second part"), "STACK", List.of(FINDING), 1_000_000);

    service.verify(SESSION, SUMMARY_252, withContext);

    var statements = ArgumentCaptor.forClass(String.class);
    var findings = ArgumentCaptor.forClass(String.class);
    var prContext = ArgumentCaptor.forClass(String.class);
    var diff = ArgumentCaptor.forClass(String.class);
    var stack = ArgumentCaptor.forClass(String.class);
    verify(verifier)
        .verify(
            statements.capture(),
            findings.capture(),
            prContext.capture(),
            diff.capture(),
            stack.capture());
    assertTrue(statements.getValue().startsWith(PromptTemplateEscaper.fencePrefix()));
    assertTrue(
        PromptTemplateEscaper.unfence(statements.getValue())
            .contains("[S2] It writes a billing_runs summary row for every run."));
    assertTrue(findings.getValue().contains("\"title\":\"No billing_runs row is written\""));
    assertTrue(findings.getValue().contains("\"file\":\"src/BillingJob.kt\",\"line\":1"));
    assertEquals("PR_CONTEXT", prContext.getValue());
    assertEquals(DIFF + "\nsecond part", PromptTemplateEscaper.unfence(diff.getValue()));
    assertEquals("STACK", stack.getValue());
  }

  @Test
  void onlyTheDiffPartsThatFitTheBudgetAreSent() {
    answer("{\"contradicted\": []}");
    var counter = new TokenCounter();
    var big = "+ line\n".repeat(4000);
    var overhead = budgetOverhead(counter);
    var material =
        new SummaryVerificationService.Material(
            "", List.of(big, DIFF), "", List.of(FINDING), overhead + 2_000);

    service.verify(SESSION, SUMMARY_252, material);

    var diff = ArgumentCaptor.forClass(String.class);
    verify(verifier).verify(anyString(), anyString(), anyString(), diff.capture(), anyString());
    assertEquals(DIFF, PromptTemplateEscaper.unfence(diff.getValue()));
  }

  @Test
  void withNoDiffPartFittingTheFindingsAloneAreCheckedAgainst() {
    answer("{\"contradicted\": [{\"id\": \"S2\", \"evidence\": \"finding\"}]}");
    var material =
        new SummaryVerificationService.Material(
            "",
            List.of("+ line\n".repeat(4000)),
            "",
            List.of(FINDING),
            budgetOverhead(new TokenCounter()) + 100);

    var verified = service.verify(SESSION, SUMMARY_252, material);

    var diff = ArgumentCaptor.forClass(String.class);
    verify(verifier).verify(anyString(), anyString(), anyString(), diff.capture(), anyString());
    assertEquals("", diff.getValue());
    assertEquals(
        "Adds a nightly billing job that totals each tenant's usage.", verified.prPurpose());
  }

  @Test
  void withNoDiffAtAllTheFindingsAloneAreCheckedAgainst() {
    answer("{\"contradicted\": [{\"id\": \"R2\", \"evidence\": \"finding\"}]}");
    var material =
        new SummaryVerificationService.Material(
            "", List.of(), "", List.of(FINDING), Integer.MAX_VALUE);

    var verified = service.verify(SESSION, SUMMARY_252, material);

    var diff = ArgumentCaptor.forClass(String.class);
    verify(verifier).verify(anyString(), anyString(), anyString(), diff.capture(), anyString());
    assertEquals("", diff.getValue());
    assertEquals(1, verified.fileSummaries().size());
  }

  @Test
  void withNoDiffPartFittingAndNoFindingsNoCallIsMade() {
    var material =
        new SummaryVerificationService.Material(
            "",
            List.of("+ line\n".repeat(4000)),
            "",
            List.of(),
            budgetOverhead(new TokenCounter()) + 100);

    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material));
    verifyNoInteractions(verifier);
  }

  @Test
  void aPromptOverTheBudgetWithoutTheDiffMakesNoCall() {
    var material =
        new SummaryVerificationService.Material("", List.of(DIFF), "", List.of(FINDING), 50);

    assertSame(SUMMARY_252, service.verify(SESSION, SUMMARY_252, material));
    verifyNoInteractions(verifier);
  }

  @Test
  void contradictedIdsThrowsOnAnAnswerWithoutTheList() throws IOException {
    assertThrows(IOException.class, () -> service.contradictedIds("{\"other\": 1}"));
    assertEquals(
        Set.of("S1"),
        service.contradictedIds("{\"contradicted\": [{\"id\": \" s1 \", \"evidence\": \"e\"}]}"));
  }

  /**
   * An upper bound on the prompt around the diff for {@link #SUMMARY_252} and {@link #FINDING}: the
   * templates, the statements and findings as sent, and a fence.
   */
  private static int budgetOverhead(TokenCounter counter) {
    return counter.estimateTokens(SummaryVerifierPrompts.SYSTEM)
        + counter.estimateTokens(SummaryVerifierPrompts.USER)
        + counter.estimateTokens(SummaryStatements.of(SUMMARY_252, true).render())
        + 300;
  }
}
