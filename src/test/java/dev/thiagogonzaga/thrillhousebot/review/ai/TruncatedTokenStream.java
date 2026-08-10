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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Emits a body cut mid-JSON with {@code finish_reason: length} — what a provider actually returns
 * when the response hits {@code max_tokens}. Counts its own starts so a test can assert how many
 * calls a truncation cost.
 */
final class TruncatedTokenStream implements TokenStream {

  private final String partialText;
  private final AtomicInteger starts;
  private Consumer<ChatResponse> completeHandler;

  TruncatedTokenStream(String partialText, AtomicInteger starts) {
    this.partialText = partialText;
    this.starts = starts;
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
    starts.incrementAndGet();
    if (completeHandler != null) {
      completeHandler.accept(
          ChatResponse.builder()
              .aiMessage(AiMessage.from(partialText))
              .finishReason(FinishReason.LENGTH)
              .build());
    }
  }
}
