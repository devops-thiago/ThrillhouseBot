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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #919 — a finding whose inline comment GitHub refuses with its content-creation limit, reported as
 * {@code 422 Validation Failed} rather than a 403.
 *
 * <p>Before the fix the 422 was read as a refusal of the payload, so it was not retried and the
 * finding went straight down the suggestion-less and file-level routes, each refused the same way
 * within milliseconds. In the round-9 corpus that left 42 findings across twelve pull requests with
 * no thread at all.
 *
 * <p>Drives {@link GitHubReviewClient}'s own {@code default} methods, as {@code
 * RescuedFindingLostWriteTest} does, so the classification and the retry really run: only the HTTP
 * attempts are stubbed. The throttle carries {@code Retry-After: 0} purely so the repeat is not
 * slept on; the 422 is classified by its body alone, and the wait it earns without that header is
 * pinned in {@code GitHubWriteRetryTest}.
 */
class ThrottledInlineCommentTest {

  /** The body recorded 118 times in the round-9 corpus, verbatim. */
  private static final String SUBMITTED_TOO_QUICKLY_BODY =
      "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequestReviewComment\","
          + "\"code\":\"custom\",\"field\":\"pull_request_review_thread.base\","
          + "\"message\":\"was submitted too quickly\"}]}";

  /** A refusal of the anchor, also seen in round 9 — what the fallback routes are for. */
  private static final String UNRESOLVABLE_LINE_BODY =
      "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequestReviewComment\","
          + "\"code\":\"custom\",\"field\":\"pull_request_review_thread.line\","
          + "\"message\":\"could not be resolved\"}]}";

  private static final String WITH_SUGGESTION = "the finding, with its suggestion";
  private static final String WITHOUT_SUGGESTION = "the finding";

  /** The lost-post registry is process-wide, so each test takes a pull request of its own. */
  private static final AtomicInteger PR_NUMBERS = new AtomicInteger(919_000);

  private final Finding finding =
      new Finding(RiskLevel.HIGH, "src/Main.java", 10, "Bug", "desc", "old", "new");

  private int prNumber;
  private FakeReviewClient reviewClient;
  private ReviewPublisher publisher;

  @BeforeEach
  void setUp() {
    prNumber = PR_NUMBERS.incrementAndGet();
    reviewClient = new FakeReviewClient();
    var suggestionFormatter = mock(SuggestionFormatter.class);
    when(suggestionFormatter.formatReviewComment(any(), eq(true), anyInt()))
        .thenReturn(WITH_SUGGESTION);
    when(suggestionFormatter.formatReviewComment(any(), eq(false), anyInt()))
        .thenReturn(WITHOUT_SUGGESTION);
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
    var githubConfig = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofHours(1));
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

  /** GitHub throttles the first attempt only: the repeat is the same comment, on the same line. */
  @Test
  void aThrottledInlineCommentIsRetriedAndLandsOnItsLine() {
    reviewClient.refuse = attempt -> attempt == 1 ? throttled() : null;

    var inline = postFinding();

    assertEquals(1, inline.posted());
    assertEquals(List.of(), inline.unanchored());
    assertEquals(0, inline.throttled());
    var lineAnchored = new Attempt(WITH_SUGGESTION, "src/Main.java", 10, false);
    assertEquals(List.of(lineAnchored, lineAnchored), reviewClient.attempts);
    assertEquals(List.of(lineAnchored), reviewClient.landed);
  }

  /**
   * GitHub throttles through every retry. Neither fallback route is tried — both would be refused
   * by the same limit — and the review says the finding has no thread, and why, once.
   */
  @Test
  void aThrottleThatOutlastsEveryRetryTakesNoFallbackRouteAndIsReported() {
    reviewClient.refuse = attempt -> throttled();
    var warnings = new CopyOnWriteArrayList<String>();
    var logger = Logger.getLogger(ReviewPublisher.class.getName());
    var capture =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            warnings.add(entry.getMessage());
          }

          @Override
          public void flush() {
            // Nothing is buffered.
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
    logger.addHandler(capture);
    try {
      publisher.postReview("Bearer tok", "owner", "repo", prNumber, "sha", result(), resolver());
    } finally {
      logger.removeHandler(capture);
    }

    // Four attempts, all at the line with the suggestion: GitHubWriteRetry.MAX_ATTEMPTS, and
    // nothing after them.
    var lineAnchored = new Attempt(WITH_SUGGESTION, "src/Main.java", 10, false);
    assertEquals(
        List.of(lineAnchored, lineAnchored, lineAnchored, lineAnchored), reviewClient.attempts);
    assertEquals(List.of(), reviewClient.landed);

    var body = reviewClient.reviewBodies.getLast();
    assertTrue(body.contains("1 issue(s) GitHub accepted no review thread for"), body);
    assertTrue(body.contains("All of these were refused by GitHub's rate limit"), body);
    assertTrue(body.contains("re-running `/review`"), body);

    var summaries = warnings.stream().filter(m -> m.contains("ended without a review thread"));
    assertEquals(
        List.of(
            "1 of 1 finding(s) on owner/repo #"
                + prNumber
                + " ended without a review thread (1 refused by GitHub's rate limit, after their"
                + " retries ran out or unretried once the write-retry budget was spent) — the"
                + " review body lists them"),
        summaries.toList());
  }

