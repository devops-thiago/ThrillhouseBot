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
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every step that lets a comment change a finding's status, with the same comment read twice: once
 * in a review where GitHub confirmed the author's write access, and once where it did not. The
 * author is an organization {@code MEMBER} both times, so the association alone would let the
 * comment through; only the confirmed permission may.
 */
class WriteAccessGatesTest {

  private static final String BOT = "thrillhousebot";
  private static final BotIdentity BOT_ID = BotIdentity.of(BOT);
  private static final String MEMBER = "org-member";

  /** The review in which GitHub answered write for the member. */
  private static final VerifiedWriters CONFIRMED = VerifiedWriters.of(List.of(MEMBER));

  /** The review in which it answered read, or could not be asked. */
  private static final VerifiedWriters UNCONFIRMED = VerifiedWriters.none();

  private static final ReviewResponse.Finding INJECTION =
      new ReviewResponse.Finding(
          "critical", "high", "src/A.java", 10, "SQL injection", "raw query", null, null);

  private static final String PREVIOUS_JSON =
      """
      {"findings": [
        {"risk": "critical", "confidence": "high", "file": "src/A.java", "line": 10,
         "title": "SQL injection", "description": "raw query"}
      ]}
      """;

  private static final String PATCH = "@@ -10,1 +10,1 @@\n-old\n+new";

  private final FollowUpAnalyzer analyzer = new FollowUpAnalyzer(new ObjectMapper());

  private static GitHubReviewClient.PullRequestComment root() {
    return new GitHubReviewClient.PullRequestComment(
        100L,
        null,
        "src/A.java",
        "**CRITICAL — SQL injection**\n\n" + SuggestionFormatter.findingMarker(1),
        new GitHubReviewClient.ReviewResponse.User(BOT),
        "NONE");
  }

  private static List<GitHubReviewClient.PullRequestComment> threadWithMemberReply(String body) {
    return List.of(
        root(),
        new GitHubReviewClient.PullRequestComment(
            101L,
            100L,
            "src/A.java",
            body,
            new GitHubReviewClient.ReviewResponse.User(MEMBER),
            "MEMBER"));
  }

  private static List<GitHubCommentClient.IssueComment> memberSays(String body) {
    return List.of(
        new GitHubCommentClient.IssueComment(
            900L, body, new GitHubReviewClient.ReviewResponse.User(MEMBER), "MEMBER"));
  }

  private static List<ReviewResponse.PreviousFindingStatus> unresolved() {
    return List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"));
  }

  @Test
  void aReplyTakesAFindingOutOfTheBackstopOnlyWhenItsAuthorIsConfirmed() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", PATCH));
    var comments = threadWithMemberReply("Intentional, leaving it as is.");

