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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * #918 at the publishing boundary: a finding whose text names the prompt's own guidance blocks, as
 * the round-9 corpus posted them, reaches GitHub without them — whichever surface carries it.
 */
class ReviewPublisherPromptLabelTest {

  private static final Pattern LEAK =
      Pattern.compile(
          "(?i)dimension \\d|heuristic section|class guidance|must quote|path/to/File\\.java");

  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);
  private final GitHubReviewClient reviewClient = mock(GitHubReviewClient.class);
  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);

  private final ReviewPublisher publisher =
      new ReviewPublisher(
          reviewClient,
          commentClient,
          mock(ReviewThreadService.class),
          new SuggestionFormatter(),
          mock(FollowUpAnalyzer.class),
          mock(PrLabeler.class),
          config,
          BotIdentity.of("thrillhousebot"));

  private static final Finding LEAKED =
      new Finding(
          RiskLevel.HIGH,
          Confidence.HIGH,
          "rust/Dockerfile",
          9,
          "COPY names roombook-server (dimension 7)",
          "Dimension 7 artifact-name mismatch. rust/Cargo.toml declares `name = \"roombook\"`, as"
              + " the comment-contradiction (dimension 4) shows, and the linked issue's"
              + " criterion, which this PR must quote: \"the image starts\". Clear it with"
              + " path/to/File.java:42.",
          "",
          "");

  private void configured() {
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    var github = mock(ThrillhouseConfig.GitHubConfig.class);
    var followUp = mock(ThrillhouseConfig.FollowUpSummaryConfig.class);
    when(config.review()).thenReturn(review);
    when(config.github()).thenReturn(github);
    when(github.writeRetryBudget()).thenReturn(Duration.ofMinutes(5));
    when(review.followUpSummary()).thenReturn(followUp);
    when(followUp.enabled()).thenReturn(true);
  }

  private static ReviewResult result(boolean firstReview, String summary) {
    return new ReviewResult(
        List.of(LEAKED),
        0,
        1,
        0,
        0,
        RiskLevel.HIGH,
        ReviewState.REQUEST_CHANGES,
        firstReview,
        summary,
        List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "fixed")),
        List.of(),
        0);
  }

  /** Every string argument any GitHub write received. */
  private List<String> postedText() {
    return java.util.stream.Stream.of(commentClient, reviewClient)
        .flatMap(client -> mockingDetails(client).getInvocations().stream())
        .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
        .map(String::valueOf)
        .toList();
  }

  @Test
  void aFindingWithLeakedLabelsIsPostedCleanOnEverySurface() {
    configured();
    var leakedSummary =
        "## ThrillhouseBot Summary\n- **HIGH:** "
            + LEAKED.title()
            + " — "
            + LEAKED.description()
            + "\n<!-- thrillhousebot:finding=1 -->";

    publisher.publishSummary("auth", "o", "r", 1, result(true, leakedSummary), false);
    publisher.publishFollowUpDelta(
        "auth",
        "o",
        "r",
        1,
        result(false, leakedSummary),
        false,
        List.of(
            new ReviewResponse.Finding(
                "high", "rust/Dockerfile", 9, LEAKED.title(), LEAKED.description(), null, null)));
    // No diff lines resolve, so the finding lands in the review body: the surface that carried the
    // round-9 leaks this test is built from.
    publisher.postReview(
        "auth", "o", "r", 1, "sha", result(true, leakedSummary), new DiffLineResolver(Map.of()));

    var posted = postedText();
    assertTrue(
        posted.stream().anyMatch(text -> text.contains("Artifact-name mismatch.")),
        String.join("\n---\n", posted));
    assertTrue(
        posted.stream().anyMatch(text -> text.contains("COPY names roombook-server")),
        String.join("\n---\n", posted));
    for (var text : posted) {
      assertFalse(LEAK.matcher(text).find(), text);
    }
  }
}
