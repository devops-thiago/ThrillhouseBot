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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReactionClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class FindingFeedbackCaptureServiceTest {

  @Mock private FindingFeedbackService feedbackService;
  @Mock private GitHubAuthClient authClient;
  @Mock private GitHubReactionClient reactionClient;
  @Mock private GitHubReviewClient reviewClient;

  private FindingFeedbackCaptureService capture;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    capture =
        new FindingFeedbackCaptureService(
            feedbackService,
            BotIdentity.of("thrillhousebot[bot]"),
            authClient,
            reactionClient,
            reviewClient);
  }

  @Test
  void captureReactionsRecordsPlusAndMinusOneFromNonBotUsers() {
    when(reactionClient.listReviewCommentReactions(
            anyString(), anyString(), eq("owner"), eq("repo"), eq(99L), eq("+1"), eq(100)))
        .thenReturn(
            List.of(
                new GitHubReactionClient.Reaction(
                    1L, "+1", new GitHubReactionClient.Reaction.User("octocat", 1), "t"),
                new GitHubReactionClient.Reaction(
                    2L,
                    "+1",
                    new GitHubReactionClient.Reaction.User("thrillhousebot[bot]", 2),
                    "t")));
    when(reactionClient.listReviewCommentReactions(
            anyString(), anyString(), eq("owner"), eq("repo"), eq(99L), eq("-1"), eq(100)))
        .thenReturn(
            List.of(
                new GitHubReactionClient.Reaction(
                    3L, "-1", new GitHubReactionClient.Reaction.User("alice", 3), "t")));

    var body = "finding\n" + SuggestionFormatter.findingMarker(2);
    capture.captureReactions("Bearer t", "owner", "repo", 7, 99L, body);

    verify(feedbackService)
        .record(
            "owner/repo",
            7,
            99L,
            2,
            FindingFeedback.SIGNAL_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "octocat",
            1L);
    verify(feedbackService)
        .record(
            "owner/repo",
            7,
            99L,
            2,
            FindingFeedback.SIGNAL_NOT_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "alice",
            3L);
    verify(feedbackService, never())
        .record(any(), anyInt(), anyLong(), any(), any(), any(), eq("thrillhousebot[bot]"), any());
  }

  @Test
  void captureReactionsSkipsCommentsWithoutFindingMarker() {
    capture.captureReactions("Bearer t", "owner", "repo", 7, 99L, "just a human comment");
    verifyNoInteractions(reactionClient);
    verifyNoInteractions(feedbackService);
  }

  @Test
  void captureOnReviewReplyFetchesRootAndRecordsHeuristic() {
    when(authClient.getAuthHeader(9L)).thenReturn("Bearer t");
    when(reviewClient.getPullRequestComment(
            anyString(), anyString(), eq("owner"), eq("repo"), eq(99L)))
        .thenReturn(
            new GitHubReviewClient.PullRequestComment(
                99L,
                null,
                "Main.java",
                "**HIGH — Bug**\n" + SuggestionFormatter.findingMarker(1),
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
    when(reactionClient.listReviewCommentReactions(
            any(), any(), any(), any(), anyLong(), any(), anyInt()))
        .thenReturn(List.of());

    capture.captureOnReviewReply(
        9L, "owner", "repo", 7, 99L, "octocat", "this is a false positive");

    verify(feedbackService)
        .record(
            "owner/repo",
            7,
            99L,
            1,
            FindingFeedback.SIGNAL_NOT_USEFUL,
            FindingFeedback.SOURCE_REPLY_HEURISTIC,
            "octocat",
            null);
  }

  @Test
  void captureOnReviewReplyIgnoresNonFindingThreads() {
    when(authClient.getAuthHeader(9L)).thenReturn("Bearer t");
    when(reviewClient.getPullRequestComment(
            anyString(), anyString(), eq("owner"), eq("repo"), eq(99L)))
        .thenReturn(
            new GitHubReviewClient.PullRequestComment(
                99L,
                null,
                "Main.java",
                "human review note",
                new GitHubReviewClient.ReviewResponse.User("alice")));

    capture.captureOnReviewReply(9L, "owner", "repo", 7, 99L, "octocat", "not useful");

    verifyNoInteractions(reactionClient);
    verifyNoInteractions(feedbackService);
  }

  @Test
  void captureOnPriorFindingsNoopsOnEmptyInput() {
    capture.captureOnPriorFindings("Bearer t", "owner", "repo", 3, null);
    capture.captureOnPriorFindings("Bearer t", "owner", "repo", 3, List.of());
    verifyNoInteractions(reactionClient, feedbackService);
  }

  @Test
  void captureOnPriorFindingsScansAllBotFindingRootsAcrossRounds() {
    when(reactionClient.listReviewCommentReactions(
            any(), any(), any(), any(), anyLong(), any(), anyInt()))
        .thenReturn(List.of());
    // Round-1 finding (id 10) plus a later-round finding (id 30); a human reply and a
    // non-finding bot root must be ignored. Empty previous-AI JSON is irrelevant — we scan
    // comments directly so reactions on older rounds are still polled.
    var comments =
        List.of(
            new GitHubReviewClient.PullRequestComment(
                10L,
                null,
                "A.java",
                "old\n" + SuggestionFormatter.findingMarker(1),
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")),
            new GitHubReviewClient.PullRequestComment(
                11L,
                10L,
                "A.java",
                "human reply",
                new GitHubReviewClient.ReviewResponse.User("octocat")),
            new GitHubReviewClient.PullRequestComment(
                20L,
                null,
                "B.java",
                "summary without marker",
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")),
            new GitHubReviewClient.PullRequestComment(
                30L,
                null,
                "C.java",
                "new\n" + SuggestionFormatter.findingMarker(1),
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")),
            new GitHubReviewClient.PullRequestComment(
                40L,
                null,
                "D.java",
                "human\n" + SuggestionFormatter.findingMarker(9),
                new GitHubReviewClient.ReviewResponse.User("alice")));

    capture.captureOnPriorFindings("Bearer t", "owner", "repo", 3, comments);

    verify(reactionClient, times(2))
        .listReviewCommentReactions(
            eq("Bearer t"), anyString(), eq("owner"), eq("repo"), eq(10L), anyString(), eq(100));
    verify(reactionClient, times(2))
        .listReviewCommentReactions(
            eq("Bearer t"), anyString(), eq("owner"), eq("repo"), eq(30L), anyString(), eq(100));
    verify(reactionClient, never())
        .listReviewCommentReactions(any(), any(), any(), any(), eq(11L), anyString(), anyInt());
    verify(reactionClient, never())
        .listReviewCommentReactions(any(), any(), any(), any(), eq(20L), anyString(), anyInt());
    verify(reactionClient, never())
        .listReviewCommentReactions(any(), any(), any(), any(), eq(40L), anyString(), anyInt());
  }

  @Test
  void listReactionsFailureIsSwallowed() {
    when(reactionClient.listReviewCommentReactions(
            anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyInt()))
        .thenThrow(new RuntimeException("api down"));
    capture.captureReactions(
        "Bearer t", "owner", "repo", 7, 99L, "x\n" + SuggestionFormatter.findingMarker(1));
    verify(feedbackService, never())
        .record(any(), anyInt(), anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void fetchCommentBodyFailureSkipsCapture() {
    when(authClient.getAuthHeader(9L)).thenReturn("Bearer t");
    when(reviewClient.getPullRequestComment(any(), any(), any(), any(), anyLong()))
        .thenThrow(new RuntimeException("gone"));
    capture.captureOnReviewReply(9L, "owner", "repo", 7, 99L, "octocat", "not useful");
    verifyNoInteractions(reactionClient, feedbackService);
  }

  @Test
  void captureReactionsSkipsNullOrIncompleteReactions() {
    when(reactionClient.listReviewCommentReactions(
            anyString(), anyString(), eq("owner"), eq("repo"), eq(99L), eq("+1"), eq(100)))
        .thenReturn(
            java.util.Arrays.asList(
                null,
                new GitHubReactionClient.Reaction(1L, "+1", null, "t"),
                new GitHubReactionClient.Reaction(
                    2L, "+1", new GitHubReactionClient.Reaction.User(null, 2), "t"),
                new GitHubReactionClient.Reaction(
                    3L, "+1", new GitHubReactionClient.Reaction.User("bob", 3), "t")));
    when(reactionClient.listReviewCommentReactions(
            anyString(), anyString(), eq("owner"), eq("repo"), eq(99L), eq("-1"), eq(100)))
        .thenReturn(List.of());

    capture.captureReactions(
        "Bearer t", "owner", "repo", 7, 99L, "x\n" + SuggestionFormatter.findingMarker(1));

    verify(feedbackService)
        .record(
            "owner/repo",
            7,
            99L,
            1,
            FindingFeedback.SIGNAL_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "bob",
            3L);
  }

  @Test
  void captureOnPriorFindingsStopsAtMaxFindingsInDeterministicIdOrder() {
    var comments = new java.util.ArrayList<GitHubReviewClient.PullRequestComment>();
    int max = FindingFeedbackCaptureService.MAX_FINDINGS_PER_CAPTURE;
    // Insert higher ids first so insertion order would prefer them; we must still poll the
    // lowest comment ids and skip the highest.
    for (int i = max + 2; i >= 1; i--) {
      long id = 1000L + i;
      comments.add(
          new GitHubReviewClient.PullRequestComment(
              id,
              null,
              "A.java",
              "x\n" + SuggestionFormatter.findingMarker(i),
              new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
    }
    when(reactionClient.listReviewCommentReactions(
            any(), any(), any(), any(), anyLong(), any(), anyInt()))
        .thenReturn(List.of());

    capture.captureOnPriorFindings("Bearer t", "owner", "repo", 3, comments);

    verify(reactionClient, times(max * 2))
        .listReviewCommentReactions(
            eq("Bearer t"), anyString(), eq("owner"), eq("repo"), anyLong(), anyString(), eq(100));
    long skippedLow = 1000L + max + 1;
    long skippedHigh = 1000L + max + 2;
    verify(reactionClient, never())
        .listReviewCommentReactions(
            any(), any(), any(), any(), eq(skippedLow), anyString(), anyInt());
    verify(reactionClient, never())
        .listReviewCommentReactions(
            any(), any(), any(), any(), eq(skippedHigh), anyString(), anyInt());
    verify(reactionClient, times(2))
        .listReviewCommentReactions(
            eq("Bearer t"), anyString(), eq("owner"), eq("repo"), eq(1001L), anyString(), eq(100));
  }

  @Test
  void scheduleCaptureOnReviewReplyRunsSuccessfully() {
    when(authClient.getAuthHeader(9L)).thenReturn("Bearer t");
    when(reviewClient.getPullRequestComment(any(), any(), eq("owner"), eq("repo"), eq(99L)))
        .thenReturn(
            new GitHubReviewClient.PullRequestComment(
                99L,
                null,
                "Main.java",
                "x\n" + SuggestionFormatter.findingMarker(1),
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
    when(reactionClient.listReviewCommentReactions(
            any(), any(), any(), any(), anyLong(), any(), anyInt()))
        .thenReturn(List.of());

    capture.scheduleCaptureOnReviewReply(9L, "owner", "repo", 7, 99L, "octocat", "thanks");
    verify(authClient, timeout(2000)).getAuthHeader(9L);
    verify(reviewClient, timeout(2000))
        .getPullRequestComment(any(), any(), eq("owner"), eq("repo"), eq(99L));
  }

  @Test
  void shutdownStopsExecutor() {
    capture.shutdown();
  }

  @Test
  void fetchCommentBodyNullCommentSkipsCapture() {
    when(authClient.getAuthHeader(9L)).thenReturn("Bearer t");
    when(reviewClient.getPullRequestComment(any(), any(), any(), any(), anyLong()))
        .thenReturn(null);
    capture.captureOnReviewReply(9L, "owner", "repo", 7, 99L, "octocat", "not useful");
    verifyNoInteractions(reactionClient, feedbackService);
  }

  @Test
  void replyHeuristicIgnoresBotAuthorAndNonMatchingBodies() {
    capture.captureReplyHeuristic("owner", "repo", 1, 9L, 1, "thrillhousebot[bot]", "not useful");
    capture.captureReplyHeuristic("owner", "repo", 1, 9L, 1, "octocat", "looks fine to me");
    capture.captureReplyHeuristic("owner", "repo", 1, 9L, 1, null, "not useful");
    capture.captureReplyHeuristic("owner", "repo", 1, 9L, 1, " ", "not useful");
    capture.captureReplyHeuristic("owner", "repo", 1, 9L, 1, "octocat", null);
    capture.captureReplyHeuristic("owner", "repo", 1, 9L, 1, "octocat", "");
    verifyNoInteractions(feedbackService);
  }

  @Test
  void scheduleCaptureOnReviewReplyRunsAsyncAndSwallowsErrors() throws Exception {
    when(authClient.getAuthHeader(anyLong())).thenThrow(new RuntimeException("auth fail"));
    capture.scheduleCaptureOnReviewReply(1L, "o", "r", 1, 9L, "u", "not useful");
    // Give the virtual thread a moment; failure must not escape.
    Thread.sleep(200);
    verify(authClient, timeout(2000)).getAuthHeader(1L);
  }

  @Test
  void nullReactionsListIsIgnored() {
    when(reactionClient.listReviewCommentReactions(
            anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyInt()))
        .thenReturn(null);
    capture.captureReactions(
        "Bearer t", "owner", "repo", 7, 99L, "x\n" + SuggestionFormatter.findingMarker(1));
    verify(feedbackService, never())
        .record(any(), anyInt(), anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void captureOnPriorFindingsPollsMarkedBotRoots() {
    var body = "x\n" + SuggestionFormatter.findingMarker(1);
    var comments =
        List.of(
            new GitHubReviewClient.PullRequestComment(
                50L,
                null,
                "A.java",
                body,
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
    when(reactionClient.listReviewCommentReactions(
            any(), any(), any(), any(), anyLong(), any(), anyInt()))
        .thenReturn(List.of());

    capture.captureOnPriorFindings("Bearer t", "owner", "repo", 3, comments);

    verify(reactionClient, times(2))
        .listReviewCommentReactions(
            eq("Bearer t"), anyString(), eq("owner"), eq("repo"), eq(50L), anyString(), eq(100));
  }
}
