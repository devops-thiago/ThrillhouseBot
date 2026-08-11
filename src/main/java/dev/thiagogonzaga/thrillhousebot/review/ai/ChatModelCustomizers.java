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

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Applies the operator's model tuning to the OpenAI-compatible chat models: the reasoning-effort
 * setting ({@code thrillhousebot.ai.reasoning.*}) and the active model's generation parameters
 * ({@code thrillhousebot.ai.models.*} — temperature, top-p, max output tokens, frequency/presence
 * penalties, seed). Every parameter is applied only when configured, so an untouched knob keeps the
 * provider default and non-reasoning models never see a reasoning argument they might reject.
 *
 * <p>Wiring goes through {@link ModelBuilderCustomizer} beans rather than the extension's {@code
 * quarkus.langchain4j.openai.chat-model.*} properties for two reasons: the values must be
 * conditionally omitted entirely when unset, and in quarkus-langchain4j 1.11.2 those properties are
 * only applied to the blocking {@code ChatModel} — the streaming builder never reads them, which
 * would silently skip the main review call ({@link PrReviewer#reviewStream}). Setting the builders'
 * own parameters covers both models and leaves every other default request parameter untouched.
 *
 * <p>Customizers bind to models by CDI qualifier: {@code ModelBuilderCustomizer.applyCustomizers}
 * selects {@code @Default}-qualified beans for the default model and {@code @ModelName}-qualified
 * beans for a named one, with no inheritance either way, and runs them after the extension's config
 * properties, immediately before {@code build()} — so a customizer wins over config. That is why
 * the {@code concise} named model (#498: summary, verifier, replies) has customizers of its own:
 * without them it would silently lose the reasoning/temperature tuning, and with the unqualified
 * ones it would have its response cap ({@code
 * quarkus.langchain4j.openai.concise.chat-model.max-tokens}, aliased to {@code
 * REVIEW_CONCISE_MAX_OUTPUT_TOKENS}) stomped by the active model's {@code max-output-tokens}. The
 * concise pair therefore applies every shared parameter except the response cap.
 */
public final class ChatModelCustomizers {

  private ChatModelCustomizers() {}

  /**
   * The {@code reasoning_effort} wire value to send, or empty when the feature is disabled and the
   * provider default should apply.
   */
  static Optional<String> reasoningEffort(ThrillhouseConfig config) {
    var reasoning = config.ai().reasoning();
    return reasoning.enabled()
        ? Optional.of(
            ThrillhouseConfig.AiPricingConfig.ReasoningConfig.normalize(reasoning.effort()))
        : Optional.empty();
  }

  /**
   * The shared customizer body, applied through one builder flavour's setter references — the
   * blocking and streaming builders share no supertype, so the four customizers adapt via {@link
   * #BLOCKING_TUNING}/{@link #STREAMING_TUNING} instead of each repeating the knob list. {@code
   * applyResponseCap} is false for the concise pair, which must never see the active model's {@code
   * max-output-tokens} (it would stomp the named config block's cap).
   */
  private record SharedTuning<B>(
      BiConsumer<B, String> reasoningEffort,
      BiConsumer<B, Double> temperature,
      BiConsumer<B, Double> topP,
      BiConsumer<B, Integer> maxTokens,
      BiConsumer<B, Double> frequencyPenalty,
      BiConsumer<B, Double> presencePenalty,
      BiConsumer<B, Integer> seed) {

    void apply(
        ThrillhouseConfig config,
        ActiveModelSettings activeModel,
        B builder,
        boolean applyResponseCap) {
      ChatModelCustomizers.reasoningEffort(config)
          .ifPresent(v -> reasoningEffort.accept(builder, v));
      activeModel.temperature().ifPresent(v -> temperature.accept(builder, v));
      activeModel.topP().ifPresent(v -> topP.accept(builder, v));
      if (applyResponseCap) {
        activeModel.maxOutputTokens().ifPresent(v -> maxTokens.accept(builder, v));
      }
      activeModel.frequencyPenalty().ifPresent(v -> frequencyPenalty.accept(builder, v));
      activeModel.presencePenalty().ifPresent(v -> presencePenalty.accept(builder, v));
      activeModel.seed().ifPresent(v -> seed.accept(builder, v));
    }
  }

  private static final SharedTuning<OpenAiChatModel.OpenAiChatModelBuilder> BLOCKING_TUNING =
      new SharedTuning<>(
          OpenAiChatModel.OpenAiChatModelBuilder::reasoningEffort,
          OpenAiChatModel.OpenAiChatModelBuilder::temperature,
          OpenAiChatModel.OpenAiChatModelBuilder::topP,
          OpenAiChatModel.OpenAiChatModelBuilder::maxTokens,
          OpenAiChatModel.OpenAiChatModelBuilder::frequencyPenalty,
          OpenAiChatModel.OpenAiChatModelBuilder::presencePenalty,
          OpenAiChatModel.OpenAiChatModelBuilder::seed);

  private static final SharedTuning<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder>
      STREAMING_TUNING =
          new SharedTuning<>(
              OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder::reasoningEffort,
              OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder::temperature,
              OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder::topP,
              OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder::maxTokens,
              OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder::frequencyPenalty,
              OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder::presencePenalty,
              OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder::seed);

  /** Tuning for the blocking model (verifier, describe, changelog, docs, replies). */
  @ApplicationScoped
  static class ChatModelCustomizer
      implements ModelBuilderCustomizer<OpenAiChatModel.OpenAiChatModelBuilder> {

    private final ThrillhouseConfig config;
    private final ActiveModelSettings activeModel;

    ChatModelCustomizer(ThrillhouseConfig config, ActiveModelSettings activeModel) {
      this.config = config;
      this.activeModel = activeModel;
    }

    @Override
    public void customize(OpenAiChatModel.OpenAiChatModelBuilder builder) {
      BLOCKING_TUNING.apply(config, activeModel, builder, true);
    }
  }

  /** Tuning for the streaming model (the main PR review call). */
  @ApplicationScoped
  static class StreamingChatModelCustomizer
      implements ModelBuilderCustomizer<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder> {

    private final ThrillhouseConfig config;
    private final ActiveModelSettings activeModel;

    StreamingChatModelCustomizer(ThrillhouseConfig config, ActiveModelSettings activeModel) {
      this.config = config;
      this.activeModel = activeModel;
    }

    @Override
    public void customize(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
      STREAMING_TUNING.apply(config, activeModel, builder, true);
    }
  }

  /**
   * Tuning for the concise named model's blocking bean (verifier, replies). Applies the same
   * reasoning and generation parameters as the default model's customizer, but never {@code
   * maxTokens}: the concise response cap comes from the named config block and applying the active
   * model's {@code max-output-tokens} here would overwrite it — customizers run after config
   * properties.
   */
  @ApplicationScoped
  @ModelName("concise")
  static class ConciseChatModelCustomizer
      implements ModelBuilderCustomizer<OpenAiChatModel.OpenAiChatModelBuilder> {

    private final ThrillhouseConfig config;
    private final ActiveModelSettings activeModel;

    ConciseChatModelCustomizer(ThrillhouseConfig config, ActiveModelSettings activeModel) {
      this.config = config;
      this.activeModel = activeModel;
    }

    @Override
    public void customize(OpenAiChatModel.OpenAiChatModelBuilder builder) {
      BLOCKING_TUNING.apply(config, activeModel, builder, false);
    }
  }

  /**
   * Tuning for the concise named model's streaming bean (the final summary call). Same contract as
   * {@link ConciseChatModelCustomizer}: everything shared, never {@code maxTokens}.
   */
  @ApplicationScoped
  @ModelName("concise")
  static class ConciseStreamingChatModelCustomizer
      implements ModelBuilderCustomizer<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder> {

    private final ThrillhouseConfig config;
    private final ActiveModelSettings activeModel;

    ConciseStreamingChatModelCustomizer(ThrillhouseConfig config, ActiveModelSettings activeModel) {
      this.config = config;
      this.activeModel = activeModel;
    }

    @Override
    public void customize(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
      STREAMING_TUNING.apply(config, activeModel, builder, false);
    }
  }
}
