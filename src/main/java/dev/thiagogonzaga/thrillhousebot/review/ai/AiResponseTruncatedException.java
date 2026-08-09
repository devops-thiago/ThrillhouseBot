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
 */
public class AiResponseTruncatedException extends AiReviewException {

  public AiResponseTruncatedException(String message) {
    super(message, 1, null);
  }
}
