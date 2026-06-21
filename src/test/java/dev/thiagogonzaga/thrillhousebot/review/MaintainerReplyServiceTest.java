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
import java.util.ArrayList;
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

  /** A comment with no {@code user} object at all (e.g. a deleted account). */
  private static GitHubReviewClient.PullRequestComment commentNoUser(
      long id, Long inReplyToId, String body) {
    return new GitHubReviewClient.PullRequestComment(id, inReplyToId, "src/Foo.java", body, null);
  }

  /**
   * Stubs the cheap single-GET pre-filter to return {@code root} as the thread's root comment, so
   * an unmentioned review-thread reply gets past the bot-thread check and on to the heavier work.
   */
  private void stubRootComment(GitHubReviewClient.PullRequestComment root) {
    when(reviewClient.getReviewComment(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(root.id())))
        .thenReturn(root);
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
  void unauthorizedMentionPostsNothing() {
    when(authorizer.isAuthorized(any(), any(), anyLong(), any(), any())).thenReturn(false);

    service.handle(mentionTask());

    // The mention path rejects before minting a token or touching any GitHub client.
    verifyNoInteractions(reviewClient, commentClient, replyAssistant);
    verify(authClient, never()).getAuthHeader(anyLong());
  }

  @Test
  void unauthorizedMentionedReviewReplyPostsNothing() {
    when(authorizer.isAuthorized(any(), any(), anyLong(), any(), any())).thenReturn(false);

    // An explicit mention skips the bot-thread pre-filter, so the authorizer is the gate; once it
    // denies, the bot must not list comments or post anything.
    service.handle(reviewThreadTask(true));

    verifyNoInteractions(replyAssistant, commentClient);
    verify(reviewClient, never())
        .listPullRequestComments(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void unmentionedReplyOnHumanThreadSkipsBeforeListingAndAuthorizing() {
    // The cheap single-GET pre-filter sees a human-authored root and bails before the authorizer
    // round-trip and the paginated comment list — a human-to-human reply must not amplify API
    // traffic.
    stubRootComment(comment(99L, null, "someone", "I think this is wrong"));

    service.handle(reviewThreadTask(false));

    verify(reviewClient).getReviewComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(99L));
    verify(reviewClient, never())
        .listPullRequestComments(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
    verifyNoInteractions(authorizer, replyAssistant, commentClient);
    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void preFilterFailSoftSkipsWhenRootFetchThrows() {
    // If the single-GET root lookup fails, an unmentioned reply is treated as not-the-bot's thread
    // and left alone rather than falling through to the expensive list.
    when(reviewClient.getReviewComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(99L)))
        .thenThrow(new RuntimeException("GitHub 503"));

    service.handle(reviewThreadTask(false));

    verify(reviewClient, never())
        .listPullRequestComments(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
    verifyNoInteractions(replyAssistant);
    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void replyOnBotThreadPostsAnswerWithFindingAndPriorRepliesAsContext() {
    authorize();
    stubRootComment(comment(99L, null, BOT, "**CRITICAL — possible NPE** on user lookup"));
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
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
  void walksAllCommentPagesToFindARootBeyondTheFirstPage() {
    authorize();
    stubRootComment(comment(99L, null, BOT, "**finding** on page two"));
    // Page 1 is full (100 comments) so a second page is fetched; the bot finding root is on page 2.
    var firstPage = new ArrayList<GitHubReviewClient.PullRequestComment>();
    for (int i = 0; i < 100; i++) {
      firstPage.add(comment(2000L + i, 77L, "octocat", "noise " + i));
    }
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(100), eq(1)))
        .thenReturn(firstPage);
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(100), eq(2)))
        .thenReturn(List.of(comment(99L, null, BOT, "**finding** on page two")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("Answer.");

    service.handle(reviewThreadTask(false));

    // Root comment 99 lived on page 2; without pagination the bot would have stayed silent.
    verify(reviewClient)
        .listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(100), eq(2));
    verify(reviewClient)
        .replyToReviewComment(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(99L), any());
  }

  @Test
  void unmentionedReplyWithUnresolvableRootPostsNothing() {
    authorize();
    // The single-GET pre-filter returns null (e.g. the root was deleted), so a non-mention reply is
    // treated as not the bot's thread and the bot stays silent without paging the comment list.
    when(reviewClient.getReviewComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(99L)))
        .thenReturn(null);

    service.handle(reviewThreadTask(false));

    verifyNoInteractions(replyAssistant);
    verify(reviewClient, never())
        .listPullRequestComments(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void stopsAtTheCommentPageBoundOnAVeryLongThread() {
    authorize();
    // Every page is full, so only the page bound can stop the walk. Use an explicit mention so the
    // bot-thread pre-filter is bypassed and the full paginated walk is exercised.
    var fullPage = new ArrayList<GitHubReviewClient.PullRequestComment>();
    for (int i = 0; i < 100; i++) {
      fullPage.add(comment(3000L + i, 88L, "octocat", "noise " + i));
    }
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
        .thenReturn(fullPage);

    service.handle(reviewThreadTask(true));

    verify(reviewClient, times(10))
        .listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt());
  }

  @Test
  void replyOnHumanThreadWithoutMentionPostsNothing() {
    authorize();
    // The thread root is human-authored, so the pre-filter leaves an unmentioned reply alone.
    stubRootComment(comment(99L, null, "someone", "I think this is wrong"));

    service.handle(reviewThreadTask(false));

    verifyNoInteractions(replyAssistant);
    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void replyOnHumanThreadWithMentionStillAnswers() {
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
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
    stubRootComment(comment(99L, null, BOT, "**HIGH — bug**"));
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("   ");

    service.handle(reviewThreadTask(false));

    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void assistantFailureIsSwallowed() {
    authorize();
    stubRootComment(comment(99L, null, BOT, "**HIGH — bug**"));
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("model down"));

    assertDoesNotThrow(() -> service.handle(reviewThreadTask(false)));

    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void mentionInlineCommentWithoutResolvableRootPostsNothing() {
    authorize();
    // reviewThread mention with no in_reply_to root id — nothing to reply under.
    var task =
        new MaintainerReplyService.ReplyTask(
            "owner",
            "repo",
            42,
            12345L,
            "octocat",
            "OWNER",
            "@thrillhousebot wat",
            "t",
            "b",
            true,
            null,
            7L,
            true,
            "@@ hunk");

    service.handle(task);

    verifyNoInteractions(replyAssistant);
    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void listCommentsFailureStillAnswersAnExplicitMention() {
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
        .thenThrow(new RuntimeException("GitHub 503"));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("Still here.");

    service.handle(reviewThreadTask(true));

    // The root could not be loaded, but the explicit mention is still answered (finding is empty).
    verify(reviewClient)
        .replyToReviewComment(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(99L), any());
  }

  @Test
  void mentionStillRepliesWhenDiffFetchFailsAndPrContextIsBlank() {
    authorize();
    when(prClient.getPullRequestFiles(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenThrow(new RuntimeException("files 500"));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("Answer.");

    // Null title and description exercise the blank-PR-context branches.
    var task =
        new MaintainerReplyService.ReplyTask(
            "owner",
            "repo",
            42,
            12345L,
            "octocat",
            "OWNER",
            "@thrillhousebot hi",
            null,
            null,
            false,
            null,
            2000L,
            true,
            null);

    service.handle(task);

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), body.capture());
    assertEquals("Answer.", body.getValue().body());
    verify(diffFormatter, never()).buildDiffString(any()); // never reached — fetch threw first
  }

  @Test
  void postFailureIsSwallowedByOuterHandler() {
    authorize();
    stubRootComment(comment(99L, null, BOT, "**HIGH — bug**"));
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("answer");
    doThrow(new RuntimeException("GitHub 422"))
        .when(reviewClient)
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());

    // The post itself blowing up must be swallowed by handle()'s outer guard.
    assertDoesNotThrow(() -> service.handle(reviewThreadTask(false)));
  }

  @Test
  void mentionWithBlankReplyPostsNothing() {
    authorize();
    when(prClient.getPullRequestFiles(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of());
    when(diffFormatter.buildDiffString(any())).thenReturn("diff");
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("");

    service.handle(mentionTask());

    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void botThreadReplyWithNullDiffHunkSendsEmptyCodeContext() {
    authorize();
    stubRootComment(comment(99L, null, BOT, "**HIGH — bug**"));
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("ok");
    var task =
        new MaintainerReplyService.ReplyTask(
            "owner", "repo", 42, 12345L, "octocat", "OWNER", "why?", "t", "b", true, 99L, 1000L,
            false, null);

    service.handle(task);

    var codeContext = ArgumentCaptor.forClass(String.class);
    verify(replyAssistant).reply(any(), any(), any(), codeContext.capture(), any());
    assertTrue(codeContext.getValue().isEmpty(), "null diff hunk yields empty code context");
    verify(reviewClient)
        .replyToReviewComment(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(99L), any());
  }

  @Test
  void threadRenderingSkipsOtherThreadsAndHandlesAnonymousReplies() {
    authorize();
    stubRootComment(comment(99L, null, BOT, "**finding**"));
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                comment(99L, null, BOT, "**finding**"),
                commentNoUser(500L, 99L, "anon reply"), // no user object → rendered as @unknown
                comment(600L, 77L, "x", "different thread reply"), // belongs to another root (77)
                comment(1000L, 99L, "octocat", "Why?"))); // the triggering comment
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("ok");

    service.handle(reviewThreadTask(false));

    var thread = ArgumentCaptor.forClass(String.class);
    verify(replyAssistant).reply(any(), any(), any(), any(), thread.capture());
    assertTrue(thread.getValue().contains("@unknown"), "anonymous reply rendered as @unknown");
    assertTrue(thread.getValue().contains("anon reply"));
    assertFalse(
        thread.getValue().contains("different thread"), "a reply on another root is excluded");
    assertFalse(thread.getValue().contains("Why?"), "triggering comment excluded");
    verify(reviewClient)
        .replyToReviewComment(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(99L), any());
  }

  @Test
  void rootWithNullAuthorIsNotTreatedAsBotThread() {
    authorize();
    // The root exists but its author is unknown (e.g. deleted account): it must not count as the
    // bot's thread, so an unmentioned reply on it is left alone by the pre-filter.
    stubRootComment(commentNoUser(99L, null, "author is null"));

    service.handle(reviewThreadTask(false));

    verifyNoInteractions(replyAssistant);
    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void nullAssistantReplyPostsNothing() {
    authorize();
    stubRootComment(comment(99L, null, BOT, "**HIGH — bug**"));
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(null);

    service.handle(reviewThreadTask(false));

    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void nullCommentListIsTreatedAsEmpty() {
    authorize();
    // GitHub returning a null body (vs an empty list) must not NPE — it falls back to no context.
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
        .thenReturn(null);
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("ok");

    // Mentioned, so it still answers even though the root could not be loaded.
    service.handle(reviewThreadTask(true));

    verify(reviewClient)
        .replyToReviewComment(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(99L), any());
  }

  @Test
  void findRootScansPastNonMatchingComments() {
    authorize();
    stubRootComment(comment(99L, null, BOT, "**finding**"));
    // The root is not first in the list, so the id filter must skip a non-matching comment first.
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                comment(500L, 88L, "x", "unrelated thread"),
                comment(99L, null, BOT, "**finding**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("ok");

    service.handle(reviewThreadTask(false));

    verify(reviewClient)
        .replyToReviewComment(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), eq(99L), any());
  }

  @Test
  void blankButNonNullPrContextFieldsAreOmitted() {
    authorize();
    when(prClient.getPullRequestFiles(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of());
    when(diffFormatter.buildDiffString(any())).thenReturn("d");
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn("ok");
    // Whitespace-only (non-null) title and description exercise the !isBlank() branch.
    var task =
        new MaintainerReplyService.ReplyTask(
            "owner",
            "repo",
            42,
            12345L,
            "octocat",
            "OWNER",
            "@thrillhousebot hi",
            "   ",
            "   ",
            false,
            null,
            2000L,
            true,
            null);

    service.handle(task);

    verify(commentClient)
        .createComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), any());
  }
}
