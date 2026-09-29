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
package dev.thiagogonzaga.thrillhousebot.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthException;
import dev.thiagogonzaga.thrillhousebot.review.ReviewPostException;
import dev.thiagogonzaga.thrillhousebot.review.ReviewResult;
import dev.thiagogonzaga.thrillhousebot.review.ReviewState;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiContextWindowExceededException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponseTruncatedException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewTimeoutException;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenSpendCeilingExceededException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The value types behind the outgoing notification: outcome, event, format, failure category. */
class ReviewOutcomeTest {

  private static final ReviewResult CLEAN =
      new ReviewResult(
          List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

  @Test
  void completedOutcomeCarriesTheResult() {
    var outcome = ReviewOutcome.completed("o", "r", 7, "sha", "t", "https://s", CLEAN);

    assertThat(outcome.event()).isEqualTo(ReviewOutcome.Event.COMPLETED);
    assertThat(outcome.result()).isSameAs(CLEAN);
    assertThat(outcome.failure()).isNull();
    assertThat(outcome.repository()).isEqualTo("o/r");
    assertThat(outcome.pullRequestUrl()).isEqualTo("https://github.com/o/r/pull/7");
  }

  @Test
  void failedOutcomeCarriesTheCategory() {
    var outcome =
        ReviewOutcome.failed("o", "r", 7, "sha", "t", "https://s", FailureCategory.AI_TIMEOUT);

    assertThat(outcome.event()).isEqualTo(ReviewOutcome.Event.FAILED);
    assertThat(outcome.result()).isNull();
    assertThat(outcome.failure()).isEqualTo(FailureCategory.AI_TIMEOUT);
  }

  @Test
  void anOutcomeMustMatchItsEvent() {
    assertThatThrownBy(
            () ->
                new ReviewOutcome(
                    ReviewOutcome.Event.COMPLETED, "o", "r", 1, "s", "t", "u", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ReviewOutcome(
                    ReviewOutcome.Event.FAILED, "o", "r", 1, "s", "t", "u", CLEAN, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ReviewOutcome(
                    ReviewOutcome.Event.COMPLETED,
                    "o",
                    "r",
                    1,
                    "s",
                    "t",
                    "u",
                    CLEAN,
                    FailureCategory.INTERNAL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ReviewOutcome(
                    ReviewOutcome.Event.FAILED, "o", "r", 1, "s", "t", "u", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReviewOutcome(null, "o", "r", 1, "s", "t", "u", null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void eventsParseCaseInsensitivelyAndNameTheirWire() {
    assertThat(ReviewOutcome.Event.from(" COMPLETED ")).contains(ReviewOutcome.Event.COMPLETED);
    assertThat(ReviewOutcome.Event.from("failed")).contains(ReviewOutcome.Event.FAILED);
    assertThat(ReviewOutcome.Event.from("started")).isEmpty();
    assertThat(ReviewOutcome.Event.from(null)).isEmpty();
    assertThat(ReviewOutcome.Event.COMPLETED.wireName()).isEqualTo("review.completed");
    assertThat(ReviewOutcome.Event.FAILED.configName()).isEqualTo("failed");
  }

  @Test
  void formatsParseCaseInsensitively() {
    assertThat(NotificationFormat.from("JSON")).contains(NotificationFormat.JSON);
    assertThat(NotificationFormat.from(" slack ")).contains(NotificationFormat.SLACK);
    assertThat(NotificationFormat.from("Discord")).contains(NotificationFormat.DISCORD);
    assertThat(NotificationFormat.from("teams")).isEmpty();
    assertThat(NotificationFormat.from(null)).isEmpty();
  }

  @Test
  void failureCategoryComesFromTheMostSpecificCauseAnywhereInTheChain() {
    assertThat(
            FailureCategory.of(
                new ReviewPostException("post", new AiResponseTruncatedException("cut"))))
        .isEqualTo(FailureCategory.AI_RESPONSE_TRUNCATED);
    assertThat(
            FailureCategory.of(
                new RuntimeException(new AiContextWindowExceededException("too big", null))))
        .isEqualTo(FailureCategory.AI_CONTEXT_WINDOW_EXCEEDED);
    assertThat(
            FailureCategory.of(
                new AiReviewTimeoutException("slow", 2, Duration.ofSeconds(300), null)))
        .isEqualTo(FailureCategory.AI_TIMEOUT);
    assertThat(FailureCategory.of(new TokenSpendCeilingExceededException(10, 5)))
        .isEqualTo(FailureCategory.TOKEN_SPEND_CEILING);
    assertThat(FailureCategory.of(new AiReviewException("boom", 5, null)))
        .isEqualTo(FailureCategory.AI_ERROR);
    assertThat(FailureCategory.of(new GitHubAuthException("jwt", null)))
        .isEqualTo(FailureCategory.GITHUB_AUTH);
    assertThat(FailureCategory.of(new ReviewPostException("post", null)))
        .isEqualTo(FailureCategory.PUBLISH_FAILED);
    assertThat(FailureCategory.of(new WebApplicationException(502)))
        .isEqualTo(FailureCategory.GITHUB_API);
    assertThat(FailureCategory.of(new IllegalStateException("?")))
        .isEqualTo(FailureCategory.INTERNAL);
    assertThat(FailureCategory.GITHUB_API.wireName()).isEqualTo("github_api");
  }
}
