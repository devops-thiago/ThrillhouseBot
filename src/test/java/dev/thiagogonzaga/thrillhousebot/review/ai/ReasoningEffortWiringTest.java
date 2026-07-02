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
 * Boots the real quarkus-langchain4j wiring with reasoning enabled and asserts both model beans
 * carry the configured {@code reasoning_effort} (normalized to lowercase) in their default request
 * parameters.
 *
 * <p>The streaming assertion is the important one: in quarkus-langchain4j 1.11.2 the {@code
 * quarkus.langchain4j.openai.chat-model.reasoning-effort} property is applied to the blocking model
 * only, so this test pins that the {@link ReasoningEffortCustomizers} route reaches the streaming
 * model used by the main PR review call. It would fail if the wiring ever regressed to the config
 * property.
 */
@QuarkusTest
@TestProfile(ReasoningEffortWiringTest.ReasoningEnabled.class)
class ReasoningEffortWiringTest {

  @Inject ChatModel chatModel;
  @Inject StreamingChatModel streamingChatModel;

  @Test
  void blockingModelCarriesConfiguredReasoningEffort() {
    var params =
        assertInstanceOf(OpenAiChatRequestParameters.class, chatModel.defaultRequestParameters());
    assertEquals("medium", params.reasoningEffort());
  }

  @Test
  void streamingModelCarriesConfiguredReasoningEffort() {
    var params =
        assertInstanceOf(
            OpenAiChatRequestParameters.class, streamingChatModel.defaultRequestParameters());
    assertEquals("medium", params.reasoningEffort());
  }

  public static class ReasoningEnabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Mixed case on purpose: the wire value must arrive normalized.
      return Map.of(
          "thrillhousebot.ai.reasoning.enabled", "true",
          "thrillhousebot.ai.reasoning.effort", "Medium");
    }
  }
}
