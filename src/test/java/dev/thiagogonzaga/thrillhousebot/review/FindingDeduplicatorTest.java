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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class FindingDeduplicatorTest {

  private final FindingDeduplicator deduplicator = new FindingDeduplicator();

  private static ReviewResponse.Finding finding(
      String risk, String file, int line, String title, String description) {
    return new ReviewResponse.Finding(risk, "high", file, line, title, description, "old", "new");
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    int critical = 0;
    for (var f : findings) {
      if ("critical".equals(f.risk())) critical++;
    }
    return new ReviewResponse(
        List.of(findings),
        List.of(),
        new ReviewResponse.Summary(
            findings.length, critical, 0, 0, 0, "assessment", "purpose", List.of()));
  }

  @Test
  void mergesSameDefectReportedAtThreeSeveritiesIntoMedian() {
    var response =
        response(
            finding("low", "src/Prompt.java", 42, "Literal backslash-n instead of newline", "a"),
            finding(
                "medium",
                "src/Prompt.java",
                43,
                "Literal backslash-n used instead of newline",
                "a longer description of the same defect"),
            finding("high", "src/Prompt.java", 44, "Backslash-n literal instead of newline", "b"));

    var result = deduplicator.dedupe(response);

    assertEquals(1, result.findings().size());
    var merged = result.findings().get(0);
    assertEquals("medium", merged.risk());
    assertEquals("a longer description of the same defect", merged.description());
    assertEquals(1, result.summary().totalFindings());
  }

  @Test
  void keepsDistinctFindingsUntouched() {
    var response =
        response(
            finding("high", "src/A.java", 10, "SQL injection in query builder", "a"),
            finding("high", "src/A.java", 12, "Missing null check on user input", "b"),
            finding("high", "src/B.java", 10, "SQL injection in query builder", "c"));

    assertSame(response, deduplicator.dedupe(response));
  }

  @Test
  void lineDistanceBeyondToleranceIsNotADuplicate() {
    var response =
        response(
            finding("high", "src/A.java", 10, "Missing null check", "a"),
            finding("high", "src/A.java", 20, "Missing null check", "b"));

    assertSame(response, deduplicator.dedupe(response));
  }

  @Test
  void singleFindingPassesThrough() {
    var response = response(finding("low", "src/A.java", 1, "Title", "d"));

    assertSame(response, deduplicator.dedupe(response));
  }

  @Test
  void twoDuplicatesKeepTheMoreSevereSeverity() {
    var response =
        response(
            finding("critical", "src/A.java", 5, "Unbounded recursion in parser", "short"),
            finding("high", "src/A.java", 5, "Unbounded recursion in parser", "short too"));

    var result = deduplicator.dedupe(response);

    assertEquals(1, result.findings().size());
    assertEquals("critical", result.findings().get(0).risk());
  }

  @Test
  void evenClusterTakesTheMoreSevereOfTheTwoCentralSeverities() {
    var response =
        response(
            finding("critical", "src/A.java", 5, "Unbounded recursion in parser", "a"),
            finding("high", "src/A.java", 5, "Unbounded recursion in parser", "b"),
            finding("medium", "src/A.java", 6, "Unbounded recursion in parser", "c"),
            finding("low", "src/A.java", 6, "Unbounded recursion in parser", "d"));

    var result = deduplicator.dedupe(response);

    assertEquals(1, result.findings().size());
    assertEquals("high", result.findings().get(0).risk());
  }

  @Test
  void mergesOnlyTheDuplicatePairAndKeepsTheRest() {
    var response =
        response(
            finding("high", "src/A.java", 5, "Unbounded recursion in parser", "first"),
            finding("medium", "src/A.java", 6, "Unbounded recursion in parser", null),
            finding("low", "src/B.java", 90, "Unrelated finding", "kept as is"),
            new ReviewResponse.Finding(
                "low", "high", null, 1, "No file on this one", "kept too", null, null));

    var result = deduplicator.dedupe(response);

    assertEquals(3, result.findings().size());
    assertEquals("Unbounded recursion in parser", result.findings().get(0).title());
    assertEquals("first", result.findings().get(0).description());
    assertEquals("Unrelated finding", result.findings().get(1).title());
    assertEquals("No file on this one", result.findings().get(2).title());
  }

  @Test
  void filelessFindingFirstNeverAnchorsACluster() {
    var response =
        response(
            new ReviewResponse.Finding(
                "low", "high", null, 1, "No file on this one", "kept", null, null),
            finding("high", "src/A.java", 5, "Unbounded recursion in parser", "first"),
            finding("medium", "src/A.java", 6, "Unbounded recursion in parser", null));

    var result = deduplicator.dedupe(response);

    assertEquals(2, result.findings().size());
    assertEquals("No file on this one", result.findings().get(0).title());
  }

  @Test
  void mergePreservesBlockingConfidenceOfTheMedianRisk() {
    var response =
        response(
            new ReviewResponse.Finding(
                "critical",
                "high",
                "src/A.java",
                5,
                "Auth bypass in token check",
                "terse",
                "o",
                "n"),
            new ReviewResponse.Finding(
                "critical",
                "low",
                "src/A.java",
                6,
                "Auth bypass in token check",
                "a very long hedged description of the same defect",
                "o",
                "n"));

    var result = deduplicator.dedupe(response);

    assertEquals(1, result.findings().size());
    assertEquals("critical", result.findings().get(0).risk());
    assertEquals("high", result.findings().get(0).confidence());
    assertEquals(
        "a very long hedged description of the same defect",
        result.findings().get(0).description());
  }

  @Test
  void mergeDoesNotPairMedianRiskWithAnotherMembersConfidence() {
    var response =
        response(
            new ReviewResponse.Finding(
                "critical",
                "low",
                "src/A.java",
                5,
                "Race condition in cache",
                "longest text here",
                "o",
                "n"),
            new ReviewResponse.Finding(
                "critical", "low", "src/A.java", 6, "Race condition in cache", "short", "o", "n"),
            new ReviewResponse.Finding(
                "medium", "high", "src/A.java", 7, "Race condition in cache", "mid", "o", "n"));

    var result = deduplicator.dedupe(response);

    assertEquals(1, result.findings().size());
    assertEquals("critical", result.findings().get(0).risk());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void chainedDuplicatesMergeIntoOneCluster() {
    var response =
        response(
            finding("high", "src/A.java", 10, "Missing null check on request", "a"),
            finding("high", "src/A.java", 12, "Missing null check on request", "b"),
            finding("high", "src/A.java", 14, "Missing null check on request", "c"));

    var result = deduplicator.dedupe(response);

    assertEquals(1, result.findings().size());
  }

  @Test
  void mergeTakesSiblingSuggestionWhenRichestHasNone() {
    var response =
        response(
            new ReviewResponse.Finding(
                "high",
                "high",
                "src/A.java",
                5,
                "Unbounded recursion in parser",
                "much longer richest description",
                null,
                null),
            new ReviewResponse.Finding(
                "high",
                "high",
                "src/A.java",
                5,
                "Unbounded recursion in parser",
                "short",
                "verbatim old",
                "verbatim new"));

    var result = deduplicator.dedupe(response);

    assertEquals(1, result.findings().size());
    assertEquals("much longer richest description", result.findings().get(0).description());
    assertEquals("verbatim old", result.findings().get(0).suggestionOld());
    assertEquals("verbatim new", result.findings().get(0).suggestionNew());
  }

  @Test
  void mergeKeepsNoSuggestionWhenNoMemberHasOne() {
    var response =
        response(
            new ReviewResponse.Finding(
                "high",
                "high",
                "src/A.java",
                5,
                "Unbounded recursion in parser",
                "longer",
                null,
                null),
            new ReviewResponse.Finding(
                "high", "high", "src/A.java", 5, "Unbounded recursion in parser", "s", null, null));

    var result = deduplicator.dedupe(response);

    assertEquals(1, result.findings().size());
    assertEquals(null, result.findings().get(0).suggestionOld());
  }

  @Test
  void pathVariantsOfTheSameFileAreDuplicates() {
    var response =
        response(
            finding("high", "nested/path/B.java", 5, "Unbounded recursion in parser", "longer a"),
            finding("high", "path/B.java", 6, "Unbounded recursion in parser", "b"));

    var result = deduplicator.dedupe(response);

    assertEquals(1, result.findings().size());
  }

  @Test
  void filePathsHelperMatchesAtDirectoryBoundariesOnly() {
    assertTrue(FilePaths.same("src/B.java", "src/B.java"));
    assertTrue(FilePaths.same("nested/path/B.java", "path/B.java"));
    assertFalse(FilePaths.same("B.java", "nested/path/B.java"));
    assertFalse(FilePaths.same("path/B.java", "path/C.java"));
    assertFalse(FilePaths.same("MyB.java", "B.java"));
    assertFalse(FilePaths.same(null, "B.java"));
    assertFalse(FilePaths.same("B.java", null));
  }

  @Test
  void titleSimilarityIgnoresLeadingSeparatorsAndPunctuation() {
    assertTrue(
        FindingDeduplicator.titleSimilarity("--- Missing null check!", "missing null check")
            >= FindingDeduplicator.TITLE_SIMILARITY_THRESHOLD);
  }

  @Test
  void contentOverlapSeparatesParaphrasesFromDistinctClaims() {
    var raised =
        new ReviewResponse.Finding(
            "medium",
            "high",
            "f",
            144,
            "Deduplicator may throw NullPointerException for unrecognized risk strings",
            "merge maps each risk string via RiskLevel.fromString which may return null;"
                + " sorted can then throw NullPointerException",
            null,
            null);
    var paraphrase =
        new ReviewResponse.Finding(
            "medium",
            "high",
            "f",
            115,
            "Potential NullPointerException if risk string is not a recognized enum value",
            "merge calls RiskLevel.fromString then sorts the list; fromString may return null"
                + " and sorting a null throws NullPointerException",
            null,
            null);
    var distinct =
        new ReviewResponse.Finding(
            "medium",
            "high",
            "f",
            60,
            "Cluster anchor comparison misses chained duplicates",
            "deduplication compares each finding only to the first cluster member so chains"
                + " split into several clusters",
            null,
            null);

    assertTrue(
        FindingDeduplicator.contentOverlap(raised, paraphrase)
            >= FindingDeduplicator.CONTENT_OVERLAP_THRESHOLD);
    assertTrue(
        FindingDeduplicator.contentOverlap(raised, distinct)
            < FindingDeduplicator.CONTENT_OVERLAP_THRESHOLD);
    assertEquals(
        0.0,
        FindingDeduplicator.contentOverlap(
            raised, new ReviewResponse.Finding("low", "high", "f", 1, null, null, null, null)));
  }

  @Test
  void titleSimilarityIsTokenBased() {
    assertTrue(
        FindingDeduplicator.titleSimilarity(
                "Literal backslash-n instead of newline", "literal BACKSLASH-N instead of newline")
            >= FindingDeduplicator.TITLE_SIMILARITY_THRESHOLD);
    assertTrue(
        FindingDeduplicator.titleSimilarity("SQL injection", "Missing null check")
            < FindingDeduplicator.TITLE_SIMILARITY_THRESHOLD);
    assertEquals(0.0, FindingDeduplicator.titleSimilarity(null, "x"));
    assertEquals(0.0, FindingDeduplicator.titleSimilarity("x", "  "));
  }
}
