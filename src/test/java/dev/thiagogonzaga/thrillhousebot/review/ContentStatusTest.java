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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.FileChange;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A previous finding's status from the content of its file (#1319): unchanged since the round that
 * raised it is open, a model "resolved" stands on a changed file and not on an unchanged one, a
 * replacement that landed resolves a finding whose flagged code left, and an older round's finding
 * the model resolves is closed by the backstop.
 */
class ContentStatusTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));
  private static final String FILE = "src/A.java";
  private static final String OTHER = "src/B.java";

  /** The flagged call is still there as context, and the guard the finding suggests is not. */
  private static final String ANCHOR_KEPT = "@@ -5,1 +5,2 @@\n doThing(input);\n+unrelated(input);";

  /** The flagged call is gone and the guard the finding suggests is added elsewhere in the file. */
  private static final String REPLACED =
      "@@ -5,1 +5,2 @@\n-doThing(input);\n+requireNonNull(input, \"input\");\n+other();";

  /** The flagged call is gone and nothing the finding suggests took its place. */
  private static final String REMOVED = "@@ -5,1 +5,1 @@\n-doThing(input);\n+other();";

  private static final ReviewResponse.Finding GUARD =
      new ReviewResponse.Finding(
          "medium",
          "high",
          FILE,
          5,
          "Input is passed on without a null guard",
          "doThing receives input unchecked.",
          "doThing(input);",
          "requireNonNull(input, \"input\");\ndoThing(input);");

  private static final ReviewResponse.Finding ELSEWHERE =
      new ReviewResponse.Finding(
          "low", "high", OTHER, 2, "Log line has a typo", "d", "log(\"teh\");", "log(\"the\");");

  private static DiffLineResolver diff(String patch, FileChange change) {
    return new DiffLineResolver(
        Map.of(FILE, patch, OTHER, "@@ -2,1 +2,1 @@\n-log(\"x\");\n+log(\"teh\");"),
        List.of(FILE, OTHER),
        DiffLineResolver.HeadFiles.NONE,
        (round, path) -> FILE.equals(path) ? change : FileChange.CHANGED);
  }

  private static String keyOf(ReviewResponse.Finding finding) {
    return SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title());
  }

  private static ReviewResponse.PreviousFindingStatus resolved(int id, ReviewResponse.Finding f) {
    return new ReviewResponse.PreviousFindingStatus(id, "resolved", "fixed", keyOf(f));
  }

  private static ModelStatusScreen screen(DiffLineResolver diff) {
    return new ModelStatusScreen(new PriorListing(List.of(GUARD), List.of(0), 1), diff, Map.of());
  }

  // --- The decided rule: a model "resolved" is held to the finding's file ---

  @Test
  void aResolvedStandsOnAChangedFileEvenWithTheFlaggedLineStillThere() {
    // #1364: the fix landed in another method while the flagged line stayed for a narrower use.
    var statuses = List.of(resolved(1, GUARD));

    assertEquals(statuses, screen(diff(ANCHOR_KEPT, FileChange.CHANGED)).screen(statuses));
  }

  @Test
  void aResolvedOnAnUnchangedFileIsHeldAsAContradictionWhateverTheAnchorReads() {
    var statuses = List.of(resolved(1, GUARD));

    var held = screen(diff(REMOVED, FileChange.UNCHANGED)).screen(statuses);

    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(
                1, "unresolved", ModelStatusScreen.CONTRADICTED_NOTE, keyOf(GUARD))),
        held);
  }

  @Test
  void withNoComparisonTheResolvedIsHeldToTheAnchorAsBefore() {
    var statuses = List.of(resolved(1, GUARD));

    assertEquals(
        "unresolved",
        screen(diff(ANCHOR_KEPT, FileChange.UNKNOWN)).screen(statuses).get(0).status());
    assertEquals(statuses, screen(diff(REMOVED, FileChange.UNKNOWN)).screen(statuses));
  }

  @Test
  void anOlderRoundsFindingIsScreenedUnderItsListedIdAndKey() {
    var listing = new PriorListing(List.of(ELSEWHERE, GUARD), List.of(0, 2), 1);
    var screen = new ModelStatusScreen(listing, diff(ANCHOR_KEPT, FileChange.UNCHANGED), Map.of());

    // Id 2 lists GUARD, raised two rounds back on a file unchanged since: its "resolved" is held.
    var screened = screen.screen(List.of(resolved(2, GUARD), resolved(2, ELSEWHERE)));

    assertEquals(1, screened.size(), "a key of another finding is dropped");
    assertEquals("unresolved", screened.get(0).status());
  }

  // --- The listing and how its statuses split ---

  @Test
  void statusesOnAnOlderRoundsFindingLeaveTheRoundsOwnAndCloseByKey() {
    var listing = new PriorListing(List.of(ELSEWHERE, GUARD), List.of(0, 1), 1);
    var own = new ReviewResponse.PreviousFindingStatus(1, "unresolved", "n", keyOf(ELSEWHERE));
    var phantom = new ReviewResponse.PreviousFindingStatus(7, "unresolved", "n");
    var older = resolved(2, GUARD);
    var olderOpen = new ReviewResponse.PreviousFindingStatus(2, "unresolved", "n", keyOf(GUARD));

    assertFalse(listing.listsEarlier(1));
    assertTrue(listing.listsEarlier(2));
    assertFalse(listing.listsEarlier(3));
    assertEquals(List.of(own, phantom), listing.ofEffectiveRound(List.of(own, older, phantom)));
    var onlyOwn = List.of(own);
    assertSame(onlyOwn, listing.ofEffectiveRound(onlyOwn));
    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(
                FollowUpAnalyzer.EARLIER_ROUND_ID, "resolved", "fixed", keyOf(GUARD))),
        listing.earlierRoundResolutions(List.of(own, older, olderOpen)));
    assertEquals(List.of(-1, -1), PriorListing.of(List.of(GUARD, ELSEWHERE)).rounds());
  }

  @Test
  void theListingNumbersOlderOpenFindingsOnChangedFilesThatNoMaintainerAnswered() {
    var answered =
        new ReviewResponse.Finding("high", "high", FILE, 40, "Answered one", "d", "x();", null);
    var unchanged =
        new ReviewResponse.Finding("high", "high", "src/C.java", 3, "Unchanged one", "d", "", null);
    var older = new ReviewResponse(List.of(GUARD, answered, unchanged), List.of(), null);
    var effective = new ReviewResponse(List.of(ELSEWHERE), List.of(), null);
    var root =
        new GitHubReviewClient.PullRequestComment(
            9L,
            null,
            FILE,
            "**Answered one**\n\n" + SuggestionFormatter.findingMarker(2, FILE, 40, "Answered one"),
            new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]"),
            null);
    var reply =
        new GitHubReviewClient.PullRequestComment(
            10L,
            9L,
            FILE,
            "This is intended.",
            new GitHubReviewClient.ReviewResponse.User("maintainer"),
            "OWNER");
    var diff =
        new DiffLineResolver(
            Map.of(FILE, ANCHOR_KEPT),
            List.of(FILE),
            DiffLineResolver.HeadFiles.NONE,
            (round, path) -> FILE.equals(path) ? FileChange.CHANGED : FileChange.UNCHANGED);

    var listing =
        FollowUpAnalyzer.priorListing(
            List.of(effective, older), () -> diff, List.of(root, reply), BOT);

    assertEquals(List.of(ELSEWHERE, GUARD), listing.findings());
    assertEquals(List.of(0, 1), listing.rounds());
    assertEquals(1, listing.effective());
    var section = FollowUpAnalyzer.formatEarlierListed(listing);
    assertTrue(section.contains("Raised in earlier rounds and still open"), section);
    assertTrue(section.contains("2. (key " + keyOf(GUARD) + ") [MEDIUM] src/A.java:5"), section);
    assertEquals("", FollowUpAnalyzer.formatEarlierListed(PriorListing.of(List.of(GUARD))));
    assertEquals(
        0,
        FollowUpAnalyzer.priorListing((List<ReviewResponse>) null, () -> diff, List.of(), BOT)
            .findings()
            .size());
  }

  // --- The effective round: no supersede on an unchanged file; a landed replacement resolves ---

  private final FollowUpAnalyzer analyzer = new FollowUpAnalyzer(MAPPER);

  private String round(ReviewResponse.Finding... findings) throws IOException {
    return MAPPER.writeValueAsString(new ReviewResponse(List.of(findings), List.of(), null));
  }

  private static ReviewResponse.PreviousFindingStatus unresolved(int id) {
    return new ReviewResponse.PreviousFindingStatus(id, "unresolved", "still there");
  }

  @Test
  void aFindingOnAnUnchangedFileIsNeverSupersededWhateverItsAnchorReads() throws IOException {
    // #1318's shape: the anchor does not match the patch, the file is byte-identical, another file
    // changed.
    var statuses = List.of(unresolved(1));

    var kept =
        analyzer.supersedeVanished(
            round(GUARD), statuses, diff(REMOVED, FileChange.UNCHANGED), Map.of(), Set.of(), 0);
    var added =
        analyzer.addUnreportedVanished(
            List.of(GUARD), List.of(), diff(REMOVED, FileChange.UNCHANGED), Map.of(), Set.of(), 0);

    assertEquals(statuses, kept);
    assertEquals(List.of(), added, "the backstop holds it");
  }

  @Test
  void aFindingWhoseCodeLeftWhileItsReplacementLandedIsResolvedNotSuperseded() throws IOException {
    var replaced = diff(REPLACED, FileChange.CHANGED);
    var removed = diff(REMOVED, FileChange.CHANGED);

    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(
                1, "resolved", FollowUpAnalyzer.REPLACEMENT_LANDED_NOTE)),
        analyzer.supersedeVanished(
            round(GUARD), List.of(unresolved(1)), replaced, Map.of(), Set.of(), 0));
    assertEquals(
        "resolved",
        analyzer
            .addUnreportedVanished(List.of(GUARD), List.of(), replaced, Map.of(), Set.of(), 0)
            .get(0)
            .status());
    assertEquals(
        "superseded",
        analyzer
            .supersedeVanished(round(GUARD), List.of(unresolved(1)), removed, Map.of(), Set.of(), 0)
            .get(0)
            .status());
  }

  @Test
  void aReplacementNeedsALongEnoughLineInAFileTheDiffStillChanges() {
    var shortFix = new ReviewResponse.Finding("low", FILE, 5, "t", "d", "x();", "}");
    var noFile =
        new ReviewResponse.Finding("low", null, 5, "t", "d", "x();", GUARD.suggestionNew());
    var replaced = diff(REPLACED, FileChange.CHANGED);

    assertFalse(FollowUpAnalyzer.replacementLanded(shortFix, replaced, Map.of()));
    assertFalse(FollowUpAnalyzer.replacementLanded(noFile, replaced, Map.of()));
    assertFalse(FollowUpAnalyzer.replacementLanded(GUARD, replaced, Map.of(FILE, "")));
    assertTrue(FollowUpAnalyzer.replacementLanded(GUARD, replaced, Map.of()));
    assertFalse(replaced.addsEveryLine("src/Missing.java", List.of("other();")));
    assertFalse(replaced.addsEveryLine(FILE, List.of()));
  }

  // --- Older rounds: the backstop ---

  private List<FollowUpAnalyzer.HeldPrevious> backstop(
      DiffLineResolver diff, List<ReviewResponse.PreviousFindingStatus> statuses) {
    var older = new ReviewResponse(List.of(GUARD), List.of(), null);
    var effective = new ReviewResponse(List.of(ELSEWHERE), List.of(), null);
    return analyzer.heldPreviousFindings(
        List.of(effective, older), statuses, List.of(), List.of(), diff, BOT, Map.of());
  }

  @Test
  void anOlderFindingOnAnUnchangedFileIsHeldWhateverItsAnchorReads() {
    var held = backstop(diff(REMOVED, FileChange.UNCHANGED), List.of(unresolved(1)));

    assertEquals(1, held.size());
    assertTrue(held.get(0).holdsOpen());
    assertEquals(GUARD, held.get(0).finding());
  }

  @Test
  void anOlderFindingWhoseReplacementLandedIsResolvedByTheBackstop() {
    var held = backstop(diff(REPLACED, FileChange.CHANGED), List.of(unresolved(1)));

    assertEquals("resolved", held.get(0).status().status());
    assertEquals(FollowUpAnalyzer.REPLACEMENT_LANDED_NOTE, held.get(0).status().note());
    assertEquals(List.of(GUARD), held.get(0).status().earlierRound().members());
  }

  @Test
  void anOlderFindingTheModelResolvedOnAChangedFileIsClosedByTheBackstop() {
    var resolution =
        new ReviewResponse.PreviousFindingStatus(
            FollowUpAnalyzer.EARLIER_ROUND_ID, "resolved", "fixed", keyOf(GUARD));
    var noKey =
        new ReviewResponse.PreviousFindingStatus(
            FollowUpAnalyzer.EARLIER_ROUND_ID, "resolved", "x");

    var held =
        backstop(diff(ANCHOR_KEPT, FileChange.CHANGED), List.of(unresolved(1), resolution, noKey));

    assertEquals("resolved", held.get(0).status().status());
    assertEquals(FollowUpAnalyzer.MODEL_RESOLVED_NOTE, held.get(0).status().note());
    assertTrue(
        backstop(diff(ANCHOR_KEPT, FileChange.CHANGED), List.of(unresolved(1), noKey))
            .get(0)
            .holdsOpen());
  }

  @Test
  void anOlderFindingTheModelResolvedIsClosedEvenWhenItsCodeIsGone() {
    var resolution =
        new ReviewResponse.PreviousFindingStatus(
            FollowUpAnalyzer.EARLIER_ROUND_ID, "resolved", "fixed", keyOf(GUARD));

    var held = backstop(diff(REMOVED, FileChange.CHANGED), List.of(unresolved(1), resolution));

    assertEquals(FollowUpAnalyzer.MODEL_RESOLVED_NOTE, held.get(0).status().note());
  }

  // --- #1317: the same defect raised again from another round ---

  private static final Path PR1315 = Path.of("src/test/resources/followup/pr1315");

  private static List<ReviewResponse.Finding> stored(String name) throws IOException {
    return MAPPER
        .readValue(Files.readString(PR1315.resolve(name)), ReviewResponse.class)
        .findings();
  }

  @Test
  void pr1315sRoundThreeRestatementsAreTheRoundOneFindingsTheyRepeat() throws IOException {
    var round1 = stored("round1-findings.json");
    var round3 = stored("round3-response.json");

    // Round 1 #7 and round 3 #1 name the same four resolvers, from two of their files.
    assertTrue(DefectIdentity.sameDefect(round3.get(0), round1.get(6)));
    // Round 1 #5 and round 3 #4: the same caching tests, five lines apart after a push.
    assertTrue(DefectIdentity.sameDefect(round3.get(3), round1.get(4)));
    // Round 3's own two cache-test findings are about different tests in different files.
    assertFalse(DefectIdentity.sameDefect(round3.get(1), round3.get(3)));
    // A test finding never pairs with a production one across files.
    assertFalse(DefectIdentity.sameDefect(round3.get(3), round1.get(6)));
  }

  @Test
  void twoFindingsQuotingTheSameMovedCodeAreOneDefectWhenTheirTitlesAgree() {
    var first =
        new ReviewResponse.Finding(
            "medium",
            FILE,
            20,
            "Cache grows unbounded: entries never evicted",
            "d",
            "cache.put(key, value, expiresAt);",
            null);
    var moved =
        new ReviewResponse.Finding(
            "medium",
            FILE,
            31,
            "Expired values leak from the cache",
            "d",
            "cache.put(key, value, expiresAt);\nreturn value;",
            null);
    var otherDefect =
        new ReviewResponse.Finding(
            "medium",
            FILE,
            31,
            "Value is returned before logging",
            "d",
            "cache.put(key, value, expiresAt);",
            null);
    var shortAnchor =
        new ReviewResponse.Finding(
            "medium", FILE, 31, "Expired values leak from the cache", "d", "x();", null);
    var shortFirst =
        new ReviewResponse.Finding(
            "medium", FILE, 20, "Cache grows unbounded: entries never evicted", "d", "x();", null);
    var noAnchor =
        new ReviewResponse.Finding(
            "medium", FILE, 31, "Expired values leak from the cache", "d", null, null);

    assertTrue(DefectIdentity.sameDefect(moved, first));
    assertFalse(DefectIdentity.sameDefect(otherDefect, first));
    assertFalse(DefectIdentity.sameDefect(shortAnchor, shortFirst));
    assertFalse(DefectIdentity.sameDefect(noAnchor, first));
  }

  @Test
  void sitesAreTheLongCodeNamesAFindingNames() {
    var finding =
        Finding.fromAiResponse(
            new ReviewResponse.Finding(
                "low",
                FILE,
                1,
                "RepoSettingsResolver re-reads getRepository",
                "fetchAndParse and getFileContent in a loop; a Foo and x.y.",
                null,
                null));

    assertEquals(
        Set.of("reposettingsresolver", "getrepository", "fetchandparse", "getfilecontent"),
        DefectIdentity.sites(finding));
  }

  // --- ThrillhouseBot-test #303: a promoted double-check item and its thread are one defect ---

  private static final String READING = "zig/src/reading.zig";

  /**
   * ThrillhouseBot-test #303, every round on one head (699be405): round 2 lists a CRLF item under
   * "Things to double-check", and round 5 raises it again confidently enough to post a thread,
   * which replaces the listed copy (#951). On the rc5 corpus run (#1319) the total fell from 21 to
   * 19 with nothing resolved. With every file unchanged, the listed copy read as still there too,
   * so it became the held member of the defect (the earlier of two equals), and a replaced copy is
   * not counted, so the thread's defect left the counts with it. The titles, files and lines are
   * the posted ones; the listed item's description is not posted and is written here.
   */
  @Test
  void aThreadThatReplacedAnEarlierDoubleCheckItemStaysCountedOnAnUnchangedFile() {
    var crlfItem =
        new ReviewResponse.Finding(
            "low",
            "low",
            READING,
            19,
            "CRLF-terminated lines are counted as skipped: grams field keeps a trailing \\r",
            "The grams field is trimmed of spaces only, so a CRLF line keeps its \\r and parseFloat"
                + " rejects it as BadWeight.",
            "",
            "");
    var crlfThread =
        new ReviewResponse.Finding(
            "low",
            "medium",
            READING,
            19,
            "CRLF-terminated input is rejected as BadWeight and silently counted as skipped",
            "The grams field's trim set is spaces only: std.mem.trim(u8, grams_text, \" \") leaves"
                + " \"10.0\\r\", which std.fmt.parseFloat does not accept, so a CRLF sample returns"
                + " error.BadWeight and is counted in skipped.",
            "",
            "    const grams = std.fmt.parseFloat(f64, std.mem.trim(u8, grams_text, \" \\r\\t\"))"
                + " catch return error.BadWeight;");
    var dockerfile =
        new ReviewResponse.Finding(
            "high",
            "medium",
            "zig/Dockerfile",
            13,
            "BEEHIVE_DB_PATH points at /data, which no build or runtime step creates",
            "d",
            "",
            "");
    // Newest first: round 6 (the effective round), round 5, round 2.
    var priors =
        List.of(
            new ReviewResponse(List.of(dockerfile), List.of(), null),
            new ReviewResponse(List.of(crlfThread), List.of(), null),
            new ReviewResponse(List.of(crlfItem), List.of(), null));
    var diff =
        new DiffLineResolver(
            Map.of(),
            List.of(READING, "zig/Dockerfile"),
            DiffLineResolver.HeadFiles.NONE,
            (round, path) -> FileChange.UNCHANGED);
    assertTrue(FollowUpAnalyzer.isSameFinding(crlfThread, crlfItem), "one defect, two rounds");
    var replaced = FollowUpAnalyzer.replacedPriors(priors, List.of());
    assertEquals(Set.of(crlfItem), Set.copyOf(replaced));

    var held =
        analyzer
            .heldPreviousFindings(
                priors, List.of(unresolved(1)), List.of(), List.of(), diff, BOT, Map.of())
            .stream()
            .map(hold -> FollowUpAnalyzer.carriedByReplacement(hold, replaced, Map.of()))
            .filter(java.util.Objects::nonNull)
            .toList();

    assertEquals(List.of(crlfThread), held.stream().map(h -> h.finding()).toList());
    assertTrue(held.get(0).holdsOpen());
  }
}
