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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link FindingStage}s that do more than hand the response to a collaborator.
 */
class FindingStageTest {

  private static final AiReviewService.PromptInputs INPUTS =
      new AiReviewService.PromptInputs("fenced diff", "pr ctx", "", "stack", "tests", "prior", "");

  private final ReviewSession session =
      ReviewSession.create(TestScopes.scope("owner/repo"), "owner/repo", 7, "PR", "sha");
  private final ReviewEvidence evidence = mock(ReviewEvidence.class);
  private final ReviewResponse raw = response(finding("raw"));
  private final DiffBudgetPlanner.BudgetPlan plan =
      new DiffBudgetPlanner.BudgetPlan(
          List.of(), List.of(), List.of(), true, null, null, null, null);

  @Test
  void theStartingContextHasNoEvidenceAndNoCandidates() {
    var context = start();

    assertSame(raw, context.response());
    assertSame(FindingVerificationService.FindingEvidence.NONE, context.attached());
    assertNull(context.candidates());
  }

  @Test
  void theEvidenceStageResolvesEvidenceForTheFindingsAsTheyStand() {
    var attached = mock(FindingVerificationService.FindingEvidence.class);
    when(evidence.forFindings(raw.findings())).thenReturn(attached);

    var next = new EvidenceStage().apply(start());

    assertSame(attached, next.attached());
    assertSame(raw, next.response(), "the findings themselves are left alone");
  }

  @Test
  void theRejectionScreenStageKeepsOnlyWhatThisHeadHasNotRejected() {
    var memory = mock(VerifierRejectionMemory.class);
    var kept = response();
    when(memory.withoutRejectionsOnThisHead(session, raw)).thenReturn(kept);

    var next = new RejectionScreenStage(memory).apply(start());

    assertSame(kept, next.response());
  }

  @Test
  void theVerificationStageSendsTheResponseAndRecordsItAsTheCandidates() {
    var verification = mock(FindingVerificationService.class);
    var budgetPlanner = mock(DiffBudgetPlanner.class);
    when(budgetPlanner.perCallInputBudget()).thenReturn(Integer.MAX_VALUE);
    var verified = response(finding("verified"));
    when(verification.verify(anyLong(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              Consumer<VerificationCoverage> sink = inv.getArgument(7);
              sink.accept(new VerificationCoverage(1, 1));
              return verified;
            });
    var attached = mock(FindingVerificationService.FindingEvidence.class);

    var next =
        new VerificationStage(verification, budgetPlanner, new TokenCounter())
            .apply(start().withAttached(attached));

    verify(verification)
        .verify(
            eq(ReviewTokenLedger.keyFor(session)),
            eq(raw),
            eq("pr ctx"),
            eq("fenced diff"),
            eq("stack"),
            eq("prior"),
            eq(attached),
            any());
    assertSame(verified, next.response());
    assertSame(raw, next.candidates());
    assertEquals(new VerificationCoverage(1, 1), plan.verificationCoverage());
  }

  @Test
  void theRememberStageRemembersTheVerdictsAgainstTheCandidatesSent() {
    var memory = mock(VerifierRejectionMemory.class);
    var verified = response(finding("verified"));
    var context = start().withVerified(verified);

    var next = new RememberVerdictsStage(memory).apply(context);

    verify(memory).remember(session, raw.findings(), verified.findings());
    assertSame(context, next);
  }

  private ScreeningContext start() {
    return ScreeningContext.start(session, raw, "### a.java\n+x\n", INPUTS, plan, evidence);
  }

  private static ReviewResponse.Finding finding(String title) {
    return new ReviewResponse.Finding(
        "medium", "high", "a.java", 1, title, "desc", "old line", "new line");
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    return new ReviewResponse(List.of(findings), List.of(), null);
  }
}
