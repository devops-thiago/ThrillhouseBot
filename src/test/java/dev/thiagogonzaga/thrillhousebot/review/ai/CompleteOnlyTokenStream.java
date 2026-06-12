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

/** Emits only a final ChatResponse with no partial tokens. */
final class CompleteOnlyTokenStream implements TokenStream {

  private final String text;
  private Consumer<ChatResponse> completeHandler;

  CompleteOnlyTokenStream(String text) {
    this.text = text;
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
    if (completeHandler != null) {
      completeHandler.accept(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
    }
  }
}
