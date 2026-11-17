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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #1322: a model status must echo the key of the finding listed under its id, and a "resolved" is
 * held to the code. The #1315 cases replay round 4 (session 9637, head {@code d8f86021}) against
 * round 3's stored findings (session 9636) and the pull request's patches at that head, as GitHub
 * served them (the compare of the base {@code cc71e8ba} with {@code d8f86021}).
 */
class ModelStatusScreenTest {

  private static final Path PR1315 = Path.of("src/test/resources/followup/pr1315");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static List<ReviewResponse.Finding> round3() throws IOException {
    return MAPPER
        .readValue(Files.readString(PR1315.resolve("round3-response.json")), ReviewResponse.class)
        .findings();
  }

  private static List<ReviewResponse.PreviousFindingStatus> round4Statuses() throws IOException {
    return MAPPER
        .readValue(Files.readString(PR1315.resolve("round4-statuses.json")), ReviewResponse.class)
        .previousFindingsStatus();
  }

  private static DiffLineResolver round4Diff() throws IOException {
    Map<String, String> patches =
        MAPPER.readValue(
            Files.readString(PR1315.resolve("round4-patches.json")), new TypeReference<>() {});
    return new DiffLineResolver(patches);
  }

  private static String keyOf(ReviewResponse.Finding finding) {
    return SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title());
  }

  /** {@code status} echoing the key of {@code finding}. */
  private static ReviewResponse.PreviousFindingStatus echoing(
      ReviewResponse.PreviousFindingStatus status, ReviewResponse.Finding finding) {
    return new ReviewResponse.PreviousFindingStatus(
        status.id(), status.status(), status.note(), keyOf(finding));
  }

  private static ReviewResponse.PreviousFindingStatus status(int id, String status, String key) {
    return new ReviewResponse.PreviousFindingStatus(id, status, "note", key);
  }

  @Test
  void pr1315Round4KeyedAsTheModelWouldHaveEchoedItKeepsEveryStatus() throws IOException {
    var previous = round3();
    var keyed =
        round4Statuses().stream()
            .map(status -> echoing(status, previous.get(status.id() - 1)))
            .toList();
    var diff = round4Diff();
    var dead404 = previous.get(5);

    assertFalse(
        diff.isFindingPresent(dead404.file(), dead404.suggestionOld()),
        "round 3 #6's anchor left the diff at d8f86021");
    assertEquals(keyed, new ModelStatusScreen(previous, diff, Map.of()).screen(keyed));
  }

  @Test
  void aResolvedOnAFindingWhoseAnchorIsStillPresentIsHeldUnresolved() throws IOException {
    var previous = round3();
    var diff = round4Diff();
    var lookupInLoop = previous.get(0);
    // Round 4's only "resolved" (on #6) moved to #1, whose flagged code is still in the diff.
    var resolvedNote = round4Statuses().get(5).note();
    var statuses =
        List.of(
            new ReviewResponse.PreviousFindingStatus(
                1, "resolved", resolvedNote, keyOf(lookupInLoop)));

    assertTrue(diff.isFindingPresent(lookupInLoop.file(), lookupInLoop.suggestionOld()));
    var screened = new ModelStatusScreen(previous, diff, Map.of()).screen(statuses);

    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(
                1, "unresolved", ModelStatusScreen.CONTRADICTED_NOTE, keyOf(lookupInLoop))),
        screened);
  }

  @Test
  void swappedStatusIdsApplyToTheFindingsTheirKeysName() throws IOException {
    var previous = round3();
    // The round-4 answer with #1 and #6 swapped: #6's "resolved" lands on #1 and #1's
    // "unresolved" on #6, each still echoing the key of the finding it was written for. The key
    // decides (#1402), so each status is read as its finding's, as the answer meant.
    var answer = round4Statuses();
    var swapped =
        List.of(
            new ReviewResponse.PreviousFindingStatus(
                1, "resolved", answer.get(5).note(), keyOf(previous.get(5))),
            new ReviewResponse.PreviousFindingStatus(
                6, "unresolved", answer.get(0).note(), keyOf(previous.get(0))));

    var screened = new ModelStatusScreen(previous, round4Diff(), Map.of()).screen(swapped);

    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(
                6, "resolved", answer.get(5).note(), keyOf(previous.get(5))),
            new ReviewResponse.PreviousFindingStatus(
                1, "unresolved", answer.get(0).note(), keyOf(previous.get(0)))),
        screened);
    assertFalse(
        screened.stream().anyMatch(s -> s.id() == 1 && "resolved".equals(s.status())),
        "the finding still in the diff is never closed by the swapped resolved");
  }

  @Test
  void aMissingKeyAndAKeyFromAnotherRoundAreDroppedAndAKeyedIdOutOfRangeIsRenumbered()
      throws IOException {
    var previous = round3();
    // Round 2's #1 (session 9635), which round 3 listed under the same number.
    var round2First =
        new ReviewResponse.Finding(
            "medium",
            "src/test/java/dev/thiagogonzaga/thrillhousebot/github/InstructionsResolverTest.java",
            176,
            "Per-repository cache test now stubs one shared missing-file result for both repos",
            "d",
            null,
            null);
    var statuses =
        List.of(
            status(1, "resolved", null),
            status(1, "resolved", keyOf(round2First)),
            status(0, "resolved", keyOf(previous.get(0))),
            status(7, "resolved", keyOf(previous.get(0))),
            status(2, "unresolved", " " + keyOf(previous.get(1)).toUpperCase() + " "));

    var screened = new ModelStatusScreen(previous, round4Diff(), Map.of()).screen(statuses);

    // The #0 status echoes #1's key, so it is read as #1's and its "resolved" held to the code;
    // the #7 one echoing the same key finds #1 taken and is dropped.
    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(
                1, "unresolved", ModelStatusScreen.CONTRADICTED_NOTE, keyOf(previous.get(0))),
            statuses.get(4)),
        screened,
        "only the echoed key, case and spaces aside");
  }

  @Test
  void aResolvedStandsWhenTheSuggestedFixLandedBesideTheStillPresentAnchor() {
    var finding =
        new ReviewResponse.Finding(
            "critical",
            "src/A.java",
            10,
            "SQL injection",
            "raw query",
            "String q = base + id;",
            "requireNumeric(id);\nString q = base + id;");
    var guardAdded = "@@ -9,2 +9,3 @@\n context();\n+requireNumeric(id);\n String q = base + id;";
    var notAdded = "@@ -9,2 +9,3 @@\n context();\n+other();\n String q = base + id;";
    var resolved = List.of(status(1, "resolved", keyOf(finding)));

    assertEquals(
        resolved,
        new ModelStatusScreen(
                List.of(finding), new DiffLineResolver(Map.of("src/A.java", guardAdded)), Map.of())
            .screen(resolved));
    assertEquals(
        "unresolved",
        new ModelStatusScreen(
                List.of(finding), new DiffLineResolver(Map.of("src/A.java", notAdded)), Map.of())
            .screen(resolved)
            .get(0)
            .status());
  }

  @Test
  void aResolvedOnAFindingWithNoFileOrAnUnchangedRenameIsHeld() {
    var noFile = new ReviewResponse.Finding("high", null, 3, "No file", "d", "x();", null);
    var renamed = new ReviewResponse.Finding("high", "old/A.java", 3, "Renamed", "d", "x();", null);
    var diff = new DiffLineResolver(Map.of());
    var screen = new ModelStatusScreen(List.of(noFile, renamed), diff, Map.of("old/A.java", ""));

    var screened =
        screen.screen(
            List.of(status(1, "resolved", keyOf(noFile)), status(2, "RESOLVED", keyOf(renamed))));

    assertEquals(
        List.of("unresolved", "unresolved"), screened.stream().map(s -> s.status()).toList());
  }

  @Test
  void otherStatusesAndAResolvedWhoseCodeLeftTheDiffPassAsTheyAre() {
    var gone = new ReviewResponse.Finding("high", "src/A.java", 3, "Gone", "d", "x();", null);
    var justified = new ReviewResponse.Finding("low", "src/A.java", 9, "Kept", "d", "y();", null);
    var diff = new DiffLineResolver(Map.of("src/A.java", "@@ -3,1 +3,1 @@\n-x();\n+z();"));
    var statuses =
        List.of(status(1, "resolved", keyOf(gone)), status(2, "justified", keyOf(justified)));

    assertEquals(
        statuses, new ModelStatusScreen(List.of(gone, justified), diff, Map.of()).screen(statuses));
  }

  @Test
  void aResponseIsRebuiltOnlyWhenAStatusChangesAndKeepsItsOtherParts() {
    var finding = new ReviewResponse.Finding("high", "src/A.java", 3, "T", "d", "x();", null);
    var screen =
        new ModelStatusScreen(
            List.of(finding),
            new DiffLineResolver(Map.of("src/A.java", "@@ -3,1 +3,1 @@\n-x();\n+z();")),
            Map.of());
    var criteria = List.of(new ReviewResponse.CriterionVerdict("c", "met", "e"));
    var kept =
        new ReviewResponse(
            List.of(finding),
            List.of(status(1, "resolved", keyOf(finding))),
            null,
            List.of(),
            criteria);
    var dropped =
        new ReviewResponse(
            List.of(finding),
            List.of(status(1, "resolved", "deadbeef")),
            null,
            List.of(),
            criteria);

    assertSame(kept, screen.screen(kept));
    var screened = screen.screen(dropped);
    assertEquals(List.of(), screened.previousFindingsStatus());
    assertEquals(criteria, screened.linkedIssueCriteria());
    assertEquals(List.of(finding), screened.findings());
  }
}
