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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SummaryRibbonTest {

  private final PrSummaryGenerator generator = new PrSummaryGenerator(false);

  @Test
  void leadsTheSummaryWithTheVerdictTheCountsAndTheFacts() {
    var files = List.of(new PrSummaryGenerator.ChangedFile("src/test/java/ATest.java", "added"));
    var round = round(ReviewState.REQUEST_CHANGES, false, RiskLevel.HIGH, RiskLevel.HIGH);

    var summary = generator.generate(1, 5, 0, files, null, round);

    assertTrue(
        summary.startsWith(
            PrSummaryGenerator.SUMMARY_HEADING
                + "\n\n**Request changes** · 2 high\n"
                + "🔴 Critical 0 · 🟠 High 2 · 🟡 Medium 0 · 🔵 Low 0\n"
                + "`Tests touched` `Follow-up`\n\n"
                + SummaryRibbon.END),
        summary);
    assertFalse(summary.contains("Impact:"), summary);
  }

  @ParameterizedTest
  @CsvSource({
    "APPROVE, , **Approve**",
    "APPROVE, LOW, **Comment**",
    "COMMENT, MEDIUM, **Comment**",
    "REQUEST_CHANGES, CRITICAL, **Request changes** · 1 critical"
  })
  void statesWhatTheReviewPosts(ReviewState state, RiskLevel risk, String expected) {
    var round = risk == null ? round(state, true) : round(state, true, risk);

    assertEquals(expected, SummaryRibbon.verdict(round, round, 0));
  }

  @Test
  void countsWhatIsStillOpenFromEarlierRounds() {
    var round = round(ReviewState.COMMENT, false);

    assertEquals(
        "**Comment** · 2 open from an earlier review", SummaryRibbon.verdict(round, round, 2));
  }

  @ParameterizedTest
  @CsvSource({
    "CRITICAL, Impact: **critical** (1 critical finding open)",
    "MEDIUM, Impact: **medium** (1 medium finding open)",
    "LOW, Impact: **low** (1 low finding open)"
  })
  void namesTheHighestSeverityOpenWhenAskedTo(RiskLevel risk, String expected) {
    var withImpact =
        new PrSummaryGenerator(false, LargePrNudge.DISABLED, new SummaryRibbon(true, true));

    var summary =
        withImpact.generate(1, 5, 0, List.of(), null, round(ReviewState.COMMENT, true, risk));

    assertTrue(summary.contains(expected), summary);
  }

  @Test
  void countsEveryFindingAtTheHighestSeverityOpen() {
    var withImpact =
        new PrSummaryGenerator(false, LargePrNudge.DISABLED, new SummaryRibbon(true, true));
    var round = round(ReviewState.COMMENT, true, RiskLevel.MEDIUM, RiskLevel.MEDIUM, RiskLevel.LOW);

    var summary = withImpact.generate(1, 5, 0, List.of(), null, round);

    assertTrue(summary.contains("Impact: **medium** (2 medium findings open)"), summary);
  }

  @Test
  void countsDoubleCheckDescriptionGapAndScanFindings() {
    var doubtful =
        new Finding(RiskLevel.LOW, Confidence.LOW, "src/A.java", 3, "Maybe", "desc", null, null);
    var scanned =
        new Finding(
            RiskLevel.HIGH,
            "src/B.java",
            7,
            SecurityRule.PRIVILEGED_CONTAINER.iacTitle(),
            "desc\n\n" + SecurityScan.PROVENANCE,
            null,
            null);
    var round =
        new ReviewResult(
            List.of(doubtful, scanned),
            0,
            1,
            0,
            1,
            null,
            ReviewState.REQUEST_CHANGES,
            true,
            "",
            List.of(),
            List.of(),
            0);
    var aiSummary =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            null,
            null,
            List.of("The description promises a retry that the change does not add."),
            List.of(),
            List.of(),
            null);

    var summary = generator.generate(2, 5, 0, List.of(), aiSummary, round);

    assertTrue(
        summary.contains("🔵 Low 1 · Double-check 1 · Description gaps 1 · Scan findings 1\n"),
        summary);
  }

  @Test
  void namesACiHoldOnlyWhenCiIsWhatHolds() {
    var check = new ReviewResult.CiCheck("build", "check-run", "pending", null);
    var finding = new Finding(RiskLevel.LOW, "src/A.java", 1, "Nit", "desc", null, null);
    var round =
        new ReviewResult(
            List.of(finding),
            0,
            0,
            0,
            1,
            null,
            ReviewState.COMMENT,
            true,
            "",
            List.of(),
            List.of(check),
            0);

    assertEquals("**Comment**", SummaryRibbon.verdict(round, round, 0));
  }

  @Test
  void endsTheFirstSentenceAtPunctuationFollowedByASpace() {
    var summary = withPurpose("Reads config.yaml v2.1 once! It also drops a retry.");

    assertTrue(summary.contains("> Reads config.yaml v2.1 once!\n"), summary);
  }

  @Test
  void doesNotEndTheFirstSentenceAtAnAbbreviation() {
    var first = "Adds a fallback, e.g. a retry, i.e. one more try vs. none, cf. the docs.";

    var summary = withPurpose(first + " Also drops a log.");

    assertTrue(summary.contains("> " + first + "\n"), summary);
  }

  @Test
  void cutsALongFirstSentenceWithNoSpaceAtTheLimit() {
    var summary = withPurpose("a".repeat(200) + ".");

    assertTrue(summary.contains("> " + "a".repeat(160) + "…\n"), summary);
  }

  @Test
  void quotesOnlyTheFirstSentenceOfALongerPurpose() {
    var summary = withPurpose("Caches the token lookup. It also drops a retry.");
    var single = withPurpose("Caches the token lookup.");

    assertTrue(summary.contains("> Caches the token lookup.\n\n" + SummaryRibbon.END), summary);
    assertFalse(single.contains("\n> "), single);
  }

  @Test
  void cutsAFirstSentenceThatRunsLong() {
    var summary = withPurpose("word ".repeat(60).strip() + ".");

    var quote = summary.lines().filter(l -> l.startsWith("> ")).findFirst().orElseThrow();
    assertTrue(quote.endsWith("word…"), quote);
    assertTrue(quote.length() <= 163, quote);
  }

  @Test
  void marksALargeChangeAndAFirstReview() {
    var large =
        new PrSummaryGenerator(
            false, new LargePrNudge(false, 20, 0), new SummaryRibbon(true, false));
    var files = List.of(new PrSummaryGenerator.ChangedFile("src/A.java", "added"));

    var summary = large.generate(20, 5, 0, files, null, round(ReviewState.APPROVE, true));

    assertTrue(summary.contains("`First review` `Large PR`"), summary);
    assertFalse(summary.contains("Tests touched"), summary);
  }

  @Test
  void rewritesTheVerdictWhenAHeldApprovalIsReleased() {
    var check = new ReviewResult.CiCheck("build", "check-run", "pending", null);
    var held =
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
            List.of(check),
            0);

    var summary = generator.generate(1, 5, 0, List.of(), null, held);
    var released = CiSummarySections.refresh(summary, round(ReviewState.APPROVE, true));

    assertTrue(summary.contains("\n**Comment** · CI hold\n"), summary);
    assertTrue(released.contains("\n**Approve**\n"), released);
    assertFalse(released.contains("CI hold\n"), released);
  }

  @Test
  void putsASectionAddedUnderTheTitleAfterTheRibbon() {
    var summary = generator.generate(1, 5, 0, List.of(), null, round(ReviewState.APPROVE, true));

    var embedded = AgentNextSteps.embedAfterHeading(summary, "### Added");

    assertTrue(embedded.contains(SummaryRibbon.END + "\n\n### Added\n\n"), embedded);
  }

  @Test
  void leavesTheSummaryAsItWasWhenOff() {
    var off = new PrSummaryGenerator(false, LargePrNudge.DISABLED, new SummaryRibbon(false, true));

    var summary = off.generate(1, 5, 0, List.of(), null, round(ReviewState.APPROVE, true));

    assertFalse(summary.contains(SummaryRibbon.END), summary);
    assertFalse(summary.contains("Impact:"), summary);
    assertTrue(summary.startsWith(PrSummaryGenerator.SUMMARY_HEADING + "\n\n###"), summary);
  }

  private String withPurpose(String purpose) {
    var aiSummary =
        new ReviewResponse.Summary(
            0, 0, 0, 0, 0, null, purpose, List.of(), List.of(), List.of(), null);
    return generator.generate(1, 5, 0, List.of(), aiSummary, round(ReviewState.APPROVE, true));
  }

  private static ReviewResult round(ReviewState state, boolean first, RiskLevel... risks) {
    var findings =
        Arrays.stream(risks)
            .map(risk -> new Finding(risk, "src/A.java", 1, "Bug " + risk, "desc", null, null))
            .toList();
    var counts = Arrays.asList(risks);
    return new ReviewResult(
        findings,
        Collections.frequency(counts, RiskLevel.CRITICAL),
        Collections.frequency(counts, RiskLevel.HIGH),
        Collections.frequency(counts, RiskLevel.MEDIUM),
        Collections.frequency(counts, RiskLevel.LOW),
        null,
        state,
        first,
        "",
        List.of(),
        List.of(),
        0);
  }
}
