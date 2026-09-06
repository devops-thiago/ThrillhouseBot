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
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/** #806: the hand-off from a run that stood down for HEAD_MOVED to the run that replaces it. */
class SupersededFindingsCarryoverTest {

  private static final String SUPERSEDED = "23e277100000000000000000000000000000abcd";
  private static final String SUPERSEDING = "c957198000000000000000000000000000000abcd";

  private final SupersededFindingsCarryover carryover =
      new SupersededFindingsCarryover(new ObjectMapper());

  private static ReviewResponse.Finding finding(String title) {
    return new ReviewResponse.Finding(
        "high", "high", "src/Main.java", 10, title, "d", "guard(x)", "guard(x, y)");
  }

  private static List<ReviewResponse.Finding> findings(int count) {
    var list = new ArrayList<ReviewResponse.Finding>();
    for (var i = 0; i < count; i++) {
      list.add(finding("Finding " + i));
    }
    return list;
  }

  /** Every line the carry-over logged, formatted the way the log manager would render it. */
  private static List<String> capturing(Runnable action) {
    var records = new CopyOnWriteArrayList<LogRecord>();
    var handler =
        new Handler() {
          @Override
          public void publish(LogRecord logRecord) {
            records.add(logRecord);
          }

          @Override
          public void flush() {
            // Nothing buffered.
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
    var logger =
        org.jboss.logmanager.LogContext.getLogContext()
            .getLogger(SupersededFindingsCarryover.class.getName());
    var previousLevel = logger.getLevel();
    logger.addHandler(handler);
    logger.setLevel(Level.ALL);
    try {
      action.run();
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
    }
    return records.stream()
        .map(
            r ->
                r.getLevel()
                    + " "
                    + (r.getParameters() != null && r.getParameters().length > 0
                        ? String.format(r.getMessage(), r.getParameters())
                        : r.getMessage()))
        .toList();
  }

  @Test
  void theReplacementTakesWhatTheSupersededRunLeftExactlyOnce() {
    carryover.stash("owner", "repo", 18, SUPERSEDED, SUPERSEDING, List.of(finding("Lost")));

    var carried = carryover.take("owner", "repo", 18, SUPERSEDING);

    assertEquals(SUPERSEDED, carried.supersededSha());
    assertEquals(SUPERSEDING, carried.supersedingSha());
    assertEquals(List.of(finding("Lost")), carried.findings());
    // Dropped once consumed: a third push must not be shown findings a replacement already took.
    assertTrue(carryover.take("owner", "repo", 18, SUPERSEDING).isEmpty());
  }

  @Test
  void theHeadThatSupersededTheRunIsMatchedCaseInsensitively() {
    carryover.stash("owner", "repo", 18, SUPERSEDED, SUPERSEDING, List.of(finding("Lost")));

    assertFalse(carryover.take("owner", "repo", 18, SUPERSEDING.toUpperCase()).isEmpty());
  }

  @Test
  void aRunThatVerifiedNothingLeavesNothingBehind() {
    var logged =
        capturing(() -> carryover.stash("owner", "repo", 18, SUPERSEDED, SUPERSEDING, List.of()));

    assertTrue(carryover.take("owner", "repo", 18, SUPERSEDING).isEmpty());
    assertEquals(List.of(), logged);
  }

  @Test
  void nothingIsCarriedAcrossPullRequestsOrRepositories() {
    carryover.stash("owner", "repo", 18, SUPERSEDED, SUPERSEDING, List.of(finding("Lost")));

    assertTrue(carryover.take("owner", "repo", 19, SUPERSEDING).isEmpty());
    assertTrue(carryover.take("owner", "other", 18, SUPERSEDING).isEmpty());
    assertTrue(carryover.take("someone", "repo", 18, SUPERSEDING).isEmpty());
    // The entry for #18 is untouched by the misses.
    assertEquals(1, carryover.take("owner", "repo", 18, SUPERSEDING).findings().size());
  }

  @Test
  void aReviewOfAnotherHeadIsNotTheReplacementAndDropsTheEntry() {
    carryover.stash("owner", "repo", 18, SUPERSEDED, SUPERSEDING, List.of(finding("Lost")));

    var logged =
        capturing(() -> assertTrue(carryover.take("owner", "repo", 18, "ffff000").isEmpty()));

    assertTrue(
        logged.stream()
            .anyMatch(
                line ->
                    line.startsWith("WARN")
                        && line.contains("Dropping 1 findings carried from the superseded review")
                        && line.contains("owner/repo #18 on head 23e2771")
                        && line.contains("this review is of ffff000")
                        && line.contains("(c957198)")),
        () -> logged.toString());
    // Dropped, not held for a review that may never come.
    assertTrue(carryover.take("owner", "repo", 18, SUPERSEDING).isEmpty());
  }

  @Test
  void aReviewWithNoHeadAtAllIsNotTheReplacementEither() {
    carryover.stash("owner", "repo", 18, SUPERSEDED, SUPERSEDING, List.of(finding("Lost")));

    assertTrue(carryover.take("owner", "repo", 18, null).isEmpty());
  }

  @Test
  void aLaterAbandonOfTheSamePullRequestReplacesTheEntry() {
    carryover.stash("owner", "repo", 18, SUPERSEDED, SUPERSEDING, List.of(finding("First")));
    carryover.stash("owner", "repo", 18, SUPERSEDING, "69d7bd6", List.of(finding("Second")));

    var carried = carryover.take("owner", "repo", 18, "69d7bd6");

    assertEquals(SUPERSEDING, carried.supersededSha());
    assertEquals(List.of(finding("Second")), carried.findings());
  }

  @Test
  void theCountCarriedIsLoggedAtWarnWithoutAnyFindingText() {
    var logged =
        capturing(
            () ->
                carryover.stash(
                    "owner",
                    "repo",
                    18,
                    SUPERSEDED,
                    SUPERSEDING,
                    List.of(finding("Lost <script>"), finding("Also lost"))));

    assertEquals(1, logged.size(), () -> logged.toString());
    var line = logged.get(0);
    assertTrue(line.startsWith("WARN "), line);
    assertTrue(
        line.contains(
            "Review of owner/repo #18 on head 23e2771 was superseded by c957198 — carrying 2 of"
                + " its 2 verified findings into the replacement run"),
        line);
    assertFalse(line.contains("Lost"), line);
  }

  @Test
  void theFindingsCarriedPerRunAreCapped() {
    var over = findings(SupersededFindingsCarryover.MAX_FINDINGS + 1);

    var logged =
        capturing(() -> carryover.stash("owner", "repo", 18, SUPERSEDED, SUPERSEDING, over));

    var carried = carryover.take("owner", "repo", 18, SUPERSEDING);
    assertEquals(SupersededFindingsCarryover.MAX_FINDINGS, carried.findings().size());
    assertEquals(over.subList(0, SupersededFindingsCarryover.MAX_FINDINGS), carried.findings());
    assertTrue(
        logged.get(0).contains("carrying 50 of its 51 verified findings"), () -> logged.get(0));
  }

  @Test
  void thePullRequestsHeldAreCappedByEvictingTheLeastRecentlyStashed() {
    var cap = SupersededFindingsCarryover.MAX_PULL_REQUESTS;
    for (var pr = 1; pr <= cap + 1; pr++) {
      carryover.stash("owner", "repo", pr, SUPERSEDED, SUPERSEDING, List.of(finding("Lost")));
    }

    assertTrue(carryover.take("owner", "repo", 1, SUPERSEDING).isEmpty());
    assertFalse(carryover.take("owner", "repo", 2, SUPERSEDING).isEmpty());
    assertFalse(carryover.take("owner", "repo", cap + 1, SUPERSEDING).isEmpty());
  }

  @Test
  void mergeGivesCarriedFindingsARoundOfTheirOwnWhenNothingWasPersisted() {
    var rounds = carryover.merge(List.of(), List.of(), List.of(finding("Lost")));

    assertEquals(1, rounds.parsed().size());
    assertEquals(List.of(finding("Lost")), rounds.parsed().get(0).findings());
    assertEquals(1, rounds.jsons().size());
    // The JSON is the same shape the pipeline persists, so the supersede pass re-reads it.
    var reparsed = new FollowUpAnalyzer(new ObjectMapper()).parseResponse(rounds.jsons().get(0));
    assertEquals(rounds.parsed().get(0), reparsed);
  }

  @Test
  void mergeAppendsCarriedFindingsAfterTheRoundTheReviewReportsOn() {
    var posted = new ReviewResponse(List.of(finding("Posted")), List.of(), null);
    var quiet =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still")),
            null);
    var analyzer = new FollowUpAnalyzer(new ObjectMapper());
    var jsons = List.of("{\"findings\":[]}", "{\"findings\":[{\"title\":\"Posted\"}]}");

    var rounds = carryover.merge(jsons, List.of(quiet, posted), List.of(finding("Lost")));

    // The quiet newer round is skipped, as the effective-round rule already does: the posted
    // finding keeps id 1 (its inline marker), the carried one takes id 2.
    assertEquals(List.of(quiet, rounds.parsed().get(1)), rounds.parsed());
    assertEquals(
        List.of(finding("Posted"), finding("Lost")),
        FollowUpAnalyzer.effectivePreviousFindings(rounds.parsed()));
    assertEquals(jsons.get(0), rounds.jsons().get(0));
    assertEquals(rounds.parsed().get(1), analyzer.parseResponse(rounds.jsons().get(1)));
  }

  @Test
  void theScopeNoteNamesTheCountAndTheSupersededHead() {
    var one =
        new SupersededFindingsCarryover.Carried(SUPERSEDED, SUPERSEDING, List.of(finding("Lost")));
    var two =
        new SupersededFindingsCarryover.Carried(
            SUPERSEDED, SUPERSEDING, List.of(finding("Lost"), finding("Also")));

    assertEquals(
        "", SupersededFindingsCarryover.formatScopeNote(SupersededFindingsCarryover.Carried.NONE));
    assertEquals(
        "1 finding from the review of superseded head `23e2771`, abandoned when the pull request"
            + " head moved, was carried into this review as previous findings and re-checked"
            + " against the current head",
        SupersededFindingsCarryover.formatScopeNote(one));
    assertEquals(
        "2 findings from the review of superseded head `23e2771`, abandoned when the pull request"
            + " head moved, were carried into this review as previous findings and re-checked"
            + " against the current head",
        SupersededFindingsCarryover.formatScopeNote(two));
  }
}
