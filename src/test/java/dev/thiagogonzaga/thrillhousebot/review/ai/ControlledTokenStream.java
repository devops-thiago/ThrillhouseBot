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

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * TokenStream the test finishes by hand: {@link #start()} records that the stream opened and does
 * nothing else, and {@link #complete(String)} delivers the response from whichever thread calls it.
 * Several streams can share one in-flight counter, so a test can state how many were open at once
 * (#838). {@code slotsAtStart} reads the gate's free slots at the moment the stream opens.
 */
final class ControlledTokenStream implements TokenStream {

  private final AtomicInteger inFlight;
  private final AtomicInteger maxInFlight;
  private final IntSupplier freeSlots;
  private final CountDownLatch started = new CountDownLatch(1);
  private volatile int slotsAtStart = -1;
  private Consumer<ChatResponse> completeHandler;

  ControlledTokenStream(AtomicInteger inFlight, AtomicInteger maxInFlight, IntSupplier freeSlots) {
    this.inFlight = inFlight;
    this.maxInFlight = maxInFlight;
    this.freeSlots = freeSlots;
  }

  @Override
  public TokenStream onPartialResponse(Consumer<String> handler) {
    return this;
  }

  @Override
  public TokenStream onRetrieved(Consumer<List<Content>> handler) {
    return this;
  }

  @Override
  public TokenStream onToolExecuted(Consumer<ToolExecution> handler) {
    return this;
  }

  @Override
  public TokenStream onCompleteResponse(Consumer<ChatResponse> handler) {
    this.completeHandler = handler;
    return this;
  }

  @Override
  public TokenStream onError(Consumer<Throwable> handler) {
    return this;
  }

  @Override
  public TokenStream ignoreErrors() {
    return this;
  }

  @Override
  public void start() {
    slotsAtStart = freeSlots.getAsInt();
    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
    started.countDown();
  }

  /** Ends the stream with {@code text} as the whole response. */
  void complete(String text) {
    inFlight.decrementAndGet();
    completeHandler.accept(
        ChatResponse.builder()
            .aiMessage(AiMessage.from(text))
            .finishReason(FinishReason.STOP)
            .build());
  }

  boolean hasStarted() {
    return started.getCount() == 0;
  }

  CountDownLatch started() {
    return started;
  }

  int slotsAtStart() {
    return slotsAtStart;
  }
}
