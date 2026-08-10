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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponseTruncatedException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenSpendCeilingExceededException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link FindingPipeline}'s multi-call (map-reduce) path. */
class FindingPipelineTest {

  @Mock private AiReviewService aiReviewService;
  @Mock private FindingQuoteValidator quoteValidator;
  @Mock private FrameworkFalsePositiveFilter frameworkFilter;
  @Mock private FindingDeduplicator deduplicator;
  @Mock private FindingVerificationService findingVerificationService;
  @Mock private FollowUpAnalyzer followUpAnalyzer;
  @Mock private DiffBudgetPlanner budgetPlanner;
  @Mock private ReviewTokenLedger tokenLedger;

  private FindingPipeline pipeline;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    pipeline =
        new FindingPipeline(
            aiReviewService,
            quoteValidator,
            frameworkFilter,
            deduplicator,
            findingVerificationService,
            followUpAnalyzer,
            new ObjectMapper(),
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            budgetPlanner,
            new TokenCounter(),
            tokenLedger);
    when(quoteValidator.validate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(frameworkFilter.filter(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(deduplicator.dedupe(any())).thenAnswer(inv -> inv.getArgument(0));
    when(findingVerificationService.verify(any(), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    when(followUpAnalyzer.dropRepliedDuplicates(any(), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient()
        .when(
            followUpAnalyzer.previousFindingFilesById(
                org.mockito.ArgumentMatchers
                    .<java.util.List<
                            dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse.Finding>>
                        any()))
        .thenReturn(Map.of());
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
    return reviewContext(List.of());
  }

  /** Same context with a caller-supplied changed-file list, so rename disclosure can be driven. */
  private static ReviewContextLoader.ReviewContext reviewContext(List<FileDiff> files) {
    return reviewContext(
        files,
        List.of(
            new FileDiff("a.java", "modified", 3, 0, 3, ""),
            new FileDiff("b.java", "modified", 2, 0, 2, "")),
        null);
  }

  /** Same context with an explicit reviewable-file set and GitHub's PR totals (may be null). */
  private static ReviewContextLoader.ReviewContext reviewContext(
      List<FileDiff> files, List<FileDiff> reviewableFiles, ReviewContextLoader.PrTotals prTotals) {
    return new ReviewContextLoader.ReviewContext(
        files,
        "raw legacy diff",
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
        reviewableFiles,
        () -> new DiffLineResolver(Map.of()),
        prTotals);
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
    when(followUpAnalyzer.previousFindingFilesById(
            org.mockito.ArgumentMatchers
                .<java.util.List<dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse.Finding>>
                    any()))
        .thenReturn(Map.of(1, "b.java"));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenReturn(
            new ReviewResponse(List.of(finding("a.java", "A")), List.of(batchOneStatus), null));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenReturn(
            new ReviewResponse(List.of(finding("b.java", "B")), List.of(batchTwoStatus), null));

    var summary = new ReviewResponse.Summary(2, 0, 0, 2, 0, "looks ok", "does things", List.of());
    var summaryStatuses = List.of(new ReviewResponse.PreviousFindingStatus(9, "resolved", "no"));
    when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), summaryStatuses, summary));

    var result =
        pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    verify(aiReviewService).reviewBatch(eq(session), any(), eq(1), eq(2));
    verify(aiReviewService).reviewBatch(eq(session), any(), eq(2), eq(2));
    verify(aiReviewService).summarize(eq(session), any());
    verify(findingVerificationService, times(2)).verify(any(), any(), any(), any());

    assertEquals(2, result.findings().size());
    assertSame(summary, result.summary());
    assertEquals(List.of(batchTwoStatus), result.previousFindingsStatus());
  }

  @Test
  void multiCallDoesNotRetryABatchTruncatedAtTheModelsLengthCap() {
    // #492: a length stop is deterministic — re-sending the identical prompt against the identical
    // cap is cut at the identical point. The generic soft-fail path retries once before giving up,
    // which for a truncation is a second guaranteed-futile billed call. It must go straight to the
    // disclosure instead, exactly once.
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenThrow(new AiResponseTruncatedException("finish_reason=length"));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("b.java", "B")), List.of(), null));
    var summary = new ReviewResponse.Summary(1, 0, 0, 1, 0, "ok", "does things", List.of());
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), summary));

    var plan = multiBatchPlan();
    var result = pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    verify(aiReviewService, times(1))
        .reviewBatch(eq(session), any(), eq(1), anyInt()); // no second, futile call

    // The rest of the soft-fail contract is unchanged: the successful batch keeps its findings and
    // the truncated batch's files are disclosed rather than silently dropped.
    assertEquals(1, result.findings().size());
    assertEquals("B", result.findings().get(0).title());
    assertEquals(List.of("a.java"), plan.runtimeUncoveredFiles());
    assertTrue(plan.truncated());
    assertTrue(captor.getValue().changedFiles().contains("a.java (not reviewed"));
  }

  @Test
  void multiCallSurvivesACyclicCauseChainOnAFailedBatch() {
    // The truncation check walks the cause chain, and a cycle (here A caused-by B caused-by A)
    // would spin forever on the review thread if the walk were unbounded. The batch must instead
    // fall through to the ordinary soft-fail path — retried once, then disclosed.
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    var cyclic = cyclicFailure();
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt())).thenThrow(cyclic);
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("b.java", "B")), List.of(), null));
    var summary = new ReviewResponse.Summary(1, 0, 0, 1, 0, "ok", "does things", List.of());
    when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), summary));

    var plan = multiBatchPlan();
    var result =
        assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(10),
            () -> pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of())));

    // Not a truncation, so the generic path applies: tried once, retried once, then disclosed.
    verify(aiReviewService, times(2)).reviewBatch(eq(session), any(), eq(1), anyInt());
    assertEquals(1, result.findings().size());
    assertEquals(List.of("a.java"), plan.runtimeUncoveredFiles());
  }

  /**
   * A cause chain that genuinely loops: {@code a -> b -> a -> b -> ...}, never reaching {@code
   * null}. Built through a holder because the two exceptions have to reference each other.
   *
   * <p>An earlier version of this helper ended in a plain exception, so the walk terminated on
   * {@code cause != null} and the depth bound was never exercised — the test passed without testing
   * anything. Coverage caught it.
   */
  private static RuntimeException cyclicFailure() {
    var forward = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var a =
        new RuntimeException("a") {
          @Override
          public synchronized Throwable getCause() {
            return forward.get();
          }
        };
    var b =
        new RuntimeException("b") {
          @Override
          public synchronized Throwable getCause() {
            return a;
          }
        };
    forward.set(b);
    return a;
  }

  @Test
  void multiCallSoftFailsAPersistentlyFailingBatchAndKeepsTheSuccessfulOnes() {
    // Lead#3: a batch that fails all its retries must not discard the batches that succeeded. The
    // review keeps their findings, records the failed batch's files as uncovered (so the verdict
    // holds APPROVE and the summary discloses the gap), and still produces a summary — instead of
    // throwing IllegalStateException and reporting the whole review as failed.
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    var failure = new AiReviewException("batch blew up", 1, null);
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt())).thenThrow(failure);
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("b.java", "B")), List.of(), null));
    var summary = new ReviewResponse.Summary(1, 0, 0, 1, 0, "ok", "does things", List.of());
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), summary));

    var plan = multiBatchPlan();
    var result = pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    // Batch 1 is tried in the parallel pass and retried once; both fail, then it is soft-failed.
    verify(aiReviewService, times(2)).reviewBatch(eq(session), any(), eq(1), anyInt());
    verify(aiReviewService).summarize(eq(session), any());
    assertEquals(1, result.findings().size());
    assertEquals("B", result.findings().get(0).title());
    assertSame(summary, result.summary());

    // The failed batch's file is recorded as an uncovered coverage gap on the shared plan, so the
    // verdict (which reads the same instance) holds APPROVE and discloses it.
    assertEquals(List.of("a.java"), plan.runtimeUncoveredFiles());
    assertTrue(plan.truncated());
    var changedFiles = captor.getValue().changedFiles();
    assertTrue(changedFiles.contains("a.java (not reviewed"), changedFiles);
    assertTrue(changedFiles.contains("b.java (modified"), changedFiles);
  }

  @Test
  void multiCallRetriesFailedBatchWithoutDiscardingSuccessfulOnes() {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    var transientFailure = new AiReviewException("transient", 1, null);
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenThrow(transientFailure)
        .thenReturn(new ReviewResponse(List.of(finding("a.java", "A")), List.of(), null));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("b.java", "B")), List.of(), null));

    var summary = new ReviewResponse.Summary(2, 0, 0, 2, 0, "ok", "does things", List.of());
    when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), summary));

    var result =
        pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    verify(aiReviewService, times(2)).reviewBatch(eq(session), any(), eq(1), anyInt());
    verify(aiReviewService).reviewBatch(eq(session), any(), eq(2), anyInt());
    assertEquals(2, result.findings().size());
    assertSame(summary, result.summary());
  }

  @Test
  void unwrapParallelFailureUsesCompletionExceptionWhenCauseMissing() {
    var missingCause = new CompletionException((Throwable) null);
    var wrapped = FindingPipeline.unwrapParallelFailure(missingCause);
    assertEquals("Parallel batch review failed", wrapped.getMessage());
    assertSame(missingCause, wrapped.getCause());
  }

  @Test
  void unwrapParallelFailurePreservesTheUnderlyingCause() {
    // The caller distinguishes an AI failure from a wiring failure through getCause(), so the
    // real cause must survive the IllegalStateException wrapper SpotBugs requires.
    var cause = new IllegalArgumentException("model refused");
    var wrapped = FindingPipeline.unwrapParallelFailure(new CompletionException(cause));

    assertEquals("Parallel batch review failed", wrapped.getMessage());
    assertSame(cause, wrapped.getCause());
  }

  @Test
  void summaryOverviewLeadsWithThePureRenameRollup() {
    var session = ReviewSession.create("owner/repo", 1, "Move a package", "sha");
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    var ctx =
        reviewContext(
            List.of(
                new FileDiff("pkg/B.java", "renamed", 0, 0, 0, null, "pkg/A.java"),
                new FileDiff("a.java", "modified", 3, 0, 3, "")));
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    // Leading, so clamping a long overview can never drop the disclosure (#386).
    assertTrue(
        captor.getValue().changedFiles().startsWith("1 pure rename omitted from AI review"),
        captor.getValue().changedFiles());
    assertTrue(captor.getValue().changedFiles().contains("pkg/A.java → pkg/B.java"));
  }

  /** Runs the multi-call path and returns the changed-files section the summary call received. */
  private String captureSummaryChangedFiles(
      ReviewSession session, ReviewContextLoader.ReviewContext ctx) {
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    return captor.getValue().changedFiles();
  }

  @Test
  void summaryOverviewStatesTheWholePrScopeForAMultiFileRefactor() {
    // The #335 fixture: a decompose whose title/body announce the full scope, but whose summary
    // call sees no diff — without these totals nothing tells it the change is more than one class.
    var session = ReviewSession.create("owner/repo", 1, "Decompose the orchestrator", "sha");
    var files =
        List.of(
            new FileDiff(
                "src/main/java/app/review/Orchestrator.java", "modified", 40, 900, 940, ""),
            new FileDiff(
                "src/main/java/app/review/CiStatusEvaluator.java", "added", 120, 0, 120, ""),
            new FileDiff("src/main/java/app/review/FindingPipeline.java", "added", 300, 0, 300, ""),
            new FileDiff("src/test/java/app/review/PipelineTest.java", "added", 200, 0, 200, ""),
            new FileDiff("README.md", "modified", 4, 2, 6, ""));
    var overview = captureSummaryChangedFiles(session, reviewContext(files, files, null));

    assertTrue(
        overview.contains("PR scope (whole pull request): 5 files changed, +664 -902"), overview);
    assertTrue(overview.contains("Directories touched: 3"), overview);
    assertTrue(overview.contains("- src/main/java/app/review: 3 files (+460 -900)"), overview);
    assertTrue(overview.contains("- src/test/java/app/review: 1 file (+200 -0)"), overview);
    assertTrue(overview.contains("- (repository root): 1 file (+4 -2)"), overview);
    // Ahead of the per-file rows, so clamping a long overview can only drop the tail.
    assertTrue(
        overview.indexOf("PR scope (whole pull request)") < overview.indexOf("README.md (modified"),
        overview);
  }

  @Test
  void summaryOverviewScopeUsesGitHubsAuthoritativeTotalsWhenAvailable() {
    // Same totals the rendered Changes Overview reports (#298), so the prose cannot contradict it.
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var reviewable =
        List.of(
            new FileDiff("a.java", "modified", 3, 0, 3, ""),
            new FileDiff("b.java", "modified", 2, 0, 2, ""));
    var ctx = reviewContext(List.of(), reviewable, new ReviewContextLoader.PrTotals(23, 1612, 240));

    var overview = captureSummaryChangedFiles(session, ctx);

    assertTrue(
        overview.contains("PR scope (whole pull request): 23 files changed, +1612 -240"), overview);
  }

  @Test
  void summaryOverviewScopeStaysSingularForASingleFilePr() {
    // No regression on small single-purpose PRs: no multi-file or multi-directory language.
    var session = ReviewSession.create("owner/repo", 1, "Fix a typo", "sha");
    var files = List.of(new FileDiff("src/main/java/app/Tiny.java", "modified", 3, 1, 4, ""));
    var overview = captureSummaryChangedFiles(session, reviewContext(files, files, null));

    assertTrue(overview.contains("PR scope (whole pull request): 1 file changed, +3 -1"), overview);
    assertTrue(overview.contains("Directories touched: 1"), overview);
    assertTrue(overview.contains("- src/main/java/app: 1 file (+3 -1)"), overview);
    assertFalse(overview.contains("files changed"), overview);
    assertFalse(overview.contains("more directories"), overview);
  }

  @Test
  void summaryOverviewScopeFallsBackToDiffCountsWhenPrTotalsAreEmpty() {
    // A zero file count means the totals carry nothing usable; announcing a zero-file PR over a
    // non-empty file list would contradict the very list printed below it.
    var session = ReviewSession.create("owner/repo", 1, "Totals unavailable", "sha");
    var reviewable =
        List.of(
            new FileDiff("a.java", "modified", 3, 0, 3, ""),
            new FileDiff("b.java", "modified", 2, 0, 2, ""));
    var ctx = reviewContext(List.of(), reviewable, new ReviewContextLoader.PrTotals(0, 0, 0));

    var overview = captureSummaryChangedFiles(session, ctx);

    assertTrue(
        overview.contains("PR scope (whole pull request): 2 files changed, +5 -0"), overview);
  }

  @Test
  void summaryOverviewOmitsTheScopeBlockWhenNothingIsInTheChangeSet() {
    // Nothing reviewable and no PR totals: emit no scope header at all rather than "0 files".
    var session = ReviewSession.create("owner/repo", 1, "Everything ignored", "sha");

    var overview = captureSummaryChangedFiles(session, reviewContext(List.of(), List.of(), null));

    assertEquals("", overview);
  }

  @Test
  void summaryOverviewScopeKeepsTotalsWhenNoFileSurvivesTheIgnoreGlob() {
    // GitHub still reports the PR's real size when the ignore-glob drops every changed file, so
    // the header stands on its own — with no directory breakdown, which would have to be empty.
    var session = ReviewSession.create("owner/repo", 1, "All ignored", "sha");
    var ctx = reviewContext(List.of(), List.of(), new ReviewContextLoader.PrTotals(23, 1612, 240));

    var overview = captureSummaryChangedFiles(session, ctx);

    assertEquals("PR scope (whole pull request): 23 files changed, +1612 -240\n", overview);
  }

  @Test
  void summaryOverviewScopeBucketsAPathWithNoDirectoryAtTheRoot() {
    // A name carrying no directory component — a blank one included — buckets at the root instead
    // of opening a directory row named after it.
    var session = ReviewSession.create("owner/repo", 1, "Odd payload", "sha");
    var files =
        List.of(
            new FileDiff("  ", "modified", 2, 0, 2, ""),
            new FileDiff("src/app/A.java", "modified", 4, 1, 5, ""));

    var overview = captureSummaryChangedFiles(session, reviewContext(files, files, null));

    assertTrue(
        overview.contains("PR scope (whole pull request): 2 files changed, +6 -1"), overview);
    assertTrue(overview.contains("Directories touched: 2"), overview);
    assertTrue(overview.contains("- (repository root): 1 file (+2 -0)"), overview);
    assertTrue(overview.contains("- src/app: 1 file (+4 -1)"), overview);
  }

  @Test
  void summaryOverviewRollsUpDirectoriesBeyondTheCap() {
    var session = ReviewSession.create("owner/repo", 1, "Wide PR", "sha");
    var files = new ArrayList<FileDiff>();
    for (var i = 0; i < 12; i++) {
      files.add(new FileDiff("pkg" + i + "/File.java", "modified", 1, 0, 1, ""));
    }
    var overview = captureSummaryChangedFiles(session, reviewContext(files, files, null));

    assertTrue(overview.contains("Directories touched: 12"), overview);
    assertTrue(overview.contains("- (+2 more directories)"), overview);
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
  void singleCallCeilingRefusalPropagatesForTheOrchestratorToFailSoft() {
    // Characterization of the single-call contract: a mid-retry ceiling refusal has no paid batch
    // findings to keep, so the pipeline does not degrade. The typed exception escapes the pipeline
    // and ReviewOrchestrator's RuntimeException handling fails the review soft — failure notice,
    // FAILED check run, session error — with ReviewDispatcher as the backstop, so nothing reaches
    // the webhook boundary. The multi-call path, which does hold paid findings, degrades instead.
    var session = persistedSession();
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "s", "t", "", "");
    var plan =
        new DiffBudgetPlanner.BudgetPlan(List.of(batch("a.java")), List.of(), List.of(), false);
    when(aiReviewService.review(eq(session), any()))
        .thenThrow(new TokenSpendCeilingExceededException(120_000, 100_000));

    var resolver = new DiffLineResolver(Map.of());
    var thrown =
        assertThrows(
            TokenSpendCeilingExceededException.class,
            () -> pipeline.run(session, template, ctx, plan, resolver));

    assertTrue(thrown.getMessage().contains("REVIEW_MAX_TOKENS_PER_REVIEW"), thrown.getMessage());
    assertNull(session.getAiResponseJson(), "a refused single-call review persists nothing");
  }

  @Test
  void resolvedFromABatchThatNeverSawTheFileIsDemotedToUnresolved() {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(followUpAnalyzer.previousFindingFilesById(
            org.mockito.ArgumentMatchers
                .<java.util.List<dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse.Finding>>
                    any()))
        .thenReturn(Map.of(1, "b.java", 2, "a.java"));

    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenReturn(
            new ReviewResponse(
                List.of(),
                List.of(
                    new ReviewResponse.PreviousFindingStatus(1, "resolved", "looks done"),
                    new ReviewResponse.PreviousFindingStatus(2, "resolved", "fixed here"),
                    new ReviewResponse.PreviousFindingStatus(3, "resolved", "no map entry")),
                null));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
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
    assertEquals("unresolved", result.previousFindingsStatus().get(2).status());
  }

  @Test
  void singleBudgetedBatchScopesResolutionClaimsLikeTheMultiCallPath() {
    var session = ReviewSession.create("owner/repo", 1, "One clipped file", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("raw", "ctx", "base", "s", "t", "", "");
    when(followUpAnalyzer.previousFindingFilesById(
            org.mockito.ArgumentMatchers
                .<java.util.List<dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse.Finding>>
                    any()))
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
    when(followUpAnalyzer.previousFindingFilesById(
            org.mockito.ArgumentMatchers
                .<java.util.List<dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse.Finding>>
                    any()))
        .thenReturn(Map.of(1, "a.java"));
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(batch("a.java"), batch("b.java")), List.of(), List.of("a.java"), true);
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenReturn(
            new ReviewResponse(
                List.of(),
                List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "looks fixed")),
                null));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
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

  /**
   * #471 — {@code FileDiff.filename()} is not validated at construction, and the omitted/clipped
   * lookups run against immutable sets whose {@code contains(null)} throws instead of answering
   * false. An unnamed file must be treated as neither omitted nor clipped, matching the null guard
   * the ignore globs already carry, rather than failing the whole review off the ack thread.
   */
  @Test
  void anUnnamedReviewableFileIsNeitherOmittedNorClippedInTheSummaryOverview() {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx =
        reviewContext(
            List.of(),
            List.of(
                new FileDiff(null, "modified", 7, 0, 7, ""),
                new FileDiff("a.java", "modified", 3, 0, 3, "")),
            null);
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(batch("a.java"), batch("b.java")), List.of("z.java"), List.of("a.java"), true);
    when(budgetPlanner.perCallInputBudget()).thenReturn(1_000_000);
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    var changedFiles = captor.getValue().changedFiles();
    assertTrue(
        changedFiles.contains("(modified, +7 -0)\n"),
        "the unnamed file is kept as an ordinary row: not skipped as omitted, not marked clipped");
    assertTrue(changedFiles.contains("a.java (modified, +3 -0 — partially analyzed"), changedFiles);
    assertTrue(changedFiles.contains("z.java (omitted"), changedFiles);
  }

  @Test
  void summaryFindingsJsonIsClampedToThePerCallBudget() throws Exception {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "d", "", "", "", "", "");
    // Descriptions long enough that one finding outweighs the changed-files overview: the budget
    // below leaves room for exactly one, and the overview keeps its own (larger) share unclamped.
    var desc =
        "this description exists to make one serialized finding the dominant cost ".repeat(3);
    var high = new ReviewResponse.Finding("high", "high", "a.java", 1, "H", desc, "o", "n");
    var medium = new ReviewResponse.Finding("medium", "high", "a.java", 3, "M", desc, "o", "n");
    var nullRisk = new ReviewResponse.Finding(null, "high", "a.java", 2, "N", desc, "o", "n");
    var critical = new ReviewResponse.Finding("critical", "high", "b.java", 1, "C", desc, "o", "n");

    var tokenCounter = new TokenCounter();
    // The overview the pipeline will build (scope header + per-file rows), so the budget below is
    // calibrated against the same fixed sections the production code measures.
    var overview =
        """
        PR scope (whole pull request): 2 files changed, +5 -0
        Directories touched: 1
        - (repository root): 2 files (+5 -0)
        a.java (modified, +3 -0)
        b.java (modified, +2 -0)
        """;
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

    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenReturn(new ReviewResponse(List.of(high, medium, nullRisk), List.of(), null));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenReturn(new ReviewResponse(List.of(critical), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var result =
        pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    assertEquals(4, result.findings().size());
    var serialized = captor.getValue().findings();
    assertTrue(serialized.contains("\"C\""), serialized);
    assertFalse(serialized.contains("\"H\""), serialized);
    assertFalse(serialized.contains("\"N\""), serialized);
    assertTrue(serialized.contains("+3 more findings not shown"), serialized);
    assertTrue(serialized.contains("4 total, 1 critical, 1 high, 1 medium, 1 low"), serialized);
  }

  /** A session persisted far enough to have an id, which the token ledger is keyed by. */
  private static ReviewSession persistedSession() {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    session.id = 42L;
    return session;
  }

  @Test
  void multiCallDoesNotRetryABatchBlockedByTheSpendCeilingAndDisclosesIt() {
    // #499(a): once the review's token spend ceiling is reached, a blocked batch must not be
    // retried (the ledger is monotonic — the retry would be refused identically), its files must
    // be disclosed with the ceiling as the reason, and the batches already paid for keep their
    // findings.
    var session = persistedSession();
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("a.java", "A")), List.of(), null));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenThrow(new TokenSpendCeilingExceededException(120_000, 100_000));
    when(tokenLedger.ceilingReached(42L)).thenReturn(true);
    // ceilingReached=true implies spent >= a positive ceiling — stub the state consistently.
    when(tokenLedger.tokensSpent(42L)).thenReturn(106_000L);
    lenient()
        .when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var plan = multiBatchPlan();
    var result = pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    verify(aiReviewService, times(1))
        .reviewBatch(eq(session), any(), eq(2), anyInt()); // no second, knowably-refused call
    verify(aiReviewService, never()).summarize(any(), any()); // the ceiling blocks the summary too
    assertEquals(1, result.findings().size());
    assertEquals("A", result.findings().get(0).title());
    assertEquals(List.of("b.java"), plan.spendCeilingSkippedFiles());
    assertEquals(List.of("b.java"), plan.runtimeUncoveredFiles());
    assertTrue(plan.truncated());
  }

  @Test
  void multiCallSkipsTheSequentialBatchRetryOnceTheSpendCeilingIsReached() {
    // #499(b) at the pipeline layer: the sequential batch retry is a fresh billed call, so it is
    // gated on the ledger before being made at all.
    var session = persistedSession();
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenThrow(new AiReviewException("transient", 1, null));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("b.java", "B")), List.of(), null));
    when(tokenLedger.ceilingReached(42L)).thenReturn(true);
    // ceilingReached=true implies spent >= a positive ceiling — stub the state consistently.
    when(tokenLedger.tokensSpent(42L)).thenReturn(106_000L);
    lenient()
        .when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var plan = multiBatchPlan();
    var result = pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    verify(aiReviewService, times(1))
        .reviewBatch(eq(session), any(), eq(1), anyInt()); // parallel attempt only, no retry
    verify(aiReviewService, never()).summarize(any(), any());
    assertEquals(1, result.findings().size());
    assertEquals("B", result.findings().get(0).title());
    assertEquals(List.of("a.java"), plan.spendCeilingSkippedFiles());
  }

  @Test
  void multiCallDisclosesABatchWhoseSequentialRetryIsRefusedMidLoop() {
    // The sequential retry's pre-gate passed (a race with a late usage callback, or the ceiling
    // trips on a retry attempt inside the call), so the refusal surfaces as the typed exception
    // from the call itself — it must land in the ceiling disclosure, not the generic soft-fail.
    var session = persistedSession();
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenThrow(new AiReviewException("transient", 1, null))
        .thenThrow(new TokenSpendCeilingExceededException(120_000, 100_000));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("b.java", "B")), List.of(), null));
    when(tokenLedger.ceilingReached(42L)).thenReturn(false).thenReturn(true);
    lenient()
        .when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var plan = multiBatchPlan();
    var result = pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    verify(aiReviewService, times(2)).reviewBatch(eq(session), any(), eq(1), anyInt());
    assertEquals(List.of("a.java"), plan.spendCeilingSkippedFiles());
    assertEquals(1, result.findings().size());
    assertEquals("B", result.findings().get(0).title());
  }

  @Test
  void multiCallKeepsFindingsWhenTheSummaryCallItselfIsRefusedAtTheCeiling() {
    // The pre-summary gate can pass and the summary call still be refused (a late usage callback
    // crossed the ceiling in between, or a summary retry was refused). The paid findings must
    // still come back with the counts-only summary, not be lost to the propagating refusal.
    var session = persistedSession();
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("a.java", "A")), List.of(), null));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("b.java", "B")), List.of(), null));
    when(tokenLedger.ceilingReached(42L)).thenReturn(false);
    when(aiReviewService.summarize(eq(session), any()))
        .thenThrow(new TokenSpendCeilingExceededException(120_000, 100_000));

    var result =
        pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    assertEquals(2, result.findings().size());
    assertNull(result.summary());
    assertNotNull(session.getAiResponseJson());
  }

  @Test
  void multiCallFallsBackToACountsOnlySummaryWhenTheCeilingTripsBeforeTheSummaryCall() {
    // #499(c): the batch findings are already paid for. A ceiling reached before the summary call
    // must degrade to the counts-only summary (null model summary — the same shape the renderer
    // already handles), not lose the whole review.
    var session = persistedSession();
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), eq(1), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("a.java", "A")), List.of(), null));
    when(aiReviewService.reviewBatch(eq(session), any(), eq(2), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("b.java", "B")), List.of(), null));
    when(tokenLedger.ceilingReached(42L)).thenReturn(true);
    lenient()
        .when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var result =
        pipeline.run(session, template, ctx, multiBatchPlan(), new DiffLineResolver(Map.of()));

    verify(aiReviewService, never()).summarize(any(), any());
    assertEquals(2, result.findings().size());
    assertNull(result.summary());
    assertNotNull(session.getAiResponseJson(), "the paid findings must still be persisted");
  }

  @Test
  void billedThenRefusedBatchesDiscloseInsteadOfClaimingNoCallsWereMade() {
    // A batch can cross the ceiling AFTER its first billed attempt, with the retry gate then
    // refusing typed. That shape must not hit the zero-call branch — its "Review made no AI
    // calls" message would be contradicted by the very spend figure inside it. It falls through
    // to disclosure + the counts-only summary, like every other ceiling refusal.
    var session = persistedSession();
    var ctx = reviewContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenThrow(new TokenSpendCeilingExceededException(106_000, 100_000));
    when(tokenLedger.ceilingReached(42L)).thenReturn(true);
    when(tokenLedger.tokensSpent(42L)).thenReturn(106_000L);

    var plan = multiBatchPlan();
    var result = pipeline.run(session, template, ctx, plan, new DiffLineResolver(Map.of()));

    assertTrue(result.findings().isEmpty());
    assertEquals(List.of("a.java", "b.java"), plan.spendCeilingSkippedFiles());
    verify(aiReviewService, never()).summarize(eq(session), any());
  }

  @Test
  void aDisabledCeilingLeavesTheMultiCallPathUntouched() {
    // #499(d) characterization: with the default REVIEW_MAX_TOKENS_PER_REVIEW=0 a review behaves
    // exactly as before this feature, even with an enormous recorded spend.
    var thrillhouseConfig = mock(dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.class);
    var reviewConfig =
        mock(dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.ReviewConfig.class);
    when(thrillhouseConfig.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxTokensPerReview()).thenReturn(0L);
    var realLedger = new ReviewTokenLedger(thrillhouseConfig);
    realLedger.open(42L);
    realLedger.recordUsage(42L, 900_000, 100_000);
    var p =
        new FindingPipeline(
            aiReviewService,
            quoteValidator,
            frameworkFilter,
            deduplicator,
            findingVerificationService,
            followUpAnalyzer,
            new ObjectMapper(),
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            budgetPlanner,
            new TokenCounter(),
            realLedger);
    var session = persistedSession();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    var summary = new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", "does things", List.of());
    when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), summary));

    var plan = multiBatchPlan();
    var result = p.run(session, template, reviewContext(), plan, new DiffLineResolver(Map.of()));

    verify(aiReviewService).reviewBatch(eq(session), any(), eq(1), anyInt());
    verify(aiReviewService).reviewBatch(eq(session), any(), eq(2), anyInt());
    verify(aiReviewService).summarize(eq(session), any());
    assertSame(summary, result.summary());
    assertTrue(plan.spendCeilingSkippedFiles().isEmpty());
  }

  @Test
  void mergeBatchStatusesLetsTheEvidenceBackedClaimWin() {
    var unresolvedOne = new ReviewResponse.PreviousFindingStatus(1, "unresolved", "not here");
    var resolvedOne = new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed in slice");
    var justifiedTwo = new ReviewResponse.PreviousFindingStatus(2, "justified", "author reply");
    var unresolvedTwo = new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still open");
    var unresolvedThree = new ReviewResponse.PreviousFindingStatus(3, "unresolved", "open");
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
            frameworkFilter,
            deduplicator,
            findingVerificationService,
            followUpAnalyzer,
            throwingMapper,
            BotIdentity.from(List.of("thrillhousebot[bot]")),
            budgetPlanner,
            new TokenCounter(),
            tokenLedger);
    var session = ReviewSession.create("owner/repo", 1, "PR", "sha");
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");
    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("a.java", "A")), List.of(), null));
    var captor = ArgumentCaptor.forClass(AiReviewService.SummaryInputs.class);
    when(aiReviewService.summarize(eq(session), captor.capture()))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    p.run(session, template, reviewContext(), multiBatchPlan(), new DiffLineResolver(Map.of()));

    assertTrue(captor.getValue().findings().startsWith("[]"), captor.getValue().findings());
    assertTrue(
        captor.getValue().findings().contains("more findings not shown"),
        captor.getValue().findings());
  }
}
