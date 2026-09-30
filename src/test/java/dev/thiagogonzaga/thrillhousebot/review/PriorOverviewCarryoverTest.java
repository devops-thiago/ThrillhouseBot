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
  void aSummaryWithItsOwnOverviewIsLeftAlone() {
    var purposeOnly =
        new ReviewResponse.Summary(0, 0, 0, 0, 0, null, "Its own purpose.", List.of());
    var filesOnly =
        new ReviewResponse.Summary(
            0, 0, 0, 0, 0, null, null, List.of(), List.of(), FILES, null, List.of());
    var ctx = context(List.of(round(PRIOR)), true);

    assertSame(purposeOnly, PriorOverviewCarryover.fill(purposeOnly, ctx));
    assertSame(filesOnly, PriorOverviewCarryover.fill(filesOnly, ctx));
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
}
