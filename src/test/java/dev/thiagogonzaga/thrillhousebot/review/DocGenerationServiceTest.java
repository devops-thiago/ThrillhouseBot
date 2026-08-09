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
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.Ref;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettings;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGenerationParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGenerator;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGeneratorPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class DocGenerationServiceTest {

  private static final String AUTH = "token gh-abc";
  private static final String HEAD_SHA = "headsha1234567";

  private static final String PATCH =
      """
      @@ -0,0 +1,6 @@
      +public int bar(int x) {
      +  return x * 2;
      +}
      +public int baz(int y) {
      +  return y + 1;
      +}""";

  private static final String WRAP_PATCH =
      """
      @@ -0,0 +1,5 @@
      +public int wrap(
      +    int x,
      +    int y) {
      +  return x + y;
      +}""";

  @Mock private GitHubAuthClient authClient;
  @Mock private GitHubPullRequestClient prClient;
  @Mock private GitHubReviewClient reviewClient;
  @Mock private GitHubCommentClient commentClient;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private ProjectStackResolver projectStackResolver;
  @Mock private RepoSettingsResolver repoSettingsResolver;
  @Mock private ActiveModelSettings activeModel;
  @Mock private DocGenerator docGenerator;
  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  private final ReviewDiffFormatter diffFormatter = new ReviewDiffFormatter(List.of(), 5000);
  private final SuggestionFormatter suggestionFormatter = new SuggestionFormatter();
  private final DocGenerationParser parser = new DocGenerationParser(new ObjectMapper());

  private DocGenerationService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(authClient.getAuthHeader(anyLong())).thenReturn(AUTH);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(50);
    when(reviewConfig.maxAiCalls()).thenReturn(6);
    // Budgeting on with ample room, so a normal PR is a single batch — the same shape the command
    // had before it joined the batching seam.
    when(activeModel.maxInputTokens()).thenReturn(1_000_000);
    when(activeModel.tokenSafetyMargin()).thenReturn(1.0);
    when(activeModel.outputBufferTokens()).thenReturn(0);
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
    when(projectStackResolver.resolve(any(), any(), any(), anyLong())).thenReturn("");
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(RepoSettings.EMPTY);
    service = serviceWith(diffFormatter);
  }

  /** The service under test, wired around the given formatter. */
  private DocGenerationService serviceWith(ReviewDiffFormatter formatter) {
    return new DocGenerationService(
        authClient,
        prClient,
        reviewClient,
        commentClient,
        formatter,
        suggestionFormatter,
        instructionsResolver,
        projectStackResolver,
        docGenerator,
        parser,
        repoSettingsResolver,
        new DiffBudgetPlanner(formatter, new TokenCounter(), config, activeModel),
        activeModel,
        config);
  }

  /** A per-call budget with exactly {@code diffTokens} of room for diff text. */
  private void budgetWithDiffRoom(int diffTokens) {
    var overhead =
        new TokenCounter()
            .estimateTokens(
                DocGeneratorPrompts.systemPrompt()
                    + DocGeneratorPrompts.userPrompt()
                    + PromptTemplateEscaper.fence(" ")
                    + "Title"
                    + "Body"
                    + "");
    when(activeModel.maxInputTokens()).thenReturn(overhead + diffTokens);
  }

  /** The diff text of every batch call the generator received, in order. */
  private List<String> diffsSentToGenerator() {
    var diff = ArgumentCaptor.forClass(String.class);
    verify(docGenerator, atLeastOnce()).generate(diff.capture(), any(), any(), any());
    return diff.getAllValues();
  }

  private DocGenerationService.DocTask task() {
    return new DocGenerationService.DocTask("owner", "repo", 7, "main", 12345L);
  }

  private void prWithFiles(FileDiff... files) {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("Title", "Body", new Ref(HEAD_SHA), new Ref("basesha")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(files));
  }

  private static FileDiff fooWithPatch() {
    return new FileDiff("src/Foo.java", "modified", 6, 0, 6, PATCH);
  }

  /** A second changed file, so the planner has something to split into more than one batch. */
  private static FileDiff otherFile() {
    return new FileDiff(
        "src/Other.java",
        "modified",
        3,
        0,
        3,
        "@@ -0,0 +1,3 @@\n+public int hop(int n) {\n+  return n;\n+}");
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
  void postsCommittableSuggestionForChangedSymbol() {
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar(int)",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** Doubles x. */\\npublic int bar(int x) {"}]}
            """);

    service.handle(task());

    var inline = capturedInlineComment();
    assertEquals(HEAD_SHA, inline.commitId());
    assertEquals("src/Foo.java", inline.path());
    assertEquals(1, inline.line());
    assertEquals("RIGHT", inline.side());
    assertTrue(inline.body().contains("```suggestion"), inline.body());
    assertTrue(inline.body().contains("public int bar(int x) {"), inline.body());
    assertTrue(inline.body().contains("bar(int)"), inline.body());
    var summary = postedSummary();
    assertTrue(summary.contains("**1**"));
    assertFalse(summary.contains("were omitted"), summary);
  }

  @Test
  void appendsPartialCoverageDisclosureNamingTheFilesNoBatchCouldCover() {
    // One batch's worth of allowance and room for one file, so the second is left uncovered by the
    // token budget — and named, rather than silently dropped at a line boundary.
    when(reviewConfig.maxAiCalls()).thenReturn(1);
    budgetWithDiffRoom(40);
    prWithFiles(fooWithPatch(), otherFile());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar(int)",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** Doubles x. */\\npublic int bar(int x) {"}]}
            """);

    service.handle(task());

    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    var summary = postedSummary();
    assertTrue(summary.contains("**1**"), summary);
    assertTrue(summary.contains("partial coverage"), summary);
    assertTrue(summary.contains("src/Other.java"), summary);
    assertFalse(summary.contains("findings and verdict"), summary);
  }

  @Test
  void postsMultiLineSuggestionAnchoredToTheWholeDeclarationRange() {
    prWithFiles(new FileDiff("src/Wrap.java", "added", 5, 0, 5, WRAP_PATCH));
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Wrap.java","line":1,"symbol":"wrap",
            "suggestion_old":"public int wrap(\\n    int x,\\n    int y) {",
            "suggestion_new":"/** Adds x and y. */\\npublic int wrap(\\n    int x,\\n    int y) {"}]}
            """);

    service.handle(task());

    var inline = capturedInlineComment();
    assertEquals(3, inline.line());
    assertEquals(1, inline.startLine());
    assertEquals("RIGHT", inline.startSide());
  }

  @Test
  void flagsMultiLineSuggestionThatCannotBeAnchoredWithoutACommittableSuggestion() {
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {\\n  return x * 99;",
            "suggestion_new":"/** Doubles. */\\npublic int bar(int x) {\\n  return x * 99;"}]}
            """);

    service.handle(task());

    var inline = capturedInlineComment();
    assertEquals(1, inline.line());
    assertNull(inline.startLine());
    assertFalse(inline.body().contains("```suggestion"), inline.body());
    assertTrue(inline.body().contains("missing documentation"), inline.body());
    assertTrue(inline.body().contains("bar"), inline.body());
    assertTrue(postedSummary().contains("add manually"), postedSummary());
    assertFalse(
        postedSummary().contains("commit the suggestions you want to keep"), postedSummary());
  }

  @Test
  void anchorsAMultiLineSuggestionByContentWhenTheReportedLineIsOffByOne() {
    var patch =
        """
        @@ -10,0 +11,3 @@
        +public int wrap(
        +    int x,
        +    int y) {
        """;
    prWithFiles(new FileDiff("src/Wrap.java", "added", 3, 0, 3, patch));
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Wrap.java","line":10,"symbol":"wrap",
            "suggestion_old":"public int wrap(\\n    int x,\\n    int y) {",
            "suggestion_new":"/** Adds x and y. */\\npublic int wrap(\\n    int x,\\n    int y) {"}]}
            """);

    service.handle(task());

    var inline = capturedInlineComment();
    assertEquals(13, inline.line());
    assertEquals(11, inline.startLine());
    assertTrue(inline.body().contains("```suggestion"), inline.body());
  }

  @Test
  void aNoteThatGitHubRejectsIsNotCounted() {
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {\\n  return x * 99;",
            "suggestion_new":"/** d */\\npublic int bar(int x) {\\n  return x * 99;"}]}
            """);
    doThrow(new RuntimeException("422 Unprocessable Entity"))
        .when(reviewClient)
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());

    service.handle(task());

    assertEquals(DocGenerationService.COULD_NOT_PLACE, postedSummary());
  }

  @Test
  void summaryReportsBothCommittableSuggestionsAndNotes() {
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[
              {"file":"src/Foo.java","line":1,"symbol":"bar",
               "suggestion_old":"public int bar(int x) {",
               "suggestion_new":"/** a */\\npublic int bar(int x) {"},
              {"file":"src/Foo.java","line":4,"symbol":"baz",
               "suggestion_old":"public int baz(int y) {\\n  return y + 99;",
               "suggestion_new":"/** b */\\npublic int baz(int y) {\\n  return y + 99;"}]}
            """);

    service.handle(task());

    var summary = postedSummary();
    assertTrue(summary.contains("committable documentation suggestion"), summary);
    assertTrue(summary.contains("add manually"), summary);
  }

  @Test
  void capsAtMaxReviewComments() {
    when(reviewConfig.maxReviewComments()).thenReturn(1);
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[
              {"file":"src/Foo.java","line":1,"symbol":"bar",
               "suggestion_old":"public int bar(int x) {",
               "suggestion_new":"/** a */\\npublic int bar(int x) {"},
              {"file":"src/Foo.java","line":4,"symbol":"baz",
               "suggestion_old":"public int baz(int y) {",
               "suggestion_new":"/** b */\\npublic int baz(int y) {"}]}
            """);

    service.handle(task());

    verify(reviewClient, times(1))
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("1 more changed symbol"), postedSummary());
    assertTrue(postedSummary().contains("re-run"), postedSummary());
  }

  @Test
  void disclosesCapDropEvenWhenNothingWasPosted() {
    when(reviewConfig.maxReviewComments()).thenReturn(0);
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
             "suggestion_old":"public int bar(int x) {",
             "suggestion_new":"/** a */\\npublic int bar(int x) {"}]}
            """);

    service.handle(task());

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("comment cap was reached"), postedSummary());
    assertFalse(postedSummary().contains("could not anchor"), postedSummary());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unpostableSuggestions")
  void doesNotPostSuggestionThatCannotAnchorCleanly(String reason, String docsJson) {
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any())).thenReturn(docsJson);

    service.handle(task());

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertEquals(DocGenerationService.COULD_NOT_PLACE, postedSummary());
  }

  static Stream<Arguments> unpostableSuggestions() {
    return Stream.of(
        arguments(
            "declaration line is not in the diff",
            """
            {"docs":[{"file":"src/Foo.java","line":99,"symbol":"ghost",
            "suggestion_old":"whatever","suggestion_new":"/** x */\\nwhatever"}]}
            """),
        arguments(
            "replacement would drop the existing declaration line",
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** just a docstring, no code */"}]}
            """),
        arguments(
            "suggestion_new is blank (not postable)",
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {","suggestion_new":""}]}
            """),
        arguments(
            "suggestion_old is omitted (no anchor to verify against)",
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"","suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
            """),
        arguments(
            "file is not part of the diff",
            """
            {"docs":[{"file":"src/Other.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
            """));
  }

  @Test
  void reportsNothingToDocumentOnEmptyResult() {
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any())).thenReturn("{\"docs\":[]}");

    service.handle(task());

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertEquals(DocGenerationService.NOTHING_TO_DOCUMENT, postedSummary());
  }

  @Test
  void reportsNoFilesWhenDiffIsEmpty() {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("Title", "Body", new Ref(HEAD_SHA), new Ref("basesha")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of());

    service.handle(task());

    assertEquals(DocGenerationService.NO_FILES, postedSummary());
    verifyNoInteractions(docGenerator);
  }

  @Test
  void reportsWhenPullRequestCannotBeLoaded() {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("404"));

    service.handle(task());

    assertEquals(DocGenerationService.NO_PR_DETAILS, postedSummary());
    verifyNoInteractions(docGenerator);
  }

  @Test
  void reportsFailureWhenGenerationThrows() {
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

    service.handle(task());

    assertEquals(DocGenerationService.GENERATION_FAILED, postedSummary());
    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void reportsFailureWhenBatchPlanningThrows() {
    // Planning stays inside the command's own handler so a planner failure still surfaces the
    // failure notice to the maintainer rather than only a log line.
    var throwingFormatter = mock(ReviewDiffFormatter.class);
    var foo = fooWithPatch();
    when(throwingFormatter.reviewableFiles(anyList())).thenReturn(List.of(foo));
    when(throwingFormatter.formatFileSection(any(), anySet()))
        .thenThrow(new RuntimeException("formatter boom"));
    var throwingService = serviceWith(throwingFormatter);
    prWithFiles(foo);

    throwingService.handle(task());

    assertEquals(DocGenerationService.GENERATION_FAILED, postedSummary());
    verifyNoInteractions(docGenerator);
    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void swallowsUnexpectedFailures() {
    when(authClient.getAuthHeader(anyLong())).thenThrow(new RuntimeException("auth down"));

    assertDoesNotThrow(() -> service.handle(task()));
    verifyNoInteractions(commentClient);
  }

  @Test
  void feedsRepositoryInstructionsAndProjectStackIntoThePrompt() {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(
            new PullRequestDetails(
                "Add cache", "Speeds up reads", new Ref(HEAD_SHA), new Ref("b")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(fooWithPatch()));
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new InstructionsResolver.ResolvedInstructions(
                "Use British spelling.", ".github/thrillhousebot.md"));
    when(projectStackResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn("Maven artifacts: quarkus-core");
    when(docGenerator.generate(any(), any(), any(), any())).thenReturn("{\"docs\":[]}");

    service.handle(task());

    verify(docGenerator)
        .generate(
            any(),
            contains("Add cache"),
            contains("Maven artifacts: quarkus-core"),
            contains("Project-Specific Instructions"));
  }

  @Test
  void continuesWhenProjectStackResolutionFails() {
    prWithFiles(fooWithPatch());
    when(projectStackResolver.resolve(any(), any(), any(), anyLong()))
        .thenThrow(new RuntimeException("stack down"));
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
            """);

    service.handle(task());

    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("**1**"));
  }

  @Test
  void continuesWhenInstructionsResolutionFails() {
    prWithFiles(fooWithPatch());
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenThrow(new RuntimeException("instructions down"));
    when(docGenerator.generate(any(), any(), any(), any())).thenReturn("{\"docs\":[]}");

    service.handle(task());

    assertEquals(DocGenerationService.NOTHING_TO_DOCUMENT, postedSummary());
  }

  @Test
  void handlesPullRequestWithNoTitleOrBody() {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails(null, null, new Ref(HEAD_SHA), new Ref("base")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(fooWithPatch()));
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
            """);

    service.handle(task());

    verify(docGenerator).generate(any(), eq(""), any(), any());
    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void reportsWhenHeadIsMissing() {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("T", "B", null, new Ref("base")));

    service.handle(task());

    assertEquals(DocGenerationService.NO_PR_DETAILS, postedSummary());
    verifyNoInteractions(docGenerator);
  }

  @Test
  void reportsWhenHeadShaIsBlank() {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("T", "B", new Ref(" "), new Ref("base")));

    service.handle(task());

    assertEquals(DocGenerationService.NO_PR_DETAILS, postedSummary());
    verifyNoInteractions(docGenerator);
  }

  @Test
  void reportsNoFilesWhenFileFetchFails() {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("T", "B", new Ref(HEAD_SHA), new Ref("base")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenThrow(new RuntimeException("files down"));

    service.handle(task());

    assertEquals(DocGenerationService.NO_FILES, postedSummary());
    verifyNoInteractions(docGenerator);
  }

  @Test
  void reportsNoFilesWhenFileListIsNull() {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("T", "B", new Ref(HEAD_SHA), new Ref("base")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(null);

    service.handle(task());

    assertEquals(DocGenerationService.NO_FILES, postedSummary());
  }

  @Test
  void skipsSuggestionRejectedByGitHub() {
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
            """);
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), anyInt(), any()))
        .thenThrow(new RuntimeException("422 line outside diff"));

    service.handle(task());

    assertEquals(DocGenerationService.COULD_NOT_PLACE, postedSummary());
  }

  @Test
  void handlesBlankTitleAndBody() {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("  ", "  ", new Ref(HEAD_SHA), new Ref("base")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(fooWithPatch()));
    when(docGenerator.generate(any(), any(), any(), any())).thenReturn("{\"docs\":[]}");

    service.handle(task());

    verify(docGenerator).generate(any(), eq(""), any(), any());
  }

  @Test
  void reportsWhenHeadShaIsNull() {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("T", "B", new Ref((String) null), new Ref("base")));

    service.handle(task());

    assertEquals(DocGenerationService.NO_PR_DETAILS, postedSummary());
    verifyNoInteractions(docGenerator);
  }

  @Test
  void ignoresChangedFilesThatCarryNoPatch() {
    prWithFiles(
        fooWithPatch(),
        new FileDiff("src/Binary.bin", "modified", 0, 0, 0, null),
        new FileDiff("src/Empty.java", "modified", 0, 0, 0, "  "));
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
            """);

    service.handle(task());

    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("**1**"));
  }

  /** The doc the model returns for {@code src/Other.java}, anchored to its declaration line. */
  private static final String DOC_FOR_OTHER =
      """
      {"docs":[{"file":"src/Other.java","line":1,"symbol":"hop(int)",
      "suggestion_old":"public int hop(int n) {",
      "suggestion_new":"/** Hops. */\\npublic int hop(int n) {"}]}
      """;

  @Test
  void postsNoSuggestionForAFileOnlyThePerRepoIgnorePatternExcludes() {
    // src/Other.java is reviewable under the global ignore list (which is empty here) and is
    // excluded only by the pattern the repository declared for itself. This command posts
    // committable edits, so an ignored path must reach neither a batch nor the anchor resolver.
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new RepoSettings(List.of("src/Other.java"), List.of(), ".github/thrillhousebot.yml"));
    prWithFiles(fooWithPatch(), otherFile());
    when(docGenerator.generate(any(), any(), any(), any())).thenReturn(DOC_FOR_OTHER);

    service.handle(task());

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertEquals(DocGenerationService.COULD_NOT_PLACE, postedSummary());
    var sent = String.join("\n", diffsSentToGenerator());
    assertFalse(sent.contains("src/Other.java"), "the ignored file must not reach a batch");
    assertTrue(sent.contains("src/Foo.java"), "the in-scope file must still reach a batch");
  }

  @Test
  void keepsTheGlobalIgnoreListWorkingForARepositoryWithNoOwnSettings() {
    // No .github/thrillhousebot.yml: the effective ignore set must still be exactly the global
    // list — the per-repo patterns are additive, never a replacement for it.
    var globallyIgnoring = new ReviewDiffFormatter(List.of("**/*.md"), 5000);
    var globalService = serviceWith(globallyIgnoring);
    prWithFiles(fooWithPatch(), new FileDiff("docs/README.md", "modified", 1, 0, 1, "@@\n+hi"));
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
            """);

    globalService.handle(task());

    var sent = String.join("\n", diffsSentToGenerator());
    assertFalse(sent.contains("docs/README.md"), sent);
    assertTrue(sent.contains("src/Foo.java"), sent);
    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("**1**"), postedSummary());
  }

  @Test
  void documentsFilesThatTheLineCapWouldHaveDroppedEntirely() {
    // The point of the change: coverage is planned over the whole file list, so a file past the
    // max-diff-lines boundary still reaches a model instead of falling off the end of a
    // line-capped render with only a footnote to show for it.
    var tinyLineCap = new ReviewDiffFormatter(List.of(), 8);
    var cappedService = serviceWith(tinyLineCap);
    prWithFiles(fooWithPatch(), otherFile());
    when(docGenerator.generate(any(), any(), any(), any())).thenReturn(DOC_FOR_OTHER);

    cappedService.handle(task());

    var sent = String.join("\n", diffsSentToGenerator());
    assertTrue(sent.contains("public int hop(int n) {"), sent);
    assertFalse(sent.contains("patch truncated"), sent);
    var inline = capturedInlineComment();
    assertEquals("src/Other.java", inline.path());
    assertTrue(inline.body().contains("```suggestion"), inline.body());
    assertFalse(postedSummary().contains("partial coverage"), postedSummary());
  }

  @Test
  void plansOneBatchPerSliceOfTheChangeSetRatherThanOneCallOverTheWholeRender() {
    budgetWithDiffRoom(40);
    prWithFiles(fooWithPatch(), otherFile());
    when(docGenerator.generate(any(), any(), any(), any())).thenReturn("{\"docs\":[]}");

    service.handle(task());

    var sent = diffsSentToGenerator();
    assertEquals(2, sent.size(), sent.toString());
    // Each batch carries its own slice: asserting only that the slices *contain* their file would
    // also pass if every call were handed the whole-PR render, which is what this replaces.
    assertTrue(sent.get(0).contains("src/Foo.java"), sent.get(0));
    assertFalse(sent.get(0).contains("src/Other.java"), sent.get(0));
    assertTrue(sent.get(1).contains("src/Other.java"), sent.get(1));
    assertFalse(sent.get(1).contains("src/Foo.java"), sent.get(1));
  }

  @Test
  void keepsTheGuardsAcrossBatchesSoOnlyTheAnchorableDocIsPosted() {
    // The anchor and declaration-retention guards run over the merged docs of every batch, not one
    // batch's: a second batch must not be able to slip past them.
    budgetWithDiffRoom(40);
    prWithFiles(fooWithPatch(), otherFile());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
            """)
        .thenReturn(
            """
            {"docs":[{"file":"src/Other.java","line":1,"symbol":"hop",
            "suggestion_old":"public int hop(int n) {",
            "suggestion_new":"/** just a docstring, no code */"}]}
            """);

    service.handle(task());

    var inline = capturedInlineComment();
    assertEquals("src/Foo.java", inline.path());
    assertTrue(postedSummary().contains("**1**"), postedSummary());
  }

  @Test
  void keepsTheCommentCapOverTheDocsMergedFromEveryBatch() {
    when(reviewConfig.maxReviewComments()).thenReturn(1);
    budgetWithDiffRoom(40);
    prWithFiles(fooWithPatch(), otherFile());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
            """)
        .thenReturn(DOC_FOR_OTHER);

    service.handle(task());

    verify(reviewClient, times(1))
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("1 more changed symbol"), postedSummary());
  }

  @Test
  void reportsTheBudgetRatherThanAVerdictWhenNoFileFitsABatch() {
    // Going quiet would hide a misconfigured budget, and NOTHING_TO_DOCUMENT here would be a
    // verdict on code the model never read.
    when(activeModel.maxInputTokens()).thenReturn(10);
    prWithFiles(fooWithPatch(), otherFile());

    service.handle(task());

    verifyNoInteractions(docGenerator);
    var summary = postedSummary();
    assertTrue(summary.startsWith(DocGenerationService.NOT_COVERED), summary);
    assertTrue(summary.contains("src/Foo.java"), summary);
    assertTrue(summary.contains("src/Other.java"), summary);
  }

  @Test
  void keepsTheDocsFromTheBatchesThatSucceededWhenOneBatchFails() {
    budgetWithDiffRoom(40);
    prWithFiles(fooWithPatch(), otherFile());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              if (call.<String>getArgument(0).contains("src/Other.java")) {
                throw new RuntimeException("model down");
              }
              return """
                  {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
                  "suggestion_old":"public int bar(int x) {",
                  "suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
                  """;
            });

    service.handle(task());

    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("Partial pass"), postedSummary());
  }

  @Test
  void reportsNoFilesWhenEveryChangedFileIsOutOfScopeForTheRepository() {
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(
            new RepoSettings(
                List.of("src/Foo.java", "src/Other.java"),
                List.of(),
                ".github/thrillhousebot.yml"));
    prWithFiles(fooWithPatch(), otherFile());

    service.handle(task());

    assertEquals(DocGenerationService.NO_FILES, postedSummary());
    verifyNoInteractions(docGenerator);
  }

  @Test
  void continuesWithTheGlobalIgnoreListWhenRepositorySettingsCannotBeResolved() {
    // The SoftLoaders contract: a failure in the new resolution path degrades to the previous
    // behaviour rather than failing the command.
    when(repoSettingsResolver.resolve(any(), any(), any(), anyLong()))
        .thenThrow(new RuntimeException("repo config down"));
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** d */\\npublic int bar(int x) {"}]}
            """);

    service.handle(task());

    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertTrue(postedSummary().contains("**1**"), postedSummary());
  }
}
