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

/**
 * Proposes whole-PR improvements from the diff, on demand via the {@code /improve} command. Shares
 * the {@link PrSuggestionPrompts#USER} inputs with the other on-request suggestion assistants but
 * returns JSON (parsed by {@link ImprovementParser}) so each improvement can be posted as a
 * committable suggestion anchored to the diff.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface PrImproveAssistant {

  // @UserMessage MUST stay on the method: on a parameter, quarkus-langchain4j sends only that
  // parameter's raw value and silently drops every other @V.
  @SystemMessage(PrImproveAssistantPrompts.SYSTEM)
  @UserMessage(PrSuggestionPrompts.USER)
  Result<String> improve(
      @V("diff") String diff,
      @V("currentTitle") String currentTitle,
      @V("currentDescription") String currentDescription,
      @V("repoInstructions") String repoInstructions);
}
