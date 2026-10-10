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
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;

/** Builds a {@link FindingScreen} from its stages' collaborators, as CDI wires it. */
final class FindingScreens {

  private FindingScreens() {}

  static FindingScreen of(
      FindingQuoteValidator quoteValidator,
      FrameworkFalsePositiveFilter frameworkFilter,
      FindingDeduplicator deduplicator,
      FindingVerificationService verification,
      VerifierRejectionMemory rejectionMemory,
      DiffBudgetPlanner budgetPlanner,
      TokenCounter tokenCounter) {
    return new FindingScreen(
        new EvidenceStage(),
        new QuoteValidationStage(quoteValidator),
        new FrameworkFilterStage(frameworkFilter),
        new DedupeStage(deduplicator),
        new RejectionScreenStage(rejectionMemory),
        new VerificationStage(verification, budgetPlanner, tokenCounter),
        new RememberVerdictsStage(rejectionMemory));
  }
}
