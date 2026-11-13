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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import java.util.Optional;

/**
 * Raised when the AI provider rejected the API key: an HTTP 401 or 403, or LangChain4j's {@link
 * AuthenticationException} (#1329).
 *
 * <p>Deterministic like a context-window rejection: the next attempt sends the same key and is
 * rejected the same way, so a retry only adds the backoff wait and hides the cause behind a generic
 * retry exhaustion. The call fails on its first attempt instead, and does not move to {@code
 * AI_FALLBACK_MODEL}, which uses the same key.
 *
 * <p>The message is fixed and never carries the provider's body, which can echo part of the key.
 * Extends {@link AiReviewException} so it survives {@link
 * AiReviewService#asAiReviewException(java.util.concurrent.ExecutionException)} as itself.
 */
public class AiApiKeyRejectedException extends AiReviewException {

  private static final int UNAUTHORIZED = 401;
  private static final int FORBIDDEN = 403;

  public AiApiKeyRejectedException(String message, Throwable cause) {
    super(message, 1, cause);
  }

  /** The key rejection inside a failure's cause chain, if any. */
  public static Optional<AiApiKeyRejectedException> findIn(Throwable failure) {
    return Throwables.findCause(failure, AiApiKeyRejectedException.class);
  }

  /** Whether a failure is, or was caused by, the provider rejecting the API key. */
  public static boolean isRejectedKey(Throwable failure) {
    return classify(failure).isPresent();
  }

  /**
   * Classifies a call failure as a key rejection: the typed rejection when the cause chain already
   * carries one, a new one wrapping {@code failure} when the chain holds a 401, a 403 or an {@link
   * AuthenticationException}, and empty for every other failure, which keeps its own handling.
   */
  public static Optional<AiApiKeyRejectedException> classify(Throwable failure) {
    var existing = findIn(failure);
    if (existing.isPresent()) {
      return existing;
    }
    return Throwables.findCause(failure, AiApiKeyRejectedException::namesARejectedKey)
        .map(
            cause ->
                new AiApiKeyRejectedException(
                    "The AI provider rejected the API key" + statusOf(cause) + "; check AI_API_KEY",
                    failure));
  }

  private static boolean namesARejectedKey(Throwable cause) {
    return cause instanceof AuthenticationException
        || (cause instanceof HttpException http
            && (http.statusCode() == UNAUTHORIZED || http.statusCode() == FORBIDDEN));
  }

  private static String statusOf(Throwable cause) {
    return cause instanceof HttpException http ? " (HTTP " + http.statusCode() + ")" : "";
  }
}
