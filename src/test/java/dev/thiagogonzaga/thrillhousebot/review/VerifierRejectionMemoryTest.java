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
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link VerifierRejectionMemory} (#711). */
class VerifierRejectionMemoryTest {

  private final VerifierRejectionMemory memory = new VerifierRejectionMemory();

  private static ReviewSession session(String headSha) {
    return ReviewSession.create("owner/repo", 70, "PR", headSha);
  }

  private static ReviewResponse.Finding finding(String file, int line, String title) {
    return new ReviewResponse.Finding(
        "critical", "high", file, line, title, "description of the claim", "old", "new");
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    return new ReviewResponse(
        List.of(findings),
        List.of(),
        new ReviewResponse.Summary(
            findings.length, findings.length, 0, 0, 0, "assessment", "purpose", List.of()));
  }

  private static List<String> titlesOf(ReviewResponse response) {
    return response.findings().stream().map(ReviewResponse.Finding::title).toList();
  }

  @Test
  void recallsARejectionReachedOnTheSameHead() {
    var head = session("2659f683");
    var claim = finding("Repositories/ReservationRepository.cs", 31, "Concatenated SQL");
    memory.remember(head, List.of(claim), List.of());

    var next = memory.withoutRejectionsOnThisHead(session("2659f683"), response(claim));

    assertEquals(List.of(), titlesOf(next));
    assertEquals(0, next.summary().totalFindings(), "the summary counts are recomputed");
    assertEquals("assessment", next.summary().overallAssessment(), "the prose fields survive");
  }

  /** The model rewords a title between rounds; the claim underneath is the same one. */
  @Test
  void recallsARejectionRaisedAgainUnderADifferentWording() {
    memory.remember(
        session("2659f683"),
        List.of(finding("src/Repo.cs", 31, "Concatenated SQL reaches the query")),
        List.of());

    var reworded = finding("src/Repo.cs", 32, "Concatenated SQL reaches the query unparameterized");
    var next = memory.withoutRejectionsOnThisHead(session("2659f683"), response(reworded));

    assertEquals(List.of(), titlesOf(next));
  }

  @Test
  void keepsAFindingTheAuditNeverRejected() {
    memory.remember(session("2659f683"), List.of(finding("src/A.cs", 3, "Rejected")), List.of());

    var other = response(finding("src/B.cs", 9, "A different defect entirely"));
    var next = memory.withoutRejectionsOnThisHead(session("2659f683"), other);

    assertSame(other, next, "a response with nothing to drop is returned unchanged");
  }

  @Test
  void forgetsARejectionOnceTheHeadMoves() {
    var claim = finding("src/A.cs", 3, "Concatenated SQL");
    memory.remember(session("2659f683"), List.of(claim), List.of());

    var afterPush = memory.withoutRejectionsOnThisHead(session("9c1d4e07"), response(claim));

    assertEquals(List.of("Concatenated SQL"), titlesOf(afterPush));
  }

  /** The head is compared case-insensitively, as GitHub's own SHAs are spelled either way. */
  @Test
  void recallsARejectionWhenTheHeadIsSpelledInAnotherCase() {
    var claim = finding("src/A.cs", 3, "Concatenated SQL");
    memory.remember(session("2659F683"), List.of(claim), List.of());

    assertEquals(
        List.of(),
        titlesOf(memory.withoutRejectionsOnThisHead(session("2659f683"), response(claim))));
  }

  @Test
  void aPushReplacesTheEntryRatherThanAddingToIt() {
    var first = finding("src/A.cs", 3, "Rejected before the push");
    var second = finding("src/B.cs", 9, "Rejected after the push");
    memory.remember(session("2659f683"), List.of(first), List.of());
    memory.remember(session("9c1d4e07"), List.of(second), List.of());

    var next = memory.withoutRejectionsOnThisHead(session("9c1d4e07"), response(first, second));

    assertEquals(
        List.of("Rejected before the push"),
        titlesOf(next),
        "only the rejection reached on the current head is recalled");
    assertEquals(1, memory.size(), "the pull request holds one entry");
  }

  @Test
  void accumulatesRejectionsAcrossRoundsOnOneHead() {
    var first = finding("src/A.cs", 3, "Rejected in round one");
    var second = finding("src/B.cs", 9, "Rejected in round two");
    memory.remember(session("2659f683"), List.of(first), List.of());
    memory.remember(session("2659f683"), List.of(second), List.of());

    var next = memory.withoutRejectionsOnThisHead(session("2659f683"), response(first, second));

    assertEquals(List.of(), titlesOf(next));
  }

  @Test
  void rememberNothingWhenTheAuditKeptEveryCandidate() {
    var kept = finding("src/A.cs", 3, "Confirmed");
    // Verification lowers risk and confidence and keeps the location and title, so the difference
    // the memory is taken on must not read a downgrade as a rejection.
    var downgraded =
        new ReviewResponse.Finding(
            "medium", "low", "src/A.cs", 3, "Confirmed", "description of the claim", "old", "new");
    memory.remember(session("2659f683"), List.of(kept), List.of(downgraded));

    assertEquals(0, memory.size(), "a round that rejected nothing remembers nothing");
    assertEquals(
        List.of("Confirmed"),
        titlesOf(memory.withoutRejectionsOnThisHead(session("2659f683"), response(kept))));
  }

  /**
   * The published side is read by the same anchor. A finding that cites no file is skipped there
   * too, so it can neither stand in for a rejected candidate nor be mistaken for one.
   */
  @Test
  void readsThePublishedSetByTheSameAnchor() {
    var rejected = finding("src/A.cs", 3, "Rejected");
    var anchorless = finding("", 0, "Nowhere in particular");
    memory.remember(session("2659f683"), List.of(rejected, anchorless), List.of(anchorless));

    assertEquals(
        List.of(),
        titlesOf(memory.withoutRejectionsOnThisHead(session("2659f683"), response(rejected))),
        "the anchored candidate the audit dropped is remembered");
  }

  @Test
  void rememberNothingForAFindingThatCitesNoFile() {
    var anchorless = finding("", 0, "Nowhere in particular");
    memory.remember(session("2659f683"), List.of(anchorless), List.of());

    assertEquals(0, memory.size());
  }

  @Test
  void rememberNothingWithoutARepositoryOrAHead() {
    var claim = finding("src/A.cs", 3, "Concatenated SQL");
    memory.remember(ReviewSession.create("", 70, "PR", "2659f683"), List.of(claim), List.of());
    memory.remember(ReviewSession.create("owner/repo", 70, "PR", null), List.of(claim), List.of());

    assertEquals(0, memory.size());
    assertEquals(
        List.of("Concatenated SQL"),
        titlesOf(
            memory.withoutRejectionsOnThisHead(
                ReviewSession.create("owner/repo", 70, "PR", " "), response(claim))),
        "a session with no head recalls nothing either");
  }

  @Test
  void anEmptyFindingListIsReturnedUnchanged() {
    var empty = response();
    assertSame(empty, memory.withoutRejectionsOnThisHead(session("2659f683"), empty));
  }

  @Test
  void aPullRequestWithNothingRememberedIsReturnedUnchanged() {
    var raised = response(finding("src/A.cs", 3, "Concatenated SQL"));
    assertSame(raised, memory.withoutRejectionsOnThisHead(session("2659f683"), raised));
  }

  @Test
  void forgetsTheOldestRejectionsPastTheCap() {
    var head = session("2659f683");
    var rejected = new ArrayList<ReviewResponse.Finding>();
    for (var i = 0; i < VerifierRejectionMemory.MAX_REJECTIONS + 1; i++) {
      rejected.add(finding("src/F" + i + ".cs", 3, "Rejected claim number " + i));
    }
    memory.remember(head, rejected, List.of());

    var oldest = rejected.get(0);
    var newest = rejected.get(rejected.size() - 1);
    assertEquals(
        List.of(oldest.title()),
        titlesOf(memory.withoutRejectionsOnThisHead(session("2659f683"), response(oldest, newest))),
        "the oldest rejection is forgotten past the cap and the newest is still recalled");
  }

  @Test
  void evictsTheLeastRecentlyWrittenPullRequestPastTheCap() {
    var claim = finding("src/A.cs", 3, "Concatenated SQL");
    for (var pr = 1; pr <= VerifierRejectionMemory.MAX_PULL_REQUESTS + 1; pr++) {
      memory.remember(
          ReviewSession.create("owner/repo", pr, "PR", "2659f683"), List.of(claim), List.of());
    }

    assertEquals(VerifierRejectionMemory.MAX_PULL_REQUESTS, memory.size());
    assertEquals(
        List.of("Concatenated SQL"),
        titlesOf(
            memory.withoutRejectionsOnThisHead(
                ReviewSession.create("owner/repo", 1, "PR", "2659f683"), response(claim))),
        "the first pull request written was evicted");
  }
}
