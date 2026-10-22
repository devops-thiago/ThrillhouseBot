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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link FindingScreen}: the per-call checks ahead of the rest of the chain. */
class FindingScreenTest {

  private static final String QUOTE_SOURCE = "### a.java\n+x\n";
  private static final AiReviewService.PromptInputs INPUTS =
      new AiReviewService.PromptInputs("fenced diff", "pr ctx", "", "stack", "tests", "prior", "");

  private final FindingQuoteValidator quoteValidator = mock(FindingQuoteValidator.class);
  private final FrameworkFalsePositiveFilter frameworkFilter =
      mock(FrameworkFalsePositiveFilter.class);
  private final FindingDeduplicator deduplicator = mock(FindingDeduplicator.class);
  private final FindingVerificationService verification = mock(FindingVerificationService.class);
  private final VerifierRejectionMemory rejectionMemory = mock(VerifierRejectionMemory.class);
  private final DiffBudgetPlanner budgetPlanner = mock(DiffBudgetPlanner.class);
  private final ReviewEvidence evidence = mock(ReviewEvidence.class);

  private final ReviewResponse raw = response(finding("raw"));
  private final ReviewResponse validated = response(finding("validated"));
  private final ReviewResponse filtered = response(finding("filtered"));
  private final ReviewResponse deduped = response(finding("deduped"));
  private final ReviewResponse candidates = response(finding("candidate"));
  private final ReviewResponse verified = response(finding("verified"));
  private final ReviewSession session =
      ReviewSession.create(TestScopes.scope("owner/repo"), "owner/repo", 7, "PR", "sha");

  private FindingScreen screen;

