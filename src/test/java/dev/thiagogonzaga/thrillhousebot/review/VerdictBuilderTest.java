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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link VerdictBuilder#build}'s truncation accounting: the disclosed omitted count
 * must reflect what was actually sent — plan omissions when budgeting is on, the legacy line-cap
 * count when it is off — never the sum of both.
 */
class VerdictBuilderTest {

  private final FollowUpAnalyzer followUpAnalyzer = mock(FollowUpAnalyzer.class);
  private final PrSummaryGenerator summaryGenerator = mock(PrSummaryGenerator.class);

  private final VerdictBuilder builder =
      new VerdictBuilder(
          summaryGenerator,
          followUpAnalyzer,
          BotIdentity.from(List.of("thrillhousebot[bot]")),
          BlockingStrictness.BALANCED);

  {
    lenient()
        .when(
            followUpAnalyzer.unresolvedFindings(
                org.mockito.ArgumentMatchers
                    .<java.util.List<
                            dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse.Finding>>
                        any(),
                any()))
        .thenReturn(List.of());
    lenient()
        .when(followUpAnalyzer.supersedeVanished(any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              List<?> statuses = inv.getArgument(1);
              return statuses == null ? List.of() : statuses;
            });
    lenient()
        .when(followUpAnalyzer.addUnreportedVanished(any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              List<?> statuses = inv.getArgument(1);
              return statuses == null ? List.of() : statuses;
            });
    lenient()
        .when(followUpAnalyzer.recheckDeclines(any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              List<?> statuses = inv.getArgument(1);
              return statuses == null ? List.of() : statuses;
            });
    lenient()
        .when(followUpAnalyzer.clearNamedInConversation(any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              List<?> statuses = inv.getArgument(1);
              return statuses == null ? List.of() : statuses;
            });
    lenient()
        .when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
        .thenReturn("");
  }

  /** A context whose legacy diff render dropped {@code lineCapOmitted} files. */
  private static ReviewContextLoader.ReviewContext contextWithLineCapOmissions(int lineCapOmitted) {
    return contextWithLineCapOmissions(
        lineCapOmitted, List.of(new FileDiff("a.java", "modified", 1, 0, 1, "")));
  }

  /** Same context with a caller-supplied reviewable-file list. */
  private static ReviewContextLoader.ReviewContext contextWithLineCapOmissions(
      int lineCapOmitted, List<FileDiff> reviewableFiles) {
    return new ReviewContextLoader.ReviewContext(
        List.of(),
        "diff",
        "",
        lineCapOmitted,
        List.of(),
        List.of(),
        List.of(),
        true,
        false,
        null,
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        reviewableFiles,
        () -> new DiffLineResolver(Map.of()),
        null);
  }

  private static final ReviewResponse CLEAN_RESPONSE =
      new ReviewResponse(List.of(), List.of(), null);

  private static final CiStatusEvaluator.CiEvaluation CI_CLEAR =
      new CiStatusEvaluator.CiEvaluation(List.of(), false);

