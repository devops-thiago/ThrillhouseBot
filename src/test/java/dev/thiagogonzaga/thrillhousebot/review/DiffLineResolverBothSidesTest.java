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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.RemovedRange;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.Side;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #1321: the resolver over both sides of each patch — no run across two hunks, quotes of removed
 * lines located on the base side, and presence that reads the head file when an anchor runs past
 * the patch's context (#1318).
 */
class DiffLineResolverBothSidesTest {

  private static final String FILE = "src/A.java";

  /** Reads the files it is given and records every path asked for. */
  private static final class RecordingHeadFiles implements DiffLineResolver.HeadFiles {
    private final Map<String, List<String>> files;
    private final List<String> asked = new ArrayList<>();

    RecordingHeadFiles(Map<String, List<String>> files) {
      this.files = files;
    }

    @Override
    public Optional<List<String>> lines(String path) {
      asked.add(path);
      return Optional.ofNullable(files.get(path));
    }
  }

  private static DiffLineResolver resolver(String patch, Map<String, List<String>> head) {
    return new DiffLineResolver(Map.of(FILE, patch), List.of(FILE), new RecordingHeadFiles(head));
  }

  /**
   * #1315 round 3, finding #4: its quote starts on line 843, one line before the hunk that starts
   * at 844, and the file did not change in the next round. The patch is the hunk GitHub returns for
   * that file at round 3's head ({@code a6310507}); the head file is that file at the pull
   * request's head, byte-identical to round 3's.
   */
  @Nested
  class Pr1315 {

    private static final String PATH =
        "src/test/java/dev/thiagogonzaga/thrillhousebot/github/RepoSettingsResolverTest.java";

    private static final String ANCHOR =
        """
              assertEquals(
                  java.util.List.of("gen/**"),
                  resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID).ignoredFiles());\
        """;

    private static final String ROUND_3_FINDING_JSON =
        """
        {"findings": [
          {"risk": "medium", "confidence": "high", "file": "%s", "line": 848,
           "title": "Caching tests had their API-call verifies removed",
           "description": "The verifies were removed.",
           "suggestion_old": %s, "suggestion_new": null}
        ]}
        """;

    private final FollowUpAnalyzer analyzer = new FollowUpAnalyzer(new ObjectMapper());

    private String patch() throws IOException {
      return Files.readString(
          Path.of("src/test/resources/review/pr1315/RepoSettingsResolverTest.patch"));
    }

    private List<String> headFile() throws IOException {
      return Files.readString(
              Path.of("src/test/resources/review/pr1315/RepoSettingsResolverTest.java.txt"))
          .lines()
          .toList();
    }

    private DiffLineResolver resolver(boolean readsHead) throws IOException {
      Map<String, List<String>> head = readsHead ? Map.of(PATH, headFile()) : Map.of();
      return new DiffLineResolver(
          Map.of(PATH, patch()), List.of(PATH), new RecordingHeadFiles(head));
    }

    @Test
    void thePatchIsTheHeadFilesOwnLines() throws IOException {
      var resolver = resolver(true);
      var head = headFile();
      var rightSide = resolver.index().files().get(PATH).rightSide();
      assertEquals(844, rightSide.getFirst().rightLine());
      for (var line : rightSide) {
        assertEquals(head.get(line.rightLine() - 1), line.text(), "line " + line.rightLine());
      }
      assertFalse(resolver.resolveRightSideLine(PATH, 840).isPresent());
    }

    @Test
    void theAnchorIsPresentWhenTheHeadFileHoldsIt() throws IOException {
      assertTrue(resolver(true).isFindingPresent(PATH, ANCHOR));
    }

    @Test
    void thePatchAloneCannotPlaceTheAnchor() throws IOException {
      // The failure behind #1318: its first line is outside the hunk, so the patch alone reads it
      // as gone. The presence above comes from the head file, not from the patch.
      assertFalse(resolver(false).isFindingPresent(PATH, ANCHOR));
    }

