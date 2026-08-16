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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #748 — what the pull request is told about a review body that its own comment fallback rescued.
 *
 * <p>{@link RescuedFindingLostWriteTest} pins the same question for a finding, whose routes #741
 * grouped into one delivery. The review body has the identical shape — {@code createReview}, then
 * the issue comment carrying the same body (#704) — and was left ungrouped, so #729's symptom and
 * its double count were still live on this pair.
 *
 * <p>Both clients' own {@code default} methods run here, only the {@code *Once} HTTP attempts are
 * faked: a mock of the client stubs the {@code default} methods away, which is precisely what hid
 * the accounting from every publisher test until #729.
 */
class RescuedReviewBodyLostWriteTest {

  /**
   * A plain secondary-rate-limit body rather than a content-creation block, so the retry derives
   * its delay from {@code Retry-After} with no floor applied (#738) and the test does not sleep.
   */
  private static final String SECONDARY_LIMIT_BODY =
      "{\"message\":\"You have exceeded a secondary rate limit.\"}";

  private static final String REVIEW_BODY = "ThrillhouseBot requested changes — see inline.";

  private static final String NOTICE = "An earlier reply on this pull request was never posted";

  /**
   * The registry the clients write to is process-wide, so each test takes a pull request of its own
   * rather than reading another's bookkeeping.
   */
  private static final AtomicInteger PR_NUMBERS = new AtomicInteger(800);

  private int prNumber;
  private ThrottledReviewClient reviewClient;
  private FakeCommentClient commentClient;
  private ReviewPublisher publisher;

  @BeforeEach
  void setUp() {
    prNumber = PR_NUMBERS.incrementAndGet();
    reviewClient = new ThrottledReviewClient();
    commentClient = new FakeCommentClient();
    publisher =
        new ReviewPublisher(
            reviewClient,
            commentClient,
            mock(ReviewThreadService.class),
            mock(SuggestionFormatter.class),
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            mock(ThrillhouseConfig.class),
            BotIdentity.of("thrillhousebot[bot]"));
  }

  private static WebApplicationException throttled() {
    return new WebApplicationException(
        Response.status(403).header("Retry-After", "0").entity(SECONDARY_LIMIT_BODY).build());
  }

  private void publishReviewBody() {
    publisher.createReviewWithFallback(
        "Bearer token",
        "owner",
        "repo",
        prNumber,
        new GitHubReviewClient.CreateReviewRequest("sha", REVIEW_BODY, "COMMENT", List.of()));
  }

  /**
   * The production sequence: GitHub is refusing content creation, {@code createReview} burns its
   * whole retry budget, and #704's issue-comment fallback lands the same body on the far side of
   * the window. The review body is on the pull request, so the comment that delivered it must not
   * open by telling the maintainer it was never posted.
   */
  @Test
  void aReviewBodyTheCommentFallbackRescuedIsNotAnnouncedAsLost() {
    publishReviewBody();

    var landed = commentClient.bodies.getLast();
    assertTrue(
        landed.contains(REVIEW_BODY),
        () -> "the fallback did not carry the review body: " + landed);
    assertFalse(
        landed.contains(NOTICE),
        () ->
            "the review body was delivered by its comment fallback, and the very comment that"
                + " delivered it opens by telling the maintainer it was never posted:\n"
                + landed);
  }

  /**
   * #729's other half on this route pair: when neither route lands, one lost review body is one
   * loss, not one per refused route.
   */
  @Test
  void aReviewBodyNoRouteCouldDeliverIsAnnouncedExactlyOnce() {
    commentClient.throttle = true;

    assertThrows(ReviewPostException.class, this::publishReviewBody);

    // Whatever the bot lands on the pull request next is what carries the notice.
    commentClient.throttle = false;
    commentClient.createComment(
        "Bearer token",
        "application/vnd.github+json",
        "owner",
        "repo",
        prNumber,
        new GitHubCommentClient.CreateCommentRequest("the next thing the bot posts"));

    var carried = commentClient.bodies.getLast();
    assertTrue(
        carried.contains(NOTICE),
        () -> "the genuinely lost review body must still be announced:\n" + carried);
    assertFalse(
        carried.contains("earlier replies on this pull request were never posted"),
        () -> "one lost review body was counted once per refused route:\n" + carried);
  }

  /** Every {@code createReview} attempt is throttled away; nothing else is exercised. */
  private static final class ThrottledReviewClient implements GitHubReviewClient {
    @Override
    public ReviewResponse createReviewOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int pullNumber,
        CreateReviewRequest request) {
      throw throttled();
    }

    @Override
    public PullRequestCommentResponse createPullRequestCommentOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int pullNumber,
        CreatePullRequestCommentRequest request) {
      throw new UnsupportedOperationException("not part of this seam");
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

  /** The fallback surface, real {@code createComment} default and all, so {@code carrying} runs. */
  private static final class FakeCommentClient implements GitHubCommentClient {
    private final List<String> bodies = new ArrayList<>();
    private boolean throttle;

    @Override
    public CommentResponse createCommentOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int issueNumber,
        CreateCommentRequest request) {
      if (throttle) {
        throw throttled();
      }
      bodies.add(request.body());
      return new CommentResponse(1L, "https://example.invalid/1");
    }

    @Override
    public List<IssueComment> listCommentsPageOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int issueNumber,
        int perPage,
        int page) {
      return List.of();
    }

    @Override
    public IssueDetails getIssueOnce(
        String auth, String accept, String owner, String repo, int issueNumber) {
      throw new UnsupportedOperationException("not part of this seam");
    }

    @Override
    public CommentResponse updateCommentOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        long commentId,
        CreateCommentRequest request) {
      throw new UnsupportedOperationException("not part of this seam");
    }
  }
}
