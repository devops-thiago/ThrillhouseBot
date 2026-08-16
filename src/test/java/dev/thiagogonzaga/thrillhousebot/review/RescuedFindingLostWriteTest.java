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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #729 — what the pull request is told about a finding that #721's file-level fallback rescued.
 *
 * <p>This drives {@link GitHubReviewClient}'s own {@code default} methods, so the {@code recording}
 * and {@code carrying} calls that do the dropped-post accounting really run. That is the whole
 * point: {@code ReviewOrchestratorTest} declares the client a Mockito {@code @Mock}, which stubs
 * those {@code default} methods away, so no publisher test had ever executed the accounting and a
 * regression visible on every throttled review was invisible to the suite. Only the client is real
 * here — the other collaborators stay mocks, because the seam under test is the client's.
 *
 * <p>Both directions are pinned: a finding a later route delivered must leave no notice behind, and
 * a finding no route could deliver must still leave one.
 */
class RescuedFindingLostWriteTest {

  private static final String FILE_LEVEL_THREAD = "the finding, filed on its file";
  private static final String REVIEW_BODY = "the review body";

  /**
   * A secondary rate limit, which is what sends a route round the backoff to exhaustion.
   *
   * <p>Not the content-creation wording measured in #722, deliberately (#749). The seam under test
   * is the accounting, not the backoff: what these tests need from the throttle is that {@link
   * dev.thiagogonzaga.thrillhousebot.github.GitHubApiError#isThrottled} says yes and that the route
   * exhausts {@code GitHubWriteRetry.MAX_ATTEMPTS}, both of which a plain secondary limit does. A
   * content-creation block additionally lifts every wait to {@code
   * CONTENT_CREATION_BLOCK_MIN_DELAY} — 30 seconds — however the delay was derived, {@code
   * Retry-After} included since #738. The {@code Retry-After: 0} below used to mean "no sleep" and
   * stopped meaning it in the same release, at a cost of 461 s for this class and 94 s for a single
   * test. Nothing here asserts on the block wording, so the cheaper body pins the same behaviour.
   */
  private static final String THROTTLE_BODY =
      "{\"message\":\"You have exceeded a secondary rate limit.\"}";

  /**
   * The registry the client writes to is process-wide, so each test takes a pull request of its own
   * rather than reading another's bookkeeping.
   */
  private static final AtomicInteger PR_NUMBERS = new AtomicInteger(700);

  private int prNumber;
  private FakeReviewClient reviewClient;
  private ReviewPublisher publisher;

