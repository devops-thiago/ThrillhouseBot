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
package dev.thiagogonzaga.thrillhousebot.webhook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.PullRequestComment;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ReviewDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class CommentCommandServiceTest {

  @Mock private ExecutorService executor;
  @Mock private GitHubAuthClient authClient;
  @Mock private GitHubCommentClient commentClient;
  @Mock private GitHubReviewClient reviewClient;
  @Mock private ReviewThreadService reviewThreadService;
  @Mock private ReviewDispatcher reviewDispatcher;
  @Mock private ReviewSessionPersistence sessionPersistence;
  @Mock private PrPauseService prPauseService;
  @Mock private ManualReviewAuthorizer authorizer;

  private CommentCommandService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(authClient.getAuthHeader(anyLong())).thenReturn("token");
    // Run submitted work inline so the async handoff is exercised synchronously in tests.
    doAnswer(
            inv -> {
              inv.getArgument(0, Runnable.class).run();
              return null;
            })
        .when(executor)
        .execute(any(Runnable.class));
    service =
        new CommentCommandService(
            executor,
            authClient,
            commentClient,
            reviewClient,
            reviewThreadService,
            reviewDispatcher,
            sessionPersistence,
            prPauseService,
            authorizer,
            new TriggerDetector());
  }

  private CommentCommandService.CommandContext ctx(CommentCommand command) {
    return new CommentCommandService.CommandContext(
        command, "owner", "repo", 7, "main", 12345L, "octocat", "OWNER");
  }

  private void authorize(boolean allowed) {
    when(authorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER")).thenReturn(allowed);
  }

  private String postedBody() {
    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(any(), any(), eq("owner"), eq("repo"), eq(7), body.capture());
    return body.getValue().body();
  }

  @Test
  void helpPostsCommandListWithoutAuthorization() {
    service.handle(ctx(CommentCommand.HELP));

    assertEquals(CommentCommandService.HELP_TEXT, postedBody());
    verifyNoInteractions(authorizer);
    verify(reviewDispatcher, never()).dispatch(any());
  }

  @Test
  void summaryDispatchesReviewWhenNoSummaryExists() {
    authorize(true);
    when(prPauseService.isPaused("owner", "repo", 7)).thenReturn(false);
    when(sessionPersistence.hasCompletedReview("owner/repo", 7)).thenReturn(false);

    service.handle(ctx(CommentCommand.SUMMARY));

    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "owner", "repo", 7, "", "(manual summary)", "", "", "main", 12345L, true));
    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void summaryIsNoOpWhenSummaryAlreadyExists() {
    authorize(true);
    when(prPauseService.isPaused("owner", "repo", 7)).thenReturn(false);
    when(sessionPersistence.hasCompletedReview("owner/repo", 7)).thenReturn(true);

    service.handle(ctx(CommentCommand.SUMMARY));

    verify(reviewDispatcher, never()).dispatch(any());
    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void summaryPostsPausedNoticeWhenPaused() {
    authorize(true);
    when(prPauseService.isPaused("owner", "repo", 7)).thenReturn(true);

    service.handle(ctx(CommentCommand.SUMMARY));

    assertEquals(CommentCommandService.PAUSED_NOTICE, postedBody());
    verify(reviewDispatcher, never()).dispatch(any());
    verify(sessionPersistence, never()).hasCompletedReview(any(), anyInt());
  }

  @Test
  void summaryIgnoredWhenUnauthorized() {
    authorize(false);

    service.handle(ctx(CommentCommand.SUMMARY));

    verify(reviewDispatcher, never()).dispatch(any());
    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
    verifyNoInteractions(prPauseService);
  }

  @Test
  void resolveResolvesOnlyUnresolvedBotThreads() {
    authorize(true);
    var botRoot = comment(100L, null, "thrillhousebot[bot]");
    var botRoot2 = comment(102L, null, "thrillhousebot[bot]");
    var botReply = comment(101L, 100L, "thrillhousebot[bot]");
    var humanRoot = comment(200L, null, "octocat");
    var authorless = new PullRequestComment(300L, null, "src/Foo.java", "body", null);
    when(reviewClient.listPullRequestComments(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(botRoot, botRoot2, botReply, humanRoot, authorless));
    when(reviewThreadService.threadsByRootComment(any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(
            Map.of(
                100L, new ReviewThreadService.ThreadRef("T100", false),
                102L, new ReviewThreadService.ThreadRef("T102", false),
                200L, new ReviewThreadService.ThreadRef("T200", false)));
    when(reviewThreadService.resolve(any(), eq("T100"))).thenReturn(true);
    when(reviewThreadService.resolve(any(), eq("T102"))).thenReturn(false); // GitHub didn't confirm

    service.handle(ctx(CommentCommand.RESOLVE));

    // Both bot threads are attempted; only the confirmed one is counted. The human thread is left
    // alone, and the bot's reply (not a thread root) is ignored.
    verify(reviewThreadService).resolve(any(), eq("T100"));
    verify(reviewThreadService).resolve(any(), eq("T102"));
    verify(reviewThreadService, never()).resolve(any(), eq("T200"));
    assertTrue(postedBody().contains("Resolved 1"));
  }

  @Test
  void resolveReportsNothingToDoWhenNoBotThreads() {
    authorize(true);
    when(reviewClient.listPullRequestComments(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(comment(200L, null, "octocat")));

    service.handle(ctx(CommentCommand.RESOLVE));

    verify(reviewThreadService, never()).threadsByRootComment(any(), any(), any(), anyInt());
    assertTrue(postedBody().contains("No open"));
  }

  @Test
  void pausePersistsAndConfirms() {
    authorize(true);

    service.handle(ctx(CommentCommand.PAUSE));

    verify(prPauseService).pause("owner", "repo", 7);
    assertTrue(postedBody().contains("paused"));
  }

  @Test
  void pauseIgnoredWhenUnauthorized() {
    authorize(false);

    service.handle(ctx(CommentCommand.PAUSE));

    verify(prPauseService, never()).pause(any(), any(), anyInt());
    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void pauseStillConfirmsWhenConcurrentPauseWonTheInsertRace() {
    authorize(true);
    // The unique-constraint loser: pause() throws, but the winning concurrent /pause left the row,
    // so the PR is paused and the confirmation must still be posted.
    doThrow(new RuntimeException("unique constraint violation"))
        .when(prPauseService)
        .pause("owner", "repo", 7);
    when(prPauseService.isPaused("owner", "repo", 7)).thenReturn(true);

    service.handle(ctx(CommentCommand.PAUSE));

    assertTrue(postedBody().contains("paused"));
  }

  @Test
  void pauseDoesNotConfirmWhenInsertGenuinelyFailed() {
    authorize(true);
    doThrow(new RuntimeException("database unavailable"))
        .when(prPauseService)
        .pause("owner", "repo", 7);
    when(prPauseService.isPaused("owner", "repo", 7)).thenReturn(false);

    service.handle(ctx(CommentCommand.PAUSE));

    // A real failure (no row afterwards) propagates to the top-level handler; no confirmation.
    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void resumeClearsPauseAndConfirms() {
    authorize(true);
    when(prPauseService.resume("owner", "repo", 7)).thenReturn(true);

    service.handle(ctx(CommentCommand.RESUME));

    verify(prPauseService).resume("owner", "repo", 7);
    assertTrue(postedBody().contains("resumed"));
  }

  @Test
  void resumeReportsWhenNotPaused() {
    authorize(true);
    when(prPauseService.resume("owner", "repo", 7)).thenReturn(false);

    service.handle(ctx(CommentCommand.RESUME));

    assertTrue(postedBody().contains("was not paused"));
  }

  @Test
  void notifyPausedPostsTheNotice() {
    service.notifyPaused(ctx(CommentCommand.REVIEW));

    assertEquals(CommentCommandService.PAUSED_NOTICE, postedBody());
  }

  @Test
  void resolveContinuesWhenOneThreadResolutionThrows() {
    authorize(true);
    when(reviewClient.listPullRequestComments(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(
            List.of(
                comment(100L, null, "thrillhousebot[bot]"),
                comment(102L, null, "thrillhousebot[bot]")));
    when(reviewThreadService.threadsByRootComment(any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(
            Map.of(
                100L, new ReviewThreadService.ThreadRef("T100", false),
                102L, new ReviewThreadService.ThreadRef("T102", false)));
    when(reviewThreadService.resolve(any(), eq("T100"))).thenThrow(new RuntimeException("network"));
    when(reviewThreadService.resolve(any(), eq("T102"))).thenReturn(true);

    service.handle(ctx(CommentCommand.RESOLVE));

    // The failure on T100 must not stop T102 from being resolved.
    verify(reviewThreadService).resolve(any(), eq("T102"));
    assertTrue(postedBody().contains("Resolved 1"));
  }

  @Test
  void resolveSkipsAlreadyResolvedThreads() {
    authorize(true);
    when(reviewClient.listPullRequestComments(any(), any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(List.of(comment(100L, null, "thrillhousebot[bot]")));
    when(reviewThreadService.threadsByRootComment(any(), eq("owner"), eq("repo"), eq(7)))
        .thenReturn(Map.of(100L, new ReviewThreadService.ThreadRef("T100", true)));

    service.handle(ctx(CommentCommand.RESOLVE));

    // An already-resolved thread is not resolved again, and the message reflects nothing to do.
    verify(reviewThreadService, never()).resolve(any(), any());
    assertTrue(postedBody().contains("No open"));
  }

  @Test
  void resolveIgnoredWhenUnauthorized() {
    authorize(false);

    service.handle(ctx(CommentCommand.RESOLVE));

    verify(reviewClient, never()).listPullRequestComments(any(), any(), any(), any(), anyInt());
    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void resumeIgnoredWhenUnauthorized() {
    authorize(false);

    service.handle(ctx(CommentCommand.RESUME));

    verify(prPauseService, never()).resume(any(), any(), anyInt());
    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void executeSwallowsRuntimeExceptions() {
    when(authClient.getAuthHeader(anyLong())).thenThrow(new RuntimeException("boom"));

    // A failure inside the async task must be logged, not propagated to the executor thread.
    assertDoesNotThrow(() -> service.handle(ctx(CommentCommand.HELP)));
    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void executeIgnoresReviewCommand() {
    // /review is handled synchronously by the controller; the service's switch default is a no-op.
    service.execute(ctx(CommentCommand.REVIEW));

    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
    verify(reviewDispatcher, never()).dispatch(any());
  }

  @Test
  void notifyPausedSwallowsRuntimeExceptions() {
    when(authClient.getAuthHeader(anyLong())).thenThrow(new RuntimeException("boom"));

    assertDoesNotThrow(() -> service.notifyPaused(ctx(CommentCommand.REVIEW)));
    verify(commentClient, never()).createComment(any(), any(), any(), any(), anyInt(), any());
  }

  private static PullRequestComment comment(long id, Long inReplyToId, String login) {
    return new PullRequestComment(
        id, inReplyToId, "src/Foo.java", "body", new ReviewResponse.User(login));
  }
}