    @Test
    void anUnresolvedFindingIsNotSupersededWhileItsFileIsUnchanged() throws IOException {
      var json =
          ROUND_3_FINDING_JSON.formatted(PATH, new ObjectMapper().writeValueAsString(ANCHOR));
      var statuses = List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", ""));

      var held = analyzer.supersedeVanished(json, statuses, resolver(true));
      var withoutHead = analyzer.supersedeVanished(json, statuses, resolver(false));

      assertEquals("unresolved", held.getFirst().status());
      assertEquals(FollowUpAnalyzer.STATUS_SUPERSEDED, withoutHead.getFirst().status());
    }

    @Test
    void theSameRunOfRemovedLinesElevenLinesDownIsNotTakenForIt() throws IOException {
      var resolver = resolver(true);

      // Lines 830-832 of the base hold the quote too, one of them removed, and their place in the
      // new file is 859-861: the finding's line 848 does not select them.
      assertEquals(Optional.empty(), resolver.locateRemovedQuote(PATH, ANCHOR, 848));
      assertEquals(
          Optional.of(new RemovedRange(830, 832, 859, 861)),
          resolver.locateRemovedQuote(PATH, ANCHOR, 860));
    }
  }

  @Nested
  class NoRunAcrossHunks {

    private static final String TWO_HUNKS =
        """
        @@ -1,3 +1,3 @@
         first();
        -old();
        +updated();
         endOfHunkOne();
        @@ -20,3 +20,3 @@
         startOfHunkTwo();
        -removedTwo();
        +addedTwo();
         last();
        """;

    @Test
    void aQuoteJoiningTheEndOfOneHunkToTheStartOfTheNextIsNotPresent() {
      var resolver = new DiffLineResolver(Map.of(FILE, TWO_HUNKS));

      assertFalse(resolver.isFindingPresent(FILE, "endOfHunkOne();\nstartOfHunkTwo();"));
      assertTrue(resolver.isFindingPresent(FILE, "updated();\nendOfHunkOne();"));
    }

    @Test
    void aQuoteJoiningTwoHunksIsNotLocated() {
      var resolver = new DiffLineResolver(Map.of(FILE, TWO_HUNKS));

      assertEquals(
          Optional.empty(), resolver.locateQuote(FILE, "endOfHunkOne();\nstartOfHunkTwo();", 3));
      assertEquals(
          Optional.empty(),
          resolver.resolveSuggestionRange(FILE, "endOfHunkOne();\nstartOfHunkTwo();"));
      assertEquals(
          Optional.of(new DiffLineResolver.LineRange(20, 21)),
          resolver.locateQuote(FILE, "startOfHunkTwo();\naddedTwo();", 20));
    }

    @Test
    void removedLinesJoiningTwoHunksAreNotLocatedOrPresent() {
      var resolver = new DiffLineResolver(Map.of(FILE, TWO_HUNKS));
      String joined = "endOfHunkOne();\nstartOfHunkTwo();\nremovedTwo();";

      assertEquals(Optional.empty(), resolver.locateRemovedQuote(FILE, joined, 20));
      assertFalse(resolver.isFindingPresent(FILE, joined, Side.LEFT));
    }
  }

  @Nested
  class RemovedLines {

    private static final String PATCH =
        """
        @@ -10,6 +10,4 @@
         keep();
        -verify(client).call();
        -verify(client).close();
         next();
        -stale();
        +fresh();
         end();
        """;

    @Test
    void aQuoteOfRemovedLinesIsLocatedOnTheBaseSide() {
      var resolver = new DiffLineResolver(Map.of(FILE, PATCH));
      String quote = "verify(client).call();\nverify(client).close();";

      assertEquals(Optional.empty(), resolver.locateQuote(FILE, quote, 11));
      assertEquals(
          Optional.of(new RemovedRange(11, 12, 11, 11)),
          resolver.locateRemovedQuote(FILE, quote, 11));
      assertEquals(
          Optional.of(new RemovedRange(11, 12, 11, 11)),
          resolver.locateRemovedQuote(FILE, quote, 13));
      assertEquals(
          Optional.of(new RemovedRange(10, 12, 10, 11)),
          resolver.locateRemovedQuote(FILE, "keep();\n" + quote, 10));
    }

