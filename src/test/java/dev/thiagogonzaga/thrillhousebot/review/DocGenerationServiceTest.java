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
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.Ref;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGenerationParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGenerator;
import java.util.List;
import java.util.Map;
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
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
    when(projectStackResolver.resolve(any(), any(), any(), anyLong())).thenReturn("");
    service =
        new DocGenerationService(
            authClient,
            prClient,
            reviewClient,
            commentClient,
            diffFormatter,
            suggestionFormatter,
            instructionsResolver,
            projectStackResolver,
            docGenerator,
            parser,
            config);
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
  void appendsPartialCoverageDisclosureToTheSummaryWhenFilesWereOmitted() {
    var truncatingFormatter = mock(ReviewDiffFormatter.class);
    var foo = fooWithPatch();
    when(truncatingFormatter.reviewableFiles(anyList())).thenReturn(List.of(foo));
    when(truncatingFormatter.buildDiffStringWithStats(anyList(), anyList()))
        .thenReturn(new ReviewDiffFormatter.FormattedDiff("## Overview\n(truncated)", 48));
    when(truncatingFormatter.patchesByReviewableFiles(anyList()))
        .thenReturn(Map.of("src/Foo.java", PATCH));
    var truncatingService =
        new DocGenerationService(
            authClient,
            prClient,
            reviewClient,
            commentClient,
            truncatingFormatter,
            suggestionFormatter,
            instructionsResolver,
            projectStackResolver,
            docGenerator,
            parser,
            config);
    prWithFiles(foo);
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar(int)",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** Doubles x. */\\npublic int bar(int x) {"}]}
            """);

    truncatingService.handle(task());

    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    var summary = postedSummary();
    assertTrue(summary.contains("**1**"), summary);
    assertTrue(summary.contains("48 file(s) were omitted"), summary);
    assertTrue(summary.contains("partial coverage"), summary);
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
  void reportsFailureWhenDiffBuildThrows() {
    var throwingFormatter = mock(ReviewDiffFormatter.class);
    var foo = fooWithPatch();
    when(throwingFormatter.reviewableFiles(anyList())).thenReturn(List.of(foo));
    when(throwingFormatter.buildDiffStringWithStats(anyList(), anyList()))
        .thenThrow(new RuntimeException("formatter boom"));
    var throwingService =
        new DocGenerationService(
            authClient,
            prClient,
            reviewClient,
            commentClient,
            throwingFormatter,
            suggestionFormatter,
            instructionsResolver,
            projectStackResolver,
            docGenerator,
            parser,
            config);
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
}
