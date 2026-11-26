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
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentNextStepsTest {

  private static final String SHA = "abcdef1234567890abcdef1234567890abcdef12";

  private static final BotIdentity BOT = BotIdentity.of("thrillhousebot[bot]");

  private static final BotIdentity CUSTOM_BOT = BotIdentity.of("acme-reviewer[bot]");

  @Test
  void renderListsManifestHintWhenOpenFindingsAreInTheManifest() {
    var finding =
        new Finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 3, "Risk", "d", null, null);
    var round =
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.COMMENT,
            true,
            "summary",
            List.of(),
            List.of(),
            0);
    var state = PrSummaryGenerator.currentState(round);
    var manifest = FindingsManifest.render(state);
    var section = AgentNextSteps.render(round, SHA, null, state, manifest, BOT);

    assertTrue(section.contains(AgentNextSteps.SECTION_HEADING));
    assertTrue(section.contains("(no prior head fingerprint on this pull request)"));
    assertTrue(section.contains("at the top of this same comment"));
    assertTrue(section.contains("required checks green"));
    assertTrue(section.contains("/review"));
  }

  @Test
  void renderListsFileLineWhenManifestIsEmpty() {
    var finding =
        new Finding(RiskLevel.LOW, Confidence.LOW, "src/B.java", 9, "Check me", "d", null, null);
    var round =
        new ReviewResult(
            List.of(finding),
            0,
            0,
            0,
            1,
            RiskLevel.LOW,
            ReviewState.COMMENT,
            true,
            "summary",
            List.of(),
            List.of(),
            0);
    var state = PrSummaryGenerator.currentState(round);
    var section =
        AgentNextSteps.render(
            round, SHA, SHA, state, "<!-- thrillhousebot:findings v=1 [] -->", BOT);

    assertTrue(section.contains("unchanged since last summary"));
    assertTrue(section.contains("src/B.java:9"));
    assertTrue(section.contains("double-check only"));
    assertTrue(
        section.contains(
            "@thrillhousebot resolved `src/B.java:9` — " + MarkdownSafe.inline("Check me")));
    assertTrue(section.contains("Thrillhousebot on this pull request"));
  }

  @Test
  void cheatsheetUsesTheInstallationsBotDisplayName() {
    var round =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            true,
            "summary",
            List.of(),
            List.of(),
            0);
    var state = PrSummaryGenerator.currentState(round);
    var section =
        AgentNextSteps.render(round, SHA, null, state, FindingsManifest.render(state), CUSTOM_BOT);

    assertTrue(section.contains("**Acme-reviewer on this pull request**"));
    assertTrue(section.contains("@acme-reviewer review"));
  }

  @Test
  void renderNamesFailingChecksAndDefersCommandsUntilCiIsGreen() {
    var checks = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", "failure"));
    var doubleCheck =
        new Finding(RiskLevel.LOW, Confidence.LOW, "src/B.java", 9, "Check me", "d", null, null);
    var round =
        new ReviewResult(
            List.of(doubleCheck),
            0,
            0,
            0,
            1,
            RiskLevel.LOW,
            ReviewState.COMMENT,
            true,
            "summary",
            List.of(),
            checks,
            0);
    var state = PrSummaryGenerator.currentState(round);
    var section =
        AgentNextSteps.render(round, SHA, null, state, FindingsManifest.render(state), BOT);

    assertTrue(section.contains("pending or failing"));
    assertTrue(section.contains("`build`"));
    assertTrue(section.contains("Fix failing CI"));
    assertFalse(section.contains("After addressing findings"));
    assertFalse(section.contains("@thrillhousebot resolved `src/B.java:9`"));
  }

  @Test
  void headMarkerIsEmptyForBlankShaAndParsePriorHeadIgnoresMissingMarkers() {
    assertEquals("", AgentNextSteps.headMarker(null));
    assertEquals("", AgentNextSteps.headMarker("  "));
    assertEquals(null, AgentNextSteps.parsePriorHead(null));
    assertEquals(null, AgentNextSteps.parsePriorHead("  "));
    assertEquals(null, AgentNextSteps.parsePriorHead("no marker here"));
  }

  @Test
  void blankInputsLeaveTheSummaryAloneAndReadAsAnUnknownHead() {
    assertEquals("overview", AgentNextSteps.embedAfterHeading("overview", null));
    assertEquals("block", AgentNextSteps.embedAfterHeading("  ", "block"));

    var round = emptyRound(ReviewState.APPROVE);
    var state = PrSummaryGenerator.currentState(round);
    var section =
        AgentNextSteps.render(round, "  ", null, state, FindingsManifest.render(state), BOT);

    assertTrue(section.contains("**Head:** unknown"));
  }

  @Test
  void aMissingManifestListsEveryOpenFindingAndMarksOnlyTheThreadlessOnes() {
    var inline =
        new Finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 1, "Inline", "d", null, null);
    var doubleCheck =
        new Finding(RiskLevel.LOW, Confidence.LOW, "src/B.java", 9, "Check me", "d", null, null);
    var round =
        new ReviewResult(
            List.of(inline, doubleCheck),
            0,
            1,
            0,
            1,
            RiskLevel.HIGH,
            ReviewState.COMMENT,
            true,
            "summary",
            List.of(),
            List.of(),
            0);
    var state = PrSummaryGenerator.currentState(round);

    for (var manifest : new String[] {null, ""}) {
      var rows =
          AgentNextSteps.render(round, SHA, null, state, manifest, BOT)
              .lines()
              .filter(line -> line.startsWith("- `src/"))
              .toList();

      assertEquals(2, rows.size());
      assertFalse(rows.get(0).contains("double-check only"), rows.get(0));
      assertTrue(rows.get(1).contains("double-check only"), rows.get(1));
    }
  }

  @Test
  void failingCiWithNothingOpenSuggestsOnlyFixingCi() {
    var checks = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", "failure"));
    var round =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.COMMENT,
            true,
            "summary",
            List.of(),
            checks,
            0);
    var state = PrSummaryGenerator.currentState(round);
    var section =
        AgentNextSteps.render(round, SHA, null, state, FindingsManifest.render(state), BOT);

    assertTrue(section.contains("**Suggested commands:**\n- Fix failing CI, then `/review`."));
  }

  @Test
  void appendHeadCoversUnknownShaNewHeadAndShortShas() {
    var round = emptyRound(ReviewState.APPROVE);
    var state = PrSummaryGenerator.currentState(round);
    var unknown =
        AgentNextSteps.render(round, null, null, state, FindingsManifest.render(state), BOT);
    assertTrue(unknown.contains("**Head:** unknown"));

    var newHead =
        AgentNextSteps.render(
            round,
            SHA,
            "0000000000000000000000000000000000000000",
            state,
            FindingsManifest.render(state),
            BOT);
    assertTrue(newHead.contains("new since last summary"));

    var shortSha =
        AgentNextSteps.render(round, "abc123", null, state, FindingsManifest.render(state), BOT);
    assertTrue(shortSha.contains("`abc123`"));
  }

  @Test
  void embedAfterHeadingFallsBackWhenTheSummaryLacksTheStandardHeading() {
    assertEquals("overview", AgentNextSteps.embedAfterHeading("overview", ""));
    assertEquals("block\n\noverview", AgentNextSteps.embedAfterHeading("overview", "block"));
    assertEquals("block", AgentNextSteps.embedAfterHeading(null, "block"));
  }

  @Test
  void openWorkTruncatesAfterMaxListedFindings() {
    var findings = new java.util.ArrayList<Finding>();
    for (int i = 0; i < AgentNextSteps.MAX_LISTED_FINDINGS + 3; i++) {
      findings.add(
          new Finding(
              RiskLevel.LOW, Confidence.LOW, "src/F" + i + ".java", i, "t" + i, "d", null, null));
    }
    var round =
        new ReviewResult(
            findings,
            0,
            0,
            0,
            findings.size(),
            RiskLevel.LOW,
            ReviewState.COMMENT,
            true,
            "s",
            List.of(),
            List.of(),
            0);
    var state = PrSummaryGenerator.currentState(round);
    var section =
        AgentNextSteps.render(
            round, SHA, null, state, "<!-- thrillhousebot:findings v=1 [] -->", BOT);
    assertTrue(section.contains("… and 3 more"));
  }

  @Test
  void ciUnreadableAndInlineOnlyFindingsShapeSuggestedCommands() {
    var inline =
        new Finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 1, "Inline", "d", null, null);
    var round =
        new ReviewResult(
            List.of(inline),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.COMMENT,
            true,
            "summary",
            List.of(),
            List.of(),
            0,
            true,
            true,
            null);
    var state = PrSummaryGenerator.currentState(round);
    var section =
        AgentNextSteps.render(round, SHA, null, state, FindingsManifest.render(state), BOT);
    assertTrue(section.contains("status could not be read"));
    assertTrue(section.contains("Fix failing CI"));
    assertFalse(section.contains("@thrillhousebot resolved `"));
  }

  @Test
  void headMarkerRoundTripsThroughParsePriorHead() {
    var marker = AgentNextSteps.headMarker(SHA);
    assertTrue(marker.startsWith(AgentNextSteps.HEAD_MARKER_PREFIX));
    assertEqualsIgnoreCase(
        SHA, AgentNextSteps.parsePriorHead(PrSummaryGenerator.SUMMARY_MARKER + "\n" + marker));
  }

  @Test
  void cheatsheetIsAlwaysPresentEvenWithNoFindingsAndGreenCi() {
    var round =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            true,
            "summary",
            List.of(),
            List.of(),
            0);
    var state = PrSummaryGenerator.currentState(round);
    var section =
        AgentNextSteps.render(round, SHA, null, state, FindingsManifest.render(state), BOT);

    assertTrue(section.contains("**Thrillhousebot on this pull request**"));
    assertTrue(section.contains("/help"));
    assertTrue(section.contains("/summary"));
    assertTrue(section.contains("/resolve"));
    assertTrue(section.contains("different from"));
    assertFalse(section.contains("**Suggested commands:**"));
  }

  @Test
  void embedAfterHeadingPlacesSectionBeforeTheOverview() {
    var overview = PrSummaryGenerator.SUMMARY_HEADING + "\n\nPurpose text.";
    var embedded =
        AgentNextSteps.embedAfterHeading(
            overview, AgentNextSteps.SECTION_HEADING + "\n\n**Head:**");
    assertTrue(embedded.startsWith(PrSummaryGenerator.SUMMARY_HEADING));
    int headingEnd = embedded.indexOf("Purpose text.");
    int sectionStart = embedded.indexOf(AgentNextSteps.SECTION_HEADING);
    assertTrue(sectionStart > 0 && sectionStart < headingEnd);
  }

  @Test
  void embedAfterHeadingKeepsBannersAboveTheHeading() {
    var banner = ReviewResult.confidenceHoldNotice(1);
    var summary = banner + PrSummaryGenerator.SUMMARY_HEADING + "\n\nPurpose text.";

    assertEquals(
        banner + PrSummaryGenerator.SUMMARY_HEADING + "\n\nblock\n\nPurpose text.",
        AgentNextSteps.embedAfterHeading(summary, "block"));
  }

  @Test
  void commandPlaceholdersStayInsideCodeSpansSoGitHubDoesNotDropThem() {
    var inline =
        new Finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 1, "Inline", "d", null, null);
    var round =
        new ReviewResult(
            List.of(inline),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.COMMENT,
            true,
            "summary",
            List.of(),
            List.of(),
            0);
    var state = PrSummaryGenerator.currentState(round);
    var section =
        AgentNextSteps.render(round, SHA, null, state, FindingsManifest.render(state), BOT);

    assertTrue(
        section.contains(
            "- To clear a finding: `@thrillhousebot resolved <path>:<line> — <title>`"));
    assertTrue(section.contains("- `@thrillhousebot declined <path>:<line> — <title>` plus"));
    var outsideCodeSpans = section.replaceAll("`[^`\n]*`", "");
    assertFalse(outsideCodeSpans.contains("<path>"), outsideCodeSpans);
    assertFalse(outsideCodeSpans.contains("<title>"), outsideCodeSpans);
  }

  private static ReviewResult emptyRound(ReviewState state) {
    return new ReviewResult(
        List.of(), 0, 0, 0, 0, null, state, true, "summary", List.of(), List.of(), 0);
  }

  private static void assertEqualsIgnoreCase(String expected, String actual) {
    assertTrue(
        expected.equalsIgnoreCase(actual), () -> "expected " + expected + " but was " + actual);
  }
}
