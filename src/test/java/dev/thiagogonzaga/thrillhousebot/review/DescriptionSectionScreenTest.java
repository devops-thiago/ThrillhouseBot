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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Description vs. Implementation section on the round 17 cases (#1074): a finding is named
 * there only when it quotes or paraphrases the PR text or the linked issue, a bullet saying the
 * description omits files it names is dropped, and on an unchanged head the findings the previous
 * round named stay named while open.
 */
class DescriptionSectionScreenTest {

  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));

  /** ThrillhouseBot-test#251's PR text (angular). */
  private static final String ANGULAR_251 =
      """
      Title: feat(angular): add the gym booking board
      Description:
      - **Booking board** (`booking-board.component.ts`): lists the week's classes with instructor \
      and remaining spots. The board refreshes itself every 60 seconds so spot counts stay current \
      while it is open on the front-desk screen.
      - **Waitlist promotion**: when a spot frees up, the next member by tier priority and join time \
      is promoted and gets a push notification; if the notification is not delivered they keep \
      their place.
      - **Container**: two-stage Dockerfile (Node build, nginx runtime).
      """;

  /** ThrillhouseBot-test#244's PR text (python). */
  private static final String PYTHON_244 =
      """
      Title: feat(python): add the classifieds service
      Description:
      Adds `python/classifieds`, a small service for used bicycle listings.
      - CLI entry point, Dockerfile, configuration docs in `python/docs/CONFIG-PYTHON.md`, and a \
      `python-ci` workflow that runs the unit tests on PRs touching `python/`.
      """;

  private static Finding finding(String title, String description) {
    return new Finding(RiskLevel.HIGH, "angular/src/app/x.ts", 10, title, description, null, null);
  }

  private static PrSummaryGenerator.DescriptionSources sources(String claimText) {
    return new PrSummaryGenerator.DescriptionSources(claimText, Set.of());
  }

  // Rule 1: a named finding quotes or paraphrases the PR text or the linked issue.

  @Test
  void aDescriptionFindingQuotingThePrTextIsNamed() {
    var refresh =
        finding(
            "Board never refreshes; no 60-second refresh exists in the component",
            "The PR description states: \"The board refreshes itself every 60 seconds so spot"
                + " counts stay current while it is open on the front-desk screen.\"");

    assertEquals(
        List.of(refresh),
        PrSummaryGenerator.descriptionFindings(List.of(), List.of(refresh), sources(ANGULAR_251)));
  }

  @Test
  void aFindingThatQuotesOnlyTheCodeIsNotNamedEvenWhenAGapCollapsedOntoIt() {
    // java #245 / angular #251: an SQL injection and a Dockerfile path were listed as gaps.
    var injection =
        finding(
            "SQL injection: patient name from query string is concatenated into the search SQL",
            "searchByPatientName builds the query by string concatenation: 'String sql = \"SELECT"
                + " \" + COLUMNS' and the PR description says nothing about escaping.");
    var docker =
        finding(
            "COPY references dist/gym-board but the build outputs dist/gym-booking-board",
            "Line 12 copies the built bundle from /app/dist/gym-board/browser, contrary to the PR"
                + " description's container.");

    assertEquals(
        List.of(),
        PrSummaryGenerator.descriptionFindings(
            List.of(injection), List.of(injection, docker), sources(ANGULAR_251)));
  }

  @Test
  void aFindingParaphrasingThePrTextIsNamed() {
    var promotion =
        finding(
            "Failed notification rejects promoteNext instead of keeping the member queued",
            "Contrary to the PR description, when the push notification is not delivered the"
                + " member does not keep their place.");

    assertEquals(
        List.of(promotion),
        PrSummaryGenerator.descriptionFindings(
            List.of(), List.of(promotion), sources(ANGULAR_251)));
  }

  @Test
  void aQuoteThePrTextDoesNotHoldIsNotAnEcho() {
    assertFalse(
        DescriptionGapScreen.echoes(
            "The comment promises 'loads every class scheduled in the week'.", ANGULAR_251));
    assertTrue(
        DescriptionGapScreen.echoes("It is not 'open on the front-desk screen'.", ANGULAR_251));
    // A single quoted word is too little to judge, even one the PR text holds.
    assertFalse(DescriptionGapScreen.echoes("The 'board' stalls.", ANGULAR_251));
  }

  @Test
  void aShortPrSentenceIsNotEnoughToParaphrase() {
    assertFalse(DescriptionGapScreen.echoes("adds a Dockerfile", "Adds a Dockerfile."));
  }

  @Test
  void withNoClaimTextKnownEveryDescriptionFindingIsNamed() {
    var docker =
        finding("COPY path is wrong", "Contrary to the PR description's container, it fails.");

    assertEquals(
        List.of(docker),
        PrSummaryGenerator.descriptionFindings(
            List.of(), List.of(docker), PrSummaryGenerator.DescriptionSources.NONE));
    assertEquals(
        List.of(docker),
        PrSummaryGenerator.descriptionFindings(
            List.of(), List.of(docker), new PrSummaryGenerator.DescriptionSources(null, Set.of())));
  }

  // Rule 2: an omission bullet naming what the PR text names is dropped.

  @Test
  void anOmissionTheDescriptionDoesNotHaveIsDropped() {
    // python #244 (6125873566): the body names the workflow, the Dockerfile and the config docs.
    var gap =
        "The description's summary covers the classifieds component but not all of the PR's"
            + " scope, which also adds the python-ci workflow, Dockerfile, config docs and a docs"
            + " renaming of README.md.";

    assertTrue(DescriptionGapScreen.falseOmission(gap, PYTHON_244));
    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(gap), PYTHON_244, ""));
  }

  @Test
  void anOmissionCuedByNoIsJudged() {
    // scala #297 R2 (#1206): carried although the edited body names the Dockerfile.
    var body =
        """
        Title: feat(scala): add cold-chain excursion tracker
        Description:
        Adds a small Scala service that tracks temperature excursions for refrigerated shipments.
        - Includes a Dockerfile, CI workflow and `docs/CONFIG-SCALA.md`.
        """;
    var gap =
        "The PR description provides no coverage of most of the change set: it describes only the"
            + " excursion tracker feature while the scope is 16 files including a new Dockerfile,"
            + " CI workflow, build config, config documentation and a full test suite.";

    assertTrue(DescriptionGapScreen.falseOmission(gap, body));
    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(gap), body, ""));
  }

  @Test
  void anOmissionNamingAPathTheDescriptionLacksIsKept() {
    var gap = "The description never mentions the new `python/migrations/` directory.";

    assertFalse(DescriptionGapScreen.falseOmission(gap, PYTHON_244));
    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), PYTHON_244, ""));
  }

  @Test
  void anOmissionNamedByPathIsMatchedOnItsLastSegment() {
    assertTrue(
        DescriptionGapScreen.falseOmission(
            "The description omits docs/CONFIG-PYTHON.md and the classifieds/ package.",
            "Docs live in CONFIG-PYTHON.md; the code is in classifieds."));
  }

  @Test
  void aNameInsideAnotherWordIsNotTheFileNamed() {
    assertFalse(
        DescriptionGapScreen.falseOmission(
            "The description never mentions the new `build/` output directory.",
            "Run `mvn verify` to rebuild and test."));
    assertFalse(
        DescriptionGapScreen.falseOmission(
            "The description never mentions the new `rate/` retry directory.",
            "Prices are accurate to the cent."));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The description fails to mention the `python-ci` workflow.",
        "The description leaves out the `python-ci` workflow.",
        "The description is missing the `python-ci` workflow.",
        "The PR body skips the `python-ci` workflow.",
        "The description says nothing about the `python-ci` workflow.",
        "The description gives no account of the `python-ci` workflow.",
        "The description claims a build cache; beyond it the change adds a Dockerfile."
      })
  void otherWordingsOfAnOmissionAreJudged(String gap) {
    assertTrue(DescriptionGapScreen.falseOmission(gap, PYTHON_244));
  }

  @Test
  void aMisclaimOrAMentionOfANonFileIsNotAnOmission() {
    // What the description says, then a negation: a misclaim the section exists to list.
    var misclaim = "The description says the Dockerfile does not pin its base image.";
    assertFalse(DescriptionGapScreen.falseOmission(misclaim, PYTHON_244));
    assertEquals(List.of(misclaim), DescriptionGapScreen.screen(List.of(misclaim), PYTHON_244, ""));
    // A backticked setting or the word "license" is not a file the description omits.
    assertFalse(
        DescriptionGapScreen.falseOmission(
            "The description never mentions that the `RATE_LIMIT` env var is clamped to 5.",
            "Reads `RATE_LIMIT` from the environment."));
    assertFalse(
        DescriptionGapScreen.falseOmission(
            "The description never mentions the license change.", "The project is MIT license."));
    // A slash pair is prose, not a path.
    assertFalse(
        DescriptionGapScreen.falseOmission(
            "The description never mentions the on/off state of the cache and/or the retry switch.",
            "Turns the cache off or on."));
    // A path of two slashes or more is one.
    assertTrue(
        DescriptionGapScreen.falseOmission(
            "The description never mentions src/app/config.ts.", "Adds a typed config.ts."));
    // A directory ending its sentence is named; a dotted file inside it is not the directory.
    assertTrue(
        DescriptionGapScreen.falseOmission(
            "The description never mentions the new docs/.", "Adds settings under docs."));
    assertFalse(
        DescriptionGapScreen.falseOmission(
            "The description never mentions the new docs/.env file.", "Adds settings under docs."));
    // A README and a code file are files too.
    assertTrue(
        DescriptionGapScreen.falseOmission(
            "The description never mentions the new README and the App.tsx retry helper.",
            "Adds a README and a retry helper in App.tsx."));
    // One file matched as a path and as a file name is one name, and the PR text holds it.
    assertTrue(
        DescriptionGapScreen.falseOmission(
            "The description never mentions python/docs/CONFIG-PYTHON.md.", PYTHON_244));
  }

  @Test
  void onlyAnOmissionNamingFilesIsJudged() {
    // Not an omission of the description, no file named, or no PR text to judge against.
    assertFalse(DescriptionGapScreen.falseOmission("The Dockerfile is missing.", PYTHON_244));
    assertFalse(
        DescriptionGapScreen.falseOmission(
            "The description says caching but no cache is added.", PYTHON_244));
    assertFalse(
        DescriptionGapScreen.falseOmission(
            "The description never mentions the CSV export.", PYTHON_244));
    assertFalse(
        DescriptionGapScreen.falseOmission("The description never mentions the Dockerfile.", "  "));
    assertTrue(
        DescriptionGapScreen.falseOmission(
            "The description covers the service, beyond it the change adds a Dockerfile.",
            PYTHON_244));
  }

  // Rule 3: the previous round's named findings, read back from its summary.

  private static IssueComment summaryComment(String body) {
    return new IssueComment(
        PrSummaryGenerator.SUMMARY_MARKER + "\n" + body,
        new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]"));
  }

  @Test
  void thePreviousSummarysNamedFindingsAreReadBack() {
    var refresh = finding("Board never refreshes", "d");
    var body =
        PrSummaryGenerator.SUMMARY_HEADING
            + "\n\n"
            + PrSummaryGenerator.GAPS_HEADING
            + "\nThe PR description does not fully match the change:\n"
            + "- Linked issue #227: booking closes 2 hours before a class\n"
            + "- "
            + PrSummaryGenerator.findingBullet(refresh)
            + "\n\n### Changes Overview\n- "
            + PrSummaryGenerator.GAP_FINDING_PREFIX
            + "not in the section\n";

    assertEquals(
        Set.of(PrSummaryGenerator.findingBullet(refresh)),
        DescriptionGapCarryover.previousFindingBullets(List.of(summaryComment(body)), BOT));
    assertEquals(Set.of(), DescriptionGapCarryover.previousFindingBullets(List.of(), BOT));
    // A section that runs to the end of the comment.
    assertEquals(
        Set.of(PrSummaryGenerator.findingBullet(refresh)),
        DescriptionGapCarryover.previousFindingBullets(
            List.of(
                summaryComment(
                    PrSummaryGenerator.GAPS_HEADING
                        + "\n- "
                        + PrSummaryGenerator.findingBullet(refresh))),
            BOT));
    assertEquals(
        Set.of(),
        DescriptionGapCarryover.previousFindingBullets(
            List.of(summaryComment(PrSummaryGenerator.SUMMARY_HEADING + "\n")), BOT));
  }

  @Test
  void aFindingThePreviousRoundNamedStaysNamedThoughNothingElseWouldNameIt() {
    // rust #255 / scala #248 / react #249: a re-review of the same head dropped a bullet. The
    // finding quotes the PR text but no longer says "the description".
    var refresh =
        finding(
            "Board never refreshes",
            "Nothing sets an interval, though the board 'refreshes itself every 60 seconds'.");
    var withoutCarry =
        PrSummaryGenerator.descriptionFindings(List.of(), List.of(refresh), sources(ANGULAR_251));
    var carried =
        PrSummaryGenerator.descriptionFindings(
            List.of(),
            List.of(refresh),
            new PrSummaryGenerator.DescriptionSources(
                ANGULAR_251, Set.of(PrSummaryGenerator.findingBullet(refresh))));

    assertEquals(List.of(), withoutCarry);
    assertEquals(List.of(refresh), carried);
  }
}
