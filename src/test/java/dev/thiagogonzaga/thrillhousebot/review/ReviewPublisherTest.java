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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ReviewPublisher#publishSummary}'s posting conditions. */
class ReviewPublisherTest {

  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);

  private final ReviewPublisher publisher =
      new ReviewPublisher(
          mock(GitHubReviewClient.class),
          commentClient,
          mock(ReviewThreadService.class),
          mock(SuggestionFormatter.class),
          mock(FollowUpAnalyzer.class),
          mock(PrLabeler.class),
          mock(ThrillhouseConfig.class),
          BotIdentity.of("thrillhousebot"));

  private static ReviewResult followUpResult(List<ReviewResult.PreviousFindingStatus> statuses) {
    return new ReviewResult(
        List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, false, "summary", statuses, List.of(), 0);
  }

  private static final ReviewResult SUPERSEDED_RESULT =
      followUpResult(List.of(new ReviewResult.PreviousFindingStatus(1, "superseded", "code gone")));

  @Test
  void followUpWithSupersededFindingEditsTheExistingSummaryInPlace() {
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot");
    // Two summary comments (e.g. /summary re-posted one): the newest is the one edited.
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(
            List.of(
                new GitHubCommentClient.IssueComment(
                    66L, PrSummaryGenerator.SUMMARY_HEADING + "\n\nolder", bot),
                new GitHubCommentClient.IssueComment(
                    77L, PrSummaryGenerator.SUMMARY_HEADING + "\n\nstale", bot)));

    assertTrue(publisher.publishSummary("auth", "o", "r", 1, SUPERSEDED_RESULT, false));

    verify(commentClient)
        .updateComment(anyString(), anyString(), anyString(), anyString(), eq(77L), any());
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void followUpWithSupersededFindingPostsANewSummaryWhenNoneExists() {
    // None of these is the bot's summary: no author, another author, no body, unrelated body.
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot");
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(
            List.of(
                new GitHubCommentClient.IssueComment(1L, PrSummaryGenerator.SUMMARY_HEADING, null),
                new GitHubCommentClient.IssueComment(
                    2L,
                    PrSummaryGenerator.SUMMARY_HEADING,
                    new GitHubReviewClient.ReviewResponse.User("someone-else")),
                new GitHubCommentClient.IssueComment(3L, null, bot),
                new GitHubCommentClient.IssueComment(4L, "just a reply", bot)));

    assertTrue(publisher.publishSummary("auth", "o", "r", 1, SUPERSEDED_RESULT, false));

    verify(commentClient)
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void plainFollowUpDoesNotPostASummary() {
    var result =
        followUpResult(List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "fixed")));

    assertFalse(publisher.publishSummary("auth", "o", "r", 1, result, false));
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }
}
