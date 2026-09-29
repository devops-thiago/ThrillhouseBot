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
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import java.util.function.UnaryOperator;

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
    ACTIVE,

    /**
     * The {@code concise} named binding — the summary, verifier and reply calls — capped by {@code
     * REVIEW_CONCISE_MAX_OUTPUT_TOKENS}. The active model's {@code max-output-tokens} is
     * deliberately never applied to it (see {@link ChatModelCustomizers}), so naming that knob here
     * would send the operator to a setting with no effect on this call.
     */
    CONCISE;

    /**
     * The lane's standing operator instruction, for a truncation whose figures cannot say whether
     * the stop reached the cap (the provider reported no usage). {@code code} formats setting names
     * for the surface: plain for the log, inline code for the posted comment.
     */
    String remedy(UnaryOperator<String> code) {
      return this == CONCISE
          ? "This call runs on the concise named model, so raise "
              + code.apply(ResponseCaps.CONCISE_CAP_SETTING)
              + " (the active model's "
              + code.apply(MAX_OUTPUT_TOKENS)
              + " does not cap it), or "
              + dropCapClause()
              + "."
          : "Raise the active model's "
              + code.apply(MAX_OUTPUT_TOKENS)
              + ", or "
              + dropCapClause()
              + ".";
    }

    /**
     * How the operator drops this lane's cap to reach the provider default. The two differ: the
     * active model's per-model key has no fallback, so leaving it unset sends no cap, while {@code
     * REVIEW_CONCISE_MAX_OUTPUT_TOKENS} falls back to the shipped 8192 when unset and only an empty
     * value drops the cap (application.properties). Advising "leave it unset" on the concise lane
     * would send an operator cut at 8192 straight back to 8192.
     */
    String dropCapClause() {
      return this == CONCISE
          ? "set it empty to drop the cap and use the provider default (unset, it falls back to"
              + " 8192)"
          : "leave it unset to use the provider default";
    }

    /** How the lane's setting stands when its requests carry no cap, for the report's figures. */
    String noCapState() {
      return this == CONCISE ? "is set empty" : "is unset";
    }

    /**
     * The aside that follows the named setting when the stop reached it: on the concise lane it
     * says why the active model's setting is not the one (#581); the active lane needs none, since
     * its setting's key already names the model.
     */
    String capNote(UnaryOperator<String> code) {
      return this == CONCISE
          ? " (this call runs on the concise named model; the active model's "
              + code.apply(MAX_OUTPUT_TOKENS)
              + " does not cap it)"
          : "";
    }
  }

  private static final String MAX_OUTPUT_TOKENS = "max-output-tokens";

  /**
   * The truncation to raise for a call licensed {@code cap}: {@code detail} states what was cut,
   * and the cap's lane sets both the advice and the {@linkplain
   * AiResponseTruncatedException#conciseModelImplicated() concise flag}, from this one source so
   * they cannot disagree (#581). {@code tokenUsage} is what the provider billed for the cut call,
   * or {@code null} when it reported none; the message states it against the cap and advises
   * raising the cap only when the billed completion reached it (#895). {@code partialBody} is the
   * text the call had produced before the cut — {@link Result#content()} on the blocking path, the
   * buffered stream on the streaming one (#580).
   */
  static AiResponseTruncatedException truncation(
      String detail, String partialBody, TokenUsage tokenUsage, ResponseCap cap) {
    var report =
        new TruncationReport(
            cap.lane(),
            cap,
            tokenUsage == null ? null : tokenUsage.inputTokenCount(),
            tokenUsage == null ? null : tokenUsage.outputTokenCount());
    return new AiResponseTruncatedException(
        detail + " " + report.describe(TruncationReport.PLAIN), partialBody, report);
  }

  /**
   * Returns the response text, or throws {@link AiResponseTruncatedException} when the model
   * stopped at its response-length cap. {@code what} names the call for the operator ("/improve",
   * "Finding verification"), since the exception is what the caller logs; {@code cap} is the cap
   * the call's request carried, on the model binding it ran on, so the message and the exception's
   * {@link AiResponseTruncatedException#conciseModelImplicated() concise flag} point at the setting
   * that supplied it, and the message states the billed usage against it (#895).
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
  public static String textOrThrowOnTruncation(
      Result<String> result, String what, ResponseCap cap) {
    if (result == null) {
      return null;
    }
    if (result.finishReason() == FinishReason.LENGTH) {
      throw truncation(
          what
              + " stopped on a length limit (finish_reason=length), so the response is incomplete.",
          result.content(),
          result.tokenUsage(),
          cap);
    }
    return result.content();
  }
}
