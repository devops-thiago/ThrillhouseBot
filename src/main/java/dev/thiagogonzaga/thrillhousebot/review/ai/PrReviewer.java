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
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class,
    // The default streaming model, behind the per-call reasoning step-down (#839). A supplier
    // rather than the extension's own binding because a TokenStream call carries no per-call
    // request parameters through quarkus-langchain4j 1.13 — see ReasoningStepDownStreamingModel.
    streamingChatLanguageModelSupplier = ReasoningStepDownStreamingModel.ActiveSupplier.class)
public interface PrReviewer {

  // One parameter, the review's PromptInputs, bound as {{inputs}} (#993): the templates read its
  // components ({{inputs.diff}}, {{inputs.prContext}}, ...), so a new section is a record
  // component, not another parameter.
  //
  // {{inputs.repoInstructions}} carries the pre-rendered trailing guidance: the review-only
  // requests followed by any repo instructions file.
  //
  // {{inputs.reviewSystemPrompt}} is the whole system prompt, assembled per call from the core and
  // the dimension blocks that call's files use (#665) — PrReviewPrompts.reviewSystemPrompt. A
  // template value is inserted verbatim, never re-parsed as a template, so the model receives
  // exactly that string; with routing off it is PrReviewPrompts.SYSTEM.
  //
  // @UserMessage MUST stay on the method: on a parameter, quarkus-langchain4j sends only that
  // parameter's raw value and silently drops every other @V.
  @SystemMessage("{{inputs.reviewSystemPrompt}}")
  @UserMessage(PrReviewPrompts.USER)
  TokenStream reviewStream(@V("inputs") AiReviewService.PromptInputs inputs);
}
