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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #944: a follow-up round on an unchanged head keeps the previous round's overview. */
class PriorOverviewCarryoverTest {

  private static final List<ReviewResponse.FileSummary> FILES =
      List.of(new ReviewResponse.FileSummary("src/lib.rs", "adds the cache layer"));

  private static final ReviewResponse.Summary PRIOR =
      new ReviewResponse.Summary(
          1,
          0,
          1,
          0,
          0,
          "ok",
          "Adds a cache layer in front of the store.",
          List.of("an old gap"),
          List.of("enhancement"),
          FILES,
          "flowchart TD\n  A --> B",
          List.of());

  private static ReviewResponse.Finding finding() {
    return new ReviewResponse.Finding(
        "high", "high", "src/lib.rs", 3, "Unbounded cache", "d", "old", "new");
  }

  private static ReviewContextLoader.ReviewContext context(
      List<ReviewResponse> priorRounds, boolean headUnchanged) {
    var file = new FileDiff("src/lib.rs", "modified", 1, 0, 1, "");
    return new ReviewContextLoader.ReviewContext(
        List.of(file),
        "",
        "",
        0,
        List.of(),
        List.of(),
        priorRounds,
        false,
        !priorRounds.isEmpty(),
        null,
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        List.of(file),
        () -> new DiffLineResolver(Map.of()),
        null,
        List.of(),
        List.of(),
        SupersededFindingsCarryover.Carried.NONE,
        "",
        headUnchanged);
  }

  private static ReviewResponse round(ReviewResponse.Summary summary) {
    return new ReviewResponse(List.of(finding()), List.of(), summary);
  }

  @Test
  void aRoundWithNoSummaryOnAnUnchangedHeadKeepsThePreviousOverview() {
    var kept = PriorOverviewCarryover.fill(null, context(List.of(round(PRIOR)), true));

    assertEquals(PRIOR.prPurpose(), kept.prPurpose());
    assertEquals(FILES, kept.fileSummaries());
    assertEquals(PRIOR.walkthroughDiagram(), kept.walkthroughDiagram());
    // Only the overview is taken: the gaps ride their own carry-over, and labels are not
    // re-applied.
    assertTrue(kept.descriptionGaps().isEmpty());
    assertTrue(kept.suggestedLabels().isEmpty());
    assertNull(kept.overallAssessment());
  }

  @Test
  void aSummaryWithoutOverviewKeepsItsOwnGapsLabelsAndCounts() {
    var bare =
        new ReviewResponse.Summary(
            2,
            0,
            0,
            2,
            0,
            "fine",
            " ",
            List.of("a fresh gap"),
            List.of("bug"),
            List.of(),
            null,
            List.of("G1"));

    var kept = PriorOverviewCarryover.fill(bare, context(List.of(round(PRIOR)), true));

    assertEquals(PRIOR.prPurpose(), kept.prPurpose());
    assertEquals(FILES, kept.fileSummaries());
    assertEquals(PRIOR.walkthroughDiagram(), kept.walkthroughDiagram());
    assertEquals(List.of("a fresh gap"), kept.descriptionGaps());
    assertEquals(List.of("bug"), kept.suggestedLabels());
    assertEquals(List.of("G1"), kept.addressedGaps());
    assertEquals("fine", kept.overallAssessment());
    assertEquals(2, kept.totalFindings());
    assertEquals(2, kept.medium());
  }

  @Test
  void aSummaryWithItsOwnOverviewAndDiagramIsLeftAlone() {
    var complete =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            null,
            "Its own purpose.",
            List.of(),
            List.of(),
            List.of(new ReviewResponse.FileSummary("src/lib.rs", "its own line")),
            "flowchart TD\n  X --> Y",
            List.of());
    var ctx = context(List.of(round(PRIOR)), true);

