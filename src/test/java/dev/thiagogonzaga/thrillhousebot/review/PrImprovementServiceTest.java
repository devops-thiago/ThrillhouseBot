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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.Ref;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ImprovementParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrImproveAssistant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PrImprovementServiceTest {

  private static final String AUTH = "token gh-abc";
  private static final String HEAD_SHA = "headsha1234567";

  private static final String PATCH =
      """
      @@ -0,0 +1,4 @@
      +var in = Files.newInputStream(path);
      +int total = 0;
      +for (int i = 0; i < items.size(); i++) {
      +  total += items.get(i).value();""";

  @Mock private GitHubPullRequestClient prClient;
  @Mock private GitHubReviewClient reviewClient;
  @Mock private GitHubCommentClient commentClient;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private PrImproveAssistant improveAssistant;
  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  private final ReviewDiffFormatter diffFormatter = new ReviewDiffFormatter(List.of(), 5000);
  private final SuggestionFormatter suggestionFormatter = new SuggestionFormatter();
  private final ImprovementParser parser = new ImprovementParser(new ObjectMapper());

  private PrImprovementService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(50);
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
    service = serviceWith(diffFormatter);
  }

  private PrImprovementService serviceWith(ReviewDiffFormatter formatter) {
    return new PrImprovementService(
        prClient,
        reviewClient,
        commentClient,
        formatter,
        suggestionFormatter,
        instructionsResolver,
        improveAssistant,
        parser,
        config);
  }

  private PrImprovementService.ImproveTask task() {
    return new PrImprovementService.ImproveTask("owner", "repo", 7, "main", 12345L);
  }

  private void prWithFiles(FileDiff... files) {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("Title", "Body", new Ref(HEAD_SHA), new Ref("basesha")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(files));
  }

  private static FileDiff foo() {
    return new FileDiff("src/Foo.java", "modified", 4, 0, 4, PATCH);
  }

  private void assistantReturns(String json) {
    when(improveAssistant.improve(any(), any(), any(), any())).thenReturn(json);
  }

  private String postedSummary() {
    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(any(), any(), eq("owner"), eq("repo"), eq(7), body.capture());
    return body.getValue().body();
  }

  private GitHubReviewClient.CreatePullRequestCommentRequest capturedInlineComment() {
    var req = ArgumentCaptor.forClass(GitHubReviewClient.CreatePullRequestCommentRequest.class);
    verify(reviewClient)
        .createPullRequestComment(any(), any(), eq("owner"), eq("repo"), eq(7), req.capture());
    return req.getValue();
  }

  @Test
  void postsCommittableSuggestionForAnImprovement() {
    prWithFiles(foo());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,
        "title":"Close the stream with try-with-resources","category":"error-handling",
        "rationale":"The stream leaks when read() throws.",
        "suggestion_old":"var in = Files.newInputStream(path);",
        "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
        """);

    service.handle(task(), AUTH);

    var inline = capturedInlineComment();
    assertEquals(HEAD_SHA, inline.commitId());
    assertEquals("src/Foo.java", inline.path());
    assertEquals(1, inline.line());
    assertEquals("RIGHT", inline.side());
    assertNull(inline.startLine());
    assertNull(inline.startSide());
    assertTrue(inline.body().contains("```suggestion"), inline.body());
    assertTrue(
        inline.body().contains("try (var in = Files.newInputStream(path)) {"), inline.body());
    assertTrue(inline.body().contains("Close the stream with try-with-resources"), inline.body());
    assertTrue(inline.body().contains("error-handling"), inline.body());
    var summary = postedSummary();
    assertTrue(summary.contains("**1** committable improvement(s)"), summary);
    assertFalse(summary.contains("partial coverage"), summary);
  }

  @Test
  void anchorsAMultiLineImprovementAcrossItsWholeRange() {
    prWithFiles(foo());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":3,
        "title":"Use an enhanced for loop","category":"readability",
        "rationale":"Index bookkeeping adds nothing here.",
        "suggestion_old":"for (int i = 0; i < items.size(); i++) {\\n  total += items.get(i).value();",
        "suggestion_new":"for (var item : items) {\\n  total += item.value();"}]}
        """);

    service.handle(task(), AUTH);

    var inline = capturedInlineComment();
    assertEquals(3, inline.startLine());
    assertEquals(4, inline.line());
    assertEquals("RIGHT", inline.startSide());
    assertTrue(inline.body().contains("```suggestion"), inline.body());
  }

  @Test
  void fallsBackToACopyPasteBlockWhenTheImprovementCannotBeAnchored() {
    prWithFiles(foo());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Other.java","line":80,
        "title":"Extract the retry loop","category":"maintainability",
        "rationale":"The loop is duplicated in three call sites.",
        "suggestion_old":"while (true) { retry(); }",
        "suggestion_new":"retryPolicy.run(this::call);"}]}
        """);

    service.handle(task(), AUTH);

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    var summary = postedSummary();
    assertTrue(summary.contains("could not be pinned to the diff"), summary);
    assertTrue(summary.contains("Extract the retry loop"), summary);
    assertTrue(summary.contains("retryPolicy.run(this::call);"), summary);
    assertTrue(summary.contains("src/Other.java:80"), summary);
  }

  @Test
  void doesNotRewriteALineWhoseQuotedCodeDoesNotMatchTheDiff() {
    // A stale or invented quote would rewrite the wrong line on commit, so it degrades to a
    // copy-paste block instead of a committable suggestion.
    prWithFiles(foo());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":2,
        "title":"Rename the accumulator","category":"readability",
        "rationale":"total is ambiguous.",
        "suggestion_old":"int sum = 0;",
        "suggestion_new":"int valueTotal = 0;"}]}
        """);

    service.handle(task(), AUTH);

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("could not be pinned to the diff"), postedSummary());
  }

  @Test
  void appendsPartialCoverageDisclosureWhenFilesWereOmitted() {
    var truncatingFormatter = mock(ReviewDiffFormatter.class);
    var foo = foo();
    when(truncatingFormatter.reviewableFiles(anyList())).thenReturn(List.of(foo));
    when(truncatingFormatter.buildDiffStringWithStats(anyList(), anyList()))
        .thenReturn(new ReviewDiffFormatter.FormattedDiff("## Overview\n(truncated)", 48));
    when(truncatingFormatter.patchesByReviewableFiles(anyList()))
        .thenReturn(Map.of("src/Foo.java", PATCH));
    prWithFiles(foo);
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,
        "title":"Close the stream","category":"error-handling","rationale":"Leaks a handle.",
        "suggestion_old":"var in = Files.newInputStream(path);",
        "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
        """);

    serviceWith(truncatingFormatter).handle(task(), AUTH);

    var summary = postedSummary();
    assertTrue(summary.contains("48 file(s) were omitted"), summary);
    assertTrue(summary.contains("partial coverage"), summary);
    assertFalse(summary.contains("findings and verdict"), summary);
  }

  @Test
  void postsTheNothingToImproveNoticeWhenTheModelFoundNothing() {
    prWithFiles(foo());
    assistantReturns("{\"improvements\":[]}");

    service.handle(task(), AUTH);

    assertEquals(PrImprovementService.NOTHING_TO_IMPROVE, postedSummary());
    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void postsTheNoChangesNoticeWhenThereIsNoDiff() {
    prWithFiles();

    service.handle(task(), AUTH);

    assertEquals(PrImprovementService.NO_CHANGES, postedSummary());
    verifyNoInteractions(improveAssistant);
  }

  @Test
  void postsTheFailureNoticeWhenTheModelAnswerCannotBeParsed() {
    prWithFiles(foo());
    assistantReturns("I could not do that.");

    service.handle(task(), AUTH);

    assertEquals(PrImprovementService.GENERATION_FAILED, postedSummary());
  }

  @Test
  void postsTheFailureNoticeWhenTheAssistantThrows() {
    prWithFiles(foo());
    when(improveAssistant.improve(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("provider down"));

    service.handle(task(), AUTH);

    assertEquals(PrImprovementService.GENERATION_FAILED, postedSummary());
  }

  @Test
  void capsThePerRunCommentCount() {
    when(reviewConfig.maxReviewComments()).thenReturn(1);
    prWithFiles(foo());
    assistantReturns(
        """
        {"improvements":[
          {"file":"src/Foo.java","line":1,"title":"Close the stream","category":"error-handling",
           "rationale":"Leaks.","suggestion_old":"var in = Files.newInputStream(path);",
           "suggestion_new":"try (var in = Files.newInputStream(path)) {"},
          {"file":"src/Foo.java","line":2,"title":"Rename total","category":"readability",
           "rationale":"Ambiguous.","suggestion_old":"int total = 0;",
           "suggestion_new":"int valueTotal = 0;"}]}
        """);

    service.handle(task(), AUTH);

    verify(reviewClient, times(1))
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("per-run comment cap"), postedSummary());
  }

  @Test
  void skipsImprovementsMissingTheDataNeededToRenderThem() {
    prWithFiles(foo());
    assistantReturns(
        """
        {"improvements":[{"file":"","line":0,"title":"Vague idea","category":"maintainability",
        "rationale":"Something could be better.","suggestion_old":"","suggestion_new":""}]}
        """);

    service.handle(task(), AUTH);

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertEquals(PrImprovementService.NOTHING_TO_IMPROVE, postedSummary());
  }

  @Test
  void aRejectedInlineCommentDegradesToACopyPasteBlock() {
    prWithFiles(foo());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,
        "title":"Close the stream","category":"error-handling","rationale":"Leaks a handle.",
        "suggestion_old":"var in = Files.newInputStream(path);",
        "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
        """);
    doThrow(new RuntimeException("422 Unprocessable Entity"))
        .when(reviewClient)
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());

    service.handle(task(), AUTH);

    assertTrue(postedSummary().contains("could not be pinned to the diff"), postedSummary());
  }
}
