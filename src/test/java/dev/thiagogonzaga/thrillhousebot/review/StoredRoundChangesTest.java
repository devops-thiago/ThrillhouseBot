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

import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.FileChange;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.RaisedAnchor;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.Side;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StoredRoundChangesTest {

  private static final String FILE = "src/A.java";
  private static final String OTHER = "src/B.java";
  private static final String HEAD_1 = "AAAA111";
  private static final String HEAD_2 = "bbbb222";

  /** Answers every question the rows cannot with {@link FileChange#CHANGED}, so it shows. */
  private static final DiffLineResolver.RoundChanges FALLBACK = (round, path) -> FileChange.CHANGED;

  private static FindingLedger.Row row(
      String key, String file, String raisedHead, String blob, RaisedAnchor anchor) {
    return new FindingLedger.Row(
        key,
        new FindingLedger.Subject(file, 3, "high", "high", "t", "x();"),
        anchor,
        blob,
        new FindingLedger.Status(FindingLedger.OPEN, raisedHead, raisedHead),
        FindingLedger.Thread.NONE);
  }

  @Test
  void theSameBlobAtTheRaisingRoundAndNowIsUnchangedAndAnotherIsChanged() {
    var changes =
        new StoredRoundChanges(
            List.of(row("k1", FILE, HEAD_1, "blob-1", null), row("k2", OTHER, HEAD_1, "b-1", null)),
            List.of(HEAD_2, HEAD_1),
            Map.of(FILE, "BLOB-1", OTHER, "b-2"),
            FALLBACK);

    assertEquals(FileChange.UNCHANGED, changes.since(1, FILE), "the SHA case does not matter");
    assertEquals(FileChange.CHANGED, changes.since(1, OTHER));
  }

  @Test
  void whatTheRowsDoNotKnowIsTheFallbacksAnswer() {
    var changes =
        new StoredRoundChanges(
            List.of(
                row("k1", FILE, HEAD_1, "blob-1", null),
                row("k2", OTHER, null, "b", null),
                row("k3", null, HEAD_1, "b", null),
                row("k4", "src/C.java", HEAD_1, null, null)),
            Arrays.asList(null, HEAD_1),
            Map.of(OTHER, "b"),
            FALLBACK);

    assertEquals(FileChange.CHANGED, changes.since(1, FILE), "the file left this head's listing");
    assertEquals(FileChange.CHANGED, changes.since(0, FILE), "a round with no recorded head");
    assertEquals(FileChange.CHANGED, changes.since(2, FILE), "past the rounds");
    assertEquals(FileChange.CHANGED, changes.since(-1, FILE));
    assertEquals(FileChange.CHANGED, changes.since(1, OTHER), "raised at no known head");
    assertEquals(FileChange.CHANGED, changes.since(1, null));
    assertEquals(FileChange.CHANGED, changes.since(1, "src/C.java"), "no blob stored");
  }

  @Test
  void theRowsTellHowEachFindingWasAnchoredWhenRaised() {
    var left = new RaisedAnchor(Side.LEFT, false);
    var changes =
        new StoredRoundChanges(
            List.of(row("k1", FILE, HEAD_1, "b", left), row("k2", FILE, HEAD_1, "b", null)),
            List.of(),
            Map.of(),
            DiffLineResolver.RoundChanges.NONE);

    assertEquals(Optional.of(left), changes.raisedAnchor("k1"));
    assertEquals(Optional.empty(), changes.raisedAnchor("k2"));
    assertEquals(Optional.empty(), DiffLineResolver.RoundChanges.NONE.raisedAnchor("k1"));
  }

  /** The #1318 shape: a quote that runs past the patch, in a file the next round left alone. */
  private static final String PATCH = "@@ -5,2 +5,2 @@\n context();\n-old();\n+new();\n";

  private static ReviewResponse.Finding finding(String quote) {
    return new ReviewResponse.Finding("high", FILE, 6, "Title", "d", quote, null);
  }

  private static DiffLineResolver resolver(String key, RaisedAnchor anchor) {
    var rows =
        anchor == null
            ? List.<FindingLedger.Row>of()
            : List.of(row(key, FILE, HEAD_1, "b", anchor));
    return new DiffLineResolver(
        Map.of(FILE, PATCH),
        List.of(FILE),
        DiffLineResolver.HeadFiles.NONE,
        new StoredRoundChanges(rows, List.of(), Map.of(), DiffLineResolver.RoundChanges.NONE));
  }

  private static String keyOf(ReviewResponse.Finding finding) {
    return SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title());
  }

  @Test
  void aQuoteThatDidNotMatchInItsOwnRoundIsNotReadAsGoneOnlyBecauseItStillDoesNot() {
    var finding = finding("before();\ncontext();");

    assertTrue(
        resolver(keyOf(finding), new RaisedAnchor(Side.RIGHT, false))
            .isFindingStillPresent(finding, FILE));
    assertFalse(
        resolver(keyOf(finding), new RaisedAnchor(Side.RIGHT, true))
            .isFindingStillPresent(finding, FILE),
        "a quote that matched then and is gone now is gone");
    assertFalse(resolver(keyOf(finding), null).isFindingStillPresent(finding, FILE));
  }

  @Test
  void aFindingAboutRemovedCodeIsLookedForOnTheSideItWasRaisedOn() {
    var finding = finding("old();");

    assertTrue(
        resolver(keyOf(finding), new RaisedAnchor(Side.LEFT, true))
            .isFindingStillPresent(finding, FILE),
        "the patch still removes it");
    assertFalse(
        resolver(keyOf(finding), null).isFindingStillPresent(finding, FILE),
        "with nothing stored it is looked for on the head side, where it is not");
  }
}
