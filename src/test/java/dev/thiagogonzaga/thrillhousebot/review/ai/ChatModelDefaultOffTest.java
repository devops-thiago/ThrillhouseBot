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
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Default profile: reasoning is off and no per-model settings entry exists, so no {@code
 * reasoning_effort} and no {@code max_tokens} reach either default model, and temperature/top-p
 * stay at the quarkus-langchain4j extension defaults (1.0/1.0) — today's behavior is preserved for
 * untuned deployments. The {@code concise} named model differs in exactly one way: it always
 * carries its response cap ({@code REVIEW_CONCISE_MAX_OUTPUT_TOKENS}, default 8192), because the
 * summary/verifier/reply responses are fixed-shape and must not run unbounded.
 */
@QuarkusTest
class ChatModelDefaultOffTest {

  @Inject ChatModel chatModel;
  @Inject StreamingChatModel streamingChatModel;

  @Inject
  @ModelName("concise")
  ChatModel conciseChatModel;

  @Inject
  @ModelName("concise")
  StreamingChatModel conciseStreamingChatModel;

  @Test
  void noTuningIsSentByDefault() {
    var blocking =
        assertInstanceOf(OpenAiChatRequestParameters.class, chatModel.defaultRequestParameters());
    assertNull(blocking.reasoningEffort());
    assertNull(blocking.maxOutputTokens());
    assertEquals(1.0, blocking.temperature());
    assertEquals(1.0, blocking.topP());

    var streaming =
        assertInstanceOf(
            OpenAiChatRequestParameters.class, streamingChatModel.defaultRequestParameters());
    assertNull(streaming.reasoningEffort());
    assertNull(streaming.maxOutputTokens());
    assertEquals(1.0, streaming.temperature());
    assertEquals(1.0, streaming.topP());
  }

  @Test
  void conciseModelsCarryOnlyTheirResponseCapByDefault() {
    var blocking =
        assertInstanceOf(
            OpenAiChatRequestParameters.class, conciseChatModel.defaultRequestParameters());
    assertEquals(8192, blocking.maxOutputTokens(), "the concise default cap must apply");
    assertNull(blocking.reasoningEffort());
    assertEquals(1.0, blocking.temperature());
    assertEquals(1.0, blocking.topP());

    var streaming =
        assertInstanceOf(
            OpenAiChatRequestParameters.class,
            conciseStreamingChatModel.defaultRequestParameters());
    assertEquals(8192, streaming.maxOutputTokens(), "the concise default cap must apply");
    assertNull(streaming.reasoningEffort());
    assertEquals(1.0, streaming.temperature());
    assertEquals(1.0, streaming.topP());
  }
}
