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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class DocGenerationServiceTest {

  private static final String AUTH = "token gh-abc";
  private static final String HEAD_SHA = "headsha1234567";

  // A two-method diff: bar() on right-side line 1, baz() on right-side line 4.
  private static final String PATCH =
      "@@ -0,0 +1,6 @@\n"
          + "+public int bar(int x) {\n"
          + "+  return x * 2;\n"
          + "+}\n"
          + "+public int baz(int y) {\n"
          + "+  return y + 1;\n"
          + "+}";

  @Mock private GitHubAuthClient authClient;
  @Mock private GitHubPullRequestClient prClient;
  @Mock private GitHubReviewClient reviewClient;
  @Mock private GitHubCommentClient commentClient;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private ProjectStackResolver projectStackResolver;
  @Mock private DocGenerator docGenerator;
  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  // Real collaborators — their formatting/line-resolution logic is what we want exercised.
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
    assertTrue(postedSummary().contains("**1**"));
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

    // Only one inline comment despite two candidates.
    verify(reviewClient, times(1))
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void skipsSuggestionWhenDeclarationLineIsNotInDiff() {
    prWithFiles(fooWithPatch());
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":99,"symbol":"ghost",
            "suggestion_old":"whatever","suggestion_new":"/** x */\\nwhatever"}]}
            """);

    service.handle(task());

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertEquals(DocGenerationService.COULD_NOT_PLACE, postedSummary());
  }

  @Test
  void skipsSuggestionThatWouldDropTheExistingLine() {
    prWithFiles(fooWithPatch());
    // suggestion_new is ONLY a docstring — committing it would delete the declaration line.
    when(docGenerator.generate(any(), any(), any(), any()))
        .thenReturn(
            """
            {"docs":[{"file":"src/Foo.java","line":1,"symbol":"bar",
            "suggestion_old":"public int bar(int x) {",
            "suggestion_new":"/** just a docstring, no code */"}]}
            """);

    service.handle(task());

    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    assertEquals(DocGenerationService.COULD_NOT_PLACE, postedSummary());
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
  void swallowsUnexpectedFailures() {
    when(authClient.getAuthHeader(anyLong())).thenThrow(new RuntimeException("auth down"));

    assertDoesNotThrow(() -> service.handle(task()));
    verifyNoInteractions(commentClient);
  }
}
