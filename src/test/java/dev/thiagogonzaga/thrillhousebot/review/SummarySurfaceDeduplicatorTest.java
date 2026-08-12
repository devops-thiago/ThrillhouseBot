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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummarySurfaceDeduplicatorTest {

  private static Finding finding(String title) {
    return new Finding(RiskLevel.HIGH, "src/A.java", 1, title, "desc", null, null);
  }

  @Test
  void keepsAGapThatStatesSomethingNoFindingStates() {
    var gaps =
        List.of("The PR promises a health check before dispatch, but no health field is present.");
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            gaps,
            Map.of(),
            List.of(finding("Worker registry pagination not followed; only first page returned")));

    assertEquals(gaps, surfaces.descriptionGaps());
  }

  @Test
  void dropsASecondGapThatOnlyRephrasesTheFirst() {
    // No finding at all: the gaps are collapsed against each other, first bullet wins.
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(
                "RunSummary.degraded is computed from every result rather than only failures, so a"
                    + " successful tick is reported degraded.",
                "The degraded flag is computed from all results and not just failures, which"
                    + " reports a successful tick as degraded."),
            Map.of(),
            List.of());

    assertEquals(1, surfaces.descriptionGaps().size());
    assertTrue(surfaces.descriptionGaps().get(0).startsWith("RunSummary.degraded"));
  }

  @Test
  void aFindingWithNoTitleMatchesNothing() {
    var gaps = List.of("The PR description omits the new retry budget entirely.");

    var surfaces = SummarySurfaceDeduplicator.collapse(gaps, Map.of(), List.of(finding(null)));

    assertEquals(gaps, surfaces.descriptionGaps());
  }

  @Test
  void aClaimTooShortToScoreLeavesBothCopiesStanding() {
    // Four content words on the shorter side: below MIN_OVERLAP_TOKENS, and no shared 3-word run.
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(),
            Map.of("src/A.java", "Adds the repository; query is vulnerable to SQL injection"),
            List.of(finding("SQL injection in task-name search query")));

    assertEquals(
        "Adds the repository; query is vulnerable to SQL injection",
        surfaces.fileSummaries().get("src/A.java"));
  }

  @Test
  void singleClauseWalkthroughNoteIsReturnedUntouched() {
    var note = "Added round-robin dispatch and the degraded flag computed from all results";
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(),
            Map.of("src/A.java", note),
            List.of(finding("degraded flag computed from all results, not just failures")));

    // Identity, not just equality: a note with no clause break never goes through the rebuild.
    assertSame(note, surfaces.fileSummaries().get("src/A.java"));
  }

  @Test
  void keepsANonRestatingClauseAndTheOriginalWhenNothingIsDropped() {
    var note = "Added HTTP client for the registry; linear membership lookup";
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(), Map.of("src/A.java", note), List.of(finding("Unrelated parser off-by-one")));

    assertSame(note, surfaces.fileSummaries().get("src/A.java"));
  }

  @Test
  void dropsOnlyTheRestatingClauseAndKeepsTheRest() {
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(),
            Map.of(
                "src/A.java",
                "Added HTTP client for worker registry; returns only the first page of workers"
                    + " (pagination not walked); also adds a retry budget"),
            List.of(finding("Worker registry pagination not followed; only first page returned")));

    assertEquals(
        "Added HTTP client for worker registry; also adds a retry budget",
        surfaces.fileSummaries().get("src/A.java"));
  }

  @Test
  void emptyClausesAroundASemicolonAreNotTreatedAsContent() {
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(),
            Map.of(
                "src/A.java",
                "Added HTTP client for worker registry; returns only the first page of workers"
                    + " (pagination not walked);  ;"),
            List.of(finding("Worker registry pagination not followed; only first page returned")));

    assertEquals(
        "Added HTTP client for worker registry", surfaces.fileSummaries().get("src/A.java"));
  }

  @Test
  void tokenizerDropsStopWordsNumbersAndSingleCharacters() {
    // "is"/"at" are stop words, "c" is a single character and "45" a line number: none of them
    // says anything about the claim, and all three match nearly every other text.
    assertEquals(
        List.of("banlist", "rea", "src", "main"),
        SummarySurfaceDeduplicator.contentTokens("banlist is read at src/main.c:45"));
  }

  @Test
  void inflectedFormsOfOneWordCollideAndShortWordsAreLeftAlone() {
    assertEquals(
        SummarySurfaceDeduplicator.contentTokens("hardcoded failures return"),
        SummarySurfaceDeduplicator.contentTokens("hardcodes failure returned"));
    assertEquals(List.of("use", "log"), SummarySurfaceDeduplicator.contentTokens("use logs"));
  }
}