  @Test
  void budgetedReviewDisclosesOnlyThePlanOmissions() {
    var ctx = contextWithLineCapOmissions(3);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("big.java"), List.of(), true, null, null, null, null);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(1, result.omittedFiles());
    assertTrue(result.truncated());
  }

  @Test
  void budgetedReviewWithFullCoverageIsNotTruncated() {
    var ctx = contextWithLineCapOmissions(3);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(0, result.omittedFiles());
    assertFalse(result.truncated());
    assertEquals(ReviewState.APPROVE, result.reviewState());
  }

  @Test
  void aFailedBatchsRuntimeUncoveredFilesHoldApprovalAndAreDisclosedAsCallFailures() {
    // Lead#3 + #655: a batch that failed all its retries records its files on the shared plan. The
    // verdict reads that same instance and must gate approval on them exactly like a planned
    // omission — but the disclosure must say the review call did not complete, not blame the diff
    // budget, so the banner agrees with the summary overview's per-file note.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    plan.recordUncoveredFiles(List.of("failed.java"));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(1, result.omittedFiles());
    assertTrue(result.truncated());
    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertEquals(List.of(), result.truncation().omittedFileNames());
    assertEquals(List.of("failed.java"), result.truncation().callFailedFileNames());
    assertTrue(
        result
            .summaryMarkdown()
            .contains(
                "1 file(s) were not reviewed because the review call for them did not complete"
                    + " (failed.java)"),
        result.summaryMarkdown());
    assertFalse(result.summaryMarkdown().contains("review budget"), result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains("1 file(s) not reviewed (review call did not complete)"),
        checkSummary);
  }

  @Test
  void aRuntimeUncoveredFileThatWasAlsoClippedIsCountedOnceAsACallFailure() {
    // No double-count: a clipped file whose batch then failed is reported as not reviewed, not
    // also as partially analyzed.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of("f.java"), true, null, null, null, null);
    plan.recordUncoveredFiles(List.of("f.java"));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(1, result.omittedFiles());
    assertTrue(
        result
            .summaryMarkdown()
            .contains("not reviewed because the review call for them did not complete (f.java)"),
        result.summaryMarkdown());
    assertFalse(result.summaryMarkdown().contains("partially analyzed"), result.summaryMarkdown());
  }

  @Test
  void aPlannedOmissionAndACallFailureAreDisclosedUnderTheirOwnReasons() {
    // #655: the two classes coexist in one review — the planned omission keeps the budget wording,
    // the failed call gets its own clause, and neither file is listed twice.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("big.java"), List.of(), true, null, null, null, null);
    plan.recordUncoveredFiles(List.of("failed.java"));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(2, result.omittedFiles());
    assertEquals(List.of("big.java"), result.truncation().omittedFileNames());
    assertEquals(List.of("failed.java"), result.truncation().callFailedFileNames());
    var summary = result.summaryMarkdown();
    assertTrue(summary.contains("omitted entirely (big.java)"), summary);
    assertTrue(
        summary.contains(
            "1 file(s) were not reviewed because the review call for them did not complete"
                + " (failed.java)"),
        summary);
  }

  @Test
  void aSpendCeilingSkipIsNotAlsoDisclosedAsACallFailure() {
    // Ceiling skips flow through recordUncoveredFiles too, but their cause is a deliberate stop —
    // the call-failed clause must not claim them.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    plan.recordSpendCeilingSkippedFiles(List.of("skipped.java"));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(List.of("skipped.java"), result.truncation().spendCeilingSkippedFileNames());
    assertEquals(List.of(), result.truncation().callFailedFileNames());
    assertFalse(result.summaryMarkdown().contains("did not complete"), result.summaryMarkdown());
  }

  @Test
  void spendCeilingSkippedFilesAreDisclosedWithTheCeilingAsTheReason() {
    // #499: a batch skipped at the token spend ceiling withholds coverage like any runtime gap —
    // holds approval, counts as omitted — but the rendered disclosure must name the ceiling (a
    // spend limit with its own knob), not the diff budget, as the reason.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("big.java"), List.of(), true, null, null, null, null);
    plan.recordSpendCeilingSkippedFiles(List.of("skipped.java"));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(2, result.omittedFiles());
    assertTrue(result.truncated());
    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertEquals(List.of("big.java"), result.truncation().omittedFileNames());
    assertEquals(List.of("skipped.java"), result.truncation().spendCeilingSkippedFileNames());
    assertTrue(
        result.summaryMarkdown().contains("omitted entirely (big.java)"), result.summaryMarkdown());
    assertTrue(
        result
            .summaryMarkdown()
            .contains(
                "1 file(s) were not reviewed because the review's token spend ceiling"
                    + " (REVIEW_MAX_TOKENS_PER_REVIEW) was reached (skipped.java)"),
        result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(checkSummary.contains("1 file(s) skipped at the token spend ceiling"), checkSummary);
  }

  @Test
  void responseCutFilesAreDisclosedAsPartiallyReviewedAndHoldApproval() {
    // #500: a batch whose response was cut but salvaged is a third kind of partial coverage — the
    // files were reviewed up to the cut and the findings kept, so the disclosure must say the
    // response was cut (naming the cap), hold approval, and NOT list the files as "not reviewed".
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    plan.recordResponseCutFiles(List.of("cut.java"));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(1, result.omittedFiles());
    assertTrue(result.truncated());
    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertEquals(List.of("cut.java"), result.truncation().responseCutFileNames());
    assertTrue(
        result
            .summaryMarkdown()
            .contains(
                "1 file(s) were only partially reviewed because the model's response was cut at"
                    + " its length cap (max-output-tokens) — findings up to the cut were kept"
                    + " (cut.java)"),
        result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains("1 file(s) partially reviewed (response cut at the length cap)"),
        checkSummary);
  }

  @Test
  void aResponseCutFileThatWasAlsoClippedIsDisclosedOnceAsPartiallyReviewed() {
    // A clipped file's batch can also have its response cut. One file, one disclosure: the
    // stronger (output-side) statement wins, and the file is never counted twice.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of("c.java"), true, null, null, null, null);
    plan.recordResponseCutFiles(List.of("c.java"));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(1, result.omittedFiles());
    assertEquals(List.of(), result.truncation().clippedFileNames());
    assertEquals(List.of("c.java"), result.truncation().responseCutFileNames());
    assertFalse(result.summaryMarkdown().contains("partially analyzed"), result.summaryMarkdown());
  }

  @Test
  void aSummaryOnlyCutIsDisclosedWithoutHoldingApproval() {
    // #500 scope A: the summary call's response was cut but every batch succeeded — the findings
    // are complete, so approval must not be held, but the posted review must say the summary was
    // shortened (naming both knobs) instead of leaving the cut log-only.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    plan.recordSummaryDegradation(SummaryDegradation.RESPONSE_CUT);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(0, result.omittedFiles());
    assertFalse(result.truncated());
    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertEquals(SummaryDegradation.RESPONSE_CUT, result.truncation().summaryDegradation());
    assertTrue(
        result.summaryMarkdown().contains("**Summary shortened.**"), result.summaryMarkdown());
    assertTrue(
        result.summaryMarkdown().contains("REVIEW_CONCISE_MAX_OUTPUT_TOKENS"),
        result.summaryMarkdown());
    // The partial-review banner's framing (findings cover only part of the diff) would overstate
    // a summary-only cut.
    assertFalse(result.summaryMarkdown().contains("partial review"), result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains("The summary was shortened (response cut at the length cap)."),
        checkSummary);
  }

  @Test
  void aCeilingSkippedSummaryIsDisclosedWithoutHoldingApproval() {
    // #518: the ceiling flavor of the summary degradation — every batch succeeded, the summary
    // call was skipped at the token spend ceiling. Like the summary-only cut, the findings are
    // complete: approval must not be held, but the posted review must name the ceiling instead of
    // leaving the degradation log-only.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    plan.recordSummaryDegradation(SummaryDegradation.SKIPPED_AT_CEILING);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(0, result.omittedFiles());
    assertFalse(result.truncated());
    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertEquals(SummaryDegradation.SKIPPED_AT_CEILING, result.truncation().summaryDegradation());
    assertTrue(result.summaryMarkdown().contains("**Summary skipped.**"), result.summaryMarkdown());
    assertTrue(
        result.summaryMarkdown().contains("REVIEW_MAX_TOKENS_PER_REVIEW"),
        result.summaryMarkdown());
    // The partial-review banner's framing (findings cover only part of the diff) would overstate
    // a summary-only degradation.
    assertFalse(result.summaryMarkdown().contains("partial review"), result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains("The summary was skipped (token spend ceiling reached)."),
        checkSummary);
  }

  @Test
  void anUnverifiedFindingSetIsDisclosedWithoutHoldingApproval() {
    // #623: verification failed open (empty body or cut response) and the findings posted anyway
    // — correct — but nothing on any surface said no second stage had screened them. The posted
    // review and the check run must both state it, and the verdict must not move: fail-open stays.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    plan.recordVerificationCoverage(new VerificationCoverage(3, 0));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertFalse(result.truncated());
    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertEquals(new VerificationCoverage(3, 0), result.truncation().verification());
    assertTrue(
        result.summaryMarkdown().contains("**Findings not fully verified.**"),
        result.summaryMarkdown());
    assertTrue(
        result.summaryMarkdown().contains("The 3 finding(s) were NOT verified"),
        result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains(
            "The 3 finding(s) were not verified (the verification call did not complete)."),
        checkSummary);
  }

  @Test
  void partialVerificationCoverageDisclosesTheHonestXOfY() {
    // #554/#617 salvage partial verdicts: some findings were verified, others not, and the
    // disclosure must represent that state rather than collapsing it either way.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    plan.recordVerificationCoverage(new VerificationCoverage(4, 3));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertTrue(
        result.summaryMarkdown().contains("only covered 3 of the 4 finding(s)"),
        result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains(
            "Verification covered 3 of 4 finding(s); the rest posted unverified."),
        checkSummary);
  }

  @Test
  void fullVerificationCoverageRendersNoDisclosure() {
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    plan.recordVerificationCoverage(new VerificationCoverage(2, 2));
    plan.recordVerificationCoverage(new VerificationCoverage(3, 3));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    // The two batches accumulate to a fully covered set — the common case adds nothing anywhere.
    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertFalse(
        result.summaryMarkdown().contains("**Findings not fully verified.**"),
        result.summaryMarkdown());
    assertFalse(
        VerdictBuilder.checkSummaryForResult(result).contains("verification"),
        VerdictBuilder.checkSummaryForResult(result));
  }

  @Test
  void anUnverifiedFindingSetAlongsideFileGapsFoldsIntoTheCoverageClause() {
    // With a real file gap the partial-review banner renders anyway, so the verification gap
    // becomes one more clause there — the dedicated banner must not stack on top.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("big.java"), List.of(), true, null, null, null, null);
    plan.recordVerificationCoverage(new VerificationCoverage(2, 0));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertTrue(result.truncated());
    assertTrue(
        result
            .summaryMarkdown()
            .contains("the 2 finding(s) were NOT verified by the second-pass audit"),
        result.summaryMarkdown());
    assertFalse(
        result.summaryMarkdown().contains("**Findings not fully verified.**"),
        result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains("2 finding(s) unverified (verification call did not complete)"),
        checkSummary);
  }

  @Test
  void anUnverifiedFindingSetIsDisclosedOnTheLegacyUnbudgetedLane() {
    // The legacy uncapped single call (max-input-tokens=0) verifies its findings too; the
    // disclosure must not silently depend on the plan being budgeted.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), false, null, null, null, null);
    plan.recordVerificationCoverage(new VerificationCoverage(1, 0));

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertTrue(
        result.summaryMarkdown().contains("**Findings not fully verified.**"),
        result.summaryMarkdown());
  }

  @Test
  void aCeilingSkippedSummaryAlongsideFileGapsFoldsIntoTheCoverageClause() {
    // With a real file gap the partial-review banner renders anyway, so the ceiling skip becomes
    // one more clause there — the dedicated summary-only banner must not stack on top.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("big.java"), List.of(), true, null, null, null, null);
    plan.recordSummaryDegradation(SummaryDegradation.SKIPPED_AT_CEILING);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertTrue(result.truncated());
    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertTrue(
        result
            .summaryMarkdown()
            .contains("the summary was skipped because the review's token spend ceiling"),
        result.summaryMarkdown());
    assertFalse(
        result.summaryMarkdown().contains("**Summary skipped.**"), result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains("summary skipped (token spend ceiling reached)"), checkSummary);
  }

  @Test
  void aSummaryCutAlongsideFileGapsFoldsIntoTheCoverageClause() {
    // With a real file gap present the partial-review banner renders anyway, so the summary cut
    // becomes one more clause there — the dedicated summary-only banner must not stack on top.
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("big.java"), List.of(), true, null, null, null, null);
    plan.recordSummaryDegradation(SummaryDegradation.RESPONSE_CUT);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertTrue(result.truncated());
    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertTrue(
        result
            .summaryMarkdown()
            .contains("the summary was shortened because the model's response was cut"),
        result.summaryMarkdown());
    assertFalse(
        result.summaryMarkdown().contains("**Summary shortened.**"), result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains("summary shortened (response cut at the length cap)"), checkSummary);
  }

  @Test
  void checkSummaryKeepsTheSummaryCutMarkerWhenFindingsArePresent() {
    // The findings-present branch shares the same suffix as the no-issues branches: a review with
    // findings whose summary response was cut must still carry the marker in the check run.
    var result =
        new ReviewResult(
            List.of(new Finding(RiskLevel.LOW, "f.java", 1, "T", "d", null, null)),
            0,
            0,
            0,
            1,
            RiskLevel.LOW,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(),
            0,
            false,
            true,
            new ReviewResult.TruncationDetail(
                List.of(), List.of(), List.of(), List.of(), SummaryDegradation.RESPONSE_CUT));

    var checkSummary = VerdictBuilder.checkSummaryForResult(result);

    assertTrue(checkSummary.contains("1 findings"), checkSummary);
    assertTrue(
        checkSummary.contains("The summary was shortened (response cut at the length cap)."),
        checkSummary);
  }

  @Test
  void clippedOnlyReviewIsDisclosedAsPartialAndHoldsApproval() {
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of("huge.java"), true, null, null, null, null);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(1, result.omittedFiles());
    assertTrue(result.truncated());
  }

  @Test
  void uncoveredFilesAreNamedOnTheDisclosureSurfaces() {
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("big.java"), List.of("huge.java"), true, null, null, null, null);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertTrue(
        result.summaryMarkdown().contains("omitted entirely (big.java)"), result.summaryMarkdown());
    assertTrue(
        result.summaryMarkdown().contains("partially analyzed (huge.java)"),
        result.summaryMarkdown());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains("1 file(s) omitted, 1 file(s) partially analyzed"), checkSummary);
  }

  @Test
  void budgetOmittedFilesAreDroppedFromTheWalkthroughRows() {
    var ctx = contextWithLineCapOmissions(0); // reviewable: a.java
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("a.java"), List.of(), true, null, null, null, null);
    var rowsCaptor = ArgumentCaptor.forClass(List.class);

    builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    verify(summaryGenerator)
        .generate(anyInt(), anyInt(), anyInt(), rowsCaptor.capture(), any(), any());
    assertTrue(rowsCaptor.getValue().isEmpty(), rowsCaptor.getValue().toString());
  }

  @Test
  void ceilingSkippedFilesAreDroppedFromTheWalkthroughRowsLikeOtherNotReviewedFiles() {
    // #515 — a file skipped at the token spend ceiling was never reviewed at all: the banner says
    // so and the model-facing overview lists it as not reviewed, so its walkthrough row must be
    // dropped like every other not-reviewed class — only partially reviewed files (clipped,
    // response-cut) keep their rows.
    var ctx =
        contextWithLineCapOmissions(
            0,
            List.of(
                new FileDiff("reviewed.java", "modified", 1, 0, 1, ""),
                new FileDiff("skipped.java", "modified", 1, 0, 1, "")));
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    plan.recordSpendCeilingSkippedFiles(List.of("skipped.java"));
    var rowsCaptor = ArgumentCaptor.forClass(List.class);

    builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    verify(summaryGenerator)
        .generate(anyInt(), anyInt(), anyInt(), rowsCaptor.capture(), any(), any());
    assertEquals(
        List.of(new PrSummaryGenerator.ChangedFile("reviewed.java", "modified")),
        rowsCaptor.getValue(),
        "a ceiling-skipped (never reviewed) file must not keep its walkthrough row");
  }

  /**
   * #471 — the walkthrough filter is the third site that asks an immutable name set whether it
   * holds a {@code FileDiff.filename()} that Jackson never validated. {@code contains(null)} throws
   * there instead of answering false, so one unnamed file would fail the whole verdict on the async
   * review thread. An unnamed file is simply not in the omitted set and keeps its row.
   */
  @Test
  void anUnnamedFileIsNotTreatedAsOmittedFromTheWalkthroughRows() {
    var ctx =
        contextWithLineCapOmissions(
            0,
            List.of(
                new FileDiff(null, "modified", 1, 0, 1, ""),
                new FileDiff("a.java", "modified", 1, 0, 1, "")));
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("a.java"), List.of(), true, null, null, null, null);
    var rowsCaptor = ArgumentCaptor.forClass(List.class);

    builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    verify(summaryGenerator)
        .generate(anyInt(), anyInt(), anyInt(), rowsCaptor.capture(), any(), any());
    assertEquals(
        List.of(new PrSummaryGenerator.ChangedFile(null, "modified")),
        rowsCaptor.getValue(),
        "only the named omitted file is dropped; the unnamed one keeps its row");
  }

  @Test
  void diffStatsNormalizesANullTruncationDetail() {
    var stats = new VerdictBuilder.DiffStats(1, 2, 3, 4, null);
    assertEquals(ReviewResult.TruncationDetail.EMPTY, stats.truncation());
  }

  /** The previous round's persisted response: one finding anchored in src/Gone.java. */
  private static final String PRIOR_JSON =
      """
      {"findings": [{"risk": "high", "file": "src/Gone.java", "line": 10,
        "title": "Unsafe regex", "description": "d", "suggestion_old": "quote(label)"}]}
      """;

  private static final ReviewResponse PRIOR_RESPONSE =
      new ReviewResponse(
          List.of(
              new ReviewResponse.Finding(
                  "high", "src/Gone.java", 10, "Unsafe regex", "d", "quote(label)", null)),
          List.of(),
          null);

  /** A follow-up context whose current diff contains only {@code file}. */
  private static ReviewContextLoader.ReviewContext followUpContext(String file) {
    return new ReviewContextLoader.ReviewContext(
        List.of(),
        "diff",
        "",
        0,
        List.of(),
        List.of(PRIOR_JSON),
        List.of(PRIOR_RESPONSE),
        false,
        true,
        PRIOR_JSON,
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        List.of(new FileDiff(file, "modified", 1, 0, 1, "")),
        () -> new DiffLineResolver(Map.of(file, "@@ -10,1 +10,1 @@\n-old\n+new")),
        null);
  }

  private static final ReviewResponse UNRESOLVED_PRIOR_RESPONSE =
      new ReviewResponse(
          List.of(),
          List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
          null);

  @Test
  void unresolvedPriorFindingWhoseCodeLeftTheDiffIsSupersededAndDoesNotHoldApprove() {
    var realBuilder =
        new VerdictBuilder(
            summaryGenerator,
            new FollowUpAnalyzer(new com.fasterxml.jackson.databind.ObjectMapper()),
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            BlockingStrictness.BALANCED);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);

    var result =
        realBuilder.build(
            followUpContext("src/Other.java"), UNRESOLVED_PRIOR_RESPONSE, CI_CLEAR, plan);

    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertTrue(result.hasSupersededPrevious());
    assertEquals(0, result.unresolvedPreviousCount());
  }

  @Test
  void unresolvedPriorFindingStillInTheDiffKeepsHoldingApprove() {
    var realBuilder =
        new VerdictBuilder(
            summaryGenerator,
            new FollowUpAnalyzer(new com.fasterxml.jackson.databind.ObjectMapper()),
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            BlockingStrictness.BALANCED);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    var ctx =
        new ReviewContextLoader.ReviewContext(
            List.of(),
            "diff",
            "",
            0,
            List.of(),
            List.of(PRIOR_JSON),
            List.of(PRIOR_RESPONSE),
            false,
            true,
            PRIOR_JSON,
            List.of(),
            "",
            new InstructionsResolver.ResolvedInstructions("", ""),
            PathScopedInstructions.NONE,
            List.of(),
            "",
            "",
            "",
            "",
            List.of(new FileDiff("src/Gone.java", "modified", 1, 0, 1, "")),
            () ->
                new DiffLineResolver(
                    Map.of("src/Gone.java", "@@ -10,1 +10,1 @@\n-old\n+quote(label)")),
            null);

    var result = realBuilder.build(ctx, UNRESOLVED_PRIOR_RESPONSE, CI_CLEAR, plan);

    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    assertFalse(result.hasSupersededPrevious());
  }

  /** Prior round from the dogfood PR: the pause() race the maintainer went on to decline. */
  private static final String RACE_FILE = "src/main/java/.../webhook/PrPauseService.java";

  private static final String RACE_TITLE =
      "Race condition in pause() can cause a UniqueConstraint violation under concurrent webhooks";

  private static final ReviewResponse RACE_PRIOR_RESPONSE =
      new ReviewResponse(
          List.of(
              new ReviewResponse.Finding(
                  "medium",
                  "low",
                  RACE_FILE,
                  60,
                  RACE_TITLE,
                  "pause() checks for an existing PausedPr and then inserts one; two deliveries"
                      + " can both pass the check before either inserts.",
                  "if (repository.find(pr) == null) {",
                  null)),
          List.of(),
          null);

  /** The dogfood PR's command path: each command is handed to the shared review executor. */
  private static final String DISPATCHING_DIFF =
      """
      diff --git a/src/main/java/.../webhook/CommentCommandService.java
      @@ -130,7 +130,9 @@ public class CommentCommandService {
      +  private void dispatch(CommandContext ctx) {
      +    executor.execute(() -> execute(ctx));
      +  }
      """;

  /** The bot's finding thread plus the maintainer's single decline on it. */
  private static final List<
          dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.PullRequestComment>
      DECLINE_THREAD =
          List.of(
              new dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.PullRequestComment(
                  700L,
                  null,
                  RACE_FILE,
                  "**MEDIUM — " + RACE_TITLE + "**",
                  new dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.ReviewResponse
                      .User("thrillhousebot[bot]")),
              new dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.PullRequestComment(
                  701L,
                  700L,
                  RACE_FILE,
                  "Not changed — pause() is only ever called from the /pause command path, which"
                      + " runs asynchronously on the review executor after the webhook has"
                      + " returned 200.",
                  new dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.ReviewResponse
                      .User("maintainer"),
                  "MEMBER"));

  private static final ReviewResponse DECLINED_PRIOR_RESPONSE =
      new ReviewResponse(
          List.of(),
          List.of(
              new ReviewResponse.PreviousFindingStatus(
                  1, "justified", "maintainer says the path cannot run concurrently")),
          null);

  private static VerdictBuilder builderWithRealAnalyzer(PrSummaryGenerator summaryGenerator) {
    return new VerdictBuilder(
        summaryGenerator,
        new FollowUpAnalyzer(new com.fasterxml.jackson.databind.ObjectMapper()),
        BotIdentity.from(List.of("thrillhousebot[bot]")),
        BlockingStrictness.BALANCED);
  }

  /**
   * A follow-up context carrying the declined race finding, with {@code diff} as the legacy diff.
   */
  private static ReviewContextLoader.ReviewContext declinedRaceContext(String diff) {
    return new ReviewContextLoader.ReviewContext(
        List.of(),
        diff,
        "",
        0,
        List.of(),
        List.of("{}"),
        List.of(RACE_PRIOR_RESPONSE),
        false,
        true,
        "{}",
        DECLINE_THREAD,
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        List.of(new FileDiff(RACE_FILE, "modified", 1, 0, 1, "")),
        () ->
            new DiffLineResolver(
                Map.of(RACE_FILE, "@@ -60,1 +60,1 @@\n-old\n+if (repository.find(pr) == null) {")),
        null);
  }

  @Test
  void declinedPriorFindingTheReviewedCodeContradictsStaysOpenAndHoldsApprove() {
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(new DiffBudgetPlanner.DiffBatch(DISPATCHING_DIFF, List.of(), 10)),
            List.of(),
            List.of(),
            true,
            null,
            null,
            null,
            null);

    var result =
        builderWithRealAnalyzer(summaryGenerator)
            .build(declinedRaceContext(""), DECLINED_PRIOR_RESPONSE, CI_CLEAR, plan);

    assertEquals(1, result.unresolvedPreviousCount());
    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertTrue(
        result.previousStatuses().get(0).note().contains("executor.execute(() -> execute(ctx));"),
        "the re-opened status must name the contradiction, was: "
            + result.previousStatuses().get(0).note());
  }

  @Test
  void declineRecheckReadsTheLegacyDiffWhenBudgetingIsDisabled() {
    // budgeted=false: the planner holds no batches, so the legacy uncapped ctx.diff() is the only
    // record of what the model saw and must still be the material the decline is checked against.
    var legacyPlan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), false, null, null, null, null);

    var result =
        builderWithRealAnalyzer(summaryGenerator)
            .build(
                declinedRaceContext(DISPATCHING_DIFF),
                DECLINED_PRIOR_RESPONSE,
                CI_CLEAR,
                legacyPlan);

    assertEquals(1, result.unresolvedPreviousCount());
    assertTrue(
        result.previousStatuses().get(0).note().contains("executor.execute(() -> execute(ctx));"));
  }

  @Test
  void declineRecheckFallsBackToTheLegacyDiffWhenABudgetedPlanHasNoBatches() {
    // budgeted=true but every file overflowed the budget, so batches is empty and the concatenation
    // would yield nothing; ctx.diff() is the fallback the re-check must use.
    var emptyBudgetedPlan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("big.java"), List.of(), true, null, null, null, null);

    var result =
        builderWithRealAnalyzer(summaryGenerator)
            .build(
                declinedRaceContext(DISPATCHING_DIFF),
                DECLINED_PRIOR_RESPONSE,
                CI_CLEAR,
                emptyBudgetedPlan);

    assertEquals(1, result.unresolvedPreviousCount());
  }

  @Test
  void disabledBudgetingDisclosesTheLegacyLineCapCount() {
    var ctx = contextWithLineCapOmissions(2);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), false, null, null, null, null);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(2, result.omittedFiles());
    assertTrue(result.truncated());
  }

  private static final CiStatusEvaluator.CiEvaluation CI_OFFENDING =
      new CiStatusEvaluator.CiEvaluation(
          List.of(new ReviewResult.CiCheck("build", "check-run", "failing", "failure")), false);

  private static final CiStatusEvaluator.CiEvaluation CI_UNREADABLE =
      new CiStatusEvaluator.CiEvaluation(List.of(), true);

  private static final DiffBudgetPlanner.BudgetPlan FULL_COVERAGE =
      new DiffBudgetPlanner.BudgetPlan(
          List.of(), List.of(), List.of(), true, null, null, null, null);

  private VerdictBuilder builderWith(CiGatingMode mode) {
    return new VerdictBuilder(
        summaryGenerator, followUpAnalyzer, BotIdentity.from(List.of("thrillhousebot[bot]")), mode);
  }

  @Test
  void strictModeHoldsApproveWhenRequiredCiFails() {
    var result =
        builderWith(CiGatingMode.STRICT)
            .build(contextWithLineCapOmissions(0), CLEAN_RESPONSE, CI_OFFENDING, FULL_COVERAGE);

    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertTrue(result.ciHoldsApproval());
    assertFalse(result.offendingCiChecks().isEmpty());
    var summary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(summary.contains("still pending or failing"), summary);
    assertFalse(summary.contains("Note:"), summary);
  }

  @Test
  void warnModeAllowsApproveButNotesOffendingCi() {
    var result =
        builderWith(CiGatingMode.WARN)
            .build(contextWithLineCapOmissions(0), CLEAN_RESPONSE, CI_OFFENDING, FULL_COVERAGE);

    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertFalse(result.ciHoldsApproval());
    assertFalse(result.offendingCiChecks().isEmpty());
    var summary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(summary.contains("Note:"), summary);
    assertTrue(summary.contains("still pending or failing"), summary);
    assertTrue(VerdictBuilder.checkTitleForResult(result).contains("✅"));
    assertEquals("success", VerdictBuilder.conclusionForResult(result));
  }

  @Test
  void warnModeAllowsApproveButNotesUnreadableCi() {
    var result =
        builderWith(CiGatingMode.WARN)
            .build(contextWithLineCapOmissions(0), CLEAN_RESPONSE, CI_UNREADABLE, FULL_COVERAGE);

    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertFalse(result.ciHoldsApproval());
    assertTrue(result.ciUnreadable());
    var summary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(summary.contains("Note: the CI status could not be read"), summary);
    assertFalse(summary.contains("holding approval"), summary);
  }

  @Test
  void warnModeNotesUnreadableAlongsideOffendingChecks() {
    var both =
        new CiStatusEvaluator.CiEvaluation(
            List.of(new ReviewResult.CiCheck("build", "check-run", "failing", "failure")), true);
    var result =
        builderWith(CiGatingMode.WARN)
            .build(contextWithLineCapOmissions(0), CLEAN_RESPONSE, both, FULL_COVERAGE);

    assertEquals(ReviewState.APPROVE, result.reviewState());
    var summary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(summary.contains("Note:"), summary);
    assertTrue(summary.contains("still pending or failing"), summary);
    assertTrue(summary.contains("could not be read"), summary);
    assertFalse(summary.contains("holding approval"), summary);
  }

  @Test
  void configConstructorParsesCiGatingMode() {
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(review);
    when(review.ciGating()).thenReturn("warn");
    when(review.blockingStrictness()).thenReturn("balanced");

    var fromConfig =
        new VerdictBuilder(
            summaryGenerator,
            followUpAnalyzer,
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            config);
    var result =
        fromConfig.build(
            contextWithLineCapOmissions(0), CLEAN_RESPONSE, CI_OFFENDING, FULL_COVERAGE);

    assertEquals(ReviewState.APPROVE, result.reviewState());
  }

  @Test
  void nullModeFallsBackToStrict() {
    var result =
        new VerdictBuilder(
                summaryGenerator,
                followUpAnalyzer,
                BotIdentity.from(List.of("thrillhousebot[bot]")),
                (CiGatingMode) null)
            .build(contextWithLineCapOmissions(0), CLEAN_RESPONSE, CI_OFFENDING, FULL_COVERAGE);

    assertEquals(ReviewState.COMMENT, result.reviewState());
  }

  @Test
  void offModeIgnoresOffendingCiForApprove() {
    // OFF still receives an evaluation when tests inject one; production skips the fetch. The
    // verdict must not hold APPROVE on CI in this mode.
    var result =
        builderWith(CiGatingMode.OFF)
            .build(contextWithLineCapOmissions(0), CLEAN_RESPONSE, CI_OFFENDING, FULL_COVERAGE);

    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertFalse(result.ciHoldsApproval());
  }

  @Test
  void strictModeHoldsApproveWhenCiIsUnreadable() {
    var result =
        builderWith(CiGatingMode.STRICT)
            .build(contextWithLineCapOmissions(0), CLEAN_RESPONSE, CI_UNREADABLE, FULL_COVERAGE);

    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertTrue(result.ciHoldsApproval());
    assertTrue(VerdictBuilder.checkSummaryForResult(result).contains("holding approval"));
  }

  @Test
  void mergePreviousStatusesReusesModelListWhenBackstopIsEmpty() {
    var model = List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "done"));
    assertSame(model, VerdictBuilder.mergePreviousStatuses(model, List.of()));
    assertSame(model, VerdictBuilder.mergePreviousStatuses(model, null));
  }

  @Test
  void mergePreviousStatusesAppendsBackstopWithoutMutatingModelList() {
    var model = List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "done"));
    var backstop = List.of(new ReviewResult.PreviousFindingStatus(2, "unresolved", "held"));

    var merged = VerdictBuilder.mergePreviousStatuses(model, backstop);

    assertEquals(2, merged.size());
    assertEquals(1, model.size());
    assertEquals("unresolved", merged.get(1).status());
  }

  @Test
  void noContextPathDoesNotTouchLineResolverSupplier() {
    var touched = new boolean[] {false};
    var ctx =
        new ReviewContextLoader.ReviewContext(
            List.of(),
            "diff",
            "",
            0,
            List.of(),
            List.of(),
            List.of(),
            true,
            false,
            null,
            List.of(),
            "",
            new InstructionsResolver.ResolvedInstructions("", ""),
            PathScopedInstructions.NONE,
            List.of(),
            "",
            "",
            "",
            "",
            List.of(new FileDiff("a.java", "modified", 1, 0, 1, "")),
            () -> {
              touched[0] = true;
              return new DiffLineResolver(Map.of());
            },
            null);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), false, null, null, null, null);

    builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertFalse(touched[0]);
  }

  @Test
  void configConstructorHonorsStrictBlockingMode() {
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(review);
    when(review.ciGating()).thenReturn("strict");
    when(review.blockingStrictness()).thenReturn("strict");

    var strictBuilder =
        new VerdictBuilder(
            summaryGenerator,
            followUpAnalyzer,
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            config);
    var hedged =
        new ReviewResponse.Finding("critical", "low", "a.java", 1, "title", "desc", null, null);
    var response = new ReviewResponse(List.of(hedged), List.of(), null);

    var result =
        strictBuilder.build(
            contextWithLineCapOmissions(0),
            response,
            CI_CLEAR,
            new DiffBudgetPlanner.BudgetPlan(
                List.of(), List.of(), List.of(), false, null, null, null, null));

    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
  }

  @Test
  void configConstructorFallsBackToBalancedOnUnrecognizedMode() {
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(review);
    when(review.ciGating()).thenReturn("strict");
    when(review.blockingStrictness()).thenReturn("aggressive");

    var fallbackBuilder =
        new VerdictBuilder(
            summaryGenerator,
            followUpAnalyzer,
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            config);
    var hedged =
        new ReviewResponse.Finding("critical", "medium", "a.java", 1, "title", "desc", null, null);
    var response = new ReviewResponse(List.of(hedged), List.of(), null);

    var result =
        fallbackBuilder.build(
            contextWithLineCapOmissions(0),
            response,
            CI_CLEAR,
            new DiffBudgetPlanner.BudgetPlan(
                List.of(), List.of(), List.of(), false, null, null, null, null));

    assertEquals(ReviewState.COMMENT, result.reviewState());
  }

  @Test
  void threeArgConstructorDefaultsToStrictCiAndBalancedBlocking() {
    var defaults =
        new VerdictBuilder(
            summaryGenerator, followUpAnalyzer, BotIdentity.from(List.of("thrillhousebot[bot]")));

    var held =
        defaults.build(contextWithLineCapOmissions(0), CLEAN_RESPONSE, CI_OFFENDING, FULL_COVERAGE);
    assertEquals(ReviewState.COMMENT, held.reviewState());

    var hedged =
        new ReviewResponse.Finding("critical", "low", "a.java", 1, "title", "desc", null, null);
    var nonBlocking =
        defaults.build(
            contextWithLineCapOmissions(0),
            new ReviewResponse(List.of(hedged), List.of(), null),
            CI_CLEAR,
            FULL_COVERAGE);
    assertEquals(ReviewState.COMMENT, nonBlocking.reviewState());
  }

  @Test
  void renameTargetsFiltersMixedInputsAndDistinguishesPureFromContentRenames() {
    var targets =
        VerdictBuilder.renameTargets(
            java.util.Arrays.asList(
                null,
                new FileDiff("src/Modified.java", "modified", 1, 0, 1, "+change", "old.java"),
                new FileDiff("src/NoPrevious.java", "renamed", 0, 0, 0, null, null),
                new FileDiff("src/BlankPrevious.java", "renamed", 0, 0, 0, "", "  "),
                new FileDiff("new/Pure.java", "RENAMED", 0, 0, 0, "  ", "old/Pure.java"),
                new FileDiff(
                    "new/Edited.java",
                    "renamed",
                    1,
                    0,
                    1,
                    "@@ -1 +1 @@\n-old\n+new",
                    "old/Edited.java")));

    assertEquals(Map.of("old/Pure.java", "", "old/Edited.java", "new/Edited.java"), targets);
  }

  @Test
  void pureRenameRollupIsInsertedIntoPositiveSummaryAsAiReviewScope() {
    var pureRename = new FileDiff("new/Pure.java", "renamed", 0, 0, 0, null, "old/Pure.java");
    var ctx =
        new ReviewContextLoader.ReviewContext(
            List.of(pureRename),
            "diff",
            "",
            0,
            List.of(),
            List.of(),
            List.of(),
            true,
            false,
            null,
            List.of(),
            "",
            new InstructionsResolver.ResolvedInstructions("", ""),
            PathScopedInstructions.NONE,
            List.of(),
            "",
            "",
            "",
            "",
            List.of(),
            () -> new DiffLineResolver(Map.of()),
            null);
    var realSummaryBuilder =
        new VerdictBuilder(
            new PrSummaryGenerator(false),
            followUpAnalyzer,
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            BlockingStrictness.BALANCED);

    var result = realSummaryBuilder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, FULL_COVERAGE);

    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertTrue(
        result
            .summaryMarkdown()
            .startsWith(
                PrSummaryGenerator.SUMMARY_HEADING
                    + "\n\n> **AI review scope:** 1 pure rename omitted from AI review "
                    + "(old/Pure.java → new/Pure.java)\n\n"),
        result.summaryMarkdown());
  }

  @Test
  void nullPureRenameRollupIsIgnored() {
    try (var formatter = mockStatic(ReviewDiffFormatter.class, CALLS_REAL_METHODS)) {
      formatter.when(() -> ReviewDiffFormatter.formatPureRenameRollup(List.of())).thenReturn(null);

      var result =
          builder.build(contextWithLineCapOmissions(0), CLEAN_RESPONSE, CI_CLEAR, FULL_COVERAGE);

      assertEquals("", result.summaryMarkdown());
    }
  }

  @Test
  void nullStrictnessInTestConstructorFallsBackToBalanced() {
    var nullModeBuilder =
        new VerdictBuilder(
            summaryGenerator,
            followUpAnalyzer,
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            (BlockingStrictness) null);
    var hedged =
        new ReviewResponse.Finding("critical", "low", "a.java", 1, "title", "desc", null, null);
    var response = new ReviewResponse(List.of(hedged), List.of(), null);

    var result =
        nullModeBuilder.build(
            contextWithLineCapOmissions(0),
            response,
            CI_CLEAR,
            new DiffBudgetPlanner.BudgetPlan(
                List.of(), List.of(), List.of(), false, null, null, null, null));

    assertEquals(ReviewState.COMMENT, result.reviewState());
  }

  // ---- #455: a zero-finding round must neither evict, double-count, nor phantom-hold ----

  private static final String CARRIED_FILE = "src/Carried.java";
  private static final String CARRIED_ANCHOR = "var name = decode(content);";

  private static final String CARRIED_ROUND_JSON =
      "{\"findings\":[{\"risk\":\"medium\",\"file\":\""
          + CARRIED_FILE
          + "\",\"line\":166,\"title\":\"Undecodable content\",\"description\":\"d\","
          + "\"suggestion_old\":\""
          + CARRIED_ANCHOR
          + "\"}],\"previous_findings_status\":[],\"summary\":null}";

  /** Round 1: the one MEDIUM finding this PR ever raised. */
  private static final ReviewResponse CARRIED_ROUND =
      new ReviewResponse(
          List.of(
              new ReviewResponse.Finding(
                  "medium", CARRIED_FILE, 166, "Undecodable content", "d", CARRIED_ANCHOR, null)),
          List.of(),
          null);

  /**
   * Round 3 of the PR #449 sequence, with round 2 having found nothing and reported round 1's
   * finding {@code carriedStatus}. The anchor is still in the diff, so nothing is superseded and
   * only the status bookkeeping decides the verdict.
   */
  private static ReviewContextLoader.ReviewContext afterZeroFindingRound(String carriedStatus) {
    var zeroFindingRound =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(1, carriedStatus, "n")),
            null);
    return new ReviewContextLoader.ReviewContext(
        List.of(),
        "diff",
        "",
        0,
        List.of(),
        List.of("{\"findings\":[]}", CARRIED_ROUND_JSON),
        List.of(zeroFindingRound, CARRIED_ROUND),
        false,
        true,
        CARRIED_ROUND_JSON,
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        List.of(new FileDiff(CARRIED_FILE, "modified", 1, 0, 1, "")),
        () ->
            new DiffLineResolver(
                Map.of(CARRIED_FILE, "@@ -166,1 +166,1 @@\n-old\n+" + CARRIED_ANCHOR)),
        null);
  }

  /**
   * #455 — one finding was ever raised on the PR, so exactly one may be reported unresolved. The
   * model's own status and the deterministic backstop each held it once, because the backstop
   * mapped the current round's ids over the zero-finding round instead of the round they name.
   */
  @Test
  void unresolvedCountAcrossAZeroFindingRoundStaysAtTheOneRealFinding() {
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    var stillUnresolved =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
            null);

    var result =
        builderWithRealAnalyzer(summaryGenerator)
            .build(afterZeroFindingRound("unresolved"), stillUnresolved, CI_CLEAR, plan);

    assertEquals(
        1,
        result.unresolvedPreviousCount(),
        "the unresolved count must equal the number of distinct real findings still open");
  }

  /**
   * #455 — the PR's only prior finding is reported resolved this round, so nothing genuine is
   * outstanding and approval must return. Before the fix the backstop could not see that report
   * (its ids did not map through the zero-finding round) and a phantom held APPROVE open forever.
   */
  @Test
  void resolvedPriorFindingNoLongerPhantomHoldsApproveAfterAZeroFindingRound() {
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    var resolvedNow =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed")),
            null);

    var result =
        builderWithRealAnalyzer(summaryGenerator)
            .build(afterZeroFindingRound("unresolved"), resolvedNow, CI_CLEAR, plan);

    assertEquals(0, result.unresolvedPreviousCount());
    assertEquals(
        ReviewState.APPROVE,
        result.reviewState(),
        "a PR with no genuine outstanding findings must be approvable again");
  }

  // ---------------------------------------------------------------------------------------------
  // #645 — a confidence hedge silently removes blocking eligibility. The gate is unchanged; what
  // the review must no longer do is stay silent about which gate produced the verdict.
  // ---------------------------------------------------------------------------------------------

  private static final String CONFIDENCE_HOLD_HEADLINE = "Severity did not decide this verdict";

  private static final String CONFIDENCE_HOLD_CLAUSE =
      "1 finding(s) were severe enough to block on their own, but the review hedged its confidence"
          + " in them";

  private static final VerdictBuilder.DiffStats ONE_FILE = new VerdictBuilder.DiffStats(1, 1, 0);

  private static ReviewResponse.Finding aiFinding(String risk, String confidence) {
    return new ReviewResponse.Finding(
        risk, confidence, "a.java", 1, "sink", "reaches a sink", null, null);
  }

  private static ReviewResponse responseWithFindings(ReviewResponse.Finding... findings) {
    return new ReviewResponse(List.of(findings), List.of(), null);
  }

  private ReviewResult verdictFor(VerdictBuilder verdictBuilder, ReviewResponse response) {
    return verdictBuilder.buildResult(
        response, true, ONE_FILE, List.of(), List.of(), CI_CLEAR, List.of());
  }

  private VerdictBuilder builderWith(BlockingStrictness strictness) {
    return new VerdictBuilder(
        summaryGenerator,
        followUpAnalyzer,
        BotIdentity.from(List.of("thrillhousebot[bot]")),
        strictness);
  }

  /**
   * The round-6 React case: a HIGH finding the model hedged to medium confidence is removed from
   * blocking consideration entirely, so the PR gets a comment instead of a request for changes. The
   * verdict may stand, but both surfaces that explain it must say the hedge is what produced it.
   */
  @Test
  void aHedgedHighFindingSaysWhyItDidNotBlock() {
    var result = verdictFor(builder, responseWithFindings(aiFinding("high", "medium")));

    assertEquals(ReviewState.COMMENT, result.reviewState());
    var markdown = result.summaryMarkdown();
    assertTrue(markdown.contains(CONFIDENCE_HOLD_HEADLINE), markdown);
    assertTrue(
        markdown.contains(
            CONFIDENCE_HOLD_CLAUSE + ", so this review comments instead of requesting changes"),
        markdown);
    assertTrue(markdown.contains("`REVIEW_BLOCKING_STRICTNESS=strict`"), markdown);
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(
        checkSummary.contains("Not blocking: " + CONFIDENCE_HOLD_CLAUSE + "."), checkSummary);
  }

  /**
   * The Angular counter-case: another finding already carries the verdict, so the hedge changed
   * nothing. Disclosing it there would put a paragraph on every blocking review explaining an
   * outcome the review did not have — the noise a check run cannot afford.
   */
  @Test
  void aReviewThatAlreadyRequestsChangesDoesNotDiscloseAHold() {
    var result =
        verdictFor(
            builder, responseWithFindings(aiFinding("high", "medium"), aiFinding("high", "high")));

    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    assertEquals(0, result.blockingWithheldByConfidence());
    assertFalse(result.summaryMarkdown().contains(CONFIDENCE_HOLD_HEADLINE));
    assertFalse(VerdictBuilder.checkSummaryForResult(result).contains("Not blocking:"));
  }

  /** Severity, not confidence, is why a hedged medium does not block — nothing to disclose. */
  @Test
  void aHedgedMediumFindingIsNotAConfidenceHold() {
    var result = verdictFor(builder, responseWithFindings(aiFinding("medium", "medium")));

    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertEquals(0, result.blockingWithheldByConfidence());
    assertFalse(result.summaryMarkdown().contains(CONFIDENCE_HOLD_HEADLINE));
  }

  /** Each mode discloses the hold for exactly the severities its own severity gate would block. */
  @Test
  void theHoldIsReportedPerModeAgainstThatModesSeverityBar() {
    var lenient = builderWith(BlockingStrictness.LENIENT);
    var hedgedHigh = verdictFor(lenient, responseWithFindings(aiFinding("high", "medium")));
    assertEquals(0, hedgedHigh.blockingWithheldByConfidence());
    assertFalse(hedgedHigh.summaryMarkdown().contains(CONFIDENCE_HOLD_HEADLINE));

    var hedgedCritical = verdictFor(lenient, responseWithFindings(aiFinding("critical", "medium")));
    assertEquals(1, hedgedCritical.blockingWithheldByConfidence());
    assertTrue(hedgedCritical.summaryMarkdown().contains(CONFIDENCE_HOLD_HEADLINE));

    // STRICT has no confidence gate, so the same finding blocks and nothing is withheld.
    var strict =
        verdictFor(
            builderWith(BlockingStrictness.STRICT),
            responseWithFindings(aiFinding("high", "medium")));
    assertEquals(ReviewState.REQUEST_CHANGES, strict.reviewState());
    assertEquals(0, strict.blockingWithheldByConfidence());
  }

  /**
   * The hedge is equally decisive on a previous finding still open, which reaches the gate through
   * {@code outstanding} but produces no new-finding counts — so the disclosure must survive the
   * check-run summary's no-new-findings branch too.
   */
  @Test
  void anUnresolvedPreviousHedgedHighIsDisclosedWithNoNewFindings() {
    when(followUpAnalyzer.toStatuses(any()))
        .thenReturn(List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still open")));
    var previous =
        new Finding(RiskLevel.HIGH, Confidence.MEDIUM, "a.java", 7, "sink", "d", null, null);

    var result =
        builder.buildResult(
            CLEAN_RESPONSE, false, ONE_FILE, List.of(), List.of(previous), CI_CLEAR, List.of());

    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertEquals(1, result.blockingWithheldByConfidence());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(checkSummary.contains("previous finding(s) remain unresolved"), checkSummary);
    assertTrue(
        checkSummary.contains("Not blocking: " + CONFIDENCE_HOLD_CLAUSE + "."), checkSummary);
  }

  /** A diff the review never saw in full is the larger caveat and keeps the top of the summary. */
  @Test
  void theCoverageBannerStaysAboveTheConfidenceHold() {
    var truncated =
        new VerdictBuilder.DiffStats(
            1,
            1,
            0,
            1,
            new ReviewResult.TruncationDetail(
                List.of("f.java"), List.of(), List.of(), List.of(), SummaryDegradation.NONE));

    var result =
        builder.buildResult(
            responseWithFindings(aiFinding("high", "medium")),
            true,
            truncated,
            List.of(),
            List.of(),
            CI_CLEAR,
            List.of());

    var markdown = result.summaryMarkdown();
    assertTrue(markdown.startsWith(ReviewResult.TRUNCATION_NOTICE_LEAD_IN), markdown);
    assertTrue(
        markdown.indexOf(ReviewResult.TRUNCATION_NOTICE_LEAD_IN)
            < markdown.indexOf(CONFIDENCE_HOLD_HEADLINE),
        markdown);
  }
}
