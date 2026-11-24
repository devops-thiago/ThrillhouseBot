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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** #1434: inline threads attach to one pending review and submit once. */
class BatchedInlineReviewTest {

  private static final String FILE = "src/Main.java";
  private static final String PATCH = "@@ -1,3 +1,3 @@\n-old\n+new\n";

  private GitHubReviewClient reviewClient;
  private ReviewPublisher publisher;
  private ThrillhouseConfig.ReviewConfig reviewConfig;

  @BeforeEach
  void setUp() {
    reviewClient = mock(GitHubReviewClient.class);
    var config = mock(ThrillhouseConfig.class);
    reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
    when(reviewConfig.batchedInlineReviewEnabled()).thenReturn(true);
    var githubConfig = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofHours(1));
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), anyInt(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(reviewClient.createPendingReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(99L, "", "PENDING", "sha", null));
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.PullRequestCommentResponse(1L, "c", FILE, 1));
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(99L, "body", "COMMENTED", "sha", null));
    var suggestionFormatter = mock(SuggestionFormatter.class);
    when(suggestionFormatter.formatReviewComment(any(), anyBoolean(), anyInt()))
        .thenReturn("finding");
    publisher =
        new ReviewPublisher(
            reviewClient,
            mock(GitHubCommentClient.class),
            mock(ReviewThreadService.class),
            suggestionFormatter,
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            config,
            BotIdentity.of("thrillhousebot[bot]"));
  }

  @Test
  void batchedRoundDisabledSkipsPendingAndSubmit() {
    when(reviewConfig.batchedInlineReviewEnabled()).thenReturn(false);
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient, never()).createPendingReview(any(), any(), any(), any(), anyInt(), any());
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), eq(7), any());
  }

  @Test
  void batchedRoundIgnoresStrandedPendingOwnedByAnotherUser() {
    var other = new GitHubReviewClient.ReviewResponse.User("other-bot");
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(1)))
        .thenReturn(
            List.of(new GitHubReviewClient.ReviewResponse(88L, "", "PENDING", "sha", other)));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient, never()).deletePendingReview(any(), any(), any(), any(), eq(7), eq(88L));
    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
  }

  @Test
  void batchedRoundAttachesEachInlineCommentToThePendingReview() {
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postInlineComments(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)),
        Optional.of(99L));

    var comment = ArgumentCaptor.forClass(GitHubReviewClient.CreatePullRequestCommentRequest.class);
    verify(reviewClient)
        .createPullRequestComment(any(), any(), any(), any(), eq(7), comment.capture());
    assertEquals(99L, comment.getValue().pullRequestReviewId());
  }

  @Test
  void batchedRoundSubmitsANonEmptyBodyWhenProseSectionsAreEmpty() {
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.APPROVE,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    var submit = ArgumentCaptor.forClass(GitHubReviewClient.SubmitReviewRequest.class);
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), submit.capture());
    assertTrue(submit.getValue().body().contains("see inline comments"), submit.getValue().body());
  }

  @Test
  void batchedRoundSubmitsRequestChangesDefaultBodyWhenProseSectionsAreEmpty() {
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    var submit = ArgumentCaptor.forClass(GitHubReviewClient.SubmitReviewRequest.class);
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), submit.capture());
    assertEquals("REQUEST_CHANGES", submit.getValue().event());
    assertTrue(
        submit.getValue().body().contains("ThrillhouseBot requested changes"),
        submit.getValue().body());
  }

  @Test
  void batchedRoundSubmitsPendingAfterAssemblingFollowUpBodySections() {
    var declineNote =
        RebuttalContradiction.NOTE_LEAD_IN
            + " the reply's premise does not refute the finding. Reply again to keep the"
            + " decline.";
    var delta = "**Previous findings resolved:** 1\n\n- Closed: `src/Old.java:1`";
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    var result =
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", declineNote)),
            List.of(),
            0);
    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "Bearer tok",
            "owner",
            "repo",
            7,
            "sha",
            result,
            new DiffLineResolver(Map.of(FILE, PATCH)),
            false,
            List.of(),
            delta,
            List.of()));

    var submit = ArgumentCaptor.forClass(GitHubReviewClient.SubmitReviewRequest.class);
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), submit.capture());
    var body = submit.getValue().body();
    assertTrue(body.contains(RebuttalContradiction.NOTE_LEAD_IN), body);
    assertTrue(body.contains("Previous findings resolved"), body);
    assertTrue(body.contains("ThrillhouseBot requested changes"), body);
    verify(reviewClient, never()).createReview(any(), any(), any(), any(), eq(7), any());
  }

  @Test
  void batchedRoundSubmitsThePendingReviewOnceAfterInlineThreadsLand() {
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
    verify(reviewClient)
        .submitReview(
            any(),
            any(),
            eq("owner"),
            eq("repo"),
            eq(7),
            eq(99L),
            any(GitHubReviewClient.SubmitReviewRequest.class));
    var submit = ArgumentCaptor.forClass(GitHubReviewClient.SubmitReviewRequest.class);
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), submit.capture());
    assertEquals("REQUEST_CHANGES", submit.getValue().event());
  }

  @Test
  void withoutAPendingReviewInlineCommentsCarryNoReviewId() {
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postInlineComments(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)),
        Optional.empty());

    var comment = ArgumentCaptor.forClass(GitHubReviewClient.CreatePullRequestCommentRequest.class);
    verify(reviewClient)
        .createPullRequestComment(any(), any(), any(), any(), eq(7), comment.capture());
    assertNull(comment.getValue().pullRequestReviewId());
  }

  @Test
  void batchedRoundOpensPendingWhenALaterFindingPostsInline() {
    var listed =
        new Finding(RiskLevel.MEDIUM, Confidence.LOW, FILE, 1, "Listed", "desc", null, null);
    var inline = new Finding(RiskLevel.HIGH, FILE, 2, "Inline", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(listed, inline),
            0,
            2,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
  }

  @Test
  void batchedRoundDoesNotOpenPendingWhenEveryFindingIsListedOnly() {
    var finding = new Finding(RiskLevel.MEDIUM, Confidence.LOW, FILE, 1, "Bug", "desc", null, null);
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of()));

    verify(reviewClient, never()).createPendingReview(any(), any(), any(), any(), anyInt(), any());
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void batchedRoundDismissesStrandedPendingBeforeOpeningANewOne() {
    var user = new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]");
    var other = new GitHubReviewClient.ReviewResponse(88L, "", "PENDING", "sha", user);
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(1)))
        .thenReturn(List.of(other));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(88L));
    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
  }

  @Test
  void batchedRoundLogsWhenPendingDeleteFailsAfterNoInlineLanded() {
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), anyInt(), any()))
        .thenThrow(new RuntimeException("422"));
    doThrow(new RuntimeException("delete failed"))
        .when(reviewClient)
        .deletePendingReview(any(), any(), any(), any(), anyInt(), anyLong());
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void batchedRoundWithNoInlinePostedDeletesPendingWithoutSubmit() {
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), anyInt(), any()))
        .thenThrow(new RuntimeException("422"));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void dismissStrandedPendingWalksReviewPagesUntilAShortPage() {
    var user = new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]");
    var stranded = new GitHubReviewClient.ReviewResponse(77L, "", "PENDING", "sha", user);
    var filler = new ArrayList<GitHubReviewClient.ReviewResponse>();
    for (int i = 0; i < 100; i++) {
      filler.add(new GitHubReviewClient.ReviewResponse(i, "", "COMMENTED", "sha", null));
    }
    filler.add(stranded);
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(1)))
        .thenReturn(filler);
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(2)))
        .thenReturn(List.of());
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(77L));
    verify(reviewClient).listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(2));
  }

  @Test
  void refusedBatchedSubmitPostsBodyWithoutPointingAtDroppedInlineThreads() {
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new WebApplicationException(Response.status(422).build()));
    when(reviewClient.createReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(1L, "", "COMMENTED", "sha", null));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.APPROVE,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    var review = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), review.capture());
    assertFalse(review.getValue().body().contains("see inline comments"), review.getValue().body());
    assertTrue(
        review.getValue().body().contains("inline comments from this round were not published"),
        review.getValue().body());
    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
  }

  @Test
  void refusalFallbackBodyReplacesTheInlineSuffixWhenPresent() {
    var withSuffix = "ThrillhouseBot review" + ReviewPublisher.BATCHED_INLINE_BODY_SUFFIX;
    assertTrue(
        ReviewPublisher.batchedSubmitRefusalFallbackBody(withSuffix)
            .contains(ReviewPublisher.BATCHED_SUBMIT_REFUSED_INLINE_NOTE));
    assertFalse(
        ReviewPublisher.batchedSubmitRefusalFallbackBody(withSuffix)
            .contains(ReviewPublisher.BATCHED_INLINE_BODY_SUFFIX));
  }

  @Test
  void refusalFallbackBodyAppendsTheRefusalNoteWhenTheBodyHasNoSuffix() {
    var plain = "Review notes only";
    var fallback = ReviewPublisher.batchedSubmitRefusalFallbackBody(plain);
    assertTrue(fallback.startsWith(plain));
    assertTrue(fallback.contains("GitHub refused the batched review submit"));
  }

  @Test
  void batchedRoundStillOpensPendingWhenStrandedDismissListingFails() {
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(1)))
        .thenThrow(new RuntimeException("reviews unavailable"));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
  }

  @Test
  void batchedRoundPostsInlineWithoutPendingWhenGitHubRefusesTheShell() {
    when(reviewClient.createPendingReview(any(), any(), any(), any(), anyInt(), any()))
        .thenThrow(new RuntimeException("422"));
    when(reviewClient.createReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(1L, "", "COMMENTED", "sha", null));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    var comment = ArgumentCaptor.forClass(GitHubReviewClient.CreatePullRequestCommentRequest.class);
    verify(reviewClient)
        .createPullRequestComment(any(), any(), any(), any(), eq(7), comment.capture());
    assertNull(comment.getValue().pullRequestReviewId());
  }

  @Test
  void batchedRoundSkipsPendingWhenTheCommentCapIsZero() {
    var cappedConfig = mock(ThrillhouseConfig.class);
    var cappedReview = mock(ThrillhouseConfig.ReviewConfig.class);
    when(cappedConfig.review()).thenReturn(cappedReview);
    when(cappedReview.maxReviewComments()).thenReturn(0);
    when(cappedReview.batchedInlineReviewEnabled()).thenReturn(true);
    var cappedGithub = mock(ThrillhouseConfig.GitHubConfig.class);
    when(cappedConfig.github()).thenReturn(cappedGithub);
    when(cappedGithub.writeRetryBudget()).thenReturn(Duration.ofHours(1));
    var cappedPublisher =
        new ReviewPublisher(
            reviewClient,
            mock(GitHubCommentClient.class),
            mock(ReviewThreadService.class),
            mock(SuggestionFormatter.class),
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            cappedConfig,
            BotIdentity.of("thrillhousebot[bot]"));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    cappedPublisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient, never()).createPendingReview(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void batchedRoundFallsBackToBodyOnlyReviewWhenPendingWasNeverOpened() {
    when(reviewClient.createPendingReview(any(), any(), any(), any(), anyInt(), any()))
        .thenThrow(new RuntimeException("refused"));
    when(reviewClient.createReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(1L, "body", "COMMENTED", "sha", null));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), eq(7), any());
    verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), any());
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void dismissStrandedPendingStopsWhenReviewPageIsNull() {
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(1)))
        .thenReturn(null);
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
    verify(reviewClient, never()).deletePendingReview(any(), any(), any(), any(), eq(7), anyLong());
  }

  @Test
  void batchedRoundPostsReviewBodyWhenInlineLandsWithoutPendingShell() {
    when(reviewClient.createPendingReview(any(), any(), any(), any(), anyInt(), any()))
        .thenThrow(new RuntimeException("422"));
    when(reviewClient.createReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(1L, "body", "COMMENTED", "sha", null));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    var review = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), review.capture());
    assertTrue(review.getValue().body().contains("requested changes"), review.getValue().body());
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void batchedRoundJoinsProseSectionsIntoSubmitBodyWhenTheyArePresent() {
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    var submit = ArgumentCaptor.forClass(GitHubReviewClient.SubmitReviewRequest.class);
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), submit.capture());
    assertTrue(
        submit.getValue().body().contains("ThrillhouseBot requested changes — see inline comments"),
        submit.getValue().body());
  }

  @Test
  void batchedRoundSkipsPendingShellWhenNoInlineRouteExists() {
    var finding = new Finding(RiskLevel.HIGH, "src/Other.java", 1, "Bug", "desc", "old", "new");
    when(reviewClient.createReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(1L, "body", "COMMENTED", "sha", null));
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient, never()).createPendingReview(any(), any(), any(), any(), anyInt(), any());
    verify(reviewClient, never())
        .deletePendingReview(any(), any(), any(), any(), anyInt(), anyLong());
    verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), any());
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void batchedRoundOpensPendingWhenAtLeastOneFindingAnchorsOnAChangedFile() {
    var outside = new Finding(RiskLevel.HIGH, "src/Other.java", 1, "Outside", "desc", "old", "new");
    var anchored = new Finding(RiskLevel.HIGH, FILE, 2, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(outside, anchored),
            0,
            2,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), any());
  }

  @Test
  void refusedBatchedSubmitReplacesTheInlineSuffixOnARequestChangesBody() {
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new WebApplicationException(Response.status(422).build()));
    when(reviewClient.createReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(1L, "", "COMMENTED", "sha", null));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    var review = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), review.capture());
    assertTrue(
        review.getValue().body().contains(ReviewPublisher.BATCHED_SUBMIT_REFUSED_INLINE_NOTE),
        review.getValue().body());
    assertFalse(review.getValue().body().contains("see inline comments"), review.getValue().body());
  }

  @Test
  void refusedBatchedSubmitStillCompletesWhenPendingDeleteFails() {
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new WebApplicationException(Response.status(422).build()));
    when(reviewClient.createReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(1L, "", "COMMENTED", "sha", null));
    doThrow(new RuntimeException("delete failed"))
        .when(reviewClient)
        .deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), any());
    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
  }

  @Test
  void dismissStrandedPendingIgnoresPendingReviewsWithNoUser() {
    var pending = new GitHubReviewClient.ReviewResponse(88L, "", "PENDING", "sha", null);
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(1)))
        .thenReturn(List.of(pending));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient, never()).deletePendingReview(any(), any(), any(), any(), eq(7), eq(88L));
    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
  }

  @Test
  void dismissStrandedPendingWalksTwoFullPagesBeforeItFindsAStrandedShell() {
    var user = new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]");
    var stranded = new GitHubReviewClient.ReviewResponse(77L, "", "PENDING", "sha", user);
    var filler = new ArrayList<GitHubReviewClient.ReviewResponse>();
    for (int i = 0; i < GitHubReviewClient.REVIEWS_PER_PAGE; i++) {
      filler.add(new GitHubReviewClient.ReviewResponse(i, "", "COMMENTED", "sha", null));
    }
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(1)))
        .thenReturn(new ArrayList<>(filler));
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(2)))
        .thenReturn(new ArrayList<>(filler));
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(3)))
        .thenReturn(List.of(stranded));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(3));
    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(77L));
  }

  @Test
  void dismissStrandedPendingFetchesTheNextPageWhenTheFirstIsFull() {
    var user = new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]");
    var stranded = new GitHubReviewClient.ReviewResponse(77L, "", "PENDING", "sha", user);
    var filler = new ArrayList<GitHubReviewClient.ReviewResponse>();
    for (int i = 0; i < GitHubReviewClient.REVIEWS_PER_PAGE; i++) {
      filler.add(new GitHubReviewClient.ReviewResponse(i, "", "COMMENTED", "sha", null));
    }
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(1)))
        .thenReturn(filler);
    when(reviewClient.listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(2)))
        .thenReturn(List.of(stranded));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).listReviewsPage(any(), any(), any(), any(), eq(7), eq(100), eq(2));
    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(77L));
  }

  @Test
  void batchedRoundRetriesInlineWithoutSuggestionAfterSuggestionIsRejected() {
    var attempts = new AtomicInteger();
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), anyInt(), any()))
        .thenAnswer(
            inv -> {
              if (attempts.incrementAndGet() == 1) {
                throw new WebApplicationException(Response.status(422).build());
              }
              return new GitHubReviewClient.PullRequestCommentResponse(1L, "c", FILE, 1);
            });
    var suggestionFormatter = mock(SuggestionFormatter.class);
    when(suggestionFormatter.formatReviewComment(any(), eq(true), anyInt())).thenReturn("with");
    when(suggestionFormatter.formatReviewComment(any(), eq(false), anyInt())).thenReturn("without");
    var customConfig = mock(ThrillhouseConfig.class);
    when(customConfig.review()).thenReturn(reviewConfig);
    var githubConfig = mock(ThrillhouseConfig.GitHubConfig.class);
    when(customConfig.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofHours(1));
    var customPublisher =
        new ReviewPublisher(
            reviewClient,
            mock(GitHubCommentClient.class),
            mock(ReviewThreadService.class),
            suggestionFormatter,
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            customConfig,
            BotIdentity.of("thrillhousebot[bot]"));
    when(reviewConfig.batchedInlineReviewEnabled()).thenReturn(true);
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    customPublisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient, org.mockito.Mockito.times(2))
        .createPullRequestComment(any(), any(), any(), any(), eq(7), any());
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), any());
  }

  @Test
  void batchedSubmitBodyUsesRequestChangesDefaultWhenProseIsEmpty() throws Exception {
    var method =
        ReviewPublisher.class.getDeclaredMethod(
            "batchedReviewSubmitBody", List.class, String.class);
    method.setAccessible(true);
    assertEquals(
        "ThrillhouseBot requested changes" + ReviewPublisher.BATCHED_INLINE_BODY_SUFFIX,
        method.invoke(null, List.of(), "REQUEST_CHANGES"));
    assertEquals(
        "ThrillhouseBot review" + ReviewPublisher.BATCHED_INLINE_BODY_SUFFIX,
        method.invoke(null, List.of(), "COMMENT"));
    assertEquals("a\n\nb", method.invoke(null, List.of("a", "b"), "COMMENT"));
  }

  @Test
  void batchedRoundUsesCommentDefaultSubmitBodyOnCleanApprovalWithInlineThreads() {
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.APPROVE,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    var submit = ArgumentCaptor.forClass(GitHubReviewClient.SubmitReviewRequest.class);
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), submit.capture());
    assertEquals(
        "ThrillhouseBot review" + ReviewPublisher.BATCHED_INLINE_BODY_SUFFIX,
        submit.getValue().body());
    assertEquals("COMMENT", submit.getValue().event());
  }

  @Test
  void batchedSubmitFailureThatIsNotARefusalThrows() {
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new RuntimeException("timeout"));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    assertThrows(
        ReviewPostException.class,
        () ->
            publisher.postReview(
                "Bearer tok",
                "owner",
                "repo",
                7,
                "sha",
                new ReviewResult(
                    List.of(finding),
                    0,
                    1,
                    0,
                    0,
                    RiskLevel.HIGH,
                    ReviewState.REQUEST_CHANGES,
                    true,
                    "",
                    List.of(),
                    List.of(),
                    0),
                new DiffLineResolver(Map.of(FILE, PATCH))));
  }

  @Test
  void batchedRoundToleratesPendingDeleteFailureOnCleanup() {
    doThrow(new RuntimeException("delete refused"))
        .when(reviewClient)
        .deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), anyInt(), any()))
        .thenThrow(
            new WebApplicationException(
                Response.status(422)
                    .header("Retry-After", "0")
                    .entity(
                        "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequestReviewComment\","
                            + "\"code\":\"custom\",\"field\":\"pull_request_review_thread.base\","
                            + "\"message\":\"was submitted too quickly\"}]}")
                    .build()));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), any());
  }

  @Test
  void batchedRoundClearsPendingWithoutSubmitWhenEveryInlineAttemptIsThrottled() {
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), anyInt(), any()))
        .thenThrow(
            new WebApplicationException(
                Response.status(422)
                    .header("Retry-After", "0")
                    .entity(
                        "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequestReviewComment\","
                            + "\"code\":\"custom\",\"field\":\"pull_request_review_thread.base\","
                            + "\"message\":\"was submitted too quickly\"}]}")
                    .build()));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postReview(
        "Bearer tok",
        "owner",
        "repo",
        7,
        "sha",
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
    verify(reviewClient, never()).submitReview(any(), any(), any(), any(), eq(7), eq(99L), any());
    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), any());
  }
}
