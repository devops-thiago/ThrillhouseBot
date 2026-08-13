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

import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiOk;
import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiTruncated;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettings;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
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
  @Mock private RepoSettingsResolver repoSettingsResolver;

  // A real TriggerDetector — its bot-login check is the actual logic we want exercised.
  private final TriggerDetector triggerDetector = new TriggerDetector();

  private MaintainerReplyService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = serviceWith(diffFormatter);
    when(authClient.getAuthHeader(anyLong())).thenReturn(AUTH);
  }

  /**
   * Builds a service with the given diff formatter. The default suite uses the {@code @Mock} one
   * for interaction assertions; the per-repo-ignore and disclosure tests pass a <em>real</em>
   * {@link ReviewDiffFormatter} so the actual filtering and line-cap logic is exercised, not merely
   * a stubbed call.
   */
  private MaintainerReplyService serviceWith(ReviewDiffFormatter formatter) {
    return new MaintainerReplyService(
        authClient,
        authorizer,
        triggerDetector,
        reviewClient,
        commentClient,
        prClient,
        formatter,
        replyAssistant,
        repoSettingsResolver);
  }

  private static GitHubPullRequestClient.FileDiff fileDiff(
      String filename, String patch, int additions) {
    return new GitHubPullRequestClient.FileDiff(
        filename, "modified", additions, 0, additions, patch);
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
        .thenReturn(aiOk("Because foo can be null."));

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
        .thenReturn(aiOk("My take: looks fine."));

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
    when(diffFormatter.reviewableFiles(any(), any())).thenReturn(List.of());
    when(diffFormatter.buildDiffStringWithStats(any(), any()))
        .thenReturn(new ReviewDiffFormatter.FormattedDiff("diff --git a/Foo b/Foo", 0));
    when(replyAssistant.reply(any(), any(), any(), any(), any()))
        .thenReturn(aiOk("It is safe because..."));

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
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("   "));

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

  @Test
  void truncatedReplyIsSwallowedRatherThanPostedHalfWritten() {
    // #497: same swallow as any other failure, but reached by a distinct path — the reply was cut
    // at the model's cap, not rejected by the provider, and the log says so.
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any()))
        .thenReturn(aiTruncated("You are right that the retry loop"));

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
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenThrow(new RuntimeException("GitHub 503"));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("Still here."));

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
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("Answer."));

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
    verify(diffFormatter, never())
        .buildDiffStringWithStats(any(), any()); // never reached — fetch threw first
  }

  @Test
  void postFailureIsSwallowedByOuterHandler() {
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("answer"));
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
    when(diffFormatter.reviewableFiles(any(), any())).thenReturn(List.of());
    when(diffFormatter.buildDiffStringWithStats(any(), any()))
        .thenReturn(new ReviewDiffFormatter.FormattedDiff("diff", 0));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk(""));

    service.handle(mentionTask());

    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void botThreadReplyWithNullDiffHunkSendsEmptyCodeContext() {
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("ok"));
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
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(
            List.of(
                comment(99L, null, BOT, "**finding**"),
                commentNoUser(500L, 99L, "anon reply"), // no user object → rendered as @unknown
                comment(600L, 77L, "x", "different thread reply"), // belongs to another root (77)
                comment(1000L, 99L, "octocat", "Why?"))); // the triggering comment
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("ok"));

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
    // bot's thread, so an unmentioned reply on it is left alone.
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of(commentNoUser(99L, null, "author is null")));

    service.handle(reviewThreadTask(false));

    verifyNoInteractions(replyAssistant);
    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void nullAssistantReplyPostsNothing() {
    authorize();
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of(comment(99L, null, BOT, "**HIGH — bug**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(null);

    service.handle(reviewThreadTask(false));

    verify(reviewClient, never())
        .replyToReviewComment(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void findRootScansPastNonMatchingComments() {
    authorize();
    // The root is not first in the list, so the id filter must skip a non-matching comment first.
    when(reviewClient.listPullRequestComments(
            eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(
            List.of(
                comment(500L, 88L, "x", "unrelated thread"),
                comment(99L, null, BOT, "**finding**")));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("ok"));

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
    when(diffFormatter.reviewableFiles(any(), any())).thenReturn(List.of());
    when(diffFormatter.buildDiffStringWithStats(any(), any()))
        .thenReturn(new ReviewDiffFormatter.FormattedDiff("d", 0));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("ok"));
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

  // A file reviewable under the (empty) global list but excluded only by a per-repo pattern: its
  // patch must not reach the model. Uses a real formatter so the actual glob filtering is
  // exercised.
  @Test
  void mentionExcludesFileMatchedOnlyByPerRepoIgnorePattern() {
    authorize();
    // Empty global ignore list: secret.txt is reviewable globally, excluded only per-repo.
    var realService = serviceWith(new ReviewDiffFormatter(List.of(), 5000));
    when(prClient.getPullRequestFiles(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(
            List.of(
                fileDiff("src/Foo.java", "@@ -1 +1 @@\n-old\n+VISIBLE_CHANGE", 1),
                fileDiff("vendor/secret.txt", "@@ -0,0 +1 @@\n+TOPSECRET_VENDORED_KEY", 1)));
    when(repoSettingsResolver.resolve(eq("owner"), eq("repo"), any(), anyLong()))
        .thenReturn(new RepoSettings(List.of("vendor/**"), List.of(), "src"));
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("Answer."));

    realService.handle(mentionTask());

    var codeContext = ArgumentCaptor.forClass(String.class);
    verify(replyAssistant).reply(any(), any(), any(), codeContext.capture(), any());
    assertTrue(
        codeContext.getValue().contains("VISIBLE_CHANGE"), "reviewable file's patch is sent");
    assertFalse(
        codeContext.getValue().contains("TOPSECRET_VENDORED_KEY"),
        "per-repo-ignored file's patch must not reach the model");
  }

  // A repository that declares no config resolves to the global list only — behaves exactly as
  // before, so a globally-reviewable file's patch is still sent.
  @Test
  void mentionWithNoRepoConfigSendsGloballyReviewableFile() {
    authorize();
    var realService = serviceWith(new ReviewDiffFormatter(List.of(), 5000));
    when(prClient.getPullRequestFiles(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(
            List.of(fileDiff("vendor/secret.txt", "@@ -0,0 +1 @@\n+TOPSECRET_VENDORED_KEY", 1)));
    // No repo config: resolver returns EMPTY (the default mock returns null → SoftLoaders → EMPTY).
    when(repoSettingsResolver.resolve(eq("owner"), eq("repo"), any(), anyLong()))
        .thenReturn(RepoSettings.EMPTY);
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("Answer."));

    realService.handle(mentionTask());

    var codeContext = ArgumentCaptor.forClass(String.class);
    verify(replyAssistant).reply(any(), any(), any(), codeContext.capture(), any());
    assertTrue(
        codeContext.getValue().contains("TOPSECRET_VENDORED_KEY"),
        "with no per-repo ignore, a globally-reviewable file is still sent (behaves as before)");
  }

  // A diff over max-diff-lines must produce a reply that discloses the omission — the surface could
  // not say so before. Real formatter with a tiny line cap forces truncation.
  @Test
  void mentionDisclosesTruncationOnLargePr() {
    authorize();
    // A 3-line cap against two multi-line files forces at least one omitted file.
    var realService = serviceWith(new ReviewDiffFormatter(List.of(), 3));
    when(prClient.getPullRequestFiles(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(
            List.of(
                fileDiff("src/A.java", "@@ -1 +1 @@\n-a\n+aa\n+aaa\n+aaaa", 3),
                fileDiff("src/B.java", "@@ -1 +1 @@\n-b\n+bb\n+bbb\n+bbbb", 3),
                fileDiff("src/C.java", "@@ -1 +1 @@\n-c\n+cc\n+ccc\n+cccc", 3)));
    when(repoSettingsResolver.resolve(eq("owner"), eq("repo"), any(), anyLong()))
        .thenReturn(RepoSettings.EMPTY);
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("Answer."));

    realService.handle(mentionTask());

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), body.capture());
    assertTrue(
        body.getValue().body().contains("partial coverage"),
        "a truncated diff must disclose the omission in the posted reply");
  }

  private MaintainerReplyService.ReplyTask mentionTask(String question) {
    return mentionTask(question, "OWNER");
  }

  private MaintainerReplyService.ReplyTask mentionTask(String question, String authorAssociation) {
    return new MaintainerReplyService.ReplyTask(
        "owner",
        "repo",
        42,
        12345L,
        "octocat",
        authorAssociation,
        question,
        "PR Title",
        "PR body",
        false,
        null,
        2000L,
        true,
        null);
  }

  @Test
  void clearDirectiveIsAcknowledgedDeterministicallyWithoutTheAssistant() {
    authorize();

    service.handle(
        mentionTask("@thrillhousebot resolved `src/A.java:10` — SQL injection, fixed in abc123"));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), body.capture());
    assertEquals(MaintainerReplyService.CLEAR_DIRECTIVE_ACK, body.getValue().body());
    verifyNoInteractions(replyAssistant, prClient);
  }

  /**
   * The reply runs before any review, with no prior round loaded, so it cannot know whether a
   * locator matches a real finding — but a directive carrying no locator at all provably clears
   * nothing, and saying so beats leaving a maintainer who mistyped it to infer the failure from a
   * review that changes nothing.
   */
  @Test
  void clearDirectiveNamingNoLocatorSaysNothingWillBeCleared() {
    authorize();

    service.handle(mentionTask("@thrillhousebot resolved the null check thing, it's fine now"));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), body.capture());
    assertEquals(MaintainerReplyService.CLEAR_DIRECTIVE_NO_LOCATOR_ACK, body.getValue().body());
    assertTrue(
        body.getValue().body().contains("nothing will be cleared"),
        "the maintainer must not read this as a confirmation");
    verifyNoInteractions(replyAssistant, prClient);
  }

  /**
   * The manual-trigger allowlist admits a commenter to the mention path whatever their {@code
   * author_association}, but the clearing path requires a write-capable one. The ack must not
   * promise a closure that gate will refuse.
   */
  @Test
  void clearDirectiveFromACommenterWhoCannotClearSaysNothingWillBeCleared() {
    authorize();

    service.handle(
        mentionTask("@thrillhousebot resolved `src/A.java:10` — SQL injection", "CONTRIBUTOR"));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), body.capture());
    assertEquals(MaintainerReplyService.CLEAR_DIRECTIVE_UNAUTHORIZED_ACK, body.getValue().body());
    assertTrue(
        body.getValue().body().contains("nothing will be cleared"),
        "the commenter must not read this as a promise the finding closes");
    verifyNoInteractions(replyAssistant, prClient);
  }

  @Test
  void clearDirectiveAckNeverClaimsAClearingAlreadyHappened() {
    // The ack states what the next review will evaluate; it must not report an outcome it cannot
    // know, and must not open with a bare confirmation.
    for (var ack :
        List.of(
            MaintainerReplyService.CLEAR_DIRECTIVE_ACK,
            MaintainerReplyService.CLEAR_DIRECTIVE_NO_LOCATOR_ACK,
            MaintainerReplyService.CLEAR_DIRECTIVE_UNAUTHORIZED_ACK)) {
      assertFalse(ack.startsWith("Noted"), ack);
      assertFalse(ack.contains("has been cleared"), ack);
      assertFalse(ack.contains("is cleared"), ack);
    }
  }

  @Test
  void anOrdinaryMentionStillGetsAnAssistantAnswer() {
    authorize();
    when(prClient.getPullRequestFiles(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42)))
        .thenReturn(List.of(fileDiff("src/A.java", "@@ -1 +1 @@\n-a\n+aa", 1)));
    when(repoSettingsResolver.resolve(eq("owner"), eq("repo"), any(), anyLong()))
        .thenReturn(RepoSettings.EMPTY);
    when(replyAssistant.reply(any(), any(), any(), any(), any())).thenReturn(aiOk("Answer."));

    service.handle(mentionTask("@thrillhousebot did you resolve this already?"));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(eq(AUTH), anyString(), eq("owner"), eq("repo"), eq(42), body.capture());
    assertEquals("Answer.", body.getValue().body());
  }
}
