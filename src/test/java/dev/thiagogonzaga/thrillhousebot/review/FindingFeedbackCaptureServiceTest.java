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
  @Mock private FollowUpAnalyzer followUpAnalyzer;
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
            followUpAnalyzer,
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
  void captureOnPriorFindingsUsesMatchedThreads() {
    var body = "x\n" + SuggestionFormatter.findingMarker(1);
    var comments =
        List.of(
            new GitHubReviewClient.PullRequestComment(
                50L,
                null,
                "A.java",
                body,
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
    when(followUpAnalyzer.matchFindingThreads(anyString(), eq(comments), any()))
        .thenReturn(java.util.Map.of(1, 50L));
    when(reactionClient.listReviewCommentReactions(
            any(), any(), any(), any(), anyLong(), any(), anyInt()))
        .thenReturn(List.of());

    capture.captureOnPriorFindings("Bearer t", "owner", "repo", 3, "{\"findings\":[]}", comments);

    verify(reactionClient, times(2))
        .listReviewCommentReactions(
            eq("Bearer t"), anyString(), eq("owner"), eq("repo"), eq(50L), anyString(), eq(100));
  }
}