  /**
   * The control: a 422 that refuses the anchor is not a throttle, is not retried, and still gets
   * the whole fallback chain — the suggestion-less comment, then the thread on the file.
   */
  @Test
  void anAnchoringRefusalKeepsTheFallbackChain() {
    reviewClient.refuse = attempt -> attempt <= 2 ? unresolvable() : null;

    var inline = postFinding();

    assertEquals(1, inline.posted());
    assertEquals(0, inline.throttled());
    assertEquals(
        List.of(
            new Attempt(WITH_SUGGESTION, "src/Main.java", 10, false),
            new Attempt(WITHOUT_SUGGESTION, "src/Main.java", 10, false),
            new Attempt(reviewClient.attempts.get(2).body(), "src/Main.java", null, true)),
        reviewClient.attempts);
    assertTrue(
        reviewClient.attempts.get(2).body().endsWith(WITHOUT_SUGGESTION),
        reviewClient.attempts.get(2).body());
  }

  /**
   * A payload refusal first and a throttle second: the file-level route is still skipped, because
   * the refusal that ended the line routes was the throttle.
   */
  @Test
  void aThrottleOnTheSuggestionLessRouteStillSkipsTheFileLevelThread() {
    reviewClient.refuse = attempt -> attempt == 1 ? unresolvable() : throttled();

    var inline = postFinding();

    assertEquals(0, inline.posted());
    assertEquals(List.of(finding), inline.unanchored());
    assertEquals(1, inline.throttled());
    assertTrue(
        reviewClient.attempts.stream().noneMatch(Attempt::fileLevel),
        () -> "a throttle took the file-level route: " + reviewClient.attempts);
  }

  /**
   * A finding whose line is outside the diff goes straight to the file; when GitHub throttles that
   * thread through every retry, it is counted as throttled, not as a refused anchor.
   */
  @Test
  void aThrottledFileLevelThreadIsCountedAsThrottled() {
    reviewClient.refuse = attempt -> throttled();
    var outsideTheDiff =
        new Finding(RiskLevel.HIGH, "src/Other.java", 99, "Bug", "desc", null, null);

    var inline =
        publisher.postInlineComments(
            "Bearer tok", "owner", "repo", prNumber, "sha", result(outsideTheDiff), resolver());

    assertEquals(List.of(outsideTheDiff), inline.unanchored());
    assertEquals(1, inline.throttled());
    assertEquals(4, reviewClient.attempts.size());
    assertTrue(reviewClient.attempts.stream().allMatch(Attempt::fileLevel), "attempts");
  }

  /**
   * One finding throttled through every retry and one whose line and file GitHub refused: the
   * review body says how many of its unthreaded findings the rate limit accounts for.
   */
  @Test
  void theReviewBodyCountsOnlyTheThrottledFindingsAsRateLimited() {
    // The first finding's four attempts are throttled; every route of the second is refused.
    reviewClient.refuse = attempt -> attempt <= 4 ? throttled() : unresolvable();
    var second = new Finding(RiskLevel.HIGH, "src/Main.java", 10, "Other bug", "desc", null, null);

    publisher.postReview(
        "Bearer tok", "owner", "repo", prNumber, "sha", result(finding, second), resolver());

    var body = reviewClient.reviewBodies.getLast();
    assertTrue(body.contains("2 issue(s) GitHub accepted no review thread for"), body);
    assertTrue(body.contains("1 of these were refused by GitHub's rate limit"), body);
  }

  // -------------------------------------------------------------------------------- the fixture

  private ReviewResult result() {
    return result(finding);
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
    return new DiffLineResolver(Map.of("src/Main.java", "@@ -10,1 +10,1 @@\n-old\n+new"));
  }

  private ReviewPublisher.InlineCommentResult postFinding() {
    return publisher.postInlineComments(
        "Bearer tok", "owner", "repo", prNumber, "sha", result(), resolver());
  }

  private static WebApplicationException throttled() {
    return new WebApplicationException(
        Response.status(422).header("Retry-After", "0").entity(SUBMITTED_TOO_QUICKLY_BODY).build());
  }

  private static WebApplicationException unresolvable() {
    return new WebApplicationException(Response.status(422).entity(UNRESOLVABLE_LINE_BODY).build());
  }

  /** One HTTP attempt at a review comment: what it said, and where it was anchored. */
  private record Attempt(String body, String path, Integer line, boolean fileLevel) {}

  /** Real everywhere the classification and the retry live; only the HTTP attempts are stubbed. */
  private static final class FakeReviewClient implements GitHubReviewClient {

    private final List<Attempt> attempts = new ArrayList<>();
    private final List<Attempt> landed = new ArrayList<>();
    private final List<String> reviewBodies = new ArrayList<>();

    /** The failure for the n-th attempt (1-based), or {@code null} to let it land. */
    private IntFunction<WebApplicationException> refuse = attempt -> null;

    @Override
    public PullRequestCommentResponse createPullRequestCommentOnce(
        String auth,
        String accept,
        String owner,
        String repo,
        int pullNumber,
        CreatePullRequestCommentRequest request) {
      var attempt =
          new Attempt(
              request.body(),
              request.path(),
              request.line(),
              SUBJECT_TYPE_FILE.equals(request.subjectType()));
      attempts.add(attempt);
      var failure = refuse.apply(attempts.size());
      if (failure != null) {
        throw failure;
      }
      landed.add(attempt);
      return new PullRequestCommentResponse(1L, request.body(), request.path(), request.line());
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
