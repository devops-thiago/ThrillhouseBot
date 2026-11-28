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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGraphQLException;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** #1434: inline threads attach to one pending review and submit once. */
class BatchedInlineReviewTest {

  private static final String FILE = "src/Main.java";
  private static final String PATCH = "@@ -1,3 +1,3 @@\n-old\n+new\n+next\n";
  private static final String NODE_ID = "PRR_99";

  private GitHubReviewClient reviewClient;
  private ReviewThreadService threadService;
  private ReviewPublisher publisher;
  private ThrillhouseConfig config;
  private ThrillhouseConfig.ReviewConfig reviewConfig;
  private SuggestionFormatter suggestionFormatter;
  private PendingReviewGuard guard;

  @BeforeEach
  void setUp() {
    reviewClient = mock(GitHubReviewClient.class);
    threadService = mock(ReviewThreadService.class);
    config = mock(ThrillhouseConfig.class);
    reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
    when(reviewConfig.batchedInlineReviewEnabled()).thenReturn(true);
    var githubConfig = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofHours(1));
    when(reviewClient.createPendingReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(
            new GitHubReviewClient.ReviewResponse(99L, "", "PENDING", "sha", null, NODE_ID));
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.PullRequestCommentResponse(1L, "c", FILE, 1));
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(99L, "body", "COMMENTED", "sha", null));
    when(reviewClient.createReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(1L, "", "COMMENTED", "sha", null));
    when(threadService.addPendingReviewThread(any(), any(), any(), anyInt(), any(), any()))
        .thenReturn("PRRT_1");
    suggestionFormatter = mock(SuggestionFormatter.class);
    when(suggestionFormatter.formatReviewComment(any(), anyBoolean(), anyInt()))
        .thenReturn("finding");
    guard = new PendingReviewGuard(Duration.ofMillis(10));
    publisher = publisher(config);
  }

  private ReviewPublisher publisher(ThrillhouseConfig publisherConfig) {
    return new ReviewPublisher(
        reviewClient,
        mock(GitHubCommentClient.class),
        threadService,
        suggestionFormatter,
        mock(FollowUpAnalyzer.class),
        mock(PrLabeler.class),
        publisherConfig,
        BotIdentity.of("thrillhousebot[bot]"),
        guard);
  }

  private static Finding finding(int line, String title) {
    return new Finding(RiskLevel.HIGH, FILE, line, title, "desc", null, null);
  }

  private static ReviewResult result(ReviewState state, Finding... findings) {
    return new ReviewResult(
        List.of(findings),
        0,
        findings.length,
        0,
        0,
        RiskLevel.HIGH,
        state,
        true,
        "",
        List.of(),
        List.of(),
        0);
  }

  private void post(ReviewResult result) {
    publisher.postReview(
        "Bearer tok", "owner", "repo", 7, "sha", result, new DiffLineResolver(Map.of(FILE, PATCH)));
  }

  private String fallbackReviewBody() {
    var review = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), review.capture());
    return review.getValue().body();
  }

  private static GitHubGraphQLException graphQLRefusal(String json) throws Exception {
    return GitHubGraphQLException.from(new ObjectMapper().readTree(json));
  }

  @Test
  void batchedRoundDisabledPostsEachCommentOnItsOwn() {
    when(reviewConfig.batchedInlineReviewEnabled()).thenReturn(false);

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    verify(reviewClient, never()).createPendingReview(any(), any(), any(), any(), anyInt(), any());
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), eq(7), any());
    verify(threadService, never())
        .addPendingReviewThread(any(), any(), any(), anyInt(), any(), any());
  }

  @Test
  void batchedRoundAttachesEachThreadToThePendingReviewThroughGraphQL() {
    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug"), finding(2, "Other")));

    var comment = ArgumentCaptor.forClass(GitHubReviewClient.CreatePullRequestCommentRequest.class);
    verify(threadService, times(2))
        .addPendingReviewThread(
            eq("Bearer tok"), eq("owner"), eq("repo"), eq(7), eq(NODE_ID), comment.capture());
    assertEquals(FILE, comment.getAllValues().get(0).path());
    assertEquals(1, comment.getAllValues().get(0).line());
    assertEquals("RIGHT", comment.getAllValues().get(0).side());
    verify(reviewClient, never())
        .createPullRequestComment(any(), any(), any(), any(), anyInt(), any());
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), any());
  }

  @Test
  void withoutAPendingReviewInlineCommentsGoOverRest() {
    publisher.postInlineComments(
        new ReviewPublisher.CommentTarget(
            "Bearer tok", "owner", "repo", 7, "sha", Optional.empty()),
        result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")),
        new DiffLineResolver(Map.of(FILE, PATCH)));

    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), eq(7), any());
    verify(threadService, never())
        .addPendingReviewThread(any(), any(), any(), anyInt(), any(), any());
  }

  @Test
  void batchedCommentRoundOpensByStatingNothingBlocks() {
    post(result(ReviewState.COMMENT, finding(1, "Bug")));

    var submit = ArgumentCaptor.forClass(GitHubReviewClient.SubmitReviewRequest.class);
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), submit.capture());
    assertEquals(VerdictDigest.nothingBlocks(false), submit.getValue().body());
    assertEquals("COMMENT", submit.getValue().event());
  }

  @Test
  void batchedChangeRequestNamesWhatBlocks() {
    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    var submit = ArgumentCaptor.forClass(GitHubReviewClient.SubmitReviewRequest.class);
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), submit.capture());
    assertEquals("REQUEST_CHANGES", submit.getValue().event());
    assertTrue(
        submit.getValue().body().contains("**Changes requested: 1 finding blocks this merge.**"),
        submit.getValue().body());
    verify(reviewClient, never()).createReview(any(), any(), any(), any(), anyInt(), any());
  }

  private static GitHubReviewClient.ReviewResponse priorReview(String state, String login) {
    return new GitHubReviewClient.ReviewResponse(
        1L,
        "",
        state,
        "old",
        login == null ? null : new GitHubReviewClient.ReviewResponse.User(login));
  }

  private String commentRoundBodyAfter(GitHubReviewClient.ReviewResponse... priorReviews) {
    return roundBodyAfter(ReviewState.COMMENT, priorReviews);
  }

  private String roundBodyAfter(
      ReviewState state, GitHubReviewClient.ReviewResponse... priorReviews) {
    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "Bearer tok",
            "owner",
            "repo",
            7,
            "sha",
            result(state, finding(1, "Bug")),
            new DiffLineResolver(Map.of(FILE, PATCH)),
            false,
            List.of(),
            "",
            List.of(priorReviews)));
    var submit = ArgumentCaptor.forClass(GitHubReviewClient.SubmitReviewRequest.class);
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), submit.capture());
    return submit.getValue().body();
  }

  @Test
  void aCommentRoundUnderAStandingChangeRequestSaysItStillStands() {
    var body =
        commentRoundBodyAfter(
            priorReview("CHANGES_REQUESTED", "thrillhousebot[bot]"),
            priorReview("COMMENTED", "thrillhousebot[bot]"),
            priorReview("APPROVED", "someone-else"),
            priorReview("APPROVED", null));

    assertEquals(VerdictDigest.nothingBlocks(true), body);
  }

  @Test
  void anApprovalAfterTheChangeRequestEndsIt() {
    var body =
        commentRoundBodyAfter(
            priorReview("CHANGES_REQUESTED", "thrillhousebot[bot]"),
            priorReview("APPROVED", "thrillhousebot[bot]"));

    assertEquals(VerdictDigest.nothingBlocks(false), body);
  }

  @Test
  void anApprovingRoundDoesNotSayTheEarlierChangeRequestStays() {
    var body =
        roundBodyAfter(
            ReviewState.APPROVE, priorReview("CHANGES_REQUESTED", "thrillhousebot[bot]"));

    assertEquals(VerdictDigest.nothingBlocks(false), body);
  }

  @Test
  void aChangeRequestFoldsTheListingsThatBlockNothing() {
    var doubtful =
        new Finding(RiskLevel.LOW, Confidence.LOW, FILE, 2, "Maybe a leak", "desc", null, null);

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug"), doubtful));

    var submit = ArgumentCaptor.forClass(GitHubReviewClient.SubmitReviewRequest.class);
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), submit.capture());
    var body = submit.getValue().body();
    assertTrue(body.contains("**Changes requested: 1 finding blocks this merge.**"), body);
    assertTrue(
        body.contains("<summary>Not blocking: 1 finding without an inline thread</summary>"), body);
    assertTrue(body.indexOf("blocks this merge") < body.indexOf("Maybe a leak"), body);
  }

  @Test
  void batchedRoundSubmitsPendingAfterAssemblingFollowUpBodySections() {
    var declineNote =
        RebuttalContradiction.NOTE_LEAD_IN
            + " the reply's premise does not refute the finding. Reply again to keep the"
            + " decline.";
    var delta = "**Previous findings resolved:** 1\n\n- Closed: `src/Old.java:1`";
    var result =
        new ReviewResult(
            List.of(finding(1, "Bug")),
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
  }

  @Test
  void eachThreadCarriesItsBasisUnderTheDescription() {
    var inDiff = finding(2, "Inline");
    var outsideDiff = finding(999, "On the file");

    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "Bearer tok",
            "owner",
            "repo",
            7,
            "sha",
            result(ReviewState.REQUEST_CHANGES, inDiff, outsideDiff),
            new DiffLineResolver(Map.of(FILE, PATCH)),
            false,
            List.of(),
            "",
            List.of(),
            f -> "**Basis**\n- " + f.title()));

    var formatted = ArgumentCaptor.forClass(Finding.class);
    verify(suggestionFormatter, atLeast(2))
        .formatReviewComment(formatted.capture(), anyBoolean(), anyInt());
    for (var title : List.of("Inline", "On the file")) {
      assertTrue(
          formatted.getAllValues().stream()
              .anyMatch(
                  f ->
                      f.title().equals(title)
                          && f.description().endsWith("\n\n**Basis**\n- " + title)),
          title);
    }
  }

  @Test
  void batchedRoundOpensPendingWhenALaterFindingPostsInline() {
    var listed =
        new Finding(RiskLevel.MEDIUM, Confidence.LOW, FILE, 1, "Listed", "desc", null, null);

    post(result(ReviewState.REQUEST_CHANGES, listed, finding(2, "Inline")));

    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
  }

  @Test
  void batchedRoundDoesNotOpenPendingWhenEveryFindingIsListedOnly() {
    var listed = new Finding(RiskLevel.MEDIUM, Confidence.LOW, FILE, 1, "Bug", "desc", null, null);

    post(result(ReviewState.COMMENT, listed));

    verify(reviewClient, never()).createPendingReview(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void batchedRoundSkipsPendingWhenNoFindingIsOnAChangedFile() {
    var outside = new Finding(RiskLevel.HIGH, "src/Other.java", 1, "Bug", "desc", null, null);

    post(result(ReviewState.REQUEST_CHANGES, outside));

    verify(reviewClient, never()).createPendingReview(any(), any(), any(), any(), anyInt(), any());
    verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), any());
  }

  @Test
  void batchedRoundOpensPendingWhenAtLeastOneFindingIsOnAChangedFile() {
    var outside = new Finding(RiskLevel.HIGH, "src/Other.java", 1, "Outside", "desc", null, null);

    post(result(ReviewState.REQUEST_CHANGES, outside, finding(2, "Bug")));

    verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), any());
  }

  @Test
  void batchedRoundSkipsPendingWhenTheCommentCapIsZero() {
    when(reviewConfig.maxReviewComments()).thenReturn(0);

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    verify(reviewClient, never()).createPendingReview(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void batchedRoundWithNoThreadLandedDiscardsPendingAndPostsTheListing() throws Exception {
    when(threadService.addPendingReviewThread(any(), any(), any(), anyInt(), any(), any()))
        .thenThrow(graphQLRefusal("[{\"message\":\"path could not be resolved\"}]"));
    doThrow(new RuntimeException("delete failed"))
        .when(reviewClient)
        .deletePendingReview(any(), any(), any(), any(), anyInt(), anyLong());

    post(result(ReviewState.COMMENT, finding(1, "Bug")));

    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
    var body = fallbackReviewBody();
    assertTrue(body.contains("`src/Main.java:1`"), body);
    assertTrue(body.contains("path could not be resolved"), body);
  }

  @Test
  void aGraphQLRefusalOfTheSuggestionRetriesTheThreadWithoutIt() {
    var attempts = new AtomicInteger();
    when(threadService.addPendingReviewThread(any(), any(), any(), anyInt(), any(), any()))
        .thenAnswer(
            inv -> {
              if (attempts.incrementAndGet() == 1) {
                throw graphQLRefusal("[{\"message\":\"suggestion refused\"}]");
              }
              return "PRRT_1";
            });
    when(suggestionFormatter.formatReviewComment(any(), eq(true), anyInt())).thenReturn("with");
    var withSuggestion = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "new", "newer");

    post(result(ReviewState.REQUEST_CHANGES, withSuggestion));

    var comment = ArgumentCaptor.forClass(GitHubReviewClient.CreatePullRequestCommentRequest.class);
    verify(threadService, times(2))
        .addPendingReviewThread(any(), any(), any(), eq(7), eq(NODE_ID), comment.capture());
    assertEquals("with", comment.getAllValues().get(0).body());
    assertEquals("finding", comment.getAllValues().get(1).body());
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), any());
  }

  @Test
  void aGraphQLRefusalOfTheLineFilesTheThreadOnTheFile() throws Exception {
    var refusal = graphQLRefusal("[{\"message\":\"line could not be resolved\"}]");
    when(threadService.addPendingReviewThread(
            any(), any(), any(), anyInt(), any(), argThat(c -> !c.fileLevel())))
        .thenThrow(refusal);

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    verify(threadService)
        .addPendingReviewThread(
            any(),
            any(),
            any(),
            eq(7),
            eq(NODE_ID),
            argThat(c -> c.fileLevel() && c.line() == null));
    verify(reviewClient).submitReview(any(), any(), any(), any(), eq(7), eq(99L), any());
  }

  @Test
  void aThrottledGraphQLThreadTakesNoOtherRoute() throws Exception {
    when(threadService.addPendingReviewThread(any(), any(), any(), anyInt(), any(), any()))
        .thenThrow(graphQLRefusal("[{\"type\":\"RATE_LIMITED\",\"message\":\"slow down\"}]"));

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    verify(threadService).addPendingReviewThread(any(), any(), any(), anyInt(), any(), any());
    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    assertTrue(fallbackReviewBody().contains("rate limit"));
  }

  @Test
  void refusedSubmitDiscardsThePendingReviewThenListsEveryInlineFinding() {
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new WebApplicationException(Response.status(422).build()));

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug"), finding(2, "Other")));

    var order = inOrder(reviewClient);
    order.verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    order.verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), any());
    var body = fallbackReviewBody();
    assertTrue(body.contains("2 issue(s) whose inline threads were not published"), body);
    assertTrue(body.contains("**HIGH:** Bug (`src/Main.java:1`)"), body);
    assertTrue(body.contains("**HIGH:** Other (`src/Main.java:2`)"), body);
    assertFalse(body.contains("see inline comments"), body);
    verify(reviewClient, never()).getReview(any(), any(), any(), any(), anyInt(), anyLong());
  }

  @Test
  void refusedSubmitStillListsTheFindingsWhenThePendingDeleteFails() {
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new WebApplicationException(Response.status(422).build()));
    doThrow(new RuntimeException("delete failed"))
        .when(reviewClient)
        .deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    assertTrue(fallbackReviewBody().contains("`src/Main.java:1`"));
  }

  @Test
  void anAmbiguousSubmitFailureOnAStillPendingReviewDiscardsItAndListsTheFindings() {
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new RuntimeException("timeout"));
    when(reviewClient.getReview(any(), any(), any(), any(), eq(7), eq(99L)))
        .thenReturn(
            new GitHubReviewClient.ReviewResponse(99L, "", "PENDING", "sha", null, NODE_ID));

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    var order = inOrder(reviewClient);
    order.verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    order.verify(reviewClient).createReview(any(), any(), any(), any(), eq(7), any());
    assertTrue(fallbackReviewBody().contains("**HIGH:** Bug (`src/Main.java:1`)"));
  }

  @Test
  void anAmbiguousSubmitFailureWhoseReviewReadHasNoBodyIsTreatedAsPending() {
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new RuntimeException("timeout"));
    when(reviewClient.getReview(any(), any(), any(), any(), eq(7), eq(99L))).thenReturn(null);

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(99L));
    assertTrue(fallbackReviewBody().contains("`src/Main.java:1`"));
  }

  @Test
  void anAmbiguousSubmitFailureAfterWhichTheReviewLandedIsASuccess() {
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new WebApplicationException(Response.status(502).build()));
    when(reviewClient.getReview(any(), any(), any(), any(), eq(7), eq(99L)))
        .thenReturn(
            new GitHubReviewClient.ReviewResponse(
                99L, "", "CHANGES_REQUESTED", "sha", null, NODE_ID));

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    verify(reviewClient, never())
        .deletePendingReview(any(), any(), any(), any(), anyInt(), anyLong());
    verify(reviewClient, never()).createReview(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void anAmbiguousSubmitFailureWhoseReviewCannotBeReadThrows() {
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new RuntimeException("timeout"));
    when(reviewClient.getReview(any(), any(), any(), any(), eq(7), eq(99L)))
        .thenThrow(new RuntimeException("read timeout"));

    var round = result(ReviewState.REQUEST_CHANGES, finding(1, "Bug"));
    var failure = assertThrows(ReviewPostException.class, () -> post(round));

    assertEquals("timeout", failure.getCause().getMessage());
    assertEquals("read timeout", failure.getSuppressed()[0].getMessage());
    verify(reviewClient, never()).createReview(any(), any(), any(), any(), anyInt(), any());
    assertTrue(guard.awaitNoPendingReview("owner", "repo", 7), "the hold is released on a throw");
  }

  @Test
  void aRefusedPendingShellPostsInlineCommentsSeparately() {
    when(reviewClient.createPendingReview(any(), any(), any(), any(), anyInt(), any()))
        .thenThrow(new RuntimeException("422"));

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), eq(7), any());
    assertTrue(fallbackReviewBody().contains("Changes requested"));
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void aPendingShellWithoutANodeIdIsDiscardedAndCommentsPostSeparately() {
    when(reviewClient.createPendingReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(98L, "", "PENDING", "sha", null, " "));

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(98L));
    verify(reviewClient).createPullRequestComment(any(), any(), any(), any(), eq(7), any());
    verify(reviewClient, never())
        .submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any());
  }

  @Test
  void aPendingShellWithANullNodeIdIsDiscarded() {
    when(reviewClient.createPendingReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(98L, "", "PENDING", "sha", null));

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(98L));
  }

  @Test
  void thePendingWindowHoldsTheGuardUntilTheSubmit() {
    var heldWhileAttaching = new AtomicBoolean();
    when(threadService.addPendingReviewThread(any(), any(), any(), anyInt(), any(), any()))
        .thenAnswer(
            inv -> {
              heldWhileAttaching.set(!guard.awaitNoPendingReview("owner", "repo", 7));
              return "PRRT_1";
            });

    post(result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")));

    assertTrue(heldWhileAttaching.get());
    assertTrue(guard.awaitNoPendingReview("owner", "repo", 7));
  }

  // #1434 review: the orchestrator's dismissal over the reviews it already listed is the only one.
  @Test
  void aStrandedBotPendingReviewIsDeletedBeforeANewOneOpensWithoutListingAgain() {
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]");
    var other = new GitHubReviewClient.ReviewResponse.User("someone");
    var priorReviews =
        List.of(
            new GitHubReviewClient.ReviewResponse(88L, "", "PENDING", "sha", bot),
            new GitHubReviewClient.ReviewResponse(87L, "", "PENDING", "sha", other));

    publisher.dismissPendingBotReviews("Bearer tok", "owner", "repo", 7, priorReviews);
    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "Bearer tok",
            "owner",
            "repo",
            7,
            "sha",
            result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")),
            new DiffLineResolver(Map.of(FILE, PATCH)),
            false,
            List.of(),
            "",
            priorReviews));

    var order = inOrder(reviewClient);
    order.verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(88L));
    order.verify(reviewClient).createPendingReview(any(), any(), any(), any(), eq(7), eq("sha"));
    verify(reviewClient, never()).deletePendingReview(any(), any(), any(), any(), eq(7), eq(87L));
    verify(reviewClient, never())
        .listReviewsPage(anyString(), any(), any(), any(), anyInt(), anyInt(), anyInt());
    verify(reviewClient, never()).listReviews(any(), any(), any(), any(), anyInt());
  }

  // #1434 review: the listing the orchestrator dismisses from walks pages, so a stranded shell
  // past the first full page is still deleted.
  @Test
  void aStrandedBotPendingReviewPastTheFirstListedPageIsDeleted() {
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]");
    var other = new GitHubReviewClient.ReviewResponse.User("someone");
    var firstPage =
        LongStream.range(0, GitHubReviewClient.REVIEWS_PER_PAGE)
            .mapToObj(
                id -> new GitHubReviewClient.ReviewResponse(id, "", "COMMENTED", "sha", other))
            .toList();
    when(reviewClient.listReviews(any(), any(), any(), any(), anyInt())).thenCallRealMethod();
    when(reviewClient.listReviewsPage(
            any(), any(), any(), any(), eq(7), eq(GitHubReviewClient.REVIEWS_PER_PAGE), eq(1)))
        .thenReturn(firstPage);
    when(reviewClient.listReviewsPage(
            any(), any(), any(), any(), eq(7), eq(GitHubReviewClient.REVIEWS_PER_PAGE), eq(2)))
        .thenReturn(
            List.of(new GitHubReviewClient.ReviewResponse(230L, "", "PENDING", "sha", bot)));
    var listed = reviewClient.listReviews("Bearer tok", "json", "owner", "repo", 7);

    publisher.dismissPendingBotReviews("Bearer tok", "owner", "repo", 7, listed);

    verify(reviewClient).deletePendingReview(any(), any(), any(), any(), eq(7), eq(230L));
    verify(reviewClient, times(1))
        .deletePendingReview(any(), any(), any(), any(), anyInt(), anyLong());
  }

  @Test
  void theInlineResultCountsTheThreadedFindings() {
    var inline =
        publisher.postInlineComments(
            new ReviewPublisher.CommentTarget(
                "Bearer tok",
                "owner",
                "repo",
                7,
                "sha",
                Optional.of(new ReviewPublisher.PendingReview(99L, NODE_ID))),
            result(ReviewState.REQUEST_CHANGES, finding(1, "Bug")),
            new DiffLineResolver(Map.of(FILE, PATCH)));

    assertEquals(1, inline.posted());
    assertEquals("Bug", inline.threaded().get(0).title());
    assertNull(inline.threaded().get(0).suggestionOld());
  }
}
