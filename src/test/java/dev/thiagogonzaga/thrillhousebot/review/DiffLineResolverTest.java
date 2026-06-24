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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
    assertTrue(resolver.isFindingPresent("A.java", "dangerous_call()"));
  }

  @Test
  void isFindingPresentShouldNotHoldDeletedCodeWhenOnlyNearbyContextSurvives() {
    // literal mechanism: the flagged line 42 is deleted outright; the bug-block survives
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
    assertFalse(resolver.isFindingPresent("A.java", "buggy_42()"));
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

    assertTrue(resolver.isFindingPresent("A.java", "alpha()\nbeta()"));
    assertTrue(resolver.isFindingPresent("A.java", "beta()\ngamma()"));
    // A line changed away breaks the block.
    assertFalse(resolver.isFindingPresent("A.java", "alpha()\ndelta()"));
    // Lines that survive only non-adjacently or out of order are not a contiguous run.
    assertFalse(resolver.isFindingPresent("A.java", "alpha()\ngamma()"));
    assertFalse(resolver.isFindingPresent("A.java", "gamma()\nbeta()"));
  }

  @Test
  void isFindingPresentShouldNotHoldMultiLineAnchorSurvivingScattered() {
    // The flagged two-line block was replaced, but each of its lines coincidentally survives in an
    // unrelated place; they never appear adjacent, so the block counts as gone.
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

    assertFalse(resolver.isFindingPresent("A.java", "validate(x);\npersist(x);"));
  }

  static Stream<Arguments> findingPresentByContentCases() {
    return Stream.of(
        // Accepted residual: a generic single-line anchor that recurs verbatim reads as present
        // even when the flagged occurrence changed away. The stale prior-revision line cannot
        // disambiguate it, so the downgrade-only backstop errs toward holding (the drop is
        // worse than a needless hold).
        arguments(
            "recurring single-line anchor leans toward holding",
            """
            @@ -1,6 +1,6 @@
             if (a) {
            -  return null;
            +  return value;
             }
             if (b) {
               return null;
             }
            """,
            "return null;"),
        // suggestion_old is quoted from the byte-exact fenced diff, so the triple-bracket sentinel
        // is matched verbatim against the raw patch — no neutralization.
        arguments(
            "diff markers match byte-exact",
            """
            @@ -1,0 +1,1 @@
            +String s = "<<<DIFF_START>>>";
            """,
            "String s = \"<<<DIFF_START>>>\";"),
        // Blank lines in the anchor must not be required against the right-side text.
        arguments(
            "blank anchor lines are ignored",
            """
            @@ -1,2 +1,2 @@
             alpha()
            +beta()
            """,
            "alpha()\n\n   \nbeta()"));
  }

  /**
   * A finding's anchor that survives on the diff's right side reads as present — whether the anchor
   * is a recurring single line, carries neutralized diff markers, or contains blank lines — so the
   * approve backstop holds it.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("findingPresentByContentCases")
  void isFindingPresentShouldMatchSurvivingAnchorContent(String name, String patch, String anchor) {
    var resolver = new DiffLineResolver(Map.of("A.java", patch));

    assertTrue(resolver.isFindingPresent("A.java", anchor));
  }

  @Test
  void isFindingPresentShouldFallBackToHoldingWithoutAnchorIfFileHasChanges() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    // No anchor (null or blank): leans toward holding (returns true) if the file has changes.
    assertTrue(resolver.isFindingPresent("main.py", null));
    assertTrue(resolver.isFindingPresent("main.py", "   "));
    assertTrue(resolver.isFindingPresent("main.py", null));
    assertFalse(resolver.isFindingPresent("other.py", null));
  }

  @Test
  void isFindingPresentShouldMatchAnchorAcrossPathVariantsAndIgnoreLine() {
    var resolver = new DiffLineResolver(Map.of("src/dir/Main.java", PATCH));

    // Path variant resolves, indentation is stripped, and the stale line number is irrelevant.
    assertTrue(resolver.isFindingPresent("dir/Main.java", "new_line()"));
  }

  @Test
  void isFindingPresentShouldRejectNullFileAndAnchorOutsideItsOwnFile() {
    var resolver = new DiffLineResolver(Map.of("A.java", PATCH));

    assertFalse(resolver.isFindingPresent(null, "new_line()"));
    // The anchor text exists in A.java's diff, but the finding points at a file not in the diff —
    // matching is file-scoped so an unrelated file never validates the finding.
    assertFalse(resolver.isFindingPresent("B.java", "new_line()"));
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
    assertFalse(resolver.isFindingPresent("A.java", "buggy_call()"));
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
    assertTrue(resolver.isFindingPresent("A.java", "first();\nthird();"));
    assertFalse(resolver.isFindingPresent("A.java", "second();"));
  }

  @Test
  void isLineInDiffFallsBackToVariantWhenExactEntryIsEmpty() {
    // a deletion-only file stores its exact-path key with an empty right side, while a
    // longer path variant holds the real right-side lines. The empty exact entry must not
    // short-circuit the variant fallback and drop a still-open finding.
    var deletionOnly =
        """
        @@ -10,2 +10,0 @@
        -removed_one
        -removed_two
        """;
    var resolver =
        new DiffLineResolver(Map.of("dir/Main.java", deletionOnly, "src/dir/Main.java", PATCH));

    assertTrue(resolver.isLineInDiff("dir/Main.java", 11, 3));
  }

  @Test
  void isFindingPresentFallsBackToVariantWhenExactEntryIsEmpty() {
    // content path: the deletion-only exact entry is empty, so the anchor must be matched
    // against the variant that actually carries the flagged code.
    var deletionOnly =
        """
        @@ -10,2 +10,0 @@
        -removed_one
        -removed_two
        """;
    var resolver =
        new DiffLineResolver(Map.of("dir/Main.java", deletionOnly, "src/dir/Main.java", PATCH));

    assertTrue(resolver.isFindingPresent("dir/Main.java", "new_line()"));
  }

  @Test
  void isFindingPresentTreatsNonEmptyExactMatchAsAuthoritativeOverVariant() {
    // When the finding's own file is in the diff (non-empty right side), presence is decided from
    // it alone: a same-suffix variant that still contains the anchor must not resurrect a finding
    // whose code was changed away in its own file.
    var exactChangedAway =
        """
        @@ -1,1 +1,1 @@
        -dangerous_call()
        +safe_call()
        """;
    var variantStillHasIt =
        """
        @@ -1,0 +1,1 @@
        +dangerous_call()
        """;
    var resolver =
        new DiffLineResolver(
            Map.of("dir/Helper.java", exactChangedAway, "src/dir/Helper.java", variantStillHasIt));

    assertFalse(resolver.isFindingPresent("dir/Helper.java", "dangerous_call()"));
  }

  @Test
  void isFindingPresentHoldsWhicheverSuffixVariantCarriesTheSurvivingCode() {
    // two changed files share the suffix "util/Helper.java", so the ambiguous shortened
    // model path FilePaths.same-matches both. The flagged code survives in only one of them. The
    // old first-match-wins lookup would test presence against whichever variant the map yielded
    // first and silently drop the finding when that was the wrong one; the scan now checks EVERY
    // matching variant, so a decoy variant lacking the anchor cannot shadow the one that has it.
    // Asserting the hold for both placements documents that the decision does not depend on which
    // suffix-colliding variant happens to carry the code (the placement where the anchor lands in a
    // non-first-iterated variant is the one that actually pins the all-variants scan).
    var withAnchor =
        """
        @@ -1,0 +1,1 @@
        +dangerous_call()
        """;
    var withoutAnchor =
        """
        @@ -1,0 +1,1 @@
        +safe_call()
        """;
    var anchorInA =
        new DiffLineResolver(
            Map.of("a/util/Helper.java", withAnchor, "b/util/Helper.java", withoutAnchor));
    var anchorInB =
        new DiffLineResolver(
            Map.of("a/util/Helper.java", withoutAnchor, "b/util/Helper.java", withAnchor));

    assertTrue(anchorInA.isFindingPresent("util/Helper.java", "dangerous_call()"));
    assertTrue(anchorInB.isFindingPresent("util/Helper.java", "dangerous_call()"));
  }

  @Test
  void isFindingPresentDoesNotHoldWhenAnchorSurvivesInNoVariant() {
    // genuinely ambiguous path, but the flagged code survives in neither candidate — the
    // finding is not held.
    var withoutAnchor =
        """
        @@ -1,0 +1,1 @@
        +safe_call()
        """;
    var resolver =
        new DiffLineResolver(
            Map.of("a/util/Helper.java", withoutAnchor, "b/util/Helper.java", withoutAnchor));

    assertFalse(resolver.isFindingPresent("util/Helper.java", "dangerous_call()"));
  }

  @Test
  void isLineInDiffChecksEveryMatchingVariantNotJustTheFirstCandidate() {
    // suffix-colliding files both FilePaths.same-match the shortened path, but only one
    // carries the target line. The scan checks every matching variant, so a decoy variant whose
    // lines are elsewhere cannot shadow the one that holds the line — the old first-match lookup
    // could have tested the decoy and dropped the match.
    var atLine20 =
        """
        @@ -1,1 +20,1 @@
        +twenty
        """;
    var farAway =
        """
        @@ -1,1 +99,1 @@
        +far
        """;
    var resolver =
        new DiffLineResolver(Map.of("a/util/Helper.java", atLine20, "b/util/Helper.java", farAway));

    assertTrue(resolver.isLineInDiff("util/Helper.java", 20, 3));
    assertFalse(resolver.isLineInDiff("util/Helper.java", 50, 3));
  }

  @Test
  void isFindingPresentSkipsEmptyVariantSoDeletionOnlyFileDoesNotHold() {
    // A path variant whose only patch is deletion-only carries no right-side text. It is skipped
    // (not matched as an empty entry), so a finding whose shortened path resolves solely to that
    // deleted file counts as no longer present.
    var deletionOnly =
        """
        @@ -1,2 +1,0 @@
        -dangerous_call()
        -also_removed()
        """;
    var resolver = new DiffLineResolver(Map.of("a/util/Helper.java", deletionOnly));

    assertFalse(resolver.isFindingPresent("util/Helper.java", "dangerous_call()"));
  }

  @Test
  void shouldNotBindBareFilenameToDirectoryVariantThroughTheFallback() {
    // The variant scan delegates path matching to FilePaths.same, whose guard requires the shorter
    // path to contain a directory segment. A bare "B.java" must never bind to a directory-bearing
    // key like "src/B.java" — a bare name matches every file of that name in the repo, and a wrong
    // bind here would falsely hold or clear approval.
    var resolver = new DiffLineResolver(Map.of("src/B.java", PATCH));

    assertFalse(resolver.isFindingPresent("B.java", "new_line()"));
    assertFalse(resolver.isLineInDiff("B.java", 11, 3));
  }

  @Test
  void isLineInDiffTreatsNonEmptyExactMatchAsAuthoritativeOverVariant() {
    // The line-membership counterpart to the isFindingPresent authoritative-exact test: when the
    // file's own diff entry is non-empty, the decision is made from it alone — a same-suffix
    // variant
    // carrying the requested line must not be consulted.
    var exactAtLine20 =
        """
        @@ -1,1 +20,1 @@
        +twenty
        """;
    var variantAtLine50 =
        """
        @@ -1,1 +50,1 @@
        +fifty
        """;
    var resolver =
        new DiffLineResolver(
            Map.of("dir/F.java", exactAtLine20, "src/dir/F.java", variantAtLine50));

    // 50 is far from the exact entry's only line (20); the variant's line 50 is never consulted.
    assertFalse(resolver.isLineInDiff("dir/F.java", 50, 3));
    // Sanity: the exact entry's own line still resolves.
    assertTrue(resolver.isLineInDiff("dir/F.java", 20, 3));
  }

  @Test
  void resolveRightSideLineShouldMatchAcrossPathVariants() {
    var resolver = new DiffLineResolver(Map.of("src/dir/F.java", PATCH));
    assertEquals(OptionalInt.of(11), resolver.resolveRightSideLine("dir/F.java", 11));
  }

  @Test
  void resolveRightSideLineTreatsNonEmptyExactMatchAsAuthoritativeOverVariant() {
    var exactAtLine20 =
        """
        @@ -1,1 +20,1 @@
        +twenty
        """;
    var variantAtLine50 =
        """
        @@ -1,1 +50,1 @@
        +fifty
        """;
    var resolver =
        new DiffLineResolver(
            Map.of("dir/F.java", exactAtLine20, "src/dir/F.java", variantAtLine50));

    assertEquals(OptionalInt.of(20), resolver.resolveRightSideLine("dir/F.java", 50));
  }

  @Test
  void resolveRightSideLineReturnsEmptyOnAmbiguity() {
    var aVariant =
        """
        @@ -1,1 +20,1 @@
        +twenty
        """;
    var bVariant =
        """
        @@ -1,1 +50,1 @@
        +fifty
        """;
    var resolver = new DiffLineResolver(Map.of("a/dir/F.java", aVariant, "b/dir/F.java", bVariant));

    assertTrue(resolver.resolveRightSideLine("dir/F.java", 20).isEmpty());
  }

  @Test
  void resolveRightSideLineFallsBackToVariantWhenExactEntryIsEmpty() {
    var deletionOnly =
        """
        @@ -10,2 +10,0 @@
        -removed_one
        -removed_two
        """;
    var resolver =
        new DiffLineResolver(Map.of("dir/F.java", deletionOnly, "src/dir/F.java", PATCH));

    assertEquals(OptionalInt.of(11), resolver.resolveRightSideLine("dir/F.java", 11));
  }

  @Test
  void shouldReturnLineTextFromPatch() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertEquals("def unchanged():", resolver.getLineText("main.py", 10));
    assertEquals("    new_line()", resolver.getLineText("main.py", 11));
    assertEquals("    added_line()", resolver.getLineText("main.py", 12));
    assertNull(resolver.getLineText("main.py", 9));
    assertNull(resolver.getLineText("main.py", 0));
    assertNull(resolver.getLineText("main.py", -5));
    assertNull(resolver.getLineText("other.py", 11));
    assertNull(resolver.getLineText(null, 11));
  }

  @Test
  void shouldReturnLineTextAcrossPathVariants() {
    var deletionOnly =
        """
        @@ -1,2 +1,0 @@
        -dangerous_call()
        -also_removed()
        """;
    var resolver =
        new DiffLineResolver(Map.of("dir/Main.java", deletionOnly, "src/dir/Main.java", PATCH));

    // 1. Exact match exists but is empty (deletion-only) -> falls back to variant and resolves line
    assertEquals("    new_line()", resolver.getLineText("dir/Main.java", 11));

    // 2. Bare filename does not bind to directory variant (requires directory segment)
    assertNull(resolver.getLineText("Main.java", 11));

    // 3. Suffix variant with directory segment matches and resolves line
    var resolverSuffix =
        new DiffLineResolver(Map.of("a/dir/Main.java", deletionOnly, "b/dir/Main.java", PATCH));
    assertEquals("    new_line()", resolverSuffix.getLineText("dir/Main.java", 11));

    // 4. Both exact match and variants are empty (or no matching variant has the line) -> returns
    // null
    var emptyResolver = new DiffLineResolver(Map.of("dir/Main.java", deletionOnly));
    assertNull(emptyResolver.getLineText("dir/Main.java", 11));
    assertNull(emptyResolver.getLineText("Main.java", 11));
  }

  @Test
  void getLineTextReturnsNullOnAmbiguousSuffixCollision() {
    // Both a/dir/Main.java and b/dir/Main.java carry right-side text and are FilePaths.same to the
    // queried "dir/Main.java". With no unique resolution, getLineText must return null rather than
    // guess whichever entry the map happens to iterate first.
    var other =
        """
        @@ -10,3 +10,4 @@
         def unchanged():
        -    old_line()
        +    other_line()
        +    added_line()
        """;
    var resolver = new DiffLineResolver(Map.of("a/dir/Main.java", PATCH, "b/dir/Main.java", other));

    assertNull(resolver.getLineText("dir/Main.java", 11));
  }
}
