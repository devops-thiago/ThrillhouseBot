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

import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.AUTH;
import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.VERIFIED;
import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.context;
import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.request;
import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.result;
import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.review;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HeadMovedGuard}: the head-moved stand-down and its carry-over (#704, #806).
 */
class HeadMovedGuardTest {

  private final ReviewContextLoader contextLoader = mock(ReviewContextLoader.class);
  private final SupersededFindingsCarryover carryover =
      new SupersededFindingsCarryover(new ObjectMapper());
  private final HeadMovedGuard guard = new HeadMovedGuard(contextLoader, carryover);

  private void headAt(String sha) {
    when(contextLoader.currentHeadSha(any(), any())).thenReturn(Optional.ofNullable(sha));
  }

  @Test
  void aMovedHeadStandsTheRunDownAndHandsItsFindingsOn() {
    headAt("movedsha");

    var standDown = guard.check(review(false, 9L)).orElseThrow();

    assertEquals(ReviewSkipReason.HEAD_MOVED, standDown.reason());
    assertEquals(
        "head moved from abcdefgh to movedsha while the review ran — abandoning the post; the"
            + " queued run for the new head replaces it",
        standDown.skipDetail());
    assertEquals(HeadMovedGuard.SUPERSEDED_CHECK_TITLE, standDown.checkTitle());
    assertEquals(
        "The pull request head moved to movedsha while this review ran, so its result was not"
            + " posted. The review of the new head replaces it.",
        standDown.checkSummary());
    assertEquals(
        "Superseded: the PR head moved to movedsha during the review", standDown.sessionError());
    assertEquals("superseded", standDown.kind());
    verify(contextLoader).currentHeadSha(AUTH, request(false));
    var carried = carryover.take("owner", "repo", 42, "movedsha");
    assertEquals("abcdefgh", carried.supersededSha());
    assertEquals(List.of(VERIFIED), carried.findings());
  }

  @Test
  void aManualReviewIsGuardedToo() {
    headAt("movedsha");

    assertTrue(guard.check(review(true, 9L)).isPresent());
  }

  @Test
  void anUnmovedOrUnreadableHeadLetsTheRunPostAndCarriesNothing() {
    headAt("ABCDEFGH");
    assertTrue(guard.check(review(false, 9L)).isEmpty());

    headAt(null);
    assertTrue(guard.check(review(false, 9L)).isEmpty());

    assertTrue(carryover.take("owner", "repo", 42, "abcdefgh").isEmpty());
  }

  @Test
  void headMovedComparesTheReviewedShaAgainstTheFreshHead() {
    var req = request(false);
    assertTrue(HeadMovedGuard.headMoved(req, "other-sha"));
    assertFalse(HeadMovedGuard.headMoved(req, "ABCDEFGH"));
    // No reviewed sha to compare against: never treated as moved.
    var noSha =
        new ReviewOrchestrator.ReviewRequest(
            "owner", "repo", 42, "", "T", "", "base", "main", 1L, TestScopes.REPOSITORY_ID, false);
    assertFalse(HeadMovedGuard.headMoved(noSha, "other-sha"));
    var nullSha =
        new ReviewOrchestrator.ReviewRequest(
            "owner",
            "repo",
            42,
            null,
            "T",
            "",
            "base",
            "main",
            1L,
            TestScopes.REPOSITORY_ID,
            false);
    assertFalse(HeadMovedGuard.headMoved(nullSha, "other-sha"));
  }

  /**
   * A replacement that is itself superseded hands on its own findings plus the carried ones its
   * verdict still holds open — and only those: a carried finding it resolved is settled, a
   * persisted round's finding is tracked by that round, a duplicate id (model plus backstop) is
   * carried once, and an id outside the round is ignored.
   */
  @Test
  void findingsToCarryKeepsOwnFindingsAndTheCarriedOnesStillOpen() {
    var posted = new ReviewResponse.Finding("low", "low", "a.java", 1, "Posted", "d", "x", "y");
    var carriedA =
        new ReviewResponse.Finding("high", "high", "b.java", 2, "Carried A", "d", "x", "y");
    var carriedB =
        new ReviewResponse.Finding("high", "high", "c.java", 3, "Carried B", "d", "x", "y");
    var own = new ReviewResponse.Finding("medium", "high", "d.java", 4, "Own", "d", "x", "y");
    var merged = new ReviewResponse(List.of(posted, carriedA, carriedB), List.of(), null);
    var ctx =
        context(
            List.of(merged),
            new SupersededFindingsCarryover.Carried(
                "abcdefgh", "d4389d2aa", List.of(carriedA, carriedB)));
    var verdict =
        result(
            List.of(
                new ReviewResult.PreviousFindingStatus(1, "unresolved", "posted round's own"),
                new ReviewResult.PreviousFindingStatus(2, "unresolved", "still open"),
                new ReviewResult.PreviousFindingStatus(2, "unresolved", "backstop duplicate"),
                new ReviewResult.PreviousFindingStatus(3, "resolved", "fixed on this head"),
                new ReviewResult.PreviousFindingStatus(99, "unresolved", "no such id")));

    var carry =
        HeadMovedGuard.findingsToCarry(
            ctx, new ReviewResponse(List.of(own), List.of(), null), verdict);

    assertEquals(List.of(carriedA, own), carry);
  }

  /**
   * #959: the backstop holds a finding an older round raised under the id that round gave it, here
   * 3 — the position of a carried finding in the newest round's id space. It names that older
   * finding, not the carried one, so it carries neither; the carried finding the model reported
   * open under its own id is still carried.
   */
  @Test
  void findingsToCarryReadsABackstopHoldByItsFindingNotItsId() {
    var posted = new ReviewResponse.Finding("low", "low", "a.java", 1, "Posted", "d", "x", "y");
    var carriedA =
        new ReviewResponse.Finding("high", "high", "b.java", 2, "Carried A", "d", "x", "y");
    var carriedB =
        new ReviewResponse.Finding("high", "high", "c.java", 3, "Carried B", "d", "x", "y");
    var olderRound =
        new ReviewResponse.Finding("critical", "high", "e.java", 9, "Older", "d", "x", "y");
    var merged = new ReviewResponse(List.of(posted, carriedA, carriedB), List.of(), null);
    var ctx =
        context(
            List.of(merged),
            new SupersededFindingsCarryover.Carried(
                "abcdefgh", "d4389d2aa", List.of(carriedA, carriedB)));
    var verdict =
        result(
            List.of(
                new ReviewResult.PreviousFindingStatus(2, "unresolved", "still open"),
                new ReviewResult.PreviousFindingStatus(
                    3,
                    "unresolved",
                    "held by the backstop",
                    new ReviewResult.EarlierRound(olderRound, null))));

    var carry =
        HeadMovedGuard.findingsToCarry(
            ctx, new ReviewResponse(List.of(), List.of(), null), verdict);

    assertEquals(List.of(carriedA), carry);
  }
}
