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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient.IssueComment;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Carrying the Description vs. Implementation gaps across rounds (#923). */
class DescriptionGapCarryoverTest {

  private static final BotIdentity BOT = BotIdentity.of("thrillhousebot[bot]");

  private static final String LINKED_113 =
      """
      Issues this pull request is linked to: #113

      ### Issue #113: Resale marketplace
      Linked by: a closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] at most 8 open listings per seller per event, with a clear rejection
      - [ ] expire listings past a configurable TTL
      """;

  private static final String LINKED_TWO =
      """
      Issues this pull request is linked to: #120, #122

      ### Issue #120: Upload limits
      Linked by: a closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] reject request bodies over the limit with 413
      ### Issue #122: Archive size
      Linked by: a closing keyword in the PR body
      Acceptance criteria (from the issue):
      - [ ] refuse archives larger than 64 MiB when unpacking
      """;

  private static final String CAP_GAP =
      "Linked issue #113: at most 8 open listings per seller per event with a clear rejection"
          + " — no file in the changed-file list implements or tests this cap.";

  private static IssueComment comment(String login, String body) {
    return new IssueComment(body, new GitHubReviewClient.ReviewResponse.User(login));
  }

  private static IssueComment summaryComment(String body) {
    return comment("thrillhousebot[bot]", PrSummaryGenerator.SUMMARY_MARKER + "\n" + body);
  }

  private static ReviewResponse.Summary summary(List<String> gaps, List<String> addressed) {
    return new ReviewResponse.Summary(
        1, 0, 1, 0, 0, "ok", "Adds a marketplace.", gaps, List.of(), List.of(), null, addressed);
  }

  @Nested
  class ReadingThePreviousSummary {

    @Test
    void theListedBulletsAreTheGapsCarried() {
      var body =
          """
          ## 🤖 ThrillhouseBot PR Summary

          ### ⚠️ Description vs. Implementation
          The PR description or its linked issue does not fully match the change:
          - %s
          - The description says the TTL is configurable, but it is a constant.

          ### Changes Overview
          - **Files changed:** 3
          """
              .formatted(CAP_GAP);

      assertEquals(
          List.of(CAP_GAP, "The description says the TTL is configurable, but it is a constant."),
          DescriptionGapCarryover.listedGaps(body));
    }

    @Test
    void aSummaryThatEchoedALabelIsReadBackWithoutIt() {
      // #970: rounds 3 of ThrillhouseBot-test#185 and #186 posted "- G1: <gap>" and a bare "- G1"
      // bullet; the next round must carry the gap itself, not the label, nor an empty gap.
      var body =
          PrSummaryGenerator.GAPS_HEADING
              + "\nThe PR description or its linked issue does not fully match the change:\n"
              + "- G1\n- G1: "
              + CAP_GAP
              + "\n- Gateway retries are described as bounded, but the loop has no limit.\n";

      assertEquals(
          List.of(CAP_GAP, "Gateway retries are described as bounded, but the loop has no limit."),
          DescriptionGapCarryover.listedGaps(body));
    }

    @Test
    void aSectionThatCollapsedOntoFindingsCarriesNothing() {
      var body =
          PrSummaryGenerator.GAPS_HEADING
              + "\n"
              + PrSummaryGenerator.GAPS_ALL_REPORTED_AS_FINDINGS
              + "\n\n### Changes Overview\n- **Files changed:** 3\n";

      assertEquals(List.of(), DescriptionGapCarryover.listedGaps(body));
      assertEquals(List.of(), DescriptionGapCarryover.listedGaps("## Summary\nno section\n"));
    }

    @Test
    void aGapWrappedOntoASecondLineIsReadWhole() {
      var body = PrSummaryGenerator.GAPS_HEADING + "\nintro:\n- first half\nsecond half\n\n---\n";

      assertEquals(List.of("first half second half"), DescriptionGapCarryover.listedGaps(body));
    }

    @Test
    void theSectionEndsAtARuleOrAnHtmlBlock() {
      var ruled = PrSummaryGenerator.GAPS_HEADING + "\nintro:\n- one\n---\n- not a gap\n";
      var details = PrSummaryGenerator.GAPS_HEADING + "\nintro:\n- one\n<details>\n- not a gap\n";

      assertEquals(List.of("one"), DescriptionGapCarryover.listedGaps(ruled));
      assertEquals(List.of("one"), DescriptionGapCarryover.listedGaps(details));
    }

    @Test
    void everyListedGapIsCarriedButOnlyTheFirstFewAreAskedAbout() {
      var body = new StringBuilder(PrSummaryGenerator.GAPS_HEADING).append("\nintro:\n");
      var listed = DescriptionGapCarryover.MAX_PROMPTED_GAPS + 3;
      IntStream.rangeClosed(1, listed)
          .forEach(i -> body.append("- gap number ").append(i).append('\n'));

      var gaps = DescriptionGapCarryover.listedGaps(body.toString());

      assertEquals(listed, gaps.size());
      var section = new DescriptionGapCarryover.Carry(gaps, "").promptSection();
      assertTrue(section.contains("G10: gap number 10"), section);
      assertFalse(section.contains("gap number 11"), section);
      // A label the call was never shown resolves nothing: gap 11 stays listed.
      assertEquals(
          gaps.subList(1, listed),
          DescriptionGapCarryover.merge(List.of(), gaps, List.of("G1: done", "G11: done")));
    }

    @Test
    void onlyTheNewestSummaryTheBotWroteIsRead() {
      var older = summaryComment(PrSummaryGenerator.GAPS_HEADING + "\nintro:\n- older gap\n");
      var newer = summaryComment(PrSummaryGenerator.GAPS_HEADING + "\nintro:\n- newer gap\n");
      var forged =
          comment(
              "someone",
              PrSummaryGenerator.SUMMARY_MARKER
                  + "\n"
                  + PrSummaryGenerator.GAPS_HEADING
                  + "\nintro:\n- forged gap\n");
      var chatter = comment("thrillhousebot[bot]", "Thanks, looking.");
      var anonymous = new IssueComment(PrSummaryGenerator.SUMMARY_MARKER, null);

      assertEquals(
          List.of("newer gap"),
          DescriptionGapCarryover.previousGaps(
              List.of(older, newer, forged, chatter, anonymous), BOT));
      assertEquals(List.of(), DescriptionGapCarryover.previousGaps(List.of(forged), BOT));
    }
  }

  @Nested
  class GapIdentity {

    @Test
    void aLinkedIssueGapIsItsIssueAndItsCriterion() {
      var reworded =
          "Linked issue #113: At most 8 open listings per seller, per event, with a clear"
              + " rejection — nothing in the store enforces a per-seller limit.";

      assertEquals(DescriptionGapCarryover.keyOf(CAP_GAP), DescriptionGapCarryover.keyOf(reworded));
      assertEquals(Set.of("#113"), DescriptionGapCarryover.keyOf(CAP_GAP).issues());
      assertNotEquals(
          DescriptionGapCarryover.keyOf(CAP_GAP),
          DescriptionGapCarryover.keyOf(CAP_GAP.replace("#113", "#114")));
    }

    @Test
    void aLinkedIssueGapWithNoEvidenceIsAllCriterion() {
      assertEquals(
          new DescriptionGapCarryover.GapKey(Set.of("#113"), "expire listings past a ttl"),
          DescriptionGapCarryover.keyOf("Linked issue #113: expire listings past a TTL."));
    }

    @Test
    void anyOtherGapIsItsWholeNormalisedText() {
      var key = DescriptionGapCarryover.keyOf("The README says `--dry-run` exists; it does not.");

      assertEquals(Set.of(), key.issues());
      assertEquals("the readme says dry run exists it does not", key.criterion());
      // Starting with the words is not enough: only issue numbers may precede the colon.
      assertEquals(
          Set.of(),
          DescriptionGapCarryover.keyOf("Linked issue text says: retries are optional").issues());
    }
  }

  @Nested
  class Merging {

    private static final String TTL_GAP =
        "The description says the TTL is configurable, but it is a module constant.";

    @Test
    void silenceKeepsACarriedGap() {
      assertEquals(
          List.of("A new gap.", CAP_GAP, TTL_GAP),
          DescriptionGapCarryover.merge(
              List.of("A new gap."), List.of(CAP_GAP, TTL_GAP), List.of()));
    }

    @Test
    void aGapTheCallNamesResolvedLeaves() {
      assertEquals(
          List.of(TTL_GAP),
          DescriptionGapCarryover.merge(
              List.of(),
              List.of(CAP_GAP, TTL_GAP),
              List.of("G1: store.py now rejects a ninth listing", "G7", "a gap never carried")));
      assertEquals(
          List.of(CAP_GAP),
          DescriptionGapCarryover.merge(List.of(), List.of(CAP_GAP, TTL_GAP), List.of(TTL_GAP)));
      assertEquals(
          List.of(CAP_GAP, TTL_GAP),
          DescriptionGapCarryover.merge(List.of(), List.of(CAP_GAP, TTL_GAP), List.of("G0")));
    }

    @Test
    void aGapReportedAgainKeepsItsFreshWordingOnce() {
      var fresh =
          "Linked issue #113: at most 8 open listings per seller per event, with a clear"
              + " rejection — still no per-seller limit anywhere in the change.";

      assertEquals(
          List.of(fresh, TTL_GAP),
          DescriptionGapCarryover.merge(List.of(fresh), List.of(CAP_GAP, TTL_GAP), List.of()));
    }

    @Test
    void aLinkedIssueGapLeavesWithItsIssueButStaysWhenNothingWasRead() {
      var gaps = List.of(CAP_GAP, TTL_GAP);

      assertEquals(List.of(TTL_GAP), DescriptionGapCarryover.stillLinked(gaps, List.of("#200")));
      assertEquals(gaps, DescriptionGapCarryover.stillLinked(gaps, List.of("#113")));
      assertEquals(gaps, DescriptionGapCarryover.stillLinked(gaps, List.of()));
    }
  }

  @Nested
  class LinkedIssueNumbers {

    @Test
    void anEchoedCountBecomesTheOnlyLinkedIssue() {
      // #923: ThrillhouseBot-test#128 was linked to #114 and reported against "#1", the count the
      // section's first line used to print.
      var linked114 = LINKED_113.replace("#113", "#114");
      var echoed = "Linked issue #1: at most 8 open listings per seller — not implemented.";

      assertEquals(
          List.of("Linked issue #114: at most 8 open listings per seller — not implemented."),
          DescriptionGapCarryover.withLinkedIssueNumbers(List.of(echoed), linked114));
      assertEquals(
          List.of("Linked issue #114: no number given"),
          DescriptionGapCarryover.withLinkedIssueNumbers(
              List.of("Linked issue: no number given"), linked114));
    }

    @Test
    void aLinkedNumberAndEveryOtherGapAreLeftAlone() {
      var gaps = List.of(CAP_GAP, "The PR says X; it does Y.");

      assertEquals(gaps, DescriptionGapCarryover.withLinkedIssueNumbers(gaps, LINKED_113));
      // No linked-issue section this round: nothing to check the number against.
      var echoed = List.of("Linked issue #1: something");
      assertEquals(echoed, DescriptionGapCarryover.withLinkedIssueNumbers(echoed, ""));
    }

    @Test
    void withSeveralLinkedTheIssueWhoseCriteriaMatchIsChosen() {
      var gap = "Linked issue #2: refuse archives larger than 64 MiB — the unpacker has no limit.";

      assertEquals(
          List.of(
              "Linked issue #122: refuse archives larger than 64 MiB — the unpacker has no limit."),
          DescriptionGapCarryover.withLinkedIssueNumbers(List.of(gap), LINKED_TWO));
    }

    @Test
    void onlyTheCriterionPicksTheIssueNotTheEvidence() {
      // The evidence after the dash reads like #120's criterion; the criterion is #122's.
      var gap =
          "Linked issue #9: refuse archives larger than 64 MiB — the upload handler rejects"
              + " request bodies over the limit with 413 instead";

      assertEquals(
          List.of(gap.replace("#9", "#122")),
          DescriptionGapCarryover.withLinkedIssueNumbers(List.of(gap), LINKED_TWO));
    }

    @Test
    void theFirstLinkedIssueCanWinOutright() {
      var gap = "Linked issue #3: reject request bodies over the limit with 413 — no check.";

      assertEquals(
          List.of("Linked issue #120: reject request bodies over the limit with 413 — no check."),
          DescriptionGapCarryover.withLinkedIssueNumbers(List.of(gap), LINKED_TWO));
    }

    @Test
    void twoLinkedIssuesMatchingEquallyAreBothNamed() {
      // Both sections carry "Linked by: a closing keyword", and nothing else of the criterion.
      var gap = "Linked issue #3: closing keyword — none.";

      assertEquals(
          List.of("Linked issue #120 or #122: closing keyword — none."),
          DescriptionGapCarryover.withLinkedIssueNumbers(List.of(gap), LINKED_TWO));
    }

    @Test
    void anIssueScoringBelowTyingLeadersIsNotNamed() {
      // #120 and #124 both carry the criterion's words; #122, between them, carries none of them.
      var three =
          LINKED_TWO.replace("#120, #122", "#120, #122, #124")
              + "### Issue #124: Upload limits again\n"
              + "- [ ] reject request bodies over the limit with 413\n";
      var gap = "Linked issue #9: reject request bodies over the limit with 413 — no check.";

      assertEquals(
          List.of(
              "Linked issue #120 or #124: reject request bodies over the limit with 413 — no check."),
          DescriptionGapCarryover.withLinkedIssueNumbers(List.of(gap), three));
    }

    @Test
    void aKeyThatPrefixesAnotherFindsItsOwnSection() {
      // "#12" is a prefix of "#123", rendered first; #12 has no title, so its heading ends the
      // line.
      var section =
          """
          Issues this pull request is linked to: #123, #12

          ### Issue #123: Archive size
          - [ ] refuse archives larger than 64 MiB when unpacking
          ### Issue #12
          - [ ] reject request bodies over the limit with 413
          """;
      var gap = "Linked issue #9: reject request bodies over the limit with 413 — no check.";

      assertEquals(
          List.of(gap.replace("#9", "#12")),
          DescriptionGapCarryover.withLinkedIssueNumbers(List.of(gap), section));
      var archive = "Linked issue #9: refuse archives larger than 64 MiB — no check.";
      assertEquals(
          List.of(archive.replace("#9", "#123")),
          DescriptionGapCarryover.withLinkedIssueNumbers(List.of(archive), section));
    }

    @Test
    void withSeveralLinkedAndNoClearMatchEveryLinkedIssueIsNamed() {
      var gap = "Linked issue #9: documentation — nothing added.";

      var checked = DescriptionGapCarryover.withLinkedIssueNumbers(List.of(gap), LINKED_TWO);

      assertEquals(List.of("Linked issue #120 or #122: documentation — nothing added."), checked);
      assertEquals(
          Set.of("#120", "#122"), DescriptionGapCarryover.keyOf(checked.getFirst()).issues());
      // A section missing an issue's heading gives that issue no words to match on.
      var headless = "Issues this pull request is linked to: #120, #122\n";
      assertEquals(
          List.of("Linked issue #120 or #122: documentation — nothing added."),
          DescriptionGapCarryover.withLinkedIssueNumbers(List.of(gap), headless));
    }
  }

  @Nested
  class ThePromptSection {

    @Test
    void nothingCarriedAsksNothing() {
      assertEquals("", DescriptionGapCarryover.Carry.NONE.promptSection());
    }

    @Test
    void eachCarriedGapIsLabelledFencedAndClipped() {
      var longGap = "x".repeat(DescriptionGapCarryover.MAX_PROMPT_GAP_CHARS + 50);
      var section =
          new DescriptionGapCarryover.Carry(List.of(CAP_GAP, longGap), LINKED_113).promptSection();

      assertTrue(section.startsWith(PrReviewPrompts.CARRIED_GAPS_REQUEST), section);
      assertTrue(section.contains(PromptTemplateEscaper.fencePrefix()), section);
      assertTrue(section.contains("G1: " + CAP_GAP + "\n"), section);
      assertTrue(section.contains("G2: " + "x".repeat(100)), section);
      assertFalse(section.contains(longGap), section);
    }
  }

  @Nested
  class Applying {

    @Test
    void aMissingSectionReadsAsNone() {
      assertEquals("", new DescriptionGapCarryover.Carry(List.of(), null).linkedIssues());
    }

    @Test
    void aDegradedRoundKeepsTheCarriedGapsInASummaryOfTheirOwn() {
      var kept = new DescriptionGapCarryover.Carry(List.of(CAP_GAP), LINKED_113).apply(null);

      assertEquals(List.of(CAP_GAP), kept.descriptionGaps());
      assertNull(kept.prPurpose());
      assertNull(DescriptionGapCarryover.Carry.NONE.apply(null));
    }

    @Test
    void theAddressedLabelsAreSpentOnTheMerge() {
      var carry = new DescriptionGapCarryover.Carry(List.of(CAP_GAP), LINKED_113);

      var applied = carry.apply(summary(List.of("Linked issue #1: TTL — missing"), List.of("G1")));

      assertEquals(List.of("Linked issue #113: TTL — missing"), applied.descriptionGaps());
      assertEquals(List.of(), applied.addressedGaps());
    }
  }

  /**
   * The rounds as a maintainer sees them: the round-1 summary is posted, and round 2 reads it back
   * from the conversation while its own summary call drops the linked-issue gap and returns only a
   * gap that restates a finding (ThrillhouseBot-test#123 and four more, #923).
   */
  @Nested
  class AcrossRounds {

    private final PrSummaryGenerator generator = new PrSummaryGenerator(false);

    private static final Finding HARDCODED_KEY =
        new Finding(
            RiskLevel.HIGH,
            Confidence.HIGH,
            "python/marketplace/config.py",
            5,
            "PAYMENT_API_KEY is hardcoded instead of read from the environment",
            "The documented PAYMENT_API_KEY setting is a module constant.",
            "PAYMENT_API_KEY = 'x'",
            null);

    /** The description gap round 2 returned: it restates the finding, so it collapses. */
    private static final String RESTATING_GAP =
        "The description documents PAYMENT_API_KEY as configurable, but PAYMENT_API_KEY is"
            + " hardcoded instead of read from the environment.";

    private String render(ReviewResponse.Summary aiSummary) {
      var result =
          new ReviewResult(
              List.of(HARDCODED_KEY),
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
      return generator.generate(3, 40, 0, List.of(), aiSummary, result);
    }

    private static String posted(String rendered) {
      return PrSummaryGenerator.SUMMARY_MARKER + "\n" + rendered;
    }

    @Test
    void theRoundOneLinkedIssueGapSurvivesRoundTwo() {
      var round1 =
          render(
              new DescriptionGapCarryover.Carry(List.of(), LINKED_113)
                  .apply(summary(List.of(CAP_GAP), List.of())));
      assertTrue(round1.contains("- " + CAP_GAP + "\n"), round1);

      var conversation = List.of(comment("thrillhousebot[bot]", posted(round1)));
      var carry = DescriptionGapCarryover.of(conversation, BOT, LINKED_113);
      assertEquals(List.of(CAP_GAP), carry.gaps());
      var round2 = render(carry.apply(summary(List.of(RESTATING_GAP), List.of())));

      assertTrue(round2.contains("- " + CAP_GAP + "\n"), round2);
      assertFalse(round2.contains(RESTATING_GAP), round2);
      assertFalse(round2.contains(PrSummaryGenerator.GAPS_ALL_REPORTED_AS_FINDINGS), round2);
      assertTrue(
          round2.contains("The PR description or its linked issue does not fully match"), round2);

      // Round 3 finds it still carried, and the call names it resolved: only then does it go.
      var round3Carry =
          DescriptionGapCarryover.of(
              List.of(comment("thrillhousebot[bot]", posted(round2))), BOT, LINKED_113);
      assertEquals(List.of(CAP_GAP), round3Carry.gaps());
      var round3 =
          render(
              round3Carry.apply(
                  summary(List.of(), List.of("G1: service.py now rejects a ninth listing"))));
      assertFalse(round3.contains(CAP_GAP), round3);
      assertFalse(round3.contains(PrSummaryGenerator.GAPS_HEADING), round3);
    }

    @Test
    void theBoilerplateNeverStandsInForAnUnreportedGap() {
      // Every gap the round itself returned collapses onto the finding; the carried gap does not,
      // so the section lists it rather than claiming every mismatch is a finding below.
      var carry = new DescriptionGapCarryover.Carry(List.of(CAP_GAP), LINKED_113);

      var rendered = render(carry.apply(summary(List.of(RESTATING_GAP), List.of())));

      assertFalse(rendered.contains(PrSummaryGenerator.GAPS_ALL_REPORTED_AS_FINDINGS), rendered);
      assertTrue(rendered.contains(CAP_GAP), rendered);

      // With nothing carried and the round's gap restating the finding, the line is true and stays.
      var collapsed =
          render(
              DescriptionGapCarryover.Carry.NONE.apply(summary(List.of(RESTATING_GAP), List.of())));
      assertTrue(collapsed.contains(PrSummaryGenerator.GAPS_ALL_REPORTED_AS_FINDINGS), collapsed);
    }

    @Test
    void anEchoedLabelNeverReachesTheSectionAndTheCarriedGapStaysOnce() {
      // #970: round 3 echoed the carried gap's label into description_gaps, as "G1: <gap>" (#185)
      // and as a bare "G1" (#186). Neither label is rendered, and the echoed gap is the carried
      // one reported again, so it is listed once.
      var carry = new DescriptionGapCarryover.Carry(List.of(CAP_GAP), LINKED_113);

      var rendered = render(carry.apply(summary(List.of("G1", "G1: " + CAP_GAP), List.of())));

      assertTrue(rendered.contains("- " + CAP_GAP + "\n"), rendered);
      assertFalse(rendered.contains("G1"), rendered);
      assertEquals(1, rendered.split(java.util.regex.Pattern.quote(CAP_GAP), -1).length - 1);
    }

    @Test
    void aCarriedLabelUsedAsTheSubjectNeverReachesTheSection() {
      // #974: ThrillhouseBot-test#203 round 2 carried two gaps and the call answered
      // "G2 stands partially unaddressed: <gap>"; the label must not reach the reader.
      var ciGap = "The description omits the deployment/CI half (Dockerfile, workflow).";
      var carry =
          new DescriptionGapCarryover.Carry(
              List.of(CAP_GAP, "The description says CI publishes the image, but no job does."),
              LINKED_113);

      var rendered =
          render(
              carry.apply(
                  summary(
                      List.of(
                          "G2 stands partially unaddressed: the description omits the"
                              + " deployment/CI half (Dockerfile, workflow).",
                          "The TTL is a constant, as in G1.",
                          "The TTL is never read (G1, still not addressed)."),
                      List.of())));

      assertTrue(rendered.contains("- " + ciGap + "\n"), rendered);
      assertTrue(rendered.contains("- The TTL is a constant.\n"), rendered);
      // #974: ThrillhouseBot-test#206 round 2 put the label in a parenthetical with a remark.
      assertTrue(rendered.contains("- The TTL is never read (still not addressed).\n"), rendered);
      assertFalse(rendered.contains("G2"), rendered);
      assertFalse(rendered.contains("G1"), rendered);
    }

    @Test
    void withNothingCarriedAGapAboutTheG1CollectorIsLeftAsWritten() {
      // #974: no labels were issued, so "G1" can only be the garbage collector.
      var gap = "G1 GC is named in the description, but the JVM flags select ZGC.";

      var applied = DescriptionGapCarryover.Carry.NONE.apply(summary(List.of(gap), List.of()));

      assertEquals(List.of(gap), applied.descriptionGaps());
    }

    @Test
    void aGapWhoseIssueIsNoLongerLinkedIsNotCarried() {
      var round1 = render(summary(List.of(CAP_GAP), List.of()));
      var conversation = List.of(comment("thrillhousebot[bot]", posted(round1)));

      var carry = DescriptionGapCarryover.of(conversation, BOT, LINKED_113.replace("#113", "#140"));

      assertEquals(List.of(), carry.gaps());
      assertEquals(
          List.of(),
          DescriptionGapCarryover.of(List.of(), BOT, LINKED_113).gaps(),
          "a first round has no summary to carry from");
    }
  }

  /**
   * How linked-issue gaps and gap labels are read: each shape with the reading it must get (the
   * readings the single backtracking patterns gave before the linked-issue one was split into a
   * head and an issue-number list and both had their blanks made possessive), and a long run of
   * blanks read in linear time.
   */
  @Nested
  class ShapeReading {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static String digits(String text) {
      return text == null
          ? ""
          : String.join(" ", DIGITS.matcher(text).results().map(r -> r.group()).toList());
    }

    static Stream<Arguments> linkedIssueGaps() {
      return Stream.of(
          Arguments.of("Linked issue #113: x", true, "113", " x"),
          Arguments.of("Linked issues #113, #114: x", true, "113 114", " x"),
          Arguments.of("linked issue 113 or 114: x", true, "113 114", " x"),
          Arguments.of("Linked issue #1/#2 and #3: x", true, "1 2 3", " x"),
          Arguments.of("Linked issue: x", true, "", " x"),
          Arguments.of("Linked issue text says: retries are optional", false, "", ""),
          Arguments.of("Linked issues#5:x", true, "5", "x"),
          Arguments.of("  Linked\n issue\t#5 :x\ny", true, "5", "x\ny"),
          Arguments.of("Linked issue #5,: x", false, "", ""),
          Arguments.of("Linked issue ,#5: x", false, "", ""),
          Arguments.of("Linked issue #5 #6: x", false, "", ""),
          Arguments.of("Linked issue #: x", false, "", ""),
          Arguments.of("Linked issue ##5: x", false, "", ""),
          Arguments.of("Linked issue 1,2,3,4,5,6,7,8,9,10: x", true, "1 2 3 4 5 6 7 8 9 10", " x"),
          Arguments.of("Linked issue 1,2,3,4,5,6,7,8,9,10,11: x", false, "", ""),
          Arguments.of("Linked issuess #5: x", false, "", ""),
          Arguments.of("Linked issue #5 OR #6 And #7: x", true, "5 6 7", " x"),
          Arguments.of("Linked issue #5 order: x", false, "", ""),
          Arguments.of("Linked issue #5: a: b", true, "5", " a: b"),
          Arguments.of("linkedissue #5: x", false, "", ""),
          Arguments.of("Linked issue #5", false, "", ""),
          Arguments.of("Not a linked issue #5: x", false, "", ""),
          Arguments.of("Linked issue #5andor#6: x", false, "", ""),
          Arguments.of("Linked issue 5and6: x", true, "5 6", " x"),
          Arguments.of("Linked issue 5 / / 6: x", false, "", ""),
          Arguments.of("Linked issue  :", true, "", ""));
    }

    @ParameterizedTest
    @MethodSource("linkedIssueGaps")
    void linkedIssueGapsAreReadAsExpected(String gap, boolean matches, String issues, String rest) {
      var read = DescriptionGapCarryover.linkedIssueGap(gap);
      assertEquals(matches, read != null, gap);
      if (read != null) {
        assertEquals(issues, digits(read.group(1)), gap);
        assertEquals(rest, read.group(2), gap);
      }
    }

    static Stream<Arguments> gapLabels() {
      return Stream.of(
          Arguments.of("G2", "2"),
          Arguments.of("[G2]", "2"),
          Arguments.of(" [ g 12 ] x", "12"),
          Arguments.of("G1234", null),
          Arguments.of("G12a", null),
          Arguments.of("xG2", null),
          Arguments.of("[[G2]", null),
          Arguments.of("G 2: done", "2"),
          Arguments.of("g", null),
          Arguments.of("", null),
          Arguments.of(" \n G7", "7"),
          Arguments.of("[ G", null),
          Arguments.of("G2-specific", "2"));
    }

    @ParameterizedTest
    @MethodSource("gapLabels")
    void gapLabelsAreReadAsExpected(String entry, String label) {
      var matcher = DescriptionGapCarryover.LABEL_REFERENCE.matcher(entry);
      if (label == null) {
        assertFalse(matcher.find(), entry);
      } else {
        assertTrue(matcher.find(), entry);
        assertEquals(label, matcher.group(1), entry);
      }
    }

    @Test
    void longBlankRunsAreReadInLinearTime() {
      var blanks = " ".repeat(200_000);
      assertTimeoutPreemptively(
          Duration.ofSeconds(5),
          () -> {
            assertEquals(
                Set.of(), DescriptionGapCarryover.keyOf("Linked issue" + blanks + "x").issues());
            assertEquals(
                Set.of(), DescriptionGapCarryover.keyOf("Linked issue" + blanks + ":x").issues());
            assertEquals(
                Set.of(),
                DescriptionGapCarryover.keyOf("Linked issue #1" + blanks + "x: y").issues());
            assertEquals(
                Set.of("#1", "#2"),
                DescriptionGapCarryover.keyOf("Linked issue #1" + blanks + "or 2" + blanks + ": y")
                    .issues());
            assertEquals(
                Set.of(),
                DescriptionGapCarryover.addressedPositions(
                    List.of(blanks + "x"), List.of("a gap")));
          });
    }
  }
}
