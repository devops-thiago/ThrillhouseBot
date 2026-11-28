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

  /** A follow-up round whose summary also lists the earlier findings still open. */
  private static ReviewResult withOpen(
      List<Finding> findings,
      List<ReviewResult.PreviousFindingStatus> statuses,
      List<Finding> openPrevious) {
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
        0,
        false,
        true,
        ReviewResult.TruncationDetail.EMPTY,
        0,
        openPrevious,
        false);
  }

  private static ReviewResult.PreviousFindingStatus replaced(int id) {
    return new ReviewResult.PreviousFindingStatus(id, "unresolved", FollowUpAnalyzer.REPLACED_NOTE);
  }

  private static ReviewResponse.Finding listed(String file, int line, String title) {
    return new ReviewResponse.Finding(
        "medium", "low", file, line, title, "description", null, null);
  }

  private static ReviewResponse.Finding inline(String file, int line, String title) {
    return new ReviewResponse.Finding(
        "medium", "medium", file, line, title, "description", null, null);
  }

  /** The section's lines as a release before the markers posted them (#1260). */
  private static List<String> englishOnly(String body) {
    return body.lines().filter(line -> !line.startsWith("<!-- thrillhousebot:")).toList();
  }

  /** The section's lines, and where {@link FollowUpDeltaSummary#endOfSection} ends it. */
  private static String lastLineOfSection(String body) {
    var lines = englishOnly(body);
    return lines.get(FollowUpDeltaSummary.endOfSection(lines, 0) - 1);
  }

  @Test
  void aThreadThatCarriesAnEarlierDoubleCheckItemIsNamedWithoutChangingTheCounts() {
    // #1312, ThrillhouseBot-test#293 on 2026-11-15: the round posted one thread, for the defect a
    // November round had listed only under "Things to double-check", and counted it as still open.
    // The section showed nothing, so the thread the round posted had no line in it.
    var file = "java/src/test/java/bloodbank/ExpiryTrackerTest.java";
    var item =
        listed(file, 74, "Offline-sensor stub returns a sentinel that the sensor contract forbids");
    var thread =
        inline(
            file,
            74,
            "Test stub returns a sentinel value the sensor contract forbids, leaving the offline"
                + " path untested");
    var prior = List.of(new ReviewResponse(List.of(item), List.of(), null));
    var replacedNow = FollowUpAnalyzer.replacedThisRound(prior, List.of(thread));
    var statuses =
        FollowUpAnalyzer.withoutReplaced(
            List.of(item),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
            FollowUpAnalyzer.replacedPriors(prior, List.of(thread)),
            replacedNow);
    var result =
        withOpen(
            List.of(Finding.fromAiResponse(thread)),
            statuses.stream()
                .map(s -> new ReviewResult.PreviousFindingStatus(s.id(), s.status(), s.note()))
                .toList(),
            FollowUpAnalyzer.stillOpenFindings(List.of(item), statuses, List.of(), replacedNow));

    var body = FollowUpDeltaSummary.render(result, List.of(item)).orElseThrow();

    assertTrue(
        body.contains(
            """
            - **New findings this round:** 0
            - **Previous findings resolved:** 0
            - **Previous findings still open:** 1
            - **Earlier double-check items now posted as threads:** 1
              - `java/src/test/java/bloodbank/ExpiryTrackerTest.java:74` — Test stub returns a \
            sentinel value the sensor contract forbids, leaving the offline path untested
            """),
        body);
    assertEquals(0, FollowUpDeltaSummary.newFindingCount(result));
    assertEquals(1, result.unresolvedPreviousCount());
    assertTrue(lastLineOfSection(body).contains("ExpiryTrackerTest.java:74"), body);
  }

  @Test
  void theRound294ShapeCountsOneNewFindingAndNamesTwoPromotedThreads() {
    // #1312, ThrillhouseBot-test#294: one new finding, and two threads for earlier double-check
    // items, so three threads posted under "New findings this round: 1".
    var discount = listed("src/InvoiceService.kt", 38, "Sibling discount is applied twice");
    var scan = listed("src/InvoiceService.kt", 36, "Course scan reads every invoice row");
    var discountThread = inline("src/InvoiceService.kt", 38, "Sibling discount is applied twice");
    var scanThread = inline("src/InvoiceService.kt", 36, "Course scan reads every invoice row");
    var fresh = inline("src/Pricing.kt", 9, "Rounding drops the last cent");
    var result =
        withOpen(
            List.of(
                Finding.fromAiResponse(discountThread),
                Finding.fromAiResponse(scanThread),
                Finding.fromAiResponse(fresh)),
            List.of(replaced(1), replaced(2)),
            List.of(Finding.fromAiResponse(discountThread), Finding.fromAiResponse(scanThread)));

    var body = FollowUpDeltaSummary.render(result, List.of(discount, scan)).orElseThrow();

    assertTrue(
        body.contains(
            """
            - **New findings this round:** 1
            - **Previous findings resolved:** 0
            - **Previous findings still open:** 2
            - **Earlier double-check items now posted as threads:** 2
              - `src/InvoiceService.kt:38` — Sibling discount is applied twice
              - `src/InvoiceService.kt:36` — Course scan reads every invoice row
            """),
        body);
    assertTrue(lastLineOfSection(body).contains("InvoiceService.kt:36"), body);
  }

  @Test
  void theRound297ShapeSaysHowManyNewFindingsHaveNoThread() {
    // #1312, ThrillhouseBot-test#297: two new findings, one of them low confidence and so listed
    // only under "Things to double-check", read as two findings with one thread.
    var token = listed("docs/CONFIG-SCALA.md", 8, "COLDCHAIN_API_TOKEN default is undocumented");
    var result =
        withOpen(
            List.of(
                Finding.fromAiResponse(inline("src/Main.scala", 4, "Reader leaks the file handle")),
                Finding.fromAiResponse(token)),
            List.of(status(1, "unresolved")),
            List.of(finding("a.java")));

    var body = render(result).orElseThrow();

    assertTrue(
        body.contains(
            """
            - **New findings this round:** 2 (1 under Things to double-check)
            - **Previous findings resolved:** 0
            - **Previous findings still open:** 1
            """),
        body);
    assertFalse(body.contains("now posted as threads"), body);
    assertTrue(lastLineOfSection(body).startsWith("- **Previous findings still open:** 1"), body);
  }

  @Test
  void anEarlierRoundsItemCarriedByAThreadIsNamedOnceAndAListedReplacementIsNot() {
    // #1312: the backstop holds an older round's item under the same note, carrying the finding
    // itself; one thread carrying two items is named once, and an item restated as another
    // double-check item got no thread, so it is not named.
    var older = listed("src/A.java", 10, "Retry loop never backs off");
    var thread = inline("src/A.java", 10, "Retry loop never backs off");
    var restated = listed("src/B.java", 5, "Cache key ignores the tenant");
    var stillListed = listed("src/B.java", 5, "Cache key ignores the tenant");
    var result =
        withOpen(
            List.of(Finding.fromAiResponse(thread), Finding.fromAiResponse(stillListed)),
            List.of(
                replaced(1),
                replaced(2),
                replaced(0),
                replaced(9),
                new ReviewResult.PreviousFindingStatus(
                    0,
                    "unresolved",
                    FollowUpAnalyzer.REPLACED_NOTE,
                    new ReviewResult.EarlierRound(older, null))),
            List.of(Finding.fromAiResponse(thread), Finding.fromAiResponse(stillListed)));

    var promoted = FollowUpDeltaSummary.promotedThreads(result, List.of(older, restated));

    assertEquals(List.of(Finding.fromAiResponse(thread)), promoted);
    // With no previous round to look ids up in, only the older round's item can be named.
    assertEquals(
        List.of(Finding.fromAiResponse(thread)),
        FollowUpDeltaSummary.promotedThreads(result, null));
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
  void justifiedAloneIsNotADelta() {
    // A maintainer's decline is not a fix and moves nothing the section counts.
    var result = followUp(List.of(), List.of(status(1, "justified")), 0);

    assertEquals(Optional.empty(), render(result));
  }

  @Test
  void supersededFindingsAreCountedAndNamedOnTheirOwnLine() {
    // #1318: the section left superseded findings out, so its counts did not add up to the
    // findings carried in and the superseded one was never named.
    var gone = previous("src/Gone.java", 4, "Removed handler still logs the token");
    var older = previous("src/Old.java", 9, "Older finding whose code left");
    var result =
        followUp(
            List.of(),
            List.of(
                status(1, "superseded"),
                status(2, "unresolved"),
                new ReviewResult.PreviousFindingStatus(
                    0, "superseded", "note", new ReviewResult.EarlierRound(older, null))),
            0);

    var body =
        FollowUpDeltaSummary.render(result, List.of(gone, previous("a.java", 1, "open")))
            .orElseThrow();

    assertTrue(
        body.contains(
            """
            - **Previous findings resolved:** 0
            - **Previous findings superseded (targeted code left the diff):** 2
              - `src/Gone.java:4` — Removed handler still logs the token
              - `src/Old.java:9` — Older finding whose code left
            - **Previous findings still open:** 1
            """),
        body);
    var lines = englishOnly(body);
    var end = FollowUpDeltaSummary.endOfSection(lines, 0);
    assertTrue(lines.get(end - 1).startsWith("- **Previous findings still open:** 1"), body);
    assertTrue(
        ClosedFindingNames.reviewBodySection(result, List.of(gone))
            .startsWith(
                ReadBackMarkers.PREVIOUS_START
                    + "\n"
                    + ClosedFindingNames.REVIEW_BODY_LEAD_IN
                    + "2 previous finding(s)"));
  }

  @Test
  void newFindingsAloneRenderTheDelta() {
    var result =
        followUp(
            List.of(finding("a.java"), finding("b.java")),
            List.of(status(1, "unresolved"), status(2, "justified")),
            0);

    var body = render(result).orElseThrow();

    assertTrue(
        body.startsWith(ReadBackMarkers.PREVIOUS_START + "\n" + FollowUpDeltaSummary.DELTA_HEADING),
        body);
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

    assertTrue(body.contains("only partially analyzed (`clipped.java`)"), body);
    assertTrue(body.contains("omitted entirely (`omitted.java`)"), body);
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
  void aFailedSummaryAlongsideFileGapsIsNamedInTheDeltaComment() {
    // #851: the delta comment carries summary degradations inside its partial-coverage clause, so
    // a failed summary on a review with a file gap is named there like the other two flavors.
    var truncation =
        new ReviewResult.TruncationDetail(
            List.of("b.java"), List.of(), List.of(), List.of(), SummaryDegradation.SUMMARY_FAILED);
    var result =
        new ReviewResult(
            List.of(finding("a.java")),
            0,
            0,
            1,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            true,
            "summary",
            List.of(),
            List.of(),
            1,
            false,
            true,
            truncation);

    var body = render(result).orElseThrow();

    assertTrue(body.contains("Large PR — partial coverage"), body);
    assertTrue(
        body.contains("the summary was not generated because the summary call failed"), body);
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
