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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrSummaryGeneratorTest {

  private final PrSummaryGenerator generator = new PrSummaryGenerator(true);

  @Test
  void injectConstructorReadsDiagramEnabledAndLargePrNudgeFromConfig() {
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    var diagram = mock(ThrillhouseConfig.DiagramConfig.class);
    var nudge = mock(ThrillhouseConfig.LargePrNudgeConfig.class);
    when(config.review()).thenReturn(review);
    when(review.diagram()).thenReturn(diagram);
    when(diagram.enabled()).thenReturn(true);
    when(review.largePrNudge()).thenReturn(nudge);
    when(nudge.enabled()).thenReturn(true);
    when(nudge.minFiles()).thenReturn(20);
    when(nudge.minChangedLines()).thenReturn(1000);

    var fromConfig = new PrSummaryGenerator(config);
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary =
        fromConfig.generate(
            1, 5, 0, List.of(), summaryWithDiagram("flowchart TD\n  A --> B"), result);

    assertTrue(summary.contains("### Control-Flow Diagram"));
    // The same config wiring carries the nudge policy; this 1-file PR is under both bounds.
    assertFalse(summary.contains(LargePrNudge.NUDGE_HEADING), summary);
    assertTrue(
        fromConfig
            .generate(42, 3102, 876, List.of(), null, result)
            .contains(LargePrNudge.NUDGE_HEADING));
  }

  @Test
  void shouldGenerateSummaryWithFindings() {
    var findings =
        List.of(
            new Finding(RiskLevel.HIGH, "src/A.java", 1, "Bug", "desc", null, null),
            new Finding(RiskLevel.MEDIUM, "src/B.java", 2, "Smell", "desc", null, null));

    var result =
        new ReviewResult(
            findings,
            0,
            1,
            1,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0);

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
  void shouldRouteLowConfidenceFindingsToDoubleCheckSectionNotKeyFindings() {
    var inline =
        new Finding(
            RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 1, "Real bug", "desc", null, null);
    var lowConfidence =
        new Finding(
            RiskLevel.MEDIUM, Confidence.LOW, "src/B.java", 22, "Possible NPE", "desc", null, null);
    var result =
        new ReviewResult(
            List.of(inline, lowConfidence),
            0,
            1,
            1,
            0,
            RiskLevel.HIGH,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary = generator.generate(2, 10, 2, List.of(), null, result);

    assertTrue(summary.contains("### Key Findings"));
    assertTrue(summary.contains("Real bug"));
    assertTrue(summary.contains("### Things to double-check"));
    assertTrue(summary.contains("1 lower-confidence finding"));
    assertTrue(summary.contains("Possible NPE"));
    assertTrue(summary.contains("`src/B.java:22`"));
    assertTrue(summary.contains("_(low confidence — verify before acting)_"));
    // Low-confidence item must not also appear under Key Findings.
    int keyIdx = summary.indexOf("### Key Findings");
    int doubleCheckIdx = summary.indexOf("### Things to double-check");
    assertTrue(keyIdx >= 0 && doubleCheckIdx > keyIdx);
    assertFalse(summary.substring(keyIdx, doubleCheckIdx).contains("Possible NPE"));
  }

  @Test
  void doubleCheckBulletThatRestatesAnInlineFindingIsCrossReferenced() {
    // ThrillhouseBot-test #39: the pagination defect was filed twice — once inline, once as a
    // lower-confidence item — and the summary presented the pair as two independent defects.
    var inline =
        new Finding(
            RiskLevel.HIGH,
            Confidence.HIGH,
            "collector/usage.py",
            88,
            "list_usage stops after the first page of usage records",
            "The loop sends one request and returns its items; next_page_token is never followed.",
            null,
            null);
    var lowConfidence =
        new Finding(
            RiskLevel.MEDIUM,
            Confidence.LOW,
            "collector/usage.py",
            91,
            "Only the first page of usage records reaches the aggregator",
            "desc",
            null,
            null);
    var result =
        new ReviewResult(
            List.of(inline, lowConfidence),
            0,
            1,
            1,
            0,
            RiskLevel.HIGH,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary = generator.generate(1, 30, 4, List.of(), null, result);

    // The bullet keeps its claim and its own locator — nothing is deleted...
    assertTrue(
        summary.contains("Only the first page of usage records reaches the aggregator"), summary);
    assertTrue(summary.contains("`collector/usage.py:91`"), summary);
    // ...but it now names the inline finding it repeats, so it no longer reads as a second defect.
    assertTrue(
        summary.contains("_Same issue as the inline finding on `collector/usage.py:88`._"),
        summary);
  }

  @Test
  void doubleCheckBulletStatingSomethingElseIsNotCrossReferenced() {
    var inline =
        new Finding(
            RiskLevel.HIGH,
            Confidence.HIGH,
            "collector/usage.py",
            88,
            "list_usage stops after the first page of usage records",
            "The loop sends one request and returns its items; next_page_token is never followed.",
            null,
            null);
    var unrelated =
        new Finding(
            RiskLevel.LOW,
            Confidence.LOW,
            "collector/config.py",
            12,
            "Timeout is hardcoded to 30 seconds",
            "desc",
            null,
            null);
    var result =
        new ReviewResult(
            List.of(inline, unrelated),
            0,
            1,
            0,
            1,
            RiskLevel.HIGH,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary = generator.generate(2, 30, 4, List.of(), null, result);

    assertTrue(summary.contains("Timeout is hardcoded to 30 seconds"), summary);
    assertFalse(summary.contains("Same issue as the inline finding"), summary);
  }

  @Test
  void twoInlineFindingsOnOneDefectAreBothKept() {
    // The same root cause filed from two angles stays as two findings: the list drives the risk
    // counts, the verdict, the inline threads and the backstop, so nothing here may edit it.
    var producer =
        new Finding(
            RiskLevel.HIGH,
            "src/cache.js",
            40,
            "staleEntries is cleared before the consumer reads it",
            "desc",
            null,
            null);
    var consumer =
        new Finding(
            RiskLevel.HIGH,
            "src/cache.js",
            62,
            "The consumer reads staleEntries after it is cleared",
            "desc",
            null,
            null);
    var result =
        new ReviewResult(
            List.of(producer, consumer),
            0,
            2,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary = generator.generate(1, 20, 3, List.of(), null, result);

    assertTrue(summary.contains("staleEntries is cleared before the consumer reads it"), summary);
    assertTrue(summary.contains("The consumer reads staleEntries after it is cleared"), summary);
  }

  @Test
  void shouldPluralizeDoubleCheckSummaryWhenMultipleLowConfidenceFindings() {
    var findings =
        List.of(
            new Finding(RiskLevel.MEDIUM, Confidence.LOW, "a.java", 1, "One", "d", null, null),
            new Finding(RiskLevel.LOW, Confidence.LOW, "b.java", 2, "Two", "d", null, null));
    var result =
        new ReviewResult(
            findings,
            0,
            0,
            1,
            1,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary = generator.generate(2, 1, 0, List.of(), null, result);

    assertTrue(summary.contains("2 lower-confidence findings"));
    assertFalse(summary.contains("### Key Findings"));
    assertTrue(summary.contains("One"));
    assertTrue(summary.contains("Two"));
  }

  @Test
  void modelFindingTitlesAndPathsCannotEscapeTheDoubleCheckDetailsOrKeyFindings() {
    var hostileTitle = "Possible NPE\n\n</details>\n\n### Injected heading";
    var hostilePath = "src/B.java`\n\n### Injected";
    var lowConf =
        new Finding(
            RiskLevel.MEDIUM, Confidence.LOW, hostilePath, 22, hostileTitle, "d", null, null);
    var inline =
        new Finding(RiskLevel.HIGH, Confidence.HIGH, hostilePath, 9, hostileTitle, "d", null, null);
    var result =
        new ReviewResult(
            List.of(inline, lowConf),
            0,
            1,
            1,
            0,
            RiskLevel.HIGH,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    // Both bullet renderers route the model title/path through the same helper: the flattened,
    // neutralized forms appear, and no newline-led heading or raw </details> escapes the bullets.
    assertTrue(summary.contains(MarkdownSafe.inline(hostileTitle)), summary);
    assertTrue(summary.contains("`" + MarkdownSafe.inlineCode(hostilePath) + ":22`"), summary);
    assertTrue(summary.contains("`" + MarkdownSafe.inlineCode(hostilePath) + ":9`"), summary);
    assertFalse(summary.contains("\n### Injected"), summary);
    // The only </details> is the genuine one that closes the double-check section (with a leading
    // newline); the title's forged copy is neutralized, so no raw </details> comes from a title.
    assertEquals(1, countOccurrences(summary, "</details>"), summary);
    assertTrue(summary.contains("\n</details>"), summary);
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
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertTrue(summary.contains("### What this PR does"));
    assertTrue(summary.contains("Adds a user update endpoint with validation."));
    assertTrue(summary.contains("### ⚠️ Description vs. Implementation"));
    assertTrue(summary.contains("- Description claims tests were added"));
    assertEquals(
        1, summary.lines().filter(l -> l.startsWith("- ") && !l.startsWith("- **")).count());
  }

  @Test
  void shouldNeutralizeHostilePrPurpose() {
    var hostilePurpose = "Legit purpose.\n### Injected heading\n</details>\n```\n| pipe";
    var aiSummary = new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", hostilePurpose, List.of());
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    // The purpose paragraph routes through the same helper as every other model field: it is
    // flattened and neutralized, so no heading, </details>, fence, or pipe escapes the section.
    assertTrue(summary.contains(MarkdownSafe.inline(hostilePurpose)), summary);
    assertFalse(summary.contains("\n### Injected"), summary);
    assertFalse(summary.contains("</details>"), summary);
    assertFalse(summary.contains("```"), summary);
  }

  @Test
  void shouldOmitPurposeSectionWhenAbsentAndStillDiscloseTheGapCheck() {
    var blankSummary = new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", " ", List.of());
    var nullPurposeSummary = new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", null, null);
    var blankGapsSummary = new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", null, List.of(" ", ""));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    // A summary came back and reported nothing to flag: the section says so rather than vanishing.
    for (var summary :
        List.of(
            generator.generate(1, 0, 0, List.of(), blankSummary, result),
            generator.generate(1, 0, 0, List.of(), nullPurposeSummary, result),
            generator.generate(1, 0, 0, List.of(), blankGapsSummary, result))) {
      assertFalse(summary.contains("What this PR does"), summary);
      assertTrue(
          summary.contains("No mismatch found between the PR description and the change."),
          summary);
    }
  }

  @Test
  void shouldOmitTheGapSectionEntirelyWhenNoSummaryCameBack() {
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    // No summary at all is the one absence that is honest: nothing compared the description to the
    // diff, and the summary-degradation banners already say why.
    var summary = generator.generate(1, 0, 0, List.of(), null, result);

    assertFalse(summary.contains("What this PR does"), summary);
    assertFalse(summary.contains("Description vs. Implementation"), summary);
  }

  @Test
  void shouldNotRenderSignedZeroLineCounts() {
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(3, 169, 0, List.of(), null, result);

    assertTrue(summary.contains("**Lines added:** +169"));
    assertTrue(summary.contains("**Lines removed:** 0"));
    assertFalse(summary.contains("-0"));
    assertTrue(generator.generate(3, 0, 7, List.of(), null, result).contains("**Lines added:** 0"));
  }

  @Test
  void shouldNotIncludeDashboardLink() {
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    assertFalse(generator.generate(1, 0, 0, List.of(), null, result).contains("View in dashboard"));
  }

  @Test
  void shouldGenerateSummaryWithZeroFindings() {
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertTrue(summary.contains("Critical | 0"));
    assertTrue(summary.contains("High | 0"));
  }

  @Test
  void cleanReviewCelebratesInsideTheSummary() {
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertTrue(summary.contains("Everything's coming up Thrillhouse"));
    assertTrue(summary.contains("No issues found in this PR."));
  }

  @Test
  void truncatedCleanReviewReportsPartialReviewInsteadOfCelebrating() {
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), List.of(), 2);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertFalse(summary.contains("Everything's coming up Thrillhouse"));
    assertTrue(summary.contains("partial review"));
  }

  @Test
  void unresolvedStatusCasingDoesNotEnableCelebration() {
    var statuses = List.of(new ReviewResult.PreviousFindingStatus(1, "UNRESOLVED", "still"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses, List.of(), 0);

    assertFalse(
        generator.generate(1, 10, 2, List.of(), null, result).contains("coming up Thrillhouse"));
  }

  @Test
  void cleanReviewWithUnresolvedPreviousFindingsDoesNotCelebrate() {
    var statuses = List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "Still there"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses, List.of(), 0);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertFalse(summary.contains("Everything's coming up Thrillhouse"));
  }

  @Test
  void reviewWithFindingsDoesNotCelebrate() {
    var findings = List.of(new Finding(RiskLevel.LOW, "src/A.java", 1, "Nit", "d", null, null));
    var result =
        new ReviewResult(
            findings,
            0,
            0,
            0,
            1,
            RiskLevel.LOW,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(),
            0);

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
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses, List.of(), 0);

    var summary = generator.generate(1, 0, 0, List.of(), null, result);

    assertTrue(summary.contains("Previous Findings Status"));
    assertTrue(summary.contains("✅ Resolved"));
    assertTrue(summary.contains("⚠️ Still present"));
  }

  @Test
  void shouldShowSupersededRowOnlyWhenAFindingWasSuperseded() {
    var statuses =
        List.of(
            new ReviewResult.PreviousFindingStatus(1, "resolved", "Fixed"),
            new ReviewResult.PreviousFindingStatus(2, "Superseded", "Code left the diff"));

    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses, List.of(), 0);

    var summary = generator.generate(1, 0, 0, List.of(), null, result);

    assertTrue(summary.contains("🗂️ Superseded (targeted code left the diff) | 1"), summary);

    var withoutSuperseded =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.COMMENT,
            false,
            "",
            List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "Fixed")),
            List.of(),
            0);

    assertFalse(
        generator.generate(1, 0, 0, List.of(), null, withoutSuperseded).contains("Superseded"));
  }

  @Test
  void shouldCountPreviousFindingsStatusCaseInsensitively() {
    var statuses =
        List.of(
            new ReviewResult.PreviousFindingStatus(1, "Resolved", "Fixed"),
            new ReviewResult.PreviousFindingStatus(2, "UNRESOLVED", "Still broken"),
            new ReviewResult.PreviousFindingStatus(3, "Justified", "Intentional"));

    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", statuses, List.of(), 0);

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
            List.of(),
            List.of(),
            0);

    var summary = generator.generate(6, 0, 0, List.of(), null, result);

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
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), checks, 0);

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
  void showsNeutralCiChecksStatusWhenRequiredSetUnknown() {
    var checks =
        List.of(
            new ReviewResult.CiCheck("build", "check-run", "failing", null),
            new ReviewResult.CiCheck("test", "status", "pending", "pending"));
    var result =
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
            checks,
            0,
            false,
            false,
            ReviewResult.TruncationDetail.EMPTY);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertFalse(summary.contains("Everything's coming up Thrillhouse"));
    assertTrue(summary.contains("until CI is confirmed green"), summary);
    assertFalse(summary.contains("required CI is confirmed green"), summary);
    assertTrue(summary.contains("### ⚠️ CI Checks Status"), summary);
    assertFalse(summary.contains("Required CI Checks Status"), summary);
    assertTrue(summary.contains("Some checks are still pending or have failed"), summary);
    assertFalse(summary.contains("Some required checks are still pending or have failed"), summary);
  }

  @Test
  void cleanReviewHeldByBothCiAndTruncationReportsBoth() {
    var checks = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", null));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), checks, 2);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertFalse(summary.contains("Everything's coming up Thrillhouse"));
    assertTrue(summary.contains("required CI is not confirmed green"), summary);
    assertTrue(summary.contains("too large to review in full"), summary);
    assertTrue(summary.contains("partial review"), summary);
  }

  @Test
  void shouldShowRequiredCiChecksStatusWhenFindingsExist() {
    var findings = List.of(new Finding(RiskLevel.LOW, "src/A.java", 1, "Nit", "d", null, null));
    var checks = List.of(new ReviewResult.CiCheck("lint", "status", "failing", "failure"));
    var result =
        new ReviewResult(
            findings,
            0,
            0,
            0,
            1,
            RiskLevel.LOW,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            checks,
            0);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertFalse(summary.contains("Everything's coming up Thrillhouse"));
    assertTrue(summary.contains("Key Findings"));
    assertTrue(summary.contains("Required CI Checks Status"));
    assertTrue(summary.contains("❌ Failed"));
    assertTrue(summary.contains("**lint**"));
  }

  @Test
  void shouldEscapePipesInCiCheckTableCells() {
    var checks =
        List.of(new ReviewResult.CiCheck("build | strict", "check-run", "failing", "failure"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), checks, 0);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

    assertTrue(summary.contains("build \\| strict"));
    assertFalse(summary.contains("build | strict"));
  }

  @Test
  void shouldRenderDashForNullCheckNameInCiTable() {
    var checks = List.of(new ReviewResult.CiCheck(null, "missing", "pending", null));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), checks, 0);

    var summary = generator.generate(1, 10, 2, List.of(), null, result);

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
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(2, 10, 1, changedFiles, aiSummary, result);

    assertTrue(summary.contains("### Changed Files"));
    assertTrue(summary.contains("| File | Change | Summary |"));
    assertTrue(summary.contains("| `src/A.java` | Modified | Adds null guard to parse() |"));
    assertTrue(summary.contains("| `src/B.java` | Added | New cache wrapper |"));
  }

  /**
   * #547 — a row the model returned no note for used to render a bare "-", which reads as a
   * rendering bug rather than the honest "nothing came back for this file". It states the reason
   * now, exactly as the pure-rename row does.
   */
  @Test
  void shouldExplainTheMissingSummaryWhenFileHasNoMatchingSummary() {
    var changedFiles = List.of(new PrSummaryGenerator.ChangedFile("src/A.java", "modified"));
    var aiSummary = summaryWithFiles(new ReviewResponse.FileSummary("src/Other.java", "unrelated"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 1, 0, changedFiles, aiSummary, result);

    assertTrue(
        summary.contains(
            "| `src/A.java` | Modified | " + PrSummaryGenerator.NO_MODEL_SUMMARY + " |"),
        summary);
    assertFalse(summary.contains("| `src/A.java` | Modified | - |"), summary);
  }

  /**
   * #547 — the walkthrough is rendered from the diff, not from the model's reply, so a summary call
   * that returned no summary object at all still produces rows. Every one of them must say why it
   * is empty rather than rendering a wall of dashes.
   */
  @Test
  void shouldExplainEveryRowWhenNoSummaryObjectCameBackAtAll() {
    var changedFiles =
        List.of(
            new PrSummaryGenerator.ChangedFile("src/A.java", "modified"),
            new PrSummaryGenerator.ChangedFile("src/B.java", "added"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(2, 1, 0, changedFiles, null, result);

    assertTrue(
        summary.contains(
            "| `src/A.java` | Modified | " + PrSummaryGenerator.NO_MODEL_SUMMARY + " |"),
        summary);
    assertTrue(
        summary.contains("| `src/B.java` | Added | " + PrSummaryGenerator.NO_MODEL_SUMMARY + " |"),
        summary);
    assertFalse(summary.contains(" | - |"), summary);
  }

  @Test
  void shouldExplainPureRenameRowInsteadOfRenderingBareDash() {
    var changedFiles =
        List.of(
            new PrSummaryGenerator.ChangedFile("src/Moved.java", "renamed", true),
            new PrSummaryGenerator.ChangedFile("src/Edited.java", "renamed", false));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(2, 0, 0, changedFiles, summaryWithFiles(), result);

    // A pure rename is excluded from AI review by design, so it never carries a model summary:
    // say so rather than rendering the "-" that reads as a missing summary (#536).
    assertTrue(
        summary.contains(
            "| `src/Moved.java` | Renamed | " + PrSummaryGenerator.PURE_RENAME_SUMMARY + " |"),
        summary);
    // A rename that also changed content WAS reviewed, so its absent summary carries the other
    // reason — the model simply returned no note — not the pure-rename one (#547).
    assertTrue(
        summary.contains(
            "| `src/Edited.java` | Renamed | " + PrSummaryGenerator.NO_MODEL_SUMMARY + " |"),
        summary);
  }

  @Test
  void shouldPreferModelSummaryOverThePureRenameExplanation() {
    var changedFiles =
        List.of(new PrSummaryGenerator.ChangedFile("src/Moved.java", "renamed", true));
    var aiSummary =
        summaryWithFiles(new ReviewResponse.FileSummary("src/Moved.java", "Moved to the new pkg"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 0, 0, changedFiles, aiSummary, result);

    assertTrue(summary.contains("| `src/Moved.java` | Renamed | Moved to the new pkg |"), summary);
    assertFalse(summary.contains(PrSummaryGenerator.PURE_RENAME_SUMMARY), summary);
  }

  @Test
  void shouldOmitChangedFilesSectionWhenNoFiles() {
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

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
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(total, 0, 0, changedFiles, null, result);

    assertTrue(summary.contains("`src/F0.java`"));
    assertTrue(summary.contains("`src/F" + (PrSummaryGenerator.MAX_FILE_ROWS - 1) + ".java`"));
    assertFalse(summary.contains("`src/F" + PrSummaryGenerator.MAX_FILE_ROWS + ".java`"));
    assertTrue(summary.contains("…and 3 more file(s)."));
  }

  @Test
  void changesOverviewUsesAuthoritativeTotalsWhenReviewableCountsDiverge() {
    var reviewable = new java.util.ArrayList<PrSummaryGenerator.ChangedFile>();
    for (int i = 0; i < 26; i++) {
      reviewable.add(new PrSummaryGenerator.ChangedFile("src/F" + i + ".java", "modified"));
    }
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(27, 975, 196, reviewable, null, result);

    assertTrue(summary.contains("**Files changed:** 27"), summary);
    assertTrue(summary.contains("**Lines added:** +975"), summary);
    assertTrue(summary.contains("**Lines removed:** -196"), summary);
    assertTrue(summary.contains("`src/F19.java`"));
    assertFalse(summary.contains("`src/F20.java`"));
    assertTrue(summary.contains("…and 7 more file(s)."), summary);
  }

  @Test
  void shouldEscapePipesInChangedFilesTable() {
    var changedFiles = List.of(new PrSummaryGenerator.ChangedFile("src/a|b.java", "modified"));
    var aiSummary =
        summaryWithFiles(new ReviewResponse.FileSummary("src/a|b.java", "handles a | b case"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 1, 0, changedFiles, aiSummary, result);

    assertTrue(summary.contains("src/a\\|b.java"));
    assertTrue(summary.contains("handles a \\| b case"));
  }

  @Test
  void shouldFoldNewlinesInChangedFilesTableCells() {
    var changedFiles = List.of(new PrSummaryGenerator.ChangedFile("src/A.java", "modified"));
    var aiSummary =
        summaryWithFiles(new ReviewResponse.FileSummary("src/A.java", "first line\nsecond line"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 1, 0, changedFiles, aiSummary, result);

    assertTrue(summary.contains("first line second line"));
    assertFalse(summary.contains("first line\nsecond line"));
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
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

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
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(0, 0, 0, null, null, result);

    assertFalse(summary.contains("Changed Files"));
  }

  @Test
  void shouldDropMalformedFileSummariesAndKeepFirstOnDuplicatePath() {
    var changedFiles =
        List.of(
            new PrSummaryGenerator.ChangedFile("src/A.java", "modified"),
            new PrSummaryGenerator.ChangedFile("src/B.java", "added"));
    var aiSummary =
        summaryWithFiles(
            new ReviewResponse.FileSummary(null, "no path"),
            new ReviewResponse.FileSummary("  ", "blank path"),
            new ReviewResponse.FileSummary("src/A.java", null),
            new ReviewResponse.FileSummary("src/A.java", "first wins"),
            new ReviewResponse.FileSummary("src/A.java", "second ignored"),
            new ReviewResponse.FileSummary("src/B.java", "b note"));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

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
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertTrue(summary.contains("### Control-Flow Diagram"));
    assertTrue(summary.contains("<details>"));
    assertTrue(summary.contains("```mermaid\nflowchart TD"));
    assertTrue(summary.contains("A[Start] --> B[End]"));
    assertTrue(summary.contains("</details>"));
  }

  @Test
  void shouldRenderCiUnavailableNoteWhenCiUnreadableInsteadOfCelebrating() {
    var result =
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
            List.of(),
            0,
            true);

    var summary = generator.generate(1, 5, 0, List.of(), null, result);

    assertTrue(summary.contains("CI Status Unavailable"));
    assertTrue(summary.contains("could not be read"));
    assertFalse(summary.contains(PrSummaryGenerator.ZERO_ISSUES_MESSAGE));
    assertTrue(
        summary.contains(
            "the CI status could not be read, so the review cannot be approved until it can be"
                + " confirmed"),
        summary);
    assertFalse(summary.contains("confirmed green"), summary);
  }

  @Test
  void approvedDespiteUnreadableCiNotesSoftGatingInSummary() {
    var result =
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
            true);

    var summary = generator.generate(1, 5, 0, List.of(), null, result);

    assertTrue(summary.contains(PrSummaryGenerator.ZERO_ISSUES_MESSAGE), summary);
    assertTrue(summary.contains("CI Status Unavailable"), summary);
    assertTrue(summary.contains("gating is not strict"), summary);
    assertFalse(summary.contains("approval is held"), summary);
  }

  @Test
  void unreadableCiWithTruncationReportsBothWithoutClaimingNotGreen() {
    var result =
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
            List.of(),
            2,
            true);

    var summary = generator.generate(1, 5, 0, List.of(), null, result);

    assertTrue(
        summary.contains(
            "the CI status could not be read, and the diff was too large to review in full"),
        summary);
    assertFalse(summary.contains("confirmed green"), summary);
    assertTrue(summary.contains("partial review"), summary);
    assertTrue(summary.contains("CI Status Unavailable"), summary);
  }

  @Test
  void shouldNotRenderDiagramWhenFeatureDisabledEvenIfModelVolunteersOne() {
    var disabled = new PrSummaryGenerator(false);
    var aiSummary = summaryWithDiagram("flowchart TD\n  A[Start] --> B[End]");
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = disabled.generate(1, 5, 0, List.of(), aiSummary, result);

    assertFalse(summary.contains("Control-Flow Diagram"));
    assertFalse(summary.contains("```mermaid"));
  }

  @Test
  void shouldOmitDiagramSectionWhenBlankOrNull() {
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    for (var aiSummary : List.of(summaryWithDiagram(null), summaryWithDiagram("   "))) {
      var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);
      assertFalse(summary.contains("Control-Flow Diagram"));
      assertFalse(summary.contains("```mermaid"));
    }
  }

  @Test
  void shouldStripBacktickFencesSoTheDiagramCannotBreakOut() {
    var aiSummary = summaryWithDiagram("```mermaid\nflowchart TD\n  A --> B\n```");
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertEquals(1, countOccurrences(summary, "```mermaid"));
    assertEquals(2, countOccurrences(summary, "```"));
    assertTrue(summary.contains("flowchart TD"));
  }

  @Test
  void shouldDropUnrecognizedDiagramSource() {
    var aiSummary = summaryWithDiagram("This change refactors the parser and adds a cache.");
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertFalse(summary.contains("Control-Flow Diagram"));
  }

  @Test
  void shouldDropOversizedDiagram() {
    var huge = "flowchart TD\n" + "  A --> B\n".repeat(PrSummaryGenerator.MAX_DIAGRAM_CHARS);
    var aiSummary = summaryWithDiagram(huge);
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertFalse(summary.contains("Control-Flow Diagram"));
  }

  @Test
  void shouldDropDiagramThatIsOnlyAFenceTag() {
    var aiSummary = summaryWithDiagram("```mermaid```");
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertFalse(summary.contains("Control-Flow Diagram"));
  }

  @Test
  void shouldRenderSequenceDiagramWithAsParticipantSyntax() {
    var aiSummary =
        summaryWithDiagram(
            """
            sequenceDiagram
              participant O as ReviewOrchestrator
              participant L as ReviewContextLoader
              O->>L: load()
              L-->>O: ReviewContext
            """);
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertTrue(summary.contains("### Control-Flow Diagram"));
    assertTrue(summary.contains("```mermaid\nsequenceDiagram"));
    assertTrue(summary.contains("participant O as ReviewOrchestrator"));
  }

  @Test
  void shouldRenderSequenceDiagramWhenBracketsAppearOnlyInTheAsDisplayName() {
    var aiSummary =
        summaryWithDiagram(
            """
            sequenceDiagram
              participant A as [User]
              participant S as Server
              A->>S: request()
            """);
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertTrue(summary.contains("### Control-Flow Diagram"));
    assertTrue(summary.contains("participant A as [User]"));
  }

  @Test
  void shouldDropSequenceDiagramWithBracketLabeledParticipants() {
    var aiSummary =
        summaryWithDiagram(
            """
            sequenceDiagram
              participant O["ReviewOrchestrator"]
              participant L["ReviewContextLoader"]
              O->>L: "load()"
            """);
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertFalse(summary.contains("Control-Flow Diagram"));
    assertFalse(summary.contains("```mermaid"));
  }

  @Test
  void shouldDropSequenceDiagramWithBraceLabeledActor() {
    var aiSummary =
        summaryWithDiagram(
            "sequenceDiagram\n  actor U as User\n  actor B{\"Bot\"}\n  U->>B: ask()");
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertFalse(summary.contains("Control-Flow Diagram"));
    assertFalse(summary.contains("```mermaid"));
  }

  @Test
  void shouldKeepFlowchartWithBracketLabelsWhenRejectingSequenceParticipants() {
    var aiSummary = summaryWithDiagram("flowchart TD\n  A[\"call foo()\"] --> B{\"ready?\"}");
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 5, 0, List.of(), aiSummary, result);

    assertTrue(summary.contains("### Control-Flow Diagram"));
    assertTrue(summary.contains("A[\"call foo()\"] --> B{\"ready?\"}"));
  }

  @Test
  void shouldRenderLargePrNudgeAfterTheCelebrationWhenEnabledAndNothingPostedInline() {
    // The #343 fixture: a PR well over the file threshold whose review produced no findings at
    // all. The celebration still renders — the review really did find nothing — but the summary
    // now also says that on a change this size, that is worth checking by hand.
    var nudging = new PrSummaryGenerator(false, new LargePrNudge(true, 20, 1000));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = nudging.generate(42, 3102, 876, List.of(), null, result);

    assertTrue(summary.contains(PrSummaryGenerator.ZERO_ISSUES_MESSAGE), summary);
    assertTrue(summary.contains(LargePrNudge.NUDGE_HEADING), summary);
    assertTrue(
        summary.contains("no inline findings across 42 changed files (+3102 -876)"), summary);
    // Ordering: the caveat reads after the celebration it qualifies.
    assertTrue(
        summary.indexOf(LargePrNudge.NUDGE_HEADING)
            > summary.indexOf(PrSummaryGenerator.ZERO_ISSUES_MESSAGE),
        summary);
  }

  @Test
  void shouldNotRenderLargePrNudgeOnASmallCleanPr() {
    var nudging = new PrSummaryGenerator(false, new LargePrNudge(true, 20, 1000));
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = nudging.generate(3, 120, 45, List.of(), null, result);

    assertTrue(summary.contains(PrSummaryGenerator.ZERO_ISSUES_MESSAGE), summary);
    assertFalse(summary.contains(LargePrNudge.NUDGE_HEADING), summary);
  }

  @Test
  void shouldNotRenderLargePrNudgeWhenTheFeatureIsOff() {
    // The shipped default: the test-visible single-arg constructor leaves the nudge disabled, so
    // even a huge finding-free PR renders exactly the summary it renders today.
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(120, 20000, 9000, List.of(), null, result);

    assertFalse(summary.contains(LargePrNudge.NUDGE_HEADING), summary);
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

  // ---------------------------------------------------------------------------------------------
  // #588 — one observation, one surface. The corpus below is the verbatim text ThrillhouseBot
  // published on devops-thiago/ThrillhouseBot-test #23 (Java) and #22 (C), where the same claim was
  // asserted as an inline finding, again as a description-gap bullet, and again as its own clause
  // in a Changed Files walkthrough row.
  // ---------------------------------------------------------------------------------------------

  @Test
  void restatedDescriptionGapAndWalkthroughClauseCollapseToTheInlineFinding() {
    var findings =
        List.of(
            new Finding(
                RiskLevel.HIGH,
                "src/main/java/com/thrillhouse/scheduler/Main.java",
                22,
                "Main passes a hardcoded empty task list, so nothing is ever dispatched",
                "desc",
                null,
                null));
    var aiSummary =
        new ReviewResponse.Summary(
            1,
            0,
            1,
            0,
            0,
            "ok",
            null,
            List.of(
                "The runnable entry point passes a hardcoded empty task list to `dispatchDueTasks`,"
                    + " so the shipped service cannot actually dispatch any task.",
                "PR says TaskRunRepository persists run history, but the added class only contains"
                    + " searchRunsByTaskName; no insert/update/upsert write path is present in the"
                    + " diff."),
            List.of(),
            List.of(
                new ReviewResponse.FileSummary(
                    "src/main/java/com/thrillhouse/scheduler/Main.java",
                    "Added one-shot entry point; hardcodes an empty task list and reads only"
                        + " registry URL env var")),
            null);
    var result =
        new ReviewResult(
            findings,
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary =
        generator.generate(
            1,
            30,
            0,
            List.of(
                new PrSummaryGenerator.ChangedFile(
                    "src/main/java/com/thrillhouse/scheduler/Main.java", "added")),
            aiSummary,
            result);

    // The claim keeps its most specific surface: the inline finding.
    assertTrue(
        summary.contains(
            "**HIGH:** Main passes a hardcoded empty task list, so nothing is ever dispatched"),
        summary);
    // ...and is gone from the other two.
    assertFalse(summary.contains("the shipped service cannot actually dispatch any task"), summary);
    assertFalse(summary.contains("hardcodes an empty task list"), summary);
    // The walkthrough row survives, still summarising the file (the deliberate nuance in #588).
    assertTrue(
        summary.contains(
            "| `src/main/java/com/thrillhouse/scheduler/Main.java` | Added | Added one-shot entry"
                + " point |"),
        summary);
    // A gap that states something no finding states is untouched.
    assertTrue(summary.contains("no insert/update/upsert write path"), summary);
    assertTrue(summary.contains("### ⚠️ Description vs. Implementation"), summary);
  }

  @Test
  void restatedGapCollapsesEvenWhenTheFindingIsNotAKeyFinding() {
    // ThrillhouseBot-test #22: the dead-config claim was 8th by severity, so it never reached the
    // Key Findings list — the dedupe still has to see it as published.
    var findings = new java.util.ArrayList<Finding>();
    for (int i = 0; i < 5; i++) {
      findings.add(
          new Finding(
              RiskLevel.CRITICAL, "src/other" + i + ".c", i, "Unrelated " + i, "d", null, null));
    }
    findings.add(
        new Finding(
            RiskLevel.MEDIUM,
            "src/main.c",
            45,
            "LOGD_BAN_REFRESH_INTERVAL is dead config; banlist is never refreshed",
            "desc",
            null,
            null));
    var aiSummary =
        new ReviewResponse.Summary(
            6,
            5,
            0,
            1,
            0,
            "ok",
            null,
            List.of(
                "Periodic banned-IP refresh claimed in the PR is not implemented: banlist_fetch is"
                    + " called only once at startup (src/main.c:45) and LOGD_BAN_REFRESH_INTERVAL"
                    + " is read into cfg.ban_refresh_interval (src/config.c:46) but never used.",
                "Duplicate skipping is claimed to last 'within a connection's lifetime', but dedup"
                    + " state is process-global in rotator.c (already_seen/remember), so identical"
                    + " messages from different clients/connections are also dropped."),
            List.of(),
            List.of(),
            null);
    var result =
        new ReviewResult(
            findings,
            5,
            0,
            1,
            0,
            RiskLevel.CRITICAL,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary = generator.generate(2, 60, 0, List.of(), aiSummary, result);

    assertFalse(summary.contains("Periodic banned-IP refresh claimed in the PR"), summary);
    assertTrue(summary.contains("dedup state is process-global in rotator.c"), summary);
  }

  @Test
  void descriptionGapsSectionStatesTheCheckRanWhenEveryBulletRestatesAFinding() {
    var findings =
        List.of(
            new Finding(
                RiskLevel.HIGH,
                "src/rotator.c",
                45,
                "LOGD_MAX_FILE_SIZE never enforced; no rotation despite docs",
                "desc",
                null,
                null));
    var aiSummary =
        new ReviewResponse.Summary(
            1,
            0,
            1,
            0,
            0,
            "ok",
            null,
            List.of(
                "Per-source 'rotated' log files and LOGD_MAX_FILE_SIZE rollover claimed in the PR"
                    + " and docs: rotator_write always appends to <source>.log and never reads"
                    + " r->max_file_size (src/rotator.c:13,45); no rotation logic exists."),
            List.of(),
            List.of(),
            null);
    var result =
        new ReviewResult(
            findings,
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary = generator.generate(1, 10, 0, List.of(), aiSummary, result);

    // #588 still collapses the bullet onto the finding — the claim is not stated twice...
    assertFalse(summary.contains("no rotation logic exists"), summary);
    // ...but the section itself survives and says the check ran, so a review that compared the
    // description against the diff no longer reads exactly like one that skipped it (#637).
    assertTrue(summary.contains("### ⚠️ Description vs. Implementation"), summary);
    assertTrue(
        summary.contains(
            "Every mismatch found between the description and the change is reported as a finding"
                + " below, so it is not repeated here."),
        summary);
    assertFalse(summary.contains("No mismatch found between the PR description"), summary);
  }

  @Test
  void descriptionGapsSectionListsTheGapWhenEveryFindingItRestatesWasDemoted() {
    // #717: the model reported one description gap, #588 collapsed it onto the one finding that
    // states the same claim, and the verifier then demoted that finding into the collapsed
    // double-check block. The ⚠️ heading was left promising a mismatch "reported as a finding
    // below" while the only thing below was a hedged maybe behind a toggle (#718).
    var gap =
        "The PR description says the docs deploy now fails loudly instead of a silent miss, but"
            + " the publish-docs job exits 0 as soon as `gh workflow run` accepts the dispatch, so"
            + " a deploy-stage failure in the dispatched docs.yml run leaves release.yml green"
            + " with no signal.";
    var findings =
        List.of(
            new Finding(
                RiskLevel.MEDIUM,
                Confidence.LOW,
                ".github/workflows/release.yml",
                88,
                "publish-docs exits 0 once the dispatch is accepted, so a failure in the dispatched"
                    + " docs.yml run leaves release.yml green",
                "desc",
                null,
                null));
    var aiSummary =
        new ReviewResponse.Summary(
            1, 0, 0, 1, 0, "ok", null, List.of(gap), List.of(), List.of(), null);
    var result =
        new ReviewResult(
            findings,
            0,
            0,
            1,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary = generator.generate(1, 10, 0, List.of(), aiSummary, result);

    assertTrue(summary.contains("### ⚠️ Description vs. Implementation"), summary);
    // The unhedged statement of the mismatch reaches the reader instead of being deleted in
    // favour of the hedged double-check bullet, which is the weaker surface of the two.
    assertTrue(summary.contains(gap), summary);
    assertFalse(
        summary.contains(
            "Every mismatch found between the description and the change is reported as a finding"
                + " below, so it is not repeated here."),
        summary);
  }

  @Test
  void descriptionGapsSectionStatesTheCheckFoundNothingWhenTheModelReportedNoGap() {
    var aiSummary =
        new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", "Adds a cache wrapper.", List.of());
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    var summary = generator.generate(1, 10, 0, List.of(), aiSummary, result);

    // A clean result is not a warning, so the heading drops the ⚠️ the mismatch states carry.
    assertTrue(summary.contains("### Description vs. Implementation"), summary);
    assertFalse(summary.contains("### ⚠️ Description vs. Implementation"), summary);
    assertTrue(
        summary.contains("No mismatch found between the PR description and the change."), summary);
  }

  @Test
  void walkthroughRowThatOnlySummarisesItsFileIsNeverTrimmed() {
    var findings =
        List.of(
            new Finding(
                RiskLevel.CRITICAL,
                "src/rotator.c",
                43,
                "Path traversal: unvalidated client source reaches fopen path",
                "desc",
                null,
                null));
    var aiSummary =
        summaryWithFiles(
            new ReviewResponse.FileSummary(
                "src/rotator.c",
                "Dedup + append to <source>.log using an unvalidated client source path"),
            new ReviewResponse.FileSummary("src/banlist.h", "Declares banlist API"));
    var result =
        new ReviewResult(
            findings,
            1,
            0,
            0,
            0,
            RiskLevel.CRITICAL,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0);

    var summary =
        generator.generate(
            2,
            20,
            0,
            List.of(
                new PrSummaryGenerator.ChangedFile("src/rotator.c", "added"),
                new PrSummaryGenerator.ChangedFile("src/banlist.h", "added")),
            aiSummary,
            result);

    // A single-clause row is the file's summary, not a re-assertion — it stays verbatim even
    // though it overlaps the inline finding heavily.
    assertTrue(
        summary.contains(
            "| `src/rotator.c` | Added | Dedup + append to <source>.log using an unvalidated"
                + " client source path |"),
        summary);
    assertTrue(summary.contains("| `src/banlist.h` | Added | Declares banlist API |"), summary);
  }
}
