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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient.IssueComment;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The linked issue and its unmet criterion, always named in the summary (#1093). */
class LinkedIssueCriteriaTest {

  /** ThrillhouseBot-test#267, as the linked-issue section renders it. */
  private static final String ISSUE_267 =
      """
      Issues this pull request is linked to: #267

      ### Issue #267: Pharmacy prescription refill queue service
      Linked by: a closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] `POST /refills` queues a refill request and responds 409 when the same Rx number is already pending
      - [ ] Prescriptions enrolled via `POST /auto-refill` are queued by a scheduled job once they come due, on the interval configured by `AUTO_REFILL_INTERVAL`
      - [ ] `GET /history` rejects stores this instance is not configured for with 400
      - [ ] `GET /refills/next` rejects a `limit` above 100 with 413
      """;

  private static final String CRITERION_267_4 =
      "`GET /refills/next` rejects a `limit` above 100 with 413";

  /** ThrillhouseBot-test#256. */
  private static final String ISSUE_256 =
      """
      Issues this pull request is linked to: #256

      ### Issue #256: Community garden plot waitlist
      Linked by: a closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] `POST /signup` adds an applicant and returns their place in line
      - [ ] Bulk import of the council's yearly export skips repeat sign-ups with the same email
      - [ ] An offer round offers each vacant plot to the next in-borough applicant in line and records when the offer expires
      - [ ] An applicant who declines two offers is moved to the back of the waitlist
      - (2 more not shown)
      """;

  /** ThrillhouseBot-test#260. */
  private static final String ISSUE_260 =
      """
      Issues this pull request is linked to: #260

      ### Issue #260: Volunteer shift board
      Linked by: a closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] The board lists every shift for the current week with its remaining spots, and a shift with no spots left shows "Full" with the Sign up button disabled.
      - [ ] Coordinators can limit the board to a set of roles through configuration.
      - [ ] Shifts that start in less than 2 hours are locked: the Sign up button is disabled and the card shows "Sign-ups closed".
      - [x] After signing up, the volunteer sees a confirmation that says whether an SMS reminder was scheduled.
      Issue body:
      Volunteers sign up on paper today.
      """;

  /** The java #269 finding reporting criterion 4 of #267. */
  private static final Finding LIMIT =
      new Finding(
          RiskLevel.HIGH,
          Confidence.HIGH,
          "java/src/main/java/com/example/refill/RefillServer.java",
          90,
          "limit above 100 is accepted instead of rejected with 413, contrary to the acceptance"
              + " criterion",
          "handleNext clamps nothing: a limit of 500 is passed to the queue.",
          null,
          null);

  /** The #273 ShiftBoard walkthrough row. */
  private static final String SHIFT_BOARD_ROW =
      "Renders the shift cards (role, location, spots left, sign-ups with note HTML) and a single"
          + " Sign up button; no cancel control and no lockout for imminent shifts.";

  private static LinkedIssueCriteria.Lines unmet(
      String issues, List<Finding> findings, String overview, Map<String, String> rows) {
    return LinkedIssueCriteria.unmet(issues, List.of(), findings, overview, rows);
  }

  @Test
  void aCriterionThatBecameAFindingGetsALinePointingAtTheFinding() {
    var lines = unmet(ISSUE_267, List.of(LIMIT), null, Map.of());

    assertEquals(
        List.of(
            "Linked issue #267: "
                + CRITERION_267_4
                + " — reported as a finding: "
                + LIMIT.title()
                + " (`java/src/main/java/com/example/refill/RefillServer.java:90`)"),
        lines.lines());
    assertEquals(Set.of(LIMIT), lines.pointed());
    assertTrue(LinkedIssueCriteria.pointsAtFinding(lines.lines().getFirst()));
  }

  @Test
  void aFindingThatDoesNotCiteTheIssueIsNotTiedToACriterion() {
    var bug =
        new Finding(
            RiskLevel.HIGH,
            "java/src/main/java/com/example/refill/RefillServer.java",
            90,
            "limit above 100 is accepted",
            "The limit is never checked.",
            null,
            null);

    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_267, List.of(bug), null, Map.of()));
  }

  @Test
  void aCriterionOnlyInTheOverviewProseGetsALine() {
    // Python #268 round 1: the overview named "the issue's" requirement, the section never did.
    var overview =
        "Adds a small WSGI service under python/ for the garden plots waitlist. However, a"
            + " high-confidence finding shows the round offers out-of-borough applicants too — the"
            + " opposite of the issue's 'next in-borough applicant' requirement — and the"
            + " Dockerfile's runtime stage copies a wheel no stage builds.";

    var lines = unmet(ISSUE_256, List.of(), overview, Map.of());

    assertEquals(
        List.of(
            "Linked issue #256: An offer round offers each vacant plot to the next in-borough"
                + " applicant in line and records when the offer expires — the overview above"
                + " reports it unmet"),
        lines.lines());
    assertEquals(Set.of(), lines.pointed());
  }

  @Test
  void anOverviewSentenceNegatingAnyFulfilmentVerbCounts() {
    for (var verb : List.of("honored", "respected", "applied", "followed", "delivered")) {
      var overview = "The issue's 'declines two offers' rule is not " + verb + " by the code.";
      assertEquals(1, unmet(ISSUE_256, List.of(), overview, Map.of()).lines().size(), verb);
    }
  }

  @Test
  void anOverviewSentenceSharingHalfACriterionsWordsWithoutAQuoteCounts() {
    var overview =
        "The issue's criterion is not met: an applicant who declines two offers stays in place"
            + " instead of moving to the back of the waitlist.";

    assertEquals(
        List.of(
            "Linked issue #256: An applicant who declines two offers is moved to the back of the"
                + " waitlist — the overview above reports it unmet"),
        unmet(ISSUE_256, List.of(), overview, Map.of()).lines());
  }

  @Test
  void anOverviewSentenceThatDoesNotSayTheCriterionIsUnmetAddsNothing() {
    var met = "Implements the issue's 'next in-borough applicant' offer round.";
    var uncited = "The round does not offer plots to the next in-borough applicant in line.";
    // A contrast about something else, in another clause, does not reach the quote.
    var contrast =
        "Implements the issue's 'next in-borough applicant' offer round, but the import step is"
            + " not fast.";

    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), met, Map.of()));
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), uncited, Map.of()));
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), contrast, Map.of()));
    // A negation about something else in the clause does not make it unmet.
    var qualified =
        "The issue's 'declines two offers' rule is now implemented with no regressions.";
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), qualified, Map.of()));
    // "Not only" affirms, and "instead" is a contrast.
    var notOnly = "The issue's 'declines two offers' rule is not only implemented, it is exceeded.";
    var instead = "The issue's 'declines two offers' rule was instead implemented as a queue.";
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), notOnly, Map.of()));
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), instead, Map.of()));
    // An unmet word a negation turns into praise.
    var praise = "The issue's 'declines two offers' rule never fails and nothing is missing.";
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), praise, Map.of()));
    var notSimply =
        "The issue's 'declines two offers' rule is not simply implemented, it is improved.";
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), notSimply, Map.of()));
    // A negation in a clause about the summary's own text says nothing about the change.
    var notCovered =
        "The issue's 'next in-borough applicant' requirement is not covered by this walkthrough"
            + " section.";
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), notCovered, Map.of()));
  }

  @Test
  void oneSentenceCanNameSeveralUnmetCriteria() {
    var both =
        "The issue's \"the current week\" board listing is missing and its \"Sign-ups closed\""
            + " card for imminent shifts is not implemented.";

    var lines = unmet(ISSUE_260, List.of(), both, Map.of()).lines();

    assertEquals(2, lines.size(), lines.toString());
    assertTrue(lines.get(0).startsWith("Linked issue #260: The board lists"), lines.toString());
    assertTrue(lines.get(1).startsWith("Linked issue #260: Shifts that start"), lines.toString());
  }

  @Test
  void aClauseAboutTheSummarysOwnTextIsNotAnUnmetCriterion() {
    var meta =
        "The issue's 'next in-borough applicant' rule is implemented but the 'records when the"
            + " offer expires' wording is missing from the walkthrough.";

    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), meta, Map.of()));
  }

  @Test
  void aFindingEquallyCloseToTwoCriteriaIsTiedToNone() {
    var button =
        new Finding(
            RiskLevel.MEDIUM,
            "react/src/components/ShiftBoard.tsx",
            27,
            "Contrary to the linked issue, the Sign up button stays enabled",
            null,
            null,
            null);

    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_260, List.of(button), null, Map.of()));
  }

  @Test
  void aQuotedPassageKeepsItsSeparatorsInOneClause() {
    // Both quotes hold a comma; split inside the quote, neither half names the criterion.
    var quoted =
        "The issue's 'remaining spots, and a shift with no spots left' requirement is not met.";
    var full =
        "The issue's 'remaining spots, and a shift with no spots left shows \"Full\" with the Sign"
            + " up button disabled' requirement is not met.";

    for (var overview : List.of(quoted, full)) {
      var lines = unmet(ISSUE_260, List.of(), overview, Map.of()).lines();
      assertEquals(1, lines.size(), overview + " -> " + lines);
      assertTrue(
          lines.getFirst().startsWith("Linked issue #260: The board lists"), lines.toString());
    }
  }

  @Test
  void aSingleQuotedPassageMayHoldAnApostrophe() {
    var quoted = "The issue's 'the council's yearly export' ask is not met.";

    var lines = unmet(ISSUE_256, List.of(), quoted, Map.of()).lines();

    assertEquals(1, lines.size(), lines.toString());
    assertTrue(lines.getFirst().startsWith("Linked issue #256: Bulk import"), lines.toString());
  }

  @Test
  void aNegationPraisingTheUnmetWordAfterItIsNoUnmetCriterion() {
    var praise = "The issue's 'declines two offers' rule never fails to honor the waitlist.";

    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), praise, Map.of()));
    // "n't" is no praising negation: it still says the rule is not honored.
    var contracted = "The issue's 'declines two offers' rule isn't honored.";
    var lines = unmet(ISSUE_256, List.of(), contracted, Map.of()).lines();
    assertEquals(1, lines.size(), lines.toString());
    assertTrue(lines.getFirst().startsWith("Linked issue #256: An applicant"), lines.toString());
  }

  @Test
  void aSeparatorOutsideTheQuoteStillSplitsTheClause() {
    // The comma outside the quote still keeps the contrast in its own clause.
    var contrast =
        "The issue's 'remaining spots, and a shift with no spots left' wording is implemented, but"
            + " nothing else is met.";

    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_260, List.of(), contrast, Map.of()));
  }

  @Test
  void aQuoteSeveralCriteriaHoldGoesToTheClearBestOrToNone() {
    // Criteria 1 and 3 of #260 both hold "the Sign up button".
    var locked =
        "The issue's \"the Sign up button\" is not disabled for shifts that start in less than 2"
            + " hours.";
    var ambiguous = "The issue's \"the Sign up button\" requirement is not met.";

    var lines = unmet(ISSUE_260, List.of(), locked, Map.of()).lines();
    assertEquals(1, lines.size(), lines.toString());
    assertTrue(
        lines.getFirst().startsWith("Linked issue #260: Shifts that start"), lines.toString());
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_260, List.of(), ambiguous, Map.of()));
    var full =
        "The issue's \"the Sign up button\" is not disabled when a shift of the current week has no"
            + " remaining spots left.";
    var fullLines = unmet(ISSUE_260, List.of(), full, Map.of()).lines();
    assertEquals(1, fullLines.size(), fullLines.toString());
    assertTrue(
        fullLines.getFirst().startsWith("Linked issue #260: The board lists"),
        fullLines.toString());
  }

  @Test
  void anOverviewClauseSayingCannotOrContradictingCounts() {
    var cannot =
        "Coordinators cannot limit the board to a set of roles through configuration, despite the"
            + " issue's ask.";
    var contradicting =
        "The round, contradicting the issue's 'next in-borough applicant' rule, offers anyone.";

    assertEquals(1, unmet(ISSUE_260, List.of(), cannot, Map.of()).lines().size());
    assertTrue(
        unmet(ISSUE_260, List.of(), cannot, Map.of())
            .lines()
            .getFirst()
            .startsWith("Linked issue #260: Coordinators can limit the board"));
    assertEquals(1, unmet(ISSUE_256, List.of(), contradicting, Map.of()).lines().size());
  }

  @Test
  void aWalkthroughClauseNegatingItsVerbCountsButAStateDoesNot() {
    var negated =
        unmet(
            ISSUE_260,
            List.of(),
            null,
            Map.of(
                "react/src/components/ShiftBoard.tsx",
                "The board doesn't lock shifts that start within 2 hours."));
    var state =
        unmet(
            ISSUE_260,
            List.of(),
            null,
            Map.of(
                "react/src/components/ShiftBoard.tsx",
                "Locks the sign-up of shifts that are not yet started within 2 hours."));

    assertEquals(1, negated.lines().size(), negated.lines().toString());
    assertTrue(negated.lines().getFirst().startsWith("Linked issue #260: Shifts that start"));
    assertEquals(LinkedIssueCriteria.Lines.NONE, state);
    // A negated verb the criterion does not use describes the criterion met.
    assertEquals(
        LinkedIssueCriteria.Lines.NONE,
        unmet(
            ISSUE_260,
            List.of(),
            null,
            Map.of(
                "react/src/components/ShiftBoard.tsx",
                "Renders Sign-ups closed cards; the board does not allow sign-ups for locked"
                    + " shifts.")));
    for (var row :
        List.of(
            "The board won't lock shifts that start within 2 hours.",
            "The board can't lock shifts that start within 2 hours.",
            "The board can’t lock shifts that start within 2 hours.")) {
      assertEquals(
          1,
          unmet(ISSUE_260, List.of(), null, Map.of("react/src/components/ShiftBoard.tsx", row))
              .lines()
              .size(),
          row);
    }
    assertEquals(
        1,
        unmet(
                ISSUE_260,
                List.of(),
                null,
                Map.of(
                    "react/src/components/ShiftBoard.tsx",
                    "The board fails to lock shifts that start within 2 hours."))
            .lines()
            .size());
  }

  @Test
  void aCriterionTheModelOmittedButAWalkthroughRowMentionsGetsALine() {
    // React #273: criterion 3 never surfaced, though the ShiftBoard row says it is missing.
    var lines =
        unmet(
            ISSUE_260,
            List.of(),
            null,
            Map.of(
                "react/src/components/ShiftBoard.tsx",
                SHIFT_BOARD_ROW,
                "react/src/lib/reminders.ts",
                "ReminderService schedules a reminder on confirmSignup; no pre-shift delivery is"
                    + " shown."));

    // "no cancel control" in the same row, and "no pre-shift delivery", map to no criterion.
    assertEquals(
        List.of(
            "Linked issue #260: Shifts that start in less than 2 hours are locked: the Sign up"
                + " button is disabled and the card shows \"Sign-ups closed\". —"
                + " `react/src/components/ShiftBoard.tsx`: no lockout for imminent shifts"),
        lines.lines());
  }

  @Test
  void aCriterionAGapAlreadyNamesGetsNoSecondLine() {
    var gap =
        "Linked issue #256: an applicant who declines two offers is moved to the back of the"
            + " waitlist — no changed file declares or touches any move-to-back logic.";
    var overview =
        "However, the issue's criterion that an applicant who declines two offers is moved to the"
            + " back of the waitlist is not met.";

    assertEquals(
        LinkedIssueCriteria.Lines.NONE,
        LinkedIssueCriteria.unmet(ISSUE_256, List.of(gap), List.of(), overview, Map.of()));
  }

  @Test
  void eachCriterionGetsOneLineFromItsFirstSource() {
    var second =
        new Finding(
            RiskLevel.MEDIUM,
            "java/src/main/java/com/example/refill/RefillServer.java",
            95,
            "Linked issue #267: a limit above 100 is not rejected",
            null,
            null,
            null);
    var otherGaps =
        List.of(
            "The description says the queue is persistent, but it is in memory.",
            "Linked issue #267: `GET /history` rejects stores this instance is not configured for"
                + " with 400 — no store check.");
    var overview =
        "Adds a refill queue. The linked issue's \"limit above 100\" rule is not met, nor is its"
            + " 'refills' wording, and the \"auto refill cadence\" is missing.";
    var rows =
        Map.of(
            "java/src/main/java/com/example/refill/RefillServer.java",
            "Serves the endpoints; no rejection of a limit above 100.");

    var lines =
        LinkedIssueCriteria.unmet(ISSUE_267, otherGaps, List.of(LIMIT, second), overview, rows);

    assertEquals(1, lines.lines().size(), lines.lines().toString());
    assertTrue(lines.lines().getFirst().contains(LIMIT.title()), lines.lines().toString());
    assertEquals(Set.of(LIMIT), lines.pointed());

    // A gap naming the criterion leaves the finding its own bullet.
    var named =
        List.of("Linked issue #267: `GET /refills/next` rejects a limit above 100 with 413");
    assertEquals(
        LinkedIssueCriteria.Lines.NONE,
        LinkedIssueCriteria.unmet(ISSUE_267, named, List.of(LIMIT), null, rows));
  }

  @Test
  void anOverviewSentenceWithAQuoteNoCriterionHoldsAndTooFewSharedWordsAddsNothing() {
    var overview =
        "The issue's 'queue' and \"paper inbox\" asks are not met by the pharmacy service refill"
            + " flow.";

    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_267, List.of(), overview, Map.of()));
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_267, List.of(), "", Map.of()));
    assertEquals(
        LinkedIssueCriteria.Lines.NONE,
        unmet(ISSUE_267, List.of(), "The linked issue is not met.", Map.of()));
  }

  @Test
  void aFindingCitingTheIssueButNoCriterionAndAGapWithNoCriterionWordsTieNothing() {
    var vague =
        new Finding(
            RiskLevel.LOW, "pom.xml", 3, "The linked issue asks for a pinned JDK", "d", null, null);
    var wordless = List.of("Linked issue #267: #1 — see the queue.");

    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_267, List.of(vague), null, Map.of()));
    assertEquals(
        1,
        LinkedIssueCriteria.unmet(ISSUE_267, wordless, List.of(LIMIT), null, Map.of())
            .lines()
            .size());
  }

  @Test
  void withTwoLinkedIssuesNothingIsAdded() {
    var two = ISSUE_267.replace("linked to: #267", "linked to: #267, #300");

    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(two, List.of(LIMIT), null, Map.of()));
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet("", List.of(LIMIT), null, Map.of()));
  }

  @Test
  void aWalkthroughClauseIsEscapedInTheLine() {
    var lines =
        unmet(
                ISSUE_260,
                List.of(),
                null,
                Map.of(
                    "react/src/components/ShiftBoard.tsx",
                    "no lockout for imminent <b>shifts</b> | `x`"))
            .lines();

    assertEquals(1, lines.size(), lines.toString());
    assertTrue(
        lines.getFirst().endsWith("no lockout for imminent &lt;b>shifts&lt;/b> \\| &#96;x&#96;"),
        lines.toString());
  }

  @Test
  void theTaskListMarkIsNotPartOfACriterion() {
    var criteria = LinkedIssueCriteria.criteria(ISSUE_260);

    assertTrue(criteria.getFirst().startsWith("The board lists"), criteria.toString());
    assertTrue(criteria.getLast().startsWith("After signing up"), criteria.toString());
  }

  @Test
  void anAdverbOrNoLongerBetweenTheNegationAndTheVerbStillBindsIt() {
    for (var row :
        List.of(
            "The board does not actually lock shifts that start within 2 hours.",
            "The board no longer locks shifts that start within 2 hours.",
            "The board does not yet lock shifts that start within 2 hours.")) {
      assertEquals(
          1,
          unmet(ISSUE_260, List.of(), null, Map.of("react/src/components/ShiftBoard.tsx", row))
              .lines()
              .size(),
          row);
    }
  }

  @Test
  void theMoreNotShownLineAndTheBodyAreNotCriteria() {
    assertEquals(4, LinkedIssueCriteria.criteria(ISSUE_256).size());
    assertEquals(4, LinkedIssueCriteria.criteria(ISSUE_260).size());
    assertFalse(
        LinkedIssueCriteria.criteria(ISSUE_260).stream().anyMatch(c -> c.contains("paper")));
  }

  @Test
  void wordsOfOneFamilyMatch() {
    assertTrue(LinkedIssueCriteria.family("lock", "lockout"));
    assertTrue(LinkedIssueCriteria.family("lockout", "lock"));
    assertTrue(LinkedIssueCriteria.family("shift", "shift"));
    // A short word does not start a family: "sign" is four letters, "up" is not one.
    assertFalse(LinkedIssueCriteria.family("pre", "present"));
    assertFalse(LinkedIssueCriteria.family("cancel", "control"));
  }

  @Test
  void anIntensifierBetweenTheNegationAndTheUnmetWordStillPraises() {
    for (var praise :
        List.of(
            "The issue's 'declines two offers' rule is not at all missing.",
            "The issue's 'declines two offers' rule never really fails.",
            "The issue's 'declines two offers' rule is not even slightly missing.")) {
      assertEquals(
          LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_256, List.of(), praise, Map.of()), praise);
    }
  }

  @Test
  void aNotMetWordReachesOnlyTheQuotesItsPartOfTheClauseSpeaksOf() {
    // The listing is missing, the quoted card renders: the card's criterion is not reported.
    var renders =
        "The issue's current-week listing rule is missing and the 'Sign-ups closed' card renders.";
    var reverse =
        "The issue's 'Sign-ups closed' card renders and the current-week listing is missing.";
    for (var overview : List.of(renders, reverse)) {
      var lines = unmet(ISSUE_260, List.of(), overview, Map.of()).lines();
      assertFalse(
          lines.stream().anyMatch(l -> l.startsWith("Linked issue #260: Shifts that start")),
          overview + " -> " + lines);
    }
    // Two quoted subjects of one verb are both reported.
    var both =
        "The issue's \"Sign-ups closed\" card and \"Sign up button disabled\" state are missing.";
    var lines = unmet(ISSUE_260, List.of(), both, Map.of()).lines();
    assertEquals(2, lines.size(), lines.toString());
    assertTrue(lines.get(0).startsWith("Linked issue #260: The board lists"), lines.toString());
    assertTrue(lines.get(1).startsWith("Linked issue #260: Shifts that start"), lines.toString());
    // A subject of one word with no quote reads on as well; a longer part stops the reading.
    var three =
        "The issue's \"Sign-ups closed\" card and lockout and \"Sign up button disabled\" state"
            + " are missing.";
    assertEquals(2, unmet(ISSUE_260, List.of(), three, Map.of()).lines().size(), three);
    var stopped =
        "The issue's \"Sign-ups closed\" card and the board listing and the import are missing.";
    assertEquals(LinkedIssueCriteria.Lines.NONE, unmet(ISSUE_260, List.of(), stopped, Map.of()));
  }

  @Test
  void lackingAndYetToBeImplementedSayACriterionIsUnmet() {
    for (var overview :
        List.of(
            "The issue's 'Sign-ups closed' card for imminent shifts is lacking.",
            "The issue's 'Sign-ups closed' card has yet to be implemented.")) {
      var lines = unmet(ISSUE_260, List.of(), overview, Map.of()).lines();
      assertEquals(1, lines.size(), overview + " -> " + lines);
      assertTrue(lines.getFirst().startsWith("Linked issue #260: Shifts that start"), overview);
    }
  }

  @Test
  void aQuotedPassageHoldingAFullStopStaysInOneSentence() {
    // "Sign-ups closed. " inside the quote is no sentence end, so the clause keeps the quote.
    var issue =
        ISSUE_260.replace(
            "the card shows \"Sign-ups closed\".",
            "the card reads: Sign-ups closed. Try another shift.");
    var overview = "The issue's 'Sign-ups closed. Try another shift' card is missing.";

    var lines = unmet(issue, List.of(), overview, Map.of()).lines();

    assertEquals(1, lines.size(), lines.toString());
    assertTrue(
        lines.getFirst().startsWith("Linked issue #260: Shifts that start"), lines.toString());
  }

  @Test
  void aQuoteNoCriterionHoldsOrOneRepeatedAddsNoSecondLineAcrossSentences() {
    // A full stop before and after the quotes ends a sentence; a quote no criterion holds and the
    // same quote again add nothing.
    var overview =
        "Adds the shift board. The issue's 'Sign-ups closed' card is missing and 'no such passage'"
            + " and the 'Sign-ups closed' card is missing. It also adds 'a footer note'. Done.";

    var lines = unmet(ISSUE_260, List.of(), overview, Map.of()).lines();

    assertEquals(1, lines.size(), lines.toString());
    assertTrue(
        lines.getFirst().startsWith("Linked issue #260: Shifts that start"), lines.toString());
  }

  /** A gap the section does not render holds back no criterion line (#1127 with #1093). */
  @Test
  void aGapCreditingTheReviewThatNoFindingBacksDoesNotHoldBackTheCriterionLine() {
    var credited =
        "Linked issue #256: An applicant who declines two offers is moved to the back of the"
            + " waitlist — a review pass flags that the code keeps them in place.";
    assertTrue(DescriptionGapScreen.creditsReview(credited));
    var result =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            RiskLevel.LOW,
            ReviewState.APPROVE,
            false,
            "",
            List.of(),
            List.of(),
            0);
    var summary =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            "ok",
            "The issue's 'declines two offers' rule is not honored.",
            List.of(credited));

    var rendered =
        new PrSummaryGenerator(false)
            .generate(
                4,
                50,
                0,
                List.of(),
                summary,
                result,
                new PrSummaryGenerator.DescriptionSources("", Set.of(), ISSUE_256));

    assertFalse(rendered.contains("a review pass flags"), rendered);
    assertTrue(
        rendered.contains(
            "- Linked issue #256: An applicant who declines two offers is moved to the back of the"
                + " waitlist — the overview above reports it unmet"),
        rendered);
  }

  /** The summary renders the lines in place of the finding bullets they point at. */
  @Test
  void theSectionNamesTheIssueAndItsCriterionAndTheLineIsNotCarried() {
    var generator = new PrSummaryGenerator(false);
    var linkedLimit =
        new Finding(
            LIMIT.risk(),
            LIMIT.confidence(),
            LIMIT.file(),
            LIMIT.line(),
            "Contrary to the linked issue, a limit above 100 is accepted",
            LIMIT.description(),
            null,
            null);
    var persistence =
        new Finding(
            RiskLevel.MEDIUM,
            "java/src/main/java/com/example/refill/RefillQueue.java",
            12,
            "The PR description says refills persist, but the queue is in memory",
            "d",
            null,
            null);
    var result =
        new ReviewResult(
            List.of(linkedLimit, persistence),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            false,
            "",
            List.of(),
            List.of(),
            0);
    // No claim text: every description finding is named, so the pointed one must be held back.
    var sources = new PrSummaryGenerator.DescriptionSources("", Set.of(), ISSUE_267);

    var rendered =
        generator.generate(
            16,
            596,
            0,
            List.of(),
            new ReviewResponse.Summary(1, 0, 1, 0, 0, "ok", "Adds a refill queue.", List.of()),
            result,
            sources);

    assertTrue(
        rendered.contains(
            "- Linked issue #267: "
                + CRITERION_267_4
                + LinkedIssueCriteria.FINDING_SEPARATOR
                + linkedLimit.title()),
        rendered);
    assertFalse(
        rendered.contains("- " + PrSummaryGenerator.GAP_FINDING_PREFIX + linkedLimit.title()),
        rendered);
    assertTrue(
        rendered.contains("- " + PrSummaryGenerator.GAP_FINDING_PREFIX + persistence.title()),
        rendered);
    assertTrue(rendered.contains(PrSummaryGenerator.GAPS_INTRO_WITH_ISSUE), rendered);

    // The next round does not carry the line as a gap: the finding is carried on its own, so once
    // it is fixed on a moved head the line goes with it.
    var conversation =
        List.of(
            new IssueComment(
                PrSummaryGenerator.SUMMARY_MARKER + "\n" + rendered,
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
    var bot = BotIdentity.of("thrillhousebot[bot]");
    assertEquals(List.of(), DescriptionGapCarryover.previousGaps(conversation, bot));
    var fixed =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            RiskLevel.LOW,
            ReviewState.APPROVE,
            false,
            "",
            List.of(),
            List.of(),
            0);
    var round2 =
        generator.generate(
            16,
            600,
            2,
            List.of(),
            DescriptionGapCarryover.of(conversation, bot, ISSUE_267)
                .apply(new ReviewResponse.Summary(0, 0, 0, 0, 0, "ok", "Adds a queue.", List.of())),
            fixed,
            sources);
    assertFalse(round2.contains(PrSummaryGenerator.GAPS_HEADING), round2);
  }
}
