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
 * Stateless streaming AI service for the final summary call of a multi-call review. Split out of
 * {@link PrReviewer} because one {@code @RegisterAiService} interface is one model binding, and the
 * summary must not share the batch review's response cap: a batch response scales with finding
 * count, while the summary returns one fixed-shape object plus {@code previous_findings_status}, so
 * it binds to the {@code concise} named model whose {@code max-tokens} is sized for fixed-shape
 * output ({@code REVIEW_CONCISE_MAX_OUTPUT_TOKENS}). Application-scoped for the same reason as
 * {@link PrReviewer}: request scope would break callers on virtual threads without an inherited CDI
 * request context. Chat memory is disabled explicitly, for the reason documented on {@link
 * PrReviewer}.
 */
@ApplicationScoped
@RegisterAiService(
    modelName = "concise",
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface PrSummarizer {

  // @UserMessage MUST stay on the method: on a parameter, quarkus-langchain4j sends only that
  // parameter's raw value and silently drops every other @V.
  @SystemMessage(PrReviewPrompts.SUMMARY_SYSTEM)
  @UserMessage(PrReviewPrompts.SUMMARY_USER)
  TokenStream summarizeStream(
      @V("prContext") String prContext,
      @V("findings") String findings,
      @V("changedFiles") String changedFiles,
      @V("previousFindings") String previousFindings,
      @V("repoInstructions") String repoInstructions);
}
