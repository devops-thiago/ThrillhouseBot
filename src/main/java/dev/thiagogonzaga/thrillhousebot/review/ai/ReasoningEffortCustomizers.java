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
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * Applies the operator's reasoning-effort setting ({@code thrillhousebot.ai.reasoning.*}) to the
 * OpenAI-compatible chat models.
 *
 * <p>Wiring goes through {@link ModelBuilderCustomizer} beans rather than the extension's {@code
 * quarkus.langchain4j.openai.chat-model.reasoning-effort} property for two reasons: the flag must
 * conditionally omit the parameter entirely when disabled (so non-reasoning models never see an
 * argument they might reject), and in quarkus-langchain4j 1.11.2 that property is only applied to
 * the blocking {@code ChatModel} — the streaming builder never reads it, which would silently skip
 * the main review call ({@link PrReviewer#reviewStream}). Setting the builder's own {@code
 * reasoningEffort} covers both models and leaves every other default request parameter untouched.
 */
public final class ReasoningEffortCustomizers {

  private ReasoningEffortCustomizers() {}

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

  /** Reasoning effort for the blocking model (verifier, describe, changelog, docs, replies). */
  @ApplicationScoped
  static class ChatModelCustomizer
      implements ModelBuilderCustomizer<OpenAiChatModel.OpenAiChatModelBuilder> {

    private final ThrillhouseConfig config;

    ChatModelCustomizer(ThrillhouseConfig config) {
      this.config = config;
    }

    @Override
    public void customize(OpenAiChatModel.OpenAiChatModelBuilder builder) {
      reasoningEffort(config).ifPresent(builder::reasoningEffort);
    }
  }

  /** Reasoning effort for the streaming model (the main PR review call). */
  @ApplicationScoped
  static class StreamingChatModelCustomizer
      implements ModelBuilderCustomizer<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder> {

    private final ThrillhouseConfig config;

    StreamingChatModelCustomizer(ThrillhouseConfig config) {
      this.config = config;
    }

    @Override
    public void customize(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
      reasoningEffort(config).ifPresent(builder::reasoningEffort);
    }
  }
}
