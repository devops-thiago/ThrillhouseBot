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

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthException;
import dev.thiagogonzaga.thrillhousebot.review.ReviewPostException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiContextWindowExceededException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponseTruncatedException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewTimeoutException;
import dev.thiagogonzaga.thrillhousebot.review.ai.Throwables;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenSpendCeilingExceededException;
import jakarta.ws.rs.WebApplicationException;

/**
 * Why a review failed, as a fixed category rather than the exception's message (#73). A message can
 * carry provider or GitHub response text, so it never leaves the process; the category tells a
 * receiver whether retrying or reconfiguring is the fix.
 */
public enum FailureCategory {
  /** The model's response hit its length cap. */
  AI_RESPONSE_TRUNCATED("ai_response_truncated"),
  /** The request exceeded the model's context window. */
  AI_CONTEXT_WINDOW_EXCEEDED("ai_context_window_exceeded"),
  /** A model call timed out. */
  AI_TIMEOUT("ai_timeout"),
  /** The review reached its token spend ceiling. */
  TOKEN_SPEND_CEILING("token_spend_ceiling"),
  /** Any other model-call failure. */
  AI_ERROR("ai_error"),
  /** The GitHub App could not authenticate. */
  GITHUB_AUTH("github_auth"),
  /** The review was built but could not be posted. */
  PUBLISH_FAILED("publish_failed"),
  /** A GitHub API call failed. */
  GITHUB_API("github_api"),
  /** Anything else. */
  INTERNAL("internal");

  private final String wireName;

  FailureCategory(String wireName) {
    this.wireName = wireName;
  }

  /** The {@code failure.category} value in the payload. */
  public String wireName() {
    return wireName;
  }

  /**
   * The category of a review failure, from the most specific type found anywhere in its cause
   * chain: a truncation wrapped in a publish failure is still a truncation.
   */
  public static FailureCategory of(Throwable failure) {
    if (AiResponseTruncatedException.findIn(failure).isPresent()) {
      return AI_RESPONSE_TRUNCATED;
    }
    if (AiContextWindowExceededException.findIn(failure).isPresent()) {
      return AI_CONTEXT_WINDOW_EXCEEDED;
    }
    if (has(failure, AiReviewTimeoutException.class)) {
      return AI_TIMEOUT;
    }
    if (has(failure, TokenSpendCeilingExceededException.class)) {
      return TOKEN_SPEND_CEILING;
    }
    if (has(failure, AiReviewException.class)) {
      return AI_ERROR;
    }
    if (has(failure, GitHubAuthException.class)) {
      return GITHUB_AUTH;
    }
    if (has(failure, ReviewPostException.class)) {
      return PUBLISH_FAILED;
    }
    if (has(failure, WebApplicationException.class)) {
      return GITHUB_API;
    }
    return INTERNAL;
  }

  private static boolean has(Throwable failure, Class<? extends Throwable> type) {
    return Throwables.findCause(failure, type).isPresent();
  }
}
