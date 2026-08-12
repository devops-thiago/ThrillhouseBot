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
 * Drafts a CHANGELOG entry for a pull request from its diff, in the Keep a Changelog format
 * (Added/Changed/Fixed/Security…). Returns the entry as plain Markdown — like the describe and
 * reply assistants, there is no JSON schema to parse.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface ChangelogAssistant {

  // @UserMessage MUST stay on the method: on a parameter, quarkus-langchain4j sends only that
  // parameter's raw value and silently drops every other @V.
  @SystemMessage(ChangelogAssistantPrompts.SYSTEM)
  @UserMessage(PrSuggestionPrompts.USER)
  Result<String> draft(
      @V("diff") String diff,
      @V("prNumber") String prNumber,
      @V("currentTitle") String currentTitle,
      @V("currentDescription") String currentDescription,
      @V("repoInstructions") String repoInstructions);

  /**
   * Merges the per-batch candidate entries for one PR into a single CHANGELOG entry. Only used when
   * the PR needed more than one batch: candidates drafted from different files of the same feature
   * describe that one change in different words, which no deterministic merge can collapse.
   */
  @SystemMessage(ChangelogAssistantPrompts.MERGE_SYSTEM)
  @UserMessage(ChangelogAssistantPrompts.MERGE_USER)
  Result<String> merge(
      @V("candidates") String candidates,
      @V("prNumber") String prNumber,
      @V("currentTitle") String currentTitle,
      @V("currentDescription") String currentDescription,
      @V("repoInstructions") String repoInstructions);
}
