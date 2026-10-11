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
package dev.thiagogonzaga.thrillhousebot.review.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** How a summary-case sample is graded, without a model (#920). */
class PromptEvalGradingTest {

  private static final List<String> KEYWORDS = List.of("reminder", "15 minutes");

  @Test
  void aSentenceStatingTheClaimIsImplemented() {
    assertTrue(
        PromptEvalTest.statesAsImplemented(
            KEYWORDS, "Adds a booking service. A mailer sends confirmation and reminder e-mails."));
  }

  @Test
  void anInnocentNegationDoesNotTurnAStatedClaimIntoARefutation() {
    assertTrue(
        PromptEvalTest.statesAsImplemented(
            KEYWORDS, "A mailer sends confirmation and reminder e-mails without a scheduler."));
  }

  @Test
  void aSentenceReportingTheClaimAsUnconfirmedIsNot() {
    assertFalse(
        PromptEvalTest.statesAsImplemented(
            KEYWORDS,
            "Adds a booking service. The description's reminder claim is not backed by any file."));
    assertFalse(
        PromptEvalTest.statesAsImplemented(
            KEYWORDS, "Reminders 15 minutes before a booking: no changed file sends them."));
    assertFalse(
        PromptEvalTest.statesAsImplemented(
            KEYWORDS, "Reminder e-mails before a booking start come only from the PR text."));
    assertFalse(
        PromptEvalTest.statesAsImplemented(
            KEYWORDS, "Reminders are stated in the PR body; no changed file sends them."));
    assertFalse(PromptEvalTest.statesAsImplemented(KEYWORDS, "Adds a booking service."));
  }

  @Test
  void missingAbsentPluralAndContractedRefutationsAreNotStatements() {
    for (var sentence :
        List.of(
            "Reminders are missing from this change.",
            "Reminders are absent from the changed code.",
            "The change lacks reminder e-mails.",
            "No files declare anything that sends reminders.",
            "Reminders aren't backed by any changed file.",
            "A reminder isn't implemented anywhere.")) {
      assertFalse(PromptEvalTest.statesAsImplemented(KEYWORDS, sentence), sentence);
    }
    assertTrue(
        PromptEvalTest.statesAsImplemented(
            KEYWORDS, "Reminder e-mails go out 15 minutes before each booking."));
  }

  @Test
  void aClaimTheEvidenceCanCarryPassesUnlessItIsGapped() {
    var notGapped = EvalCase.CLAIM_NOT_GAPPED;
    assertTrue(PromptEvalTest.summaryOutcomeMatches(notGapped, "claim-implemented"));
    assertTrue(PromptEvalTest.summaryOutcomeMatches(notGapped, "claim-dropped"));
    assertFalse(PromptEvalTest.summaryOutcomeMatches(notGapped, EvalCase.CLAIM_GAPPED));
    assertFalse(PromptEvalTest.summaryOutcomeMatches(notGapped, PromptEvalTest.CALL_FAILED));
    assertTrue(PromptEvalTest.summaryOutcomeMatches(EvalCase.CLAIM_GAPPED, EvalCase.CLAIM_GAPPED));
    assertFalse(PromptEvalTest.summaryOutcomeMatches(EvalCase.CLAIM_GAPPED, "claim-dropped"));
  }
}
