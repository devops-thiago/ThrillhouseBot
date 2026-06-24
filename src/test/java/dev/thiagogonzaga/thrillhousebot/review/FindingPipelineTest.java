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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link FindingPipeline}'s multi-call (map-reduce) path (#53). */
class FindingPipelineTest {

  @Mock private AiReviewService aiReviewService;
  @Mock private FindingQuoteValidator quoteValidator;
  @Mock private FindingDeduplicator deduplicator;
  @Mock private FindingVerificationService findingVerificationService;
  @Mock private FollowUpAnalyzer followUpAnalyzer;

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
            BotIdentity.from(List.of("thrillhousebot[bot]")));
    // The post-AI chain is exercised elsewhere; here it passes the response through unchanged so
    // the
    // multi-call orchestration (batch fan-out, aggregation, summary merge) is what's asserted.
    when(quoteValidator.validate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(deduplicator.dedupe(any())).thenAnswer(inv -> inv.getArgument(0));
    when(findingVerificationService.verify(any(), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    when(followUpAnalyzer.dropRepliedDuplicates(any(), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private static ReviewResponse.Finding finding(String file, String title) {
    return new ReviewResponse.Finding(
        "medium", "high", file, 1, title, "desc", "old line", "new line");
  }

  private static DiffBudgetPlanner.DiffBatch batch(String name) {
    var file = new FileDiff(name, "modified", 3, 0, 3, "@@ -1 +1 @@\n+x\n");
    return new DiffBudgetPlanner.DiffBatch("### " + name + "\n", List.of(file), 10);
  }

  private static ReviewContextLoader.ReviewContext multiBatchContext() {
    return new ReviewContextLoader.ReviewContext(
        List.of(),
        "",
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
        null,
        List.of(batch("a.java"), batch("b.java")),
        List.of());
  }

  @Test
  void multiCallReviewsEachBatchAggregatesAndSummarizes() {
    var session = ReviewSession.create("owner/repo", 1, "Big PR", "sha");
    var ctx = multiBatchContext();
    var template = new AiReviewService.PromptInputs("d", "ctx", "base", "stack", "tests", "", "");

    when(aiReviewService.reviewBatch(eq(session), any(), anyInt(), anyInt()))
        .thenReturn(new ReviewResponse(List.of(finding("a.java", "A")), List.of(), null))
        .thenReturn(new ReviewResponse(List.of(finding("b.java", "B")), List.of(), null));

    var summary = new ReviewResponse.Summary(2, 0, 0, 2, 0, "looks ok", "does things", List.of());
    var previous = List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"));
    when(aiReviewService.summarize(eq(session), any()))
        .thenReturn(new ReviewResponse(List.of(), previous, summary));

    var result = pipeline.run(session, template, ctx, new DiffLineResolver(Map.of()));

    // One blocking review call per batch, with the right index/count.
    verify(aiReviewService).reviewBatch(eq(session), any(), eq(1), eq(2));
    verify(aiReviewService).reviewBatch(eq(session), any(), eq(2), eq(2));
    // Exactly one summary call rolls up the union.
    verify(aiReviewService).summarize(eq(session), any());

    // Findings are the union of both batches; the PR-level summary + previous status come from the
    // summary call (not from any single batch).
    assertEquals(2, result.findings().size());
    assertSame(summary, result.summary());
    assertEquals(previous, result.previousFindingsStatus());
  }
}
