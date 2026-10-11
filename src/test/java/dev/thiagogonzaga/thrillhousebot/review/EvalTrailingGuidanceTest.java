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

import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link EvalTrailingGuidance}: the live eval sends the trailing guidance a review of the same diff
 * would (#979). Runs in every build, so the eval's prompt cannot drift from production unnoticed.
 */
class EvalTrailingGuidanceTest {

  private static String corpusDiff(String name) throws IOException {
    return Files.readString(Path.of("src/test/resources/evalcorpus/" + name + "/diff.txt"));
  }

  /** pr100's defect is the heuristic request's own probe; production sends that request. */
  @Test
  void theNoNewlineCaseCarriesTheHeuristicFailureModeRequest() throws IOException {
    var guidance =
        EvalTrailingGuidance.forCase(
            corpusDiff("pr100-no-newline-indicator-must-find"),
            "",
            "Extends the validator.",
            "",
            "");

    assertTrue(guidance.contains(PrReviewPrompts.HEURISTIC_FAILURE_MODES_REQUEST), guidance);
    assertFalse(guidance.contains(PrReviewPrompts.MOCK_FIDELITY_REQUEST), guidance);
  }

  /** pr102 changes a test and declares a bug fix: mock fidelity and bug-fix efficacy, in order. */
  @Test
  void theMockCaseCarriesMockFidelityThenBugFixEfficacy() throws IOException {
    var tests =
        EvalTrailingGuidance.relatedTests(
            List.of(
                "src/main/java/dev/thiagogonzaga/thrillhousebot/review/ReviewDispatcher.java",
                "src/test/java/dev/thiagogonzaga/thrillhousebot/webhook/WebhookControllerTest.java"));
    var guidance =
        EvalTrailingGuidance.forCase(
            corpusDiff("pr102-mock-contradicts-dispatch-must-find"),
            tests,
            "- [x] 🐛 Bug fix\n\nFixes #89",
            "",
            "");

    assertEquals(
        "src/test/java/dev/thiagogonzaga/thrillhousebot/webhook/WebhookControllerTest.java", tests);
    var mock = guidance.indexOf(PrReviewPrompts.MOCK_FIDELITY_REQUEST);
    var bugFix = guidance.indexOf(PrReviewPrompts.BUG_FIX_EFFICACY_REQUEST);
    assertTrue(mock >= 0 && bugFix > mock, guidance);
  }

  @Test
  void patchCoverageIsFencedAsTheAssemblerSendsIt() {
    var guidance = EvalTrailingGuidance.forCase("", "", "", "- src/A.java: 3", "");

    assertTrue(guidance.startsWith(PrReviewPrompts.PATCH_COVERAGE_REQUEST), guidance);
    assertTrue(guidance.contains(PromptTemplateEscaper.fencePrefix()), guidance);
    assertEquals("", EvalTrailingGuidance.forCase("", "", "", "", ""));
  }

  /** A case that pins a stored decision carries it fenced, last, as the assembler sends it. */
  @Test
  void learningsAreFencedAfterTheOtherSections() {
    var guidance =
        EvalTrailingGuidance.forCase("", "", "", "- src/A.java: 3", "- COPY --from tags: declined");

    var coverage = guidance.indexOf(PrReviewPrompts.PATCH_COVERAGE_REQUEST);
    var learnings = guidance.indexOf(PrReviewPrompts.LEARNINGS_REQUEST);
    assertTrue(coverage >= 0 && learnings > coverage, guidance);
    assertTrue(guidance.indexOf("- COPY --from tags: declined") > learnings, guidance);
  }
}
