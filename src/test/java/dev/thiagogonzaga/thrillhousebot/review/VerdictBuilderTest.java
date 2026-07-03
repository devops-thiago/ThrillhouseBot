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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VerdictBuilder#build}'s truncation accounting (#53): the disclosed omitted
 * count must reflect what was actually sent — plan omissions when budgeting is on, the legacy
 * line-cap count when it is off — never the sum of both.
 */
class VerdictBuilderTest {

  private final FollowUpAnalyzer followUpAnalyzer = mock(FollowUpAnalyzer.class);

  private final VerdictBuilder builder =
      new VerdictBuilder(
          mock(PrSummaryGenerator.class),
          followUpAnalyzer,
          BotIdentity.from(List.of("thrillhousebot[bot]")));

  {
    lenient().when(followUpAnalyzer.unresolvedFindings(any(), any())).thenReturn(List.of());
  }

  /** A context whose legacy diff render dropped {@code lineCapOmitted} files. */
  private static ReviewContextLoader.ReviewContext contextWithLineCapOmissions(int lineCapOmitted) {
    return new ReviewContextLoader.ReviewContext(
        List.of(),
        "diff",
        "",
        lineCapOmitted,
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
        List.of(new FileDiff("a.java", "modified", 1, 0, 1, "")),
        new DiffLineResolver(Map.of()),
        null);
  }

  private static final ReviewResponse CLEAN_RESPONSE =
      new ReviewResponse(List.of(), List.of(), null);

  private static final CiStatusEvaluator.CiEvaluation CI_CLEAR =
      new CiStatusEvaluator.CiEvaluation(List.of(), false);

  @Test
  void budgetedReviewDisclosesOnlyThePlanOmissions() {
    // A budgeted review never sends the legacy diff string, so its line-cap count must not leak
    // into the disclosed omissions and hold approval on a fully covered PR.
    var ctx = contextWithLineCapOmissions(3);
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of("big.java"), List.of(), true);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(1, result.omittedFiles());
    assertTrue(result.truncated());
  }

  @Test
  void budgetedReviewWithFullCoverageIsNotTruncated() {
    var ctx = contextWithLineCapOmissions(3);
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of(), true);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(0, result.omittedFiles());
    assertFalse(result.truncated());
    assertEquals(ReviewState.APPROVE, result.reviewState());
  }

  @Test
  void clippedOnlyReviewIsDisclosedAsPartialAndHoldsApproval() {
    // A hunk-clipped file's unseen content was withheld just like an omitted file's whole diff:
    // a clipped-only review must not silently approve.
    var ctx = contextWithLineCapOmissions(0);
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of("huge.java"), true);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(1, result.omittedFiles());
    assertTrue(result.truncated());
  }

  @Test
  void disabledBudgetingDisclosesTheLegacyLineCapCount() {
    var ctx = contextWithLineCapOmissions(2);
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of(), false);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(2, result.omittedFiles());
    assertTrue(result.truncated());
  }
}
