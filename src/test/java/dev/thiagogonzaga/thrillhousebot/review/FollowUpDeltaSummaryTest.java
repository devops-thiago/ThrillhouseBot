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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FollowUpDeltaSummary}'s delta detection and rendering. */
class FollowUpDeltaSummaryTest {

  private static ReviewResult.PreviousFindingStatus status(int id, String state) {
    return new ReviewResult.PreviousFindingStatus(id, state, "note");
  }

  private static ReviewResult followUp(
      List<Finding> findings, List<ReviewResult.PreviousFindingStatus> statuses, int omittedFiles) {
    return new ReviewResult(
        findings,
        0,
        0,
        findings.size(),
        0,
        RiskLevel.MEDIUM,
        ReviewState.COMMENT,
        false,
        "summary",
        statuses,
        List.of(),
        omittedFiles);
  }

  private static Finding finding(String file) {
    return new Finding(RiskLevel.MEDIUM, file, 1, "title", "description", null, null);
  }

  @Test
  void noNewFindingsAndNothingResolvedRendersNothing() {
    // The anti-noise case: a follow-up pass where nothing moved. Previous findings that merely
    // stayed open are not a delta and must never produce a comment on their own.
    var result = followUp(List.of(), List.of(status(1, "unresolved"), status(2, "unresolved")), 0);

    assertEquals(Optional.empty(), FollowUpDeltaSummary.render(result));
  }

  @Test
  void emptyRoundWithNoPreviousStatusesRendersNothing() {
    assertEquals(Optional.empty(), FollowUpDeltaSummary.render(followUp(List.of(), List.of(), 0)));
  }

  @Test
  void justifiedOrSupersededAloneIsNotADelta() {
    // Neither is a fix this round: justified is a maintainer's decline, superseded re-posts the
    // full summary instead (and the publisher then skips this comment anyway).
    var result = followUp(List.of(), List.of(status(1, "justified"), status(2, "superseded")), 0);

    assertEquals(Optional.empty(), FollowUpDeltaSummary.render(result));
  }

  @Test
  void newFindingsAloneRenderTheDelta() {
    var result =
        followUp(
            List.of(finding("a.java"), finding("b.java")),
            List.of(status(1, "unresolved"), status(2, "justified")),
            0);

    var body = FollowUpDeltaSummary.render(result).orElseThrow();

    assertTrue(body.startsWith(FollowUpDeltaSummary.DELTA_HEADING), body);
    assertTrue(body.contains("**New findings this round:** 2"), body);
    assertTrue(body.contains("**Previous findings resolved:** 0"), body);
    assertTrue(body.contains("**Previous findings still open:** 1"), body);
  }

  @Test
  void resolvedPreviousFindingsAloneRenderTheDelta() {
    var result =
        followUp(
            List.of(),
            List.of(status(1, "resolved"), status(2, "RESOLVED"), status(3, "unresolved")),
            0);

    var body = FollowUpDeltaSummary.render(result).orElseThrow();

    assertTrue(body.contains("**New findings this round:** 0"), body);
    assertTrue(body.contains("**Previous findings resolved:** 2"), body);
    assertTrue(body.contains("**Previous findings still open:** 1"), body);
  }

  @Test
  void truncatedReviewDisclosesPartialCoverage() {
    var result = followUp(List.of(finding("a.java")), List.of(), 3);

    var body = FollowUpDeltaSummary.render(result).orElseThrow();

    assertTrue(body.endsWith(ReviewResult.truncationDisclosure(3)), body);
    assertTrue(body.contains("Large PR — partial coverage."), body);
  }

  @Test
  void budgetedReviewDescribesClippedFilesAsPartiallyAnalyzedNotOmitted() {
    // On the budgeted path omittedFiles folds in clipped files. Passing only the count would
    // describe a clipped (partially analyzed) file as omitted; the detail must distinguish them
    // (F8).
    var truncation =
        new ReviewResult.TruncationDetail(List.of("omitted.java"), List.of("clipped.java"));
    var result =
        new ReviewResult(
            List.of(finding("a.java")),
            0,
            0,
            1,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            false,
            "summary",
            List.of(),
            List.of(),
            2,
            false,
            true,
            truncation);

    var body = FollowUpDeltaSummary.render(result).orElseThrow();

    assertTrue(body.contains("only partially analyzed (clipped.java)"), body);
    assertTrue(body.contains("omitted entirely (omitted.java)"), body);
  }

  @Test
  void summaryOnlyCutMustNotClaimPartialDiffCoverageInTheDeltaComment() {
    // #516 — only the summary response was cut: the findings (and this comment's counts) cover
    // the whole diff. The partial-coverage framing is about per-file gaps; wrapping the
    // summary-cut clause in it renders the self-contradictory "the findings themselves are
    // complete, so this covers only part of the diff".
    var truncation =
        new ReviewResult.TruncationDetail(List.of(), List.of(), List.of(), List.of(), true);
    var result =
        new ReviewResult(
            List.of(finding("a.java")),
            0,
            0,
            1,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            false,
            "summary",
            List.of(),
            List.of(),
            0,
            false,
            true,
            truncation);

    var body = FollowUpDeltaSummary.render(result).orElseThrow();

    assertFalse(body.contains("Large PR — partial coverage"), body);
    assertFalse(body.contains("covers only part of the diff"), body);
  }

  @Test
  void ceilingSkippedSummaryOnlyMustNotClaimPartialDiffCoverageInTheDeltaComment() {
    // #518's flag gets the same treatment as the summary cut (#516): a summary skipped at the
    // token spend ceiling leaves the findings — and this comment's counts — covering the whole
    // diff, so the per-file partial-coverage framing must not render.
    var truncation =
        new ReviewResult.TruncationDetail(List.of(), List.of(), List.of(), List.of(), false, true);
    var result =
        new ReviewResult(
            List.of(finding("a.java")),
            0,
            0,
            1,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            false,
            "summary",
            List.of(),
            List.of(),
            0,
            false,
            true,
            truncation);

    var body = FollowUpDeltaSummary.render(result).orElseThrow();

    assertFalse(body.contains("Large PR — partial coverage"), body);
    assertFalse(body.contains("covers only part of the diff"), body);
  }

  @Test
  void untruncatedReviewCarriesNoDisclosure() {
    var body = FollowUpDeltaSummary.render(followUp(List.of(finding("a.java")), List.of(), 0));

    assertFalse(body.orElseThrow().contains("partial coverage"), body.orElseThrow());
  }

  @Test
  void deltaCommentIsNeverMistakenForTheSummaryComment() {
    // The delta must not carry the summary heading: that marker decides whether a review is the
    // first one, and which comment the superseded path edits in place.
    var body = FollowUpDeltaSummary.render(followUp(List.of(finding("a.java")), List.of(), 0));

    assertFalse(ReviewContextLoader.isBotSummaryComment(body.orElseThrow()), body.orElseThrow());
    assertEquals(
        0,
        body.orElseThrow()
            .lines()
            .filter(line -> line.strip().equals(PrSummaryGenerator.SUMMARY_HEADING))
            .count());
  }
}
