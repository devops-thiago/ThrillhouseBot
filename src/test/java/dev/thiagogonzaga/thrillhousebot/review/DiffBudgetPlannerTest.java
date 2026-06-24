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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DiffBudgetPlanner}: packing, priority, clipping, omitted-by-name (#53). */
class DiffBudgetPlannerTest {

  private final ReviewDiffFormatter formatter = new ReviewDiffFormatter(List.of("**/*.lock"), 0);
  private final TokenCounter tokenCounter = new TokenCounter();
  private final DiffBudgetPlanner planner = new DiffBudgetPlanner(formatter, tokenCounter);

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
    // Four equal-size files; a budget of two files forces two batches of two.
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
    // Equal-size sections (same patch + equal-length names), different impact so priority is clear.
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
    // Highest-impact kept; the two lowest-impact reported by name (never silently dropped).
    assertEquals(List.of("dir/fc.java", "dir/fd.java"), plan.omittedFiles());
    assertEquals(List.of("dir/fa.java", "dir/fb.java"), coveredFilenames(plan));
  }

  @Test
  void oversizedSingleFileIsClippedToFitOneBatch() {
    var big = file("dir/huge.java", 400, patch(400));
    var budget = 80;
    var plan = planner.plan(List.of(big), budget, 3);

    assertEquals(1, plan.batches().size());
    var batch = plan.batches().get(0);
    assertEquals(1, batch.files().size());
    assertTrue(batch.estimatedTokens() <= budget, "clipped batch over budget");
    assertTrue(batch.text().contains("truncated"), "oversized section should be clipped");
    assertFalse(plan.truncated(), "a clipped file is covered, not omitted");
  }

  @Test
  void ignoredFilesAreNeverPlannedOrOmitted() {
    var files = List.of(file("dir/app.java", 5, patch(5)), file("deps/yarn.lock", 999, patch(50)));
    var plan = planner.plan(files, 100_000, 3);
    var covered = coveredFilenames(plan);
    assertEquals(List.of("dir/app.java"), covered);
    assertFalse(plan.omittedFiles().contains("deps/yarn.lock"));
  }
}
