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
 * <p>The exception also carries the {@linkplain #partialBody() partial body}: the text the call had
 * produced before the cut, on every lane. The streaming review path buffers each token it received;
 * the blocking lanes hand over {@code Result#content()}, which holds the same thing (#580). The
 * body is well-formed up to the cut, so the caller that decides what a truncation costs — the
 * pipeline's disclose step, the verifier's salvage — can recover the complete leading elements
 * instead of discarding paid output it already holds. Carrying it on the exception keeps the
 * no-retry contract intact: the detection site still throws, nothing re-enters the retry lane, and
 * only the consumer gains an input it previously threw away.
 *
 * <p>The message's remedy and the {@linkplain #conciseModelImplicated() concise flag} are set
 * together at construction by {@link AiResponses.ModelLane#truncation}, the one source for both, so
 * they cannot disagree (#581). There is deliberately no way to re-mark the flag afterwards: the
 * method that did so kept the message as it was, which is how a summary-lane truncation once told
 * the operator to raise a knob its own flag said did not apply (#600).
 */
public class AiResponseTruncatedException extends AiReviewException {

  private final String partialBody;
  private final boolean conciseModelImplicated;

  public AiResponseTruncatedException(String message) {
    this(message, null, false);
  }

  /**
   * @param partialBody the response text received before the cut — the streaming buffer, or the
   *     blocking call's {@code Result#content()} — or {@code null} when the call produced none
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

  /** The text received before the cut, on any lane; {@code null} when the call produced none. */
  public String partialBody() {
    return partialBody;
  }

  /** Whether the truncated call ran on the {@code concise} named model. */
  public boolean conciseModelImplicated() {
    return conciseModelImplicated;
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
