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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewResultTest {

  @Test
  void hasIssuesShouldReturnTrueWhenFindingsNotEmpty() {
    var findings = List.of(new Finding(RiskLevel.LOW, "f", 1, "t", "d", null, null));

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

    assertTrue(result.hasIssues());
  }

  @Test
  void hasIssuesShouldReturnFalseWhenFindingsEmpty() {
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    assertFalse(result.hasIssues());
  }

  @Test
  void keyFindingsShouldExcludeLowConfidenceFindingsRoutedToDoubleCheck() {
    var inline = new Finding(RiskLevel.HIGH, Confidence.HIGH, "a", 1, "Inline", "", null, null);
    var summaryOnly =
        new Finding(RiskLevel.MEDIUM, Confidence.LOW, "b", 2, "Double-check", "", null, null);
    var result =
        new ReviewResult(
            List.of(inline, summaryOnly),
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

    assertEquals(List.of(inline), result.keyFindings());
    assertEquals(List.of(summaryOnly), result.doubleCheckFindings());
  }

  @Test
  void totalFindingsShouldReturnListSize() {
    var findings =
        List.of(
            new Finding(RiskLevel.CRITICAL, "a", 1, "C", "", null, null),
            new Finding(RiskLevel.HIGH, "b", 2, "H", "", null, null),
            new Finding(RiskLevel.MEDIUM, "c", 3, "M", "", null, null));

    var result =
        new ReviewResult(
            findings,
            1,
            1,
            1,
            0,
            RiskLevel.CRITICAL,
            ReviewState.REQUEST_CHANGES,
            false,
            "",
            List.of(),
            List.of(),
            0);

    assertEquals(3, result.totalFindings());
  }

  @Test
  void totalFindingsShouldReturnZeroForEmptyList() {
    var result =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

    assertEquals(0, result.totalFindings());
  }

  @Test
  void compactConstructorShouldDefensivelyCopyLists() {
    var mutableFindings = new ArrayList<Finding>();
    mutableFindings.add(new Finding(RiskLevel.LOW, "f", 1, "t", "d", null, null));
    var mutableStatuses = new ArrayList<ReviewResult.PreviousFindingStatus>();
    mutableStatuses.add(new ReviewResult.PreviousFindingStatus(1, "resolved", "done"));

    var result =
        new ReviewResult(
            mutableFindings,
            0,
            0,
            0,
            1,
            RiskLevel.LOW,
            ReviewState.COMMENT,
            true,
            "",
            mutableStatuses,
            List.of(),
            0);

    // Mutate the original lists — the record's lists should be unaffected
    mutableFindings.add(new Finding(RiskLevel.HIGH, "g", 2, "x", "y", null, null));
    mutableStatuses.add(new ReviewResult.PreviousFindingStatus(2, "unresolved", "nope"));

    assertEquals(1, result.findings().size());
    assertEquals(1, result.previousStatuses().size());
    assertNotSame(mutableFindings, result.findings());
    assertNotSame(mutableStatuses, result.previousStatuses());
  }

  @Test
  void compactConstructorShouldDeriveNullReviewStateFromHighestRisk() {
    var findings = List.of(new Finding(RiskLevel.HIGH, "f", 1, "t", "d", null, null));

    var result =
        new ReviewResult(
            findings, 0, 1, 0, 0, RiskLevel.HIGH, null, true, "", List.of(), List.of(), 0);

    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
  }

  @Test
  void compactConstructorShouldDeriveApproveWhenStateAndRiskAreNull() {
    var result =
        new ReviewResult(List.of(), 0, 0, 0, 0, null, null, true, "", List.of(), List.of(), 0);

    assertEquals(ReviewState.APPROVE, result.reviewState());
  }

  @Test
  void truncationDisclosureIsEmptyWhenNothingWasOmitted() {
    assertEquals("", ReviewResult.truncationDisclosure(0));
  }

  @Test
  void truncationDisclosureDisclosesTheOmittedCountWithoutReviewFraming() {
    var disclosure = ReviewResult.truncationDisclosure(48);

    // Leads with a blank-line separator so it appends cleanly after a command's own footer, and
    // shares the omitted-file clause with the review banner...
    assertTrue(disclosure.startsWith("\n\n"), disclosure);
    assertTrue(
        disclosure.contains("48 file(s) were omitted because the diff exceeded the size budget"),
        disclosure);
    assertTrue(disclosure.contains("partial coverage"), disclosure);
    // ...but drops the review-only "findings and verdict" framing, which is wrong for a
    // description / changelog entry / doc suggestion.
    assertFalse(disclosure.contains("findings and verdict"), disclosure);
    assertFalse(disclosure.contains("partial review"), disclosure);
  }

  @Test
  void truncationDisclosureTreatsASummaryFlagOnlyDetailAsEmpty() {
    // #516 — the disclosure's framing ("Large PR — partial coverage … covers only part of the
    // diff") is about per-file gaps. A detail whose only content is a summary flag means the
    // findings cover the whole diff, so this surface must render nothing.
    var detail =
        new ReviewResult.TruncationDetail(
            List.of(), List.of(), List.of(), List.of(), SummaryDegradation.RESPONSE_CUT);

    assertEquals("", ReviewResult.truncationDisclosure(0, detail));
    // The guard's null arm behaves like an empty detail, and a detail with file gaps still
    // discloses even when the caller's count is zero — the names are the coverage truth.
    assertEquals("", ReviewResult.truncationDisclosure(0, null));
    var clippedOnly =
        new ReviewResult.TruncationDetail(
            List.of(), List.of("clipped.java"), List.of(), List.of(), SummaryDegradation.NONE);
    assertTrue(
        ReviewResult.truncationDisclosure(0, clippedOnly).contains("partially analyzed"),
        ReviewResult.truncationDisclosure(0, clippedOnly));
  }

  @Test
  void truncationDisclosureStillRendersWhenFileGapsAccompanyTheSummaryCut() {
    // With a real file gap the partial-coverage framing is correct, and the summary cut folds in
    // as one more clause — the flag-only guard must not suppress this shape.
    var detail =
        new ReviewResult.TruncationDetail(
            List.of("omitted.java"),
            List.of(),
            List.of(),
            List.of(),
            SummaryDegradation.RESPONSE_CUT);

    var disclosure = ReviewResult.truncationDisclosure(1, detail);

    assertTrue(disclosure.contains("partial coverage"), disclosure);
    assertTrue(disclosure.contains("omitted.java"), disclosure);
    assertTrue(disclosure.contains("the summary was shortened"), disclosure);
  }

  /**
   * #455 — the recognizer decides whether a prior review body is the bot's own verdict prose (and
   * so must never be fed back to the model as a "previous finding") or someone else's text, which
   * must be kept. It has to match every count the generator can emit, in the shape a stored review
   * body arrives in.
   */
  @Test
  void isUnresolvedPreviousMessageShouldMatchTheGeneratedSentenceForAnyCount() {
    for (var count : List.of(1L, 2L, 47L)) {
      assertTrue(
          ReviewResult.isUnresolvedPreviousMessage(ReviewResult.unresolvedPreviousMessage(count)),
          "count " + count);
    }
    assertTrue(
        ReviewResult.isUnresolvedPreviousMessage(
            "  " + ReviewResult.unresolvedPreviousMessage(3) + "  "),
        "a stored review body keeps its surrounding whitespace");
  }

  @Test
  void coverageGapClauseNamesTheSpendCeilingSeparatelyFromTheBudgetOmissions() {
    // #499: files skipped because the review's token spend ceiling was reached have a different
    // cause — and a different operator fix — than files the diff budget dropped, so the rendered
    // disclosure must name the ceiling (and its knob) rather than folding them into the budget
    // wording.
    var detail =
        new ReviewResult.TruncationDetail(
            List.of("a.java"),
            List.of("b.java"),
            List.of("c.java", "d.java"),
            List.of(),
            SummaryDegradation.NONE);

    var clause = ReviewResult.coverageGapClause(4, detail);

    assertTrue(clause.contains("1 file(s) were omitted entirely (a.java)"), clause);
    assertTrue(clause.contains("1 file(s) were only partially analyzed (b.java)"), clause);
    assertTrue(
        clause.contains(
            "2 file(s) were not reviewed because the review's token spend ceiling"
                + " (REVIEW_MAX_TOKENS_PER_REVIEW) was reached (c.java, d.java)"),
        clause);
  }

  @Test
  void coverageGapClauseWithOnlySpendCeilingSkipsDropsTheBudgetWording() {
    var detail =
        new ReviewResult.TruncationDetail(
            List.of(), List.of(), List.of("c.java"), List.of(), SummaryDegradation.NONE);

    var clause = ReviewResult.coverageGapClause(1, detail);

    assertFalse(clause.contains("review budget"), clause);
    assertTrue(clause.contains("token spend ceiling"), clause);
    assertTrue(clause.contains("REVIEW_MAX_TOKENS_PER_REVIEW"), clause);
  }

  @Test
  void coverageGapBriefCountsSpendCeilingSkipsAsTheirOwnClass() {
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
            false,
            true,
            new ReviewResult.TruncationDetail(
                List.of("a.java"),
                List.of(),
                List.of("c.java"),
                List.of(),
                SummaryDegradation.NONE));

    var brief = result.coverageGapBrief();

    assertTrue(brief.contains("1 file(s) omitted"), brief);
    assertTrue(brief.contains("1 file(s) skipped at the token spend ceiling"), brief);
  }

  @Test
  void truncationDetailNormalizesNullListsToEmpty() {
    var detail = new ReviewResult.TruncationDetail(null, null, null, null, null);
    assertTrue(detail.isEmpty());
    assertEquals(List.of(), detail.spendCeilingSkippedFileNames());
    assertEquals(List.of(), detail.responseCutFileNames());
    assertEquals(
        SummaryDegradation.NONE,
        detail.summaryDegradation(),
        "a null degradation normalizes to NONE");
  }

  @Test
  void coverageGapClauseNamesTheResponseCutClassWithTheCapAndTheKeptFindings() {
    // #500: a salvaged batch's files were reviewed up to the cut — the clause must say the
    // response was cut (naming the cap) and that the findings up to the cut were kept, not fold
    // them into the budget or ceiling wording.
    var detail =
        new ReviewResult.TruncationDetail(
            List.of("a.java"), List.of(), List.of(), List.of("cut.java"), SummaryDegradation.NONE);

    var clause = ReviewResult.coverageGapClause(2, detail);

    assertTrue(clause.contains("1 file(s) were omitted entirely (a.java)"), clause);
    assertTrue(
        clause.contains(
            "1 file(s) were only partially reviewed because the model's response was cut at its"
                + " length cap (max-output-tokens) — findings up to the cut were kept (cut.java)"),
        clause);
  }

  @Test
  void coverageGapBriefCountsResponseCutFilesAsTheirOwnClass() {
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
            1,
            false,
            true,
            new ReviewResult.TruncationDetail(
                List.of(), List.of(), List.of(), List.of("cut.java"), SummaryDegradation.NONE));

    var brief = result.coverageGapBrief();

    assertTrue(
        brief.contains("1 file(s) partially reviewed (response cut at the length cap)"), brief);
  }

  @Test
  void truncationDetailWithOnlyResponseCutFilesIsNotEmpty() {
    var detail =
        new ReviewResult.TruncationDetail(
            List.of(), List.of(), List.of(), List.of("cut.java"), SummaryDegradation.NONE);
    assertFalse(detail.isEmpty());
    // The canonical constructor with an empty response-cut list keeps the pre-#500 shape.
    assertTrue(
        new ReviewResult.TruncationDetail(
                List.of(), List.of(), List.of(), List.of(), SummaryDegradation.NONE)
            .isEmpty());
  }

  @Test
  void coverageGapClauseNamesTheSummaryCutAlongsideFileGaps() {
    // #500 scope A: when the summary call's response was cut too, the clause must say so in the
    // posted review — naming both knobs (the summary runs on the concise model) and making clear
    // the findings themselves are complete — instead of leaving the cut log-only while the
    // sibling ceiling degradation of the same lane discloses itself.
    var detail =
        new ReviewResult.TruncationDetail(
            List.of("a.java"), List.of(), List.of(), List.of(), SummaryDegradation.RESPONSE_CUT);

    var clause = ReviewResult.coverageGapClause(1, detail);

    assertTrue(clause.contains("1 file(s) were omitted entirely (a.java)"), clause);
    assertTrue(
        clause.contains(
            "the summary was shortened because the model's response was cut at its length cap"
                + " (max-output-tokens / REVIEW_CONCISE_MAX_OUTPUT_TOKENS) — the findings"
                + " themselves are complete"),
        clause);
  }

  @Test
  void coverageGapBriefMarksTheSummaryCut() {
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
            1,
            false,
            true,
            new ReviewResult.TruncationDetail(
                List.of("a.java"),
                List.of(),
                List.of(),
                List.of(),
                SummaryDegradation.RESPONSE_CUT));

    var brief = result.coverageGapBrief();

    assertTrue(brief.contains("1 file(s) omitted"), brief);
    assertTrue(brief.contains("summary shortened (response cut at the length cap)"), brief);
  }

  @Test
  void truncationDetailWithOnlyTheSummaryCutIsNotEmpty() {
    var detail =
        new ReviewResult.TruncationDetail(
            List.of(), List.of(), List.of(), List.of(), SummaryDegradation.RESPONSE_CUT);
    assertFalse(detail.isEmpty());
    // The canonical constructor with no degradation keeps the pre-summary-cut shape.
    assertEquals(
        SummaryDegradation.NONE,
        new ReviewResult.TruncationDetail(
                List.of(), List.of(), List.of(), List.of(), SummaryDegradation.NONE)
            .summaryDegradation());
  }

  @Test
  void truncationDetailWithOnlyTheCeilingSkippedSummaryIsNotEmpty() {
    // #518: like the summary cut, the ceiling-skipped summary must defeat the EMPTY guards so
    // the summary-aware surfaces render it...
    var detail =
        new ReviewResult.TruncationDetail(
            List.of(), List.of(), List.of(), List.of(), SummaryDegradation.SKIPPED_AT_CEILING);
    assertFalse(detail.isEmpty());
    // ...while the per-file surfaces treat it as gap-free, exactly like the cut flavor (#516).
    assertFalse(detail.hasFileGaps());
    // The single slot keeps the two flavors mutually exclusive by construction.
    assertEquals(
        SummaryDegradation.RESPONSE_CUT,
        new ReviewResult.TruncationDetail(
                List.of(), List.of(), List.of(), List.of(), SummaryDegradation.RESPONSE_CUT)
            .summaryDegradation());
  }

  @Test
  void coverageGapClauseNamesTheCeilingSkippedSummaryAlongsideFileGaps() {
    // #518: when the summary call was skipped at the token spend ceiling, the clause must say so
    // in the posted review — naming the ceiling knob and making clear the findings themselves are
    // complete — instead of leaving the degradation log-only.
    var detail =
        new ReviewResult.TruncationDetail(
            List.of("a.java"),
            List.of(),
            List.of(),
            List.of(),
            SummaryDegradation.SKIPPED_AT_CEILING);

    var clause = ReviewResult.coverageGapClause(1, detail);

    assertTrue(clause.contains("1 file(s) were omitted entirely (a.java)"), clause);
    assertTrue(
        clause.contains(
            "the summary was skipped because the review's token spend ceiling"
                + " (REVIEW_MAX_TOKENS_PER_REVIEW) was reached — the findings themselves are"
                + " complete"),
        clause);
  }

  @Test
  void coverageGapBriefMarksTheCeilingSkippedSummary() {
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
            1,
            false,
            true,
            new ReviewResult.TruncationDetail(
                List.of("a.java"),
                List.of(),
                List.of(),
                List.of(),
                SummaryDegradation.SKIPPED_AT_CEILING));

    var brief = result.coverageGapBrief();

    assertTrue(brief.contains("1 file(s) omitted"), brief);
    assertTrue(brief.contains("summary skipped (token spend ceiling reached)"), brief);
  }

  @Test
  void truncationDisclosureTreatsACeilingSkipFlagOnlyDetailAsEmpty() {
    // #516's guard must hold for the ceiling flavor too: no file gap means no partial-coverage
    // framing, whichever summary flag is set.
    var detail =
        new ReviewResult.TruncationDetail(
            List.of(), List.of(), List.of(), List.of(), SummaryDegradation.SKIPPED_AT_CEILING);

    assertEquals("", ReviewResult.truncationDisclosure(0, detail));
  }

  @Test
  void truncationDetailWithOnlySpendCeilingSkipsIsNotEmpty() {
    var detail =
        new ReviewResult.TruncationDetail(
            List.of(), List.of(), List.of("c.java"), List.of(), SummaryDegradation.NONE);
    assertFalse(detail.isEmpty());
    // The canonical constructor with an empty ceiling-skip list keeps the pre-#499 shape.
    assertTrue(
        new ReviewResult.TruncationDetail(
                List.of(), List.of(), List.of(), List.of(), SummaryDegradation.NONE)
            .isEmpty());
  }

  /**
   * #455 — everything the recognizer accepts is dropped from the previous-findings context, so a
   * near miss costs a maintainer their review body. A human sentence that merely opens with the
   * same words, or that reproduces only the generated ending, is not the bot's own prose.
   */
  @Test
  void isUnresolvedPreviousMessageShouldRejectTextThatOnlyResemblesIt() {
    assertFalse(ReviewResult.isUnresolvedPreviousMessage(null));
    assertFalse(
        ReviewResult.isUnresolvedPreviousMessage(
            "No new issues in this revision, but the null check on line 12 is still wrong."),
        "a human review opening with the same words carries a real finding and must be kept");
    assertFalse(
        ReviewResult.isUnresolvedPreviousMessage(
            "Please fix them, or reply on their review thread (where one exists) with why they are"
                + " deferred."),
        "the generated ending without the generated opening is not the sentence");
  }
}
