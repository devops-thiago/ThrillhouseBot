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

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * Drafts a CHANGELOG entry for a pull request from its diff, in the Keep a Changelog format
 * (Added/Changed/Fixed/Security…). Returns the entry as plain Markdown — like the describe and
 * reply assistants, there is no JSON schema to parse.
 */
@RegisterAiService
public interface ChangelogAssistant {

  // @UserMessage MUST be on the method, not a parameter: on a parameter quarkus-langchain4j sends
  // only that parameter's raw value as the user message and never renders this template, silently
  // dropping prNumber, currentTitle, currentDescription and repoInstructions (see
  // AiServicePromptRenderingTest and the #186 regression).
  @SystemMessage(ChangelogAssistantPrompts.SYSTEM)
  @UserMessage(PrSuggestionPrompts.USER)
  String draft(
      @V("diff") String diff,
      @V("prNumber") String prNumber,
      @V("currentTitle") String currentTitle,
      @V("currentDescription") String currentDescription,
      @V("repoInstructions") String repoInstructions);
}