  @BeforeEach
  void setUp() {
    screen = screenWith(rejectionMemory);
    when(budgetPlanner.perCallInputBudget()).thenReturn(Integer.MAX_VALUE);
    when(evidence.forFindings(any())).thenReturn(FindingVerificationService.FindingEvidence.NONE);
    when(quoteValidator.validate(raw, QUOTE_SOURCE)).thenReturn(validated);
    when(frameworkFilter.filter(validated, QUOTE_SOURCE)).thenReturn(filtered);
    when(deduplicator.dedupe(filtered)).thenReturn(deduped);
    when(rejectionMemory.withoutRejectionsOnThisHead(eq(session), any())).thenReturn(candidates);
    when(verification.verify(anyLong(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(verified);
  }

  @Test
  void eachLaneRunsItsStagesInThisOrder() {
    assertEquals(
        List.of(
            EvidenceStage.class,
            QuoteValidationStage.class,
            FrameworkFilterStage.class,
            DedupeStage.class,
            RejectionScreenStage.class,
            VerificationStage.class,
            RememberVerdictsStage.class),
        stageTypes(screen.singleCallStages()));
    // The batch lane is the single-call lane without dedupe, which runs once over every batch.
    assertEquals(
        List.of(
            EvidenceStage.class,
            QuoteValidationStage.class,
            FrameworkFilterStage.class,
            RejectionScreenStage.class,
            VerificationStage.class,
            RememberVerdictsStage.class),
        stageTypes(screen.batchStages()));
    assertEquals(
        screen.singleCallStages().stream().filter(s -> !(s instanceof DedupeStage)).toList(),
        screen.batchStages(),
        "both lanes share the same stage instances");
  }

  @Test
  void singleCallRunsEveryCheckInOrderAndDedupesBeforeTheRejectionScreen() {
    var result = screen.screenSingleCall(session, raw, QUOTE_SOURCE, INPUTS, plan(), evidence);

    assertSame(verified, result);
    var order =
        inOrder(
            evidence, quoteValidator, frameworkFilter, deduplicator, rejectionMemory, verification);
    // #650/#475: evidence is read off the findings as the model raised them, before validation.
    order.verify(evidence).forFindings(raw.findings());
    order.verify(quoteValidator).validate(raw, QUOTE_SOURCE);
    order.verify(frameworkFilter).filter(validated, QUOTE_SOURCE);
    order.verify(deduplicator).dedupe(filtered);
    order.verify(rejectionMemory).withoutRejectionsOnThisHead(session, deduped);
    order
        .verify(verification)
        .verify(
            eq(ReviewTokenLedger.keyFor(session)),
            eq(candidates),
            eq("pr ctx"),
            eq("fenced diff"),
            eq("stack"),
            eq("prior"),
            eq(FindingVerificationService.FindingEvidence.NONE),
            any());
    order.verify(rejectionMemory).remember(session, candidates.findings(), verified.findings());
  }

  @Test
  void batchRunsTheSameChecksWithoutDeduping() {
    var result = screen.screenBatch(session, raw, QUOTE_SOURCE, INPUTS, plan(), evidence);

    assertSame(verified, result);
    verify(deduplicator, never()).dedupe(any());
    var order = inOrder(evidence, quoteValidator, frameworkFilter, rejectionMemory, verification);
    order.verify(evidence).forFindings(raw.findings());
    order.verify(quoteValidator).validate(raw, QUOTE_SOURCE);
    order.verify(frameworkFilter).filter(validated, QUOTE_SOURCE);
    // The batch's filtered findings go straight to the rejection screen; dedupe runs once later.
    order.verify(rejectionMemory).withoutRejectionsOnThisHead(session, filtered);
    order
        .verify(verification)
        .verify(anyLong(), eq(candidates), any(), any(), any(), any(), any(), any());
    order.verify(rejectionMemory).remember(session, candidates.findings(), verified.findings());
  }

  @Test
  void theVerifierCoverageIsRecordedOnThePlan() {
    when(verification.verify(anyLong(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              Consumer<VerificationCoverage> sink = inv.getArgument(7);
              sink.accept(new VerificationCoverage(3, 2));
              return verified;
            });
    var plan = plan();

    screen.screenBatch(session, raw, QUOTE_SOURCE, INPUTS, plan, evidence);

    assertEquals(new VerificationCoverage(3, 2), plan.verificationCoverage());
  }

  @Test
  void thePrContextIsBoundedToItsShareOfThePerCallBudgetBeforeTheVerifierSeesIt() {
    when(budgetPlanner.perCallInputBudget()).thenReturn(200);
    var longContext = "word ".repeat(2_000);
    var inputs = new AiReviewService.PromptInputs("d", longContext, "", "s", "t", "p", "");

    screen.screenSingleCall(session, raw, QUOTE_SOURCE, inputs, plan(), evidence);

    var sent = ArgumentCaptor.forClass(String.class);
    verify(verification)
        .verify(anyLong(), any(), sent.capture(), any(), any(), any(), any(), any());
    assertTrue(sent.getValue().length() < longContext.length(), "the context is clipped");
    assertTrue(sent.getValue().contains(PrContextBudget.TRUNCATION_NOTICE), "and says so");
  }

  @Test
  void aClaimTheVerifierRejectedOnThisHeadIsNotPutToItAgain() {
    var memory = new VerifierRejectionMemory();
    var realScreen = screenWith(memory);
    var claim = finding("claim");
    when(quoteValidator.validate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(frameworkFilter.filter(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    // First round: the verifier rejects the only candidate.
    when(verification.verify(anyLong(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(response());
    realScreen.screenBatch(session, response(claim), QUOTE_SOURCE, INPUTS, plan(), evidence);

    // Second round on the same head: the claim is screened out before the call.
    var sent = ArgumentCaptor.forClass(ReviewResponse.class);
    realScreen.screenBatch(session, response(claim), QUOTE_SOURCE, INPUTS, plan(), evidence);

    verify(verification, times(2))
        .verify(anyLong(), sent.capture(), any(), any(), any(), any(), any(), any());
    assertEquals(List.of(claim), sent.getAllValues().get(0).findings());
    assertEquals(List.of(), sent.getAllValues().get(1).findings());
  }

  private static List<Class<?>> stageTypes(List<FindingStage> stages) {
    return stages.stream().<Class<?>>map(FindingStage::getClass).toList();
  }

  private FindingScreen screenWith(VerifierRejectionMemory memory) {
    return FindingScreens.of(
        quoteValidator,
        frameworkFilter,
        deduplicator,
        verification,
        memory,
        budgetPlanner,
        new TokenCounter());
  }

  private static DiffBudgetPlanner.BudgetPlan plan() {
    return new DiffBudgetPlanner.BudgetPlan(
        List.of(), List.of(), List.of(), true, null, null, null, null);
  }

  private static ReviewResponse.Finding finding(String title) {
    return new ReviewResponse.Finding(
        "medium", "high", "a.java", 1, title, "desc", "old line", "new line");
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    return new ReviewResponse(List.of(findings), List.of(), null);
  }
}
