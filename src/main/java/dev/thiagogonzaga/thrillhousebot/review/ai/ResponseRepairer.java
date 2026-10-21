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

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The repair call for a review call's answer the parser refused (#1079): returns the same content
 * in the response contract's shape, as raw JSON parsed by {@link ResponseRepairService}. Bound to
 * the active model, the one that wrote the answer, because a repaired answer is about as long as
 * the failed one and the concise cap could cut it. Chat memory is disabled for the reason
 * documented on {@link PrReviewer}.
 */
@ApplicationScoped
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class,
    // The active model behind the process-wide model call ceiling (#838). See BoundedChatModel.
    chatLanguageModelSupplier = BoundedChatModel.ActiveSupplier.class)
public interface ResponseRepairer {

  // @UserMessage MUST stay on the method: on a parameter, quarkus-langchain4j sends only that
  // parameter's raw value and silently drops every other @V.
  @SystemMessage(ResponseRepairPrompts.SYSTEM)
  @UserMessage(ResponseRepairPrompts.USER)
  Result<String> repair(
      @V("contract") String contract, @V("error") String error, @V("answer") String answer);
}
