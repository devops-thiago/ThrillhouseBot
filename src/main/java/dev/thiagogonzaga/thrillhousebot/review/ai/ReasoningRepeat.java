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

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.Result;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.AiPricingConfig.ReasoningConfig;
import io.quarkus.logging.Log;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The one repeat of a call that stopped at its length cap with no content: the reasoning spent the
 * whole output allowance before the answer began (#839). This class decides the effort the repeat
 * goes out at, carries it to the model on the calling thread, and runs the repeat for the blocking
 * calls; {@link AiReviewService} runs it for the streamed ones.
 *
 * <p>The effort is carried in a thread-local that {@link ReasoningStepDownStreamingModel} and
 * {@link BoundedChatModel} read when the call reaches them, because neither path takes a per-call
 * reasoning parameter from the AI service method (see {@link ReasoningStepDownStreamingModel}). It
 * is kept apart from {@link ReviewSessionContext}: binding a session on a blocking call would make
 * the observability listener record its usage on top of the caller's own recording.
 */
final class ReasoningRepeat {

  private static final ThreadLocal<String> EFFORT = new ThreadLocal<>();

  private ReasoningRepeat() {}

  /**
   * The {@code reasoning_effort} the repeat sends on the given lane, or empty when there is no
   * repeat to make.
   *
   * <p>With reasoning enabled, the repeat goes out at {@code none}, unless the lane already sends
   * {@code none} and the repeat would be the identical call. With reasoning off in config nothing
   * was sent, so the provider's default effort applied, and a model that reasons by default can
   * spend the cap that way on most calls, as {@code glm-5.3-flash} did on the concise lane in
   * #1169. The repeat then sends the effort the lane would send with reasoning enabled ({@code low}
   * unless configured), not {@code none}: that model spent the cap again at {@code none}, while at
   * {@code low} the same calls answered in under a thousand output tokens.
   */
  static Optional<String> effortFor(ThrillhouseConfig config, boolean concise) {
    var reasoning = config.ai().reasoning();
    if (!reasoning.enabled()) {
      return Optional.of(
          concise
              ? ReasoningConfig.resolveConciseEffort(reasoning)
              : ReasoningConfig.normalize(reasoning.effort()));
    }
    return ChatModelCustomizers.reasoningEffort(config, concise)
        .filter(effort -> !ReasoningConfig.EFFORT_NONE.equals(effort))
        .map(effort -> ReasoningConfig.EFFORT_NONE);
  }

  /** The effort the lane's first call went out at, as the step-down logs name it. */
  static String firstEffort(ThrillhouseConfig config, boolean concise) {
    return ChatModelCustomizers.reasoningEffort(config, concise).orElse("the provider default");
  }

  /**
   * Sets {@code effort} as the override for the calls this thread makes until the returned scope is
   * closed; {@code null} sets nothing. The previous value comes back on close, so scopes nest.
   */
  static Scope override(String effort) {
    var previous = EFFORT.get();
    if (effort != null) {
      EFFORT.set(effort);
    }
    return new Scope(previous);
  }

  /** The override set on this thread, or {@code null} for a call that goes out as configured. */
  static String current() {
    return EFFORT.get();
  }

  /**
   * {@code request} with the thread's override as its {@code reasoning_effort}, or {@code request}
   * itself when none is set. The effort goes on an OpenAI-typed copy of the request's own
   * parameters: the provider model merges a call's parameters over its builder-level defaults, and
   * only an OpenAI-typed value keeps the OpenAI-specific fields through that merge.
   */
  static ChatRequest applied(ChatRequest request) {
    var effort = current();
    if (effort == null) {
      return request;
    }
    return request.toBuilder()
        .parameters(
            OpenAiChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .reasoningEffort(effort)
                .build())
        .build();
  }

  /** Whether {@code result} stopped at its length cap before any answer text arrived. */
  static boolean stoppedWithNoContent(Result<String> result) {
    return result != null
        && result.finishReason() == FinishReason.LENGTH
        && (result.content() == null || result.content().isBlank());
  }

  /**
   * One blocking call with the repeat: {@code call} is made, {@code meter} records its usage, and
   * when it {@linkplain #stoppedWithNoContent stopped with no content} it is made once more at the
   * effort {@code effort} supplies, unless it supplies none or {@code ceilingReached} says the
   * review's spend ceiling stops it. The effort is read only once a repeat is due. The repeat is
   * metered too, and {@code onRepeat} runs before it so the review can disclose it. The caller
   * reads the returned result as it read the first one: a repeat that is cut again is reported like
   * any other cut answer.
   */
  static Result<String> blocking(
      String what,
      Supplier<Result<String>> call,
      Supplier<Optional<String>> effortFor,
      BooleanSupplier ceilingReached,
      Consumer<Result<String>> meter,
      Runnable onRepeat) {
    var result = call.get();
    meter.accept(result);
    if (!stoppedWithNoContent(result)) {
      return result;
    }
    var effort = effortFor.get();
    if (effort.isEmpty() || ceilingReached.getAsBoolean()) {
      return result;
    }
    var usage = result.tokenUsage();
    Log.warnf(
        "%s stopped at its response-length cap with no content (%s output tokens): the reasoning"
            + " spent the whole allowance, so the call is repeated once at reasoning_effort=%s",
        what, usage == null ? "unknown" : usage.outputTokenCount(), effort.get());
    onRepeat.run();
    try (var _ = override(effort.get())) {
      result = call.get();
    }
    meter.accept(result);
    return result;
  }

  /** Restores the thread's previous override when closed. */
  static final class Scope implements AutoCloseable {

    private final String previous;

    private Scope(String previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) {
        EFFORT.remove();
      } else {
        EFFORT.set(previous);
      }
    }
  }
}
