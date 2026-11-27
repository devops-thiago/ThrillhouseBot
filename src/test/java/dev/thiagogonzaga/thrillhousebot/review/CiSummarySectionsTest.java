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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CiSummarySectionsTest {

  private static final ReviewResult.CiCheck PENDING =
      new ReviewResult.CiCheck("build", "check-run", "pending", null);
  private static final ReviewResult.CiCheck FAILED =
      new ReviewResult.CiCheck("build", "check-run", "failing", "failure");

  private final PrSummaryGenerator generator = new PrSummaryGenerator(true);

  private static ReviewResult ci(ReviewState state, List<ReviewResult.CiCheck> checks) {
    return new ReviewResult(
        List.of(), 0, 0, 0, 0, null, state, true, "", List.of(), checks, 0, false);
  }

  private String heldSummary() {
    return generator.generate(1, 5, 0, List.of(), null, ci(ReviewState.COMMENT, List.of(PENDING)));
  }

  private static String heldAgentSection() {
    var held = ci(ReviewState.COMMENT, List.of(PENDING));
    var state = PrSummaryGenerator.currentState(held);
    return "intro\n"
        + AgentNextSteps.render(
            held,
            "abc1234",
            null,
            state,
            FindingsManifest.render(state),
            BotIdentity.of("thrillhousebot[bot]"))
        + "\n\noutro";
  }

  @Test
  void aReleasedHoldAlsoUpdatesTheAgentNextStepsSection() {
    var held = heldAgentSection();
    assertTrue(held.contains("**CI:** pending or failing"), held);
    assertTrue(held.contains("Fix failing CI"), held);

    var released = CiSummarySections.refresh(held, ci(ReviewState.APPROVE, List.of()));

    assertTrue(released.contains("**CI:** required checks green."), released);
    assertFalse(released.contains("pending or failing"), released);
    assertFalse(released.contains("Fix failing CI"), released);
    assertTrue(released.endsWith("outro"), released);
  }

  @Test
  void aKeptHoldShowsTheFailingCheckInTheAgentNextStepsSection() {
    var kept =
        CiSummarySections.refresh(heldAgentSection(), ci(ReviewState.COMMENT, List.of(FAILED)));

    assertTrue(kept.contains("**CI:** pending or failing"), kept);
    assertTrue(kept.contains('`' + FAILED.name() + '`'), kept);
    assertTrue(kept.contains("Fix failing CI"), kept);
  }

  @Test
  void aReleasedHoldNoLongerSaysItCannotBeApprovedOrShowsPendingChecks() {
    var held = heldSummary();
    assertTrue(held.contains("cannot be approved"), held);
    assertTrue(held.contains("Pending"), held);

    var released = CiSummarySections.refresh(held, ci(ReviewState.APPROVE, List.of()));

    assertFalse(released.contains("cannot be approved"), released);
    assertFalse(released.contains("Pending"), released);
    assertTrue(released.contains("the held approval was posted"), released);
    assertTrue(released.contains("All required checks are green."), released);
    assertTrue(released.contains("Reply with `/review` to re-run."), released);
    assertEquals(released, CiSummarySections.refresh(released, ci(ReviewState.APPROVE, List.of())));
  }

  @Test
  void aStillHeldSummaryShowsTheCheckThatHasSinceFailed() {
    var refreshed =
        CiSummarySections.refresh(heldSummary(), ci(ReviewState.COMMENT, List.of(FAILED)));

    assertTrue(refreshed.contains("cannot be approved"), refreshed);
    assertTrue(refreshed.contains("❌ Failed"), refreshed);
    assertFalse(refreshed.contains("Pending"), refreshed);
  }

  @Test
  void unknownRequiredChecksAreNotCalledRequired() {
    var released =
        CiSummarySections.refresh(
            "x\n" + CiSummarySections.wrap(CiSummarySections.CHECKS, "old"),
            new ReviewResult(
                List.of(),
                0,
                0,
                0,
                0,
                null,
                ReviewState.APPROVE,
                true,
                "",
                List.of(),
                List.of(),
                0,
                false,
                false,
                ReviewResult.TruncationDetail.EMPTY));

    assertTrue(released.contains("All checks are green."), released);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a summary with no markers",
        "x\n<!-- thrillhousebot:ci-checks -->\nnever closed",
        "x\nquoted <!-- thrillhousebot:ci-checks --> inline\n<!-- /thrillhousebot:ci-checks -->"
      })
  void aBodyWithoutAMarkedSectionIsLeftAlone(String body) {
    assertEquals(body, CiSummarySections.refresh(body, ci(ReviewState.APPROVE, List.of())));
  }

  @Test
  void aVerdictThatCannotBeReleasedIsNotMarked() {
    var summary =
        generator.generate(
            1,
            5,
            0,
            List.of(),
            null,
            new ReviewResult(
                List.of(),
                0,
                0,
                0,
                0,
                null,
                ReviewState.COMMENT,
                true,
                "",
                List.of(),
                List.of(PENDING),
                2,
                false));

    assertFalse(summary.contains("thrillhousebot:ci-"), summary);
  }
}
