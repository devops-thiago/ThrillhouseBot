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

import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.Result;

/**
 * Unwraps a blocking AI-service {@link Result}, turning a response the model cut short into a named
 * failure instead of an unparseable body.
 *
 * <p>The streaming review path reads {@code finishReason} off its {@code ChatResponse} directly.
 * The blocking assistants return their text through {@link Result}, which carries the same signal —
 * so every command path can distinguish "the model ran out of room" from "the model emitted bad
 * JSON" rather than reporting the second when the first happened. Without it an operator who capped
 * {@code max-output-tokens} too low sees only a parse warning and has nothing pointing at the cap.
 */
public final class AiResponses {

  private AiResponses() {}

  /**
   * Returns the response text, or throws {@link AiResponseTruncatedException} when the model
   * stopped at its response-length cap. {@code what} names the call for the operator ("/improve",
   * "Finding verification"), since the exception is what the caller logs.
   *
   * <p>A {@code null} result passes through as {@code null}: the callers already treat "no
   * response" as a soft failure, and that is not the same condition as a truncation.
   */
  public static String textOrThrowOnTruncation(Result<String> result, String what) {
    if (result == null) {
      return null;
    }
    if (result.finishReason() == FinishReason.LENGTH) {
      throw new AiResponseTruncatedException(
          what
              + " stopped at the model's response-length cap (finish_reason=length), so the"
              + " response is incomplete. Raise the active model's max-output-tokens, or leave it"
              + " unset to use the provider default.");
    }
    return result.content();
  }
}
