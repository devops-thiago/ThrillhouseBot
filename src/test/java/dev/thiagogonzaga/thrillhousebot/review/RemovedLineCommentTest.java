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
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #1321: a finding that quotes lines the pull request removes is posted on those lines, on the
 * diff's base side ({@code side: LEFT}), without a suggestion block GitHub could not apply there.
 */
class RemovedLineCommentTest {

  private static final String FILE = "src/Main.java";
  private static final String WITH_SUGGESTION = "the finding, with its suggestion";
  private static final String WITHOUT_SUGGESTION = "the finding";

  private static final String PATCH =
      "@@ -10,5 +10,3 @@\n keep();\n-verify(a).call();\n-verify(a).close();\n next();\n-x();\n+y();";

  private static final String UNRESOLVABLE_LINE_BODY =
      "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequestReviewComment\","
          + "\"code\":\"custom\",\"field\":\"pull_request_review_thread.line\","
          + "\"message\":\"could not be resolved\"}]}";

  private static final String SUBMITTED_TOO_QUICKLY_BODY =
      "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequestReviewComment\","
          + "\"code\":\"custom\",\"field\":\"pull_request_review_thread.base\","
          + "\"message\":\"was submitted too quickly\"}]}";

  /** The lost-post registry is process-wide, so each test takes a pull request of its own. */
  private static final AtomicInteger PR_NUMBERS = new AtomicInteger(1_321_000);

  private int prNumber;
  private FakeReviewClient reviewClient;
  private ReviewPublisher publisher;
  private SuggestionFormatter suggestionFormatter;

  @BeforeEach
  void setUp() {
    prNumber = PR_NUMBERS.incrementAndGet();
    reviewClient = new FakeReviewClient();
    suggestionFormatter = mock(SuggestionFormatter.class);
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

  @Test
  void aQuoteOfRemovedLinesIsPostedOnThemOnTheBaseSide() {
    var inline = post(finding("verify(a).call();\nverify(a).close();", "restore()"));

    assertEquals(1, inline.posted());
    assertEquals(
        List.of(new Attempt(WITHOUT_SUGGESTION, 12, "LEFT", 11, "LEFT", false)),
        reviewClient.landed);
  }

  /**
   * #1360: GitHub numbers the thread on the base side, so its marker also carries the finding's
   * head-side line, which the summary shows and a reply on the thread may name.
   */
  @Test
  void aThreadOnRemovedLinesCarriesItsFindingsLineInItsMarker() {
    var marker = SuggestionFormatter.findingMarker(1, FILE, 12, "Bug");
    when(suggestionFormatter.formatReviewComment(any(), eq(false), anyInt()))
        .thenReturn("the finding\n" + marker + "\n");

    post(finding("x();", null));
    post(finding("y();", null));

    assertEquals(
        OptionalInt.of(12), SuggestionFormatter.parseMarkedLine(reviewClient.landed.get(0).body()));
    assertEquals(
        OptionalInt.empty(),
        SuggestionFormatter.parseMarkedLine(reviewClient.landed.get(1).body()));
  }

  @Test
  void aSingleRemovedLineIsPostedWithoutARange() {
    post(finding("x();", null));

    assertEquals(
        List.of(new Attempt(WITHOUT_SUGGESTION, 14, "LEFT", null, null, false)),
        reviewClient.landed);
  }

  @Test
  void aQuoteOfHeadCodeStaysOnTheRightSide() {
    post(finding("y();", null));

    assertEquals(
        List.of(new Attempt(WITHOUT_SUGGESTION, 12, "RIGHT", null, null, false)),
        reviewClient.landed);
  }

  @Test
  void aBlankQuoteStaysOnTheRightSide() {
    post(finding("  ", null));

    assertEquals(
        List.of(new Attempt(WITHOUT_SUGGESTION, 12, "RIGHT", null, null, false)),
        reviewClient.landed);
  }

  @Test
  void aRefusedBaseSideCommentIsFiledOnTheFile() {
    reviewClient.refuse =
        attempt ->
            attempt == 1
                ? new WebApplicationException(
                    Response.status(422).entity(UNRESOLVABLE_LINE_BODY).build())
                : null;

    var inline = post(finding("x();", null));

    assertEquals(1, inline.posted());
    assertEquals(2, reviewClient.attempts.size());
    assertTrue(reviewClient.landed.getFirst().fileLevel());
  }

  @Test
  void aThrottleOnTheBaseSideTakesNoOtherRoute() {
    reviewClient.refuse =
        attempt ->
            new WebApplicationException(
                Response.status(422)
                    .header("Retry-After", "0")
                    .entity(SUBMITTED_TOO_QUICKLY_BODY)
                    .build());

    var inline = post(finding("x();", null));

    assertEquals(0, inline.posted());
    assertEquals(1, inline.throttled());
    assertTrue(reviewClient.attempts.stream().noneMatch(Attempt::fileLevel));
  }

  private static Finding finding(String quote, String suggestion) {
    return new Finding(RiskLevel.HIGH, FILE, 12, "Bug", "desc", quote, suggestion);
  }

  private ReviewPublisher.InlineCommentResult post(Finding finding) {
    return publisher.postInlineComments(
        "Bearer tok",
        "owner",
        "repo",
        prNumber,
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
  }

  /** One HTTP attempt at a review comment and where it was anchored. */
  private record Attempt(
      String body,
      Integer line,
      String side,
      Integer startLine,
      String startSide,
      boolean fileLevel) {}

  /** Only the HTTP attempts are stubbed; the retry and its classification are real. */
  private static final class FakeReviewClient implements GitHubReviewClient {

    private final List<Attempt> attempts = new ArrayList<>();
    private final List<Attempt> landed = new ArrayList<>();
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
              request.line(),
              request.side(),
              request.startLine(),
              request.startSide(),
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
