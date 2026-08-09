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
