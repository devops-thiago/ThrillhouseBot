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
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The checks every review call's raw findings face against the material that call was given, before
 * anything later in {@link FindingPipeline} sees them. Both lanes run it — the single call over its
 * whole response ({@link #screenSingleCall}) and the multi-call lane once per batch ({@link
 * #screenBatch}) — so a finding is checked against the text its own call saw, whichever lane raised
 * it.
 *
 * <p>Each check is a {@link FindingStage}, and each lane is an ordered list of stages built in the
 * constructor; the lanes differ only in which stages their list holds. The order is fixed. Evidence
 * is resolved from the findings as the model raised them, before the quote validator nulls {@code
 * suggestion_old} on exactly the finding whose quote is outside the call's window (#650/#475).
 * Quote validation runs before dedupe, so a merged finding cannot inherit a phantom quote while a
 * verbatim sibling is discarded. A claim the audit already rejected on this head is dropped before
 * the verification call rather than put to it again (#711), and the verdicts are remembered against
 * the candidates that were actually sent.
 */
@ApplicationScoped
public class FindingScreen {

  private final List<FindingStage> singleCallStages;
  private final List<FindingStage> batchStages;

  @Inject
  FindingScreen(
      EvidenceStage evidence,
      QuoteValidationStage quoteValidation,
      FrameworkFilterStage frameworkFilter,
      DedupeStage dedupe,
      RejectionScreenStage rejectionScreen,
      VerificationStage verification,
      RememberVerdictsStage rememberVerdicts) {
    // The single call's whole response is one call's, so its findings are deduped here, after
    // quote validation and before the verifier sees them.
    this.singleCallStages =
        List.of(
            evidence,
            quoteValidation,
            frameworkFilter,
            dedupe,
            rejectionScreen,
            verification,
            rememberVerdicts);
    // A batch's findings are not deduped here: that happens once over every batch's survivors, so
    // a duplicate raised by two batches is merged too.
    this.batchStages =
        List.of(
            evidence,
            quoteValidation,
            frameworkFilter,
            rejectionScreen,
            verification,
            rememberVerdicts);
  }

  /**
   * The single-call lane's screen, dedupe included.
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
    return run(
        singleCallStages,
        ScreeningContext.start(session, aiResponse, quoteSource, promptInputs, plan, evidence));
  }

  /**
   * One batch's screen on the multi-call lane, without dedupe.
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
    return run(
        batchStages,
        ScreeningContext.start(session, batchResponse, batchText, batchInputs, plan, evidence));
  }

  /** The single-call lane's stages, in the order they run. */
  List<FindingStage> singleCallStages() {
    return singleCallStages;
  }

  /** The multi-call lane's per-batch stages, in the order they run. */
  List<FindingStage> batchStages() {
    return batchStages;
  }

  private static ReviewResponse run(List<FindingStage> stages, ScreeningContext start) {
    var context = start;
    for (var stage : stages) {
      context = stage.apply(context);
    }
    return context.response();
  }
}
