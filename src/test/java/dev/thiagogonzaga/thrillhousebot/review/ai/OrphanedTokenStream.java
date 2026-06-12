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

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import dev.thiagogonzaga.thrillhousebot.TestDelays;
import java.util.List;
import java.util.function.Consumer;

/** TokenStream that keeps emitting partial tokens on a background thread after start. */
final class OrphanedTokenStream implements TokenStream {

  private final long tokenDelayMs;
  private Consumer<String> partialHandler;

  OrphanedTokenStream(long tokenDelayMs) {
    this.tokenDelayMs = tokenDelayMs;
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
    var emitter =
        new Thread(
            () -> {
              for (var i = 0; i < 30; i++) {
                if (!TestDelays.simulateLatency(tokenDelayMs)) {
                  return;
                }
                if (partialHandler != null) {
                  partialHandler.accept("token-" + i);
                }
              }
            },
            "orphaned-token-stream");
    emitter.setDaemon(true);
    emitter.start();
  }
}
