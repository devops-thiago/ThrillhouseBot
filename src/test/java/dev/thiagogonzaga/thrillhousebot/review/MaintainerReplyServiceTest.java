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

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReplyAssistant;
import dev.thiagogonzaga.thrillhousebot.webhook.ManualReviewAuthorizer;
import dev.thiagogonzaga.thrillhousebot.webhook.TriggerDetector;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MaintainerReplyServiceTest {

  private static final String BOT = "thrillhousebot[bot]";
  private static final String AUTH = "token gh-abc";

  @Mock private GitHubAuthClient authClient;
  @Mock private ManualReviewAuthorizer authorizer;
  @Mock private GitHubReviewClient reviewClient;
  @Mock private GitHubCommentClient commentClient;
  @Mock private GitHubPullRequestClient prClient;
  @Mock private ReviewDiffFormatter diffFormatter;
  @Mock private ReplyAssistant replyAssistant;

  // A real TriggerDetector — its bot-login check is the actual logic we want exercised.
  private final TriggerDetector triggerDetector = new TriggerDetector();

  private MaintainerReplyService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new MaintainerReplyService(
            authClient,
            authorizer,
            triggerDetector,
            reviewClient,
            commentClient,
            prClient,
            diffFormatter,
            replyAssistant);
    when(authClient.getAuthHeader(anyLong())).thenReturn(AUTH);
  }

  private static GitHubReviewClient.PullRequestComment comment(
      long id, Long inReplyToId, String login, String body) {
    return new GitHubReviewClient.PullRequestComment(
        id, inReplyToId, "src/Foo.java", body, new GitHubReviewClient.ReviewResponse.User(login));
  }

  private MaintainerReplyService.ReplyTask reviewThreadTask(boolean mentioned) {
    return new MaintainerReplyService.ReplyTask(
        "owner",
        "repo",
        42,
        12345L,
        "octocat",
        "OWNER",
        "Why is this flagged?",
        "PR Title",
        "PR body",
        true,
        99L,
        1000L,
        mentioned,
        "@@ -1 +1 @@ context");
  }

  private MaintainerReplyService.ReplyTask mentionTask() {
    return new MaintainerReplyService.ReplyTask(
        "owner",
        "repo",
        42,
        12345L,
        "octocat",
        "OWNER",
        "@thrillhousebot is this safe?",
        "PR Title",
        "PR body",
        false,
        null,
        2000L,
        true,
        null);
  }

  private void authorize() {
    when(authorizer.isAuthorized(any(), any(), anyLong(), any(), any())).thenReturn(true);
  }

  @Test
  void unauthorizedRequestPostsNothing() {
    when(authorizer.isAuthorized(any(), any(), anyLong(), any(), any())).thenReturn(false);

    service.handle(reviewThreadTask(false));

    verifyNoInteractions(reviewClient, commentClient, replyAssistant);
    verify(authClient, never()).getAuthHeader(anyLong());
  }

  @Test
  void replyOnBotThreadPostsAnswerWithFindingAndPriorRepliesAsContext() {
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(
            List.of(
                comment(99L, null, BOT, "**CRITICAL — possible NPE** on user lookup"),
                comment(500L, 99L, "maintainer", "are you sure?"),
                comment(1000L, 99L, "octocat", "Why is this flagged?")));
    when(replyAssistant.reply(any(), any(), any(), any(), any()))
        .thenReturn("Because foo can be null.");

    service.handle(reviewThreadTask(false));

    var question = ArgumentCaptor.forClass(String.class);
    var prContext = ArgumentCaptor.forClass(String.class);
    var finding = ArgumentCaptor.forClass(String.class);
    var codeContext = ArgumentCaptor.forClass(String.class);
    var thread = ArgumentCaptor.forClass(String.class);
    verify(replyAssistant)
        .reply(
            question.capture(),
            prContext.capture(),
            finding.capture(),
            codeContext.capture(),
            thread.capture());

    assertTrue(question.getValue().contains("Why is this flagged?"));
    assertTrue(finding.getValue().contains("possible NPE"), "finding carries the bot's comment");
    assertTrue(
        codeContext.getValue().contains("@@ -1 +1 @@"), "code context carries the diff hunk");
    // The prior maintainer reply is context; the triggering comment is shown only as the question.
    assertTrue(thread.getValue().contains("are you sure?"), "prior reply is in the thread");
    assertFalse(
        thread.getValue().contains("Why is this flagged?"),
        "triggering comment excluded from thread");

    var reply = ArgumentCaptor.forClass(GitHubReviewClient.ReplyToReviewCommentRequest.class);
    verify(reviewClient)
        .replyToReviewComment(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(99L), reply.capture());
    assertEquals("Because foo can be null.", reply.getValue().body());
    verifyNoInteractions(commentClient);
  }

  @Test
  void replyOnHumanThreadWithoutMentionPostsNothing() {
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(
            List.of(
                comment(99L, null, "someone", "I think this is wrong"),
                comment(1000L, 99L, "octocat", "Why is this flagged?")));

    service.handle(reviewThreadTask(false));

    verifyNoInteractions(replyAssistant);
    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void replyOnHumanThreadWithMentionStillAnswers() {
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of(comment(99L, null, "someone", "what does the bot think?")));
    when(replyAssistant.reply(any(), any(), any(), any(), any()))
        .thenReturn("My take: looks fine.");

    service.handle(reviewThreadTask(true));

    // No bot finding to cite, but the explicit mention still gets answered.
    var finding = ArgumentCaptor.forClass(String.class);
    verify(replyAssistant).reply(any(), any(), finding.capture(), any(), any());
    assertTrue(finding.getValue() == null || finding.getValue().isEmpty());
    verify(reviewClient)
        .replyToReviewComment(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(99L), any());
  }

  @Test
  void mentionFetchesDiffAndPostsIssueComment() {
    authorize();
    when(prClient.getPullRequestFiles(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of());
    when(diffFormatter.buildDiffString(any())).thenReturn("diff --git a/Foo b/Foo");
    when(replyAssistant.reply(any(), any(), any(), any(), any()))
        .thenReturn("It is safe because...");

    service.handle(mentionTask());

    var codeContext = ArgumentCaptor.forClass(String.class);
    verify(replyAssistant).reply(any(), any(), any(), codeContext.capture(), any());
    assertTrue(codeContext.getValue().contains("diff --git"));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), body.capture());
    assertEquals("It is safe because...", body.getValue().body());
    verifyNoInteractions(reviewClient);
  }

  @Test
  void blankAssistantReplyPostsNothing() {
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("   ");

    service.handle(reviewThreadTask(false));

    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void assistantFailureIsSwallowed() {
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("model down"));

    assertDoesNotThrow(() -> service.handle(reviewThreadTask(false)));

    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }
}
