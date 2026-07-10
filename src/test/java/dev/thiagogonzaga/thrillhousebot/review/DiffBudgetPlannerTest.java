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
}
