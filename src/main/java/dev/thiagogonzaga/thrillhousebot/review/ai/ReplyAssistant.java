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
 * Conversational reply to a maintainer's question or pushback in a PR thread. Returns the reply as
 * plain Markdown — unlike the review call, there is no JSON schema to parse.
 */
@RegisterAiService
public interface ReplyAssistant {

  // @UserMessage MUST stay on the method: on a parameter, quarkus-langchain4j sends only that
  // parameter's raw value and silently drops every other @V.
  @SystemMessage(ReplyAssistantPrompts.SYSTEM)
  @UserMessage(ReplyAssistantPrompts.USER)
  String reply(
      @V("question") String question,
      @V("prContext") String prContext,
      @V("finding") String finding,
      @V("codeContext") String codeContext,
      @V("thread") String thread);
}
