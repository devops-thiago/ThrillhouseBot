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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/** #1117: one outcome notification carries optional follow-up counts and closure identity. */
class FollowUpReviewNoiseTest {
  private static final ReviewResponse.Finding CLOSED =
      new ReviewResponse.Finding(
          "medium", "src/Closed.java", 12, "Closed null-check bug", "details", null, null);
  private static final Finding NEW =
      new Finding(
          RiskLevel.MEDIUM,
          Confidence.HIGH,
          "src/New.java",
          1,
          "Open race",
          "Concurrent calls race",
          null,
          null);
  private static final BotIdentity BOT = BotIdentity.of("thrillhousebot");
  private final GitHubCommentClient comments = mock(GitHubCommentClient.class);
  private final GitHubReviewClient reviews = mock(GitHubReviewClient.class);
  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);
  private final ThrillhouseConfig.FollowUpSummaryConfig delta =
      mock(ThrillhouseConfig.FollowUpSummaryConfig.class);
  private final ReviewPublisher publisher =
      new ReviewPublisher(
          reviews,
          comments,
          mock(ReviewThreadService.class),
          new SuggestionFormatter(),
          mock(FollowUpAnalyzer.class),
          mock(PrLabeler.class),
          config,
          BOT);

  @BeforeEach
  void configured() {
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    var github = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.review()).thenReturn(review);
    when(config.github()).thenReturn(github);
    when(review.followUpSummary()).thenReturn(delta);
    when(delta.enabled()).thenReturn(true);
    when(review.maxReviewComments()).thenReturn(20);
    when(github.writeRetryBudget()).thenReturn(Duration.ofMinutes(5));
    when(reviews.createPullRequestComment(
            anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
        .thenReturn(
            new GitHubReviewClient.PullRequestCommentResponse(1L, "finding", "src/New.java", 1));
  }

  private static ReviewResult result(
      ReviewState state,
      List<Finding> findings,
      List<ReviewResult.PreviousFindingStatus> statuses,
      List<ReviewResult.CiCheck> checks,
      int omitted,
      ReviewResult.TruncationDetail truncation) {
    return new ReviewResult(
        findings,
        0,
        0,
        findings.size(),
        0,
        findings.isEmpty() ? null : RiskLevel.MEDIUM,
        state,
        false,
        "summary",
        statuses,
        checks,
        omitted,
        false,
        true,
        truncation,
        0);
  }

  private static List<ReviewResult.PreviousFindingStatus> resolved() {
    return List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "fixed"));
  }

  private ReviewPublisher.PostReviewRequest request(
      ReviewResult result, boolean summaryCommandPosted) {
    var section = publisher.followUpDeltaSection(result, summaryCommandPosted, List.of(CLOSED));
    return new ReviewPublisher.PostReviewRequest(
        "auth",
        "o",
        "r",
        7,
        "sha",
        result,
        new DiffLineResolver(Map.of("src/New.java", "@@ -0,0 +1 @@\n+newCode\n")),
        summaryCommandPosted,
        List.of(CLOSED),
        section,
        List.of());
  }

  private GitHubReviewClient.CreateReviewRequest postedReview() {
    var capture = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviews)
        .createReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), capture.capture());
    return capture.getValue();
  }

  private static void closedOnce(String body) {
    assertEquals(1, body.split("Closed null-check bug", -1).length - 1, body);
    assertTrue(body.contains("src/Closed.java:12"), body);
    assertFalse(body.contains(ClosedFindingNames.REVIEW_BODY_LEAD_IN), body);
  }

  @ParameterizedTest
  @CsvSource({"APPROVE, APPROVE", "COMMENT, COMMENT", "REQUEST_CHANGES, REQUEST_CHANGES"})
  void oneReviewRetainsItsVerdictCountsAndClosedName(ReviewState state, String event) {
    var findings = state == ReviewState.APPROVE ? List.<Finding>of() : List.of(NEW);
    var statuses =
        state == ReviewState.APPROVE
            ? resolved()
            : List.of(
                resolved().getFirst(),
                new ReviewResult.PreviousFindingStatus(2, "unresolved", "still open"));
    publisher.postReview(
        request(
            result(state, findings, statuses, List.of(), 0, ReviewResult.TruncationDetail.EMPTY),
            false));
    var review = postedReview();
    assertEquals(event, review.event());
    var body = review.body();
    assertTrue(body.contains("**New findings this round:** " + findings.size()), body);
    assertTrue(body.contains("**Previous findings resolved:** 1"), body);
    assertTrue(
        body.contains("**Previous findings still open:** " + (findings.isEmpty() ? 0 : 1)), body);
    closedOnce(body);
    if (state == ReviewState.APPROVE)
      assertTrue(body.contains(PrSummaryGenerator.ZERO_ISSUES_MESSAGE), body);
    if (state == ReviewState.COMMENT)
      assertTrue(FollowUpAnalyzer.isSelfAuthoredStatusBody(body), body);
    verify(comments, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void ciHoldKeepsThePendingCheckAndOneClosedName() {
    publisher.postReview(
        request(
            result(
                ReviewState.COMMENT,
                List.of(),
                resolved(),
                List.of(new ReviewResult.CiCheck("test", "check-run", "pending", null)),
                0,
                ReviewResult.TruncationDetail.EMPTY),
            false));
    var review = postedReview();
    assertEquals("COMMENT", review.event());
    assertTrue(review.body().contains("Check **test** is pending"), review.body());
    closedOnce(review.body());
  }

  @ParameterizedTest
  @CsvSource({"0, 2, 0", "2, 2, 1"})
  void coverageAndUnverifiedDisclosuresAppearOnceWithTheCounts(
      int omitted, int candidates, int verified) {
    var detail =
        new ReviewResult.TruncationDetail(
            omitted == 0 ? List.of() : List.of("a.java", "b.java"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            SummaryDegradation.NONE,
            new VerificationCoverage(candidates, verified));
    publisher.postReview(
        request(
            result(ReviewState.COMMENT, List.of(NEW), resolved(), List.of(), omitted, detail),
            false));
    var body = postedReview().body();
    var notice =
        omitted == 0
            ? ReviewResult.verificationBrief(detail.verification())
            : ReviewResult.truncationDisclosure(omitted, detail).strip();
    assertEquals(1, body.split(java.util.regex.Pattern.quote(notice), -1).length - 1, body);
    closedOnce(body);
    assertTrue(body.contains("**New findings this round:** 1"), body);
  }

  @Test
  void lowConfidenceFindingStillHasItsDetailsAndTheDeltaInOneOutcome() {
    var finding =
        new Finding(
            RiskLevel.MEDIUM,
            Confidence.LOW,
            "src/New.java",
            1,
            "Needs a double-check",
            "The timeout may be missing",
            null,
            null);
    publisher.postReview(
        request(
            result(
                ReviewState.COMMENT,
                List.of(finding),
                resolved(),
                List.of(),
                0,
                ReviewResult.TruncationDetail.EMPTY),
            false));
    var body = postedReview().body();
    assertTrue(body.contains("Things to double-check"), body);
    assertTrue(body.contains(finding.title()) && body.contains(finding.description()), body);
    closedOnce(body);
  }

  @Test
  void anUnchangedOpenCountDoesNotAddAZeroDelta() {
    publisher.postReview(
        request(
            result(
                ReviewState.COMMENT,
                List.of(),
                List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still open")),
                List.of(),
                0,
                ReviewResult.TruncationDetail.EMPTY),
            false));
    var body = postedReview().body();
    assertTrue(body.contains(ReviewResult.unresolvedPreviousMessage(1)), body);
    assertFalse(body.contains(FollowUpDeltaSummary.DELTA_HEADING), body);
    assertFalse(body.contains("**New findings this round:** 0"), body);
  }

  @Test
  void summaryCommandKeepsDefaultClosureAttributionWithoutAnotherDelta() {
    publisher.postReview(
        request(
            result(
                ReviewState.APPROVE,
                List.of(),
                resolved(),
                List.of(),
                0,
                ReviewResult.TruncationDetail.EMPTY),
            true));
    var body = postedReview().body();
    assertTrue(body.contains(ClosedFindingNames.REVIEW_BODY_LEAD_IN), body);
    assertTrue(body.contains(CLOSED.title()), body);
    assertFalse(body.contains(FollowUpDeltaSummary.DELTA_HEADING), body);
  }

  @Test
  void directCallersKeepTheirClosureAndPartialNoticeWithoutEnrichment() {
    var result =
        result(
            ReviewState.COMMENT,
            List.of(),
            resolved(),
            List.of(),
            1,
            ReviewResult.TruncationDetail.EMPTY);
    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "auth",
            "o",
            "r",
            7,
            "sha",
            result,
            new DiffLineResolver(Map.of()),
            false,
            List.of(CLOSED)));
    var body = postedReview().body();
    assertTrue(body.contains(ClosedFindingNames.REVIEW_BODY_LEAD_IN), body);
    assertTrue(body.contains(ReviewResult.TRUNCATION_NOTICE_LEAD_IN), body);
    assertFalse(body.contains(FollowUpDeltaSummary.DELTA_HEADING), body);
  }

  @Test
  void aDefiniteReviewRefusalDeliversOneFallbackContainingTheOutcomeAndDelta() {
    when(reviews.createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
        .thenThrow(new WebApplicationException(422));
    publisher.postReview(
        request(
            result(
                ReviewState.APPROVE,
                List.of(),
                resolved(),
                List.of(),
                0,
                ReviewResult.TruncationDetail.EMPTY),
            false));
    var capture = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(comments)
        .createComment(
            anyString(), anyString(), anyString(), anyString(), anyInt(), capture.capture());
    var body = capture.getValue().body();
    assertTrue(body.startsWith(ReviewPublisher.REVIEW_REFUSED_NOTE), body);
    assertTrue(body.contains(PrSummaryGenerator.ZERO_ISSUES_MESSAGE), body);
    assertTrue(body.contains("**Previous findings resolved:** 1"), body);
    closedOnce(body);
  }

  @Test
  void ambiguousReviewDeliveryDoesNotPostADuplicateFallback() {
    when(reviews.createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
        .thenThrow(new WebApplicationException(503));
    var post =
        request(
            result(
                ReviewState.APPROVE,
                List.of(),
                resolved(),
                List.of(),
                0,
                ReviewResult.TruncationDetail.EMPTY),
            false);
    assertThrows(ReviewPostException.class, () -> publisher.postReview(post));
    verify(comments, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }
}
