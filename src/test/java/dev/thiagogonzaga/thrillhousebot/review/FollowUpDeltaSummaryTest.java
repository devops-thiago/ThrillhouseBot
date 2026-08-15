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
import java.util.ArrayList;
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

  /** Renders with no previous round to name closed findings from — the counts-only assertions. */
  private static Optional<String> render(ReviewResult result) {
    return FollowUpDeltaSummary.render(result, List.of());
  }

  private static ReviewResponse.Finding previous(String file, int line, String title) {
    return new ReviewResponse.Finding("medium", file, line, title, "description", null, null);
  }

  @Test
  void noNewFindingsAndNothingResolvedRendersNothing() {
    // The anti-noise case: a follow-up pass where nothing moved. Previous findings that merely
    // stayed open are not a delta and must never produce a comment on their own.
    var result = followUp(List.of(), List.of(status(1, "unresolved"), status(2, "unresolved")), 0);

    assertEquals(Optional.empty(), render(result));
  }

  @Test
  void emptyRoundWithNoPreviousStatusesRendersNothing() {
    assertEquals(Optional.empty(), render(followUp(List.of(), List.of(), 0)));
  }

  @Test
  void justifiedOrSupersededAloneIsNotADelta() {
    // Neither is a fix this round: justified is a maintainer's decline, superseded re-posts the
    // full summary instead (and the publisher then skips this comment anyway).
    var result = followUp(List.of(), List.of(status(1, "justified"), status(2, "superseded")), 0);

    assertEquals(Optional.empty(), render(result));
  }

  @Test
  void newFindingsAloneRenderTheDelta() {
    var result =
        followUp(
            List.of(finding("a.java"), finding("b.java")),
            List.of(status(1, "unresolved"), status(2, "justified")),
            0);

    var body = render(result).orElseThrow();

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

    var body = render(result).orElseThrow();

    assertTrue(body.contains("**New findings this round:** 0"), body);
    assertTrue(body.contains("**Previous findings resolved:** 2"), body);
    assertTrue(body.contains("**Previous findings still open:** 1"), body);
  }

  @Test
  void closedFindingsAreNamedByLocatorAndTitle() {
    // #714: three bare integers left the maintainer diffing GitHub thread state to learn which
    // finding a clearing directive actually closed. The delta now names each one with the same
    // `path:line` and title the directive used, and names only the ones the resolved count covers.
    var result =
        followUp(
            List.of(),
            List.of(status(1, "resolved"), status(2, "unresolved"), status(3, "RESOLVED")),
            0);
    var previous =
        List.of(
            previous("src/main/java/A.java", 10, "Guard the null token"),
            previous("src/main/java/B.java", 20, "Still open"),
            previous("src/main/java/C.java", 30, "Close the stream"));

    var body = FollowUpDeltaSummary.render(result, previous).orElseThrow();

    assertTrue(body.contains("**Previous findings resolved:** 2"), body);
    assertTrue(body.contains("  - `src/main/java/A.java:10` — Guard the null token\n"), body);
    assertTrue(body.contains("  - `src/main/java/C.java:30` — Close the stream\n"), body);
    assertFalse(body.contains("src/main/java/B.java"), body);
    // The names sit between the count they explain and the still-open line, not after it.
    assertTrue(
        body.indexOf("src/main/java/A.java") < body.indexOf("**Previous findings still open:** 1"),
        body);
  }

  @Test
  void closedFindingNamesAreBoundedAndRollTheRestUp() {
    // A PR can close many findings in one round and this comment has a size budget, so the list is
    // capped like the coverage disclosure's file names and the remainder becomes a count.
    var statuses = new ArrayList<ReviewResult.PreviousFindingStatus>();
    var previous = new ArrayList<ReviewResponse.Finding>();
    for (int i = 1; i <= 12; i++) {
      statuses.add(status(i, "resolved"));
      previous.add(previous("src/F" + i + ".java", i, "Finding " + i));
    }

    var body =
        FollowUpDeltaSummary.render(followUp(List.of(), statuses, 0), previous).orElseThrow();

    assertTrue(body.contains("  - `src/F10.java:10` — Finding 10\n"), body);
    assertFalse(body.contains("src/F11.java"), body);
    assertTrue(body.contains("  - …and 2 more\n"), body);
  }

  @Test
  void closedFindingWithNoTitleIsNamedByItsLocatorAlone() {
    // A finding the model gave no title renders as the bare locator rather than a dangling dash.
    // Both halves are model text spliced into a list item, so they route through MarkdownSafe.
    var previous =
        List.of(
            previous("src/A.java", 10, null),
            previous("src/B.java", 20, "Backticked `code` and a\nnewline"));

    var body =
        FollowUpDeltaSummary.render(
                followUp(List.of(), List.of(status(1, "resolved"), status(2, "resolved")), 0),
                previous)
            .orElseThrow();

    assertTrue(body.contains("  - `src/A.java:10`\n"), body);
    assertTrue(
        body.contains("  - `src/B.java:20` — Backticked &#96;code&#96; and a newline\n"), body);
  }

  @Test
  void closedFindingsWithNoResolvablePreviousRoundRenderCountsOnly() {
    // An id outside the previous round names nothing, and a round loaded without its previous
    // findings has nothing to name from at all: the counts stay authoritative, no list renders.
    var statuses = List.of(status(1, "resolved"), status(7, "resolved"));

    var unnamed = render(followUp(List.of(), statuses, 0)).orElseThrow();
    var absent = FollowUpDeltaSummary.render(followUp(List.of(), statuses, 0), null).orElseThrow();
    var outOfRange =
        FollowUpDeltaSummary.render(
                followUp(List.of(), List.of(status(0, "resolved"), status(7, "resolved")), 0),
                List.of(previous("src/A.java", 10, "Only finding")))
            .orElseThrow();

    assertTrue(unnamed.contains("**Previous findings resolved:** 2"), unnamed);
    assertFalse(unnamed.contains("  - `"), unnamed);
    assertEquals(unnamed, absent);
    assertFalse(outOfRange.contains("  - `"), outOfRange);
  }

  @Test
  void truncatedReviewDisclosesPartialCoverage() {
    var result = followUp(List.of(finding("a.java")), List.of(), 3);

    var body = render(result).orElseThrow();

    assertTrue(body.endsWith(ReviewResult.truncationDisclosure(3)), body);
    assertTrue(body.contains("Large PR — partial coverage."), body);
  }

  @Test
  void budgetedReviewDescribesClippedFilesAsPartiallyAnalyzedNotOmitted() {
    // On the budgeted path omittedFiles folds in clipped files. Passing only the count would
    // describe a clipped (partially analyzed) file as omitted; the detail must distinguish them
    // (F8).
    var truncation =
        new ReviewResult.TruncationDetail(
            List.of("omitted.java"),
            List.of("clipped.java"),
            List.of(),
            List.of(),
            SummaryDegradation.NONE);
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

    var body = render(result).orElseThrow();

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
        new ReviewResult.TruncationDetail(
            List.of(), List.of(), List.of(), List.of(), SummaryDegradation.RESPONSE_CUT);
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

    var body = render(result).orElseThrow();

    assertFalse(body.contains("Large PR — partial coverage"), body);
    assertFalse(body.contains("covers only part of the diff"), body);
  }

  @Test
  void ceilingSkippedSummaryOnlyMustNotClaimPartialDiffCoverageInTheDeltaComment() {
    // #518's flag gets the same treatment as the summary cut (#516): a summary skipped at the
    // token spend ceiling leaves the findings — and this comment's counts — covering the whole
    // diff, so the per-file partial-coverage framing must not render.
    var truncation =
        new ReviewResult.TruncationDetail(
            List.of(), List.of(), List.of(), List.of(), SummaryDegradation.SKIPPED_AT_CEILING);
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

    var body = render(result).orElseThrow();

    assertFalse(body.contains("Large PR — partial coverage"), body);
    assertFalse(body.contains("covers only part of the diff"), body);
  }

  @Test
  void unverifiedFindingSetIsDisclosedInTheDeltaComment() {
    // #623: the delta's "new findings this round" count must not read as fully screened when the
    // second-pass verification never covered them — same fact the review body and check run state,
    // in the one-sentence form, without the per-file partial-coverage framing.
    var truncation =
        new ReviewResult.TruncationDetail(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            SummaryDegradation.NONE,
            new VerificationCoverage(2, 0));
    var result =
        new ReviewResult(
            List.of(finding("a.java"), finding("b.java")),
            0,
            0,
            2,
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

    var body = render(result).orElseThrow();

    assertTrue(
        body.contains("The 2 finding(s) were not verified (no verdicts were returned)."), body);
    assertFalse(body.contains("Large PR — partial coverage"), body);
  }

  @Test
  void verificationDisclosureDoesNotStackOnACoverageDisclosureThatAlreadyCarriesIt() {
    // With a file gap the partial-coverage disclosure folds the verification clause in, exactly
    // like the posted review's banner; the one-sentence brief must not repeat the same fact.
    var truncation =
        new ReviewResult.TruncationDetail(
            List.of("omitted.java"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            SummaryDegradation.NONE,
            new VerificationCoverage(2, 0));
    var result =
        new ReviewResult(
            List.of(finding("a.java"), finding("b.java")),
            0,
            0,
            2,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            false,
            "summary",
            List.of(),
            List.of(),
            1,
            false,
            true,
            truncation);

    var body = render(result).orElseThrow();

    assertTrue(body.contains("were NOT verified by the second-pass audit"), body);
    assertFalse(
        body.contains("The 2 finding(s) were not verified (no verdicts were returned)."), body);
  }

  @Test
  void untruncatedReviewCarriesNoDisclosure() {
    var body = render(followUp(List.of(finding("a.java")), List.of(), 0));

    assertFalse(body.orElseThrow().contains("partial coverage"), body.orElseThrow());
  }

  @Test
  void deltaCommentIsNeverMistakenForTheSummaryComment() {
    // The delta must not carry the summary heading: that marker decides whether a review is the
    // first one, and which comment the superseded path edits in place.
    var body = render(followUp(List.of(finding("a.java")), List.of(), 0));

    assertFalse(ReviewContextLoader.isBotSummaryComment(body.orElseThrow()), body.orElseThrow());
    assertEquals(
        0,
        body.orElseThrow()
            .lines()
            .filter(line -> line.strip().equals(PrSummaryGenerator.SUMMARY_HEADING))
            .count());
  }
}
