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
import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGitDataClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileContent;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.Ref;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.RefRepo;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResults;
import dev.thiagogonzaga.thrillhousebot.review.ai.FixGenerator;
import dev.thiagogonzaga.thrillhousebot.review.ai.FixResponseParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.webhook.TriggerDetector;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class FixServiceTest {

  private static final String AUTH = "token gh-abc";
  private static final String HEAD_SHA = "headsha1234567";
  private static final long ROOT_COMMENT_ID = 555L;

  private static final String FOO_CONTENT =
      """
      public class Foo {
        int x = 1;
      }
      """;

  private static final String PATCH =
      """
      @@ -1,3 +1,3 @@
       public class Foo {
      -  int x = 0;
      +  int x = 1;
       }""";

  @Mock private ExecutorService executor;
  @Mock private GitHubAuthClient authClient;
  @Mock private GitHubPullRequestClient prClient;
  @Mock private GitHubReviewClient reviewClient;
  @Mock private GitHubGitDataClient gitDataClient;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private ProjectStackResolver projectStackResolver;
  @Mock private FixGenerator fixGenerator;
  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;
  @Mock private ThrillhouseConfig.FixConfig fixConfig;
  @Mock private ThrillhouseConfig.AiPricingConfig aiConfig;
  @Mock private ThrillhouseConfig.AiPricingConfig.ReasoningConfig reasoning;
  @Mock private ActiveModelSettings activeModel;
  @Mock private ReviewTokenLedger tokenLedger;

  private final ReviewDiffFormatter diffFormatter = new ReviewDiffFormatter(List.of(), 5000);
  private final FixResponseParser parser = new FixResponseParser(new ObjectMapper());

  private FixService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(authClient.getAuthHeader(anyLong())).thenReturn(AUTH);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.fix()).thenReturn(fixConfig);
    when(fixConfig.maxEditedFiles()).thenReturn(10);
    when(config.ai()).thenReturn(aiConfig);
    when(aiConfig.reasoning()).thenReturn(reasoning);
    when(reasoning.effort()).thenReturn("low");
    when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
        .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
    when(projectStackResolver.resolve(any(), any(), any(), anyLong())).thenReturn("");
    service =
        new FixService(
            executor,
            authClient,
            prClient,
            reviewClient,
            gitDataClient,
            diffFormatter,
            instructionsResolver,
            projectStackResolver,
            fixGenerator,
            parser,
            config,
            activeModel,
            tokenLedger,
            new TriggerDetector());
  }

  private FixService.FixTask task() {
    return new FixService.FixTask(
        "owner", "repo", 7, "main", 12345L, "alice", ROOT_COMMENT_ID, "src/Foo.java");
  }

  private void findingThreadExists() {
    when(reviewClient.getPullRequestComment(
            any(), any(), eq("owner"), eq("repo"), eq(ROOT_COMMENT_ID)))
        .thenReturn(
            new GitHubReviewClient.PullRequestComment(
                ROOT_COMMENT_ID,
                null,
                "src/Foo.java",
                "**🔴 HIGH — x should be 2**\n\nThe field is off by one.",
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
  }

  private void prWithFooFile() {
    prWithHead(new Ref(HEAD_SHA, "feature-branch"));
  }

  private void prWithHead(Ref head) {
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(new PullRequestDetails("Title", "Body", head, new Ref("basesha", "main")));
    when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(new FileDiff("src/Foo.java", "modified", 1, 1, 2, PATCH)));
    stubFileContent("src/Foo.java", FOO_CONTENT);
  }

  private void stubFileContent(String path, String text) {
    var encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    when(prClient.getFileContent(any(), any(), eq("owner"), eq("repo"), eq(path), eq(HEAD_SHA)))
        .thenReturn(new FileContent(path, path, encoded, "base64", text.length()));
  }

  private void gitDataSucceeds() {
    when(gitDataClient.getCommit(any(), any(), eq("owner"), eq("repo"), eq(HEAD_SHA)))
        .thenReturn(
            new GitHubGitDataClient.GitCommit(
                HEAD_SHA, new GitHubGitDataClient.TreeRef("basetree")));
    when(gitDataClient.createTree(any(), any(), eq("owner"), eq("repo"), any()))
        .thenReturn(new GitHubGitDataClient.GitObject("newtreesha"));
    when(gitDataClient.createCommit(any(), any(), eq("owner"), eq("repo"), any()))
        .thenReturn(new GitHubGitDataClient.GitObject("fixcommitsha"));
    when(gitDataClient.createRef(any(), any(), eq("owner"), eq("repo"), any()))
        .thenReturn(new GitHubGitDataClient.GitObject("fixcommitsha"));
    when(prClient.createPullRequest(any(), any(), eq("owner"), eq("repo"), any()))
        .thenReturn(
            new GitHubPullRequestClient.CreatedPullRequest(
                99, "https://github.com/owner/repo/pull/99"));
  }

  private void modelReturns(String json) {
    when(fixGenerator.generate(any(), any(), any(), any(), any(), any()))
        .thenReturn(AiResults.aiOk(json));
  }

  private String threadReply() {
    var captor = ArgumentCaptor.forClass(GitHubReviewClient.ReplyToReviewCommentRequest.class);
    verify(reviewClient)
        .replyToReviewComment(
            any(), any(), eq("owner"), eq("repo"), eq(7), eq(ROOT_COMMENT_ID), captor.capture());
    return captor.getValue().body();
  }

  private void verifyNoGitWrites() {
    verify(gitDataClient, never()).createTree(any(), any(), any(), any(), any());
    verify(gitDataClient, never()).createCommit(any(), any(), any(), any(), any());
    verify(gitDataClient, never()).createRef(any(), any(), any(), any(), any());
    verify(prClient, never()).createPullRequest(any(), any(), any(), any(), any());
  }

  @Test
  void appliesReplaceEditAndOpensAttributedFixPr() {
    findingThreadExists();
    prWithFooFile();
    gitDataSucceeds();
    modelReturns(
        """
        {"summary": "Set x to 2", "edits": [
          {"file": "src/Foo.java", "operation": "replace",
           "search": "int x = 1;", "replace": "int x = 2;"}
        ], "notes": "double-check the constant"}
        """);

    service.execute(task());

    var tree = ArgumentCaptor.forClass(GitHubGitDataClient.CreateTreeRequest.class);
    verify(gitDataClient).createTree(any(), any(), eq("owner"), eq("repo"), tree.capture());
    assertEquals("basetree", tree.getValue().baseTree());
    assertEquals(1, tree.getValue().tree().size());
    assertEquals("src/Foo.java", tree.getValue().tree().get(0).path());
    assertTrue(tree.getValue().tree().get(0).content().contains("int x = 2;"));
    assertFalse(tree.getValue().tree().get(0).content().contains("int x = 1;"));

    var commit = ArgumentCaptor.forClass(GitHubGitDataClient.CreateCommitRequest.class);
    verify(gitDataClient).createCommit(any(), any(), eq("owner"), eq("repo"), commit.capture());
    assertEquals(List.of(HEAD_SHA), commit.getValue().parents());
    assertTrue(commit.getValue().message().startsWith("Set x to 2"));
    assertTrue(commit.getValue().message().contains("@alice"));

    var ref = ArgumentCaptor.forClass(GitHubGitDataClient.CreateRefRequest.class);
    verify(gitDataClient).createRef(any(), any(), eq("owner"), eq("repo"), ref.capture());
    assertTrue(ref.getValue().ref().startsWith("refs/heads/" + FixService.BRANCH_PREFIX));
    assertEquals("fixcommitsha", ref.getValue().sha());

    var pr = ArgumentCaptor.forClass(GitHubPullRequestClient.CreatePullRequestRequest.class);
    verify(prClient).createPullRequest(any(), any(), eq("owner"), eq("repo"), pr.capture());
    assertEquals("feature-branch", pr.getValue().base());
    assertTrue(pr.getValue().head().startsWith(FixService.BRANCH_PREFIX));
    assertTrue(pr.getValue().title().contains("Set x to 2"));
    assertTrue(pr.getValue().body().contains("@alice"));
    assertTrue(pr.getValue().body().contains("#7"));
    assertTrue(pr.getValue().body().contains("discussion_r" + ROOT_COMMENT_ID));
    assertTrue(pr.getValue().body().contains(FixService.PR_DISCLAIMER));

    assertTrue(threadReply().contains("https://github.com/owner/repo/pull/99"));
  }

  @Test
  void createsNewFileAlongsideReplaceEdit() {
    findingThreadExists();
    prWithFooFile();
    gitDataSucceeds();
    // The created path must not already exist: GitHub answers its content lookup with a 404.
    when(prClient.getFileContent(
            any(), any(), eq("owner"), eq("repo"), eq("src/FooValidator.java"), eq(HEAD_SHA)))
        .thenThrow(new WebApplicationException(Response.status(404).build()));
    modelReturns(
        """
        {"summary": "Extract validation", "edits": [
          {"file": "src/Foo.java", "operation": "replace",
           "search": "int x = 1;", "replace": "int x = FooValidator.DEFAULT;"},
          {"file": "src/FooValidator.java", "operation": "create",
           "search": "", "replace": "public class FooValidator { static final int DEFAULT = 2; }"}
        ], "notes": ""}
        """);

    service.execute(task());

    var tree = ArgumentCaptor.forClass(GitHubGitDataClient.CreateTreeRequest.class);
    verify(gitDataClient).createTree(any(), any(), eq("owner"), eq("repo"), tree.capture());
    assertEquals(2, tree.getValue().tree().size());
    assertEquals("src/FooValidator.java", tree.getValue().tree().get(1).path());
  }

  @Test
  void repliesForkUnsupportedWithoutTouchingGitData() {
    findingThreadExists();
    prWithHead(new Ref(HEAD_SHA, "feature-branch", new RefRepo("someone-else/fork")));

    service.execute(task());

    assertEquals(FixService.FORK_UNSUPPORTED, threadReply());
    verifyNoGitWrites();
    verifyNoInteractions(fixGenerator);
  }

  @Test
  void repliesDeclineWithModelNotesWhenNoEdits() {
    findingThreadExists();
    prWithFooFile();
    modelReturns("{\"summary\": \"\", \"edits\": [], \"notes\": \"Already fixed upstream.\"}");

    service.execute(task());

    var reply = threadReply();
    assertTrue(reply.contains("declined"));
    assertTrue(reply.contains("Already fixed upstream."));
    verifyNoGitWrites();
  }

  @Test
  void abortsWhenSearchSnippetIsMissing() {
    findingThreadExists();
    prWithFooFile();
    modelReturns(
        """
        {"summary": "s", "edits": [
          {"file": "src/Foo.java", "operation": "replace",
           "search": "int y = 9;", "replace": "int y = 2;"}
        ], "notes": ""}
        """);

    service.execute(task());

    assertEquals(FixService.EDIT_MISMATCH, threadReply());
    verifyNoGitWrites();
  }

  @Test
  void abortsWhenSearchSnippetIsAmbiguous() {
    findingThreadExists();
    prWithFooFile();
    stubFileContent("src/Foo.java", "int x = 1;\nint x = 1;\n");
    modelReturns(
        """
        {"summary": "s", "edits": [
          {"file": "src/Foo.java", "operation": "replace",
           "search": "int x = 1;", "replace": "int x = 2;"}
        ], "notes": ""}
        """);

    service.execute(task());

    assertEquals(FixService.EDIT_MISMATCH, threadReply());
    verifyNoGitWrites();
  }

  @Test
  void abortsWhenEditTargetsUnloadedFile() {
    findingThreadExists();
    prWithFooFile();
    modelReturns(
        """
        {"summary": "s", "edits": [
          {"file": "src/Elsewhere.java", "operation": "replace",
           "search": "a", "replace": "b"}
        ], "notes": ""}
        """);

    service.execute(task());

    assertEquals(FixService.EDIT_MISMATCH, threadReply());
    verifyNoGitWrites();
  }

  @Test
  void abortsWhenCreatedPathEscapesTheRepo() {
    findingThreadExists();
    prWithFooFile();
    modelReturns(
        """
        {"summary": "s", "edits": [
          {"file": "../evil.sh", "operation": "create", "search": "", "replace": "x"}
        ], "notes": ""}
        """);

    service.execute(task());

    assertEquals(FixService.EDIT_MISMATCH, threadReply());
    verifyNoGitWrites();
  }

  @Test
  void rejectsFixTouchingMoreFilesThanConfigured() {
    findingThreadExists();
    prWithFooFile();
    when(fixConfig.maxEditedFiles()).thenReturn(1);
    modelReturns(
        """
        {"summary": "s", "edits": [
          {"file": "src/Foo.java", "operation": "replace", "search": "int x = 1;", "replace": "a"},
          {"file": "src/New.java", "operation": "create", "search": "", "replace": "b"}
        ], "notes": ""}
        """);

    service.execute(task());

    assertTrue(threadReply().contains("limit of 1"));
    verifyNoGitWrites();
  }

  @Test
  void abandonsACreateWhenThePathCheckFailsWithoutA404() {
    assertCreateAbandoned(new WebApplicationException(Response.status(502).build()));
  }

  @Test
  void abandonsACreateWhenThePathCheckFailsOutsideHttp() {
    assertCreateAbandoned(new RuntimeException("connection reset"));
  }

  @Test
  void abandonsACreateWhenThePathCheckFailsWithNoResponse() {
    assertCreateAbandoned(new WebApplicationException((Response) null));
  }

  /** A lookup that fails without saying "not found" must never be read as a free path. */
  private void assertCreateAbandoned(RuntimeException lookupFailure) {
    findingThreadExists();
    prWithFooFile();
    when(prClient.getFileContent(
            any(), any(), eq("owner"), eq("repo"), eq("src/New.java"), eq(HEAD_SHA)))
        .thenThrow(lookupFailure);
    modelReturns(
        """
        {"summary": "s", "edits": [
          {"file": "src/New.java", "operation": "create", "search": "", "replace": "b"}
        ], "notes": ""}
        """);

    service.execute(task());

    assertEquals(FixService.PATH_CHECK_FAILED, threadReply());
    verifyNoGitWrites();
  }

  @Test
  void countsSeveralEditsOnOneFileAsOneFileAgainstTheCap() {
    findingThreadExists();
    prWithFooFile();
    gitDataSucceeds();
    when(fixConfig.maxEditedFiles()).thenReturn(1);
    modelReturns(
        """
        {"summary": "s", "edits": [
          {"file": "src/Foo.java", "operation": "replace",
           "search": "int x = 1;", "replace": "int x = 2;"},
          {"file": "src/Foo.java", "operation": "replace",
           "search": "public class Foo", "replace": "public final class Foo"}
        ], "notes": ""}
        """);

    service.execute(task());

    var tree = ArgumentCaptor.forClass(GitHubGitDataClient.CreateTreeRequest.class);
    verify(gitDataClient).createTree(any(), any(), eq("owner"), eq("repo"), tree.capture());
    assertEquals(1, tree.getValue().tree().size());
    assertTrue(tree.getValue().tree().get(0).content().contains("public final class Foo"));
    assertTrue(tree.getValue().tree().get(0).content().contains("int x = 2;"));
  }

  @Test
  void saysSoWithoutCallingTheModelWhenTheThreadRootIsNotTheBots() {
    // A /fix that opens its own inline thread: the root is the command itself, typed by a person.
    when(reviewClient.getPullRequestComment(
            any(), any(), eq("owner"), eq("repo"), eq(ROOT_COMMENT_ID)))
        .thenReturn(
            new GitHubReviewClient.PullRequestComment(
                ROOT_COMMENT_ID,
                null,
                "src/Foo.java",
                "/fix",
                new GitHubReviewClient.ReviewResponse.User("alice")));

    service.execute(task());

    assertEquals(FixService.NOT_A_FINDING, threadReply());
    verifyNoInteractions(fixGenerator, prClient);
    verifyNoGitWrites();
  }

  @Test
  void saysSoWhenTheThreadRootHasNoAuthor() {
    when(reviewClient.getPullRequestComment(
            any(), any(), eq("owner"), eq("repo"), eq(ROOT_COMMENT_ID)))
        .thenReturn(
            new GitHubReviewClient.PullRequestComment(
                ROOT_COMMENT_ID, null, "src/Foo.java", "orphaned", null));

    service.execute(task());

    assertEquals(FixService.NOT_A_FINDING, threadReply());
    verifyNoInteractions(fixGenerator);
  }

  @Test
  void repliesPushFailedWhenRefCreationIsRejected() {
    findingThreadExists();
    prWithFooFile();
    gitDataSucceeds();
    when(gitDataClient.createRef(any(), any(), eq("owner"), eq("repo"), any()))
        .thenThrow(new RuntimeException("403 Resource not accessible by integration"));
    modelReturns(
        """
        {"summary": "s", "edits": [
          {"file": "src/Foo.java", "operation": "replace",
           "search": "int x = 1;", "replace": "int x = 2;"}
        ], "notes": ""}
        """);

    service.execute(task());

    assertEquals(FixService.PUSH_FAILED, threadReply());
    verify(prClient, never()).createPullRequest(any(), any(), any(), any(), any());
  }

  @Test
  void repliesNoThreadWhenFindingCommentCannotBeLoaded() {
    when(reviewClient.getPullRequestComment(
            any(), any(), eq("owner"), eq("repo"), eq(ROOT_COMMENT_ID)))
        .thenThrow(new RuntimeException("404"));

    service.execute(task());

    assertEquals(FixService.NO_THREAD, threadReply());
    verifyNoGitWrites();
    verifyNoInteractions(fixGenerator);
  }

  @Test
  void repliesGenerationFailedWhenModelThrows() {
    findingThreadExists();
    prWithFooFile();
    when(fixGenerator.generate(any(), any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("provider 500"));

    service.execute(task());

    assertEquals(FixService.GENERATION_FAILED, threadReply());
    verifyNoGitWrites();
  }

  @Test
  void handleRunsOnTheReviewExecutor() {
    service.handle(task());
    verify(executor).execute(any());
    verifyNoInteractions(reviewClient);
  }
}
