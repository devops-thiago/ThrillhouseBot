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
 * Generates docstrings/inline documentation for the symbols changed in a PR diff, on demand via the
 * {@code /add-docs} command. Returns the suggestions as JSON (parsed by {@link
 * DocGenerationParser}) rather than the streaming, schema-rich response the full review uses — this
 * is a focused, single-shot blocking call like {@link ReplyAssistant}.
 */
@RegisterAiService
public interface DocGenerator {

  // @UserMessage MUST be on the method, not a parameter: on a parameter quarkus-langchain4j sends
  // only that parameter's raw value as the user message and never renders this template, silently
  // dropping prContext, projectStack and repoInstructions (see AiServicePromptRenderingTest).
  @SystemMessage(DocGeneratorPrompts.SYSTEM)
  @UserMessage(DocGeneratorPrompts.USER)
  String generate(
      @V("diff") String diff,
      @V("prContext") String prContext,
      @V("projectStack") String projectStack,
      @V("repoInstructions") String repoInstructions);
}
