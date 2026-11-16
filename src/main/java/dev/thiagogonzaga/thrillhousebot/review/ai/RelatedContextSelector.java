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
 * The opt-in related-context selection call (#55): picks, by number, the candidate files outside
 * the diff the review should read; returns raw JSON parsed by {@link
 * RelatedContextSelectionService}. Chat memory is disabled for the reason documented on {@link
 * PrReviewer}. Bound to the {@code concise} model: the input is a list of paths and the answer a
 * short list of numbers.
 */
@ApplicationScoped
@RegisterAiService(
    modelName = "concise",
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class,
    // The concise model behind the process-wide model call ceiling (#838). See BoundedChatModel.
    chatLanguageModelSupplier = BoundedChatModel.ConciseSupplier.class)
public interface RelatedContextSelector {

  // @UserMessage MUST stay on the method: on a parameter, quarkus-langchain4j sends only that
  // parameter's raw value and silently drops every other @V.
  @SystemMessage(RelatedContextPrompts.SYSTEM)
  @UserMessage(RelatedContextPrompts.USER)
  Result<String> select(
      @V("changedFiles") String changedFiles,
      @V("candidates") String candidates,
      @V("maxFiles") int maxFiles);
}
