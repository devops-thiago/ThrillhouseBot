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

/**
 * Raised instead of making an AI call when the review has already consumed its per-review token
 * ceiling ({@code REVIEW_MAX_TOKENS_PER_REVIEW}). Like {@link AiResponseTruncatedException} this is
 * a <em>deterministic</em> refusal, not a transient failure: the ledger is monotonic within a
 * review, so re-attempting the same call can only be refused again. The type lets the retry loop
 * and the batch soft-fail path decline to retry, degrade coverage by disclosure instead, and — when
 * no call was made at all — fail the review with a message naming the knob.
 *
 * <p>Deliberately not an {@link AiReviewException}: that type means "the call was attempted and
 * failed"; this one means "the call was never made because it would spend past the ceiling".
 */
public class TokenSpendCeilingExceededException extends RuntimeException {

  private final long tokensSpent;
  private final long ceiling;

  public TokenSpendCeilingExceededException(long tokensSpent, long ceiling) {
    this(
        "AI call skipped: this review has consumed "
            + tokensSpent
            + " tokens, at or above its "
            + ceiling
            + "-token spend ceiling (REVIEW_MAX_TOKENS_PER_REVIEW /"
            + " thrillhousebot.review.max-tokens-per-review)",
        tokensSpent,
        ceiling);
  }

  /** Variant for callers that need their own framing (e.g. the zero-calls-made review failure). */
  public TokenSpendCeilingExceededException(String message, long tokensSpent, long ceiling) {
    super(message);
    this.tokensSpent = tokensSpent;
    this.ceiling = ceiling;
  }

  public long tokensSpent() {
    return tokensSpent;
  }

  public long ceiling() {
    return ceiling;
  }
}
