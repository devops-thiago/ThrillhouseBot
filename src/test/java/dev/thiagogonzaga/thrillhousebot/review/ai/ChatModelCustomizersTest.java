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

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatModelCustomizersTest {

  private static final String MODEL = "deepseek-chat";

  private static ThrillhouseConfig config(
      boolean reasoningEnabled,
      String effort,
      Map<String, ThrillhouseConfig.AiPricingConfig.ModelSettings> models) {
    return config(reasoningEnabled, effort, Optional.empty(), models);
  }

  private static ThrillhouseConfig config(
      boolean reasoningEnabled,
      String effort,
      Optional<String> conciseEffort,
      Map<String, ThrillhouseConfig.AiPricingConfig.ModelSettings> models) {
    var config = mock(ThrillhouseConfig.class);
    var ai = mock(ThrillhouseConfig.AiPricingConfig.class);
    var reasoning = mock(ThrillhouseConfig.AiPricingConfig.ReasoningConfig.class);
    lenient().when(config.ai()).thenReturn(ai);
    lenient().when(ai.reasoning()).thenReturn(reasoning);
    lenient().when(ai.models()).thenReturn(models);
    lenient().when(reasoning.enabled()).thenReturn(reasoningEnabled);
    lenient().when(reasoning.effort()).thenReturn(effort);
    lenient().when(reasoning.conciseEffort()).thenReturn(conciseEffort);
    return config;
  }

  private static ThrillhouseConfig.AiPricingConfig.ModelSettings settings(
      Optional<Double> temperature, Optional<Double> topP, Optional<Integer> maxOutputTokens) {
    var settings = mock(ThrillhouseConfig.AiPricingConfig.ModelSettings.class);
    lenient().when(settings.temperature()).thenReturn(temperature);
    lenient().when(settings.topP()).thenReturn(topP);
    lenient().when(settings.maxOutputTokens()).thenReturn(maxOutputTokens);
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.empty());
    lenient().when(settings.outputBufferTokens()).thenReturn(Optional.empty());
    lenient().when(settings.tokenSafetyMargin()).thenReturn(Optional.empty());
    lenient().when(settings.frequencyPenalty()).thenReturn(Optional.empty());
    lenient().when(settings.presencePenalty()).thenReturn(Optional.empty());
    lenient().when(settings.seed()).thenReturn(Optional.empty());
    return settings;
  }

  private static ThrillhouseConfig.AiPricingConfig.ModelSettings settingsWithPenaltiesAndSeed(
      double frequencyPenalty, double presencePenalty, int seed) {
    var settings = settings(Optional.empty(), Optional.empty(), Optional.empty());
    lenient().when(settings.frequencyPenalty()).thenReturn(Optional.of(frequencyPenalty));
    lenient().when(settings.presencePenalty()).thenReturn(Optional.of(presencePenalty));
    lenient().when(settings.seed()).thenReturn(Optional.of(seed));
    return settings;
  }

  private static ActiveModelSettings activeModel(ThrillhouseConfig config) {
    return new ActiveModelSettings(config, MODEL);
  }

  @Test
  void chatModelGetsNormalizedEffortWhenEnabled() {
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var config = config(true, " Medium ", Map.of());

    new ChatModelCustomizers.ChatModelCustomizer(config, activeModel(config)).customize(builder);

    verify(builder).reasoningEffort("medium");
  }

  @Test
  void chatModelIsLeftUntouchedWhenDisabled() {
    // Disabled must not send any reasoning parameter — non-reasoning models could reject it, and
    // the provider default must keep applying.
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var config = config(false, "high", Map.of());

    new ChatModelCustomizers.ChatModelCustomizer(config, activeModel(config)).customize(builder);

    verifyNoInteractions(builder);
  }

  @Test
  void streamingModelGetsNormalizedEffortWhenEnabled() {
    var builder = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);
    var config = config(true, "none", Map.of());

    new ChatModelCustomizers.StreamingChatModelCustomizer(config, activeModel(config))
        .customize(builder);

    verify(builder).reasoningEffort("none");
  }

  @Test
  void streamingModelIsLeftUntouchedWhenDisabled() {
    var builder = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);
    var config = config(false, "low", Map.of());

    new ChatModelCustomizers.StreamingChatModelCustomizer(config, activeModel(config))
        .customize(builder);

    verifyNoInteractions(builder);
  }

  @Test
  void chatModelGetsTheActiveModelsGenerationParameters() {
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var config =
        config(
            false,
            "low",
            Map.of(MODEL, settings(Optional.of(0.2), Optional.of(0.95), Optional.of(4096))));

    new ChatModelCustomizers.ChatModelCustomizer(config, activeModel(config)).customize(builder);

    verify(builder).temperature(0.2);
    verify(builder).topP(0.95);
    verify(builder).maxTokens(4096);
    verifyNoMoreInteractions(builder);
  }

  @Test
  void streamingModelGetsTheActiveModelsGenerationParameters() {
    var builder = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);
    var config =
        config(
            false,
            "low",
            Map.of(MODEL, settings(Optional.of(0.2), Optional.of(0.95), Optional.of(4096))));

    new ChatModelCustomizers.StreamingChatModelCustomizer(config, activeModel(config))
        .customize(builder);

    verify(builder).temperature(0.2);
    verify(builder).topP(0.95);
    verify(builder).maxTokens(4096);
    verifyNoMoreInteractions(builder);
  }

  @Test
  void chatModelGetsPenaltiesAndSeed() {
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var config = config(false, "low", Map.of(MODEL, settingsWithPenaltiesAndSeed(0.5, -0.5, 42)));

    new ChatModelCustomizers.ChatModelCustomizer(config, activeModel(config)).customize(builder);

    verify(builder).frequencyPenalty(0.5);
    verify(builder).presencePenalty(-0.5);
    verify(builder).seed(42);
    verifyNoMoreInteractions(builder);
  }

  @Test
  void streamingModelGetsPenaltiesAndSeed() {
    var builder = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);
    var config = config(false, "low", Map.of(MODEL, settingsWithPenaltiesAndSeed(0.5, -0.5, 42)));

    new ChatModelCustomizers.StreamingChatModelCustomizer(config, activeModel(config))
        .customize(builder);

    verify(builder).frequencyPenalty(0.5);
    verify(builder).presencePenalty(-0.5);
    verify(builder).seed(42);
    verifyNoMoreInteractions(builder);
  }

  @Test
  void conciseChatModelGetsSharedTuningButNeverAResponseCap() {
    // The concise model's cap comes from its named config block
    // (quarkus.langchain4j.openai.concise.chat-model.max-tokens); customizers run after config
    // properties, so a maxTokens call here would stomp the cap the named model exists to carry.
    // The reasoning effort is the lane's own default, not the active model's "Medium" (#567).
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var config =
        config(
            true,
            "Medium",
            Map.of(MODEL, settings(Optional.of(0.2), Optional.of(0.95), Optional.of(384_000))));

    new ChatModelCustomizers.ConciseChatModelCustomizer(config, activeModel(config))
        .customize(builder);

    verify(builder).reasoningEffort("low");
    verify(builder).temperature(0.2);
    verify(builder).topP(0.95);
    verifyNoMoreInteractions(builder);
  }

  @Test
  void conciseStreamingModelGetsSharedTuningButNeverAResponseCap() {
    var builder = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);
    var config =
        config(
            true,
            "Medium",
            Map.of(MODEL, settings(Optional.of(0.2), Optional.of(0.95), Optional.of(384_000))));

    new ChatModelCustomizers.ConciseStreamingChatModelCustomizer(config, activeModel(config))
        .customize(builder);

    verify(builder).reasoningEffort("low");
    verify(builder).temperature(0.2);
    verify(builder).topP(0.95);
    verifyNoMoreInteractions(builder);
  }

  @Test
  void conciseModelsDoNotInheritTheActiveModelsMaxEffort() {
    // #567: reasoning tokens are billed as output and count against the concise response cap, so a
    // max-effort active model could leave the verifier with no allowance left for content and no
    // body at all. The lane caps its own effort instead.
    var blocking = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var streaming = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);
    var config = config(true, "max", Map.of());

    new ChatModelCustomizers.ConciseChatModelCustomizer(config, activeModel(config))
        .customize(blocking);
    new ChatModelCustomizers.ConciseStreamingChatModelCustomizer(config, activeModel(config))
        .customize(streaming);

    verify(blocking).reasoningEffort("low");
    verify(streaming).reasoningEffort("low");
    verifyNoMoreInteractions(blocking, streaming);
  }

  @Test
  void theActiveLaneKeepsMaxEffortWhileTheConciseLaneIsCapped() {
    // The dial the operator set still drives the review itself — only the fixed-shape calls change.
    var blocking = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var streaming = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);
    var config = config(true, "max", Map.of());

    new ChatModelCustomizers.ChatModelCustomizer(config, activeModel(config)).customize(blocking);
    new ChatModelCustomizers.StreamingChatModelCustomizer(config, activeModel(config))
        .customize(streaming);

    verify(blocking).reasoningEffort("max");
    verify(streaming).reasoningEffort("max");
    verifyNoMoreInteractions(blocking, streaming);
  }

  @Test
  void anExplicitConciseEffortWinsOverTheLanesDefault() {
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var config = config(true, "max", Optional.of(" High "), Map.of());

    new ChatModelCustomizers.ConciseChatModelCustomizer(config, activeModel(config))
        .customize(builder);

    verify(builder).reasoningEffort("high");
    verifyNoMoreInteractions(builder);
  }

  @Test
  void theConciseLaneNeverReasonsMoreThanTheActiveLane() {
    // An operator who asked for 'none' everywhere must not get 'low' on the concise calls just
    // because that is this lane's ceiling.
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var config = config(true, "None", Map.of());

    new ChatModelCustomizers.ConciseChatModelCustomizer(config, activeModel(config))
        .customize(builder);

    verify(builder).reasoningEffort("none");
    verifyNoMoreInteractions(builder);
  }

  @Test
  void anUnrecognizedActiveEffortLeavesTheConciseLaneAtItsDefault() {
    // Boot fails over the typo (StartupConfigValidator); the lane must still resolve to a value it
    // can send rather than propagating the unknown tier.
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var config = config(true, "maximum", Map.of());

    new ChatModelCustomizers.ConciseChatModelCustomizer(config, activeModel(config))
        .customize(builder);

    verify(builder).reasoningEffort("low");
    verifyNoMoreInteractions(builder);
  }

  @Test
  void conciseModelsSendNoEffortWhileReasoningIsDisabled() {
    // Disabled still means no reasoning parameter at all on either lane — the concise default must
    // not smuggle one onto a non-reasoning model.
    var blocking = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var streaming = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);
    var config = config(false, "max", Optional.of("high"), Map.of());

    new ChatModelCustomizers.ConciseChatModelCustomizer(config, activeModel(config))
        .customize(blocking);
    new ChatModelCustomizers.ConciseStreamingChatModelCustomizer(config, activeModel(config))
        .customize(streaming);

    verifyNoInteractions(blocking, streaming);
  }

  @Test
  void conciseCustomizersApplyPenaltiesAndSeed() {
    var blocking = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var streaming = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);
    var config = config(false, "low", Map.of(MODEL, settingsWithPenaltiesAndSeed(0.5, -0.5, 42)));

    new ChatModelCustomizers.ConciseChatModelCustomizer(config, activeModel(config))
        .customize(blocking);
    new ChatModelCustomizers.ConciseStreamingChatModelCustomizer(config, activeModel(config))
        .customize(streaming);

    verify(blocking).frequencyPenalty(0.5);
    verify(blocking).presencePenalty(-0.5);
    verify(blocking).seed(42);
    verifyNoMoreInteractions(blocking);
    verify(streaming).frequencyPenalty(0.5);
    verify(streaming).presencePenalty(-0.5);
    verify(streaming).seed(42);
    verifyNoMoreInteractions(streaming);
  }

  @Test
  void anotherModelsEntryIsNotAppliedToTheActiveModel() {
    // A tuning entry keyed to a different model name must not leak into the active model's calls.
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var config =
        config(
            false,
            "low",
            Map.of(
                "some-other-model",
                settings(Optional.of(1.5), Optional.of(0.5), Optional.of(1024))));

    new ChatModelCustomizers.ChatModelCustomizer(config, activeModel(config)).customize(builder);

    verifyNoInteractions(builder);
  }

  @Test
  void partialGenerationParametersLeaveTheRestAtProviderDefaults() {
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
    var config =
        config(
            false,
            "low",
            Map.of(MODEL, settings(Optional.of(0.0), Optional.empty(), Optional.empty())));

    new ChatModelCustomizers.ChatModelCustomizer(config, activeModel(config)).customize(builder);

    verify(builder).temperature(0.0);
    verifyNoMoreInteractions(builder);
  }
}
