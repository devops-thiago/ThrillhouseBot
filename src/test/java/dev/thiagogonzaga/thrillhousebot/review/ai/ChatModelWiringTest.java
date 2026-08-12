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

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Boots the real quarkus-langchain4j wiring with reasoning enabled and a per-model settings entry
 * for the configured model, and asserts both model beans carry the configured {@code
 * reasoning_effort} (normalized to lowercase) and the per-model generation parameters in their
 * default request parameters.
 *
 * <p>The streaming assertions are the important ones: in quarkus-langchain4j 1.11.2 the {@code
 * quarkus.langchain4j.openai.chat-model.*} properties are applied to the blocking model only, so
 * this test pins that the {@link ChatModelCustomizers} route reaches the streaming model used by
 * the main PR review call. It would fail if the wiring ever regressed to the config properties.
 *
 * <p>The {@code concise} named model (summary, verifier, replies — #498) is pinned separately:
 * customizers bind by CDI qualifier ({@code applyCustomizers} selects {@code @Default} beans for
 * the default model and {@code @ModelName}-qualified beans for a named one), so these assertions
 * prove the named model carries its own response cap ({@code REVIEW_CONCISE_MAX_OUTPUT_TOKENS}
 * default 8192) instead of the active model's {@code max-output-tokens}, and its own {@code
 * reasoning_effort} instead of the active model's (#567), while still receiving the shared
 * temperature tuning — and that the batch-review models keep theirs.
 */
@QuarkusTest
@TestProfile(ChatModelWiringTest.TuningEnabled.class)
class ChatModelWiringTest {

  @Inject ChatModel chatModel;
  @Inject StreamingChatModel streamingChatModel;

  @Inject
  @ModelName("concise")
  ChatModel conciseChatModel;

  @Inject
  @ModelName("concise")
  StreamingChatModel conciseStreamingChatModel;

  @Inject PrReviewer prReviewer;

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

  @Test
  void conciseBlockingModelCarriesTheConciseCapAndTheSharedTuning() {
    var params =
        assertInstanceOf(
            OpenAiChatRequestParameters.class, conciseChatModel.defaultRequestParameters());
    assertEquals(
        8192,
        params.maxOutputTokens(),
        "the concise response cap must apply, not the active model's max-output-tokens");
    assertEquals(
        "low",
        params.reasoningEffort(),
        "the concise lane sends its own effort, not the active model's 'Medium'");
    assertEquals(0.3, params.temperature());
    assertEquals(0.95, params.topP());
  }

  @Test
  void conciseStreamingModelCarriesTheConciseCapAndTheSharedTuning() {
    // The summary call streams, so the named model's streaming bean is the one that matters.
    var params =
        assertInstanceOf(
            OpenAiChatRequestParameters.class,
            conciseStreamingChatModel.defaultRequestParameters());
    assertEquals(
        8192,
        params.maxOutputTokens(),
        "the concise response cap must apply, not the active model's max-output-tokens");
    assertEquals(
        "low",
        params.reasoningEffort(),
        "the concise lane sends its own effort, not the active model's 'Medium'");
    assertEquals(0.3, params.temperature());
    assertEquals(0.95, params.topP());
  }

  /**
   * Review calls are stateless by design — state is carried by the previous-findings context, the
   * code check for whether a finding was fixed, and user comments, never by conversation history.
   * Without an explicit opt-out every {@code @RegisterAiService} interface gets the extension's
   * default {@code MessageWindowChatMemory}, and because none of them declare {@code @MemoryId}
   * every call shares one memory id: the second review would resend the first review's prompt and
   * answer (#584).
   *
   * <p>The two reviews run on a plain virtual-thread executor, the shape {@code
   * ReviewExecutorProducer} gives the real review path, so no CDI request context is active — the
   * only default memory-id provider the extension ships resolves the request-context state and
   * returns {@code null} there, leaving the constant fallback id shared by every PR the process
   * ever reviews. Distinct diffs make that concrete: neither request may carry the other's.
   */
  @Test
  void reviewRequestsCarryOnlyTheCurrentUserMessage() throws Exception {
    var requests = new CopyOnWriteArrayList<List<ChatMessage>>();
    QuarkusMock.installMockForType(
        new CapturingStreamingChatModel(requests), StreamingChatModel.class);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> streamOneReview("diff-of-the-first-pr")).get(30, TimeUnit.SECONDS);
      executor.submit(() -> streamOneReview("diff-of-the-second-pr")).get(30, TimeUnit.SECONDS);
    }

    assertEquals(2, requests.size(), "both review calls must reach the model");
    var second = requests.get(1);
    assertEquals(
        0,
        second.stream().filter(AiMessage.class::isInstance).count(),
        "a review request must carry no assistant messages — chat memory is replaying prior"
            + " answers: "
            + describe(second));
    assertEquals(
        1,
        second.stream().filter(dev.langchain4j.data.message.UserMessage.class::isInstance).count(),
        "a review request must carry exactly one user message — chat memory is replaying prior"
            + " prompts: "
            + describe(second));
    assertFalse(
        second.stream().anyMatch(message -> message.toString().contains("diff-of-the-first-pr")),
        "a review request must not carry another review's diff");
  }

  private Void streamOneReview(String diff) {
    var done = new CompletableFuture<Void>();
    prReviewer
        .reviewStream(diff, "prContext", "baseComparison", "stack", "tests", "previous", "instr")
        .onPartialResponse(token -> {})
        .onCompleteResponse(response -> done.complete(null))
        .onError(done::completeExceptionally)
        .start();
    try {
      done.get(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException(e);
    }
    return null;
  }

  private static String describe(List<ChatMessage> messages) {
    return messages.stream().map(m -> m.type().toString()).toList().toString();
  }

  /** Records the messages of every request instead of calling a provider. */
  private record CapturingStreamingChatModel(List<List<ChatMessage>> requests)
      implements StreamingChatModel {

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
      requests.add(List.copyOf(request.messages()));
      handler.onPartialResponse("{}");
      handler.onCompleteResponse(
          ChatResponse.builder()
              .aiMessage(AiMessage.from("{}"))
              .metadata(
                  ChatResponseMetadata.builder()
                      .finishReason(FinishReason.STOP)
                      .tokenUsage(new TokenUsage(1, 1))
                      .build())
              .build());
    }
  }

  public static class TuningEnabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "thrillhousebot.ai.reasoning.enabled", "true",
          "thrillhousebot.ai.reasoning.effort", "Medium",
          "thrillhousebot.ai.models.deepseek-chat.temperature", "0.3",
          "thrillhousebot.ai.models.deepseek-chat.top-p", "0.95",
          "thrillhousebot.ai.models.deepseek-chat.max-output-tokens", "4096");
    }
  }
}
