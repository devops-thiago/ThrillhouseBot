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

import java.util.Optional;

/**
 * Raised when the model stopped because it hit its response-length cap ({@code finish_reason:
 * length}) rather than because it finished answering. The body is cut mid-structure, so it would
 * fail to parse — but as a <em>deterministic</em> failure, not a transient one.
 *
 * <p>That distinction is the whole point of the type. Without it a truncation is just an
 * unparseable body, which lands in the transient-failure retry path and re-runs the identical call:
 * same prompt, same cap, same cut, {@code max-ai-retries} times over, and then again for the batch
 * retry above it. Every one of those calls is knowably futile and every one is billed. Carrying the
 * cause in the type lets each retry layer decline to repeat a call that cannot succeed.
 *
 * <p>Extends {@link AiReviewException} so it survives {@link
 * AiReviewService#asAiReviewException(java.util.concurrent.ExecutionException)} with its identity
 * intact — that unwrapper returns an {@code AiReviewException} cause as-is.
 *
 * <p>The exception also carries the {@linkplain #partialBody() buffered partial body} when the
 * failing lane has it (the streaming review path buffers every token it received before the cut).
 * The body is well-formed up to the cut, so the caller that decides what a truncation costs — the
 * pipeline's disclose step — can salvage the complete leading elements instead of discarding paid
 * output it already holds. Carrying it on the exception keeps the no-retry contract intact: the
 * detection site still throws, nothing re-enters the retry lane, and only the disclose step gains
 * an input it previously threw away.
 */
public class AiResponseTruncatedException extends AiReviewException {

  /** Cause-chain links inspected before giving up, so a cyclic chain cannot spin. */
  private static final int MAX_CAUSE_DEPTH = 16;

  private final String partialBody;
  private final boolean conciseModelImplicated;

  public AiResponseTruncatedException(String message) {
    this(message, null, false);
  }

  /**
   * @param partialBody the response text received before the cut, or {@code null} when the failing
   *     lane does not buffer it (the blocking assistants)
   * @param conciseModelImplicated whether the truncated call ran on the {@code concise} named
   *     model, whose cap is {@code REVIEW_CONCISE_MAX_OUTPUT_TOKENS} rather than the active model's
   *     {@code max-output-tokens} — rendered copy names the knob that actually applies
   */
  public AiResponseTruncatedException(
      String message, String partialBody, boolean conciseModelImplicated) {
    super(message, 1, null);
    this.partialBody = partialBody;
    this.conciseModelImplicated = conciseModelImplicated;
  }

  /** The buffered text received before the cut; {@code null} when the lane does not buffer it. */
  public String partialBody() {
    return partialBody;
  }

  /** Whether the truncated call ran on the {@code concise} named model. */
  public boolean conciseModelImplicated() {
    return conciseModelImplicated;
  }

  /**
   * This truncation, marked as coming from the {@code concise} named model — same message, same
   * partial body. Returns {@code this} when already marked.
   */
  public AiResponseTruncatedException implicatingConciseModel() {
    if (conciseModelImplicated) {
      return this;
    }
    return new AiResponseTruncatedException(getMessage(), partialBody, true);
  }

  /**
   * The truncation inside a failure's cause chain, if any. Hoisted from {@code FindingPipeline} so
   * every layer that reacts to a truncation — the pipeline's disclose step, the orchestrator's
   * failure notice — shares one walk instead of each growing its own.
   *
   * <p>Bounded rather than walked to {@code null}: a cause chain can cycle — a {@link Throwable}
   * overriding {@code getCause()}, or plain {@code A caused-by B caused-by A} — and an unbounded
   * walk would spin forever on the review thread. The bound is far above any real chain (the
   * failure arrives wrapped at depth 2 in practice), so a truncation is never missed for depth.
   */
  public static Optional<AiResponseTruncatedException> findIn(Throwable failure) {
    var cause = failure;
    for (var depth = 0;
        cause != null && depth < MAX_CAUSE_DEPTH;
        depth++, cause = cause.getCause()) {
      if (cause instanceof AiResponseTruncatedException truncation) {
        return Optional.of(truncation);
      }
    }
    return Optional.empty();
  }
}
