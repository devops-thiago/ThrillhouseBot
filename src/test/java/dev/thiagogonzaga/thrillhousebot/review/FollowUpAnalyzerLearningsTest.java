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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link FollowUpAnalyzer#survivingDeclines}: which declines are safe to remember across pull
 * requests (#38), with the two dogfood shapes as fixtures — PR #159's platform-fact decline, which
 * must be remembered, and PR #160's race decline, which must not be (#169).
 */
class FollowUpAnalyzerLearningsTest {

  static final String BOT = "thrillhousebot";
  static final BotIdentity BOT_ID = BotIdentity.of(BOT);

  // --- PR #159: "renderThread misses deeper nested replies", declined with a platform invariant

  static final String NESTED_FILE =
      "src/main/java/dev/thiagogonzaga/thrillhousebot/review/MaintainerReplyService.java";

  static final String NESTED_TITLE = "renderThread misses deeper nested replies";

  static final ReviewResponse.Finding NESTED =
      new ReviewResponse.Finding(
          "medium",
          "medium",
          NESTED_FILE,
          156,
          NESTED_TITLE,
          "renderThread only renders direct replies to the root; a reply to a reply is dropped.",
          null,
          null);

  static final String FLAT_THREADS =
      "Not changing this — GitHub PR review threads are flat: every reply's in_reply_to_id is the"
          + " thread root, never an intermediate comment, so there is no deeper level to miss.";

  // --- PR #160: the PrPauseService race, declined on an "async after the ack" premise

  static final String PAUSE_FILE =
      "src/main/java/dev/thiagogonzaga/thrillhousebot/webhook/PrPauseService.java";

  static final String RACE_TITLE =
      "Race condition in pause() can cause a UniqueConstraint violation under concurrent webhooks";

  static final ReviewResponse.Finding RACE =
      new ReviewResponse.Finding(
          "medium",
          "low",
          PAUSE_FILE,
          60,
          RACE_TITLE,
          "pause() checks for an existing PausedPr and then inserts one; two deliveries can both"
              + " pass the check before either inserts.",
          null,
          null);

  static final String ASYNC_AFTER_ACK =
      "Not changed — pause() is only ever called from the /pause command path, which runs"
          + " asynchronously on the review executor after the webhook has returned 200.";

  /** Reviewed code that holds no dispatch — the #160 executor lived outside the diff. */
  static final String PLAIN_DIFF =
      """
      diff --git a/src/main/java/Foo.java
      @@ -1,2 +1,3 @@
      +  int answer() { return 42; }
      """;

  /** Reviewed code that contradicts the race rebuttal. */
  static final String DISPATCHING_DIFF =
      """
      diff --git a/src/main/java/CommentCommandService.java
      @@ -130,7 +130,9 @@
      +    executor.execute(() -> execute(ctx));
      """;

  private final FollowUpAnalyzer analyzer = new FollowUpAnalyzer(new ObjectMapper());

  static GitHubReviewClient.PullRequestComment comment(
      long id, Long inReplyTo, String path, String body, String author, String association) {
    return new GitHubReviewClient.PullRequestComment(
        id, inReplyTo, path, body, new GitHubReviewClient.ReviewResponse.User(author), association);
  }

  /** A bot thread on {@code finding} with the given maintainer replies. */
  static List<GitHubReviewClient.PullRequestComment> thread(
      ReviewResponse.Finding finding, long rootId, String... replies) {
    var comments = new ArrayList<GitHubReviewClient.PullRequestComment>();
    comments.add(
        comment(rootId, null, finding.file(), "**MEDIUM — " + finding.title() + "**", BOT, "NONE"));
    for (var i = 0; i < replies.length; i++) {
      comments.add(
          comment(rootId + 1 + i, rootId, finding.file(), replies[i], "maintainer", "MEMBER"));
    }
    return comments;
  }

  static GitHubCommentClient.IssueComment conversation(long id, String body) {
    return new GitHubCommentClient.IssueComment(
        id, body, new GitHubReviewClient.ReviewResponse.User("maintainer"), "OWNER");
  }

  @Test
  void thePr159PlatformFactDeclineIsRememberedWithItsReasonAndSource() {
    var surviving =
        analyzer.survivingDeclines(
            List.of(NESTED),
            List.of(1),
            thread(NESTED, 500L, FLAT_THREADS),
            List.of(),
            BOT_ID,
            () -> PLAIN_DIFF);

    assertEquals(1, surviving.size());
    var decline = surviving.get(0);
    assertEquals(NESTED, decline.finding());
    assertEquals(FLAT_THREADS, decline.reason());
    assertEquals(501L, decline.sourceCommentId());
    assertTrue(decline.onThread());
    assertEquals("maintainer", decline.author());
    assertEquals(List.of("maintainer"), decline.contributors());
  }

  @Test
  void thePr160RaceDeclineIsNotRememberedEvenWhenTheRefutingCodeIsOutsideTheDiff() {
    // The re-check could not see ReviewExecutorProducer, so the decline stood as "justified" this
    // round. Remembering it would suppress the valid race finding on every later pull request.
    var surviving =
        analyzer.survivingDeclines(
            List.of(RACE),
            List.of(1),
            thread(RACE, 700L, ASYNC_AFTER_ACK),
            List.of(),
            BOT_ID,
            () -> PLAIN_DIFF);

    assertTrue(surviving.isEmpty(), "a refutable no-concurrency premise is never remembered");
  }

  @Test
  void aDeclineThatStoodOnlyByAnsweringThePushBackTwiceIsNotRemembered() {
    // The first reply was contradicted and reopened the finding; the second reply is the
    // guaranteed escape hatch and records it justified — but the code contradicted the premise.
    var comments =
        thread(RACE, 700L, ASYNC_AFTER_ACK, "Still no — the executor never runs two for one PR.");
    var statuses =
        analyzer.recheckDeclines(
            List.of(RACE),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "justified", "declined")),
            comments,
            List.of(),
            BOT_ID,
            () -> DISPATCHING_DIFF);
    assertEquals("justified", statuses.get(0).status(), "precondition: the escape hatch won");

    var surviving =
        analyzer.survivingDeclines(
            List.of(RACE), List.of(1), comments, List.of(), BOT_ID, () -> DISPATCHING_DIFF);

    assertTrue(surviving.isEmpty());
  }

  @Test
  void aStyleDeclineOfAConcurrencyFindingIsRememberedBecauseTheCodeCannotRefuteIt() {
    var surviving =
        analyzer.survivingDeclines(
            List.of(RACE),
            List.of(1),
            thread(RACE, 700L, "Intentional — command handlers stay this way by house style."),
            List.of(),
            BOT_ID,
            () -> DISPATCHING_DIFF);

    assertEquals(1, surviving.size());
  }

  @Test
  void everyMaintainerReplyIsKeptAsTheReasonAndTheFirstIsTheSource() {
    var surviving =
        analyzer.survivingDeclines(
            List.of(NESTED),
            List.of(1),
            thread(NESTED, 500L, FLAT_THREADS, "See the REST docs for review comments."),
            List.of(),
            BOT_ID,
            () -> PLAIN_DIFF);

    assertEquals(
        FLAT_THREADS + "\n\nSee the REST docs for review comments.", surviving.get(0).reason());
    assertEquals(501L, surviving.get(0).sourceCommentId());
  }

  @Test
  void aReplyFromAnAuthorWithoutWriteAccessIsNotAReason() {
    var comments = new ArrayList<>(thread(NESTED, 500L));
    comments.add(comment(501L, 500L, NESTED_FILE, FLAT_THREADS, "fork-author", "CONTRIBUTOR"));
    comments.add(comment(502L, 500L, NESTED_FILE, FLAT_THREADS, BOT, "NONE"));
    comments.add(comment(503L, 500L, NESTED_FILE, "  ", "maintainer", "MEMBER"));
    comments.add(comment(505L, 500L, NESTED_FILE, null, "maintainer", "MEMBER"));
    comments.add(
        new GitHubReviewClient.PullRequestComment(504L, 500L, NESTED_FILE, FLAT_THREADS, null));

    var surviving =
        analyzer.survivingDeclines(
            List.of(NESTED), List.of(1), comments, List.of(), BOT_ID, () -> PLAIN_DIFF);

    assertTrue(surviving.isEmpty());
  }

  @Test
  void aConversationDeclineWithAReasonIsRememberedAndCitesTheComment() {
    var directive =
        "@thrillhousebot declined `"
            + NESTED_FILE
            + ":156` — "
            + NESTED_TITLE
            + "\n\n"
            + FLAT_THREADS;

    var surviving =
        analyzer.survivingDeclines(
            List.of(NESTED),
            List.of(1),
            List.of(),
            List.of(
                conversation(900L, "unrelated remark"),
                new GitHubCommentClient.IssueComment(
                    901L,
                    directive,
                    new GitHubReviewClient.ReviewResponse.User("outsider"),
                    "NONE"),
                conversation(902L, directive)),
            BOT_ID,
            () -> PLAIN_DIFF);

    assertEquals(1, surviving.size());
    assertEquals(902L, surviving.get(0).sourceCommentId());
    assertFalse(surviving.get(0).onThread());
    assertEquals(FLAT_THREADS, surviving.get(0).reason());
  }

  @Test
  void aConversationDirectiveNamingAnotherFindingIsNotItsReason() {
    var other = "@thrillhousebot declined `" + PAUSE_FILE + ":60` — " + RACE_TITLE + "\n\nNo.";
    var titleless = new ReviewResponse.Finding("low", NESTED_FILE, 3, null, null, null, null);

    assertTrue(
        analyzer
            .survivingDeclines(
                List.of(NESTED, titleless),
                List.of(1, 2),
                List.of(),
                List.of(conversation(903L, other)),
                BOT_ID,
                () -> PLAIN_DIFF)
            .isEmpty());
  }

  @Test
  void aConversationDeclineWithoutAReasonIsNotRemembered() {
    var directive = "@thrillhousebot declined `" + NESTED_FILE + ":156` — " + NESTED_TITLE;

    var surviving =
        analyzer.survivingDeclines(
            List.of(NESTED),
            List.of(1),
            null,
            List.of(conversation(902L, directive)),
            BOT_ID,
            () -> PLAIN_DIFF);

    assertTrue(surviving.isEmpty(), "a bare decline, like a bare thumbs-down, carries no why");
  }

  @Test
  void aJustifiedWithNoMaintainerTextBehindItIsNotRemembered() {
    var noAnchor = new ReviewResponse.Finding("low", null, 3, null, null, null, null);
    var noFile = new ReviewResponse.Finding("low", null, 3, NESTED_TITLE, "d", null, null);

    assertTrue(
        analyzer
            .survivingDeclines(
                List.of(NESTED, noAnchor, noFile),
                List.of(1, 2, 3),
                List.of(),
                null,
                BOT_ID,
                () -> PLAIN_DIFF)
            .isEmpty());
    assertTrue(
        analyzer
            .survivingDeclines(
                List.of(noAnchor), List.of(1), List.of(), List.of(), BOT_ID, () -> PLAIN_DIFF)
            .isEmpty());
  }

  @Test
  void nothingIsRememberedWhenTheRecheckDidNotRun() {
    var comments = thread(NESTED, 500L, FLAT_THREADS);
    var recheckOff = new FollowUpAnalyzer(new ObjectMapper(), false);

    assertTrue(
        recheckOff
            .survivingDeclines(
                List.of(NESTED), List.of(1), comments, List.of(), BOT_ID, () -> PLAIN_DIFF)
            .isEmpty(),
        "without the #169 re-check no decline has survived anything");
    assertTrue(
        analyzer
            .survivingDeclines(List.of(NESTED), List.of(1), comments, List.of(), BOT_ID, () -> " ")
            .isEmpty());
    assertTrue(
        analyzer
            .survivingDeclines(List.of(NESTED), List.of(1), comments, List.of(), BOT_ID, () -> null)
            .isEmpty());
    assertTrue(
        analyzer
            .survivingDeclines(List.of(NESTED), List.of(1), comments, List.of(), BOT_ID, null)
            .isEmpty());
  }

  @Test
  void onlyJustifiedIdsInsideThePriorRoundAreRead() {
    var comments = thread(NESTED, 500L, FLAT_THREADS);

    assertTrue(
        analyzer
            .survivingDeclines(null, List.of(1), comments, List.of(), BOT_ID, () -> PLAIN_DIFF)
            .isEmpty());
    assertTrue(
        analyzer
            .survivingDeclines(List.of(NESTED), null, comments, List.of(), BOT_ID, () -> PLAIN_DIFF)
            .isEmpty());
    assertTrue(
        analyzer
            .survivingDeclines(
                List.of(NESTED), List.of(), comments, List.of(), BOT_ID, () -> PLAIN_DIFF)
            .isEmpty());
    assertTrue(
        analyzer
            .survivingDeclines(
                List.of(NESTED), List.of(0, 2), comments, List.of(), BOT_ID, () -> PLAIN_DIFF)
            .isEmpty());
    assertEquals(
        1,
        analyzer
            .survivingDeclines(
                List.of(NESTED), List.of(1, 1), comments, List.of(), BOT_ID, () -> PLAIN_DIFF)
            .size(),
        "a repeated id is one decline");
  }

  @Test
  void aRefutablePremiseIsRecognizedWhateverTheFindingIsCalled() {
    assertTrue(RebuttalContradiction.assertsRefutablePremise(ASYNC_AFTER_ACK));
    assertTrue(
        RebuttalContradiction.assertsRefutablePremise("It never runs concurrently for one PR."));
    assertFalse(RebuttalContradiction.assertsRefutablePremise(FLAT_THREADS));
    assertFalse(RebuttalContradiction.assertsRefutablePremise("House style, not changing."));
    assertFalse(RebuttalContradiction.assertsRefutablePremise(null));
    assertTrue(
        RebuttalContradiction.assertsRefutablePremise("x".repeat(20_001)),
        "a reason too long for the re-check to read was never re-checked");
  }

  @Test
  void aRaceDescribedWithoutConcurrencyWordsIsStillNotRemembered() {
    var doubleInsert =
        new ReviewResponse.Finding(
            "medium",
            "low",
            PAUSE_FILE,
            60,
            "pause() double-inserts PausedPr and violates the unique constraint on redelivery",
            "Two deliveries both pass the existence check.",
            null,
            null);

    var surviving =
        analyzer.survivingDeclines(
            List.of(doubleInsert),
            List.of(1),
            thread(doubleInsert, 800L, "Not changed — it is only ever called from /pause."),
            List.of(),
            BOT_ID,
            () -> PLAIN_DIFF);

    assertTrue(surviving.isEmpty());
  }
}
