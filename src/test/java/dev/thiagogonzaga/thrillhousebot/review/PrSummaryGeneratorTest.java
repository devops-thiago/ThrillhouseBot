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

import static org.junit.jupiter.api.Assertions.*;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrSummaryGeneratorTest {

  private final PrSummaryGenerator generator = new PrSummaryGenerator();

  @Test
  void shouldGenerateSummaryWithFindings() {
    var findings =
        List.of(
            new Finding(RiskLevel.HIGH, "src/A.java", 1, "Bug", "desc", null, null),
            new Finding(RiskLevel.MEDIUM, "src/B.java", 2, "Smell", "desc", null, null));

    var result =
        new ReviewResult(
            findings, 0, 1, 1, 0, RiskLevel.HIGH, ReviewState.REQUEST_CHANGES, true, "", List.of());

    var summary = generator.generate(3, 120, 45, null, result);

    assertTrue(summary.contains("🤖 ThrillhouseBot PR Summary"));
    assertFalse(summary.contains("Repository:"));
    assertTrue(summary.contains("+120"));
    assertTrue(summary.contains("-45"));
    assertTrue(summary.contains("🔴 Critical | 0"));
    assertTrue(summary.contains("🟠 High | 1"));
    assertTrue(summary.contains("/review"));
  }

  @Test
  void shouldRenderPrPurposeAndDescriptionGaps() {
    var aiSummary =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            "ok",
            "Adds a user update endpoint with validation.",
            List.of("Description claims tests were added, but no test files changed", "  "));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 5, 0, aiSummary, result);

    assertTrue(summary.contains("### What this PR does"));
    assertTrue(summary.contains("Adds a user update endpoint with validation."));
    assertTrue(summary.contains("### ⚠️ Description vs. Implementation"));
    assertTrue(summary.contains("- Description claims tests were added"));
    // The blank gap entry is skipped, leaving a single gap bullet (overview bullets use "- **")
    assertEquals(
        1, summary.lines().filter(l -> l.startsWith("- ") && !l.startsWith("- **")).count());
  }

  @Test
  void shouldOmitPurposeAndGapsSectionsWhenAbsent() {
    var blankSummary = new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", " ", List.of());
    var nullPurposeSummary = new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", null, null);
    // Only blank gaps must not render a section header with zero bullets
    var blankGapsSummary = new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", null, List.of(" ", ""));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    for (var summary :
        List.of(
            generator.generate(1, 0, 0, null, result),
            generator.generate(1, 0, 0, blankSummary, result),
            generator.generate(1, 0, 0, nullPurposeSummary, result),
            generator.generate(1, 0, 0, blankGapsSummary, result))) {
      assertFalse(summary.contains("What this PR does"));
      assertFalse(summary.contains("Description vs. Implementation"));
    }
  }

  @Test
  void shouldNotRenderSignedZeroLineCounts() {
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(3, 169, 0, null, result);

    assertTrue(summary.contains("**Lines added:** +169"));
    assertTrue(summary.contains("**Lines removed:** 0"));
    assertFalse(summary.contains("-0"));
    assertTrue(generator.generate(3, 0, 7, null, result).contains("**Lines added:** 0"));
  }

  @Test
  void shouldNotIncludeDashboardLink() {
    // The dashboard deep-link lives on the check run (details_url), not in the PR comment
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    assertFalse(generator.generate(1, 0, 0, null, result).contains("View in dashboard"));
  }

  @Test
  void shouldGenerateSummaryWithZeroFindings() {
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 10, 2, null, result);

    assertTrue(summary.contains("Critical | 0"));
    assertTrue(summary.contains("High | 0"));
  }

  @Test
  void cleanReviewCelebratesInsideTheSummary() {
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 10, 2, null, result);

    assertTrue(summary.contains("Everything's coming up Thrillhouse"));
    assertTrue(summary.contains("No issues found in this PR."));
  }

  @Test
  void unresolvedStatusCasingDoesNotEnableCelebration() {
    var statuses = List.of(new ReviewResult.PreviousFindingStatus(1, "UNRESOLVED", "still"));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses);

    assertFalse(generator.generate(1, 10, 2, null, result).contains("coming up Thrillhouse"));
  }

  @Test
  void cleanReviewWithUnresolvedPreviousFindingsDoesNotCelebrate() {
    var statuses = List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "Still there"));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses);

    var summary = generator.generate(1, 10, 2, null, result);

    assertFalse(summary.contains("Everything's coming up Thrillhouse"));
  }

  @Test
  void reviewWithFindingsDoesNotCelebrate() {
    var findings = List.of(new Finding(RiskLevel.LOW, "src/A.java", 1, "Nit", "d", null, null));
    var result =
        new ReviewResult(
            findings, 0, 0, 0, 1, RiskLevel.LOW, ReviewState.COMMENT, true, "", List.of());

    var summary = generator.generate(1, 10, 2, null, result);

    assertFalse(summary.contains("Everything's coming up Thrillhouse"));
    assertTrue(summary.contains("Key Findings"));
  }

  @Test
  void shouldShowPreviousFindingsStatus() {
    var statuses =
        List.of(
            new ReviewResult.PreviousFindingStatus(1, "resolved", "Fixed"),
            new ReviewResult.PreviousFindingStatus(2, "unresolved", "Still broken"));

    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses);

    var summary = generator.generate(1, 0, 0, null, result);

    assertTrue(summary.contains("Previous Findings Status"));
    assertTrue(summary.contains("✅ Resolved"));
    assertTrue(summary.contains("⚠️ Still present"));
  }

  @Test
  void shouldLimitKeyFindingsToFive() {
    var findings =
        List.of(
            new Finding(RiskLevel.CRITICAL, "a", 1, "C1", "", null, null),
            new Finding(RiskLevel.CRITICAL, "b", 1, "C2", "", null, null),
            new Finding(RiskLevel.HIGH, "c", 1, "H1", "", null, null),
            new Finding(RiskLevel.HIGH, "d", 1, "H2", "", null, null),
            new Finding(RiskLevel.MEDIUM, "e", 1, "M1", "", null, null),
            new Finding(RiskLevel.LOW, "f", 1, "L1", "", null, null));

    var result =
        new ReviewResult(
            findings,
            2,
            2,
            1,
            1,
            RiskLevel.CRITICAL,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of());

    var summary = generator.generate(6, 0, 0, null, result);

    // Should only show 5 findings
    assertTrue(summary.contains("C1"));
    assertTrue(summary.contains("C2"));
    assertTrue(summary.contains("H1"));
    assertTrue(summary.contains("H2"));
    assertTrue(summary.contains("M1"));
    assertFalse(summary.contains("L1")); // 6th should be excluded
  }

  @Test
  void shouldShowRequiredCiChecksStatusWhenNotEmpty() {
    var checks =
        List.of(
            new ReviewResult.CiCheck("build", "check-run", "failing", null),
            new ReviewResult.CiCheck("test", "status", "pending", "pending"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), checks);

    var summary = generator.generate(1, 10, 2, null, result);

    assertFalse(summary.contains("Everything's coming up Thrillhouse"));
    assertTrue(
        summary.contains("No new issues found in this PR, but the review cannot be approved"));
    assertTrue(summary.contains("Required CI Checks Status"));
    assertTrue(summary.contains("❌ Failed"));
    assertTrue(summary.contains("⏳ Pending"));
    assertTrue(summary.contains("**build**"));
    assertTrue(summary.contains("**test**"));
  }

  @Test
  void shouldShowRequiredCiChecksStatusWhenFindingsExist() {
    var findings = List.of(new Finding(RiskLevel.LOW, "src/A.java", 1, "Nit", "d", null, null));
    var checks = List.of(new ReviewResult.CiCheck("lint", "status", "failing", "failure"));
    var result =
        new ReviewResult(
            findings, 0, 0, 0, 1, RiskLevel.LOW, ReviewState.COMMENT, true, "", List.of(), checks);

    var summary = generator.generate(1, 10, 2, null, result);

    assertFalse(summary.contains("Everything's coming up Thrillhouse"));
    assertTrue(summary.contains("Key Findings"));
    assertTrue(summary.contains("Required CI Checks Status"));
    assertTrue(summary.contains("❌ Failed"));
    assertTrue(summary.contains("**lint**"));
  }

  @Test
  void shouldEscapePipesInCiCheckTableCells() {
    // A check name containing '|' must be escaped so it cannot break the Markdown table layout.
    var checks =
        List.of(new ReviewResult.CiCheck("build | strict", "check-run", "failing", "failure"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), checks);

    var summary = generator.generate(1, 10, 2, null, result);

    assertTrue(summary.contains("build \\| strict"));
    assertFalse(summary.contains("build | strict"));
  }

  @Test
  void shouldRenderDashForNullCheckNameInCiTable() {
    var checks = List.of(new ReviewResult.CiCheck(null, "missing", "pending", null));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), checks);

    var summary = generator.generate(1, 10, 2, null, result);

    // A null name/conclusion renders as "-" rather than throwing.
    assertTrue(summary.contains("Required CI Checks Status"));
    assertTrue(summary.contains("⏳ Pending"));
  }
}
