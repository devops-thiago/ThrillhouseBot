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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReasoningStepDownStreamingModel} (#839): the request is rewritten only for
 * a call bound as reasoning-disabled, and the rewrite keeps what the request already carried.
 */
class ReasoningStepDownStreamingModelTest {

  private static final ChatRequest REQUEST =
      ChatRequest.builder()
          .messages(List.of(UserMessage.from("review this")))
          .parameters(ChatRequestParameters.builder().temperature(0.5).build())
          .build();

  private final List<ChatRequest> seen = new ArrayList<>();
  private final StreamingChatModel delegate =
      new StreamingChatModel() {
        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
          seen.add(request);
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
          return OpenAiChatRequestParameters.builder().reasoningEffort("max").build();
        }
      };
  private final ReasoningStepDownStreamingModel model =
      new ReasoningStepDownStreamingModel(delegate);

  @AfterEach
  void clearBinding() {
    ReviewSessionContext.reset();
  }

  @Test
  void aCallBoundAsReasoningDisabledGoesOutWithEffortNone() {
    ReviewSessionContext.bind(1L, 1, true);

    model.chat(REQUEST, handler());

    var sent = assertInstanceOf(OpenAiChatRequestParameters.class, seen.get(0).parameters());
    assertEquals("none", sent.reasoningEffort());
    assertEquals(0.5, sent.temperature(), "the request's own values survive the rewrite");
    assertEquals(REQUEST.messages(), seen.get(0).messages());
  }

  @Test
  void aCallBoundAtTheConfiguredEffortGoesOutUntouched() {
    ReviewSessionContext.bind(1L, 1, false);

    model.chat(REQUEST, handler());

    assertSame(REQUEST, seen.get(0), "nothing is rebuilt for an ordinary call");
  }

  @Test
  void aCallWithNoBindingAtAllGoesOutUntouched() {
    model.chat(REQUEST, handler());

    assertSame(REQUEST, seen.get(0));
  }

  /**
   * The provider model merges a call's parameters over its builder-level defaults through its own
   * builder; the rewritten value has to win that merge for the effort and lose it for nothing else.
   */
  @Test
  void theRewrittenParametersWinTheProviderMergeForTheEffortOnly() {
    var configured =
        OpenAiChatRequestParameters.builder()
            .reasoningEffort("max")
            .maxOutputTokens(4096)
            .temperature(0.3)
            .build();

    var effective =
        assertInstanceOf(
            OpenAiChatRequestParameters.class,
            configured.overrideWith(
                ReasoningStepDownStreamingModel.withReasoningDisabled(REQUEST).parameters()));

    assertEquals("none", effective.reasoningEffort());
    assertEquals(4096, effective.maxOutputTokens());
    assertEquals(0.5, effective.temperature(), "the call's own temperature overrides the default");
  }

  @Test
  void defaultRequestParametersAreTheDelegates() {
    assertEquals(
        "max", ((OpenAiChatRequestParameters) model.defaultRequestParameters()).reasoningEffort());
  }

  @Test
  void bothSuppliersWrapTheModelTheyAreGiven() {
    ReviewSessionContext.bind(1L, 1, true);

    new ReasoningStepDownStreamingModel.ActiveSupplier(delegate).get().chat(REQUEST, handler());
    new ReasoningStepDownStreamingModel.ConciseSupplier(delegate).get().chat(REQUEST, handler());

    assertEquals(2, seen.size());
    for (var request : seen) {
      assertEquals(
          "none",
          assertInstanceOf(OpenAiChatRequestParameters.class, request.parameters())
              .reasoningEffort());
    }
  }

  @Test
  void theBindingCarriesTheFlagAndClearsWithIt() {
    ReviewSessionContext.bind(9L, 2, true);
    assertTrue(ReviewSessionContext.reasoningDisabledForCurrentCall());
    ReviewSessionContext.clear();
    assertFalse(ReviewSessionContext.reasoningDisabledForCurrentCall(), "no binding, no flag");
    ReviewSessionContext.bind(9L, 2);
    assertFalse(
        ReviewSessionContext.reasoningDisabledForCurrentCall(),
        "the two-argument bind is an ordinary call at the configured effort");
  }

  private static StreamingChatResponseHandler handler() {
    return new StreamingChatResponseHandler() {
      @Override
      public void onPartialResponse(String partialResponse) {}

      @Override
      public void onCompleteResponse(
          dev.langchain4j.model.chat.response.ChatResponse completeResponse) {}

      @Override
      public void onError(Throwable error) {}
    };
  }
}
