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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Boots the real AI-service wiring and pins that every blocking model call the bot makes holds a
 * slot of the process-wide ceiling while it reaches the model (#838). The streamed calls (review
 * batches and the final summary) take their slot in {@code AiReviewService}, which {@code
 * AiReviewServiceTest} covers; every other call reaches the provider through its AI service's
 * {@code chatLanguageModelSupplier}, and nothing short of the booted extension proves that the
 * supplier is the model the service actually calls.
 */
@QuarkusTest
class BoundedChatModelWiringTest {

  /** The AI services whose calls stream through {@code AiReviewService} and are gated there. */
  private static final Set<Class<?>> STREAMED_THROUGH_REVIEW_SERVICE =
      Set.of(PrReviewer.class, PrSummarizer.class);

  @Inject FindingVerifier findingVerifier;
  @Inject ReplyAssistant replyAssistant;
  @Inject ChangelogAssistant changelogAssistant;
  @Inject DocGenerator docGenerator;
  @Inject PrDescribeAssistant describeAssistant;
  @Inject PrImproveAssistant improveAssistant;
  @Inject UnitTestAssistant unitTestAssistant;

  @Test
  void everyBlockingAiServiceCallHoldsASlotWhileItReachesTheModel() {
    var gate = new ModelCallGate(1, Duration.ofSeconds(30), System::nanoTime);
    QuarkusMock.installMockForType(gate, ModelCallGate.class);
    var slotsSeen = new CopyOnWriteArrayList<Integer>();
    var model = new SlotReadingChatModel(gate, slotsSeen);
    QuarkusMock.installMockForType(model, ChatModel.class);
    QuarkusMock.installMockForType(model, ChatModel.class, ModelName.Literal.of("concise"));

    var calls = new LinkedHashMap<String, Runnable>();
    calls.put("FindingVerifier#verify", () -> findingVerifier.verify("f", "c", "d", "s", "p"));
    calls.put("ReplyAssistant#reply", () -> replyAssistant.reply("q", "c", "f", "d", "t"));
    calls.put("ChangelogAssistant#draft", () -> changelogAssistant.draft("d", "7", "t", "b", "i"));
    calls.put("ChangelogAssistant#merge", () -> changelogAssistant.merge("c", "7", "t", "b", "i"));
    calls.put("DocGenerator#generate", () -> docGenerator.generate("d", "c", "s", "i"));
    calls.put("PrDescribeAssistant#describe", () -> describeAssistant.describe("d", "t", "b", "i"));
    calls.put(
        "PrDescribeAssistant#synthesize", () -> describeAssistant.synthesize("p", "t", "b", "i"));
    calls.put("PrImproveAssistant#improve", () -> improveAssistant.improve("d", "t", "b", "i"));
    calls.put(
        "UnitTestAssistant#generate", () -> unitTestAssistant.generate("d", "c", "s", "i", "p"));

    for (var call : calls.entrySet()) {
      slotsSeen.clear();
      call.getValue().run();
      assertEquals(
          List.of(0), slotsSeen, call.getKey() + " reached the model without holding a slot");
    }
    assertEquals(1, gate.availableSlots(), "every call must return its slot");
  }

  /**
   * Holds a new AI service to the same rule: one that does not stream through {@code
   * AiReviewService} must name the bounded supplier for its model binding, or its calls would go
   * around the ceiling.
   */
  @Test
  void everyOtherAiServiceNamesTheBoundedSupplierForItsModel() {
    var services =
        Arc.container().beanManager().getBeans(Object.class, Any.Literal.INSTANCE).stream()
            .flatMap(bean -> bean.getTypes().stream())
            .filter(Class.class::isInstance)
            .map(type -> (Class<?>) type)
            .filter(type -> type.isInterface() && type.isAnnotationPresent(RegisterAiService.class))
            .collect(Collectors.toSet());
    assertTrue(
        services.containsAll(STREAMED_THROUGH_REVIEW_SERVICE)
            && services.contains(FindingVerifier.class),
        "the AI services must be discoverable: " + services);

    for (var service : services) {
      if (STREAMED_THROUGH_REVIEW_SERVICE.contains(service)) {
        continue;
      }
      var binding = service.getAnnotation(RegisterAiService.class);
      var expected =
          "concise".equals(binding.modelName())
              ? BoundedChatModel.ConciseSupplier.class
              : BoundedChatModel.ActiveSupplier.class;
      assertEquals(
          expected,
          binding.chatLanguageModelSupplier(),
          service.getSimpleName() + " must reach its model through the bounded supplier");
    }
  }

  /** Answers every request, recording how many slots the gate had free while the call ran. */
  private record SlotReadingChatModel(ModelCallGate gate, List<Integer> slotsSeen)
      implements ChatModel {

    @Override
    public ChatResponse doChat(ChatRequest request) {
      slotsSeen.add(gate.availableSlots());
      return ChatResponse.builder()
          .aiMessage(AiMessage.from("{}"))
          .metadata(
              ChatResponseMetadata.builder()
                  .finishReason(FinishReason.STOP)
                  .tokenUsage(new TokenUsage(1, 1))
                  .build())
          .build();
    }
  }
}
