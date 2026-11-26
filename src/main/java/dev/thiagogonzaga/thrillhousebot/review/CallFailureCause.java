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

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewTimeoutException;
import dev.thiagogonzaga.thrillhousebot.review.ai.Throwables;

/**
 * Names why a batch's review call failed, for the coverage disclosure on the pull request (#1472).
 *
 * <p>The wording is picked from exception types and the HTTP status alone, never from an exception
 * message: a provider's error body can echo the API key (#1329), and this text is posted in public.
 */
final class CallFailureCause {

  static final String TIMED_OUT = "the call timed out";
  static final String CONTEXT_WINDOW = "the request exceeded the model's context window";
  static final String RESPONSE_CUT =
      "the response hit the length cap before any finding was complete";
  static final String RATE_LIMITED = "the AI provider rate-limited the call";
  static final String UNREADABLE_ANSWER = "the model's answer could not be read";
  static final String UNCLASSIFIED = "the call failed, see the server log";

  private CallFailureCause() {}

  static String describe(Throwable failure) {
    if (AiFailures.isContextWindowRejection(failure)) {
      return CONTEXT_WINDOW;
    }
    if (Throwables.findCause(failure, AiReviewTimeoutException.class).isPresent()) {
      return TIMED_OUT;
    }
    var http = Throwables.findCause(failure, HttpException.class);
    if (http.isPresent()) {
      return "the AI provider returned HTTP " + http.get().statusCode();
    }
    if (Throwables.findCause(failure, RateLimitException.class).isPresent()) {
      return RATE_LIMITED;
    }
    return AiReviewService.isUnreadableAnswer(failure) ? UNREADABLE_ANSWER : UNCLASSIFIED;
  }
}