  @BeforeEach
  void setUp() {
    prNumber = PR_NUMBERS.incrementAndGet();
    reviewClient = new FakeReviewClient();
    var suggestionFormatter = mock(SuggestionFormatter.class);
    // Distinguishable bodies: the line-anchored comment is tried with the suggestion block and
    // again without it, and telling those two routes apart is what pins the double count.
    when(suggestionFormatter.formatReviewComment(any(), eq(true), anyInt()))
        .thenReturn("the finding, with its suggestion");
    when(suggestionFormatter.formatReviewComment(any(), eq(false), anyInt()))
        .thenReturn("the finding");
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
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

  /**
   * The production sequence: GitHub is refusing comment creation, the line-anchored comment burns
   * its whole retry budget, the file-level thread lands on the far side of the window, and the
   * review body goes out moments later. The finding has a working thread, so the body must not open
   * with "an earlier reply on this pull request was never posted … run the command again".
   */
  @Test
  void aFindingTheFileLevelFallbackRescuedIsNotAnnouncedAsLost() {
    reviewClient.blockLineAnchoredComments();

    var inline = postFinding(findingWithoutSuggestion());

    assertEquals(1, inline.posted(), "the fallback was supposed to land the finding");
    assertEquals(List.of(FILE_LEVEL_THREAD), reviewClient.landed);
    assertNoNotice(postReviewBody());
  }

  /**
   * The same for a finding carrying a suggestion block. The line-anchored route is tried twice —
   * with the suggestion and without — and each attempt was its own piece of accounting, so one
   * rescued finding was charged two losses and the body read "2 earlier replies … were never
   * posted".
   */
  @Test
  void aRescuedFindingWithASuggestionIsNotAnnouncedTwiceEither() {
    reviewClient.blockLineAnchoredComments();

    var inline = postFinding(findingWithSuggestion());

    assertEquals(1, inline.posted());
    assertEquals(
        2,
        reviewClient.lineAnchoredRoutes().size(),
        () ->
            "the suggestion block is what earns the line-anchored comment a second route: "
                + reviewClient.lineAnchoredRoutes());
    assertNoNotice(postReviewBody());
  }

  /**
   * The other direction, which the fix must not cost: when the throttle outlasts every route the
   * finding really is gone, and the maintainer does have to run the command again. Said once, for
   * one finding, rather than once per refused route — the over-count is not confined to the rescued
   * case, and a review that lost three findings to a wide window should say three, not nine.
   */
  @Test
  void aFindingNoRouteCouldDeliverIsStillAnnouncedAsLost() {
    reviewClient.blockEveryComment();

    var finding = findingWithoutSuggestion();
    var inline = postFinding(finding);

    assertEquals(0, inline.posted());
    assertEquals(List.of(finding), inline.unanchored());
    var body = postReviewBody();
    assertTrue(
        body.startsWith("> [!WARNING]"),
        () -> "a genuinely lost finding said nothing on the pull request: " + body);
    assertTrue(body.contains("An earlier reply on this pull request was never posted."), body);
    assertFalse(
        body.contains("earlier replies"),
        () -> "one lost finding was counted once per refused route: " + body);
    assertTrue(body.endsWith("\n\n" + REVIEW_BODY), body);
  }

  // -------------------------------------------------------------------------------- the fixture

  private static Finding findingWithoutSuggestion() {
    return new Finding(RiskLevel.HIGH, "src/Main.java", 10, "Bug", "desc", null, null);
  }

  private static Finding findingWithSuggestion() {
    return new Finding(RiskLevel.HIGH, "src/Main.java", 10, "Bug", "desc", "old", "new");
  }

  private ReviewPublisher.InlineCommentResult postFinding(Finding finding) {
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
            List.of(),
            List.of(),
            0);
    var resolver = new DiffLineResolver(Map.of("src/Main.java", "@@ -10,1 +10,1 @@\n-old\n+new"));
    return publisher.postInlineComments(
        "Bearer tok", "owner", "repo", prNumber, "sha", result, resolver);
  }

  /** Publishes the review body — the next content on the PR, and the surface a notice rides on. */
  private String postReviewBody() {
    publisher.createReviewWithFallback(
        "Bearer tok",
        "owner",
        "repo",
        prNumber,
        new GitHubReviewClient.CreateReviewRequest("sha", REVIEW_BODY, "COMMENT", List.of()));
    return reviewClient.reviewBodies.getLast();
  }

  private static void assertNoNotice(String body) {
    assertEquals(
        REVIEW_BODY,
        body,
        () ->
            "the finding was delivered by its file-level thread, but the review body posted"
                + " moments later still told the maintainer it had been thrown away: "
                + body);
  }

  /**
   * A {@link GitHubReviewClient} that is real everywhere the accounting lives: only the {@code
   * *Once} HTTP attempts are stubbed, so {@code createPullRequestComment}, {@code createReview} and
   * the retry and backoff between them all execute exactly as they do in production.
   */
  private static final class FakeReviewClient implements GitHubReviewClient {

    private final List<String> landed = new ArrayList<>();
    private final List<String> reviewBodies = new ArrayList<>();
    private final Set<String> lineAnchoredBodies = new LinkedHashSet<>();
    private boolean blockLineAnchored;
    private boolean blockFileLevel;

    private void blockLineAnchoredComments() {
      blockLineAnchored = true;
    }

    private void blockEveryComment() {
      blockLineAnchored = true;
      blockFileLevel = true;
    }

    /** The distinct line-anchored routes tried, rather than the HTTP attempts each one spent. */
    private Set<String> lineAnchoredRoutes() {
      return lineAnchoredBodies;
    }

    @Override
    public PullRequestCommentResponse createPullRequestCommentOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int pullNumber,
        CreatePullRequestCommentRequest request) {
      var fileLevel = SUBJECT_TYPE_FILE.equals(request.subjectType());
      if (!fileLevel) {
        lineAnchoredBodies.add(request.body());
      }
      if (fileLevel ? blockFileLevel : blockLineAnchored) {
        throw blocked();
      }
      var body = fileLevel ? FILE_LEVEL_THREAD : request.body();
      landed.add(body);
      return new PullRequestCommentResponse(1L, body, request.path(), request.line());
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

    /** GitHub throttling the post, naming a deadline of "now" so the test does not sleep. */
    private static WebApplicationException blocked() {
      return new WebApplicationException(
          Response.status(403).header("Retry-After", "0").entity(THROTTLE_BODY).build());
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
