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

import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class DiffLineResolverTest {

  private static final String PATCH =
      """
      @@ -10,3 +10,4 @@
       def unchanged():
      -    old_line()
      +    new_line()
      +    added_line()
      """;

  @Test
  void shouldAcceptExactLineInDiff() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertEquals(OptionalInt.of(11), resolver.resolveRightSideLine("main.py", 11));
  }

  @Test
  void shouldSnapToNearestCommentableLine() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertEquals(OptionalInt.of(12), resolver.resolveRightSideLine("main.py", 15));
    assertEquals(OptionalInt.of(10), resolver.resolveRightSideLine("main.py", 5));
  }

  @Test
  void shouldReturnEmptyWhenFileNotInDiff() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertTrue(resolver.resolveRightSideLine("other.py", 10).isEmpty());
  }

  @Test
  void shouldParseRightSideLinesFromPatch() {
    var lines = DiffLineResolver.parseRightSideLines(PATCH);

    assertEquals(3, lines.size());
    assertTrue(lines.contains(10));
    assertTrue(lines.contains(11));
    assertTrue(lines.contains(12));
  }

  @Test
  void shouldReturnEmptyForInvalidInputs() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertTrue(resolver.resolveRightSideLine(null, 10).isEmpty());
    assertTrue(resolver.resolveRightSideLine("main.py", 0).isEmpty());
    assertTrue(resolver.resolveRightSideLine("main.py", -1).isEmpty());
  }

  @Test
  void shouldReturnEmptyForBlankPatch() {
    var resolver = new DiffLineResolver(Map.of("main.py", ""));

    assertTrue(resolver.resolveRightSideLine("main.py", 10).isEmpty());
    assertTrue(DiffLineResolver.parseRightSideLines(null).isEmpty());
    assertTrue(DiffLineResolver.parseRightSideLines("   ").isEmpty());
  }

  @Test
  void shouldPreferCeilingWhenItIsCloserThanFloor() {
    var patch =
        """
        @@ -1,1 +10,1 @@
        +ten
        @@ -1,1 +20,1 @@
        +twenty
        """;
    var resolver = new DiffLineResolver(Map.of("main.py", patch));

    assertEquals(OptionalInt.of(20), resolver.resolveRightSideLine("main.py", 17));
  }

  @Test
  void shouldPreferCloserLineWhenSnapping() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertEquals(OptionalInt.of(11), resolver.resolveRightSideLine("main.py", 11));
    assertEquals(OptionalInt.of(12), resolver.resolveRightSideLine("main.py", 13));
    assertEquals(OptionalInt.of(10), resolver.resolveRightSideLine("main.py", 9));
  }

  @Test
  void shouldUseCeilingWhenOnlyAboveRequestedLineExists() {
    var patch =
        """
        @@ -1,1 +20,1 @@
        -old
        +new
        """;
    var resolver = new DiffLineResolver(Map.of("main.py", patch));

    assertEquals(OptionalInt.of(20), resolver.resolveRightSideLine("main.py", 5));
  }

  @Test
  void shouldUseFloorWhenOnlyBelowRequestedLineExists() {
    var patch =
        """
        @@ -1,1 +5,1 @@
        -old
        +new
        """;
    var resolver = new DiffLineResolver(Map.of("main.py", patch));

    assertEquals(OptionalInt.of(5), resolver.resolveRightSideLine("main.py", 99));
  }

  @Test
  void isLineInDiffShouldAcceptExactLineAndLinesWithinTolerance() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertTrue(resolver.isLineInDiff("main.py", 11, 3)); // exact right-side line
    assertTrue(resolver.isLineInDiff("main.py", 13, 3)); // one past line 12, within tolerance
    assertTrue(resolver.isLineInDiff("main.py", 8, 3)); // two before line 10, within tolerance
  }

  @Test
  void isLineInDiffShouldRejectLineFarFromDiffUnlikeResolveRightSideLine() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    // resolveRightSideLine snaps a far line onto the nearest hunk line; isLineInDiff must not, so
    // the approve backstop never treats a merely-touched file as a still-present finding.
    assertEquals(OptionalInt.of(12), resolver.resolveRightSideLine("main.py", 99));
    assertFalse(resolver.isLineInDiff("main.py", 99, 3)); // only a floor exists, far below
    assertFalse(resolver.isLineInDiff("main.py", 2, 3)); // only a ceiling exists, far above
  }

  @Test
  void isLineInDiffShouldRejectWhenFileHasNoRightSideLines() {
    var deletionOnly =
        """
        @@ -10,2 +10,0 @@
        -removed_one
        -removed_two
        """;
    var resolver = new DiffLineResolver(Map.of("main.py", deletionOnly));

    assertFalse(resolver.isLineInDiff("main.py", 10, 3));
  }

  @Test
  void isLineInDiffShouldRejectMissingFileAndInvalidInputs() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertFalse(resolver.isLineInDiff("other.py", 11, 3));
    assertFalse(resolver.isLineInDiff(null, 11, 3));
    assertFalse(resolver.isLineInDiff("main.py", 0, 3));
  }

  @Test
  void isLineInDiffShouldMatchAcrossPathVariants() {
    var resolver = new DiffLineResolver(Map.of("src/dir/Main.java", PATCH));

    // The model may report a shorter path than GitHub's diff filename; FilePaths.same bridges them.
    assertTrue(resolver.isLineInDiff("dir/Main.java", 11, 3));
  }

  @Test
  void shouldIgnoreDeletionOnlyLines() {
    var patch =
        """
        @@ -10,2 +10,0 @@
        -removed_one
        -removed_two
        """;
    var lines = DiffLineResolver.parseRightSideLines(patch);

    assertTrue(lines.isEmpty());
  }

  @Test
  void shouldTrackContextLinesInPatch() {
    var patch =
        """
        @@ -1,2 +1,2 @@
         context
        -old
        +new
        """;
    var lines = DiffLineResolver.parseRightSideLines(patch);

    assertEquals(2, lines.size());
    assertTrue(lines.contains(1));
    assertTrue(lines.contains(2));
  }

  @Test
  void isFindingPresentShouldHoldAnchorThatDriftedBeyondToleranceViaContent() {
    // The still-open code survived verbatim but a force-push pushed it from old line 10 to line 32.
    var patch =
        """
        @@ -10,2 +30,3 @@
         keep_one()
        +inserted_line()
             dangerous_call()
        """;
    var resolver = new DiffLineResolver(Map.of("A.java", patch));

    // The raw line-number proxy drops it (32 is far from the old line 10); content matching holds.
    assertFalse(resolver.isLineInDiff("A.java", 10, 3));
    assertTrue(resolver.isFindingPresent("A.java", 10, "dangerous_call()", 3));
  }

  @Test
  void isFindingPresentShouldNotHoldDeletedCodeWhenOnlyNearbyContextSurvives() {
    // #129(b), literal mechanism: the flagged line 42 is deleted outright; the bug-block survives
    // only as left-side deletions, while context lines 38-39 / 44-45 remain on the right side.
    var patch =
        """
        @@ -38,8 +38,4 @@
         ctx_38()
         ctx_39()
        -buggy_40()
        -buggy_41()
        -buggy_42()
        -buggy_43()
         ctx_44()
         ctx_45()
        """;
    var resolver = new DiffLineResolver(Map.of("A.java", patch));

    // The raw line-number proxy is fooled: stale line 42 is within ±3 of the surviving context
    // line now at right-line 41 (ctx_45), so it reads "present" though the bug was deleted.
    assertTrue(resolver.isLineInDiff("A.java", 42, 3));
    // Content matching is not: the anchor exists only as a deletion, never on the right side.
    assertFalse(resolver.isFindingPresent("A.java", 42, "buggy_42()", 3));
  }

  @Test
  void isFindingPresentShouldRequireAnchorAsContiguousInOrderRun() {
    var patch =
        """
        @@ -1,3 +1,3 @@
         alpha()
         beta()
         gamma()
        """;
    var resolver = new DiffLineResolver(Map.of("A.java", patch));

    assertTrue(resolver.isFindingPresent("A.java", 1, "alpha()\nbeta()", 3));
    assertTrue(resolver.isFindingPresent("A.java", 1, "beta()\ngamma()", 3));
    // A line changed away breaks the block.
    assertFalse(resolver.isFindingPresent("A.java", 1, "alpha()\ndelta()", 3));
    // Lines that survive only non-adjacently or out of order are not a contiguous run (#129(b)).
    assertFalse(resolver.isFindingPresent("A.java", 1, "alpha()\ngamma()", 3));
    assertFalse(resolver.isFindingPresent("A.java", 1, "gamma()\nbeta()", 3));
  }

  @Test
  void isFindingPresentShouldNotHoldMultiLineAnchorSurvivingScattered() {
    // The flagged two-line block was replaced, but each of its lines coincidentally survives in an
    // unrelated place; they never appear adjacent, so the block counts as gone (#129(b)).
    var patch =
        """
        @@ -1,5 +1,5 @@
         validate(x);
         do_one();
         do_two();
         do_three();
         persist(x);
        """;
    var resolver = new DiffLineResolver(Map.of("A.java", patch));

    assertFalse(resolver.isFindingPresent("A.java", 1, "validate(x);\npersist(x);", 3));
  }

  @Test
  void isFindingPresentShouldLeanTowardHoldingForARecurringSingleLineAnchor() {
    // Accepted residual: a generic single-line anchor that recurs verbatim reads as present even
    // when the flagged occurrence changed away. The stale prior-revision line cannot disambiguate
    // it, so the downgrade-only backstop errs toward holding rather than risking the #118 drop.
    var patch =
        """
        @@ -1,6 +1,6 @@
         if (a) {
        -  return null;
        +  return value;
         }
         if (b) {
           return null;
         }
        """;
    var resolver = new DiffLineResolver(Map.of("A.java", patch));

    assertTrue(resolver.isFindingPresent("A.java", 2, "return null;", 3));
  }

  @Test
  void isFindingPresentShouldNeutralizeDiffMarkersBeforeMatching() {
    // suggestion_old is quoted from the neutralized diff (<<DIFF_START>>), but the raw GitHub patch
    // still carries the triple-bracket sentinel; the patch side must be neutralized to match.
    var patch =
        """
        @@ -1,0 +1,1 @@
        +String s = "<<<DIFF_START>>>";
        """;
    var resolver = new DiffLineResolver(Map.of("A.java", patch));

    assertTrue(resolver.isFindingPresent("A.java", 1, "String s = \"<<DIFF_START>>\";", 3));
  }

  @Test
  void isFindingPresentShouldIgnoreBlankAnchorLines() {
    var patch =
        """
        @@ -1,2 +1,2 @@
         alpha()
        +beta()
        """;
    var resolver = new DiffLineResolver(Map.of("A.java", patch));

    // Blank lines in the anchor must not be required against the right-side text.
    assertTrue(resolver.isFindingPresent("A.java", 1, "alpha()\n\n   \nbeta()", 3));
  }

  @Test
  void isFindingPresentShouldFallBackToLineMembershipWithoutAnchor() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    // No anchor (null or blank): behaves like isLineInDiff.
    assertTrue(resolver.isFindingPresent("main.py", 11, null, 3));
    assertTrue(resolver.isFindingPresent("main.py", 11, "   ", 3));
    assertFalse(resolver.isFindingPresent("main.py", 99, null, 3));
  }

  @Test
  void isFindingPresentShouldMatchAnchorAcrossPathVariantsAndIgnoreLine() {
    var resolver = new DiffLineResolver(Map.of("src/dir/Main.java", PATCH));

    // Path variant resolves, indentation is stripped, and the stale line number is irrelevant.
    assertTrue(resolver.isFindingPresent("dir/Main.java", 999, "new_line()", 3));
  }

  @Test
  void isFindingPresentShouldRejectNullFileAndAnchorOutsideItsOwnFile() {
    var resolver = new DiffLineResolver(Map.of("A.java", PATCH));

    assertFalse(resolver.isFindingPresent(null, 11, "new_line()", 3));
    // The anchor text exists in A.java's diff, but the finding points at a file not in the diff —
    // matching is file-scoped so an unrelated file never validates the finding.
    assertFalse(resolver.isFindingPresent("B.java", 11, "new_line()", 3));
  }

  @Test
  void isFindingPresentShouldNotHoldWhenFileHasOnlyDeletions() {
    var deletionOnly =
        """
        @@ -10,2 +10,0 @@
        -buggy_call()
        -also_removed()
        """;
    var resolver = new DiffLineResolver(Map.of("A.java", deletionOnly));

    // The flagged code was deleted outright: it survives only on the left side, so the file's
    // right-side text is empty and the finding counts as no longer present.
    assertFalse(resolver.isFindingPresent("A.java", 10, "buggy_call()", 3));
  }

  @Test
  void shouldTrackBlankRightSideLineNumberButNotItsText() {
    var patch =
        """
        @@ -1,0 +1,3 @@
        +first();
        +
        +third();
        """;
    var lines = DiffLineResolver.parseRightSideLines(patch);
    var resolver = new DiffLineResolver(Map.of("A.java", patch));

    // A blank added line still advances the right-side counter (it is a commentable line) ...
    assertEquals(3, lines.size());
    // ... but is excluded from the right-side text, so "first();" and "third();" are adjacent and
    // match contiguously. Were the blank kept, it would sit between them and break the run.
    assertTrue(resolver.isFindingPresent("A.java", 1, "first();\nthird();", 3));
    assertFalse(resolver.isFindingPresent("A.java", 1, "second();", 3));
  }
}
