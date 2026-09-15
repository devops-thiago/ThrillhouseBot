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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class BoundedChatModelTest {

  private static final ChatRequest REQUEST =
      ChatRequest.builder().messages(UserMessage.from("verify these findings")).build();

  private static final ChatResponse RESPONSE =
      ChatResponse.builder().aiMessage(AiMessage.from("{}")).build();

  @Test
  void aCallHoldsASlotWhileItRunsAndReturnsIt() {
    var gate = new ModelCallGate(1, Duration.ofSeconds(30), System::nanoTime);
    var slotsWhileRunning = new AtomicInteger(-1);
    var model =
        new BoundedChatModel(
            answering(
                request -> {
                  slotsWhileRunning.set(gate.availableSlots());
                  return RESPONSE;
                }),
            gate);

    assertSame(RESPONSE, model.chat(REQUEST));

    assertEquals(0, slotsWhileRunning.get(), "the call must hold the only slot while it runs");
    assertEquals(1, gate.availableSlots(), "a finished call must return its slot");
  }

  @Test
  void aFailedCallReturnsItsSlot() {
    var gate = new ModelCallGate(1, Duration.ofSeconds(30), System::nanoTime);
    var slotsWhileRunning = new AtomicInteger(-1);
    var model =
        new BoundedChatModel(
            answering(
                request -> {
                  slotsWhileRunning.set(gate.availableSlots());
                  throw new IllegalStateException("provider down");
                }),
            gate);

    assertThrows(IllegalStateException.class, () -> model.chat(REQUEST));

    assertEquals(0, slotsWhileRunning.get(), "the call must hold the only slot while it runs");
    assertEquals(1, gate.availableSlots(), "a failed call must return its slot");
  }

  @Test
  void aCallPastTheCeilingWaitsForTheSlotAndNeverOverlaps() throws Exception {
    var gate = new ModelCallGate(1, Duration.ofSeconds(30), System::nanoTime);
    var entered = new AtomicInteger();
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var model =
        new BoundedChatModel(
            answering(
                request -> {
                  if (entered.incrementAndGet() == 1) {
                    firstEntered.countDown();
                    await(releaseFirst);
                  }
                  return RESPONSE;
                }),
            gate);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> model.chat(REQUEST));
      assertTrue(firstEntered.await(10, TimeUnit.SECONDS), "the first call must reach the model");
      var second = executor.submit(() -> model.chat(REQUEST));

      awaitCondition(() -> gate.queuedCalls() > 0 || entered.get() > 1);
      assertEquals(
          1, entered.get(), "the second call reached the model while the first held the only slot");

      releaseFirst.countDown();
      assertSame(RESPONSE, first.get(10, TimeUnit.SECONDS));
      assertSame(RESPONSE, second.get(10, TimeUnit.SECONDS));
    }
    assertEquals(2, entered.get());
    assertEquals(1, gate.availableSlots());
  }

  @Test
  void aCallThatFindsNoFreeSlotInTimeIsNeverSent() {
    var gate = new ModelCallGate(1, Duration.ofMillis(20), System::nanoTime);
    var calls = new AtomicInteger();
    var model =
        new BoundedChatModel(
            answering(
                request -> {
                  calls.incrementAndGet();
                  return RESPONSE;
                }),
            gate);

    try (var _ = gate.acquire("a call holding the only slot")) {
      assertThrows(AiReviewException.class, () -> model.chat(REQUEST));
    }

    assertEquals(0, calls.get(), "a call that never got a slot must not reach the provider");
  }

  @Test
  void describesItselfAsTheModelItWraps() {
    var delegate = mock(ChatModel.class);
    var parameters = ChatRequestParameters.builder().temperature(0.2).build();
    when(delegate.defaultRequestParameters()).thenReturn(parameters);
    when(delegate.provider()).thenReturn(ModelProvider.OPEN_AI);
    when(delegate.supportedCapabilities())
        .thenReturn(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    var model =
        new BoundedChatModel(delegate, new ModelCallGate(0, Duration.ZERO, System::nanoTime));

    assertSame(parameters, model.defaultRequestParameters());
    assertEquals(ModelProvider.OPEN_AI, model.provider());
    assertEquals(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA), model.supportedCapabilities());
  }

  @Test
  void bothSuppliersPutTheirModelBehindTheGate() {
    var gate = new ModelCallGate(1, Duration.ofSeconds(30), System::nanoTime);
    var slotsWhileRunning = new AtomicInteger(-1);
    var delegate =
        answering(
            request -> {
              slotsWhileRunning.set(gate.availableSlots());
              return RESPONSE;
            });

    for (var supplied :
        new ChatModel[] {
          new BoundedChatModel.ActiveSupplier(delegate, gate).get(),
          new BoundedChatModel.ConciseSupplier(delegate, gate).get()
        }) {
      slotsWhileRunning.set(-1);
      assertInstanceOf(BoundedChatModel.class, supplied);
      assertSame(RESPONSE, supplied.chat(REQUEST));
      assertEquals(0, slotsWhileRunning.get(), "the supplied model must take a slot per call");
    }
    assertFalse(gate.queuedCalls() > 0);
  }

  /** A provider model that answers every request with {@code answer}. */
  private static ChatModel answering(Function<ChatRequest, ChatResponse> answer) {
    return new ChatModel() {
      @Override
      public ChatResponse doChat(ChatRequest request) {
        return answer.apply(request);
      }
    };
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS), "the test never released the first call");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void awaitCondition(BooleanSupplier condition) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "condition not reached within 10 seconds");
      Thread.onSpinWait();
    }
  }
}
