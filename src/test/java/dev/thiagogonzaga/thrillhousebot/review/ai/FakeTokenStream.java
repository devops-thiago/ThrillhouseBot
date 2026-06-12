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
import java.util.List;
import java.util.function.Consumer;

/** Minimal TokenStream test double that emits a fixed payload when started. */
final class FakeTokenStream implements TokenStream {

  private final String text;
  private final int chunkSize;
  private Consumer<String> partialHandler;
  private Consumer<ChatResponse> completeHandler;
  private Consumer<Throwable> errorHandler;
  private int errorHandlingRegistrations;

  FakeTokenStream(String text) {
    this(text, 0);
  }

  FakeTokenStream(String text, int chunkSize) {
    this.text = text;
    this.chunkSize = chunkSize;
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
    this.errorHandler = handler;
    errorHandlingRegistrations++;
    return this;
  }

  @Override
  public TokenStream ignoreErrors() {
    errorHandlingRegistrations++;
    return this;
  }

  @Override
  public void start() {
    // Mirrors AiServiceTokenStream.validateConfiguration() in langchain4j: registering both
    // onError and ignoreErrors (or neither) makes the real stream throw at start()
    if (errorHandlingRegistrations != 1) {
      throw new IllegalStateException(
          "One of [onError, ignoreErrors] must be invoked on TokenStream exactly 1 time, was: "
              + errorHandlingRegistrations);
    }
    if (partialHandler != null) {
      if (chunkSize <= 0) {
        partialHandler.accept(text);
      } else {
        for (var i = 0; i < text.length(); i += chunkSize) {
          partialHandler.accept(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
      }
    }
    if (completeHandler != null) {
      completeHandler.accept(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
    }
  }

  Consumer<Throwable> errorHandler() {
    return errorHandler;
  }
}