    @Test
    void aQuoteOfADeletedFileStandsAtItsFirstLine() {
      var resolver = new DiffLineResolver(Map.of(FILE, "@@ -1,3 +0,0 @@\n-a();\n-b();\n-c();"));
      var cited =
          new ReviewResponse.Finding("high", "high", FILE, 0, "T", "D", "b();\nc();", "x();");

      assertEquals(
          Optional.of(new RemovedRange(2, 3, 1, 1)),
          resolver.locateRemovedQuote(FILE, "b();\nc();", 3));
      assertEquals(1, SuggestionAnchor.place(cited, resolver).line());
    }

    @Test
    void aRemovedQuoteTheCitedLineDoesNotSelectIsNotTaken() {
      var resolver = new DiffLineResolver(Map.of(FILE, PATCH));

      assertEquals(
          Optional.empty(), resolver.locateRemovedQuote(FILE, "verify(client).call();", 40));
    }

    @Test
    void contextAloneIsNotARemovedRun() {
      var resolver = new DiffLineResolver(Map.of(FILE, PATCH));

      assertEquals(Optional.empty(), resolver.locateRemovedQuote(FILE, "keep();", 10));
      assertEquals(Optional.empty(), resolver.locateRemovedQuote(FILE, " ", 10));
      assertEquals(Optional.empty(), resolver.locateRemovedQuote(null, "keep();", 10));
      assertEquals(Optional.empty(), resolver.locateRemovedQuote("other.java", "stale();", 13));
    }

    @Test
    void aQuoteStillInTheNewFileIsNeverPlacedOnRemovedLines() {
      // foo() is removed near line 5 and still added twice further on, so locateQuote cannot
      // choose between the two right-side runs; the quote is still not about removed code.
      var patch =
          "@@ -4,3 +4,4 @@\n keep();\n-foo();\n+bar();\n+foo();\n@@ -20 +21,2 @@\n end();\n+foo();";
      var resolver = new DiffLineResolver(Map.of(FILE, patch));

      assertEquals(Optional.empty(), resolver.locateQuote(FILE, "foo();", 40));
      assertEquals(Optional.empty(), resolver.locateRemovedQuote(FILE, "foo();", 5));
    }

    @Test
    void twoRemovedRunsEquallyNearLeaveTheLocationOpen() {
      var twice =
          """
          @@ -1,4 +1,2 @@
           a();
          -dup();
           b();
          -dup();
          """;
      var resolver = new DiffLineResolver(Map.of(FILE, twice));

      assertEquals(Optional.empty(), resolver.locateRemovedQuote(FILE, "dup();", 5));
    }

    @Test
    void aRemovalIsPresentWhileThePatchStillRemovesIt() {
      var resolver = resolver(PATCH, Map.of());

      assertTrue(resolver.isFindingPresent(FILE, "verify(client).close();", Side.LEFT));
      assertFalse(resolver.isFindingPresent(FILE, "verify(client).close();", Side.RIGHT));
      // Context the patch keeps is not removed code, and no head file is read for it here.
      assertFalse(resolver.isFindingPresent(FILE, "keep();", Side.LEFT));
    }

    @Test
    void aRemovalThePatchNoLongerShowsIsPresentWhileTheHeadFileLacksIt() {
      var otherChange = "@@ -1,1 +1,1 @@\n-x();\n+y();\n";
      var stillRemoved = resolver(otherChange, Map.of(FILE, List.of("y();", "keep();")));
      var restored = resolver(otherChange, Map.of(FILE, List.of("y();", "verify(client).call();")));
      var unreadable = resolver(otherChange, Map.of());

      assertTrue(stillRemoved.isFindingPresent(FILE, "verify(client).call();", Side.LEFT));
      assertFalse(restored.isFindingPresent(FILE, "verify(client).call();", Side.LEFT));
      assertFalse(unreadable.isFindingPresent(FILE, "verify(client).call();", Side.LEFT));
    }

    @Test
    void withoutAnAnchorEitherSideAsksWhetherTheFileChanged() {
      var resolver = new DiffLineResolver(Map.of(FILE, PATCH));

      assertTrue(resolver.isFindingPresent(FILE, null, Side.LEFT));
      assertFalse(resolver.isFindingPresent(null, "stale();", Side.LEFT));
    }
  }

  @Nested
  class HeadFileFallback {

    private static final String PATCH = "@@ -5,2 +5,2 @@\n context();\n-old();\n+new();\n";

