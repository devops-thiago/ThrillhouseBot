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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LargePrNudge}'s trigger conditions and rendered note. */
class LargePrNudgeTest {

  /** The shipped thresholds, enabled: 20 files or 1000 changed lines. */
  private static final LargePrNudge ENABLED = new LargePrNudge(true, 20, 1000);

  private static ReviewResult resultWith(List<Finding> findings) {
    return new ReviewResult(
        findings, 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);
  }

  private static ReviewResult clean() {
    return resultWith(List.of());
  }

  private static Finding inlineFinding() {
    return new Finding(
        RiskLevel.MEDIUM, Confidence.HIGH, "src/A.java", 12, "Real bug", "desc", null, null);
  }

  private static Finding doubleCheckFinding(String file) {
    return new Finding(
        RiskLevel.MEDIUM, Confidence.LOW, file, 7, "Possible NPE", "desc", null, null);
  }

  @Test
  void firesOnALargeFileCountWithNoFindings() {
    var note = ENABLED.render(42, 3102, 876, clean()).orElseThrow();

    assertTrue(note.startsWith(LargePrNudge.NUDGE_HEADING));
    assertTrue(note.contains("no inline findings across 42 changed files (+3102 -876)"));
    assertTrue(note.contains("`/review`"));
    assertTrue(note.contains("`/improve`"));
    assertTrue(note.contains("advisory"));
  }

  @Test
  void firesOnTheLineThresholdEvenWhenFewFilesChanged() {
    // A narrow-but-huge change is the same shape of risk as a wide one: either dimension is
    // enough on its own.
    var note = ENABLED.render(2, 900, 150, clean()).orElseThrow();

    assertTrue(note.contains("no inline findings across 2 changed files (+900 -150)"));
  }

  @Test
  void staysSilentOnASmallCleanPr() {
    // The "small PRs unchanged — no false escalation noise" criterion: 5 files / 120 lines is
    // under both bounds, so a clean review reads exactly as it does today.
    assertEquals(Optional.empty(), ENABLED.render(5, 100, 20, clean()));
  }

  @Test
  void staysSilentJustUnderBothThresholds() {
    // Boundary: the bounds are inclusive, so 19 files / 999 lines must not fire while 20 does.
    assertEquals(Optional.empty(), ENABLED.render(19, 500, 499, clean()));
    assertTrue(ENABLED.render(20, 500, 499, clean()).isPresent());
    assertTrue(ENABLED.render(19, 500, 500, clean()).isPresent());
  }

  @Test
  void staysSilentWhenAFindingOpenedAnInlineThread() {
    // The review already marked the diff, so there is nothing suspicious about the quiet.
    assertEquals(
        Optional.empty(), ENABLED.render(42, 3102, 876, resultWith(List.of(inlineFinding()))));
  }

  @Test
  void firesWhenEveryFindingWasTooLowConfidenceToPostInline() {
    // The issue's "or all low confidence" half: the diff itself still carries no thread, so the
    // note applies — and says how many findings were held back.
    var result =
        resultWith(List.of(doubleCheckFinding("src/A.java"), doubleCheckFinding("src/B.java")));

    var note = ENABLED.render(30, 2000, 400, result).orElseThrow();

    assertTrue(note.contains("The 2 findings it did raise were too low-confidence"));
    assertTrue(note.contains("Things to double-check"));
  }

  @Test
  void singularWordingForOneHeldBackFinding() {
    var result = resultWith(List.of(doubleCheckFinding("src/A.java")));

    var note = ENABLED.render(30, 2000, 400, result).orElseThrow();

    assertTrue(note.contains("The 1 finding it did raise was too low-confidence"));
  }

  @Test
  void singularWordingForASingleHugeFile() {
    var note = ENABLED.render(1, 1200, 300, clean()).orElseThrow();

    assertTrue(note.contains("across 1 changed file (+1200 -300)"));
  }

  @Test
  void staysSilentWhenDisabled() {
    var off = new LargePrNudge(false, 20, 1000);

    assertEquals(Optional.empty(), off.render(500, 90000, 40000, clean()));
    assertEquals(Optional.empty(), LargePrNudge.DISABLED.render(500, 90000, 40000, clean()));
  }

  @Test
  void aZeroBoundSwitchesOnlyItsOwnDimensionOff() {
    var linesOnly = new LargePrNudge(true, 0, 1000);
    assertEquals(Optional.empty(), linesOnly.render(500, 10, 10, clean()));
    assertTrue(linesOnly.render(1, 1000, 0, clean()).isPresent());

    var filesOnly = new LargePrNudge(true, 20, 0);
    assertEquals(Optional.empty(), filesOnly.render(1, 90000, 40000, clean()));
    assertTrue(filesOnly.render(20, 1, 0, clean()).isPresent());
  }

  @Test
  void bothBoundsZeroNeverFires() {
    var neither = new LargePrNudge(true, 0, 0);

    assertEquals(Optional.empty(), neither.render(5000, 900000, 400000, clean()));
  }

  @Test
  void readsItsPolicyFromConfig() {
    var config = mock(ThrillhouseConfig.LargePrNudgeConfig.class);
    when(config.enabled()).thenReturn(true);
    when(config.minFiles()).thenReturn(7);
    when(config.minChangedLines()).thenReturn(0);

    var nudge = LargePrNudge.from(config);

    assertEquals(new LargePrNudge(true, 7, 0), nudge);
    assertTrue(nudge.render(7, 10, 10, clean()).isPresent());
  }

  @Test
  void theNoteNeverClaimsToHoldApproval() {
    // The verdict gates in VerdictBuilder are deliberately untouched; the copy must not imply
    // otherwise, or a maintainer will wait for a block that never comes.
    var note = ENABLED.render(42, 3102, 876, clean()).orElseThrow();

    assertTrue(note.contains("it does not hold approval"));
    assertFalse(note.contains("blocked"));
  }
}
