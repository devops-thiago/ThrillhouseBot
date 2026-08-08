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
import static org.junit.jupiter.params.provider.Arguments.arguments;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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

  /**
   * A patch whose code is indented, so a quote that drops the indentation is distinguishable from
   * one that reproduces it. Line 2 carries trailing whitespace (kept with {@code \s}, which text
   * blocks would otherwise strip) so the insignificant-trailing-space case is exercised too.
   */
  private static final String INDENTED_PATCH =
      """
      @@ -0,0 +1,2 @@
      +    var in = Files.newInputStream(path);
      +    int total = 0;  \s""";

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

  private static FileDiff indentedFoo() {
    return new FileDiff("src/Foo.java", "modified", 2, 0, 2, INDENTED_PATCH);
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
  void reportsTheCapWhenItSwallowedEveryImprovement() {
    // Nothing lands at all: the summary must say why rather than claim there was nothing to do.
    when(reviewConfig.maxReviewComments()).thenReturn(0);
    prWithFiles(foo());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,"title":"Close the stream",
        "category":"error-handling","rationale":"Leaks.",
        "suggestion_old":"var in = Files.newInputStream(path);",
        "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
        """);

    service.handle(task(), AUTH);

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    var summary = postedSummary();
    assertTrue(summary.contains("posted no improvements"), summary);
    assertTrue(summary.contains("per-run comment cap was reached"), summary);
    assertTrue(summary.contains("**1**"), summary);
    assertNotEquals(PrImprovementService.NOTHING_TO_IMPROVE, summary);
  }

  @Test
  void anchorsAQuoteThatReproducesTheLeadingIndentation() {
    prWithFiles(indentedFoo());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,"title":"Close the stream",
        "category":"error-handling","rationale":"Leaks a handle.",
        "suggestion_old":"    var in = Files.newInputStream(path);",
        "suggestion_new":"    try (var in = Files.newInputStream(path)) {"}]}
        """);

    service.handle(task(), AUTH);

    var inline = capturedInlineComment();
    assertEquals(1, inline.line());
    assertTrue(inline.body().contains("```suggestion"), inline.body());
    assertTrue(
        inline.body().contains("    try (var in = Files.newInputStream(path)) {"), inline.body());
  }

  @Test
  void doesNotRewriteALineWhoseQuoteDropsTheLeadingIndentation() {
    // Committing a suggestion replaces the line with suggestion_new verbatim. A model that
    // re-indented the code it quoted has re-indented its replacement too, so applying it would
    // silently reflow the line — refuse to anchor and hand the author a copy-paste block instead.
    prWithFiles(indentedFoo());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,"title":"Close the stream",
        "category":"error-handling","rationale":"Leaks a handle.",
        "suggestion_old":"var in = Files.newInputStream(path);",
        "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
        """);

    service.handle(task(), AUTH);

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    var summary = postedSummary();
    assertTrue(summary.contains("could not be pinned to the diff"), summary);
    assertTrue(summary.contains("try (var in = Files.newInputStream(path)) {"), summary);
  }

  @Test
  void anchorsDespiteInsignificantTrailingWhitespaceInTheDiff() {
    // Trailing whitespace is invisible and formatters strip it, so it must not block anchoring.
    prWithFiles(indentedFoo());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":2,"title":"Rename the accumulator",
        "category":"readability","rationale":"total is ambiguous.",
        "suggestion_old":"    int total = 0;",
        "suggestion_new":"    int valueTotal = 0;"}]}
        """);

    service.handle(task(), AUTH);

    var inline = capturedInlineComment();
    assertEquals(2, inline.line());
    assertTrue(inline.body().contains("```suggestion"), inline.body());
  }

  @Test
  void fallsBackToCopyPasteWhenThePrHasNoHeadSha() {
    // An inline comment needs a commit to anchor to; without one the improvements must still be
    // delivered rather than silently dropped.
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("Title", "Body", new Ref("  "), new Ref("basesha")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(foo()));
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,"title":"Close the stream",
        "category":"error-handling","rationale":"Leaks a handle.",
        "suggestion_old":"var in = Files.newInputStream(path);",
        "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
        """);

    service.handle(task(), AUTH);

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    var summary = postedSummary();
    assertTrue(summary.contains("could not be pinned to the diff"), summary);
    assertTrue(summary.contains("try (var in = Files.newInputStream(path)) {"), summary);
  }

  @Test
  void fallsBackToCopyPasteWhenThePrDetailsCarryNoHeadRef() {
    // A PR payload can arrive with a null head ref; that must degrade, not NPE.
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("Title", "Body", null, new Ref("basesha")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(foo()));
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,"title":"Close the stream",
        "category":"error-handling","rationale":"Leaks a handle.",
        "suggestion_old":"var in = Files.newInputStream(path);",
        "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
        """);

    service.handle(task(), AUTH);

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("could not be pinned to the diff"), postedSummary());
  }

  @ParameterizedTest(name = "a {0} rendered diff posts the no-changes notice")
  @NullSource
  @ValueSource(strings = {"   \n ", "(no changes detected)"})
  void postsTheNoChangesNoticeWhenThereIsNothingToRender(String renderedDiff) {
    var emptyFormatter = mock(ReviewDiffFormatter.class);
    when(emptyFormatter.reviewableFiles(anyList())).thenReturn(List.of(foo()));
    when(emptyFormatter.buildDiffStringWithStats(anyList(), anyList()))
        .thenReturn(new ReviewDiffFormatter.FormattedDiff(renderedDiff, 0));
    prWithFiles(foo());

    serviceWith(emptyFormatter).handle(task(), AUTH);

    assertEquals(PrImprovementService.NO_CHANGES, postedSummary());
    verifyNoInteractions(improveAssistant);
  }

  @Test
  void toleratesAFormatterThatYieldsNoReviewableFileList() {
    // The reviewable list only drives line anchoring; a null must degrade to copy-paste, not NPE.
    var nullListFormatter = mock(ReviewDiffFormatter.class);
    when(nullListFormatter.reviewableFiles(anyList())).thenReturn(null);
    when(nullListFormatter.buildDiffStringWithStats(anyList(), any()))
        .thenReturn(new ReviewDiffFormatter.FormattedDiff("## Overview\ndiff", 0));
    when(nullListFormatter.patchesByReviewableFiles(anyList())).thenReturn(Map.of());
    prWithFiles(foo());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,"title":"Close the stream",
        "category":"error-handling","rationale":"Leaks a handle.",
        "suggestion_old":"var in = Files.newInputStream(path);",
        "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
        """);

    serviceWith(nullListFormatter).handle(task(), AUTH);

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("could not be pinned to the diff"), postedSummary());
  }

  /**
   * Every way a postable improvement can fail to anchor onto the diff. Each must refuse the
   * committable suggestion — a wrong-line or wrong-content commit is worse than no commit — and
   * hand the improvement to the author as a copy-paste block instead.
   */
  static Stream<Arguments> unanchorable() {
    return Stream.of(
        arguments(
            "a stale or invented quote for a line that is in the diff",
            """
            {"improvements":[{"file":"src/Foo.java","line":2,
            "title":"Rename the accumulator","category":"readability",
            "rationale":"total is ambiguous.",
            "suggestion_old":"int sum = 0;",
            "suggestion_new":"int valueTotal = 0;"}]}
            """),
        arguments(
            "a multi-line block whose range cannot be resolved verbatim",
            """
            {"improvements":[{"file":"src/Foo.java","line":3,"title":"Use an enhanced for loop",
            "category":"readability","rationale":"Index bookkeeping adds nothing.",
            "suggestion_old":"for (int i = 0; i < items.size(); i++) {\\n  total += items.get(i).cost();",
            "suggestion_new":"for (var item : items) {\\n  total += item.cost();"}]}
            """),
        arguments(
            "a reported line that only snaps to a neighbouring line",
            """
            {"improvements":[{"file":"src/Foo.java","line":99,"title":"Rename the accumulator",
            "category":"readability","rationale":"Ambiguous name.",
            "suggestion_old":"int total = 0;","suggestion_new":"int valueTotal = 0;"}]}
            """));
  }

  @ParameterizedTest(name = "{0} falls back to a copy-paste block")
  @MethodSource("unanchorable")
  void fallsBackToCopyPasteWhenTheImprovementCannotBeAnchoredOntoTheDiff(
      String label, String response) {
    prWithFiles(foo());
    assistantReturns(response);

    service.handle(task(), AUTH);

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("could not be pinned to the diff"), label);
  }

  @Test
  void swallowsAFailureWhilePostingTheSummarySoTheWebhookPathNeverThrows() {
    prWithFiles(foo());
    assistantReturns("{\"improvements\":[]}");
    doThrow(new RuntimeException("503 Service Unavailable"))
        .when(commentClient)
        .createComment(any(), any(), any(), any(), anyInt(), any());

    assertDoesNotThrow(() -> service.handle(task(), AUTH));
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
