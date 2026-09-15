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

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The blocking chat model the non-streamed AI services call, with the process-wide model call
 * ceiling (#838) in front of the provider model: each call holds a {@link ModelCallGate} slot for
 * as long as the provider takes to answer and returns it when the call returns or throws.
 *
 * <p>The streamed review calls take their slot in {@link AiReviewService}, where the attempt's
 * timeout and the stream's cancellation live. Every other model call is blocking: the finding
 * verifier, maintainer replies, and the {@code /describe}, {@code /changelog}, {@code /add-docs},
 * {@code /improve} and {@code /generate-tests} commands, nine call sites across seven AI services.
 * Wrapping the model they share covers all of them, and a method added to one of those services
 * later, where a wrapper at each call site would have to be remembered at every new one. The wrap
 * goes in through each service's {@code chatLanguageModelSupplier}, the seam {@link
 * ReasoningStepDownStreamingModel} already uses for the streamed services, and {@code
 * BoundedChatModelWiringTest} fails for an AI service that neither streams through {@link
 * AiReviewService} nor names one of the suppliers below.
 *
 * <p>The call is synchronous, so the slot is held exactly as long as the request is open, and the
 * HTTP client's {@code AI_TIMEOUT} bounds how long that is. Only {@link #chat(ChatRequest)} takes a
 * slot: it is the one entry point the extension's AI services call. The defaults, provider and
 * capabilities are read off the model while the request is built, so they are the wrapped model's.
 */
final class BoundedChatModel implements ChatModel {

  private final ChatModel delegate;
  private final ModelCallGate gate;

  BoundedChatModel(ChatModel delegate, ModelCallGate gate) {
    this.delegate = delegate;
    this.gate = gate;
  }

  @Override
  public ChatResponse chat(ChatRequest request) {
    try (var _ = gate.acquire("A blocking model call")) {
      return delegate.chat(request);
    }
  }

  @Override
  public ChatRequestParameters defaultRequestParameters() {
    return delegate.defaultRequestParameters();
  }

  @Override
  public ModelProvider provider() {
    return delegate.provider();
  }

  @Override
  public Set<Capability> supportedCapabilities() {
    return delegate.supportedCapabilities();
  }

  /**
   * Supplies the default blocking model behind the ceiling. A bean, so the injected model is the
   * same customized, listener-bearing instance every other injection point sees, and the extension
   * still produces it now that the AI services no longer ask for it themselves.
   */
  @ApplicationScoped
  static class ActiveSupplier implements Supplier<ChatModel> {

    private final ChatModel model;
    private final ModelCallGate gate;

    ActiveSupplier(ChatModel model, ModelCallGate gate) {
      this.model = model;
      this.gate = gate;
    }

    @Override
    public ChatModel get() {
      return new BoundedChatModel(model, gate);
    }
  }

  /** Supplies the {@code concise} blocking model behind the ceiling, as {@link ActiveSupplier}. */
  @ApplicationScoped
  static class ConciseSupplier implements Supplier<ChatModel> {

    private final ChatModel model;
    private final ModelCallGate gate;

    ConciseSupplier(@ModelName("concise") ChatModel model, ModelCallGate gate) {
      this.model = model;
      this.gate = gate;
    }

    @Override
    public ChatModel get() {
      return new BoundedChatModel(model, gate);
    }
  }
}