    assertTrue(
        analyzer
            .unreportedUnresolvedStatuses(
                List.of(PREVIOUS_JSON), List.of(), comments, resolver, BOT_ID, CONFIRMED)
            .isEmpty(),
        "a maintainer answered, so the dropped finding is not held");
    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(PREVIOUS_JSON), List.of(), comments, resolver, BOT_ID, UNCONFIRMED);
    assertEquals(1, held.size(), "a member nobody confirmed answered nothing");
    assertEquals("unresolved", held.get(0).status());
  }

  @Test
  void aReplyDropsAReRaisedFindingOnlyWhenItsAuthorIsConfirmed() {
    var comments = threadWithMemberReply("Declining, guarded upstream.");
    var reRaised = new ReviewResponse(List.of(INJECTION), List.of(), null);

    assertTrue(
        analyzer
            .dropRepliedDuplicates(
                reRaised,
                List.of(PREVIOUS_JSON),
                comments,
                BOT_ID,
                CONFIRMED,
                new DiffLineResolver(Map.of()))
            .findings()
            .isEmpty());
    assertSame(
        reRaised,
        analyzer.dropRepliedDuplicates(
            reRaised,
            List.of(PREVIOUS_JSON),
            comments,
            BOT_ID,
            UNCONFIRMED,
            new DiffLineResolver(Map.of())));
  }

  @Test
  void aClearDirectiveOnTheFindingsThreadClosesItOnlyWhenItsAuthorIsConfirmed() {
    var comments = threadWithMemberReply("@thrillhousebot resolved");

    assertEquals(
        "resolved",
        analyzer
            .clearNamedInConversation(
                List.of(INJECTION), unresolved(), List.of(), comments, BOT_ID, CONFIRMED)
            .get(0)
            .status());
    assertEquals(
        unresolved(),
        analyzer.clearNamedInConversation(
            List.of(INJECTION), unresolved(), List.of(), comments, BOT_ID, UNCONFIRMED));
  }

  @Test
  void aClearDirectiveOnTheConversationClosesAFindingOnlyWhenItsAuthorIsConfirmed() {
    var conversation = memberSays("@thrillhousebot resolved `src/A.java:10` — SQL injection");

    assertEquals(
        "resolved",
        analyzer
            .clearNamedInConversation(
                List.of(INJECTION), unresolved(), conversation, List.of(), BOT_ID, CONFIRMED)
            .get(0)
            .status());
    assertEquals(
        unresolved(),
        analyzer.clearNamedInConversation(
            List.of(INJECTION), unresolved(), conversation, List.of(), BOT_ID, UNCONFIRMED));
  }

  @Test
  void aDeclineDirectiveOnTheConversationDeclinesAFindingOnlyWhenItsAuthorIsConfirmed() {
    var conversation =
        memberSays("@thrillhousebot declined `src/A.java:10` — SQL injection\n\nAccepted risk.");

    assertEquals(
        "justified",
        analyzer
            .recheckDeclines(
                List.of(INJECTION),
                unresolved(),
                List.of(),
                conversation,
                BOT_ID,
                CONFIRMED,
                () -> "")
            .get(0)
            .status());
    assertEquals(
        unresolved(),
        analyzer.recheckDeclines(
            List.of(INJECTION),
            unresolved(),
            List.of(),
            conversation,
            BOT_ID,
            UNCONFIRMED,
            () -> ""));
  }

  @Test
  void aDeclineIsOfferedAsALearningOnlyWhenItsAuthorIsConfirmed() {
    var comments = threadWithMemberReply("Queries here only take values from the fixed enum.");
    var diff = "diff --git a/src/A.java\n@@ -10,1 +10,1 @@\n+  int answer() { return 42; }\n";

    var learned =
        analyzer.survivingDeclines(
            List.of(INJECTION), List.of(1), comments, List.of(), BOT_ID, CONFIRMED, () -> diff);
    assertEquals(1, learned.size());
    assertEquals(List.of(MEMBER), learned.get(0).contributors());
    assertTrue(
        analyzer
            .survivingDeclines(
                List.of(INJECTION),
                List.of(1),
                comments,
                List.of(),
                BOT_ID,
                UNCONFIRMED,
                () -> diff)
            .isEmpty());
  }

  @Test
  void aModelReportedDeclineStandsOnlyWhenTheReplyBehindItIsAConfirmedWriters() {
    var comments = threadWithMemberReply("Intentional, this is the house style.");
    var justified =
        List.of(new ReviewResponse.PreviousFindingStatus(1, "justified", "declined on the thread"));

    assertEquals(
        justified,
        analyzer.recheckDeclines(
            List.of(INJECTION), justified, comments, List.of(), BOT_ID, CONFIRMED, () -> ""));
    var kept =
        analyzer.recheckDeclines(
            List.of(INJECTION), justified, comments, List.of(), BOT_ID, UNCONFIRMED, () -> "");
    assertEquals("unresolved", kept.get(0).status());
    assertTrue(RebuttalContradiction.explainsKeptFinding(kept.get(0).note()));
    assertTrue(kept.get(0).note().startsWith(RebuttalContradiction.UNCONFIRMED_LEAD_IN));
    assertTrue(kept.get(0).note().contains("`src/A.java:10`"));
    assertTrue(kept.get(0).note().contains("@thrillhousebot declined"));
  }

  @Test
  void thePromptMarksAReplyWhoseAuthorWasNotConfirmedAsContextOnly() {
    var comments =
        List.of(
            root(),
            new GitHubReviewClient.PullRequestComment(
                101L,
                100L,
                "src/A.java",
                "Won't fix, this is by design.",
                new GitHubReviewClient.ReviewResponse.User("pr-author"),
                "CONTRIBUTOR"),
            new GitHubReviewClient.PullRequestComment(
                102L,
                100L,
                "src/A.java",
                "Agreed.",
                new GitHubReviewClient.ReviewResponse.User(MEMBER),
                "MEMBER"),
            new GitHubReviewClient.PullRequestComment(
                103L,
                100L,
                "src/A.java",
                "Noted.",
                new GitHubReviewClient.ReviewResponse.User(BOT),
                "NONE"));

    var confirmed =
        analyzer.buildPreviousFindingsContext(
            PREVIOUS_JSON, List.of(), comments, BOT_ID, CONFIRMED);
    assertTrue(
        confirmed.contains(
            "- @pr-author (no write access, context only): Won't fix, this is by design."));
    assertTrue(confirmed.contains("- @org-member: Agreed."));
    assertTrue(confirmed.contains("- @thrillhousebot: Noted."));

    var unconfirmed =
        analyzer.buildPreviousFindingsContext(
            PREVIOUS_JSON, List.of(), comments, BOT_ID, UNCONFIRMED);
    assertTrue(unconfirmed.contains("- @org-member (no write access, context only): Agreed."));
    assertFalse(unconfirmed.contains("- @org-member: Agreed."));
    assertTrue(unconfirmed.contains("- @thrillhousebot: Noted."));
  }
}
