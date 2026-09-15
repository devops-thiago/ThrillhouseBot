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
import dev.langchain4j.model.output.TokenUsage;
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
 *
 * <p>{@link #reasoningExhausted} is the other shape a length stop takes (#839): no content at all,
 * the reasoning field carrying everything the model produced, and a usage report showing the whole
 * output allowance spent — the production signature of a reasoning tail that never reached the
 * answer.
 */
final class TruncatedTokenStream implements TokenStream {

  private final String partialText;
  private final String reasoning;
  private final TokenUsage usage;
  private final AtomicInteger starts;
  private Consumer<ChatResponse> completeHandler;

  TruncatedTokenStream(String partialText, AtomicInteger starts) {
    this(partialText, null, null, starts);
  }

  private TruncatedTokenStream(
      String partialText, String reasoning, TokenUsage usage, AtomicInteger starts) {
    this.partialText = partialText;
    this.reasoning = reasoning;
    this.usage = usage;
    this.starts = starts;
  }

  /** A length stop with an empty content body: the model spent its output allowance reasoning. */
  static TruncatedTokenStream reasoningExhausted(AtomicInteger starts) {
    return new TruncatedTokenStream(
        "",
        "Let me look at the diff again before I decide what to report...",
        new TokenUsage(30_000, 65_536),
        starts);
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
              .aiMessage(AiMessage.builder().text(partialText).thinking(reasoning).build())
              .finishReason(FinishReason.LENGTH)
              .tokenUsage(usage)
              .build());
    }
  }
}
