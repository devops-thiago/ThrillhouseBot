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

/** #1323: the overview's prose states no count of findings and no size of the change. */
class CountStatementsTest {

  @Test
  void theSentenceFromPr1315IsCutAndTheRestKept() {
    assertEquals(
        "Adds a retry to the uploader. It also logs each attempt.",
        CountStatements.withoutCounts(
            "Adds a retry to the uploader. One medium finding remains. It also logs each"
                + " attempt."));
  }

  @Test
  void textWithNoCountIsReturnedAsItIs() {
    var text = "Adds a retry to the uploader.  It logs each attempt.";

    assertSame(text, CountStatements.withoutCounts(text));
  }

  @Test
  void nullAndBlankTextAreLeftAlone() {
    assertNull(CountStatements.withoutCounts(null));
    assertEquals(" ", CountStatements.withoutCounts(" "));
  }

  @Test
  void aPurposeOfCountsAloneIsCutToNothing() {
    assertEquals("", CountStatements.withoutCounts("17 findings remain open."));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "One medium finding remains.",
        "17 findings were raised.",
        "Two high-severity findings are still open.",
        "The review leaves 3 findings open.",
        "Two of the findings concern the parser.",
        "It leaves one finding",
        "One finding is still open.",
        "It is a feature-sized change (+555 lines across 6 directories).",
        "+555 lines in total.",
        "The change is +40/−20.",
        "A 555-line change to the parser.",
        "It touches 6 files.",
        "It spans about 600 lines.",
        "It adds two new files.",
        "Refactors the parser (12 files).",
        "Roughly 1,204 lines changed in the parser.",
        "Splits twenty-three files across the modules.",
        "It touches twenty-three files.",
        "It touches 6 files today.",
        "The parser shrinks by \u221220.",
        "The parser shrinks by -20.",
        "It is +40/-20 overall.",
        "Adds 4 config files.",
        "Touches 2 schema directories.",
        "Adds 3 new migration files.",
        "It leaves 3 findings",
        "Seventeen open high-severity findings remain in the parser.",
        "It adds two new high-density files.",
        "It holds 40 changed lines.",
        "One finding remaining.",
        "It leaves one finding unaddressed.",
        "Adds thirty two new migration files.",
        "It adds +12 to the parser.",
        "It ends at +40/-20."
      })
  void aCountOrSizeIsStated(String sentence) {
    assertTrue(CountStatements.statesCount(sentence), sentence);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Merges the passes into one finding stage.",
        "Caps the walkthrough table at 50 files.",
        "Moves the build to Java 17.",
        "Retries two times before giving up.",
        "Keeps the configuration in 6 files.",
        "Bumps C++17 support.",
        "Adds version 2",
        "Adds 2 new",
        "Reads the findings and posts them.",
        "Fixes issue #1315 where the parser failed.",
        "One of the many open and stale findings.",
        "Sets the offset to x+5.",
        "Fixes #1315 where findings were miscounted.",
        "Since v0.9.2 findings carry a stable id.",
        "Screens the P1 findings first.",
        "Replaces the old pass with a new one, findings included.",
        "Moves the v2 files across to the new module.",
        "Ships schema 6, files across the tree keep it.",
        "6 files are new.",
        "Adds a one-line guard to the parser.",
        "Keeps a single-file layout.",
        "Sets the offset to x-5.",
        "Accepts Java 17-21.",
        "Adds 2 checks for missing files.",
        "Merges the two findings columns into one query.",
        "Splits 3 findings tables by severity.",
        "Introduces the two findings screens used by the pipeline.",
        "Merges the three findings passes into one.",
        "Sets the offset to -5.",
        "Allows a retry limit of -1 to disable it.",
        "Accepts negative offsets of -1.",
        "+5 is the default offset.",
        "Shifts the offsets +4 -2 apart."
      })
  void noCountOrSizeIsStated(String sentence) {
    assertFalse(CountStatements.statesCount(sentence), sentence);
  }

  @Test
  void theComputedSentenceOnAFailingCheckIsNeverCut() {
    var text =
        "Adds a cache. "
            + CiFailureCitations.SENTENCE_OPENING
            + "'test' (2 findings explain it across 3 files).";

    assertSame(text, CountStatements.withoutCounts(text));
  }

  @Test
  void aSummaryHasItsPurposeAndAssessmentScreened() {
    var gaps = List.of("The description says X.");
    var files = List.of(new ReviewResponse.FileSummary("a.java", "adds the cache"));
    var summary =
        new ReviewResponse.Summary(
            4,
            0,
            1,
            3,
            0,
            "Looks solid. 4 findings need attention.",
            "Adds a cache layer. It touches 6 files.",
            gaps,
            List.of("enhancement"),
            files,
            "flowchart TD",
            List.of("G1: done"));

    var screened = CountStatements.screen(summary);

    assertEquals("Looks solid.", screened.overallAssessment());
    assertEquals("Adds a cache layer.", screened.prPurpose());
    assertEquals(4, screened.totalFindings());
    assertEquals(1, screened.high());
    assertEquals(3, screened.medium());
    assertEquals(gaps, screened.descriptionGaps());
    assertEquals(List.of("enhancement"), screened.suggestedLabels());
    assertEquals(files, screened.fileSummaries());
    assertEquals("flowchart TD", screened.walkthroughDiagram());
    assertEquals(List.of("G1: done"), screened.addressedGaps());
  }

  @Test
  void aSummaryWithNoCountIsReturnedAsItIs() {
    var summary =
        new ReviewResponse.Summary(0, 0, 0, 0, 0, "Looks solid.", "Adds a cache.", List.of());

    assertSame(summary, CountStatements.screen(summary));
    assertNull(CountStatements.screen(null));
  }

  @Test
  void anAssessmentAloneWithACountIsScreened() {
    var summary =
        new ReviewResponse.Summary(
            0, 0, 0, 0, 0, "Two findings remain.", "Adds a cache.", List.of());

    var screened = CountStatements.screen(summary);

    assertEquals("", screened.overallAssessment());
    assertEquals("Adds a cache.", screened.prPurpose());
  }
}
