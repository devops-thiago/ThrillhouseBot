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

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the two upstream langchain4j behaviors the session-correlation design depends on:
 *
 * <ol>
 *   <li>{@link ChatModelListener#onRequest} runs synchronously on the thread that initiates the
 *       chat call — {@code ReviewSessionContext}'s ThreadLocal binding relies on this to stamp the
 *       session id into the per-request attributes.
 *   <li>{@link ChatModelListener#onResponse} fires <em>before</em> the user-level {@code
 *       onCompleteResponse} handler — {@code AiReviewService.streamOnce} relies on this when it
 *       deregisters the attempt right after the result future resolves; a reordering would silently
 *       drop usage/cost persistence for successful reviews.
 * </ol>
 *
 * <p>If a dependency upgrade breaks this test, fix the correlation mechanism in {@code
 * ReviewSessionContext}/{@code OtelObservabilityListener} before bumping the dependency.
 */
class StreamingChatModelListenerOrderingTest {

  @Test
  void listenerCallbacksRunOnCallerThreadAndBeforeHandlerCompletion() {
    var order = Collections.synchronizedList(new ArrayList<String>());
    var onRequestThread = new AtomicReference<Thread>();
    var onResponseThread = new AtomicReference<Thread>();

    var listener =
        new ChatModelListener() {
          @Override
          public void onRequest(ChatModelRequestContext ctx) {
            order.add("listener.onRequest");
            onRequestThread.set(Thread.currentThread());
          }

          @Override
          public void onResponse(ChatModelResponseContext ctx) {
            order.add("listener.onResponse");
            onResponseThread.set(Thread.currentThread());
          }
        };

    var model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("token");
            handler.onCompleteResponse(
                ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
          }

          @Override
          public List<ChatModelListener> listeners() {
            return List.of(listener);
          }
        };

    model.chat(
        ChatRequest.builder().messages(UserMessage.from("hi")).build(),
        new StreamingChatResponseHandler() {
          @Override
          public void onPartialResponse(String partialResponse) {
            order.add("handler.onPartialResponse");
          }

          @Override
          public void onCompleteResponse(ChatResponse completeResponse) {
            order.add("handler.onCompleteResponse");
          }

          @Override
          public void onError(Throwable error) {
            order.add("handler.onError");
          }
        });

    assertEquals(
        Thread.currentThread(),
        onRequestThread.get(),
        "onRequest must run on the calling thread — ReviewSessionContext's ThreadLocal binding"
            + " depends on it");
    var listenerResponse = order.indexOf("listener.onResponse");
    var handlerComplete = order.indexOf("handler.onCompleteResponse");
    assertTrue(listenerResponse >= 0 && handlerComplete >= 0, "both callbacks must fire: " + order);
    assertTrue(
        listenerResponse < handlerComplete,
        "listener.onResponse must fire before handler.onCompleteResponse — AiReviewService"
            + " deregisters the attempt when the future resolves; observed order: "
            + order);
  }

  @Test
  void usageRecordedByTheListenerIsOnTheLedgerBeforeTheCompletionHandlerReturns() {
    // The #499 spend-ceiling gate runs at the top of each retry attempt, strictly AFTER the
    // previous attempt's completion handler resolved the result future. This pins the property
    // that ordering guarantee gives the gate: usage a ChatModelListener records into the
    // ReviewTokenLedger is already observable at the moment the user-level completion handler
    // runs — so the next gate can never read a ledger that has not seen the prior billed attempt.
    var config =
        org.mockito.Mockito.mock(dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.class);
    var review =
        org.mockito.Mockito.mock(
            dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.ReviewConfig.class);
    org.mockito.Mockito.when(config.review()).thenReturn(review);
    org.mockito.Mockito.when(review.maxTokensPerReview()).thenReturn(100_000L);
    var ledger = new ReviewTokenLedger(config);
    ledger.open(7L);

    var spentSeenAtCompletion = new java.util.concurrent.atomic.AtomicLong(-1);
    var ceilingSeenAtCompletion = new AtomicReference<Boolean>();

    var listener =
        new ChatModelListener() {
          @Override
          public void onResponse(ChatModelResponseContext ctx) {
            ledger.recordUsage(7L, 60_000, 50_000);
          }
        };

    var model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onCompleteResponse(
                ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
          }

          @Override
          public List<ChatModelListener> listeners() {
            return List.of(listener);
          }
        };

    model.chat(
        ChatRequest.builder().messages(UserMessage.from("hi")).build(),
        new StreamingChatResponseHandler() {
          @Override
          public void onPartialResponse(String partialResponse) {}

          @Override
          public void onCompleteResponse(ChatResponse completeResponse) {
            spentSeenAtCompletion.set(ledger.tokensSpent(7L));
            ceilingSeenAtCompletion.set(ledger.ceilingReached(7L));
          }

          @Override
          public void onError(Throwable error) {}
        });

    assertEquals(
        110_000L,
        spentSeenAtCompletion.get(),
        "the billed attempt's usage must be on the ledger before the completion handler runs");
    assertEquals(Boolean.TRUE, ceilingSeenAtCompletion.get());
  }
}
