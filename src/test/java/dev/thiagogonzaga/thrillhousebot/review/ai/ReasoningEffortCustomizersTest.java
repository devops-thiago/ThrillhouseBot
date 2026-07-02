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

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import org.junit.jupiter.api.Test;

class ReasoningEffortCustomizersTest {

  private static ThrillhouseConfig config(boolean enabled, String effort) {
    var config = mock(ThrillhouseConfig.class);
    var ai = mock(ThrillhouseConfig.AiPricingConfig.class);
    var reasoning = mock(ThrillhouseConfig.AiPricingConfig.ReasoningConfig.class);
    lenient().when(config.ai()).thenReturn(ai);
    lenient().when(ai.reasoning()).thenReturn(reasoning);
    lenient().when(reasoning.enabled()).thenReturn(enabled);
    lenient().when(reasoning.effort()).thenReturn(effort);
    return config;
  }

  @Test
  void chatModelGetsNormalizedEffortWhenEnabled() {
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);

    new ReasoningEffortCustomizers.ChatModelCustomizer(config(true, " Medium ")).customize(builder);

    verify(builder).reasoningEffort("medium");
  }

  @Test
  void chatModelIsLeftUntouchedWhenDisabled() {
    // Disabled must not send any reasoning parameter — non-reasoning models could reject it, and
    // the provider default must keep applying.
    var builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);

    new ReasoningEffortCustomizers.ChatModelCustomizer(config(false, "high")).customize(builder);

    verifyNoInteractions(builder);
  }

  @Test
  void streamingModelGetsNormalizedEffortWhenEnabled() {
    var builder = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);

    new ReasoningEffortCustomizers.StreamingChatModelCustomizer(config(true, "none"))
        .customize(builder);

    verify(builder).reasoningEffort("none");
  }

  @Test
  void streamingModelIsLeftUntouchedWhenDisabled() {
    var builder = mock(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class);

    new ReasoningEffortCustomizers.StreamingChatModelCustomizer(config(false, "low"))
        .customize(builder);

    verifyNoInteractions(builder);
  }
}
