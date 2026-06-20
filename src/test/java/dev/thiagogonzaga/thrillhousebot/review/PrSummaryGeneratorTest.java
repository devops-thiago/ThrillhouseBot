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

    var summary = generator.generate(3, 120, 45, List.of(), null, result);

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

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

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
            generator.generate(1, 0, 0, List.of(), null, result),
            generator.generate(1, 0, 0, List.of(), blankSummary, result),
            generator.generate(1, 0, 0, List.of(), nullPurposeSummary, result),
            generator.generate(1, 0, 0, List.of(), blankGapsSummary, result))) {
      assertFalse(summary.contains("What this PR does"));
      assertFalse(summary.contains("Description vs. Implementation"));
    }
  }

  @Test
  void shouldNotRenderSignedZeroLineCounts() {
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(3, 169, 0, List.of(), null, result);

    assertTrue(summary.contains("**Lines added:** +169"));
    assertTrue(summary.contains("**Lines removed:** 0"));
    assertFalse(summary.contains("-0"));
    assertTrue(generator.generate(3, 0, 7, List.of(), null, result).contains("**Lines added:** 0"));
  }

  @Test
  void shouldNotIncludeDashboardLink() {
    // The dashboard deep-link lives on the check run (details_url), not in the PR comment
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    assertFalse(generator.generate(1, 0, 0, List.of(), null, result).contains("View in dashboard"));
  }

  @Test
  void shouldGenerateSummaryWithZeroFindings() {
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertTrue(summary.contains("Critical | 0"));
    assertTrue(summary.contains("High | 0"));
  }

  @Test
  void cleanReviewCelebratesInsideTheSummary() {
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertTrue(summary.contains("Everything's coming up Thrillhouse"));
    assertTrue(summary.contains("No issues found in this PR."));
  }

  @Test
  void unresolvedStatusCasingDoesNotEnableCelebration() {
    var statuses = List.of(new ReviewResult.PreviousFindingStatus(1, "UNRESOLVED", "still"));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses);

    assertFalse(
        generator.generate(1, 10, 2, List.of(), null, result).contains("coming up Thrillhouse"));
  }

  @Test
  void cleanReviewWithUnresolvedPreviousFindingsDoesNotCelebrate() {
    var statuses = List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "Still there"));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertFalse(summary.contains("Everything's coming up Thrillhouse"));
  }

  @Test
  void reviewWithFindingsDoesNotCelebrate() {
    var findings = List.of(new Finding(RiskLevel.LOW, "src/A.java", 1, "Nit", "d", null, null));
    var result =
        new ReviewResult(
            findings, 0, 0, 0, 1, RiskLevel.LOW, ReviewState.COMMENT, true, "", List.of());

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

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

    var summary = generator.generate(1, 0, 0, List.of(), null, result);

    assertTrue(summary.contains("Previous Findings Status"));
    assertTrue(summary.contains("✅ Resolved"));
    assertTrue(summary.contains("⚠️ Still present"));
  }

  @Test
  void shouldCountPreviousFindingsStatusCaseInsensitively() {
    // Counting must be case-insensitive, consistent with the gate logic; a model emitting
    // "Resolved"/"UNRESOLVED"/"Justified" would otherwise be undercounted to zero in the table.
    var statuses =
        List.of(
            new ReviewResult.PreviousFindingStatus(1, "Resolved", "Fixed"),
            new ReviewResult.PreviousFindingStatus(2, "UNRESOLVED", "Still broken"),
            new ReviewResult.PreviousFindingStatus(3, "Justified", "Intentional"));

    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses);

    var summary = generator.generate(1, 0, 0, List.of(), null, result);

    assertTrue(summary.contains("✅ Resolved | 1"), summary);
    assertTrue(summary.contains("⚠️ Still present | 1"), summary);
    assertTrue(summary.contains("💬 Justified | 1"), summary);
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

    var summary = generator.generate(6, 0, 0, List.of(), null, result);

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

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

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

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

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

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertTrue(summary.contains("build \\| strict"));
    assertFalse(summary.contains("build | strict"));
  }

  @Test
  void shouldRenderDashForNullCheckNameInCiTable() {
    var checks = List.of(new ReviewResult.CiCheck(null, "missing", "pending", null));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), checks);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    // A null name/conclusion renders as "-" rather than throwing.
    assertTrue(summary.contains("Required CI Checks Status"));
    assertTrue(summary.contains("⏳ Pending"));
  }

  @Test
  void shouldRenderChangedFilesTableWithPerFileSummaries() {
    var changedFiles =
        List.of(
            new PrSummaryGenerator.ChangedFile("src/A.java", "modified"),
            new PrSummaryGenerator.ChangedFile("src/B.java", "added"));
    var aiSummary =
        summaryWithFiles(
            new ReviewResponse.FileSummary("src/A.java", "Adds null guard to parse()"),
            new ReviewResponse.FileSummary("src/B.java", "New cache wrapper"));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(2, 10, 1, changedFiles, aiSummary, result);

    assertTrue(summary.contains("### Changed Files"));
    assertTrue(summary.contains("| File | Change | Summary |"));
    assertTrue(summary.contains("| `src/A.java` | Modified | Adds null guard to parse() |"));
    assertTrue(summary.contains("| `src/B.java` | Added | New cache wrapper |"));
  }

  @Test
  void shouldRenderDashWhenFileHasNoMatchingSummary() {
    var changedFiles = List.of(new PrSummaryGenerator.ChangedFile("src/A.java", "modified"));
    // AI summarized a different file; the changed file still appears, with "-" for its summary.
    var aiSummary = summaryWithFiles(new ReviewResponse.FileSummary("src/Other.java", "unrelated"));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 1, 0, changedFiles, aiSummary, result);

    assertTrue(summary.contains("| `src/A.java` | Modified | - |"));
  }

  @Test
  void shouldOmitChangedFilesSectionWhenNoFiles() {
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(0, 0, 0, List.of(), summaryWithFiles(), result);

    assertFalse(summary.contains("Changed Files"));
  }

  @Test
  void shouldBoundChangedFilesTableAndReportOverflow() {
    var changedFiles = new java.util.ArrayList<PrSummaryGenerator.ChangedFile>();
    int total = PrSummaryGenerator.MAX_FILE_ROWS + 3;
    for (int i = 0; i < total; i++) {
      changedFiles.add(new PrSummaryGenerator.ChangedFile("src/F" + i + ".java", "modified"));
    }
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(total, 0, 0, changedFiles, null, result);

    // Only MAX_FILE_ROWS rows render; the first is present, the (MAX+1)th is rolled into the note.
    assertTrue(summary.contains("`src/F0.java`"));
    assertTrue(summary.contains("`src/F" + (PrSummaryGenerator.MAX_FILE_ROWS - 1) + ".java`"));
    assertFalse(summary.contains("`src/F" + PrSummaryGenerator.MAX_FILE_ROWS + ".java`"));
    assertTrue(summary.contains("…and 3 more file(s)."));
  }

  @Test
  void shouldEscapePipesInChangedFilesTable() {
    var changedFiles = List.of(new PrSummaryGenerator.ChangedFile("src/a|b.java", "modified"));
    var aiSummary =
        summaryWithFiles(new ReviewResponse.FileSummary("src/a|b.java", "handles a | b case"));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 1, 0, changedFiles, aiSummary, result);

    assertTrue(summary.contains("src/a\\|b.java"));
    assertTrue(summary.contains("handles a \\| b case"));
  }

  @Test
  void shouldLabelKnownChangeTypesAndFallBackForUnknown() {
    var changedFiles =
        List.of(
            new PrSummaryGenerator.ChangedFile("a", "added"),
            new PrSummaryGenerator.ChangedFile("b", "removed"),
            new PrSummaryGenerator.ChangedFile("c", "renamed"),
            new PrSummaryGenerator.ChangedFile("d", "modified"),
            new PrSummaryGenerator.ChangedFile("e", null),
            new PrSummaryGenerator.ChangedFile("f", "unmerged"),
            new PrSummaryGenerator.ChangedFile("g", ""),
            new PrSummaryGenerator.ChangedFile("h", "deleted"),
            new PrSummaryGenerator.ChangedFile("i", "copied"),
            new PrSummaryGenerator.ChangedFile("j", "CHANGED"));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(10, 0, 0, changedFiles, null, result);

    assertTrue(summary.contains("| `a` | Added |"));
    assertTrue(summary.contains("| `b` | Removed |"));
    assertTrue(summary.contains("| `c` | Renamed |"));
    assertTrue(summary.contains("| `d` | Modified |"));
    assertTrue(summary.contains("| `e` | Changed |")); // null status falls back to "Changed"
    assertTrue(summary.contains("| `f` | unmerged |")); // unknown status passes through verbatim
    assertTrue(summary.contains("| `g` | Changed |")); // blank status falls back to "Changed"
    assertTrue(summary.contains("| `h` | Removed |")); // "deleted" aliases to "Removed"
    assertTrue(summary.contains("| `i` | Copied |"));
    assertTrue(summary.contains("| `j` | Modified |")); // matching is case-insensitive
  }

  @Test
  void shouldOmitChangedFilesSectionWhenChangedFilesNull() {
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(0, 0, 0, null, null, result);

    assertFalse(summary.contains("Changed Files"));
  }

  @Test
  void shouldDropMalformedFileSummariesAndKeepFirstOnDuplicatePath() {
    var changedFiles =
        List.of(
            new PrSummaryGenerator.ChangedFile("src/A.java", "modified"),
            new PrSummaryGenerator.ChangedFile("src/B.java", "added"));
    // Malformed entries (null/blank path, null summary) are dropped; a duplicate path keeps the
    // first usable note rather than throwing from Collectors.toMap.
    var aiSummary =
        summaryWithFiles(
            new ReviewResponse.FileSummary(null, "no path"),
            new ReviewResponse.FileSummary("  ", "blank path"),
            new ReviewResponse.FileSummary("src/A.java", null),
            new ReviewResponse.FileSummary("src/A.java", "first wins"),
            new ReviewResponse.FileSummary("src/A.java", "second ignored"),
            new ReviewResponse.FileSummary("src/B.java", "b note"));
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(2, 1, 0, changedFiles, aiSummary, result);

    assertTrue(summary.contains("| `src/A.java` | Modified | first wins |"));
    assertFalse(summary.contains("second ignored"));
    assertTrue(summary.contains("| `src/B.java` | Added | b note |"));
  }

  private static ReviewResponse.Summary summaryWithFiles(ReviewResponse.FileSummary... files) {
    return new ReviewResponse.Summary(
        0, 0, 0, 0, 0, "ok", null, List.of(), List.of(), List.of(files), null);
  }

  @Test
  void shouldRenderWalkthroughDiagramAsCollapsibleMermaidBlock() {
    var aiSummary = summaryWithDiagram("flowchart TD\n  A[Start] --> B[End]");
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertTrue(summary.contains("### Control-Flow Diagram"));
    assertTrue(summary.contains("<details>"));
    assertTrue(summary.contains("```mermaid\nflowchart TD"));
    assertTrue(summary.contains("A[Start] --> B[End]"));
    assertTrue(summary.contains("</details>"));
  }

  @Test
  void shouldOmitDiagramSectionWhenBlankOrNull() {
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    for (var aiSummary : List.of(summaryWithDiagram(null), summaryWithDiagram("   "))) {
      var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);
      assertFalse(summary.contains("Control-Flow Diagram"));
      assertFalse(summary.contains("```mermaid"));
    }
  }

  @Test
  void shouldStripBacktickFencesSoTheDiagramCannotBreakOut() {
    // A model that ignores the "no fences" rule and wraps the source must not escape our block.
    var aiSummary = summaryWithDiagram("```mermaid\nflowchart TD\n  A --> B\n```");
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    // Exactly one opening and one closing fence — the wrapper backticks were stripped.
    assertEquals(1, countOccurrences(summary, "```mermaid"));
    assertEquals(2, countOccurrences(summary, "```"));
    assertTrue(summary.contains("flowchart TD"));
  }

  @Test
  void shouldDropUnrecognizedDiagramSource() {
    // Prose that is not a Mermaid diagram would render as a broken block, so it is dropped.
    var aiSummary = summaryWithDiagram("This change refactors the parser and adds a cache.");
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertFalse(summary.contains("Control-Flow Diagram"));
  }

  @Test
  void shouldDropOversizedDiagram() {
    var huge = "flowchart TD\n" + "  A --> B\n".repeat(PrSummaryGenerator.MAX_DIAGRAM_CHARS);
    var aiSummary = summaryWithDiagram(huge);
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertFalse(summary.contains("Control-Flow Diagram"));
  }

  private static ReviewResponse.Summary summaryWithDiagram(String diagram) {
    return new ReviewResponse.Summary(
        0, 0, 0, 0, 0, "ok", null, List.of(), List.of(), List.of(), diagram);
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    for (int i = haystack.indexOf(needle);
        i >= 0;
        i = haystack.indexOf(needle, i + needle.length())) {
      count++;
    }
    return count;
  }
}
