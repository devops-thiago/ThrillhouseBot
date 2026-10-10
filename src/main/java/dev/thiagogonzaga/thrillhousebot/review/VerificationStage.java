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

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Puts the surviving findings to the verifier, with the evidence resolved for the raw findings, and
 * records the verifier's coverage on the plan. The findings it sends become the context's
 * candidates, which the verdicts are remembered against.
 */
@ApplicationScoped
class VerificationStage implements FindingStage {

  private final FindingVerificationService findingVerificationService;
  private final DiffBudgetPlanner budgetPlanner;
  private final TokenCounter tokenCounter;

  @Inject
  VerificationStage(
      FindingVerificationService findingVerificationService,
      DiffBudgetPlanner budgetPlanner,
      TokenCounter tokenCounter) {
    this.findingVerificationService = findingVerificationService;
    this.budgetPlanner = budgetPlanner;
    this.tokenCounter = tokenCounter;
  }

  @Override
  public ScreeningContext apply(ScreeningContext context) {
    var inputs = context.promptInputs();
    // #736: the verification call is the one review-path call that does no budget arithmetic of
    // its own, so the section the author alone sizes is bounded here before it is sent.
    var verified =
        findingVerificationService.verify(
            ReviewTokenLedger.keyFor(context.session()),
            context.response(),
            PrContextBudget.bound(
                inputs.prContext(), budgetPlanner.perCallInputBudget(), tokenCounter),
            inputs.diff(),
            inputs.projectStack(),
            inputs.previousFindings(),
            context.attached(),
            context.plan()::recordVerificationCoverage);
    return context.withVerified(verified);
  }
}
