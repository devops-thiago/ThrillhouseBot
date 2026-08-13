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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Raised when the provider rejected the request outright because it exceeds the model's context
 * window — a <em>deterministic</em> failure, not a transient one.
 *
 * <p>Same class of failure as {@link AiResponseTruncatedException} (#495) and the spend-ceiling
 * refusal (#508): re-sending the identical prompt against the identical window is rejected
 * identically, so every retry is a knowably futile billed call. Without this type the rejection is
 * just one more {@code RuntimeException} from the provider, lands in the transient-failure retry
 * path, and is re-billed {@code max-ai-retries} times over — on exactly the requests that are the
 * most expensive ones a review makes, because only an oversized prompt gets here (#622).
 *
 * <p>Extends {@link AiReviewException} so it survives {@link
 * AiReviewService#asAiReviewException(java.util.concurrent.ExecutionException)} with its identity
 * intact — that unwrapper returns an {@code AiReviewException} cause as-is.
 *
 * <p>Detection is by provider message, because the rejection arrives as an untyped HTTP-level error
 * whose only stable signal is its wording. The markers cover the phrasings the supported providers
 * use for the input-side rejection; a marker miss degrades safely to today's behaviour (the failure
 * is retried as transient), never to a lost review.
 */
public class AiContextWindowExceededException extends AiReviewException {

  /**
   * Provider wordings for an input-exceeds-context-window rejection, matched case-insensitively:
   * OpenAI ({@code context_length_exceeded} / "maximum context length"), Anthropic ("prompt is too
   * long", "input length and max_tokens exceed context limit"), and Gemini ("The input token count
   * (...) exceeds the maximum number of tokens allowed").
   *
   * <p>Every marker is anchored to input-side vocabulary — "context", "prompt", "input" — never to
   * a bare token-limit phrase, so an output-side wording (e.g. a response blocked for exceeding the
   * maximum number of tokens, which is {@link AiResponseTruncatedException}'s lane) cannot classify
   * here and draw the input-shrinking remedy that would not fix it. Under-matching is the safe
   * direction: a marker miss degrades to the transient-retry path, never to a lost review.
   */
  private static final List<String> CONTEXT_WINDOW_MARKERS =
      List.of(
          "context_length_exceeded",
          "maximum context length",
          "exceed context limit",
          "exceeds the context limit",
          "prompt is too long",
          "input is too long",
          "input token count");

  /**
   * Cause-chain links inspected before giving up — same bound, same rationale as {@link
   * Throwables#findCause}: a cyclic chain must not spin the review thread.
   */
  private static final int MAX_CAUSE_DEPTH = 16;

  public AiContextWindowExceededException(String message, Throwable cause) {
    super(message, 1, cause);
  }

  /**
   * The context-window rejection inside a failure's cause chain, if any — the shared walk every
   * layer that reacts to one uses, mirroring {@link AiResponseTruncatedException#findIn}.
   */
  public static Optional<AiContextWindowExceededException> findIn(Throwable failure) {
    return Throwables.findCause(failure, AiContextWindowExceededException.class);
  }

  /**
   * Classifies a call failure as a context-window rejection: returns the typed rejection when the
   * failure already carries one in its cause chain, wraps the failure in one when any message in
   * the chain matches a provider marker, and returns empty otherwise — leaving the failure on the
   * transient path it is on today.
   */
  public static Optional<AiContextWindowExceededException> classify(RuntimeException failure) {
    var existing = findIn(failure);
    if (existing.isPresent()) {
      return existing;
    }
    var cause = (Throwable) failure;
    for (var depth = 0;
        cause != null && depth < MAX_CAUSE_DEPTH;
        depth++, cause = cause.getCause()) {
      var message = cause.getMessage();
      if (message != null && matchesMarker(message)) {
        return Optional.of(
            new AiContextWindowExceededException(
                "Provider rejected the request for exceeding the model's context window: "
                    + message,
                failure));
      }
    }
    return Optional.empty();
  }

  private static boolean matchesMarker(String message) {
    var normalized = message.toLowerCase(Locale.ROOT);
    return CONTEXT_WINDOW_MARKERS.stream().anyMatch(normalized::contains);
  }
}
