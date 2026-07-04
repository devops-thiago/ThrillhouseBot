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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link FindingPipeline}'s multi-call (map-reduce) path (#53). */
class FindingPipelineTest {

  @Mock private AiReviewService aiReviewService;
  @Mock private FindingQuoteValidator quoteValidator;
  @Mock private FindingDeduplicator deduplicator;
  @Mock private FindingVerificationService findingVerificationService;
  @Mock private FollowUpAnalyzer followUpAnalyzer;
  @Mock private DiffBudgetPlanner budgetPlanner;

  private FindingPipeline pipeline;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    pipeline =
        new FindingPipeline(
            aiReviewService,
            quoteValidator,
            deduplicator,
            findingVerificationService,
            followUpAnalyzer,
            new ObjectMapper(),
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            budgetPlanner,
            new TokenCounter());
    // The post-AI chain is exercised elsewhere; here it passes the response through unchanged so
    // the
    // multi-call orchestration (batch fan-out, aggregation, summary merge) is what's asserted.
    when(quoteValidator.validate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(deduplicator.dedupe(any())).thenAnswer(inv -> inv.getArgument(0));
    when(findingVerificationService.verify(any(), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    when(followUpAnalyzer.dropRepliedDuplicates(any(), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(followUpAnalyzer.previousFindingFilesById(any())).thenReturn(Map.of());
    lenient().when(budgetPlanner.perCallInputBudget()).thenReturn(Integer.MAX_VALUE);
  }

  private static ReviewResponse.Finding finding(String file, String title) {
    return new ReviewResponse.Finding(
        "medium", "high", file, 1, title, "desc", "old line", "new line");
  }

  private static DiffBudgetPlanner.DiffBatch batch(String name) {
    var file = new FileDiff(name, "modified", 3, 0, 3, "@@ -1 +1 @@\n+x\n");
    return new DiffBudgetPlanner.DiffBatch("### " + name + "\n", List.of(file), 10);
  }

  private static ReviewContextLoader.ReviewContext reviewContext() {
    return new ReviewContextLoader.ReviewContext(
        List.of(),
        "raw legacy diff",
        "",
        0,
        List.of(),
        List.of(),
        true,
        false,
        null,
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        List.of(),
        "",
        List.of(
            new FileDiff("a.java", "modified", 3, 0, 3, ""),
            new FileDiff("b.java", "modified", 2, 0, 2, "")),
        new DiffLineResolver(Map.of()),
        null);
  }

  private static DiffBudgetPlanner.BudgetPlan multiBatchPlan() {
    return multiBatchPlan(List.of());
  }

  private static DiffBudgetPlanner.BudgetPlan multiBatchPlan(List<String> omittedByName) {
    return new DiffBudgetPlanner.BudgetPlan(
        List.of(batch("a.java"), batch("b.java")), omittedByName, List.of(), true);
  }

  @Test
  void multiCallReviewsEachBatchAggregatesAndSummarizes() {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");

    var batchOneStatus = new ReviewResponse.PreviousFindingStatus(1, "unresolved", "not in slice");
    var batchTwoStatus = new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed");
    // Prior finding #1 lives in b.java — batch 2's slice — so its resolved claim is
    // evidence-backed.
    when(followUpAnalyzer.previousFindingFilesById(any())).thenReturn(Map.of(1, "b.java"));
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(
            new ReviewResponse(List.of(finding("a.java", "A")), List.of(batchOneStatus), null))
        .thenReturn(
            new ReviewResponse(List.of(finding("b.java", "B")), List.of(batchTwoStatus), null));

    var summary = new ReviewResponse.Summary(2, 0, 0, 2, 0, "looks ok", "does things", List.of());
    var summaryStatuses = List.of(new ReviewResponse.PreviousFindingStatus(9, "resolved", "no"));
    when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), summaryStatuses, summary));

    var result =
        pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    // One blocking review call per batch, with the right index/count.
    verify(aiReviewService).reviewBatch(eq(session), any(), eq(1), eq(2));
    verify(aiReviewService).reviewBatch(eq(session), any(), eq(2), eq(2));
    // Exactly one summary call rolls up the union.
    verify(aiReviewService).summarize(eq(session), any());
    // Each batch's findings are verified against that batch's own in-budget diff — never the
    // over-budget combined text (#53).
    verify(findingVerificationService, times(2)).verify(any(), any(), any(), any());

    // Findings are the union of both batches; the PR-level summary comes from the summary call,
    // but previous-finding statuses come from the batch calls (which saw the diff) — the
    // code-blind summary call's statuses are discarded, and the evidence-backed "resolved" wins.
    assertEquals(2, result.findings().size());
    assertSame(summary, result.summary());
    assertEquals(List.of(batchTwoStatus), result.previousFindingsStatus());
  }

  @Test
  void budgetedSingleBatchSendsThePlannedTextNotTheRawDiff() {
    var session = ReviewSession.create("owner/repo", 1, "One big file", "sha");
    var ctx = reviewContext();
    var template =
        new AiReviewService.PromptInputs("raw legacy diff", "ctx", "base", "s", "t", "", "");
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(batch("clipped.java")), List.of(), List.of(), true);
    var captor = ArgumentCaptor.forClass(AiReviewService.PromptInputs.class);
    when(aiReviewService.review(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    // The (possibly hunk-clipped) batch text is what the model sees; the uncapped raw diff would
    // bypass the budget in exactly the oversized-file case that motivated clipping (#53).
    assertTrue(captor.getValue().diff().contains("### clipped.java"), captor.getValue().diff());
    assertEquals("base", captor.getValue().baseComparison());
    verify(quoteValidator).validate(any(), eq("### clipped.java\n"));
  }

  @Test
  void disabledBudgetingKeepsTheLegacyDiffPath() {
    var session = ReviewSession.create("owner/repo", 1, "PR", "sha");
    var ctx = reviewContext();
    var template =
        new AiReviewService.PromptInputs("raw legacy diff", "ctx", "base", "s", "t", "", "");
    var plan =
        new DiffBudgetPlanner.BudgetPlan(List.of(batch("a.java")), List.of(), List.of(), false);
    var captor = ArgumentCaptor.forClass(AiReviewService.PromptInputs.class);
    when(aiReviewService.review(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    assertEquals("raw legacy diff", captor.getValue().diff());
    verify(quoteValidator).validate(any(), eq("raw legacy diff"));
  }

  @Test
  void resolvedFromABatchThatNeverSawTheFileIsDemotedToUnresolved() {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    // Prior finding #1 lives in b.java, #2 in a.java: only the batch whose slice contains the
    // file may close it.
    when(followUpAnalyzer.previousFindingFilesById(any()))
        .thenReturn(Map.of(1, "b.java", 2, "a.java"));

    // Batch 1 (a.java) hallucinates "resolved" on #1 without ever seeing b.java, legitimately
    // resolves #2 (its own slice), and claims #3 — unmappable to any file, so no batch can prove
    // it saw it; batch 2 (b.java) — informed on #1 — says unresolved.
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(
            new ReviewResponse(
                List.of(),
                List.of(
                    new ReviewResponse.PreviousFindingStatus(1, "resolved", "looks done"),
                    new ReviewResponse.PreviousFindingStatus(2, "resolved", "fixed here"),
                    new ReviewResponse.PreviousFindingStatus(3, "resolved", "no map entry")),
                null))
        .thenReturn(
            new ReviewResponse(
                List.of(),
                List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still broken")),
                null));
    when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var result =
        pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    assertEquals(3, result.previousFindingsStatus().size());
    assertEquals("unresolved", result.previousFindingsStatus().get(0).status());
    assertEquals("resolved", result.previousFindingsStatus().get(1).status());
    // The unmappable claim is demoted: with no id-to-file mapping, scoping cannot be bypassed.
    assertEquals("unresolved", result.previousFindingsStatus().get(2).status());
  }

  @Test
  void singleBudgetedBatchScopesResolutionClaimsLikeTheMultiCallPath() {
    var session = ReviewSession.create("owner/repo", 1, "One clipped file", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("raw", "ctx", "base", "s", "t", "", "");
    // Prior #1 lives in a.java (clipped: only leading hunks were sent); prior #2 in b.java (fully
    // in the batch). The single call may close #2 but not #1.
    when(followUpAnalyzer.previousFindingFilesById(any()))
        .thenReturn(Map.of(1, "a.java", 2, "b.java"));
    var batchFiles =
        List.of(
            new FileDiff("a.java", "modified", 3, 0, 3, "@@ -1 +1 @@\n+x\n"),
            new FileDiff("b.java", "modified", 2, 0, 2, "@@ -1 +1 @@\n+y\n"));
    var batch = new DiffBudgetPlanner.DiffBatch("### a.java\n### b.java\n", batchFiles, 10);
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(batch), List.of(), List.of("a.java"), true);
    when(aiReviewService.review(eq(session), any()))
        .thenReturn(
            new ReviewResponse(
                List.of(),
                List.of(
                    new ReviewResponse.PreviousFindingStatus(1, "resolved", "looks fixed"),
                    new ReviewResponse.PreviousFindingStatus(2, "resolved", "fixed here")),
                null));

    var result = pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    assertEquals("unresolved", result.previousFindingsStatus().get(0).status());
    assertEquals("resolved", result.previousFindingsStatus().get(1).status());
  }

  @Test
  void resolvedFromABatchThatOnlySawTheClippedFileIsDemoted() {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    // Prior finding #1 lives in a.java — which is in batch 1's slice, but hunk-clipped: the batch
    // saw only its leading hunks, so it cannot prove the fix in the unseen tail.
    when(followUpAnalyzer.previousFindingFilesById(any())).thenReturn(Map.of(1, "a.java"));
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(batch("a.java"), batch("b.java")), List.of(), List.of("a.java"), true);
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(
            new ReviewResponse(
                List.of(),
                List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "looks fixed")),
                null))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var result = pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    assertEquals(1, result.previousFindingsStatus().size());
    assertEquals("unresolved", result.previousFindingsStatus().get(0).status());
  }

  @Test
  void oversizedOverviewIsClampedWithARollupNote() {
    var session = ReviewSession.create("owner/repo", 1, "Huge PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "d", "", "", "", "", "");
    // Budget just above the inherited sections: the overview cannot fit both file rows, so it is
    // truncated line-wise with a rollup instead of blowing the summary prompt on file names.
    var tokenCounter = new TokenCounter();
    var inherited = PrReviewPrompts.SUMMARY_SYSTEM + PrReviewPrompts.SUMMARY_USER + "d";
    when(budgetPlanner.perCallInputBudget())
        .thenReturn(tokenCounter.estimateTokens(inherited) + 20);
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    assertTrue(
        captor.getValue().changedFiles().contains("more changed files"),
        captor.getValue().changedFiles());
  }

  @Test
  void overviewIsWithheldWhenTheInheritedSectionsExhaustTheBudget() {
    var session = ReviewSession.create("owner/repo", 1, "Huge PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "d", "", "", "", "", "");
    when(budgetPlanner.perCallInputBudget()).thenReturn(10);
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    assertTrue(
        captor.getValue().changedFiles().contains("overview withheld"),
        captor.getValue().changedFiles());
  }

  @Test
  void summaryFindingsJsonFallsToEmptyWhenNothingFitsTheBudget() {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "d", "", "", "", "", "");
    when(budgetPlanner.perCallInputBudget()).thenReturn(10);
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("a.java", "A")), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    // Nothing fits: an empty array is sent, but the true totals still ride along so the summary
    // model does not describe the review as finding-free.
    assertTrue(captor.getValue().findings().startsWith("[]"), captor.getValue().findings());
    assertTrue(
        captor.getValue().findings().contains("more findings not shown"),
        captor.getValue().findings());
  }

  @Test
  void clippedFilesAreDisclosedAsPartiallyAnalyzedInTheSummaryOverview() {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(batch("a.java"), batch("b.java")), List.of(), List.of("a.java"), true);
    // Finite budget with nothing to drop: the clamp is a no-op and serializes everything.
    when(budgetPlanner.perCallInputBudget()).thenReturn(1_000_000);
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    var changedFiles = captor.getValue().changedFiles();
    assertTrue(changedFiles.contains("a.java (modified, +3 -0 — partially analyzed"), changedFiles);
    assertFalse(changedFiles.contains("b.java (modified, +2 -0 — partially"), changedFiles);
  }

