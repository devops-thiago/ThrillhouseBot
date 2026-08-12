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
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stateless streaming AI service for the PR review call, whose response scales with finding count
 * and therefore carries the default model's response cap. The final summary call lives on {@link
 * PrSummarizer}, bound to the {@code concise} named model, so its fixed-shape response gets a
 * tighter cap. Application-scoped because request scope would break parallel map-reduce batches
 * that run on virtual threads without an inherited CDI request context.
 *
 * <p>Chat memory is disabled explicitly. Omitting {@code chatMemoryProviderSupplier} does not make
 * a service stateless: the annotation defaults to {@code BeanChatMemoryProviderSupplier}, so the
 * extension's default {@code MessageWindowChatMemory} applies, and with no {@code @MemoryId}
 * parameter every call shares one default memory id — which made each review resend the previous
 * reviews' prompts and answers (#584). State for a review is carried by the previous-findings
 * context, the code check for whether a finding was fixed, and user comments, never by conversation
 * history.
 */
@ApplicationScoped
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface PrReviewer {

  // {{repoInstructions}} carries the pre-rendered trailing guidance: available repository labels
  // (when labelling is on) followed by any repo instructions file.
  //
  // @UserMessage MUST stay on the method: on a parameter, quarkus-langchain4j sends only that
  // parameter's raw value and silently drops every other @V.
  @SystemMessage(PrReviewPrompts.SYSTEM)
  @UserMessage(PrReviewPrompts.USER)
  TokenStream reviewStream(
      @V("diff") String diff,
      @V("prContext") String prContext,
      @V("baseComparison") String baseComparison,
      @V("projectStack") String projectStack,
      @V("relatedTests") String relatedTests,
      @V("previousFindings") String previousFindings,
      @V("repoInstructions") String repoInstructions);
}
