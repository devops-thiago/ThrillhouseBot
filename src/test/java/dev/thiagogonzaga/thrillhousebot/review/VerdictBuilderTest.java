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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        .when(followUpAnalyzer.supersedeVanished(any(), any(), any()))
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
        List.of(),
        "",
        "",
        List.of(new FileDiff("a.java", "modified", 1, 0, 1, "")),
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
    var ctx = contextWithLineCapOmissions(0);
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of("huge.java"), true);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(1, result.omittedFiles());
    assertTrue(result.truncated());
  }

  @Test
  void uncoveredFilesAreNamedOnTheDisclosureSurfaces() {
    var ctx = contextWithLineCapOmissions(0);
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(), List.of("big.java"), List.of("huge.java"), true);

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
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of("a.java"), List.of(), true);
    var rowsCaptor = ArgumentCaptor.forClass(List.class);

    builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    verify(summaryGenerator)
        .generate(anyInt(), anyInt(), anyInt(), rowsCaptor.capture(), any(), any());
    assertTrue(rowsCaptor.getValue().isEmpty(), rowsCaptor.getValue().toString());
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
        List.of(),
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
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of(), true);

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
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of(), true);
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
            List.of(),
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

  @Test
  void disabledBudgetingDisclosesTheLegacyLineCapCount() {
    var ctx = contextWithLineCapOmissions(2);
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of(), false);

    var result = builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertEquals(2, result.omittedFiles());
    assertTrue(result.truncated());
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
            List.of(),
            "",
            "",
            List.of(new FileDiff("a.java", "modified", 1, 0, 1, "")),
            () -> {
              touched[0] = true;
              return new DiffLineResolver(Map.of());
            },
            null);
    var plan = new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of(), false);

    builder.build(ctx, CLEAN_RESPONSE, CI_CLEAR, plan);

    assertFalse(touched[0]);
  }

  @Test
  void configConstructorHonorsStrictBlockingMode() {
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(review);
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
            new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of(), false));

    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
  }

  @Test
  void configConstructorFallsBackToBalancedOnUnrecognizedMode() {
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(review);
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
            new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of(), false));

    assertEquals(ReviewState.COMMENT, result.reviewState());
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
            new DiffBudgetPlanner.BudgetPlan(List.of(), List.of(), List.of(), false));

    assertEquals(ReviewState.COMMENT, result.reviewState());
  }
}
