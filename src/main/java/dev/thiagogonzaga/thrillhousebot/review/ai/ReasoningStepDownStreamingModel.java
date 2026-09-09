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

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.AiPricingConfig.ReasoningConfig;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.function.Supplier;

/**
 * The streaming model the review's AI services call, with the per-call reasoning step-down (#839)
 * in front of the provider model: a call that {@link AiReviewService} bound as {@linkplain
 * ReviewSessionContext#reasoningDisabledForCurrentCall() reasoning-disabled} goes out with {@code
 * reasoning_effort=none}, every other call goes out exactly as configured.
 *
 * <p>The override is applied here, at the model, and not as a per-call request parameter on the AI
 * service method, because that slot does not reach the wire on this path: for a {@code TokenStream}
 * return type quarkus-langchain4j 1.13 hands the call to langchain4j's own token stream, which
 * reads per-call parameters from the invocation context's method arguments, and the extension
 * builds that context with the memory id alone — so a {@code ChatRequestParameters} argument, like
 * a per-call {@code @ModelName}, is silently dropped for streamed calls while the blocking and
 * {@code Multi} paths honour it. The step-down is decided after a call has already failed once, so
 * it has to be per call rather than a second configured model with its own duplicated settings;
 * what survives the extension's path unchanged is the thread that starts the stream, and the
 * session binding that {@link AiReviewService} already puts on it for the observability listener
 * carries the flag the same way.
 *
 * <p>The rewrite folds the request's own parameters into an OpenAI-typed copy and sets the effort
 * on that: the provider model merges a call's parameters over its builder-level defaults through
 * its own builder, and only an OpenAI-typed value keeps the OpenAI-specific fields through that
 * merge — a plain {@code reasoning_effort} on a generic parameters object would be flattened away.
 * Everything else about the call is untouched: the delegate's own {@code chat} runs its listeners,
 * so the observability listener and the token ledger see the stepped-down call exactly as any
 * other.
 */
final class ReasoningStepDownStreamingModel implements StreamingChatModel {

  private final StreamingChatModel delegate;

  ReasoningStepDownStreamingModel(StreamingChatModel delegate) {
    this.delegate = delegate;
  }

  @Override
  public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
    delegate.chat(
        ReviewSessionContext.reasoningDisabledForCurrentCall()
            ? withReasoningDisabled(request)
            : request,
        handler);
  }

  /** The delegate's defaults: this wrapper adds nothing a caller could read off the model. */
  @Override
  public ChatRequestParameters defaultRequestParameters() {
    return delegate.defaultRequestParameters();
  }

  /**
   * {@code request} with {@code reasoning_effort=none} on an OpenAI-typed copy of its parameters,
   * so the provider model's merge keeps the effort and every other value the request carried.
   */
  static ChatRequest withReasoningDisabled(ChatRequest request) {
    return request.toBuilder()
        .parameters(
            OpenAiChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .reasoningEffort(ReasoningConfig.EFFORT_NONE)
                .build())
        .build();
  }

  /**
   * Supplies {@link PrReviewer}'s model: the default streaming model behind the step-down. A bean,
   * so the extension resolves it from the container and the injected model is the same customized,
   * listener-bearing instance every other injection point sees — and so the extension still
   * produces that model bean now that the AI service no longer asks for it itself.
   */
  @ApplicationScoped
  static class ActiveSupplier implements Supplier<StreamingChatModel> {

    private final StreamingChatModel model;

    ActiveSupplier(StreamingChatModel model) {
      this.model = model;
    }

    @Override
    public StreamingChatModel get() {
      return new ReasoningStepDownStreamingModel(model);
    }
  }

  /**
   * Supplies {@link PrSummarizer}'s model: the {@code concise} streaming model behind the
   * step-down, on the same terms as {@link ActiveSupplier}.
   */
  @ApplicationScoped
  static class ConciseSupplier implements Supplier<StreamingChatModel> {

    private final StreamingChatModel model;

    ConciseSupplier(@ModelName("concise") StreamingChatModel model) {
      this.model = model;
    }

    @Override
    public StreamingChatModel get() {
      return new ReasoningStepDownStreamingModel(model);
    }
  }
}
