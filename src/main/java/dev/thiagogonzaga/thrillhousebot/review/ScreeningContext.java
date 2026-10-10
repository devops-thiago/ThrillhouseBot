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

/**
 * What one review call's findings carry through the {@link FindingStage}s of {@link FindingScreen}:
 * the call's own material, which no stage changes, and the parts the stages produce.
 *
 * @param session the review, which keys the rejection memory and the verifier's spend ledger
 * @param quoteSource the text the call was given, which quotes are checked against
 * @param promptInputs the call's prompt inputs, whose context the verifier is given
 * @param plan the review's budget plan, which records the verifier's coverage
 * @param evidence the review's evidence resolver
 * @param response the findings as they stand after the stages run so far
 * @param attached the evidence resolved for the raw findings, handed to the verifier
 * @param candidates the findings sent to the verifier, which its verdicts are remembered against;
 *     {@code null} until the verifier has run
 */
record ScreeningContext(
    ReviewSession session,
    String quoteSource,
    AiReviewService.PromptInputs promptInputs,
    DiffBudgetPlanner.BudgetPlan plan,
    ReviewEvidence evidence,
    ReviewResponse response,
    FindingVerificationService.FindingEvidence attached,
    ReviewResponse candidates) {

  /** The state before the first stage: the call's raw response, with nothing resolved yet. */
  static ScreeningContext start(
      ReviewSession session,
      ReviewResponse response,
      String quoteSource,
      AiReviewService.PromptInputs promptInputs,
      DiffBudgetPlanner.BudgetPlan plan,
      ReviewEvidence evidence) {
    return new ScreeningContext(
        session,
        quoteSource,
        promptInputs,
        plan,
        evidence,
        response,
        FindingVerificationService.FindingEvidence.NONE,
        null);
  }

  ScreeningContext withResponse(ReviewResponse next) {
    return new ScreeningContext(
        session, quoteSource, promptInputs, plan, evidence, next, attached, candidates);
  }

  ScreeningContext withAttached(FindingVerificationService.FindingEvidence next) {
    return new ScreeningContext(
        session, quoteSource, promptInputs, plan, evidence, response, next, candidates);
  }

  /** The verifier's verdicts replace the response, which becomes the candidates. */
  ScreeningContext withVerified(ReviewResponse verified) {
    return new ScreeningContext(
        session, quoteSource, promptInputs, plan, evidence, verified, attached, response);
  }
}
