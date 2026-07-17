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
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

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
      reasoningEffort(config).ifPresent(builder::reasoningEffort);
      activeModel.temperature().ifPresent(builder::temperature);
      activeModel.topP().ifPresent(builder::topP);
      activeModel.maxOutputTokens().ifPresent(builder::maxTokens);
      activeModel.frequencyPenalty().ifPresent(builder::frequencyPenalty);
      activeModel.presencePenalty().ifPresent(builder::presencePenalty);
      activeModel.seed().ifPresent(builder::seed);
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
      reasoningEffort(config).ifPresent(builder::reasoningEffort);
      activeModel.temperature().ifPresent(builder::temperature);
      activeModel.topP().ifPresent(builder::topP);
      activeModel.maxOutputTokens().ifPresent(builder::maxTokens);
      activeModel.frequencyPenalty().ifPresent(builder::frequencyPenalty);
      activeModel.presencePenalty().ifPresent(builder::presencePenalty);
      activeModel.seed().ifPresent(builder::seed);
    }
  }
}
