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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubWriteBudget;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * #734 — what happens to a review's findings once the review has spent its write-retry budget.
 *
 * <p>Drives {@link GitHubReviewClient}'s own {@code default} methods for the reason {@code
 * RescuedFindingLostWriteTest} does: the budget is charged inside the retry those methods run, so a
 * mocked client would stub the very seam under test away. Only the client is real; the budget is
 * handed to the publisher small enough to cross in one wait, and GitHub names a one-second deadline
 * so that wait is real but short.
 */
class ReviewWriteBudgetTest {

  private static final String THROTTLE_BODY =
      "{\"message\":\"You have exceeded a secondary rate limit.\"}";

  /** The registry the client writes to is process-wide, so each test takes its own PR. */
  private static final AtomicInteger PR_NUMBERS = new AtomicInteger(800);

  private final int prNumber = PR_NUMBERS.incrementAndGet();
  private final FakeReviewClient reviewClient = new FakeReviewClient();

  private ReviewPublisher publisher(GitHubWriteBudget budget) {
    var suggestionFormatter = mock(SuggestionFormatter.class);
    when(suggestionFormatter.formatReviewComment(any(), anyBoolean(), anyInt()))
        .thenReturn("the finding");
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
    return new ReviewPublisher(
        reviewClient,
        mock(GitHubCommentClient.class),
        mock(ReviewThreadService.class),
        suggestionFormatter,
        mock(FollowUpAnalyzer.class),
        mock(PrLabeler.class),
        config,
        BotIdentity.of("thrillhousebot[bot]"),
        budget);
  }

  /**
   * GitHub refuses every write for the whole review. Without a review-wide ceiling each finding
   * spends the full per-call budget on each of its routes; with one, the first wait that crosses it
   * is the last wait the review serves, and every later write goes out once and is given up on.
   * What the review says about those findings is the same thing it says about any finding no route
   * delivered — with the reason added, so the maintainer knows a re-run will post them.
   */
  @Test
  void aReviewStopsRetryingOnceItsBudgetIsSpentAndSaysSoBesideTheFindingsItCouldNotPost() {
    reviewClient.retryAfterSeconds = 1;
    var first = finding("First bug", 10);
    var second = finding("Second bug", 11);

    publisher(new GitHubWriteBudget(Duration.ofSeconds(1)))
        .postReview(
            "Bearer tok", "owner", "repo", prNumber, "sha", result(first, second), resolver());

    // First finding, line route: one refusal, the one-second wait that spends the budget, a
    // second refusal that is not waited on. Its file route and both routes of the second finding
    // go out once each and are given up on. Five attempts in all, where sixteen were possible.
    assertEquals(5, reviewClient.attempts.get(), "attempts: " + reviewClient.attempts);
    var body = reviewClient.reviewBodies.getLast();
    assertTrue(body.contains("2 issue(s) GitHub accepted no review thread for"), body);
    assertTrue(body.contains("write-retry budget"), body);
    assertTrue(body.contains("1s"), body);
    assertTrue(body.contains("First bug"), body);
    assertTrue(body.contains("Second bug"), body);
  }

  /** Control: a review that never crosses the ceiling discloses its lost findings as before. */
  @Test
  void aReviewThatNeverCrossesTheBudgetSaysNothingAboutIt() {
    reviewClient.retryAfterSeconds = 0;
    var finding = finding("Only bug", 10);

    publisher(new GitHubWriteBudget(Duration.ofHours(1)))
        .postReview("Bearer tok", "owner", "repo", prNumber, "sha", result(finding), resolver());

    var body = reviewClient.reviewBodies.getLast();
    assertTrue(body.contains("1 issue(s) GitHub accepted no review thread for:"), body);
    assertFalse(body.contains("write-retry budget"), body);
    assertTrue(body.contains("Only bug"), body);
  }

  // -------------------------------------------------------------------------------- the fixture

  private static Finding finding(String title, int line) {
    return new Finding(RiskLevel.HIGH, "src/Main.java", line, title, "desc", null, null);
  }

  private static ReviewResult result(Finding... findings) {
    return new ReviewResult(
        List.of(findings),
        0,
        findings.length,
        0,
        0,
        RiskLevel.HIGH,
        ReviewState.REQUEST_CHANGES,
        true,
        "",
        List.of(),
        List.of(),
        0);
  }

  private static DiffLineResolver resolver() {
    return new DiffLineResolver(
        Map.of("src/Main.java", "@@ -10,2 +10,2 @@\n-old\n-old\n+new\n+new"));
  }

  /** Real everywhere the retry and the accounting live; only the HTTP attempts are stubbed. */
  private static final class FakeReviewClient implements GitHubReviewClient {

    private final AtomicInteger attempts = new AtomicInteger();
    private final List<String> reviewBodies = new ArrayList<>();
    private int retryAfterSeconds;

    @Override
    public PullRequestCommentResponse createPullRequestCommentOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int pullNumber,
        CreatePullRequestCommentRequest request) {
      attempts.incrementAndGet();
      throw new WebApplicationException(
          Response.status(403)
              .header("Retry-After", String.valueOf(retryAfterSeconds))
              .entity(THROTTLE_BODY)
              .build());
    }

    @Override
    public ReviewResponse createReviewOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int pullNumber,
        CreateReviewRequest request) {
      reviewBodies.add(request.body());
      return new ReviewResponse(1L, request.body(), request.event(), request.commitId(), null);
    }

    @Override
    public List<ReviewResponse> listReviewsPageOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int pullNumber,
        int perPage,
        int page) {
      return List.of();
    }

    @Override
    public List<PullRequestComment> listPullRequestCommentsPageOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int pullNumber,
        int perPage,
        int page) {
      return List.of();
    }

    @Override
    public PullRequestComment getPullRequestCommentOnce(
        String auth, String accept, String owner, String repo, long commentId) {
      throw new UnsupportedOperationException("not part of this seam");
    }

    @Override
    public PullRequestCommentResponse replyToReviewCommentOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int pullNumber,
        long commentId,
        ReplyToReviewCommentRequest request) {
      throw new UnsupportedOperationException("not part of this seam");
    }

    @Override
    public void deletePendingReview(
        String auth, String accept, String owner, String repo, int pullNumber, long reviewId) {
      throw new UnsupportedOperationException("not part of this seam");
    }
  }
}
