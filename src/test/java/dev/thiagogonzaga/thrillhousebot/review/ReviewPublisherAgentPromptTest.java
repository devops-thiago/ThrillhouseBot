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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** #1432: agent prompt block on inline comments only, not file-level threads. */
class ReviewPublisherAgentPromptTest {

  private static final String FILE = "src/Main.java";
  private static final String PATCH = "@@ -1,3 +1,3 @@\n-old\n+new\n";

  private static final String UNRESOLVABLE_LINE_BODY =
      "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequestReviewComment\","
          + "\"code\":\"custom\",\"field\":\"pull_request_review_thread.line\","
          + "\"message\":\"could not be resolved\"}]}";

  private GitHubReviewClient reviewClient;
  private ReviewPublisher publisher;

  @BeforeEach
  void setUp() {
    reviewClient = mock(GitHubReviewClient.class);
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
    when(reviewConfig.agentPromptEnabled()).thenReturn(true);
    var githubConfig = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofHours(1));
    var suggestionFormatter = mock(SuggestionFormatter.class);
    when(suggestionFormatter.formatReviewComment(any(), anyBoolean(), anyInt()))
        .thenReturn("finding body");
    publisher =
        new ReviewPublisher(
            reviewClient,
            mock(GitHubCommentClient.class),
            mock(ReviewThreadService.class),
            suggestionFormatter,
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            config,
            BotIdentity.of("thrillhousebot[bot]"),
            new PendingReviewGuard());
  }

  @Test
  void inlineCommentAppendsTheAgentPromptBlockWhenEnabled() {
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), eq(8), any()))
        .thenReturn(new GitHubReviewClient.PullRequestCommentResponse(1L, "c", FILE, 1));
    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    publisher.postInlineComments(
        "Bearer tok",
        "owner",
        "repo",
        8,
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
        .createPullRequestComment(any(), any(), any(), any(), eq(8), comment.capture());
    assertTrue(comment.getValue().body().contains("<summary>Agent prompt</summary>"));
  }

  @Test
  void fileLevelFallbackDoesNotIncludeTheAgentPromptBlock() {
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), eq(7), any()))
        .thenAnswer(
            inv -> {
              var req =
                  inv.getArgument(5, GitHubReviewClient.CreatePullRequestCommentRequest.class);
              if (req != null && req.line() != null) {
                throw new WebApplicationException(
                    Response.status(422).entity(UNRESOLVABLE_LINE_BODY).build());
              }
              return new GitHubReviewClient.PullRequestCommentResponse(2L, "c", FILE, null);
            });
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
        new DiffLineResolver(Map.of(FILE, PATCH)));

    var comment = ArgumentCaptor.forClass(GitHubReviewClient.CreatePullRequestCommentRequest.class);
    verify(reviewClient, atLeastOnce())
        .createPullRequestComment(any(), any(), any(), any(), eq(7), comment.capture());
    var fileLevel =
        comment.getAllValues().stream().filter(req -> req.line() == null).findFirst().orElseThrow();
    assertFalse(fileLevel.body().contains("Agent prompt"), fileLevel.body());
  }

  @Test
  void inlineCommentOmitsAgentBlockWhenDisabled() {
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    var config = mock(ThrillhouseConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
    when(reviewConfig.agentPromptEnabled()).thenReturn(false);
    var githubConfig = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofHours(1));
    var suggestionFormatter = mock(SuggestionFormatter.class);
    when(suggestionFormatter.formatReviewComment(any(), anyBoolean(), anyInt()))
        .thenReturn("finding body");
    var disabledPublisher =
        new ReviewPublisher(
            reviewClient,
            mock(GitHubCommentClient.class),
            mock(ReviewThreadService.class),
            suggestionFormatter,
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            config,
            BotIdentity.of("thrillhousebot[bot]"),
            new PendingReviewGuard());
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), eq(9), any()))
        .thenReturn(new GitHubReviewClient.PullRequestCommentResponse(1L, "c", FILE, 1));

    var finding = new Finding(RiskLevel.HIGH, FILE, 1, "Bug", "desc", "old", "new");
    disabledPublisher.postInlineComments(
        "Bearer tok",
        "owner",
        "repo",
        9,
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
        .createPullRequestComment(any(), any(), any(), any(), eq(9), comment.capture());
    assertFalse(comment.getValue().body().contains("Agent prompt"));
    assertEquals("finding body", comment.getValue().body());
  }
}
