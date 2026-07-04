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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Default profile: reasoning is off, so no {@code reasoning_effort} reaches either model and the
 * provider default keeps applying — today's behavior is preserved for non-reasoning models.
 */
@QuarkusTest
class ReasoningEffortDefaultOffTest {

  @Inject ChatModel chatModel;
  @Inject StreamingChatModel streamingChatModel;

  @Test
  void noReasoningEffortIsSentByDefault() {
    var blocking =
        assertInstanceOf(OpenAiChatRequestParameters.class, chatModel.defaultRequestParameters());
    assertNull(blocking.reasoningEffort());

    var streaming =
        assertInstanceOf(
            OpenAiChatRequestParameters.class, streamingChatModel.defaultRequestParameters());
    assertNull(streaming.reasoningEffort());
  }
}
