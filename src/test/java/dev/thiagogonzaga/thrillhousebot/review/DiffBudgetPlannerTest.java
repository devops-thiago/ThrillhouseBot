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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DiffBudgetPlanner}: packing, priority, clipping, omitted-by-name. */
class DiffBudgetPlannerTest {

  private static final String MODEL = "test-model";

  private final ReviewDiffFormatter formatter = new ReviewDiffFormatter(List.of("**/*.lock"), 0);
  private final TokenCounter tokenCounter = new TokenCounter();
  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);
  private final ThrillhouseConfig.ReviewConfig reviewConfig =
      mock(ThrillhouseConfig.ReviewConfig.class);
  private final ThrillhouseConfig.AiPricingConfig aiConfig =
      mock(ThrillhouseConfig.AiPricingConfig.class);
  private final Map<String, ThrillhouseConfig.AiPricingConfig.ModelSettings> models =
      new HashMap<>();
  private final DiffBudgetPlanner planner =
      new DiffBudgetPlanner(
          formatter, tokenCounter, config, new ActiveModelSettings(config, MODEL));

  {
    lenient().when(config.review()).thenReturn(reviewConfig);
    lenient().when(config.ai()).thenReturn(aiConfig);
    lenient().when(aiConfig.models()).thenReturn(models);
  }

  /** A per-model settings entry for {@link #MODEL}; unset values fall back to the review keys. */
  private ThrillhouseConfig.AiPricingConfig.ModelSettings modelSettings(
      Optional<Integer> maxInputTokens,
      Optional<Integer> outputBufferTokens,
      Optional<Double> tokenSafetyMargin) {
    var settings = mock(ThrillhouseConfig.AiPricingConfig.ModelSettings.class);
    lenient().when(settings.maxInputTokens()).thenReturn(maxInputTokens);
    lenient().when(settings.outputBufferTokens()).thenReturn(outputBufferTokens);
    lenient().when(settings.tokenSafetyMargin()).thenReturn(tokenSafetyMargin);
    lenient().when(settings.separateOutputBudget()).thenReturn(Optional.empty());
    return settings;
  }

  private static FileDiff file(String name, int additions, String patch) {
    return new FileDiff(name, "modified", additions, 0, additions, patch);
  }

  /** A patch of {@code lines} added lines — enough text to carry a measurable token cost. */
  private static String patch(int lines) {
    var sb = new StringBuilder("@@ -1," + lines + " +1," + lines + " @@\n");
    for (var i = 0; i < lines; i++) {
      sb.append("+    final var someLocalVariableNumber").append(i).append(" = compute(i);\n");
    }
    return sb.toString();
  }

  private int sectionTokens(FileDiff f) {
    return tokenCounter.estimateTokens(formatter.formatFileSection(f, Set.of(f.filename())));
  }

  private static List<String> coveredFilenames(DiffBudgetPlanner.BudgetPlan plan) {
    var names = new ArrayList<String>();
    plan.batches().forEach(b -> b.files().forEach(f -> names.add(f.filename())));
    return names;
  }

  @Test
  void emptyInputGivesEmptyPlan() {
    var plan = planner.plan(List.of(), 100, 3);
    assertTrue(plan.batches().isEmpty());
    assertTrue(plan.omittedFiles().isEmpty());
    assertFalse(plan.truncated());
  }

  @Test
  void zeroBudgetPutsEveryReviewableFileInOneBatch() {
    var files =
        List.of(
            file("dir/f1.java", 5, patch(5)),
            file("dir/f2.java", 5, patch(5)),
            file("dir/f3.java", 5, patch(5)));
    var plan = planner.plan(files, 0, 3);
    assertEquals(1, plan.batches().size());
    assertEquals(3, plan.batches().get(0).files().size());
    assertFalse(plan.truncated());
  }

  @Test
  void smallFilesFitASingleBatch() {
    var files = List.of(file("dir/f1.java", 4, patch(4)), file("dir/f2.java", 4, patch(4)));
    var budget = sectionTokens(files.get(0)) + sectionTokens(files.get(1)) + 50;
    var plan = planner.plan(files, budget, 3);
    assertEquals(1, plan.batches().size());
    assertFalse(plan.multiCall());
    assertEquals(2, plan.batches().get(0).files().size());
  }

  @Test
  void splitsAcrossBatchesEachWithinBudget() {
    var files =
        List.of(
            file("dir/f1.java", 6, patch(6)),
            file("dir/f2.java", 6, patch(6)),
            file("dir/f3.java", 6, patch(6)),
            file("dir/f4.java", 6, patch(6)));
    var t = sectionTokens(files.get(0));
    var plan = planner.plan(files, 2 * t, 5);

    assertEquals(2, plan.batches().size());
    assertFalse(plan.truncated());
    assertEquals(4, coveredFilenames(plan).size());
    for (var batch : plan.batches()) {
      assertTrue(batch.estimatedTokens() <= 2 * t, "batch over budget: " + batch.estimatedTokens());
    }
  }

  @Test
  void maxBatchesCapOmitsLowestImpactByName() {
    var files =
        List.of(
            file("dir/fa.java", 40, patch(6)),
            file("dir/fb.java", 30, patch(6)),
            file("dir/fc.java", 20, patch(6)),
            file("dir/fd.java", 10, patch(6)));
    var budget = files.stream().mapToInt(this::sectionTokens).max().orElseThrow(); // one file/bin
    var plan = planner.plan(files, budget, 2);

    assertEquals(2, plan.batches().size());
    assertTrue(plan.truncated());
    assertEquals(List.of("dir/fc.java", "dir/fd.java"), plan.omittedFiles());
    assertEquals(List.of("dir/fa.java", "dir/fb.java"), coveredFilenames(plan));
  }

  @Test
  void oversizedSingleFileIsClippedToFitOneBatch() {
    var big = file("dir/huge.java", 400, patch(400));
    var budget = 80;
    var plan = planner.plan(List.of(big), budget, 3);

    assertEquals(List.of("dir/huge.java"), plan.clippedFiles());
    assertEquals(1, plan.batches().size());
    var batch = plan.batches().get(0);
    assertEquals(1, batch.files().size());
    assertTrue(batch.estimatedTokens() <= budget, "clipped batch over budget");
    assertTrue(batch.text().contains("truncated"), "oversized section should be clipped");
    assertTrue(plan.truncated(), "a clipped file's unseen hunks make the review partial");
  }

  @Test
  void largePrStaysWithinCallCapAndAccountsForEveryFile() {
    var files = new ArrayList<FileDiff>();
    for (var i = 0; i < 120; i++) {
      files.add(file(String.format("src/F%03d.java", i), 6, patch(6)));
    }
    var perFile = sectionTokens(files.get(0)); // equal-size sections
    var maxBatches = 5;
    var budget = 10 * perFile; // ~10 files/batch → 5 batches cover 50, the rest overflow by name
    var plan = planner.plan(files, budget, maxBatches);

    assertTrue(plan.batches().size() <= maxBatches, "exceeded the call cap");
    for (var batch : plan.batches()) {
      assertTrue(batch.estimatedTokens() <= budget, "batch over budget");
    }

    var accounted = new HashSet<>(coveredFilenames(plan));
    accounted.addAll(plan.omittedFiles());
    assertEquals(120, accounted.size(), "every file must be covered or listed by name");
    assertEquals(50, coveredFilenames(plan).size());
    assertEquals(70, plan.omittedFiles().size());
  }

  @Test
  void absurdlySmallBudgetOmitsTheFileByName() {
    var big = file("dir/huge.java", 400, patch(400));
    var plan = planner.plan(List.of(big), 2, 1);
    assertTrue(plan.batches().isEmpty(), "an unclippable file must not be packed");
    assertEquals(List.of("dir/huge.java"), plan.omittedFiles());
    assertTrue(plan.truncated());
  }

  @Test
  void planTrustsTheCallerPreFilteredReviewableList() {
    var files = List.of(file("dir/app.java", 5, patch(5)), file("deps/yarn.lock", 999, patch(50)));
    var plan = planner.plan(formatter.reviewableFiles(files), 100_000, 3);
    var covered = coveredFilenames(plan);
    assertEquals(List.of("dir/app.java"), covered);
    assertFalse(plan.omittedFiles().contains("deps/yarn.lock"));
  }

  @Test
  void overheadConsumingTheBudgetKeepsBudgetingOnAndDisclosesOmissions() {
    var files = List.of(file("dir/f1.java", 5, patch(5)), file("dir/f2.java", 5, patch(5)));
    var overhead = patch(200); // far more tokens than the input budget below
    var plan = planner.plan(files, overhead, 50, 3);
    assertTrue(plan.budgeted(), "budgeting must stay on when overhead eats the budget");
    assertTrue(plan.batches().isEmpty());
    assertEquals(List.of("dir/f1.java", "dir/f2.java"), plan.omittedFiles());
  }

  @Test
  void aClippedFileThatOverflowsEveryBinIsOnlyOmittedNeverAlsoClipped() {
    var files = new ArrayList<FileDiff>();
    for (var i = 0; i < 5; i++) {
      files.add(file("dir/huge" + i + ".java", 400 - i, patch(400)));
    }
    var plan = planner.plan(files, 80, 1);

    assertFalse(plan.omittedFiles().isEmpty(), "scenario must overflow the single bin");
    assertFalse(plan.clippedFiles().isEmpty(), "scenario must clip the packed files");
    for (var omitted : plan.omittedFiles()) {
      assertFalse(plan.clippedFiles().contains(omitted), omitted + " listed in both classes");
    }
    var accounted = new HashSet<>(coveredFilenames(plan));
    accounted.addAll(plan.omittedFiles());
    assertEquals(5, accounted.size(), "every file covered or omitted, exactly once");
  }

  @Test
  void aPlanBuiltWithANullPatchlessListExposesAnEmptyOne() {
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), null, true, null, null, null, null, null);

    assertEquals(List.of(), plan.patchlessFiles());
    assertFalse(plan.truncated());
  }

  @Test
  void aPatchlessChangedFileIsOmittedByNameNotSilentlyReviewed() {
    // GitHub returns patch == null for binary files and for text diffs too large to display, while
    // still reporting real additions/deletions. Such a file survives isPureRename (non-zero change
    // count) and renders as a bare header with no ```diff``` body — there is nothing to review — so
    // the planner must omit it by name, never pack it as if it were fully reviewed.
    var patchless = new FileDiff("src/Huge.java", "modified", 4000, 10, 4010, null);
    var plan = planner.plan(List.of(patchless), 100_000, 3);

    assertEquals(List.of("src/Huge.java"), plan.patchlessFiles());
    assertTrue(
        plan.omittedFiles().isEmpty(),
        "a patch-less file is not a budget omission — nothing exceeded the budget (#628)");
    assertTrue(
        plan.truncated(), "an omitted patch-less file makes the review partial (holds APPROVE)");
    assertTrue(
        coveredFilenames(plan).isEmpty(), "a patch-less file must never be packed into a batch");
  }

  @Test
  void aBlankPatchChangedFileIsOmittedWhileRealDiffsAreStillPacked() {
    // The patch-less omission does not swallow files that do carry a diff: only the empty one is
    // dropped, the real one is packed and covered.
    var blank = new FileDiff("src/Blob.bin", "modified", 900, 0, 900, "   ");
    var real = file("src/App.java", 5, patch(5));
    var plan = planner.plan(List.of(blank, real), 100_000, 3);

    assertEquals(List.of("src/Blob.bin"), plan.patchlessFiles());
    assertTrue(plan.omittedFiles().isEmpty());
    assertEquals(List.of("src/App.java"), coveredFilenames(plan));
    assertTrue(plan.truncated());
  }

  @Test
  void aPatchlessFileWithNoChangesIsNotTreatedAsACoverageGap() {
    // A pure rename carries no patch AND no additions/deletions. It has nothing to review, but it
    // is also nothing the review failed to cover — omitting it would hold APPROVE over a file that
    // never needed reviewing. Only a patch-less file with real changes is a gap.
    var pureRename = new FileDiff("src/Renamed.java", "renamed", 0, 0, 0, null);
    var plan = planner.plan(List.of(pureRename), 100_000, 3);

    assertTrue(
        plan.omittedFiles().isEmpty(), "a zero-change patch-less file is not a coverage gap");
    assertTrue(
        plan.patchlessFiles().isEmpty(), "a zero-change patch-less file is not a coverage gap");
    assertFalse(plan.truncated(), "a pure rename must not hold APPROVE");
  }

  @Test
  void recordingTheSameUncoveredFileTwiceKeepsOneEntry() {
    // A batch can be recorded more than once (a retry path, or two batches sharing a file), and the
    // coverage gap must not be double-counted in the disclosure or the omitted total.
    var plan = planner.plan(List.of(file("src/App.java", 5, patch(5))), 100_000, 3);

    plan.recordUncoveredFiles(List.of("src/Failed.java"));
    plan.recordUncoveredFiles(List.of("src/Failed.java"));

    assertEquals(List.of("src/Failed.java"), plan.runtimeUncoveredFiles());
  }

  @Test
  void recordingANullFilenameIsIgnored() {
    // A FileDiff can carry a null filename; recording it must not put a null into the gap list,
    // where it would reach the disclosure text and the null-hostile immutable copies downstream.
    var plan = planner.plan(List.of(file("src/App.java", 5, patch(5))), 100_000, 3);

    plan.recordUncoveredFiles(Arrays.asList("src/Failed.java", null));

    assertEquals(List.of("src/Failed.java"), plan.runtimeUncoveredFiles());
  }

  @Test
  void aPlanBuiltWithANullRuntimeGapListStillAcceptsGaps() {
    // The canonical constructor normalizes a null accumulator rather than storing it: a null here
    // would NPE the moment a failed batch recorded a gap, on the async review thread, taking the
    // whole review down with it.
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);

    assertTrue(plan.runtimeUncoveredFiles().isEmpty(), "a null accumulator normalizes to empty");

    plan.recordUncoveredFiles(List.of("src/Failed.java"));

    assertEquals(List.of("src/Failed.java"), plan.runtimeUncoveredFiles());
  }

  @Test
  void spendCeilingSkipsFoldIntoRuntimeGapsButKeepTheirOwnReason() {
    // #499: a ceiling-skipped batch's files take the same coverage-gap route as any runtime gap
    // (verdict holds, summary discloses) while staying separately attributable so the disclosure
    // can name the spend ceiling rather than the diff budget. Nulls and duplicates are dropped
    // like recordUncoveredFiles does, and the accessor is a defensive copy.
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);

    plan.recordSpendCeilingSkippedFiles(Arrays.asList("a.java", null, "a.java", "b.java"));

    assertEquals(List.of("a.java", "b.java"), plan.spendCeilingSkippedFiles());
    assertEquals(List.of("a.java", "b.java"), plan.runtimeUncoveredFiles());
    assertEquals(List.of("a.java", "b.java"), plan.effectiveOmittedFiles());
    assertTrue(plan.truncated());
    var skipped = plan.spendCeilingSkippedFiles();
    assertThrows(UnsupportedOperationException.class, () -> skipped.add("escaped.java"));
  }

  @Test
  void aPlanBuiltWithANullSpendCeilingListStillAcceptsSkips() {
    // Same normalization contract as the runtime-gap list: a null accumulator must not NPE the
    // moment a ceiling-blocked batch records a skip on the review thread.
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);

    assertTrue(plan.spendCeilingSkippedFiles().isEmpty());

    plan.recordSpendCeilingSkippedFiles(List.of("src/Skipped.java"));

    assertEquals(List.of("src/Skipped.java"), plan.spendCeilingSkippedFiles());
  }

  @Test
  void responseCutFilesStayOutOfTheUncoveredSetButHoldTruncation() {
    // #500: a salvaged batch's files are partially reviewed — they must trip the truncation gate
    // (approval held, disclosure rendered) without joining the not-reviewed sets, keep dedupe/null
    // hygiene like the other recorders, and stay behind a defensive copy.
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);

    plan.recordResponseCutFiles(Arrays.asList("a.java", null, "a.java", "b.java"));

    assertEquals(List.of("a.java", "b.java"), plan.responseCutFiles());
    assertTrue(plan.runtimeUncoveredFiles().isEmpty());
    assertTrue(plan.effectiveOmittedFiles().isEmpty());
    assertTrue(plan.truncated());
    var cutFiles = plan.responseCutFiles();
    assertThrows(UnsupportedOperationException.class, () -> cutFiles.add("escaped.java"));
  }

  @Test
  void aPlanBuiltWithANullResponseCutListStillAcceptsRecords() {
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);

    assertTrue(plan.responseCutFiles().isEmpty());

    plan.recordResponseCutFiles(List.of("src/Cut.java"));

    assertEquals(List.of("src/Cut.java"), plan.responseCutFiles());
  }

  @Test
  void summaryResponseCutIsRecordedLiveButItsAccessorReturnsASnapshot() {
    // #500 scope A: the degradation slot rides the shared plan like the runtime-gap lists —
    // written after the plan is built, read by the verdict — and, like them, its record accessor
    // must not leak the mutable backing.
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    assertEquals(SummaryDegradation.NONE, plan.summaryDegradation());

    plan.recordSummaryDegradation(SummaryDegradation.RESPONSE_CUT);

    assertEquals(SummaryDegradation.RESPONSE_CUT, plan.summaryDegradation());
    assertFalse(plan.truncated(), "a summary cut is not a coverage gap and must not hold approval");
    var snapshot = plan.summaryDegradationRef();
    snapshot.set(SummaryDegradation.NONE);
    assertEquals(
        SummaryDegradation.RESPONSE_CUT,
        plan.summaryDegradation(),
        "mutating the snapshot must not touch the live slot");
  }

  @Test
  void summarySkippedAtCeilingIsRecordedLiveOverwritingTheSingleSlot() {
    // #518: the ceiling flavor rides the same single slot — one enum makes the meaningless
    // cut-and-skipped-at-once state unrepresentable where the old boolean pair allowed it.
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, null);
    assertEquals(SummaryDegradation.NONE, plan.summaryDegradation());

    plan.recordSummaryDegradation(SummaryDegradation.SKIPPED_AT_CEILING);

    assertEquals(SummaryDegradation.SKIPPED_AT_CEILING, plan.summaryDegradation());
    assertFalse(
        plan.truncated(), "a skipped summary is not a coverage gap and must not hold approval");
    var snapshot = plan.summaryDegradationRef();
    snapshot.set(SummaryDegradation.NONE);
    assertEquals(
        SummaryDegradation.SKIPPED_AT_CEILING,
        plan.summaryDegradation(),
        "mutating the snapshot must not touch the live slot");
  }

  @Test
  void aPlanBuiltWithItsOwnDegradationSlotKeepsThatInstanceLive() {
    var live = new java.util.concurrent.atomic.AtomicReference<>(SummaryDegradation.RESPONSE_CUT);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of(), true, null, null, null, live);

    assertEquals(SummaryDegradation.RESPONSE_CUT, plan.summaryDegradation());
    assertNotSame(live, plan.summaryDegradationRef(), "the accessor returns a defensive snapshot");

    live.set(SummaryDegradation.NONE);
    assertEquals(
        SummaryDegradation.NONE,
        plan.summaryDegradation(),
        "the passed-in slot stays the live backing");
  }

  @Test
  void clippedFilesAreReportedUnchangedWhenNoBatchFailed() {
    // No runtime gap: the clipped list passes through, so a partially analyzed file keeps its
    // "clipped" meaning rather than being reported as wholly uncovered.
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of(), List.of("src/Clipped.java"), true, null, null, null, null);

    assertEquals(List.of("src/Clipped.java"), plan.effectiveClippedFiles());
  }

  @Test
  void aClippedFileAFailedBatchLeftUncoveredIsReportedOmittedNotClipped() {
    // A file that was clipped AND then lost to a failed batch must not be counted twice: it drops
    // out of the clipped list and is reported as omitted, so coverage is never overstated.
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(),
            List.of(),
            List.of("src/Clipped.java", "src/Other.java"),
            true,
            null,
            null,
            null,
            null);

    plan.recordUncoveredFiles(List.of("src/Clipped.java"));

    assertEquals(List.of("src/Other.java"), plan.effectiveClippedFiles());
    assertTrue(
        plan.effectiveOmittedFiles().contains("src/Clipped.java"),
        "the lost file is accounted for as omitted instead");
  }

  @Test
  void perCallInputBudgetIsUnboundedWhenBudgetingIsDisabled() {
    when(reviewConfig.maxInputTokens()).thenReturn(0);
    assertEquals(Integer.MAX_VALUE, planner.perCallInputBudget());
  }

  @Test
  void perCallInputBudgetAppliesMarginAndOutputBuffer() {
    when(reviewConfig.maxInputTokens()).thenReturn(48000);
    when(reviewConfig.tokenSafetyMargin()).thenReturn(0.9);
    when(reviewConfig.outputBufferTokens()).thenReturn(8192);
    assertEquals(43200 - 8192, planner.perCallInputBudget());
  }

  @Test
  void perCallInputBudgetKeepsTheWholeWindowWhenTheOutputBudgetIsSeparate() {
    // #493: the output buffer is subtracted because a shared window spends output tokens out of
    // the input pool. When the model's response allowance is its own, that subtraction hands the
    // diff budget away for nothing — here it would cost 384000 of a 900000-token budget.
    var settings = modelSettings(Optional.of(1_000_000), Optional.of(384_000), Optional.empty());
    lenient().when(settings.separateOutputBudget()).thenReturn(Optional.of(true));
    models.put(MODEL, settings);
    when(reviewConfig.maxInputTokens()).thenReturn(1_000_000);
    when(reviewConfig.tokenSafetyMargin()).thenReturn(0.9);

    assertEquals(900_000, planner.perCallInputBudget());
  }

  @Test
  void perCallInputBudgetStillSubtractsTheBufferOnASharedWindow() {
    // The counterpart: without the flag the subtraction stands, so this change cannot quietly
    // widen the budget for every existing deployment.
    var settings = modelSettings(Optional.of(1_000_000), Optional.of(384_000), Optional.empty());
    lenient().when(settings.separateOutputBudget()).thenReturn(Optional.of(false));
    models.put(MODEL, settings);
    when(reviewConfig.maxInputTokens()).thenReturn(1_000_000);
    when(reviewConfig.tokenSafetyMargin()).thenReturn(0.9);

    assertEquals(900_000 - 384_000, planner.perCallInputBudget());
  }

  @Test
  void configDisabledBudgetingYieldsOneUnbudgetedBatch() {
    when(reviewConfig.maxInputTokens()).thenReturn(0);
    var files = List.of(file("dir/f1.java", 5, patch(5)), file("dir/f2.java", 5, patch(5)));
    var inputs = new AiReviewService.PromptInputs("d", "ctx", "base", "s", "t", "", "");
    var plan = planner.plan(files, inputs);
    assertFalse(plan.budgeted());
    assertEquals(1, plan.batches().size());
    assertEquals(2, plan.batches().get(0).files().size());
  }

  @Test
  void configDrivenPlanSizesOverheadFromThePromptInputs() {
    when(reviewConfig.maxInputTokens()).thenReturn(200_000);
    when(reviewConfig.tokenSafetyMargin()).thenReturn(1.0);
    when(reviewConfig.outputBufferTokens()).thenReturn(0);
    when(reviewConfig.maxAiCalls()).thenReturn(6);
    var files = List.of(file("dir/f1.java", 5, patch(5)), file("dir/f2.java", 5, patch(5)));
    var inputs = new AiReviewService.PromptInputs("d", "ctx", "base", "s", "t", "", "");
    var plan = planner.plan(files, inputs);
    assertTrue(plan.budgeted());
    assertEquals(1, plan.batches().size());
    assertFalse(plan.truncated());
  }

  @Test
  void perCallInputBudgetIsCappedByTheDefaultModelInputCap() {
    when(reviewConfig.maxInputTokens()).thenReturn(500_000);
    when(reviewConfig.tokenSafetyMargin()).thenReturn(1.0);
    when(reviewConfig.outputBufferTokens()).thenReturn(0);
    assertEquals(128_000, planner.perCallInputBudget());
  }

  @Test
  void perModelMaxInputTokensRaisesTheCapPastTheDefault() {
    models.put(MODEL, modelSettings(Optional.of(500_000), Optional.empty(), Optional.empty()));
    when(reviewConfig.maxInputTokens()).thenReturn(300_000);
    when(reviewConfig.tokenSafetyMargin()).thenReturn(1.0);
    when(reviewConfig.outputBufferTokens()).thenReturn(0);
    assertEquals(300_000, planner.perCallInputBudget());
  }

  @Test
  void perModelMaxInputTokensLowersTheEffectiveBudget() {
    models.put(MODEL, modelSettings(Optional.of(32_000), Optional.empty(), Optional.empty()));
    when(reviewConfig.maxInputTokens()).thenReturn(48_000);
    when(reviewConfig.tokenSafetyMargin()).thenReturn(1.0);
    when(reviewConfig.outputBufferTokens()).thenReturn(0);
    assertEquals(32_000, planner.perCallInputBudget());
  }

  @Test
  void perModelBufferAndMarginOverrideTheGlobalValues() {
    models.put(MODEL, modelSettings(Optional.empty(), Optional.of(1_000), Optional.of(0.5)));
    when(reviewConfig.maxInputTokens()).thenReturn(48_000);
    assertEquals(24_000 - 1_000, planner.perCallInputBudget());
  }

  @Test
  void aModelCapNeverReenablesExplicitlyDisabledBudgeting() {
    models.put(MODEL, modelSettings(Optional.of(64_000), Optional.empty(), Optional.empty()));
    when(reviewConfig.maxInputTokens()).thenReturn(0);
    assertEquals(Integer.MAX_VALUE, planner.perCallInputBudget());
  }

  /**
   * A previous-findings block in the shape {@code FollowUpAnalyzer} builds: numbered entries with
   * id, risk, location and title on one line, prose and quoted thread replies indented under it,
   * then the unnumbered "answered in earlier rounds" list.
   */
  private static String previousFindingsBlock(int findings, int descriptionLines) {
    var sb = new StringBuilder();
    for (var i = 1; i <= findings; i++) {
      sb.append(i)
          .append(". [HIGH] src/File")
          .append(i)
          .append(".java:")
          .append(i * 3)
          .append(" — Finding ")
          .append(i)
          .append(" title\n");
      for (var d = 0; d < descriptionLines; d++) {
        sb.append("   The call site never checks the returned handle, so a failure here is")
            .append(" swallowed and the caller proceeds on stale state, line ")
            .append(d)
            .append(".\n");
      }
      sb.append("   Thread replies:\n").append("   - @maintainer: not fixing this right now\n");
    }
    sb.append("\nAnswered in earlier rounds — do NOT raise these again and do NOT")
        .append(" include them in previous_findings_status:\n")
        .append("- src/Old.java:12 — An older answered finding\n")
        .append("   Thread replies:\n")
        .append("   - @maintainer: answered already\n");
    return sb.toString();
  }

  private static AiReviewService.PromptInputs inputsWith(String previousFindings) {
    return new AiReviewService.PromptInputs("d", "ctx", "base", "s", "t", previousFindings, "");
  }

  /** Budget knobs with no safety margin and no output buffer, so the per-call budget is exact. */
  private void budget(int maxInputTokens) {
    models.put(
        MODEL, modelSettings(Optional.of(maxInputTokens), Optional.empty(), Optional.empty()));
    when(reviewConfig.maxInputTokens()).thenReturn(maxInputTokens);
    lenient().when(reviewConfig.tokenSafetyMargin()).thenReturn(1.0);
    lenient().when(reviewConfig.outputBufferTokens()).thenReturn(0);
    lenient().when(reviewConfig.maxAiCalls()).thenReturn(6);
  }

  @Test
  void anAccumulatedPreviousFindingsBlockNoLongerStarvesTheDiffBudget() {
    // #583: the block is shared overhead — every batch call repeats it in full — and it grows every
    // round on a long-lived PR whose head keeps advancing and whose findings therefore never
    // retire. Unbounded, it swallows the whole input budget and every file is omitted by name: the
    // review stops reading code in order to re-read what it already said.
    budget(20_000);
    var files = List.of(file("dir/f1.java", 5, patch(5)), file("dir/f2.java", 5, patch(5)));

    var plan =
        planner.plan(files, inputsWith(PromptTemplateEscaper.fence(previousFindingsBlock(300, 6))));

    assertTrue(plan.omittedFiles().isEmpty(), "the diff budget must survive the previous findings");
    assertEquals(List.of("dir/f1.java", "dir/f2.java"), coveredFilenames(plan));
  }

  @Test
  void anOversizedBlockIsCondensedToIdentityRatherThanTruncated() {
    // Blind truncation would make the follow-up pass forget findings it already reported. What that
    // pass needs is identity and location — the id it reports a status for, the file:line it looks
    // at, the title it matches on — not the prose, so every entry keeps its own line and loses only
    // its continuation lines.
    budget(20_000);
    var inputs = inputsWith(PromptTemplateEscaper.fence(previousFindingsBlock(40, 6)));

    var bounded = planner.boundPreviousFindings(inputs);

    for (var i = 1; i <= 40; i++) {
      assertTrue(
          bounded
              .previousFindings()
              .contains(i + ". [HIGH] src/File" + i + ".java:" + (i * 3) + " — Finding " + i),
          "finding " + i + " lost its identity line");
    }
    assertTrue(
        bounded.previousFindings().contains("- src/Old.java:12 — An older answered finding"),
        "the answered-earlier list keeps its entries too");
    assertFalse(
        bounded.previousFindings().contains("The call site never checks"), "prose must be dropped");
    assertFalse(bounded.previousFindings().contains("@maintainer"), "replies must be dropped");
    assertTrue(
        bounded.previousFindings().contains("condensed to id, location and title"),
        "the elision must be disclosed");
    assertFalse(
        bounded.previousFindings().contains("Answered in earlier rounds"),
        "the section header is a detail line and condenses away with the rest");
    assertTrue(
        bounded.previousFindings().contains("answered in an earlier round"),
        "so the disclosure carries its meaning instead, outside the fence");
    assertFalse(
        bounded.previousFindings().contains("did not fit and were omitted entirely"),
        "nothing was dropped, so nothing may claim it was");
    assertTrue(
        tokenCounter.estimateTokens(bounded.previousFindings()) <= 20_000 / 4,
        "the bounded block must fit its share of the budget");
    assertEquals("d", bounded.diff(), "no other prompt section is touched");
    assertEquals("ctx", bounded.prContext());
    assertEquals("base", bounded.baseComparison());
    assertEquals("s", bounded.projectStack());
    assertEquals("t", bounded.relatedTests());
    assertEquals("", bounded.repoInstructions());
  }

  @Test
  void condensingKeepsTheUntrustedRegionFencedWithTheDisclosureOutsideIt() {
    // The fence is the data/instruction boundary. Dropping the closing line would leave the notice
    // — our own instruction — inside the untrusted region, so both lines survive the cut.
    budget(20_000);
    var fenced = PromptTemplateEscaper.fence(previousFindingsBlock(40, 6));
    var fence = fenced.lines().findFirst().orElseThrow();

    var bounded = planner.boundPreviousFindings(inputsWith(fenced)).previousFindings();

    var lines = bounded.lines().toList();
    assertEquals(fence, lines.get(0), "the opening fence must survive");
    assertEquals(2, lines.stream().filter(fence::equals).count(), "the fence must stay balanced");
    assertTrue(
        bounded.indexOf("condensed to id, location and title") > bounded.lastIndexOf(fence),
        "the disclosure must sit outside the fence");
  }

  @Test
  void entriesThatStillDoNotFitAreDroppedFromTheTailAndDisclosed() {
    // Real forgetting, so it degrades the safe way: the numbered ids previous_findings_status is
    // keyed to outlive the advisory answered-earlier list, and the model is told never to call a
    // finding it cannot see resolved — the approve backstop then holds it open by itself.
    budget(1_200);
    var inputs = inputsWith(previousFindingsBlock(60, 2));

    var bounded = planner.boundPreviousFindings(inputs).previousFindings();

    assertTrue(bounded.contains("1. [HIGH] src/File1.java:3"), "the ids in use must be kept");
    assertFalse(bounded.contains("60. [HIGH] src/File60.java"), "the tail must be dropped");
    assertFalse(
        bounded.contains("- src/Old.java:12"), "the advisory list yields before the numbered ids");
    assertTrue(
        bounded.contains("did not fit and were omitted entirely"), "the drop must be disclosed");
    assertTrue(
        bounded.contains("Never report a finding you cannot see as resolved"),
        "the dangerous inference must be forbidden explicitly");
    assertTrue(tokenCounter.estimateTokens(bounded) <= 300, "the cap must actually hold");
  }

  @Test
  void aBlockTooSmallToHoldItsOwnDisclosureStillCarriesTheDisclosure() {
    // Pathological cap: not one entry fits. The disclosure is the one thing that must not be what
    // gets dropped — a silently empty block is exactly the forgetting this bound exists to prevent.
    // A block that opens with a section header (the answered-earlier list, when no round raised
    // numbered findings) loses the header without it counting as a dropped finding: the count
    // must report findings, not lines.
    budget(4);
    var inputs =
        inputsWith(
            "Answered in earlier rounds — do NOT raise these again:\n"
                + "- src/Old.java:12 — An older answered finding");

    var bounded = planner.boundPreviousFindings(inputs).previousFindings();

    assertFalse(bounded.contains("src/Old.java"), "nothing fits under this cap");
    assertFalse(bounded.contains("Answered in earlier rounds — do NOT"), "nor does the header");
    assertTrue(bounded.contains("1 further previous finding(s) did not fit"));
  }

  @Test
  void aBlockWithinItsShareIsUntouchedAndBoundingIsIdempotent() {
    // An ordinary follow-up round must pay nothing for this, and re-bounding an already bounded
    // block must not stack disclosures — plan() bounds again when it sizes the shared overhead.
    budget(20_000);
    var small = inputsWith(previousFindingsBlock(3, 1));

    assertSame(small, planner.boundPreviousFindings(small));

    var big = inputsWith(PromptTemplateEscaper.fence(previousFindingsBlock(40, 6)));
    var bounded = planner.boundPreviousFindings(big);
    assertNotSame(big, bounded);
    assertSame(bounded, planner.boundPreviousFindings(bounded));
  }

  @Test
  void anUnfencedBlockIsBoundedToo() {
    // The review path fences the block, but the bound is a property of the text, not of its
    // wrapper: an unfenced block condenses the same way, with no fence line invented for it.
    budget(20_000);

    var bounded =
        planner.boundPreviousFindings(inputsWith(previousFindingsBlock(40, 6))).previousFindings();

    assertFalse(
        bounded.contains(PromptTemplateEscaper.fencePrefix()), "no fence may be invented here");
    assertTrue(bounded.contains("40. [HIGH] src/File40.java:120"), "every id must survive");
    assertFalse(bounded.contains("The call site never checks"));
  }

  @Test
  void aBlockThatOnlyLooksFencedIsCondensedAsPlainText() {
    // A leading fence line with no matching closing line is not a fence — that block was already
    // unterminated — so it is condensed as ordinary text rather than having a boundary invented.
    budget(20_000);
    var fenced = PromptTemplateEscaper.fence(previousFindingsBlock(40, 6));
    var fence = fenced.lines().findFirst().orElseThrow();

    var bounded =
        planner.boundPreviousFindings(inputsWith(fenced + "\ntrailing text")).previousFindings();

    assertEquals(
        1,
        bounded.lines().filter(fence::equals).count(),
        "the unmatched line is ordinary text: none is added, and the one buried among the entries"
            + " condenses away like any other detail line");
    assertTrue(bounded.contains("40. [HIGH] src/File40.java:120"));
  }

  @Test
  void anAbsentOrEmptyBlockIsLeftAlone() {
    budget(20_000);
    var absent = inputsWith(null);
    var empty = inputsWith("   ");

    assertSame(absent, planner.boundPreviousFindings(absent));
    assertSame(empty, planner.boundPreviousFindings(empty));
  }

  @Test
  void disabledBudgetingLeavesThePreviousFindingsBlockUncapped() {
    // max-input-tokens=0 is an explicit "no cap at all"; this is not the place to reintroduce one.
    when(reviewConfig.maxInputTokens()).thenReturn(0);
    var inputs = inputsWith(PromptTemplateEscaper.fence(previousFindingsBlock(300, 6)));

    assertSame(inputs, planner.boundPreviousFindings(inputs));
  }

  @Test
  void mixedPrPackingPrefersRealDiffsAfterPureRenamesAreFilteredOut() {
    // Mirrors the orchestrator path: reviewableFiles() drops pure renames before plan().
    var pure = new FileDiff("moved/B.java", "renamed", 0, 0, 0, null, "moved/A.java");
    var real = file("src/App.java", 8, patch(8));
    var reviewable = formatter.reviewableFiles(List.of(pure, real));

    assertEquals(List.of("src/App.java"), reviewable.stream().map(FileDiff::filename).toList());

    when(reviewConfig.maxAiCalls()).thenReturn(3);
    var plan = planner.plan(reviewable, sectionTokens(real) + 10, 2);

    assertEquals(List.of("src/App.java"), coveredFilenames(plan));
    assertTrue(plan.omittedFiles().isEmpty(), "pure rename must not appear as budget omission");
  }

  @Test
  void aPatchlessFileWithNoNameIsCountedAsAGapInsteadOfFailingThePlan() {
    // GitHub returns a null/blank patch for binaries and oversized text diffs, and FileDiff does
    // not validate its filename. A null name reaching the plan's omitted list makes List.copyOf in
    // the BudgetPlan constructor throw, failing the whole review at plan time (#550). Dropping it
    // instead would be the worse failure and a silent one: the list size is what holds APPROVE, so
    // the only omission vanishing would let the bot approve a PR it never read (#473).
    var files = Arrays.asList(new FileDiff(null, "modified", 7, 0, 7, ""));

    var plan = planner.plan(files, 10000, 3);

    assertEquals(List.of("(unnamed file)"), plan.patchlessFiles());
    assertTrue(plan.omittedFiles().isEmpty(), plan.omittedFiles().toString());
    assertTrue(plan.clippedFiles().isEmpty(), plan.clippedFiles().toString());
    assertTrue(plan.truncated(), "an unreviewed file must keep withholding approval");
  }

  @Test
  void anUnnamedFileTyingOnSizeDoesNotBlowUpTheImpactSort() {
    // #473: the name tie-break only runs between files of equal additions+deletions, and
    // Comparator.thenComparing's natural ordering throws on a null key. Two 7-change files, one
    // unnamed, are exactly that tie.
    var files =
        Arrays.asList(
            file("src/App.java", 7, patch(7)), new FileDiff(null, "modified", 7, 0, 7, ""));

    var plan = planner.plan(files, 10_000, 3);

    assertEquals(List.of("src/App.java"), coveredFilenames(plan));
    assertEquals(
        List.of("(unnamed file)"), plan.patchlessFiles(), "the patchless file has no diff");
  }

  @Test
  void anUnnamedFileSortsAfterItsEquallySizedNamedPeers() {
    // Which side of the tie the unnamed file lands on is a choice, not a mechanical detail: packing
    // is impact-descending, so whatever sorts last is what the bin cap omits first. Unnamed last
    // keeps a degenerate entry from displacing a well-formed file — findings on a file the model
    // cannot name have no path to anchor an inline comment to.
    var named = file("src/App.java", 7, patch(7));
    var unnamed = new FileDiff(null, "modified", 7, 0, 7, patch(7));
    var budget = sectionTokens(named) + 10; // room for exactly one of the two

    var plan = planner.plan(Arrays.asList(unnamed, named), budget, 1);

    assertEquals(List.of("src/App.java"), coveredFilenames(plan), "the named file keeps the bin");
    assertEquals(List.of("(unnamed file)"), plan.omittedFiles());
    assertTrue(plan.truncated());
  }

  @Test
  void anUnnamedFileOverflowingEveryBinIsCountedNotDropped() {
    // The bin-packing omission site: a file that fits its own budget but not the remaining bins.
    // Its count is what VerdictBuilder reads for DiffStats.truncated(), so a nameless omission has
    // to occupy a slot — otherwise the single omission disappears and APPROVE is no longer held.
    var big = file("src/Big.java", 40, patch(8));
    var unnamed = new FileDiff(null, "modified", 8, 0, 8, patch(8));
    var budget = sectionTokens(big) + 10; // one file per bin, one bin

    var plan = planner.plan(Arrays.asList(big, unnamed), budget, 1);

    assertEquals(List.of("src/Big.java"), coveredFilenames(plan));
    assertEquals(List.of("(unnamed file)"), plan.omittedFiles());
    assertEquals(1, plan.omittedFiles().size(), "the gap count must stay honest");
    assertTrue(plan.truncated(), "an unreviewed file must keep withholding approval");
  }

  @Test
  void aCondensedPreviousFindingsContextStillLeavesAnUnnamedGapHoldingApproval() {
    // The two accounts this class keeps are separate and must stay separate: #583 elides prompt
    // text (findings the model is re-shown), #473 counts file coverage gaps. Run both at once — a
    // previous-findings block far past its share, and a file GitHub named nothing — and the
    // condensation must not absorb the coverage gap, nor the placeholder leak into the prompt.
    budget(20_000);
    var named = file("src/App.java", 5, patch(5));
    var unnamed = new FileDiff(null, "modified", 7, 0, 7, "");
    var inputs = inputsWith(PromptTemplateEscaper.fence(previousFindingsBlock(300, 6)));

    var plan = planner.plan(Arrays.asList(named, unnamed), inputs);

    assertEquals(List.of("src/App.java"), coveredFilenames(plan), "the named file is reviewed");
    assertEquals(
        List.of("(unnamed file)"), plan.patchlessFiles(), "the nameless gap is still counted");
    assertTrue(plan.truncated(), "an unreviewed file must keep withholding approval");
    assertFalse(
        planner
            .boundPreviousFindings(inputs)
            .previousFindings()
            .contains(DiffBudgetPlanner.UNNAMED_FILE),
        "the coverage placeholder has no business in the previous-findings prompt text");
  }
}
