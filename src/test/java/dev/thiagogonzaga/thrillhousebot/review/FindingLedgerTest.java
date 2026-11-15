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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.PullRequestComment;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.RaisedAnchor;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.Side;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FindingLedgerTest {

  private static final String FILE = "src/A.java";
  private static final String HEAD_1 = "1111111";
  private static final String HEAD_2 = "2222222";
  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));

  /** Line 11 adds {@code fresh();}; lines 11-12 of the base removed two {@code verify} calls. */
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

  private static final DiffLineResolver RESOLVER = new DiffLineResolver(Map.of(FILE, PATCH));

  private static Finding finding(int line, String title, String quote) {
    return new Finding(RiskLevel.MEDIUM, FILE, line, title, "d", quote, null);
  }

  private static ReviewResponse.Finding stored(int line, String title) {
    return new ReviewResponse.Finding("high", FILE, line, title, "d", "x();", null);
  }

  private static String keyOf(int line, String title) {
    return SuggestionFormatter.findingKey(FILE, line, title);
  }

  private static FindingLedger.Round round(
      String head, List<Finding> raised, Map<String, FindingLedger.Reported> statuses) {
    return new FindingLedger.Round(
        head, raised, statuses, Map.of(FILE, "blob-a"), RESOLVER, Map.of());
  }

  private static FindingLedger.Row openRow(int line, String title) {
    return new FindingLedger.Row(
        keyOf(line, title),
        FindingLedger.Subject.of(stored(line, title)),
        new RaisedAnchor(Side.RIGHT, true),
        "blob-a",
        new FindingLedger.Status(FindingLedger.OPEN, HEAD_1, HEAD_1),
        FindingLedger.Thread.NONE);
  }

  @Test
  void aRoundsOwnFindingsGetAnOpenRowWithTheirAnchorAndBlob() {
    var onHead = finding(13, "Fresh is wrong", "fresh();");
    var removed =
        finding(12, "Close was dropped", "verify(client).call();\nverify(client).close();");
    var nowhere = finding(13, "Quotes nothing", "absent();");

    var update =
        FindingLedger.afterRound(
            Map.of(), round(HEAD_1, List.of(onHead, removed, nowhere), Map.of()));

    assertFalse(update.statusMoved(), "new findings alone move no status");
    var head = update.rows().get(keyOf(13, "Fresh is wrong"));
    assertEquals(new RaisedAnchor(Side.RIGHT, true), head.anchor());
    assertEquals("blob-a", head.blobSha());
    assertEquals(new FindingLedger.Status(FindingLedger.OPEN, HEAD_1, HEAD_1), head.status());
    assertEquals(FindingLedger.Thread.NONE, head.thread());
    assertEquals("medium", head.subject().risk());
    assertEquals("high", head.subject().confidence());
    assertEquals(
        new RaisedAnchor(Side.LEFT, true),
        update.rows().get(keyOf(12, "Close was dropped")).anchor());
    assertEquals(
        new RaisedAnchor(Side.RIGHT, false),
        update.rows().get(keyOf(13, "Quotes nothing")).anchor());
  }

  @Test
  void aFindingWithNoFileOrQuoteIsStoredWithoutAnAnchorMatchOrBlob() {
    var noFile = new Finding(null, null, 0, "Somewhere", "d", null, null);

    var row =
        FindingLedger.afterRound(Map.of(), round(HEAD_1, List.of(noFile), Map.of()))
            .rows()
            .values()
            .iterator()
            .next();

    assertEquals(new RaisedAnchor(Side.RIGHT, false), row.anchor());
    assertNull(row.blobSha());
    assertNull(row.subject().risk());
  }

  @Test
  void aStatusIsWrittenOnlyWhenItDiffersFromTheStoredOne() {
    var held = openRow(3, "Held");
    var fixed = openRow(4, "Fixed");
    var stored = Map.of(held.key(), held, fixed.key(), fixed);
    var statuses =
        Map.of(
            held.key(), new FindingLedger.Reported(held.subject(), FindingLedger.OPEN),
            fixed.key(), new FindingLedger.Reported(fixed.subject(), FindingLedger.RESOLVED));

    var update = FindingLedger.afterRound(stored, round(HEAD_2, List.of(), statuses));

    assertTrue(update.statusMoved());
    assertEquals(List.of(fixed.key()), List.copyOf(update.rows().keySet()));
    assertEquals(
        new FindingLedger.Status(FindingLedger.RESOLVED, HEAD_1, HEAD_2),
        update.rows().get(fixed.key()).status());

    var again =
        FindingLedger.afterRound(
            Map.of(held.key(), held, fixed.key(), update.rows().get(fixed.key())),
            round(HEAD_2, List.of(), statuses));
    assertFalse(again.statusMoved(), "the same statuses again move nothing");
    assertTrue(again.rows().isEmpty());
  }

  @Test
  void aPreviousFindingWithNoRowIsBackfilledAndCountsAsMoved() {
    var subject = FindingLedger.Subject.of(stored(5, "From before the table"));
    var key = subject.key();

    var update =
        FindingLedger.afterRound(
            Map.of(),
            round(
                HEAD_2,
                List.of(),
                Map.of(key, new FindingLedger.Reported(subject, FindingLedger.OPEN))));

    assertTrue(update.statusMoved());
    var row = update.rows().get(key);
    assertNull(row.anchor(), "how it was anchored is not known");
    assertNull(row.blobSha());
    assertEquals(new FindingLedger.Status(FindingLedger.OPEN, null, HEAD_2), row.status());
  }

  @Test
  void aReRaisedClosedFindingIsOpenAgainAndAnOpenOneIsLeftAsItIs() {
    var raised = finding(13, "Fresh is wrong", "fresh();");
    var subject = FindingLedger.Subject.of(raised);
    var closed =
        new FindingLedger.Row(
            subject.key(),
            subject,
            new RaisedAnchor(Side.RIGHT, true),
            "blob-a",
            new FindingLedger.Status(FindingLedger.RESOLVED, HEAD_1, HEAD_1),
            FindingLedger.Thread.NONE);

    var reopened =
        FindingLedger.afterRound(
            Map.of(closed.key(), closed), round(HEAD_2, List.of(raised), Map.of()));
    assertEquals(
        new FindingLedger.Status(FindingLedger.OPEN, HEAD_1, HEAD_2),
        reopened.rows().get(closed.key()).status());

    var stillOpen =
        FindingLedger.afterRound(
            Map.of(closed.key(), reopened.rows().get(closed.key())),
            round(HEAD_2, List.of(raised), Map.of()));
    assertTrue(stillOpen.rows().isEmpty());
  }

  @Test
  void statusesNameTheEffectiveRoundsFindingsByIdAndAnEarlierRoundsByItsCopies() {
    var first = stored(1, "First");
    var second = stored(2, "Second");
    var olderCopy = stored(9, "Older copy");
    var newerCopy = stored(8, "Newer copy");
    var statuses =
        List.of(
            new ReviewResult.PreviousFindingStatus(1, "unresolved", "n"),
            new ReviewResult.PreviousFindingStatus(2, "resolved", "n"),
            new ReviewResult.PreviousFindingStatus(3, "resolved", "n"),
            new ReviewResult.PreviousFindingStatus(0, "resolved", "n"),
            new ReviewResult.PreviousFindingStatus(1, "mystery", "n"),
            new ReviewResult.PreviousFindingStatus(
                FollowUpAnalyzer.EARLIER_ROUND_ID,
                "superseded",
                "n",
                new ReviewResult.EarlierRound(
                    olderCopy, null, List.of(olderCopy, newerCopy), false)));

    var byKey = FindingLedger.statuses(statuses, List.of(first, second));

    assertEquals(FindingLedger.OPEN, byKey.get(keyOf(1, "First")).status());
    assertEquals(FindingLedger.RESOLVED, byKey.get(keyOf(2, "Second")).status());
    assertEquals(FindingLedger.SUPERSEDED, byKey.get(keyOf(9, "Older copy")).status());
    assertEquals(FindingLedger.SUPERSEDED, byKey.get(keyOf(8, "Newer copy")).status());
    assertEquals(4, byKey.size(), "an id outside the round and an unknown status name nothing");
  }

  @Test
  void aStatusThatClosesAFindingWinsOverOneThatHoldsItOpen() {
    var finding = stored(1, "Twice");
    var holds = new ReviewResult.PreviousFindingStatus(1, "unresolved", "n");
    var closes = new ReviewResult.PreviousFindingStatus(1, "justified", "n");

    assertEquals(
        FindingLedger.JUSTIFIED,
        FindingLedger.statuses(List.of(holds, closes), List.of(finding))
            .get(keyOf(1, "Twice"))
            .status());
    assertEquals(
        FindingLedger.JUSTIFIED,
        FindingLedger.statuses(List.of(closes, holds), List.of(finding))
            .get(keyOf(1, "Twice"))
            .status());
  }

  @Test
  void aDoubleCheckItemThisRoundsThreadReplacedIsClosedAsReplaced() {
    // #1312, ThrillhouseBot-test#293: the thread for an earlier double-check item came back under
    // another key, and the item's row stayed open beside the thread's, 21 rows to 20 counted.
    var item = openRow(10, "Offline-sensor stub returns a sentinel");
    var thread = finding(11, "Test stub returns a sentinel value, leaving offline untested", null);
    var statuses =
        FindingLedger.statuses(
            List.of(
                new ReviewResult.PreviousFindingStatus(
                    1, "unresolved", FollowUpAnalyzer.REPLACED_NOTE)),
            List.of(stored(10, "Offline-sensor stub returns a sentinel")));

    var update =
        FindingLedger.afterRound(
            Map.of(item.key(), item), round(HEAD_2, List.of(thread), statuses));

    assertTrue(update.statusMoved());
    assertEquals(
        new FindingLedger.Status(FindingLedger.REPLACED, HEAD_1, HEAD_2),
        update.rows().get(item.key()).status());
    var threadKey = FindingLedger.Subject.of(thread).key();
    assertEquals(FindingLedger.OPEN, update.rows().get(threadKey).status().value());
  }

  @Test
  void onlyAHoldUnderTheReplacedNoteMarksARowReplaced() {
    var note = FollowUpAnalyzer.REPLACED_NOTE;
    assertEquals(Optional.of(FindingLedger.REPLACED), FindingLedger.rowStatus("unresolved", note));
    assertEquals(Optional.of(FindingLedger.RESOLVED), FindingLedger.rowStatus("resolved", note));
    assertEquals(Optional.of(FindingLedger.OPEN), FindingLedger.rowStatus("unresolved", "n"));
    assertEquals(Optional.empty(), FindingLedger.rowStatus("mystery", note));
  }

  @Test
  void rowStatusesAreTheFourTheLedgerKnows() {
    assertEquals(Optional.of(FindingLedger.OPEN), FindingLedger.rowStatus("Unresolved"));
    assertEquals(Optional.of(FindingLedger.SUPERSEDED), FindingLedger.rowStatus("SUPERSEDED"));
    assertEquals(Optional.empty(), FindingLedger.rowStatus("open"));
    assertEquals(Optional.empty(), FindingLedger.rowStatus(null));
  }

  private static PullRequestComment comment(
      long id, Long replyTo, String login, String body, String commit) {
    return new PullRequestComment(
        id,
        replyTo,
        FILE,
        body,
        login == null ? null : new GitHubReviewClient.ReviewResponse.User(login),
        null,
        null,
        null,
        commit);
  }

  @Test
  void theBotsOwnThreadsAreFoundByTheirMarkerKeyAtTheCommitTheyWerePostedOn() {
    var marker = SuggestionFormatter.findingMarker(1, FILE, 3, "Held");
    var comments =
        List.of(
            comment(30, null, "thrillhousebot[bot]", "later " + marker, "c3"),
            comment(20, null, "thrillhousebot[bot]", "first " + marker, null),
            comment(40, null, "thrillhousebot[bot]", "last " + marker, "c8"),
            comment(21, 20L, "thrillhousebot[bot]", "reply " + marker, "c4"),
            comment(
                22,
                null,
                "someone",
                "forged " + SuggestionFormatter.findingMarker(1, FILE, 4, "x"),
                "c5"),
            comment(23, null, null, "no user", "c6"),
            comment(
                24,
                null,
                "thrillhousebot[bot]",
                "old marker " + SuggestionFormatter.findingMarker(2),
                "c7"));

    var threads = FindingLedger.threads(comments, BOT, HEAD_2);

    assertEquals(Map.of(keyOf(3, "Held"), new FindingLedger.Thread(20L, HEAD_2)), threads);
    assertEquals(
        Map.of(keyOf(3, "Held"), new FindingLedger.Thread(30L, "c3")),
        FindingLedger.threads(List.of(comments.get(0)), BOT, HEAD_2));
  }

  @Test
  void aThreadIsRecordedOnARowThatHasNoneAndNeverReplacesOne() {
    var noThread = openRow(3, "Held");
    var threaded = openRow(4, "Threaded").withThread(new FindingLedger.Thread(7L, HEAD_1));
    var threads =
        Map.of(
            noThread.key(),
            new FindingLedger.Thread(40L, HEAD_2),
            threaded.key(),
            new FindingLedger.Thread(41L, HEAD_2),
            "deadbeef",
            new FindingLedger.Thread(42L, HEAD_2));

    var update =
        FindingLedger.afterRound(
            Map.of(noThread.key(), noThread, threaded.key(), threaded),
            new FindingLedger.Round(HEAD_2, List.of(), Map.of(), Map.of(), RESOLVER, threads));

    assertFalse(update.statusMoved(), "a thread is not a status");
    assertEquals(
        Map.of(noThread.key(), noThread.withThread(new FindingLedger.Thread(40L, HEAD_2))),
        update.rows());
  }
}
