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
package dev.thiagogonzaga.thrillhousebot.review;

import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.UnaryOperator;

/**
 * The checks every review call's raw findings face against the material that call was given, before
 * anything later in {@link FindingPipeline} sees them: evidence resolution, quote validation, the
 * framework false-positive filter, the rejection-memory screen, then the verifier. Both lanes run
 * it — the single call over its whole response ({@link #screenSingleCall}) and the multi-call lane
 * once per batch ({@link #screenBatch}) — so a finding is checked against the text its own call
 * saw, whichever lane raised it.
 *
 * <p>The order is fixed. Evidence is resolved from the findings as the model raised them, before
 * the quote validator nulls {@code suggestion_old} on exactly the finding whose quote is outside
 * the call's window (#650/#475). Quote validation runs before dedupe, so a merged finding cannot
 * inherit a phantom quote while a verbatim sibling is discarded. A claim the audit already rejected
 * on this head is dropped before the verification call rather than put to it again (#711), and the
 * verdicts are remembered against the candidates that were actually sent.
 */
@ApplicationScoped
public class FindingScreen {

  private final FindingQuoteValidator quoteValidator;
  private final FrameworkFalsePositiveFilter frameworkFilter;
  private final FindingDeduplicator deduplicator;
  private final FindingVerificationService findingVerificationService;
  private final VerifierRejectionMemory rejectionMemory;
  private final DiffBudgetPlanner budgetPlanner;
  private final TokenCounter tokenCounter;

  @Inject
  public FindingScreen(
      FindingQuoteValidator quoteValidator,
      FrameworkFalsePositiveFilter frameworkFilter,
      FindingDeduplicator deduplicator,
      FindingVerificationService findingVerificationService,
      VerifierRejectionMemory rejectionMemory,
      DiffBudgetPlanner budgetPlanner,
      TokenCounter tokenCounter) {
    this.quoteValidator = quoteValidator;
    this.frameworkFilter = frameworkFilter;
    this.deduplicator = deduplicator;
    this.findingVerificationService = findingVerificationService;
    this.rejectionMemory = rejectionMemory;
    this.budgetPlanner = budgetPlanner;
    this.tokenCounter = tokenCounter;
  }

  /**
   * The single-call lane's screen: the whole response is one call's, so its findings are deduped
   * here, after quote validation and before the verifier sees them.
   *
   * @param quoteSource the diff text the call was given, which quotes are checked against
   * @param promptInputs the call's prompt inputs, whose context the verifier is given
   */
  ReviewResponse screenSingleCall(
      ReviewSession session,
      ReviewResponse aiResponse,
      String quoteSource,
      AiReviewService.PromptInputs promptInputs,
      DiffBudgetPlanner.BudgetPlan plan,
      ReviewEvidence evidence) {
    return screen(
        session, aiResponse, quoteSource, promptInputs, plan, evidence, deduplicator::dedupe);
  }

  /**
   * One batch's screen on the multi-call lane. Its findings are not deduped here: that happens once
   * over every batch's survivors, so a duplicate raised by two batches is merged too.
   *
   * @param batchText the batch's own in-budget text, which quotes are checked against
   * @param batchInputs the batch call's prompt inputs, whose context the verifier is given
   */
  ReviewResponse screenBatch(
      ReviewSession session,
      ReviewResponse batchResponse,
      String batchText,
      AiReviewService.PromptInputs batchInputs,
      DiffBudgetPlanner.BudgetPlan plan,
      ReviewEvidence evidence) {
    return screen(
        session, batchResponse, batchText, batchInputs, plan, evidence, UnaryOperator.identity());
  }

  private ReviewResponse screen(
      ReviewSession session,
      ReviewResponse response,
      String quoteSource,
      AiReviewService.PromptInputs promptInputs,
      DiffBudgetPlanner.BudgetPlan plan,
      ReviewEvidence evidence,
      UnaryOperator<ReviewResponse> beforeRejectionScreen) {
    // #650/#475: resolved against the findings as the model raised them, before the quote
    // validator below nulls suggestion_old on exactly the finding whose quote is outside the
    // window.
    var attached = evidence.forFindings(response.findings());
    var validated = quoteValidator.validate(response, quoteSource);
    validated = beforeRejectionScreen.apply(frameworkFilter.filter(validated, quoteSource));
    // #711: a claim the audit already rejected on this head is dropped before the call rather
    // than put to it again. A second verdict on unchanged code is a re-roll of the same question,
    // and on the fail-open paths there is no second verdict at all.
    var candidates = rejectionMemory.withoutRejectionsOnThisHead(session, validated);
    // #736: the verification call is the one review-path call that does no budget arithmetic of
    // its own, so the section the author alone sizes is bounded here before it is sent.
    var verified =
        findingVerificationService.verify(
            ReviewTokenLedger.keyFor(session),
            candidates,
            PrContextBudget.bound(
                promptInputs.prContext(), budgetPlanner.perCallInputBudget(), tokenCounter),
            promptInputs.diff(),
            promptInputs.projectStack(),
            promptInputs.previousFindings(),
            attached,
            plan::recordVerificationCoverage);
    rejectionMemory.remember(session, candidates.findings(), verified.findings());
    return verified;
  }
}
