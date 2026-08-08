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
 * Drafts the multi-file change that resolves one review finding, on demand via the opt-in {@code
 * /fix} command. Returns the edits as JSON (parsed by {@link FixResponseParser}) from a focused,
 * single-shot blocking call like {@link DocGenerator} — the bot, not the model, then applies the
 * edits, commits them to a bot branch, and opens the fix PR.
 */
@RegisterAiService
public interface FixGenerator {

  // @UserMessage MUST stay on the method: on a parameter, quarkus-langchain4j sends only that
  // parameter's raw value and silently drops every other @V.
  @SystemMessage(FixGeneratorPrompts.SYSTEM)
  @UserMessage(FixGeneratorPrompts.USER)
  String generate(
      @V("finding") String finding,
      @V("fileContents") String fileContents,
      @V("diff") String diff,
      @V("prContext") String prContext,
      @V("projectStack") String projectStack,
      @V("repoInstructions") String repoInstructions);
}
