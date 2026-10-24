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

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

/**
 * The model one streamed call is sent to when it is not {@code AI_MODEL} (#43): the low-risk model
 * a routed review call goes to, or the fallback model a call's retries go to after a provider
 * error. Carried on the thread that starts the stream, like {@link ReasoningRepeat}'s effort, and
 * applied by {@link ReasoningStepDownStreamingModel} as the request's {@code model}, so the call
 * goes out on the same provider client with only the model name changed. The observability listener
 * prices the call by the request's model name, so a routed call is costed as itself.
 */
final class ModelOverride {

  private static final ThreadLocal<String> MODEL = new ThreadLocal<>();

  private ModelOverride() {}

  /**
   * Sets {@code model} as the override for the calls this thread makes until the returned scope is
   * closed; {@code null} sets nothing. The previous value comes back on close, so scopes nest.
   */
  static Scope override(String model) {
    var previous = MODEL.get();
    if (model != null) {
      MODEL.set(model);
    }
    return new Scope(previous);
  }

  /** The override set on this thread, or {@code null} for a call that goes to the lane's model. */
  static String current() {
    return MODEL.get();
  }

  /**
   * {@code request} with the thread's override as its model name, or {@code request} itself when
   * none is set. Built on an OpenAI-typed copy of the request's parameters, as {@link
   * ReasoningRepeat#applied} does, so a reasoning effort set there survives the provider's merge.
   */
  static ChatRequest applied(ChatRequest request) {
    var model = current();
    if (model == null) {
      return request;
    }
    return request.toBuilder()
        .parameters(
            OpenAiChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .modelName(model)
                .build())
        .build();
  }

  /** Restores the thread's previous override when closed. */
  static final class Scope implements AutoCloseable {

    private final String previous;

    private Scope(String previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) {
        MODEL.remove();
      } else {
        MODEL.set(previous);
      }
    }
  }
}
