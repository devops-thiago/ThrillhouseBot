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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SummarySurfaceDeduplicatorTest {

  private static Finding finding(String title) {
    return new Finding(RiskLevel.HIGH, "src/A.java", 1, title, "desc", null, null);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("gapsNoFindingRestates")
  void keepsAGapNoFindingRestates(String label, String gap, String findingTitle) {
    var gaps = List.of(gap);

    var surfaces =
        SummarySurfaceDeduplicator.collapse(gaps, Map.of(), List.of(finding(findingTitle)));

    assertEquals(gaps, surfaces.descriptionGaps(), label);
  }

  static Stream<Arguments> gapsNoFindingRestates() {
    return Stream.of(
        arguments(
            "a gap that states something no finding states",
            "The PR promises a health check before dispatch, but no health field is present.",
            "Worker registry pagination not followed; only first page returned"),
        arguments(
            "a finding with no title matches nothing",
            "The PR description omits the new retry budget entirely.",
            null),
        // Identical but for the negation: "does"/"not" used to be stop words, so both sides
        // tokenized the same and the gap was deleted as a duplicate of the claim it contradicts.
        arguments(
            "a negated paraphrase contradicts the finding instead of restating it",
            "Path traversal: unvalidated client source does not reach fopen path",
            "Path traversal: unvalidated client source reaches fopen path"));
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
  void aWalkthroughClauseIsKeptWhenItAssertsTheOppositeOfTheFinding() {
    // The clause names strictly less than the finding does, so containment runs the other way.
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(),
            Map.of(
                "src/A.java",
                "Adds the repository; user input is sanitized before it reaches the query"),
            List.of(finding("User input is not sanitized before it reaches the SQL query")));

    assertEquals(
        "Adds the repository; user input is sanitized before it reaches the query",
        surfaces.fileSummaries().get("src/A.java"));
  }

  @Test
  void oppositePolarityStillCollapsesWhenTheTwoAlsoDifferInSubstance() {
    // A description gap normally quotes the PR's affirmative promise while the finding reports the
    // absence, so differing polarity alone must not keep a genuine duplicate alive.
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(
                "The PR says the worker registry is walked page by page, but the client stops"
                    + " after the first page of workers it receives."),
            Map.of(),
            List.of(finding("Worker registry pagination not followed; only first page returned")));

    assertEquals(List.of(), surfaces.descriptionGaps());
  }

  @Test
  void aContractedNegationIsDetectedSoItsOppositeSurvives() {
    // Splitting on non-alphanumeric runs tears "doesn't" into "doesn" and "t", so without the
    // contraction rewrite both gaps read as affirmative, score 1.0 on the shorter side, and the
    // negative conclusion is deleted as a restatement of the affirmative one.
    var gaps =
        List.of(
            "The registry URL is validated before use.",
            "The client doesn't validate the registry URL before use.");

    var surfaces = SummarySurfaceDeduplicator.collapse(gaps, Map.of(), List.of());

    assertEquals(gaps, surfaces.descriptionGaps());
  }

  @Test
  void aContractedNegationInTheFindingKeepsTheOpposingWalkthroughClause() {
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            List.of(),
            Map.of(
                "src/A.java",
                "Adds the repository; user input is sanitized before it reaches the SQL query"),
            List.of(finding("User input isn't sanitized before it reaches the SQL query")));

    assertEquals(
        "Adds the repository; user input is sanitized before it reaches the SQL query",
        surfaces.fileSummaries().get("src/A.java"));
  }

  @Test
  void everyContractedNegatorFormSetsPolarityAndLeavesNoStrayWord() {
    var plain = SummarySurfaceDeduplicator.claim("the value is sanitized");

    for (String contracted :
        List.of(
            "the value isn't sanitized",
            "the value isn’t sanitized",
            "the value wasn't sanitized",
            "the value won't be sanitized",
            "the value can't be sanitized",
            "the value couldn't be sanitized",
            "the value shouldn't be sanitized",
            "the value didn't sanitize")) {
      var claim = SummarySurfaceDeduplicator.claim(contracted);

      assertTrue(claim.negated(), contracted);
      assertEquals(plain.words(), claim.words(), contracted);
    }
  }

  @Test
  void negatorsSetPolarityInsteadOfBecomingContentWords() {
    var negated = SummarySurfaceDeduplicator.claim("the banlist is never refreshed");
    var plain = SummarySurfaceDeduplicator.claim("the banlist is refreshed");

    assertEquals(plain.words(), negated.words());
    assertTrue(negated.negated());
    assertFalse(plain.negated());
  }

  @Test
  void tokenizerDropsStopWordsNumbersAndSingleCharacters() {
    // "is"/"at" are stop words, "c" is a single character and "45" a line number: none of them
    // says anything about the claim, and all three match nearly every other text.
    assertEquals(
        List.of("banlist", "rea", "src", "main"),
        SummarySurfaceDeduplicator.claim("banlist is read at src/main.c:45").words());
  }

  @Test
  void inflectedFormsOfOneWordCollideAndShortWordsAreLeftAlone() {
    assertEquals(
        SummarySurfaceDeduplicator.claim("hardcoded failures return").words(),
        SummarySurfaceDeduplicator.claim("hardcodes failure returned").words());
    assertEquals(List.of("use", "log"), SummarySurfaceDeduplicator.claim("use logs").words());
  }
}