    @Test
    void anAnchorOutsideThePatchIsFoundInTheHeadFile() {
      var head = Map.of(FILE, List.of("a();", "b();", "", "", "c();", "context();", "new();"));
      var resolver = resolver(PATCH, head);

      assertTrue(resolver.isFindingPresent(FILE, "a();\nb();\nc();"));
      assertFalse(resolver.isFindingPresent(FILE, "b();\na();"));
      assertFalse(resolver.isFindingPresent(FILE, "c();\ncontext();\nmissing();"));
    }

    @Test
    void aShortPathReadsTheVariantThatKeepsTheFileNotADeletedNamesake() {
      var deleted = "a/util/H.java";
      var kept = "b/util/H.java";
      var head = new RecordingHeadFiles(Map.of(kept, List.of("x();", "y();", "z();")));
      var resolver =
          new DiffLineResolver(
              Map.of(
                  deleted, "@@ -1,2 +0,0 @@\n-old();\n-gone();", kept, "@@ -3 +3 @@\n-q();\n+z();"),
              List.of(deleted, kept),
              head);

      assertTrue(resolver.isFindingPresent("util/H.java", "x();\ny();"));
      assertEquals(List.of(kept), head.asked);
    }

    @Test
    void aRunCutOffByTheEndOfTheFileIsNotPresent() {
      var endsInABlank = Map.of(FILE, List.of("a();", ""));
      var endsAtTheLine = Map.of(FILE, List.of("context();", "new();", "a();"));

      assertFalse(resolver(PATCH, endsInABlank).isFindingPresent(FILE, "a();\nb();"));
      assertFalse(resolver(PATCH, endsAtTheLine).isFindingPresent(FILE, "a();\nb();"));
    }

    @Test
    void moreThanTwoBlankLinesBreakARun() {
      var head = Map.of(FILE, List.of("a();", "", "", "", "b();", "context();", "new();"));

      assertFalse(resolver(PATCH, head).isFindingPresent(FILE, "a();\nb();"));
    }

    @Test
    void aPatchMatchNeedsNoRead() {
      var headFiles = new RecordingHeadFiles(Map.of());
      var resolver = new DiffLineResolver(Map.of(FILE, PATCH), List.of(FILE), headFiles);

      assertTrue(resolver.isFindingPresent(FILE, "context();\nnew();"));
      assertEquals(List.of(), headFiles.asked);
    }

    @Test
    void onlyAChangedFileIsReadAndByItsOwnPath() {
      var headFiles = new RecordingHeadFiles(Map.of(FILE, List.of("a();")));
      var resolver = new DiffLineResolver(Map.of(FILE, PATCH), List.of(FILE), headFiles);

      assertTrue(resolver.isFindingPresent("repo/src/A.java", "a();"));
      assertFalse(resolver.isFindingPresent("src/B.java", "a();"));
      assertEquals(List.of(FILE), headFiles.asked);
    }

    @Test
    void aRemovedFileIsNotRead() {
      var headFiles = new RecordingHeadFiles(Map.of(FILE, List.of("gone();")));
      var resolver =
          new DiffLineResolver(
              Map.of(FILE, "@@ -1,1 +0,0 @@\n-gone();\n"), List.of(FILE), headFiles);

      assertFalse(resolver.isFindingPresent(FILE, "gone();"));
      assertEquals(List.of(), headFiles.asked);
    }

    @Test
    void withoutAHeadReaderPresenceIsWhatThePatchShows() {
      var resolver = new DiffLineResolver(Map.of(FILE, PATCH), List.of(FILE));

      assertFalse(resolver.isFindingPresent(FILE, "a();"));
    }
  }

  @Test
  void theIndexHoldsEveryPatchWithBothSides() {
    var resolver = new DiffLineResolver(Map.of(FILE, "@@ -3,2 +3,2 @@\n-old();\n+new();\n"));

    var lines = resolver.index().files().get(FILE).lines();

    assertEquals(
        List.of(
            new PatchIndex.Line(0, PatchIndex.Kind.DELETED, 3, 3, "old();", 1),
            new PatchIndex.Line(0, PatchIndex.Kind.ADDED, 4, 3, "new();", 2)),
        lines);
  }
}