  @Test
  void summaryFindingsJsonIsClampedToThePerCallBudget() throws Exception {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "d", "", "", "", "", "");
    var high = new ReviewResponse.Finding("high", "high", "a.java", 1, "H", "d", "o", "n");
    var medium = new ReviewResponse.Finding("medium", "high", "a.java", 3, "M", "d", "o", "n");
    var nullRisk = new ReviewResponse.Finding(null, "high", "a.java", 2, "N", "d", "o", "n");
    var critical = new ReviewResponse.Finding("critical", "high", "b.java", 1, "C", "d", "o", "n");

    // Budget sized so the fixed summary sections plus exactly one finding fit: the critical one
    // must win the remaining space; the high and null-risk ones are dropped from the
    // serialization only.
    var tokenCounter = new TokenCounter();
    var overview = "a.java (modified, +3 -0)\nb.java (modified, +2 -0)\n";
    var fixedSections =
        PrReviewPrompts.SUMMARY_SYSTEM + PrReviewPrompts.SUMMARY_USER + "d" + overview;
    var criticalJson = new ObjectMapper().writeValueAsString(List.of(critical));
    var allFindings = List.of(high, medium, nullRisk, critical);
    var noteReserve =
        tokenCounter.estimateTokens(
            "\n" + FindingPipeline.trueTotalsNote(allFindings, allFindings.size()));
    when(budgetPlanner.perCallInputBudget())
        .thenReturn(
            tokenCounter.estimateTokens(fixedSections)
                + noteReserve
                + tokenCounter.estimateTokens(criticalJson)
                + 1);

    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(high, medium, nullRisk), List.of(), null))
        .thenReturn(new ReviewResponse(List.of(critical), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var result =
        pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    // The response keeps every finding; only the summary-call serialization is clamped, keeping
    // the most severe finding, and the true totals ride along so the summary model's counts and
    // narrative describe the full review rather than the serialized subset.
    assertEquals(4, result.findings().size());
    var serialized = captor.getValue().findings();
    assertTrue(serialized.contains("\"C\""), serialized);
    assertFalse(serialized.contains("\"H\""), serialized);
    assertFalse(serialized.contains("\"N\""), serialized);
    assertTrue(serialized.contains("+3 more findings not shown"), serialized);
    assertTrue(serialized.contains("4 total, 1 critical, 1 high, 1 medium, 1 low"), serialized);
  }

  @Test
  void mergeBatchStatusesLetsTheEvidenceBackedClaimWin() {
    var unresolvedOne = new ReviewResponse.PreviousFindingStatus(1, "unresolved", "not here");
    var resolvedOne = new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed in slice");
    var justifiedTwo = new ReviewResponse.PreviousFindingStatus(2, "justified", "author reply");
    var unresolvedTwo = new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still open");
    var unresolvedThree = new ReviewResponse.PreviousFindingStatus(3, "unresolved", "open");
    // A malformed null status ranks lowest and never displaces a real verdict.
    var nullFour = new ReviewResponse.PreviousFindingStatus(4, null, "malformed");
    var justifiedFour = new ReviewResponse.PreviousFindingStatus(4, "justified", "reply");

    var merged =
        FindingPipeline.mergeBatchStatuses(
            List.of(
                List.of(unresolvedOne, justifiedTwo, unresolvedThree, nullFour),
                List.of(resolvedOne, unresolvedTwo, justifiedFour)));

    assertEquals(List.of(resolvedOne, justifiedTwo, unresolvedThree, justifiedFour), merged);
  }

  @Test
  void budgetedPlanWithNoFittingBatchSkipsTheReviewCall() {
    // Every reviewable file overflowed the budget: sending the raw diff instead would bypass the
    // budget on exactly the PR it was meant to bound. Only the diff-free summary call runs; the
    // statuses stay empty (no call saw the diff) and the plan's omissions disclose the partial.
    var session = ReviewSession.create("owner/repo", 1, "PR", "sha");
    var template =
        new AiReviewService.PromptInputs("raw legacy diff", "ctx", "base", "s", "t", "", "");
    var plan =
        new DiffBudgetPlanner.BudgetPlan(List.of(), List.of("a.java", "b.java"), List.of(), true);
    var summary = new ReviewResponse.Summary(0, 0, 0, 0, 0, "too large", "unknown", List.of());
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), summary));

    var result =
        pipeline.run(session, template, reviewContext(), plan, new DiffLineResolver(Map.of()));

    verify(aiReviewService, never()).review(any(), any());
    verify(aiReviewService, never()).reviewBatch(any(), any(), anyInt(), anyInt());
    assertTrue(result.findings().isEmpty());
    assertTrue(result.previousFindingsStatus().isEmpty());
    assertSame(summary, result.summary());
    assertTrue(captor.getValue().changedFiles().contains("a.java (omitted"));
  }

  @Test
  void budgetedPlanWithNoReviewableFilesKeepsTheLegacySingleCall() {
    // Batches and omissions both empty (nothing reviewable): the ordinary single call proceeds
    // with the assembled prompt, whose diff render is the tiny "(no changes detected)" text.
    var session = ReviewSession.create("owner/repo", 1, "PR", "sha");
    var template =
        new AiReviewService.PromptInputs("(no changes detected)", "ctx", "base", "s", "t", "", "");
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of(), true);
    var captor = ArgumentCaptor.forClass(AiReviewService.PromptInputs.class);
    when(aiReviewService.review(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(session, template, reviewContext(), plan, new DiffLineResolver(Map.of()));

    assertEquals("(no changes detected)", captor.getValue().diff());
  }

  @Test
  void summaryInputsDiscloseOmittedFilesByName() {
    var session = ReviewSession.create("owner/repo", 1, "Huge PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(
        session, template, ctx, multiBatchPlan(List.of("a.java")), new DiffLineResolver(Map.of()));

    // An omitted reviewable file appears exactly once — as the omission note, not also as a
    // covered row with change counts.
    var changedFiles = captor.getValue().changedFiles();
    assertTrue(changedFiles.contains("a.java (omitted"), changedFiles);
    assertFalse(changedFiles.contains("a.java (modified"), changedFiles);
    assertTrue(changedFiles.contains("b.java (modified"), changedFiles);
  }

  @Test
  void findingsSerializationFailureFallsBackToEmptyArray() throws Exception {
    var throwingMapper = mock(ObjectMapper.class);
    when(throwingMapper.writeValueAsString(any()))
        .thenThrow(new JsonProcessingException("boom") {});
    var p =
        new FindingPipeline(
            aiReviewService,
            quoteValidator,
            deduplicator,
            findingVerificationService,
            followUpAnalyzer,
            throwingMapper,
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            budgetPlanner,
            new TokenCounter());
    var session = ReviewSession.create("owner/repo", 1, "PR", "sha");
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("a.java", "A")), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    p.run(session, template, reviewContext(), multiBatchPlan(), new DiffLineResolver(Map.of()));

    // The summary still gets a valid (empty) findings array rather than propagating the failure —
    // but with real findings present, the true-totals note must ride along so the model does not
    // describe a clean PR while the verdict posts findings.
    assertTrue(captor.getValue().findings().startsWith("[]"), captor.getValue().findings());
    assertTrue(
        captor.getValue().findings().contains("more findings not shown"),
        captor.getValue().findings());
  }
}
