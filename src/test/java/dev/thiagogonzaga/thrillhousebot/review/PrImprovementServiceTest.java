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
import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.Ref;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettings;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ImprovementParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrImproveAssistant;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrImproveAssistantPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrSuggestionPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
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
  @Mock private RepoSettingsResolver repoSettingsResolver;
  @Mock private ActiveModelSettings activeModel;
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
    lenient().when(reviewConfig.maxAiCalls()).thenReturn(6);
    // Default: budgeting on with ample room, so existing single-batch expectations hold.
    lenient().when(activeModel.maxInputTokens()).thenReturn(1_000_000);
    lenient().when(activeModel.tokenSafetyMargin()).thenReturn(1.0);
    lenient().when(activeModel.outputBufferTokens()).thenReturn(0);
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
    lenient()
        .when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(RepoSettings.EMPTY);
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
        repoSettingsResolver,
        new DiffBudgetPlanner(formatter, new TokenCounter(), config, activeModel),
        activeModel,
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
  void doesNotDiscloseTruncationForTheLineCapWhenTheBudgetCoveredEverything() {
    // The line cap no longer decides coverage, so its omitted count must no longer drive the
    // disclosure. A formatter reporting 48 line-omitted files, against a plan that batched every
    // file within budget, is full coverage — claiming otherwise would be a false partial warning.
    var lineCapReports48 = mock(ReviewDiffFormatter.class);
    var foo = foo();
    when(lineCapReports48.reviewableFiles(anyList())).thenReturn(List.of(foo));
    when(lineCapReports48.buildDiffStringWithStats(anyList(), anyList()))
        .thenReturn(new ReviewDiffFormatter.FormattedDiff("## Overview\n(truncated)", 48));
    when(lineCapReports48.patchesByReviewableFiles(anyList()))
        .thenReturn(Map.of("src/Foo.java", PATCH));
    when(lineCapReports48.formatFileSection(any(), any())).thenReturn(PATCH);
    prWithFiles(foo);
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,
        "title":"Close the stream","category":"error-handling","rationale":"Leaks a handle.",
        "suggestion_old":"var in = Files.newInputStream(path);",
        "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
        """);

    serviceWith(lineCapReports48).handle(task(), AUTH);

    var summary = postedSummary();
    assertFalse(summary.contains("48 file(s) were omitted"), summary);
    assertFalse(summary.contains("partial coverage"), summary);
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

    // With no reviewable files there is nothing to plan batches over, so the run reports that
    // rather than calling the model or NPE-ing on the null list.
    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertEquals(PrImprovementService.NO_CHANGES, postedSummary());
    verifyNoInteractions(improveAssistant);
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

  // ---------------------------------------------------------------------------------------------
  // Token-budgeted batching (#53 parity): /improve plans batches over the reviewable FILE LIST,
  // so a diff longer than max-diff-lines no longer costs whole files their coverage.
  // ---------------------------------------------------------------------------------------------

  /** A second file, so the line cap has something to drop and the planner something to batch. */
  private static final String OTHER_PATCH =
      """
      @@ -0,0 +1,3 @@
      +int retries = 0;
      +while (retries < 3) { call(); }
      +log.info("done");""";

  private static FileDiff otherFile() {
    return new FileDiff("src/Other.java", "modified", 3, 0, 3, OTHER_PATCH);
  }

  /**
   * A per-call input budget of exactly the shared prompt overhead plus {@code diffTokens}. Derived
   * from the real prompts rather than hardcoded, so editing a prompt cannot silently turn these
   * tests into no-ops by making every file overflow.
   */
  private static int budgetFor(int diffTokens) {
    var overhead =
        new TokenCounter()
            .estimateTokens(
                PrImproveAssistantPrompts.SYSTEM
                    + PrSuggestionPrompts.USER
                    + PromptTemplateEscaper.fence(" ")
                    + "Title"
                    + "Body"
                    + "");
    return overhead + diffTokens;
  }

  /** Budgeting on, with {@code diffTokens} of room per call for diff text. */
  private void budgetWithDiffRoom(int diffTokens) {
    when(activeModel.maxInputTokens()).thenReturn(budgetFor(diffTokens));
    when(activeModel.tokenSafetyMargin()).thenReturn(1.0);
    when(activeModel.outputBufferTokens()).thenReturn(0);
  }

  /** The diff text each assistant call actually received, in call order. */
  private List<String> diffsSentToAssistant() {
    var diff = ArgumentCaptor.forClass(String.class);
    verify(improveAssistant, atLeastOnce()).improve(diff.capture(), any(), any(), any());
    return diff.getAllValues();
  }

  @Test
  void coversFilesThatTheLineCapWouldHaveDroppedEntirely() {
    // The whole point of the command. With a line cap this small the rendered diff string keeps
    // only the first file, so the pre-batching implementation never showed the model src/Other.java
    // at all. Batching sizes by tokens over the file list instead, so both files are analyzed.
    var lineCapped = new ReviewDiffFormatter(List.of(), 4);
    budgetWithDiffRoom(4000);
    prWithFiles(foo(), otherFile());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Other.java","line":2,"title":"Bound the retry loop",
        "category":"error-handling","rationale":"An unbounded retry can spin forever.",
        "suggestion_old":"while (retries < 3) { call(); }",
        "suggestion_new":"while (retries++ < 3) { call(); }"}]}
        """);

    serviceWith(lineCapped).handle(task(), AUTH);

    // The dropped file reached the model...
    var sent = String.join("\n", diffsSentToAssistant());
    assertTrue(sent.contains("src/Other.java"), sent);
    assertTrue(sent.contains("while (retries < 3) { call(); }"), sent);
    // ...and produced a real committable suggestion anchored to it.
    var inline = capturedInlineComment();
    assertEquals("src/Other.java", inline.path());
    assertEquals(2, inline.line());
    assertTrue(inline.body().contains("```suggestion"), inline.body());
  }

  @Test
  void splitsAnOversizedChangeSetAcrossBatchesAndDedupesTheResults() {
    // Room for roughly one file per call, so the two files land in separate batches. Both batch
    // responses name the same line; it must be posted once, not twice.
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Foo.java","line":1,"title":"Close the stream",
        "category":"error-handling","rationale":"Leaks a handle.",
        "suggestion_old":"var in = Files.newInputStream(path);",
        "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
        """);

    service.handle(task(), AUTH);

    assertTrue(diffsSentToAssistant().size() > 1, "expected more than one batch call");
    verify(reviewClient, times(1))
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void neverSpendsMoreModelCallsThanMaxAiCalls() {
    when(reviewConfig.maxAiCalls()).thenReturn(1);
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    assistantReturns("{\"improvements\":[]}");

    service.handle(task(), AUTH);

    verify(improveAssistant, times(1)).improve(any(), any(), any(), any());
  }

  @Test
  void namesTheFilesLeftUncoveredWhenTheBatchBudgetRunsOut() {
    // With batching, max-ai-calls — not max-diff-lines — is what bounds coverage on a huge PR.
    // Covering only the first N batches and saying nothing would be the same class of bug as the
    // line cap this replaced, just moved, so the files that never got a batch must be named.
    when(reviewConfig.maxAiCalls()).thenReturn(1);
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    assistantReturns("{\"improvements\":[]}");

    service.handle(task(), AUTH);

    var summary = postedSummary();
    assertTrue(summary.contains("partial coverage"), summary);
    assertTrue(summary.contains("src/Other.java"), summary);
    assertTrue(summary.contains("omitted entirely"), summary);
  }

  @Test
  void disclosesFilesTheTokenBudgetCouldNotCoverByName() {
    // Room for the small file only: the large one cannot be clipped into any batch, so it is
    // omitted by the plan — and the disclosure must now come from that, not from the line cap.
    budgetWithDiffRoom(30);
    var big = new FileDiff("src/Huge.java", "modified", 400, 0, 400, hugePatch());
    prWithFiles(otherFile(), big);
    assistantReturns("{\"improvements\":[]}");

    service.handle(task(), AUTH);

    var summary = postedSummary();
    assertTrue(summary.contains("partial coverage"), summary);
    assertTrue(summary.contains("src/Huge.java"), summary);
  }

  private static String hugePatch() {
    var sb = new StringBuilder("@@ -0,0 +1,400 @@");
    for (int i = 0; i < 400; i++) {
      sb.append("\n+int aVariableWithALongDescriptiveName")
          .append(i)
          .append(" = compute(")
          .append(i)
          .append(");");
    }
    return sb.toString();
  }

  @Test
  void sendsOneUncappedBatchWhenTokenBudgetingIsDisabled() {
    // max-input-tokens=0 turns budgeting off. That must mean one call covering everything — not a
    // regression to the line-capped string, which would drop src/Other.java again.
    when(activeModel.maxInputTokens()).thenReturn(0);
    var lineCapped = new ReviewDiffFormatter(List.of(), 4);
    prWithFiles(foo(), otherFile());
    assistantReturns("{\"improvements\":[]}");

    serviceWith(lineCapped).handle(task(), AUTH);

    var sent = diffsSentToAssistant();
    assertEquals(1, sent.size());
    assertTrue(sent.get(0).contains("src/Other.java"), sent.get(0));
    assertTrue(sent.get(0).contains("src/Foo.java"), sent.get(0));
  }

  @Test
  void keepsImprovementsFromTheBatchesThatSucceededWhenOneBatchFails() {
    budgetWithDiffRoom(40);
    prWithFiles(foo(), otherFile());
    when(improveAssistant.improve(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("provider 503"))
        .thenReturn(
            """
            {"improvements":[{"file":"src/Foo.java","line":1,"title":"Close the stream",
            "category":"error-handling","rationale":"Leaks a handle.",
            "suggestion_old":"var in = Files.newInputStream(path);",
            "suggestion_new":"try (var in = Files.newInputStream(path)) {"}]}
            """);

    service.handle(task(), AUTH);

    verify(reviewClient, times(1))
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    var summary = postedSummary();
    assertTrue(summary.contains("Partial pass"), summary);
    assertTrue(summary.contains("could not be analyzed"), summary);
  }

  @Test
  void leavesFilesTheRepositoryAskedTheBotToIgnoreOutOfScope() {
    // #449: per-repo ignore patterns are additive on top of the global set. Now that the pass
    // covers the whole PR rather than the first max-diff-lines, a repo-ignored file is no longer
    // excluded by accident — so it has to be excluded on purpose.
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new RepoSettings(List.of("src/Other.java"), List.of(), ".github/thrillhousebot.yml"));
    budgetWithDiffRoom(4000);
    prWithFiles(foo(), otherFile());
    assistantReturns("{\"improvements\":[]}");

    service.handle(task(), AUTH);

    var sent = String.join("\n", diffsSentToAssistant());
    assertFalse(sent.contains("src/Other.java"), sent);
    assertTrue(sent.contains("src/Foo.java"), sent);
  }

  /**
   * A third file whose hunk starts deep in the file, so a line number that is correct for the whole
   * PR is nowhere near an index within its own batch. It is the smallest change of the three, so
   * the planner sizes it last and it can only land in a batch after the others.
   */
  private static final String LATE_PATCH =
      """
      @@ -0,0 +120,2 @@
      +int backoffMillis = 100;
      +Thread.sleep(backoffMillis);""";

  private static FileDiff lateFile() {
    return new FileDiff("src/Late.java", "modified", 2, 0, 2, LATE_PATCH);
  }

  private static final String LATE_IMPROVEMENT =
      """
      {"improvements":[{"file":"src/Late.java","line":121,"title":"Back off without blocking",
      "category":"performance","rationale":"A sleep blocks the worker for the whole backoff.",
      "suggestion_old":"Thread.sleep(backoffMillis);",
      "suggestion_new":"scheduler.delay(backoffMillis);"}]}
      """;

  /**
   * A per-call diff budget equal to the largest rendered file section, derived from the real
   * renderer rather than guessed: every file then fits a batch whole (so nothing is clipped), but
   * no two ever share one, so each file gets its own batch.
   */
  private int oneFilePerBatchBudget(FileDiff... files) {
    var names = ReviewDiffFormatter.namesOf(List.of(files));
    var counter = new TokenCounter();
    int largest = 0;
    for (var file : files) {
      largest =
          Math.max(largest, counter.estimateTokens(diffFormatter.formatFileSection(file, names)));
    }
    return largest;
  }

  @Test
  void anchorsAnImprovementFromALaterBatchToItsAbsoluteLine() {
    // A batch carries only its own files, but line resolution must stay whole-PR. src/Late.java's
    // hunk starts at line 120 on purpose: an improvement that came out of a later batch has to
    // anchor at the file's real line number, so a line map built from one batch — or one indexed
    // within a batch — would either fail to anchor it or rewrite the wrong line.
    budgetWithDiffRoom(oneFilePerBatchBudget(foo(), otherFile(), lateFile()));
    prWithFiles(foo(), otherFile(), lateFile());
    when(improveAssistant.improve(any(), any(), any(), any()))
        .thenAnswer(
            call ->
                call.<String>getArgument(0).contains("src/Late.java")
                    ? LATE_IMPROVEMENT
                    : "{\"improvements\":[]}");

    service.handle(task(), AUTH);

    var sent = diffsSentToAssistant();
    int batchWithLate = -1;
    for (int i = 0; i < sent.size(); i++) {
      if (sent.get(i).contains("src/Late.java")) {
        batchWithLate = i;
      }
    }
    assertTrue(batchWithLate > 0, "src/Late.java should reach a later batch, was " + batchWithLate);
    var inline = capturedInlineComment();
    assertEquals("src/Late.java", inline.path());
    assertEquals(121, inline.line());
    assertTrue(inline.body().contains("scheduler.delay(backoffMillis);"), inline.body());
  }

  @Test
  void neverCommitsASuggestionToAFileTheRepositoryAskedTheBotToIgnore() {
    // The batches never show the model an ignored file, but a hallucinated path must not be able
    // to anchor onto one either: the line map is built from the same effective file list the
    // batches are planned over, so #449's exclusion holds all the way to the committable comment.
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new RepoSettings(List.of("src/Other.java"), List.of(), ".github/thrillhousebot.yml"));
    prWithFiles(foo(), otherFile());
    assistantReturns(
        """
        {"improvements":[{"file":"src/Other.java","line":2,"title":"Bound the retry loop",
        "category":"error-handling","rationale":"An unbounded retry can spin forever.",
        "suggestion_old":"while (retries < 3) { call(); }",
        "suggestion_new":"while (retries++ < 3) { call(); }"}]}
        """);

    service.handle(task(), AUTH);

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("could not be pinned to the diff"), postedSummary());
  }
}