    assertSame(complete, PriorOverviewCarryover.fill(complete, ctx));
    assertSame(complete, PriorOverviewCarryover.fill(complete, context(List.of(), true)));
  }

  @Test
  void aSummaryWithItsOwnOverviewTakesTheEarlierSummaryForEachFileItLacksAndTheDiagram() {
    // #964 (Round 15 group B): kotlin #204 round 2 kept its purpose paragraph but lost nine
    // file summaries and the Control-Flow Diagram that round 1 had, on the same head.
    var earlier =
        new ReviewResponse.Summary(
            1,
            0,
            1,
            0,
            0,
            "ok",
            "Adds a cache layer.",
            List.of(),
            List.of(),
            List.of(
                new ReviewResponse.FileSummary("src/lib.rs", "adds the cache layer"),
                new ReviewResponse.FileSummary("src/http.rs", "routes cache reads"),
                new ReviewResponse.FileSummary("src/blank.rs", " "),
                new ReviewResponse.FileSummary(" ", "a line with no path")),
            "flowchart TD\n  A --> B",
            List.of());
    var current =
        new ReviewResponse.Summary(
            2,
            0,
            0,
            2,
            0,
            "fine",
            "Its own purpose.",
            List.of("a gap"),
            List.of(),
            List.of(
                new ReviewResponse.FileSummary("src/lib.rs", "its own line"),
                new ReviewResponse.FileSummary("src/http.rs", "")),
            "",
            List.of());

    var kept = PriorOverviewCarryover.fill(current, context(List.of(round(earlier)), true));

    assertEquals("Its own purpose.", kept.prPurpose());
    assertEquals(
        List.of(
            new ReviewResponse.FileSummary("src/lib.rs", "its own line"),
            new ReviewResponse.FileSummary("src/http.rs", "routes cache reads")),
        kept.fileSummaries());
    assertEquals("flowchart TD\n  A --> B", kept.walkthroughDiagram());
    assertEquals(List.of("a gap"), kept.descriptionGaps());
    assertEquals(2, kept.medium());
    // On a moved head nothing is taken.
    assertSame(
        current, PriorOverviewCarryover.fill(current, context(List.of(round(earlier)), false)));
  }

  @Test
  void onlyTheDiagramIsTakenWhenEveryFileIsCovered() {
    var current =
        new ReviewResponse.Summary(
            0, 0, 0, 0, 0, null, "Its own purpose.", List.of(), List.of(), FILES, null, List.of());

    var kept = PriorOverviewCarryover.fill(current, context(List.of(round(PRIOR)), true));

    assertEquals(FILES, kept.fileSummaries());
    assertEquals(PRIOR.walkthroughDiagram(), kept.walkthroughDiagram());
  }

  @Test
  void withNoEarlierDiagramOnlyTheMissingFileIsTaken() {
    var earlier =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            null,
            "Adds a cache layer.",
            List.of(),
            List.of(),
            List.of(new ReviewResponse.FileSummary("src/http.rs", "routes cache reads")),
            null,
            List.of());
    var current =
        new ReviewResponse.Summary(
            0, 0, 0, 0, 0, null, "Its own purpose.", List.of(), List.of(), FILES, null, List.of());

    var kept = PriorOverviewCarryover.fill(current, context(List.of(round(earlier)), true));

    assertEquals(
        List.of(
            FILES.getFirst(), new ReviewResponse.FileSummary("src/http.rs", "routes cache reads")),
        kept.fileSummaries());
    assertNull(kept.walkthroughDiagram());
  }

  @Test
  void anOverviewWithTheEarlierPurposeButOtherFilesIsNotCarried() {
    var ctx = context(List.of(round(PRIOR)), true);
    var samePurpose =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            null,
            PRIOR.prPurpose(),
            List.of(),
            List.of(),
            List.of(new ReviewResponse.FileSummary("src/other.rs", "x")),
            null,
            List.of());

    assertFalse(PriorOverviewCarryover.carried(samePurpose, ctx));
  }

  @Test
  void anEarlierSummaryForTheSameFileUnderAnotherSpellingIsNotTakenTwice() {
    // The answer and the earlier round can spell one file with and without a leading directory;
    // FilePaths.same holds them for one file, as the rest of the pipeline does.
    var earlier =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            null,
            "Adds a cache layer.",
            List.of(),
            List.of(),
            List.of(
                new ReviewResponse.FileSummary("repo/src/lib.rs", "the earlier line"),
                new ReviewResponse.FileSummary("other/lib.rs", "a different lib.rs")),
            null,
            List.of());
    var current =
        new ReviewResponse.Summary(
            0, 0, 0, 0, 0, null, "Its own purpose.", List.of(), List.of(), FILES, null, List.of());

    var kept = PriorOverviewCarryover.fill(current, context(List.of(round(earlier)), true));

    assertEquals(
        List.of(
            FILES.getFirst(), new ReviewResponse.FileSummary("other/lib.rs", "a different lib.rs")),
        kept.fileSummaries());
  }

  @Test
  void aDegradedRoundCarriesTheOverviewUnderItsOwnFindingCounts() {
    var findings =
        List.of(
            finding(),
            new ReviewResponse.Finding("critical", "high", "src/lib.rs", 9, "t", "d", null, null),
            new ReviewResponse.Finding("low", "high", "src/lib.rs", 12, "t2", "d", null, null));

    var kept = PriorOverviewCarryover.fillDegraded(context(List.of(round(PRIOR)), true), findings);

    assertEquals(PRIOR.prPurpose(), kept.prPurpose());
    assertEquals(FILES, kept.fileSummaries());
    assertEquals(3, kept.totalFindings());
    assertEquals(1, kept.critical());
    assertEquals(1, kept.high());
    assertEquals(0, kept.medium());
    assertEquals(1, kept.low());
    // No overview to carry: the counts-only shape, a null summary, as before.
    assertNull(
        PriorOverviewCarryover.fillDegraded(context(List.of(round(PRIOR)), false), findings));
  }

  @Test
  void aCarriedOverviewIsToldApartFromTheRoundsOwn() {
    var ctx = context(List.of(round(PRIOR)), true);
    var carried = PriorOverviewCarryover.fill(null, ctx);
    var own = new ReviewResponse.Summary(0, 0, 0, 0, 0, null, "Its own purpose.", List.of());

    assertTrue(PriorOverviewCarryover.carried(carried, ctx));
    assertFalse(PriorOverviewCarryover.carried(own, ctx));
    assertFalse(PriorOverviewCarryover.carried(null, ctx));
    assertFalse(PriorOverviewCarryover.carried(carried, context(List.of(round(PRIOR)), false)));
    assertFalse(PriorOverviewCarryover.carried(carried, context(List.of(), true)));
  }

  @Test
  void aMovedHeadKeepsTheDegradedShape() {
    assertNull(PriorOverviewCarryover.fill(null, context(List.of(round(PRIOR)), false)));
  }

  @Test
  void nothingToKeepWhenThePreviousRoundHadNoOverview() {
    var noOverview = new ReviewResponse.Summary(0, 0, 0, 0, 0, null, null, List.of("gap"));

    assertNull(PriorOverviewCarryover.fill(null, context(List.of(round(noOverview)), true)));
    assertNull(PriorOverviewCarryover.fill(null, context(List.of(round(null)), true)));
    assertNull(PriorOverviewCarryover.fill(null, context(List.of(), true)));
  }

  @Test
  void theOverviewComesFromTheRoundTheHeadWasComparedAgainst() {
    // The newest round raised nothing, so the round the head check names is the one before it.
    var newestClean =
        new ReviewResponse(
            List.of(),
            List.of(),
            new ReviewResponse.Summary(0, 0, 0, 0, 0, null, "A newer, other purpose.", List.of()));

    var kept = PriorOverviewCarryover.fill(null, context(List.of(newestClean, round(PRIOR)), true));

    assertEquals(PRIOR.prPurpose(), kept.prPurpose());
  }

  @Test
  void noPreviousRoundListMeansNoPreviousSummary() {
    assertNull(PriorOverviewCarryover.previousRoundSummary(null));
    assertNull(PriorOverviewCarryover.previousRoundSummary(List.of()));
  }

  @Test
  void hasOverviewNeedsAPurposeOrAFileSummary() {
    assertFalse(PriorOverviewCarryover.hasOverview(null));
    assertFalse(
        PriorOverviewCarryover.hasOverview(
            new ReviewResponse.Summary(0, 0, 0, 0, 0, null, null, List.of())));
    assertTrue(PriorOverviewCarryover.hasOverview(PRIOR));
  }

  private static ReviewResponse.Summary overview(String purpose, String diagram) {
    return new ReviewResponse.Summary(
        1, 0, 1, 0, 0, "ok", purpose, List.of(), List.of(), FILES, diagram, List.of());
  }

  @Test
  void aBlankPurposeAloneTakesThePreviousPurpose() {
    // The overview screen took every sentence out of the purpose; files and diagram are there.
    var current = overview("", "flowchart TD\n  X --> Y");

    var kept = PriorOverviewCarryover.fill(current, context(List.of(round(PRIOR)), true));

    assertEquals(PRIOR.prPurpose(), kept.prPurpose());
    assertEquals(FILES, kept.fileSummaries());
    assertEquals(current.walkthroughDiagram(), kept.walkthroughDiagram());
  }

  @Test
  void aBlankPurposeStaysBlankWhenThePreviousRoundHadNone() {
    var current = overview("", "flowchart TD\n  X --> Y");
    var priorWithoutPurpose = overview(" ", "flowchart TD\n  A --> B");

    assertSame(
        current,
        PriorOverviewCarryover.fill(current, context(List.of(round(priorWithoutPurpose)), true)));
  }

  @Test
  void manySameNamedFilesKeepEveryDistinctPathAndPreferCurrentSummaries() {
    var priorFiles = new java.util.ArrayList<ReviewResponse.FileSummary>();
    var currentFiles = new java.util.ArrayList<ReviewResponse.FileSummary>();
    for (int i = 0; i < 2000; i++) {
      priorFiles.add(
          new ReviewResponse.FileSummary("repo/service" + i + "/Main.java", "prior " + i));
      if (i % 2 == 0) {
        currentFiles.add(
            new ReviewResponse.FileSummary("service" + i + "/Main.java", "current " + i));
      }
    }
    var prior =
        new ReviewResponse.Summary(
            0, 0, 0, 0, 0, null, "Prior", List.of(), List.of(), priorFiles, null, List.of());
    var current =
        new ReviewResponse.Summary(
            0, 0, 0, 0, 0, null, "Current", List.of(), List.of(), currentFiles, null, List.of());
    var kept = PriorOverviewCarryover.fill(current, context(List.of(round(prior)), true));
    var expected = new java.util.ArrayList<>(currentFiles);
    for (int i = 1; i < 2000; i += 2) {
      expected.add(priorFiles.get(i));
    }
    assertEquals(expected, kept.fileSummaries());
  }

  private static ReviewResponse.Summary withPurpose(String purpose) {
    return new ReviewResponse.Summary(
        1,
        0,
        1,
        0,
        0,
        "ok",
        purpose,
        List.of(),
        List.of(),
        FILES,
        "flowchart TD\n  A --> B",
        List.of());
  }

  /**
   * #1095, node ThrillhouseBot-test#278 round 2: the purpose paragraph came back holding only the
   * change's size, so the previous round's account of what the change does is kept.
   */
  @Test
  void aPurposeThatOnlyStatesTheSizeGivesWayToThePreviousOne() {
    // The paragraph node #278 posted, its computed CI sentence included.
    var sizeOnly =
        withPurpose(
            "It is a feature-sized change (+555 lines across 6 directories) that also ships the"
                + " container image and CI pipeline described in issue #262. CI on this commit"
                + " fails the check \"test\" (failing tests \"accepts a team of exactly maxTeamSize"
                + " members\"), which the finding \"Team of exactly MAX_TEAM_SIZE members is"
                + " rejected (off-by-one)\" explains.");

    var kept = PriorOverviewCarryover.fill(sizeOnly, context(List.of(round(PRIOR)), true));

    assertEquals(PRIOR.prPurpose(), kept.prPurpose());
    assertSame(
        sizeOnly, PriorOverviewCarryover.fill(sizeOnly, context(List.of(round(PRIOR)), false)));
  }

  /** A purpose that says what the change does, even with its size, is the round's own. */
  @Test
  void aPurposeThatSaysWhatTheChangeDoesIsKept() {
    var own = withPurpose("Adds a carrier poller (+555 lines across 6 directories).");

    assertSame(own, PriorOverviewCarryover.fill(own, context(List.of(round(PRIOR)), true)));
    var priorSizeOnly = withPurpose("A large change touching 12 files.");
    var current = withPurpose("It is a small change of 40 lines.");
    assertSame(
        current,
        PriorOverviewCarryover.fill(current, context(List.of(round(priorSizeOnly)), true)));
  }

  @Test
  void aSizeStatementIsReadSentenceBySentence() {
    assertTrue(
        PriorOverviewCarryover.sizeOnly(
            "It is a feature-sized change (+555 lines across 6 directories)."));
    assertTrue(PriorOverviewCarryover.sizeOnly("A 1,204-line PR. It's 9 files across 3 modules."));
    assertTrue(PriorOverviewCarryover.sizeOnly("Medium-sized diff."));
    assertFalse(PriorOverviewCarryover.sizeOnly("A large PR. It adds a poller."));
    assertFalse(PriorOverviewCarryover.sizeOnly("It is a large change."));
    assertFalse(PriorOverviewCarryover.sizeOnly("Bumps 3 dependencies."));
    assertFalse(PriorOverviewCarryover.sizeOnly(" "));
    assertFalse(PriorOverviewCarryover.sizeOnly(null));
    // A size statement may lead the first sentence; what follows its clause is not read.
    assertTrue(
        PriorOverviewCarryover.sizeOnly(
            "This is a large change (12 files; 900 lines), with a new CI job."));
    assertFalse(
        PriorOverviewCarryover.sizeOnly(
            "It is a feature-sized change (+555 lines). It adds a team signup service."));
    // A paragraph that held only the CI sentence has no size statement.
    assertFalse(
        PriorOverviewCarryover.sizeOnly(
            CiFailureCitations.SENTENCE_OPENING + "\"test\", which no finding explains."));
  }
}
