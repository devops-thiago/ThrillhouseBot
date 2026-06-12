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
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import dev.thiagogonzaga.thrillhousebot.TestDelays;
import java.util.List;
import java.util.function.Consumer;

/** Emits small chunks with a delay between them to exercise time-based stream flushing. */
final class DelayedChunkTokenStream implements TokenStream {

  private final String text;
  private final int chunkSize;
  private final long delayMs;
  private Consumer<String> partialHandler;
  private Consumer<ChatResponse> completeHandler;

  DelayedChunkTokenStream(String text, int chunkSize, long delayMs) {
    this.text = text;
    this.chunkSize = chunkSize;
    this.delayMs = delayMs;
  }

  @Override
  public TokenStream onPartialResponse(Consumer<String> handler) {
    this.partialHandler = handler;
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
    if (partialHandler != null) {
      for (var i = 0; i < text.length(); i += chunkSize) {
        if (i > 0 && delayMs > 0 && !TestDelays.simulateLatency(delayMs)) {
          return;
        }
        partialHandler.accept(text.substring(i, Math.min(i + chunkSize, text.length())));
      }
    }
    if (completeHandler != null) {
      completeHandler.accept(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
    }
  }
}
