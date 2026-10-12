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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holding findings and description gaps to the linked issue the review read (#1073), on the round
 * 17 cases: the number named, a cited criterion the issue does not hold taken out, and a finding
 * reporting an unmet criterion kept on a thread at medium or above.
 */
class LinkedIssueCitationsTest {

  /** ThrillhouseBot-test#228, as the linked-issue section renders it for kotlin #252. */
  private static final String KOTLIN_228 =
      """
      Issues this pull request is linked to: #228

      ### Issue #228: Newsletter subscription billing service (Kotlin)
      Linked by: closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] Monthly-plan subscribers are charged their plan's list price once per billing period, \
      and an invoice is recorded for each successful charge.
      - [ ] A subscriber who already has an invoice for a period is not charged again for that \
      period.
      - [ ] Annual-plan subscribers are charged only in their renewal month.
      - [ ] The admin endpoints (`POST /billing/run`, `GET /subscribers`) reject requests without \
      a valid `X-Admin-Key` header with 401.
      Issue body:
      ## Summary
      Add a Kotlin service that bills newsletter subscribers each period.
      """;

  /** ThrillhouseBot-test#231, for c #253. */
  private static final String C_231 =
      """
      Issues this pull request is linked to: #231

      ### Issue #231: Water meter reading validator (C)
      Linked by: closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] Only zones listed in `METER_ALLOWED_ZONES` can be validated; any other zone is \
      refused with a usage error.
      - [ ] Readings with a negative litre value are rejected with reason `negative`.
      - [ ] Repeated readings (same meter serial and timestamp) are rejected with reason \
      `duplicate`.
      - [ ] Readings timestamped more than 15 minutes in the future (relative to the host clock) \
      are rejected with reason `future`.
      """;

  /** ThrillhouseBot-test#229, for zig #250. */
  private static final String ZIG_229 =
      """
      Issues this pull request is linked to: #229

      ### Issue #229: Sample tube reception queue (Zig)
      Linked by: closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] Each scanned barcode is checked against its mod-10 check digit, and tubes with a bad \
      check digit are counted as rejected in `/status`.
      - [ ] A tray scan with more than 96 tubes is rejected as a whole with HTTP 413 and nothing \
      from it is queued.
      """;

  /** ThrillhouseBot-test#225, for go #247. */
  private static final String GO_225 =
      """
      Issues this pull request is linked to: #225

      ### Issue #225: Pick batching service (Go)
      Linked by: closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] `POST /batches` groups the posted orders by zone into batches of at most \
      `PICKBATCH_MAX_ORDERS` orders and at most 40 kg per cart.
      - [ ] `POST /batches` rejects requests with more than 1,000 orders with HTTP 413.
      """;

  /** Two issues linked: "the linked issue" names neither. */
  private static final String TWO_ISSUES =
      """
      Issues this pull request is linked to: #228, #231

      ### Issue #228: Newsletter subscription billing service (Kotlin)
      Acceptance criteria (from the issue):
      - [ ] Annual-plan subscribers are charged only in their renewal month.

      ### Issue #231: Water meter reading validator (C)
      Acceptance criteria (from the issue):
      - [ ] Readings with a negative litre value are rejected with reason `negative`.
      """;

  /** Kotlin #252 (4248918234): a PR claim and a criterion #228 does not contain. */
  private static final String KOTLIN_DECLINED =
      "In production the else branch is unreachable. This directly contradicts the PR claim"
          + " 'Declined charges mark the subscriber past_due' and the acceptance criterion for"
          + " declined handling. The in-diff test still passes only because StubGateway"
          + " fabricates a 'declined' receipt.";

  private static ReviewResponse.Finding finding(
      String risk, String confidence, String title, String description) {
    return new ReviewResponse.Finding(
        risk, confidence, "src/Billing.kt", 49, title, description, null, null);
  }

  private static ReviewResponse responseOf(ReviewResponse.Finding... findings) {
    return new ReviewResponse(List.of(findings), List.of(), null);
  }

  private static ReviewResponse.Finding only(ReviewResponse response) {
    assertEquals(1, response.findings().size());
    return response.findings().getFirst();
  }

  // Rule 3: a cited criterion must be in the issue.

  @Test
  void aNamedCriterionTheIssueDoesNotHoldIsCutAndTheOtherClaimKept() {
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "high",
                        "high",
                        "Declined charges throw and abort the whole billing run",
                        KOTLIN_DECLINED)),
                KOTLIN_228));

    assertEquals(
        "In production the else branch is unreachable. This directly contradicts the PR claim"
            + " 'Declined charges mark the subscriber past_due'. The in-diff test still passes"
            + " only because StubGateway fabricates a 'declined' receipt.",
        held.description());
    assertEquals("high", held.risk());
  }

  @Test
  void aFindingWhoseOnlyClaimIsACriterionTheIssueDoesNotHoldIsDropped() {
    var response =
        responseOf(
            finding(
                "medium",
                "low",
                "Acceptance criterion 'refunds' readings rejected is not implemented anywhere",
                "Nothing in validator.c rejects them."));

    assertTrue(LinkedIssueCitations.apply(response, C_231).findings().isEmpty());
  }

  @Test
  void aDescriptionThatWasOnlyAnUnheldQuoteDropsTheFinding() {
    var response =
        responseOf(
            finding(
                "high",
                "high",
                "Refunds are never issued",
                "The linked issue states: 'Refunds are issued within a day of a declined charge'."));

    assertTrue(LinkedIssueCitations.apply(response, KOTLIN_228).findings().isEmpty());
  }

  @Test
  void aSentenceCitingAnUnheldQuoteWithNoOtherClaimIsRemovedWhole() {
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "high",
                        "high",
                        "Admin endpoints have no authentication",
                        "Neither handler checks any header. The linked issue states: 'Admins sign"
                            + " in with a password'. Anyone can trigger a billing run.")),
                KOTLIN_228));

    assertEquals(
        "Neither handler checks any header. Anyone can trigger a billing run.", held.description());
  }

  @Test
  void aQuotedCriterionTheIssueHoldsIsKeptAsWritten() {
    var description =
        "The linked issue #228 states: 'The admin endpoints (`POST /billing/run`, `GET"
            + " /subscribers`) reject requests without a valid `X-Admin-Key` header with 401.'"
            + " Neither handler checks any header.";
    var finding = finding("high", "high", "Admin endpoints have no authentication", description);

    assertSame(finding, only(LinkedIssueCitations.apply(responseOf(finding), KOTLIN_228)));
  }

  @Test
  void aNamedCriterionTheIssueHoldsIsKept() {
    var title = "Admin endpoints have no authentication; issue criterion requires X-Admin-Key";
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(finding("high", "high", title, "Neither handler checks any header.")),
                KOTLIN_228));

    assertEquals(title, held.title());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The code never compares the meter serial, breaking the acceptance criterion for"
            + " duplicates (same meter serial and timestamp).",
        "This breaks the acceptance criterion for the future reason: readings timestamped more"
            + " than 15 minutes ahead are kept."
      })
  void aCriterionWhoseSubjectRunsOnPastAParenthesisOrColonIsJudgedWhole(String description) {
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(finding("low", "low", "Readings are accepted", description)), C_231));

    assertEquals("medium", held.risk());
    assertEquals("medium", held.confidence());
  }

  @Test
  void aCriterionNamedInTooFewWordsIsNotJudged() {
    var description = "This breaks the acceptance criterion for refunds.";
    var finding = finding("high", "high", "Refund path throws", description);

    assertSame(finding, only(LinkedIssueCitations.apply(responseOf(finding), KOTLIN_228)));
  }

  @Test
  void aQuoteFarFromTheCueOrInTheNextSentenceIsNotTheIssues() {
    var description =
        "The linked issue #228 is about billing. The comment reads 'charges are retried hourly'.";
    var finding = finding("low", "high", "Comment is stale", description);

    assertSame(finding, only(LinkedIssueCitations.apply(responseOf(finding), KOTLIN_228)));
  }

  @Test
  void aCitationOfAnIssueTheRequestDoesNotLinkIsNotJudged() {
    var description = "Issue #999 says 'refunds are instant', which is unrelated.";
    var finding = finding("low", "high", "Stale reference", description);

    assertSame(finding, only(LinkedIssueCitations.apply(responseOf(finding), KOTLIN_228)));
  }

  @Test
  void aCitationByTheLinkedIssuesNumberIsJudged() {
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "high",
                        "high",
                        "Charges run twice",
                        "Invoices are not checked, and issue #228 says 'refunds are instant'.")),
                KOTLIN_228));

    assertEquals("Invoices are not checked.", held.description());
  }

  @Test
  void aJoinerInsideAQuoteIsNotWhereTheCutStarts() {
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "high",
                        "high",
                        "Charges run twice",
                        "The code reads 'charge and record' and the acceptance criterion for"
                            + " instant refunds.")),
                KOTLIN_228));

    assertEquals("The code reads 'charge and record'.", held.description());
  }

  // Rule 2: a finding reporting an unmet criterion is threaded, at medium or above.

  @Test
  void aLowConfidenceFindingReportingAnUnmetCriterionIsRaisedOntoAThread() {
    // c #253: the criterion was listed only under "Things to double-check".
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "medium",
                        "low",
                        "Acceptance criterion 'future' readings rejected is not implemented"
                            + " anywhere",
                        "validate_reading never compares the timestamp with the host clock.")),
                C_231));

    assertEquals("medium", held.risk());
    assertEquals("medium", held.confidence());
    assertTrue(Finding.fromAiResponse(held).postsInline());
  }

  @Test
  void aLowRiskFindingReportingAnUnmetCriterionIsRaisedToMedium() {
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "low",
                        "high",
                        "Over-long tray is truncated",
                        "The linked issue states: \"A tray scan with more than 96 tubes is"
                            + " rejected as a whole with HTTP 413 and nothing from it is queued.\""
                            + " The code stops after 96.")),
                ZIG_229));

    assertEquals("medium", held.risk());
    assertEquals("high", held.confidence());
  }

  @Test
  void aFindingSayingTheCodeMatchesACriterionIsNotRaised() {
    // go #247 (4248906952): the issue's 40 kg is cited as what the constant gets right.
    var description =
        "The comment asserts 25 kg while the code reads 40000 grams, which matches the linked"
            + " issue #225's 'at most 40 kg per cart'.";
    var finding = finding("low", "low", "Comment states a 25 kg cart cap", description);

    assertSame(finding, only(LinkedIssueCitations.apply(responseOf(finding), GO_225)));
  }

  @Test
  void aFindingSayingTheCriterionRequiresWhatTheCodeDoesIsNotRaised() {
    // The affirmation follows the quote instead of preceding it.
    var description =
        "The comment asserts 25 kg while the code reads 40000 grams, which the linked issue #225's"
            + " 'at most 40 kg per cart' requires.";
    var finding = finding("low", "low", "Comment states a 25 kg cart cap", description);

    assertSame(finding, only(LinkedIssueCitations.apply(responseOf(finding), GO_225)));

    // The clause ends at its comma: "not" in the next clause is about the comment, not the code.
    var commented =
        finding(
            "low",
            "low",
            "Comment states a 25 kg cart cap",
            "The code reads 40000 grams, which the linked issue #225's 'at most 40 kg per cart'"
                + " requires, but the comment does not say so.");
    assertSame(commented, only(LinkedIssueCitations.apply(responseOf(commented), GO_225)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The cap is 40000 grams, satisfying the linked issue #225's 'at most 40 kg per cart'.",
        "The cap is 40000 grams, meeting the linked issue #225's 'at most 40 kg per cart'.",
        "The cap is 40000 grams, consistent with the linked issue #225's 'at most 40 kg per cart'.",
        "The linked issue #225's 'at most 40 kg per cart' is met by the 40000 gram cap."
      })
  void anAffirmationInAnyFormIsNotRaised(String description) {
    var finding = finding("low", "low", "Comment states a 25 kg cart cap", description);

    assertSame(finding, only(LinkedIssueCitations.apply(responseOf(finding), GO_225)));
  }

  @Test
  void aNegatedAffirmationIsRaised() {
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "low",
                        "low",
                        "Carts are filled to 60 kg",
                        "The 60000 gram cap does not match the linked issue #225's 'at most 40 kg"
                            + " per cart'.")),
                GO_225));

    assertEquals("medium", held.risk());
  }

  @Test
  void aRelativeClauseSayingTheCodeViolatesACriterionIsRaised() {
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "low",
                        "low",
                        "Carts are filled to 60 kg",
                        "The cap is 60000 grams, which violates the linked issue #225's 'at most"
                            + " 40 kg per cart'.")),
                GO_225));

    assertEquals("medium", held.risk());
    assertEquals("medium", held.confidence());
  }

  @Test
  void twoUnmatchedCitationsInOneSentenceAreCutWithoutStaleOffsets() {
    // The later citation's cut takes the whole sentence, which also holds the earlier one.
    var response =
        responseOf(
            finding(
                "high",
                "high",
                "Refund path throws",
                "The linked issue's 'refunds are instant' is broken per the acceptance criterion"
                    + " for declined handling."));

    assertTrue(LinkedIssueCitations.apply(response, KOTLIN_228).findings().isEmpty());

    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "high",
                        "high",
                        "Refund path throws",
                        "The charge loop aborts. The linked issue's 'refunds are instant' is"
                            + " broken per the acceptance criterion for declined handling. Nothing"
                            + " is retried.")),
                KOTLIN_228));
    assertEquals("The charge loop aborts. Nothing is retried.", held.description());
  }

  @Test
  void aFindingWithNoConfidenceIsRaisedOnRiskOnly() {
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "low",
                        null,
                        "Duplicate charge",
                        "This violates the linked issue #228's criterion 'is not charged again"
                            + " for that period'.")),
                KOTLIN_228));

    assertEquals("medium", held.risk());
    assertNull(held.confidence());
    // A finding with no confidence reads as high confidence, which always posts on a thread.
    assertTrue(Finding.fromAiResponse(held).postsInline());
  }

  // Rule 1: the issue is named.

  @Test
  void theLinkedIssueNamesItsNumberWhenOneIssueIsLinked() {
    // zig #250: "The linked issue states: …" never said which issue.
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "high",
                        "medium",
                        "Over-long tray is truncated, not rejected with 413",
                        "The linked issue states: \"A tray scan with more than 96 tubes is"
                            + " rejected as a whole with HTTP 413 and nothing from it is queued.\""
                            + " This also breaks the linked issue's scope.")),
                ZIG_229));

    assertEquals(
        "The linked issue #229 states: \"A tray scan with more than 96 tubes is rejected as a whole"
            + " with HTTP 413 and nothing from it is queued.\" This also breaks the linked issue"
            + " #229's scope.",
        held.description());
  }

  @Test
  void theLinkedIssuesAcceptanceCriteriaNameTheIssueAndHoldThePrice() {
    // python #244 (4248904021): two cues run into one citation, whose quote holds full stops.
    var python =
        """
        Issues this pull request is linked to: #223

        ### Issue #223: Bike classifieds service (Python)
        Acceptance criteria (from the issue):
        - [ ] Listings with an asking price above 20,000 (2,000,000 cents) are rejected with a \
        validation error.
        """;
    var held =
        only(
            LinkedIssueCitations.apply(
                responseOf(
                    finding(
                        "high",
                        "high",
                        "Missing validation: prices above 20,000 are accepted",
                        "The linked issue's acceptance criteria state: \"Listings with an asking"
                            + " price above 20,000 (2,000,000 cents) are rejected with a validation"
                            + " error.\" The only price check is 'if price_cents <= 0'.")),
                python));

    assertEquals(
        "The linked issue #223's acceptance criteria state: \"Listings with an asking price above"
            + " 20,000 (2,000,000 cents) are rejected with a validation error.\" The only price"
            + " check is 'if price_cents <= 0'.",
        held.description());
  }

  @Test
  void aFindingWithNoTitleIsNotDroppedForIt() {
    var finding = finding("high", "high", null, "The linked issue states the rule.");

    var held = only(LinkedIssueCitations.apply(responseOf(finding), KOTLIN_228));

    assertNull(held.title());
    assertEquals("The linked issue #228 states the rule.", held.description());
  }

  @Test
  void theLinkedIssueIsNotRewrittenWhenTwoIssuesAreLinked() {
    var description =
        "This breaks the linked issue: 'Annual-plan subscribers are charged only in their renewal"
            + " month'.";
    var finding = finding("high", "high", "Annual plans charged monthly", description);

    var held = only(LinkedIssueCitations.apply(responseOf(finding), TWO_ISSUES));

    assertEquals(description, held.description());
  }

  @Test
  void aMentionAlreadyNamingTheNumberIsLeftAlone() {
    assertEquals(
        "the linked issue #228 and the linked issue (#228)",
        LinkedIssueCitations.named(
            "the linked issue #228 and the linked issue (#228)", List.of("#228")));
    assertEquals(
        "the linked issues say so",
        LinkedIssueCitations.named("the linked issues say so", List.of("#228")));
  }

  @Test
  void aNullTitleOrDescriptionIsKeptNull() {
    var finding = finding("high", "high", "Linked issue left unnamed", null);

    var held = only(LinkedIssueCitations.apply(responseOf(finding), KOTLIN_228));

    assertEquals("Linked issue #228 left unnamed", held.title());
    assertNull(held.description());
  }

  // No linked issue: nothing changes.

  @Test
  void nothingChangesWhenNoIssueIsLinked() {
    var response =
        responseOf(
            finding(
                "low",
                "low",
                "Acceptance criterion 'refunds' is not implemented",
                "The linked issue states: 'Refunds are instant'."));

    assertSame(response, LinkedIssueCitations.apply(response, ""));
    assertSame(response, LinkedIssueCitations.apply(response, null));
    var gaps = List.of("The linked issue asks for 'refunds are instant'.");
    assertSame(gaps, LinkedIssueCitations.screenGaps(gaps, ""));
  }

  @Test
  void aResponseWithNoFindingsOrNothingToChangeIsReturnedAsIs() {
    var empty = responseOf();
    assertSame(empty, LinkedIssueCitations.apply(empty, KOTLIN_228));

    var plain = responseOf(finding("high", "high", "Off-by-one", "The loop skips the last row."));
    assertSame(plain, LinkedIssueCitations.apply(plain, KOTLIN_228));
    assertSame(List.of(), LinkedIssueCitations.screenGaps(List.of(), KOTLIN_228));
  }

  // The Description vs. Implementation bullets.

  @Test
  void aLinkedIssueBulletWhoseCriterionTheIssueHoldsIsKept() {
    // go #247: the shortened criterion still reads as #225's.
    var gap =
        "Linked issue #225: POST /batches rejects requests with more than 1,000 orders with HTTP"
            + " 413 — no changed file declares such a limit in handleCreateBatches.";

    assertEquals(List.of(gap), LinkedIssueCitations.screenGaps(List.of(gap), GO_225));
  }

  @Test
  void aLinkedIssueBulletWhoseCriterionTheIssueDoesNotHoldIsDropped() {
    var gap = "Linked issue #228: declined charges mark the subscriber past_due — not handled.";

    assertEquals(List.of(), LinkedIssueCitations.screenGaps(List.of(gap), KOTLIN_228));
  }

  @Test
  void aLinkedIssueBulletQuotingItsCriterionIsHeldToTheQuote() {
    var held =
        "Linked issue #231: 'rejected with reason `negative`' — no changed file checks the sign.";
    var misquoted = "Linked issue #231: 'rejected with reason `stale`' — nothing rejects them.";

    assertEquals(
        List.of(held), LinkedIssueCitations.screenGaps(List.of(held, misquoted), TWO_ISSUES));
  }

  @Test
  void aLinkedIssueBulletNamingAnIssueNotLinkedIsHeldToEveryIssue() {
    var gap = "Linked issue #5: annual-plan subscribers charged only in their renewal month.";

    assertEquals(List.of(gap), LinkedIssueCitations.screenGaps(List.of(gap), TWO_ISSUES));
  }

  @Test
  void aLinkedIssueBulletTooShortToJudgeIsKept() {
    var gap = "Linked issue #228: refunds — missing.";

    assertEquals(List.of(gap), LinkedIssueCitations.screenGaps(List.of(gap), KOTLIN_228));
  }

  @Test
  void anotherBulletLosesAnUnheldCitationAndNamesTheIssue() {
    var gaps =
        List.of(
            "The description promises past_due handling, and the linked issue asks for 'instant"
                + " refunds on decline'.",
            "The acceptance criterion for instant refunds is not met.");

    assertEquals(
        List.of("The description promises past_due handling."),
        LinkedIssueCitations.screenGaps(gaps, KOTLIN_228));
  }

  @Test
  void anotherBulletMentioningTheLinkedIssueNamesItsNumber() {
    assertEquals(
        List.of("The description omits the linked issue #228's renewal month."),
        LinkedIssueCitations.screenGaps(
            List.of("The description omits the linked issue's renewal month."), KOTLIN_228));
  }

  @Test
  void theCarryHoldsTheSummarysBulletsToTheIssue() {
    var carry = new DescriptionGapCarryover.Carry(List.of(), KOTLIN_228);
    var summary =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            null,
            null,
            List.of(
                "Linked issue #228: declined charges mark the subscriber past_due — not handled.",
                "Linked issue #228: annual-plan subscribers charged only in their renewal month —"
                    + " no renewal check."),
            List.of(),
            List.of(),
            null);

    var gaps = carry.apply(summary).descriptionGaps();

    assertEquals(1, gaps.size());
    assertFalse(gaps.getFirst().contains("declined"));
  }
}
