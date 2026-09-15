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
 *
 * <p>The streaming lane also attaches the cut call's provider-reported token counts ({@link
 * #inputTokens()}, {@link #outputTokens()}): a length stop with an empty body is the reasoning tail
 * spending the whole output allowance (#839), and the step-down that repeats such a call with
 * reasoning off logs the counts that show it. Plain counts rather than the provider's usage object,
 * so the exception stays serializable and free of client types.
 */
public class AiResponseTruncatedException extends AiReviewException {

  private final String partialBody;
  private final boolean conciseModelImplicated;
  private final Integer inputTokens;
  private final Integer outputTokens;

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
    this(message, partialBody, conciseModelImplicated, null, null);
  }

  /**
   * @param inputTokens the cut call's provider-reported input token count, or {@code null} when the
   *     lane does not carry it (the blocking assistants) or the provider reported none
   * @param outputTokens the cut call's provider-reported output token count, same terms
   */
  public AiResponseTruncatedException(
      String message,
      String partialBody,
      boolean conciseModelImplicated,
      Integer inputTokens,
      Integer outputTokens) {
    super(message, 1, null);
    this.partialBody = partialBody;
    this.conciseModelImplicated = conciseModelImplicated;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
  }

  /** The buffered text received before the cut; {@code null} when the lane does not buffer it. */
  public String partialBody() {
    return partialBody;
  }

  /** The cut call's provider-reported input tokens; {@code null} when not carried or reported. */
  public Integer inputTokens() {
    return inputTokens;
  }

  /** The cut call's provider-reported output tokens; {@code null} when not carried or reported. */
  public Integer outputTokens() {
    return outputTokens;
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
    return new AiResponseTruncatedException(
        getMessage(), partialBody, true, inputTokens, outputTokens);
  }

  /**
   * The truncation inside a failure's cause chain, if any. Hoisted from {@code FindingPipeline} so
   * every layer that reacts to a truncation — the pipeline's disclose step, the orchestrator's
   * failure notice — shares one walk instead of each growing its own; the walk itself is the
   * generic bounded one in {@link Throwables#findCause}.
   */
  public static Optional<AiResponseTruncatedException> findIn(Throwable failure) {
    return Throwables.findCause(failure, AiResponseTruncatedException.class);
  }
}
