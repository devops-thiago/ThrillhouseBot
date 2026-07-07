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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Boots the real quarkus-langchain4j wiring with reasoning enabled and a per-model settings entry
 * for the configured model, and asserts both model beans carry the configured {@code
 * reasoning_effort} (normalized to lowercase, #65) and the per-model generation parameters (#50) in
 * their default request parameters.
 *
 * <p>The streaming assertions are the important ones: in quarkus-langchain4j 1.11.2 the {@code
 * quarkus.langchain4j.openai.chat-model.*} properties are applied to the blocking model only, so
 * this test pins that the {@link ChatModelCustomizers} route reaches the streaming model used by
 * the main PR review call. It would fail if the wiring ever regressed to the config properties.
 */
@QuarkusTest
@TestProfile(ChatModelWiringTest.TuningEnabled.class)
class ChatModelWiringTest {

  @Inject ChatModel chatModel;
  @Inject StreamingChatModel streamingChatModel;

  @Test
  void blockingModelCarriesConfiguredTuning() {
    var params =
        assertInstanceOf(OpenAiChatRequestParameters.class, chatModel.defaultRequestParameters());
    assertEquals("medium", params.reasoningEffort());
    assertEquals(0.3, params.temperature());
    assertEquals(0.95, params.topP());
    assertEquals(4096, params.maxOutputTokens());
  }

  @Test
  void streamingModelCarriesConfiguredTuning() {
    var params =
        assertInstanceOf(
            OpenAiChatRequestParameters.class, streamingChatModel.defaultRequestParameters());
    assertEquals("medium", params.reasoningEffort());
    assertEquals(0.3, params.temperature());
    assertEquals(0.95, params.topP());
    assertEquals(4096, params.maxOutputTokens());
  }

  public static class TuningEnabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Mixed case on purpose: the wire value must arrive normalized. The models key matches the
      // default test model name (AI_MODEL is unset in tests, so deepseek-chat applies).
      return Map.of(
          "thrillhousebot.ai.reasoning.enabled", "true",
          "thrillhousebot.ai.reasoning.effort", "Medium",
          "thrillhousebot.ai.models.deepseek-chat.temperature", "0.3",
          "thrillhousebot.ai.models.deepseek-chat.top-p", "0.95",
          "thrillhousebot.ai.models.deepseek-chat.max-output-tokens", "4096");
    }
  }
}
