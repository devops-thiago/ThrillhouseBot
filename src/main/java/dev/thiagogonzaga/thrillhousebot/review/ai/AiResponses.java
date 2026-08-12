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
   * Which model binding the unwrapped call runs on. The two bindings are capped by different knobs,
   * and a truncation remedy only helps an operator when it names the one that actually applies — so
   * every call site states its lane instead of inheriting a default. A {@link Result} carries no
   * trace of the model that produced it, and the implicit "active model" wording this helper used
   * to apply to every blocking call told operators of the concise-bound lanes to raise a cap that
   * does not bound them.
   */
  public enum ModelLane {

    /** The default binding, capped by the active model's {@code max-output-tokens}. */
    ACTIVE(
        "Raise the active model's max-output-tokens, or leave it unset to use the provider"
            + " default."),

    /**
     * The {@code concise} named binding — the summary, verifier and reply calls — capped by {@code
     * REVIEW_CONCISE_MAX_OUTPUT_TOKENS}. The active model's {@code max-output-tokens} is
     * deliberately never applied to it (see {@link ChatModelCustomizers}), so naming that knob here
     * would send the operator to a setting with no effect on this call.
     */
    CONCISE(
        "This call runs on the concise named model, so raise REVIEW_CONCISE_MAX_OUTPUT_TOKENS (the"
            + " active model's max-output-tokens does not cap it), or leave it unset to use the"
            + " provider default.");

    private final String remedy;

    ModelLane(String remedy) {
      this.remedy = remedy;
    }

    /** The operator instruction naming the response cap that applies to this lane. */
    String remedy() {
      return remedy;
    }
  }

  /**
   * Returns the response text, or throws {@link AiResponseTruncatedException} when the model
   * stopped at its response-length cap. {@code what} names the call for the operator ("/improve",
   * "Finding verification"), since the exception is what the caller logs; {@code lane} names the
   * model binding the call ran on, so the message and the exception's {@link
   * AiResponseTruncatedException#conciseModelImplicated() concise flag} point at the cap that
   * actually cut it.
   *
   * <p>A {@code null} result, and a completed response with no content body, both pass through as
   * {@code null}. "No response" is not a truncation, and each caller owns what it costs them: the
   * {@code /describe}-family generators and the maintainer-reply lane post nothing, and the
   * verifier keeps its unverified findings. It is a real case, not a defensive one — a reasoning
   * model can spend its whole output budget on reasoning tokens and return an empty body with no
   * length stop to show for it.
   *
   * <p>The cut text travels on the failure as its {@linkplain
   * AiResponseTruncatedException#partialBody() partial body} (#580). {@link Result#content()} holds
   * what the model produced before the cap stopped it — output that was generated and billed, and
   * that is well-formed up to the cut — so a caller that wants to keep the elements which closed
   * can run it through {@link TruncatedResponseSalvager}, the same machinery the streaming review
   * lane salvages with. Dropping it here made that impossible by construction of this helper rather
   * than by any provider limitation: every blocking lane's cut body was discarded before its caller
   * could see it. Handing it over decides nothing for the caller — the throw, the no-retry contract
   * and each lane's existing error contract are unchanged, and a lane that ignores the body behaves
   * exactly as it did.
   */
  public static String textOrThrowOnTruncation(Result<String> result, String what, ModelLane lane) {
    if (result == null) {
      return null;
    }
    if (result.finishReason() == FinishReason.LENGTH) {
      throw new AiResponseTruncatedException(
          what
              + " stopped at the model's response-length cap (finish_reason=length), so the"
              + " response is incomplete. "
              + lane.remedy(),
          result.content(),
          lane == ModelLane.CONCISE);
    }
    return result.content();
  }
}
