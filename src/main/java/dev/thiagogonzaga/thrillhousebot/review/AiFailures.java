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

import dev.thiagogonzaga.thrillhousebot.review.ai.AiApiKeyRejectedException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiContextWindowExceededException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewException;
import dev.thiagogonzaga.thrillhousebot.review.ai.Throwables;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenSpendCeilingExceededException;

/**
 * How {@link FindingPipeline} reads a wrapped AI failure. Each question walks the failure's cause
 * chain with the bounded walk in {@link Throwables#findCause}, since a batch failure arrives
 * wrapped in the parallel pass's {@link java.util.concurrent.CompletionException}.
 */
final class AiFailures {

  private AiFailures() {}

  /**
   * Whether an AI failure is the calling thread being interrupted rather than the call failing. The
   * AI service restores the interrupt flag before wrapping the {@link InterruptedException}, but a
   * later attempt can consume the flag, so the cause chain is read as well.
   */
  static boolean isInterruption(AiReviewException failure) {
    return Thread.currentThread().isInterrupted()
        || Throwables.findCause(failure, InterruptedException.class).isPresent();
  }

  /** Whether a batch failure was the spend ceiling refusing the call before it was made. */
  static boolean isSpendCeilingBlocked(Throwable failure) {
    return Throwables.findCause(failure, TokenSpendCeilingExceededException.class).isPresent();
  }

  /**
   * Throws the provider's rejection of the API key when {@code failure} is one (#1329): a batch's
   * sequential retry would send the same key, so the review fails with that reason instead of
   * disclosing the batch's files as not reviewed. Returns for every other failure.
   */
  static void throwIfApiKeyRejected(Throwable failure) {
    var rejection = AiApiKeyRejectedException.classify(failure);
    if (rejection.isPresent()) {
      throw rejection.get();
    }
  }

  /** Whether the provider rejected the request for exceeding the model's context window (#622). */
  static boolean isContextWindowRejection(Throwable failure) {
    return AiContextWindowExceededException.findIn(failure).isPresent();
  }
}
