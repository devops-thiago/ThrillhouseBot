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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ReviewPublisher#publishSummary}'s and {@link
 * ReviewPublisher#publishFollowUpDelta}'s posting conditions.
 */
class ReviewPublisherTest {

  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);

  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);

  private final ThrillhouseConfig.ReviewConfig reviewConfig =
      mock(ThrillhouseConfig.ReviewConfig.class);

  private final ThrillhouseConfig.FollowUpSummaryConfig followUpSummaryConfig =
      mock(ThrillhouseConfig.FollowUpSummaryConfig.class);

  private final ReviewPublisher publisher =
      new ReviewPublisher(
          mock(GitHubReviewClient.class),
          commentClient,
          mock(ReviewThreadService.class),
          mock(SuggestionFormatter.class),
          mock(FollowUpAnalyzer.class),
          mock(PrLabeler.class),
          config,
          BotIdentity.of("thrillhousebot"));

  private void followUpSummaryEnabled(boolean enabled) {
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.followUpSummary()).thenReturn(followUpSummaryConfig);
    when(followUpSummaryConfig.enabled()).thenReturn(enabled);
  }

  private static ReviewResult followUpResult(List<ReviewResult.PreviousFindingStatus> statuses) {
    return new ReviewResult(
        List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, false, "summary", statuses, List.of(), 0);
  }

  private static final ReviewResult RESOLVED_FOLLOW_UP =
      followUpResult(List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "fixed")));

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
  void followUpWithSupersededFindingIgnoresNewerConversationalHeadingReference() {
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot");
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(
            List.of(
                new GitHubCommentClient.IssueComment(
                    66L,
                    ReviewResult.truncationNotice(2)
                        + PrSummaryGenerator.SUMMARY_HEADING
                        + "\n\nstale",
                    bot),
                new GitHubCommentClient.IssueComment(
                    77L,
                    "As noted in the "
                        + PrSummaryGenerator.SUMMARY_HEADING
                        + ", this was reviewed.",
                    bot)));

    assertTrue(publisher.publishSummary("auth", "o", "r", 1, SUPERSEDED_RESULT, false));

    verify(commentClient)
        .updateComment(anyString(), anyString(), anyString(), anyString(), eq(66L), any());
    verify(commentClient, never())
        .updateComment(anyString(), anyString(), anyString(), anyString(), eq(77L), any());
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
    assertFalse(publisher.publishSummary("auth", "o", "r", 1, RESOLVED_FOLLOW_UP, false));
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void followUpDeltaIsNotPostedWhenTheFeatureIsOff() {
    followUpSummaryEnabled(false);

    assertFalse(publisher.publishFollowUpDelta("auth", "o", "r", 1, RESOLVED_FOLLOW_UP, false));
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void followUpDeltaIsPostedWhenEnabledAndTheDeltaIsNonEmpty() {
    followUpSummaryEnabled(true);

    assertTrue(publisher.publishFollowUpDelta("auth", "o", "r", 1, RESOLVED_FOLLOW_UP, false));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    assertTrue(
        body.getValue().body().startsWith(FollowUpDeltaSummary.DELTA_HEADING),
        body.getValue().body());
    assertTrue(
        body.getValue().body().contains("**Previous findings resolved:** 1"),
        body.getValue().body());
  }

  @Test
  void followUpDeltaIsSkippedWhenNothingChangedThisRound() {
    // Enabled, but the round raised nothing and closed nothing — previous findings that merely
    // stayed open are not a delta, so the PR gets no comment at all.
    followUpSummaryEnabled(true);
    var stalled =
        followUpResult(List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still")));

    assertFalse(publisher.publishFollowUpDelta("auth", "o", "r", 1, stalled, false));
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void followUpDeltaNeverDuplicatesTheFirstRunSummary() {
    // A first review posts its summary and nothing else: the round leaves exactly one comment on
    // the PR, the summary. The delta is suppressed by the first-review check on its own — hence
    // {@code summaryPosted == false} here, so the assertion cannot be satisfied by the
    // already-posted-a-summary branch instead. That combination is also the real failure mode:
    // ReviewOrchestrator swallows a failed summary post and passes false, and the delta must not
    // step in as a stand-in first summary.
    followUpSummaryEnabled(true);
    var firstReview =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            true,
            "summary",
            List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "fixed")),
            List.of(),
            0);

    assertTrue(publisher.publishSummary("auth", "o", "r", 1, firstReview, false));
    assertFalse(publisher.publishFollowUpDelta("auth", "o", "r", 1, firstReview, false));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient, times(1))
        .createComment(
            anyString(), anyString(), anyString(), anyString(), anyInt(), body.capture());
    assertEquals("summary", body.getValue().body());
  }

  @Test
  void followUpDeltaIsSkippedWhenThisRoundAlreadyPostedASummary() {
    // /summary re-post, or a superseded finding refreshing the summary: the PR already carries a
    // summary comment for this round, so the delta must not land beside it.
    followUpSummaryEnabled(true);

    assertFalse(publisher.publishFollowUpDelta("auth", "o", "r", 1, RESOLVED_FOLLOW_UP, true));
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }
}
