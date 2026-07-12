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
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Second-pass AI call that audits candidate findings; returns raw JSON parsed by the caller.
 * Application-scoped (no chat memory) so parallel map-reduce batch threads can invoke it.
 */
@ApplicationScoped
@RegisterAiService
public interface FindingVerifier {

  // @UserMessage MUST stay on the method: on a parameter, quarkus-langchain4j sends only that
  // parameter's raw value and silently drops every other @V.
  @SystemMessage(FindingVerifierPrompts.SYSTEM)
  @UserMessage(FindingVerifierPrompts.USER)
  String verify(
      @V("findings") String findings,
      @V("diff") String diff,
      @V("projectStack") String projectStack,
      @V("previousFindings") String previousFindings);
}
