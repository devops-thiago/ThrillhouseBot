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
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FollowUpAnalyzerTest {

  private final FollowUpAnalyzer analyzer = new FollowUpAnalyzer(new ObjectMapper());

  private static final String BOT = "thrillhousebot";

  private static final BotIdentity BOT_ID = BotIdentity.of(BOT);

  private static final String PREVIOUS_JSON =
      """
      {"findings": [
        {"risk": "critical", "file": "src/A.java", "line": 10, "title": "SQL injection",
         "description": "raw query"},
        {"risk": "medium", "file": "src/B.java", "line": 5, "title": "Missing null check",
         "description": "may NPE"}
      ]}
      """;

  private static GitHubReviewClient.PullRequestComment comment(
      long id, Long inReplyTo, String path, String body, String author) {
    // Default replies to a write-capable association so existing maintainer-reply fixtures still
    // clear holds after the author-association gate (F1). Non-privileged replies use the overload.
    return comment(id, inReplyTo, path, body, author, "MEMBER");
  }

  private static GitHubReviewClient.PullRequestComment comment(
      long id, Long inReplyTo, String path, String body, String author, String association) {
    return new GitHubReviewClient.PullRequestComment(
        id, inReplyTo, path, body, new GitHubReviewClient.ReviewResponse.User(author), association);
  }

  @Test
  void contextShouldIncludeThreadRepliesUnderMatchingFinding() {
    var comments =
        List.of(
            comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT),
            comment(101L, 100L, "src/A.java", "Fixed in abc123", "maintainer"),
            comment(102L, 100L, "src/A.java", "Confirmed", BOT));

    var context = analyzer.buildPreviousFindingsContext(PREVIOUS_JSON, List.of(), comments, BOT_ID);

    assertTrue(context.contains("Thread replies:"));
    assertTrue(context.contains("- @maintainer: Fixed in abc123"));
    assertTrue(context.contains("- @thrillhousebot: Confirmed"));
  }

  @Test
  void contextShouldOmitRepliesSectionWhenThreadHasNone() {
    var comments = List.of(comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT));

    var context = analyzer.buildPreviousFindingsContext(PREVIOUS_JSON, List.of(), comments, BOT_ID);

    assertFalse(context.contains("Thread replies:"));
  }

  @Test
  void contextShouldIgnoreCommentsFromOtherAuthorsPathsOrTitles() {
    var comments =
        List.of(
            comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", "someone-else"),
            comment(200L, null, "src/Other.java", "**CRITICAL — SQL injection**", BOT),
            comment(300L, null, "src/A.java", "unrelated comment", BOT),
            comment(301L, 300L, "src/A.java", "reply to unrelated", "maintainer"));

    var context = analyzer.buildPreviousFindingsContext(PREVIOUS_JSON, List.of(), comments, BOT_ID);

    assertFalse(context.contains("Thread replies:"));
  }

  @Test
  void contextShouldHandleNullCommentFieldsAndFindingsWithoutTitleOrFile() {
    var json =
        """
        {"findings": [
          {"risk": "low", "file": null, "line": 1, "title": "X", "description": ""},
          {"risk": "low", "file": "f", "line": 1, "title": null, "description": ""},
          {"risk": "critical", "file": "src/A.java", "line": 10, "title": "SQL injection",
           "description": ""}
        ]}
        """;
    var comments =
        List.of(
            comment(50L, null, "src/A.java", null, BOT),
            new GitHubReviewClient.PullRequestComment(
                60L, null, "src/A.java", "**CRITICAL — SQL injection**", null),
            comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT),
            new GitHubReviewClient.PullRequestComment(
                101L, 100L, "src/A.java", "anonymous reply", null));

    var context = analyzer.buildPreviousFindingsContext(json, List.of(), comments, BOT_ID);

    assertTrue(context.contains("- @unknown: anonymous reply"));
  }

  @Test
  void matchFindingThreadsShouldMapFindingIdsToRootComments() {
    var comments =
        List.of(
            comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT),
            comment(101L, 100L, "src/A.java", "a reply", "maintainer"),
            comment(200L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT));

    var threads = analyzer.matchFindingThreads(PREVIOUS_JSON, comments, BOT_ID);

    assertEquals(100L, threads.get(1));
    assertEquals(200L, threads.get(2));
  }

  @Test
  void matchFindingThreadsShouldPreferHiddenMarkersOverTitleMatching() {
    var json =
        """
        {"findings": [
          {"risk": "medium", "file": "src/A.java", "line": 10, "title": "Missing null check",
           "description": ""},
          {"risk": "medium", "file": "src/A.java", "line": 50, "title": "Missing null check",
           "description": ""}
        ]}
        """;
    var comments =
        List.of(
            comment(
                100L,
                null,
                "src/A.java",
                "**MEDIUM — Missing null check**\n<!-- thrillhousebot:finding=1 -->",
                BOT),
            comment(
                200L,
                null,
                "src/A.java",
                "**MEDIUM — Missing null check**\n<!-- thrillhousebot:finding=2 -->",
                BOT));

    var threads = analyzer.matchFindingThreads(json, comments, BOT_ID);

    assertEquals(100L, threads.get(1));
    assertEquals(200L, threads.get(2));
  }

  @Test
  void matchFindingThreadsShouldNotBindSummaryOnlyFindingToAnEarlierRoundsSameIndexThread() {
    // The effective previous round's finding #1 was summary-only (no inline thread of its own). An
    // earlier round posted a DIFFERENT finding under the recurring finding=1 marker on the same
    // file. Finding #1 must not bind to that unrelated same-index thread (F2).
    var json =
        """
        {"findings": [
          {"risk": "medium", "file": "src/B.java", "line": 5, "title": "Missing null check",
           "description": "may NPE"}
        ]}
        """;
    var comments =
        List.of(
            comment(
                300L,
                null,
                "src/B.java",
                "**HIGH — Unrelated earlier bug**\n<!-- thrillhousebot:finding=1 -->",
                BOT));

    var threads = analyzer.matchFindingThreads(json, comments, BOT_ID);

    assertFalse(
        threads.containsKey(1),
        "a summary-only finding must not bind to a different finding's same-index thread");
  }

  @Test
  void matchFindingThreadsShouldStillBindViaTitleWhenNoMarkerExistsOnTheFile() {
    // No finding=N marker anywhere on the file: the pre-marker title fallback still binds (F2 must
    // not regress the marker-free path).
    var comments = List.of(comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT));

    var threads = analyzer.matchFindingThreads(PREVIOUS_JSON, comments, BOT_ID);

    assertEquals(100L, threads.get(1));
  }

  @Test
  void dropRepliedDuplicatesShouldTolerateNullPriorList() {
    var comments = List.of(comment(100L, null, "src/B.java", "**MEDIUM — X**", BOT));
    var withFinding =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 5, "X", "d", null, null)),
            List.of(),
            null);

    assertSame(withFinding, analyzer.dropRepliedDuplicates(withFinding, null, comments, BOT_ID));
  }

  @Test
  void markerMatchShouldAcceptFindingsWithoutFile() {
    var json =
        """
        {"findings": [
          {"risk": "low", "file": null, "line": 1, "title": "No file", "description": ""}
        ]}
        """;
    var comments =
        List.of(
            comment(
                100L,
                null,
                "src/A.java",
                "**LOW — No file**\n<!-- thrillhousebot:finding=1 -->",
                BOT));

    var threads = analyzer.matchFindingThreads(json, comments, BOT_ID);

    assertEquals(100L, threads.get(1));
  }

  @Test
  void dropRepliedDuplicatesShouldMatchPathVariants() {
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            comment(101L, 100L, "src/B.java", "Declining.", "maintainer"));
    var reRaised =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "high", "app/src/B.java", 5, "Missing null check", "d", null, null)),
            List.of(),
            null);

    var result = analyzer.dropRepliedDuplicates(reRaised, List.of(PREVIOUS_JSON), comments, BOT_ID);

    assertTrue(result.findings().isEmpty());
  }

  @Test
  void dropRepliedDuplicatesShouldCatchParaphrasedReRaisesAtDriftedLines() {
    var priorJson =
        """
        {"findings": [
          {"risk": "medium", "file": "src/Dedup.java", "line": 144,
           "title": "Deduplicator may throw NullPointerException for unrecognized risk strings",
           "description": "The merge method maps each finding risk string via RiskLevel.fromString which may return null for an invalid or misspelled LLM output. The stream then calls sorted on a list that can contain null, causing a NullPointerException"}
        ]}
        """;
    var comments =
        List.of(
            comment(
                100L,
                null,
                "src/Dedup.java",
                "**MEDIUM — Deduplicator may throw NullPointerException for unrecognized risk"
                    + " strings**",
                BOT),
            comment(101L, 100L, "src/Dedup.java", "fromString never returns null.", "maintainer"));
    var paraphrased =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium",
                    "high",
                    "src/Dedup.java",
                    115,
                    "Potential NullPointerException if risk string is not a recognized enum value",
                    "The merge method calls RiskLevel.fromString for each finding, then sorts the"
                        + " resulting list. If risk contains an unexpected value, fromString may"
                        + " return null. Sorting a list containing null throws"
                        + " NullPointerException",
                    null,
                    null)),
            List.of(),
            null);

    var result = analyzer.dropRepliedDuplicates(paraphrased, List.of(priorJson), comments, BOT_ID);

    assertTrue(result.findings().isEmpty());
  }

  @Test
  void dropRepliedDuplicatesShouldKeepDistinctClaimsInTheSameFile() {
    var priorJson =
        """
        {"findings": [
          {"risk": "medium", "file": "src/Dedup.java", "line": 144,
           "title": "Deduplicator may throw NullPointerException for unrecognized risk strings",
           "description": "The merge method maps each finding risk string via RiskLevel.fromString which may return null, causing a NullPointerException when sorting"}
        ]}
        """;
    var comments =
        List.of(
            comment(
                100L,
                null,
                "src/Dedup.java",
                "**MEDIUM — Deduplicator may throw NullPointerException for unrecognized risk"
                    + " strings**",
                BOT),
            comment(101L, 100L, "src/Dedup.java", "fromString never returns null.", "maintainer"));
    var distinct =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium",
                    "high",
                    "src/Dedup.java",
                    60,
                    "Cluster anchor comparison misses chained duplicates",
                    "Deduplication only compares each finding to the first cluster member, so"
                        + " chain duplicates beyond the tolerance from the anchor split into"
                        + " several clusters and get posted more than once",
                    null,
                    null)),
            List.of(),
            null);

    assertSame(
        distinct, analyzer.dropRepliedDuplicates(distinct, List.of(priorJson), comments, BOT_ID));
  }

  @Test
  void dropRepliedDuplicatesShouldKeepDistinctNearbyFindingOfTheSameSeverity() {
    var priorJson =
        """
        {"findings": [
          {"risk": "medium", "file": "src/B.java", "line": 40,
           "title": "SQL injection in query builder",
           "description": "User input is concatenated directly into the SQL string"}
        ]}
        """;
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — SQL injection in query builder**", BOT),
            comment(101L, 100L, "src/B.java", "Declining for now.", "maintainer"));
    var distinct =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium",
                    "high",
                    "src/B.java",
                    42,
                    "Missing null check on the response",
                    "The response object may be null when the upstream call times out",
                    null,
                    null)),
            List.of(),
            null);

    assertSame(
        distinct, analyzer.dropRepliedDuplicates(distinct, List.of(priorJson), comments, BOT_ID));
  }

  @Test
  void markerOnADifferentFileDoesNotBind() {
    var json =
        """
        {"findings": [
          {"risk": "low", "file": "src/A.java", "line": 1, "title": "Some finding",
           "description": ""}
        ]}
        """;
    var comments =
        List.of(
            comment(
                100L,
                null,
                "src/Other.java",
                "**LOW — Unrelated thing**\n<!-- thrillhousebot:finding=1 -->",
                BOT));

    var threads = analyzer.matchFindingThreads(json, comments, BOT_ID);

    assertTrue(threads.isEmpty());
  }

  @Test
  void titleFallbackShouldBindTheNewestSameTitleThread() {
    var json =
        """
        {"findings": [
          {"risk": "critical", "file": "src/A.java", "line": 10, "title": "SQL injection",
           "description": ""}
        ]}
        """;
    var comments =
        List.of(
            comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT),
            comment(200L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT));

    var threads = analyzer.matchFindingThreads(json, comments, BOT_ID);

    assertEquals(200L, threads.get(1));
  }

  @Test
  void dropRepliedDuplicatesShouldFindTheReplyOnAnyRoundsThread() {
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            comment(101L, 100L, "src/B.java", "Declining, by design.", "maintainer"),
            comment(200L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT));
    var reRaised =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 5, "Missing null check", "d", null, null)),
            List.of(),
            null);

    var result = analyzer.dropRepliedDuplicates(reRaised, List.of(PREVIOUS_JSON), comments, BOT_ID);

    assertTrue(result.findings().isEmpty());
  }

  @Test
  void matchFindingThreadsShouldSkipFindingsWithoutMatchingComment() {
    var comments = List.of(comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT));

    var threads = analyzer.matchFindingThreads(PREVIOUS_JSON, comments, BOT_ID);

    assertEquals(1, threads.size());
    assertFalse(threads.containsKey(2));
  }

  @Test
  void dropRepliedDuplicatesShouldDropReRaisedFindingWithHumanReply() {
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            comment(101L, 100L, "src/B.java", "Declining — guarded upstream.", "maintainer"));
    var reRaised =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 7, "Missing null check", "d", null, null),
                new ReviewResponse.Finding(
                    "high", "high", "src/C.java", 30, "Genuinely new bug", "d", null, null)),
            List.of(),
            new ReviewResponse.Summary(2, 0, 1, 1, 0, "assessment", "purpose", List.of()));

    var result = analyzer.dropRepliedDuplicates(reRaised, List.of(PREVIOUS_JSON), comments, BOT_ID);

    assertEquals(1, result.findings().size());
    assertEquals("Genuinely new bug", result.findings().get(0).title());
    assertEquals(1, result.summary().totalFindings());
    assertEquals(1, result.summary().high());
    assertEquals(0, result.summary().medium());
    assertEquals("assessment", result.summary().overallAssessment());
  }

  @Test
  void dropRepliedDuplicatesShouldKeepFindingWithoutHumanEngagement() {
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            comment(101L, 100L, "src/B.java", "bot self-reply", BOT));
    var reRaised =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 5, "Missing null check", "d", null, null)),
            List.of(),
            null);

    assertSame(
        reRaised,
        analyzer.dropRepliedDuplicates(reRaised, List.of(PREVIOUS_JSON), comments, BOT_ID));
  }

  @Test
  void dropRepliedDuplicatesShouldKeepFindingRepliedToByANonWriteAuthor() {
    // A fork-PR author (author_association CONTRIBUTOR) replies on the finding thread. Their reply
    // is not a maintainer decision, so it must not delete the re-raised finding (F1).
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            comment(101L, 100L, "src/B.java", "Not a real bug.", "fork-author", "CONTRIBUTOR"));
    var reRaised =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 5, "Missing null check", "d", null, null)),
            List.of(),
            null);

    assertSame(
        reRaised,
        analyzer.dropRepliedDuplicates(reRaised, List.of(PREVIOUS_JSON), comments, BOT_ID));
  }

  @Test
  void dropRepliedDuplicatesShouldDropEscalatedReRaiseWithSimilarTitle() {
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            comment(101L, 100L, "src/B.java", "Intentional; declining.", "maintainer"));
    var escalated =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "high",
                    "high",
                    "src/B.java",
                    5,
                    "Missing null check on input",
                    "d",
                    null,
                    null)),
            List.of(),
            null);

    var result =
        analyzer.dropRepliedDuplicates(escalated, List.of(PREVIOUS_JSON), comments, BOT_ID);

    assertTrue(result.findings().isEmpty());
  }

  @Test
  void dropRepliedDuplicatesShouldMatchFindingsFromOlderRounds() {
    var latestRoundJson =
        """
        {"findings": []}
        """;
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            comment(101L, 100L, "src/B.java", "Declining, by design.", "maintainer"));
    var reRaised =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 5, "Missing null check", "d", null, null)),
            List.of(),
            null);

    var result =
        analyzer.dropRepliedDuplicates(
            reRaised, List.of(latestRoundJson, PREVIOUS_JSON), comments, BOT_ID);

    assertTrue(result.findings().isEmpty());
  }

  @Test
  void contextShouldListAnsweredFindingsFromOlderRounds() {
    var olderJson =
        """
        {"findings": [
          {"risk": "high", "file": "src/C.java", "line": 7, "title": "Scan does not gate",
           "description": "older round"},
          {"risk": "medium", "file": "src/G.java", "line": 12, "title": "Second answered item",
           "description": "also replied to"},
          {"risk": "low", "file": "src/D.java", "line": 9, "title": "Unanswered nit",
           "description": "no reply on this one"}
        ]}
        """;
    var comments =
        List.of(
            comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT),
            comment(200L, null, "src/C.java", "**HIGH — Scan does not gate**", BOT),
            comment(201L, 200L, "src/C.java", "Report-only by design.", "maintainer"),
            comment(400L, null, "src/G.java", "**MEDIUM — Second answered item**", BOT),
            comment(401L, 400L, "src/G.java", "Also intentional.", "maintainer"),
            comment(300L, null, "src/D.java", "**LOW — Unanswered nit**", BOT));

    var context =
        analyzer.buildPreviousFindingsContext(
            PREVIOUS_JSON, List.of(), comments, List.of(olderJson, olderJson), BOT_ID);

    assertTrue(context.contains("Answered in earlier rounds"));
    assertTrue(context.contains("src/C.java:7 — Scan does not gate"));
    assertTrue(context.contains("- @maintainer: Report-only by design."));
    assertFalse(context.contains("Unanswered nit"));
    assertEquals(context.indexOf("Scan does not gate"), context.lastIndexOf("Scan does not gate"));
    assertTrue(context.contains("SQL injection"));
  }

  @Test
  void contextWithoutOlderRoundsHasNoAnsweredSection() {
    var context =
        analyzer.buildPreviousFindingsContext(
            PREVIOUS_JSON, List.of(), List.of(), List.of(), BOT_ID);

    assertFalse(context.contains("Answered in earlier rounds"));

    var nullOlder =
        analyzer.buildPreviousFindingsContext(PREVIOUS_JSON, List.of(), List.of(), null, BOT_ID);
    assertFalse(nullOlder.contains("Answered in earlier rounds"));
  }

  @Test
  void answeredSectionAppendsToFallbackContextWhenNoStructuredFindings() {
    var olderJson =
        """
        {"findings": [
          {"risk": "high", "file": "src/C.java", "line": 7, "title": "Scan does not gate",
           "description": ""}
        ]}
        """;
    var comments =
        List.of(
            comment(200L, null, "src/C.java", "**HIGH — Scan does not gate**", BOT),
            comment(201L, 200L, "src/C.java", "Report-only by design.", "maintainer"));

    var context =
        analyzer.buildPreviousFindingsContext(
            null, List.of(), comments, List.of(olderJson), BOT_ID);

    assertTrue(context.contains("Answered in earlier rounds"));
    assertTrue(context.contains("src/C.java:7 — Scan does not gate"));
  }

  @Test
  void answeredSectionSkipsUnmatchableFindings() {
    var olderJson =
        """
        {"findings": [
          {"risk": "low", "file": null, "line": 1, "title": "No file", "description": ""},
          {"risk": "low", "file": "src/E.java", "line": 2, "title": null, "description": ""},
          {"risk": "low", "file": "src/F.java", "line": 3, "title": "No comment anywhere",
           "description": ""}
        ]}
        """;
    var comments =
        List.of(comment(900L, 800L, "src/F.java", "stray reply to nothing", "maintainer"));

    var context =
        analyzer.buildPreviousFindingsContext(
            PREVIOUS_JSON, List.of(), comments, List.of(olderJson), BOT_ID);

    assertFalse(context.contains("Answered in earlier rounds"));
  }

  @Test
  void dropRepliedDuplicatesShouldKeepFindingsWithoutFile() {
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            comment(101L, 100L, "src/B.java", "Declining.", "maintainer"));
    var fileless =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "high", null, 5, "Missing null check", "d", null, null)),
            List.of(),
            null);

    assertSame(
        fileless,
        analyzer.dropRepliedDuplicates(fileless, List.of(PREVIOUS_JSON), comments, BOT_ID));
  }

  @Test
  void dropRepliedDuplicatesShouldRequireSameLocationAndTitle() {
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            comment(101L, 100L, "src/B.java", "Declining.", "maintainer"));
    var different =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "critical", "high", "src/B.java", 5, "Injection here", "d", null, null),
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 50, "Missing null check", "d", null, null),
                new ReviewResponse.Finding(
                    "medium", "high", "src/Z.java", 5, "Missing null check", "d", null, null)),
            List.of(),
            null);

    assertSame(
        different,
        analyzer.dropRepliedDuplicates(different, List.of(PREVIOUS_JSON), comments, BOT_ID));
  }

  @Test
  void dropRepliedDuplicatesShouldKeepEdgeShapes() {
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            new GitHubReviewClient.PullRequestComment(
                102L, 100L, "src/B.java", "anonymous reply", null));
    var response =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 5, "Missing null check", "d", null, null),
                new ReviewResponse.Finding(
                    "critical", "high", "src/A.java", 11, "SQL injection again", "d", null, null),
                new ReviewResponse.Finding("low", "high", null, 1, "No file", "d", null, null)),
            List.of(),
            null);

    assertSame(
        response,
        analyzer.dropRepliedDuplicates(response, List.of(PREVIOUS_JSON), comments, BOT_ID));
  }

  @Test
  void dropRepliedDuplicatesShouldSkipTriviallyEmptyInputs() {
    var finding = new ReviewResponse.Finding("low", "high", "f", 1, "t", "d", null, null);
    var withFinding = new ReviewResponse(List.of(finding), List.of(), null);
    var noFindings = new ReviewResponse(List.of(), List.of(), null);
    var comments = List.of(comment(1L, null, "f", "x", BOT));

    assertSame(
        noFindings,
        analyzer.dropRepliedDuplicates(noFindings, List.of(PREVIOUS_JSON), comments, BOT_ID));
    assertSame(
        withFinding,
        analyzer.dropRepliedDuplicates(withFinding, List.of(PREVIOUS_JSON), List.of(), BOT_ID));
    assertSame(
        withFinding, analyzer.dropRepliedDuplicates(withFinding, List.of(), comments, BOT_ID));
  }

  @Test
  void unresolvedFindingsShouldReturnOnlyUnresolvedOnes() {
    var statuses =
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"),
            new ReviewResponse.PreviousFindingStatus(2, "UNRESOLVED", "still there"));

    var unresolved = analyzer.unresolvedFindings(PREVIOUS_JSON, statuses);

    assertEquals(1, unresolved.size());
    assertEquals("Missing null check", unresolved.get(0).title());
    assertEquals(RiskLevel.MEDIUM, unresolved.get(0).risk());
  }

  @Test
  void unresolvedFindingsShouldIgnoreOutOfRangeIds() {
    var statuses =
        List.of(
            new ReviewResponse.PreviousFindingStatus(0, "unresolved", "?"),
            new ReviewResponse.PreviousFindingStatus(99, "unresolved", "?"));

    assertTrue(analyzer.unresolvedFindings(PREVIOUS_JSON, statuses).isEmpty());
  }

  @Test
  void previousFindingFilesByIdMapsTheOneBasedStatusIdSpace() {
    var filesById = analyzer.previousFindingFilesById(PREVIOUS_JSON);

    assertEquals("src/A.java", filesById.get(1));
    assertEquals("src/B.java", filesById.get(2));
    assertEquals(2, filesById.size());
  }

  @Test
  void previousFindingFilesByIdIsEmptyForMissingOrBadInput() {
    assertTrue(analyzer.previousFindingFilesById((String) null).isEmpty());
    assertTrue(analyzer.previousFindingFilesById("not json").isEmpty());
    assertTrue(analyzer.previousFindingFilesById((List<ReviewResponse.Finding>) null).isEmpty());
  }

  @Test
  void unresolvedFindingsShouldReturnEmptyForMissingInputs() {
    var unresolvedStatus = List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "x"));

    assertTrue(analyzer.unresolvedFindings(PREVIOUS_JSON, null).isEmpty());
    assertTrue(analyzer.unresolvedFindings(PREVIOUS_JSON, List.of()).isEmpty());
    assertTrue(analyzer.unresolvedFindings((String) null, unresolvedStatus).isEmpty());
    assertTrue(analyzer.unresolvedFindings("not json", unresolvedStatus).isEmpty());
    assertTrue(
        analyzer
            .unresolvedFindings((List<ReviewResponse.Finding>) null, unresolvedStatus)
            .isEmpty());
    assertTrue(analyzer.unresolvedFindings(List.of(), unresolvedStatus).isEmpty());
  }

  @Test
  void parsePreviousResponsesHandlesNullEmptyAndValidJson() {
    assertTrue(analyzer.parsePreviousResponses(null).isEmpty());
    assertTrue(analyzer.parsePreviousResponses(List.of()).isEmpty());
    var parsed = analyzer.parsePreviousResponses(List.of(PREVIOUS_JSON));
    assertEquals(1, parsed.size());
    assertEquals(2, parsed.get(0).findings().size());
  }

  /**
   * #471 — a persisted body that is the JSON literal {@code null} is syntactically valid, so
   * Jackson returns Java null without throwing and the response never reached the parse-failure
   * fallback. The null element then went straight into {@code List.copyOf}, which rejects it,
   * failing every later review of that PR on the async thread. Unusable prior state must degrade to
   * the empty response so the review proceeds.
   */
  @Test
  void persistedResponseOfTheJsonLiteralNullDegradesToTheEmptyResponse() {
    var parsed = analyzer.parsePreviousResponses(List.of("null", PREVIOUS_JSON));

    assertEquals(2, parsed.size());
    assertNotNull(parsed.get(0), "a literal-null body must not leave a null in the parsed list");
    assertTrue(parsed.get(0).findings().isEmpty());
    assertTrue(parsed.get(0).previousFindingsStatus().isEmpty());
    assertFalse(
        FollowUpAnalyzer.isPersistedResponse(parsed.get(0)),
        "a literal-null body is the same absence as an unparseable one");
    assertEquals(2, parsed.get(1).findings().size(), "the readable rounds still parse");
    assertTrue(
        analyzer.previousFindingFilesById("null").isEmpty(),
        "the by-id lookup reads the same response and must not dereference null");
  }

  @Test
  void preParsedApisReuseFindingsWithoutReReadingJson() {
    var previous = analyzer.parsePreviousResponses(List.of(PREVIOUS_JSON)).get(0).findings();
    var unresolvedStatus = List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "x"));

    assertEquals(1, analyzer.unresolvedFindings(previous, unresolvedStatus).size());
    assertEquals("src/A.java", analyzer.previousFindingFilesById(previous).get(1));
    assertTrue(
        analyzer
            .matchFindingThreads((List<ReviewResponse.Finding>) null, List.of(), BOT_ID)
            .isEmpty());
    assertTrue(analyzer.matchFindingThreads(List.of(), List.of(), BOT_ID).isEmpty());

    var ctx =
        analyzer.buildPreviousFindingsContext(
            previous, true, List.of(), List.of(), List.of(), BOT_ID);
    assertTrue(ctx.contains("src/A.java"));
    assertTrue(ctx.contains("src/B.java"));

    // Null previous findings falls back to the prior-review body path (empty here).
    assertEquals(
        "",
        analyzer.buildPreviousFindingsContext(
            (List<ReviewResponse.Finding>) null, false, List.of(), List.of(), List.of(), BOT_ID));

    // Older rounds with no maintainer reply contribute nothing to the answered-earlier section.
    var older =
        analyzer.parsePreviousResponses(
            List.of(
                "{\"findings\":[{\"risk\":\"low\",\"file\":\"src/Old.java\",\"line\":1,"
                    + "\"title\":\"Old\",\"description\":\"d\",\"suggestion_old\":\"o\","
                    + "\"suggestion_new\":\"n\"}],\"previous_findings_status\":[],\"summary\":null}"));
    var withOlder =
        analyzer.buildPreviousFindingsContext(previous, true, List.of(), List.of(), older, BOT_ID);
    assertTrue(withOlder.contains("src/A.java"));
    assertFalse(withOlder.contains("Answered in earlier rounds"));
    assertTrue(
        analyzer
            .buildPreviousFindingsContext(previous, true, List.of(), List.of(), null, BOT_ID)
            .contains("src/A.java"));

    assertTrue(
        analyzer
            .unreportedUnresolvedStatusesFromParsed(
                null, List.of(), List.of(), new DiffLineResolver(Map.of()), BOT_ID)
            .isEmpty());
    assertTrue(
        analyzer
            .unreportedUnresolvedStatusesFromParsed(
                List.of(), List.of(), List.of(), new DiffLineResolver(Map.of()), BOT_ID)
            .isEmpty());
  }

  @Test
  void supersedeVanishedShouldRewriteUnresolvedWhoseFileLeftTheDiff() {
    // Only src/A.java is still in the diff; src/B.java (finding 2) vanished after a force-push.
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));
    var statuses =
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still"),
            new ReviewResponse.PreviousFindingStatus(2, "UNRESOLVED", "still"));

    var rewritten = analyzer.supersedeVanished(PREVIOUS_JSON, statuses, resolver);

    assertEquals("unresolved", rewritten.get(0).status());
    assertEquals("superseded", rewritten.get(1).status());
    assertEquals(2, rewritten.get(1).id());
    assertTrue(rewritten.get(1).note().contains("no longer in this revision's diff"));
  }

  @Test
  void supersedeVanishedShouldNotSupersedeASettledId() {
    // Finding #2's code vanished, but a newer round already closed it (settled). It must not be
    // rewritten to superseded, or hasSupersededPrevious would pin on and re-post the summary every
    // push (#470).
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));
    var statuses = List.of(new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still"));

    var rewritten =
        analyzer.supersedeVanished(PREVIOUS_JSON, statuses, resolver, Map.of(), Set.of(2));

    assertEquals(
        "unresolved",
        rewritten.get(0).status(),
        "a settled id must not be re-superseded even when its code vanished");
  }

  @Test
  void supersedeVanishedShouldJudgePresenceByTheAnchorNotTheFile() {
    var anchoredJson =
        """
        {"findings": [
          {"risk": "medium", "file": "src/A.java", "line": 10, "title": "Bad regex",
           "description": "d", "suggestion_old": "quote(label)"},
          {"risk": "medium", "file": "src/A.java", "line": 12, "title": "Kept",
           "description": "d", "suggestion_old": "keepMe()"}
        ]}
        """;
    // The file is still in the diff, but only finding 2's anchored hunk survived.
    var resolver = new DiffLineResolver(Map.of("src/A.java", "@@ -10,1 +10,1 @@\n-old\n+keepMe()"));
    var statuses =
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still"),
            new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still"));

    var rewritten = analyzer.supersedeVanished(anchoredJson, statuses, resolver);

    assertEquals("superseded", rewritten.get(0).status());
    assertEquals("unresolved", rewritten.get(1).status());
  }

  @Test
  void supersedeVanishedShouldLeaveNonUnresolvedAndUnplaceableStatusesAlone() {
    var nullFileJson =
        """
        {"findings": [
          {"risk": "medium", "file": null, "line": 3, "title": "No file", "description": "d"},
          {"risk": "medium", "file": "src/Gone.java", "line": 5, "title": "Gone",
           "description": "d"}
        ]}
        """;
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));
    var statuses =
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "unresolved", "cannot place"),
            new ReviewResponse.PreviousFindingStatus(2, "resolved", "fixed"),
            new ReviewResponse.PreviousFindingStatus(0, "unresolved", "below range"),
            new ReviewResponse.PreviousFindingStatus(99, "unresolved", "out of range"));

    var rewritten = analyzer.supersedeVanished(nullFileJson, statuses, resolver);

    assertEquals(statuses, rewritten);
  }

  @Test
  void supersedeVanishedShouldPassThroughOnMissingInputs() {
    var resolver = new DiffLineResolver(Map.of());
    var statuses = List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "x"));

    assertTrue(analyzer.supersedeVanished(PREVIOUS_JSON, null, resolver).isEmpty());
    assertTrue(analyzer.supersedeVanished(PREVIOUS_JSON, List.of(), resolver).isEmpty());
    assertEquals(statuses, analyzer.supersedeVanished(PREVIOUS_JSON, statuses, null));
    assertEquals(statuses, analyzer.supersedeVanished(null, statuses, resolver));
    assertEquals(statuses, analyzer.supersedeVanished("not json", statuses, resolver));
  }

  @Test
  void addUnreportedVanishedShouldPassThroughGuardInputsAndAcceptNullStatuses() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", "@@ -1,1 +1,1 @@\n-old\n+flagged()"));
    var reported = List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"));
    var previous =
        List.of(
            new ReviewResponse.Finding(
                "medium", "src/A.java", 1, "Finding", "description", "flagged()", null));

    assertEquals(reported, analyzer.addUnreportedVanished(null, reported, resolver, Map.of()));
    assertTrue(analyzer.addUnreportedVanished(null, null, resolver, Map.of()).isEmpty());
    assertEquals(reported, analyzer.addUnreportedVanished(List.of(), reported, resolver, Map.of()));
    assertEquals(reported, analyzer.addUnreportedVanished(previous, reported, null, Map.of()));
    assertTrue(analyzer.addUnreportedVanished(previous, null, resolver, Map.of()).isEmpty());
  }

  @Test
  void addUnreportedVanishedShouldHandleReportedUnplaceablePresentVanishedAndRenamedFindings() {
    var previous =
        List.of(
            new ReviewResponse.Finding(
                "medium", "src/Reported.java", 1, "Reported", "d", "reported()", null),
            new ReviewResponse.Finding("medium", null, 2, "No file", "d", "unplaceable()", null),
            new ReviewResponse.Finding(
                "medium", "src/Still.java", 3, "Still", "d", "stillFlagged()", null),
            new ReviewResponse.Finding(
                "medium", "src/Gone.java", 4, "Gone", "d", "goneFlagged()", null),
            new ReviewResponse.Finding(
                "medium", "old/Moved.java", 5, "Moved", "d", "movedFlagged()", null),
            new ReviewResponse.Finding(
                "medium", "old/Gone.java", 6, "Moved then gone", "d", "goneAgain()", null),
            new ReviewResponse.Finding(
                "medium", "old/Pure.java", 7, "Pure rename", "d", "unchanged()", null));
    var reported = List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"));
    var resolver =
        new DiffLineResolver(
            Map.of(
                "src/Still.java", "@@ -3,1 +3,1 @@\n-old\n+stillFlagged()",
                "new/Moved.java", "@@ -5,1 +5,1 @@\n-old\n+movedFlagged()"));
    var renameTargets =
        Map.of(
            "old/Moved.java", "new/Moved.java",
            "old/Gone.java", "new/Gone.java",
            "old/Pure.java", "");

    var statuses = analyzer.addUnreportedVanished(previous, reported, resolver, renameTargets);

    assertEquals(List.of(1, 4, 6, 7), statuses.stream().map(s -> s.id()).toList());
    assertEquals("superseded", statuses.get(1).status());
    assertEquals("superseded", statuses.get(2).status());
    assertEquals("unresolved", statuses.get(3).status());
    assertTrue(statuses.get(3).note().contains("renamed without content changes"));
  }

  @Test
  void addUnreportedVanishedShouldNotResupersedeAFindingANewerRoundAlreadyClosed() {
    // f2's code vanished, but a newer round already closed it (settled). Re-superseding it would
    // pin hasSupersededPrevious on and re-post the summary every push while suppressing the delta
    // (#470).
    var previous =
        List.of(
            new ReviewResponse.Finding(
                "medium", "src/A.java", 1, "Open", "d", "openAnchor()", null),
            new ReviewResponse.Finding(
                "medium", "src/B.java", 2, "Closed", "d", "goneAnchor()", null));
    var resolver =
        new DiffLineResolver(Map.of("src/A.java", "@@ -1,1 +1,1 @@\n-old\n+openAnchor()"));

    // Without the settled set, f2 (id 2) is superseded — the phantom the fix removes.
    var naive = analyzer.addUnreportedVanished(previous, List.of(), resolver, Map.of());
    assertEquals(List.of(2), naive.stream().map(s -> s.id()).toList());
    assertEquals("superseded", naive.get(0).status());

    var settled =
        analyzer.addUnreportedVanished(previous, List.of(), resolver, Map.of(), Set.of(2));
    assertTrue(
        settled.stream().noneMatch(s -> s.id() == 2),
        "a finding a newer round already closed must not be re-superseded");
  }

  @Test
  void settledPreviousIdsShouldCollectClosuresFromRoundsNewerThanTheEffectiveOne() {
    // newest-first: two zero-finding rounds close #1 (resolved) and #2 (justified); the effective
    // previous round raised #1..#3. #3 stays open.
    var roundC =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed")),
            null);
    var roundB =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(2, "justified", "declined")),
            null);
    var roundA =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding("medium", "high", "src/A.java", 1, "1", "d", null, null),
                new ReviewResponse.Finding("medium", "high", "src/B.java", 2, "2", "d", null, null),
                new ReviewResponse.Finding(
                    "medium", "high", "src/C.java", 3, "3", "d", null, null)),
            List.of(),
            null);

    assertEquals(
        Set.of(1, 2), FollowUpAnalyzer.settledPreviousIds(List.of(roundC, roundB, roundA)));
    assertEquals(Set.of(), FollowUpAnalyzer.settledPreviousIds(List.of(roundA)));

    // A null newer round (missing/unparseable persisted response) is skipped, not dereferenced,
    // while a non-null newer round beside it still contributes its closure.
    var withNull = new ArrayList<ReviewResponse>();
    withNull.add(null);
    withNull.add(roundC);
    withNull.add(roundA);
    assertEquals(Set.of(1), FollowUpAnalyzer.settledPreviousIds(withNull));
  }

  @Test
  void contextShouldOmitSettledFindingsButKeepTheirIdSlots() {
    // Finding #2 was closed by a newer round; it must not be re-shown to the model, but #1 and #3
    // must keep their original id numbers (no renumber) (#470).
    var previous =
        List.of(
            new ReviewResponse.Finding(
                "critical", "high", "src/A.java", 10, "First", "d", null, null),
            new ReviewResponse.Finding(
                "medium", "high", "src/B.java", 5, "Second", "d", null, null),
            new ReviewResponse.Finding("high", "high", "src/C.java", 7, "Third", "d", null, null));

    var context =
        analyzer.buildPreviousFindingsContext(
            previous, true, List.of(), List.of(), List.of(), BOT_ID, Set.of(2));

    assertTrue(context.contains("1. [CRITICAL] src/A.java:10 — First"), context);
    assertFalse(context.contains("Second"), context);
    assertTrue(context.contains("3. [HIGH] src/C.java:7 — Third"), context);
  }

  @Test
  void supersedeVanishedShouldKeepUnresolvedAcrossPureRename() {
    var statuses = List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still"));

    var rewritten =
        analyzer.supersedeVanished(
            PREVIOUS_JSON, statuses, new DiffLineResolver(Map.of()), Map.of("src/A.java", ""));

    assertEquals(statuses, rewritten);
  }

  @Test
  void unreportedPureRenameShouldRemainUnresolved() {
    var previous =
        List.of(
            new ReviewResponse.Finding(
                "medium", "old/A.java", 10, "Finding", "description", "flagged()", null));

    var statuses =
        analyzer.addUnreportedVanished(
            previous, List.of(), new DiffLineResolver(Map.of()), Map.of("old/A.java", ""));

    assertEquals(1, statuses.size());
    assertEquals("unresolved", statuses.get(0).status());
    assertTrue(statuses.get(0).note().contains("renamed without content changes"));
  }

  @Test
  void toStatusesShouldKeepTheSyntheticSupersededStatus() {
    var statuses =
        analyzer.toStatuses(
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "superseded", "code gone"),
                new ReviewResponse.PreviousFindingStatus(2, "wontfix", "junk")));

    assertEquals(1, statuses.size());
    assertEquals("superseded", statuses.get(0).status());
  }

  /** One-line patch whose only right-side line is {@code line}, for backstop presence tests. */
  private static String patch(int line) {
    return "@@ -" + line + ",1 +" + line + ",1 @@\n-old\n+new";
  }

  private static List<Integer> heldIds(List<ReviewResult.PreviousFindingStatus> held) {
    return held.stream().map(ReviewResult.PreviousFindingStatus::id).toList();
  }

  /** A round persisting a single finding at {@code file}:{@code line} titled {@code title}. */
  private static String roundJson(String file, int line, String title) {
    return "{\"findings\": [{\"risk\": \"medium\", \"file\": \""
        + file
        + "\", \"line\": "
        + line
        + ", \"title\": \""
        + title
        + "\", \"description\": \"d\"}]}";
  }

  /** A round with one finding plus a previous_findings_status verdict on the prior round's id 1. */
  private static String roundJson(String file, int line, String title, String priorStatus) {
    return "{\"findings\": [{\"risk\": \"medium\", \"file\": \""
        + file
        + "\", \"line\": "
        + line
        + ", \"title\": \""
        + title
        + "\", \"description\": \"d\"}], \"previous_findings_status\": [{\"id\": 1, \"status\": \""
        + priorStatus
        + "\", \"note\": \"n\"}]}";
  }

  @Test
  void unreportedUnresolvedShouldHoldSilentlyDroppedFindingsStillInDiff() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(PREVIOUS_JSON), List.of(), List.of(), resolver, BOT_ID);

    assertEquals(List.of(1, 2), heldIds(held));
    assertTrue(held.stream().allMatch(s -> "unresolved".equals(s.status())));
  }

  @Test
  void unreportedUnresolvedShouldSkipAnyFindingTheModelReported() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    assertTrue(
        analyzer
            .unreportedUnresolvedStatuses(
                List.of(PREVIOUS_JSON),
                List.of(
                    new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"),
                    new ReviewResponse.PreviousFindingStatus(2, "justified", "intentional")),
                List.of(),
                resolver,
                BOT_ID)
            .isEmpty());

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(PREVIOUS_JSON),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still")),
            List.of(),
            resolver,
            BOT_ID);
    assertEquals(List.of(2), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHoldFindingsWithUnrecognizedStatus() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    for (String junk : new String[] {"wontfix", "open", "RESOLVE", "", null}) {
      var held =
          analyzer.unreportedUnresolvedStatuses(
              List.of(PREVIOUS_JSON),
              List.of(
                  new ReviewResponse.PreviousFindingStatus(1, junk, "?"),
                  new ReviewResponse.PreviousFindingStatus(2, "resolved", "fixed")),
              List.of(),
              resolver,
              BOT_ID);
      assertEquals(
          List.of(1), heldIds(held), "status \"" + junk + "\" must not suppress the backstop");
    }
  }

  @Test
  void unreportedUnresolvedShouldRecognizeStatusesCaseInsensitively() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    assertTrue(
        analyzer
            .unreportedUnresolvedStatuses(
                List.of(PREVIOUS_JSON),
                List.of(
                    new ReviewResponse.PreviousFindingStatus(1, "RESOLVED", "fixed"),
                    new ReviewResponse.PreviousFindingStatus(2, "Justified", "intentional")),
                List.of(),
                resolver,
                BOT_ID)
            .isEmpty());
  }

  @Test
  void unreportedUnresolvedShouldExcludeFindingsWithMaintainerReply() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));
    var comments =
        List.of(
            comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT),
            comment(101L, 100L, "src/A.java", "intentional, leaving as is", "maintainer"));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(PREVIOUS_JSON), List.of(), comments, resolver, BOT_ID);

    assertEquals(List.of(2), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldNotClearHoldForNonWriteReply() {
    // A fork-PR author's reply (author_association NONE) must not clear the approve backstop, so
    // both findings stay held (F1).
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));
    var comments =
        List.of(
            comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT),
            comment(101L, 100L, "src/A.java", "looks fine to me", "fork-author", "NONE"));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(PREVIOUS_JSON), List.of(), comments, resolver, BOT_ID);

    assertEquals(List.of(1, 2), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldTreatANullAssociationReplyAsNonWrite() {
    // A reply whose author_association is absent (null) cannot be shown to hold write access, so it
    // must not clear the hold — both findings stay held (F1).
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));
    var comments =
        List.of(
            comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT),
            comment(101L, 100L, "src/A.java", "looks fine to me", "someone", null));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(PREVIOUS_JSON), List.of(), comments, resolver, BOT_ID);

    assertEquals(List.of(1, 2), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldSeeMaintainerReplyOnNullTitleFindingViaMarker() {
    var json =
        """
        {"findings": [
          {"risk": "high", "file": "src/A.java", "line": 10, "title": null,
           "description": "frees then dereferences"}
        ]}
        """;
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));
    var comments =
        List.of(
            comment(
                100L,
                null,
                "src/A.java",
                "**HIGH — null**\n\nfrees then dereferences\n<!-- thrillhousebot:finding=1 -->",
                BOT),
            comment(101L, 100L, "src/A.java", "intentional, won't fix", "maintainer"));

    var held =
        analyzer.unreportedUnresolvedStatuses(List.of(json), List.of(), comments, resolver, BOT_ID);

    assertTrue(
        held.isEmpty(),
        "a maintainer reply on a null-title finding's marked thread must clear the hold");
  }

  static Stream<Arguments> stillPresentFindingHeldCases() {
    return Stream.of(
        arguments(
            "null-title finding, own thread but no reply",
            """
            {"findings": [
              {"risk": "high", "file": "src/A.java", "line": 10, "title": null,
               "description": "frees then dereferences"}
            ]}
            """,
            List.of(
                comment(
                    100L,
                    null,
                    "src/A.java",
                    "**HIGH — null**\n\nfrees then dereferences\n<!-- thrillhousebot:finding=1 -->",
                    BOT))),
        arguments(
            "null-title finding, earlier round reused its marker index",
            """
            {"findings": [
              {"risk": "high", "file": "src/A.java", "line": 10, "title": null,
               "description": "frees then dereferences"}
            ]}
            """,
            List.of(
                comment(
                    100L,
                    null,
                    "src/A.java",
                    "**LOW — Naming nit**\n\nrename the local for clarity\n<!-- thrillhousebot:finding=1 -->",
                    BOT),
                comment(101L, 100L, "src/A.java", "fine as-is", "maintainer"))),
        arguments(
            "thread-less finding, earlier round reused its marker index",
            """
            {"findings": [
              {"risk": "high", "file": "src/A.java", "line": 10, "title": "Use-after-free",
               "description": "frees then dereferences"}
            ]}
            """,
            List.of(
                comment(
                    100L,
                    null,
                    "src/A.java",
                    "**LOW — Naming nit**\n<!-- thrillhousebot:finding=1 -->",
                    BOT),
                comment(101L, 100L, "src/A.java", "fine as-is", "maintainer"))),
        arguments(
            "short title is a substring of another finding's title",
            """
            {"findings": [
              {"risk": "high", "file": "src/A.java", "line": 10, "title": "NPE",
               "description": "dereferences a value that may be null"}
            ]}
            """,
            List.of(
                comment(
                    100L,
                    null,
                    "src/A.java",
                    "**HIGH — NPE in handler**\n\nguard the handler\n<!-- thrillhousebot:finding=1 -->",
                    BOT),
                comment(101L, 100L, "src/A.java", "intentional", "maintainer"))),
        arguments(
            "blank title and no description",
            """
            {"findings": [
              {"risk": "high", "file": "src/A.java", "line": 10, "title": "", "description": null}
            ]}
            """,
            List.of(
                comment(
                    100L,
                    null,
                    "src/A.java",
                    "**HIGH — flagged here**\n<!-- thrillhousebot:finding=1 -->",
                    BOT))),
        arguments(
            "blank title and blank description",
            """
            {"findings": [
              {"risk": "high", "file": "src/A.java", "line": 10, "title": "", "description": ""}
            ]}
            """,
            List.of(
                comment(
                    100L,
                    null,
                    "src/A.java",
                    "**HIGH — flagged here**\n<!-- thrillhousebot:finding=1 -->",
                    BOT))));
  }

  /**
   * A prior finding whose code is still present in the current diff is held as unresolved across a
   * variety of thread shapes — an own thread without a reply, marker-index reuse by an earlier
   * answered finding, a short title that is a substring of another, and degenerate blank-title or
   * no-description findings that yield no content key. Every case is the safe (downgrade-only)
   * direction: hold rather than risk the silent approve-over-open.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("stillPresentFindingHeldCases")
  void unreportedUnresolvedShouldHoldStillPresentFinding(
      String name, String json, List<GitHubReviewClient.PullRequestComment> comments) {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));

    var held =
        analyzer.unreportedUnresolvedStatuses(List.of(json), List.of(), comments, resolver, BOT_ID);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldBindReplyToTheFindingsOwnMarkedThread() {
    var json =
        """
        {"findings": [
          {"risk": "medium", "file": "src/A.java", "line": 10, "title": "Missing null check",
           "description": "first"},
          {"risk": "medium", "file": "src/A.java", "line": 50, "title": "Missing null check",
           "description": "second"}
        ]}
        """;
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10) + "\n" + patch(50)));
    var comments =
        List.of(
            comment(
                100L,
                null,
                "src/A.java",
                "**MEDIUM — Missing null check**\n<!-- thrillhousebot:finding=1 -->",
                BOT),
            comment(
                200L,
                null,
                "src/A.java",
                "**MEDIUM — Missing null check**\n<!-- thrillhousebot:finding=2 -->",
                BOT),
            comment(201L, 200L, "src/A.java", "intentional", "maintainer"));

    var held =
        analyzer.unreportedUnresolvedStatuses(List.of(json), List.of(), comments, resolver, BOT_ID);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHoldFindingWhenItsMarkedThreadHasOnlyABotReply() {
    var json =
        """
        {"findings": [
          {"risk": "high", "file": "src/A.java", "line": 10, "title": null,
           "description": "frees then dereferences"}
        ]}
        """;
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));
    var comments =
        List.of(
            comment(
                100L,
                null,
                "src/A.java",
                "**HIGH — null**\n\nfrees then dereferences\n<!-- thrillhousebot:finding=1 -->",
                BOT),
            comment(101L, 100L, "src/A.java", "tracking this", BOT));

    var held =
        analyzer.unreportedUnresolvedStatuses(List.of(json), List.of(), comments, resolver, BOT_ID);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHoldNullTitleFindingWhenNoThreadExistsAtAll() {
    var json =
        """
        {"findings": [
          {"risk": "high", "file": "src/A.java", "line": 10, "title": null,
           "description": "anchorless finding"}
        ]}
        """;
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(json), List.of(), List.of(), resolver, BOT_ID);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldExcludeFindingsNotInCurrentDiff() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(PREVIOUS_JSON), List.of(), List.of(), resolver, BOT_ID);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHoldStillPresentFindingUnderARenamedAndEditedFile() {
    // The finding's file was renamed-and-edited; its flagged code is still present at the rename
    // target, not the pre-rename path. The backstop must resolve through renameTargets — as
    // supersedeVanished/addUnreportedVanished already do — or the finding escapes the hold (F5).
    var round =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "old/Moved.java", 5, "Still open", "d", "movedFlagged()", null)),
            List.of(),
            null);
    var resolver =
        new DiffLineResolver(Map.of("new/Moved.java", "@@ -5,1 +5,1 @@\n-old\n+movedFlagged()"));
    var renameTargets = Map.of("old/Moved.java", "new/Moved.java");

    var held =
        analyzer.unreportedUnresolvedStatusesFromParsed(
            List.of(round), List.of(), List.of(), resolver, BOT_ID, renameTargets);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHoldFindingUnderAContentIdenticalPureRename() {
    // The finding's file was renamed without content changes (blank rename target), so its anchor
    // resolves at neither path — but the finding is unchanged and must still be held (F5), matching
    // addUnreportedVanished's pure-rename handling.
    var round =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium", "old/Pure.java", 5, "Still open", "d", "unchanged()", null)),
            List.of(),
            null);
    var resolver = new DiffLineResolver(Map.of());
    var renameTargets = Map.of("old/Pure.java", "");

    var held =
        analyzer.unreportedUnresolvedStatusesFromParsed(
            List.of(round), List.of(), List.of(), resolver, BOT_ID, renameTargets);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void isStillPresentShouldReturnFalseForANullFileFinding() {
    // Defensive-contract test: a null-file finding cannot reach isStillPresent in production —
    // addOpenFindings admits a finding to a cluster only when findingKey is non-null, and
    // findingKey
    // returns null for a null file, so holdableTarget's cluster members always carry a file.
    // Pinning
    // the guard directly means a future refactor that drops it is caught.
    var nullFile = new ReviewResponse.Finding("medium", null, 1, "No file", "d", "anchor()", null);

    assertFalse(
        FollowUpAnalyzer.isStillPresent(nullFile, new DiffLineResolver(Map.of()), Map.of()),
        "a null-file finding cannot be placed in the diff, so it is never present");
  }

  @Test
  void unreportedUnresolvedShouldReturnEmptyForMissingInputs() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    assertTrue(
        analyzer
            .unreportedUnresolvedStatuses(null, List.of(), List.of(), resolver, BOT_ID)
            .isEmpty());
    assertTrue(
        analyzer
            .unreportedUnresolvedStatuses(List.of(), List.of(), List.of(), resolver, BOT_ID)
            .isEmpty());
    assertTrue(
        analyzer
            .unreportedUnresolvedStatuses(
                List.of("not json"), List.of(), List.of(), resolver, BOT_ID)
            .isEmpty());
    assertTrue(
        analyzer
            .unreportedUnresolvedStatuses(
                List.of(PREVIOUS_JSON), List.of(), List.of(), null, BOT_ID)
            .isEmpty());
    assertEquals(
        2,
        analyzer
            .unreportedUnresolvedStatuses(List.of(PREVIOUS_JSON), null, List.of(), resolver, BOT_ID)
            .size());
  }

  @Test
  void unreportedUnresolvedShouldHoldSilentlyDroppedFindingFromAnOlderRound() {
    var prior = List.of(roundJson("src/B.java", 5, "B finding"), roundJson("src/A.java", 10, "A"));
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            prior,
            List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed B")),
            List.of(),
            resolver,
            BOT_ID);

    assertEquals(1, held.size());
    assertTrue(held.stream().allMatch(s -> "unresolved".equals(s.status())));
  }

  @Test
  void unreportedUnresolvedShouldNotHoldFindingResolvedInAnIntermediateRound() {
    var prior =
        List.of(
            roundJson("src/B.java", 5, "B finding", "resolved"), roundJson("src/A.java", 10, "A"));
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held = analyzer.unreportedUnresolvedStatuses(prior, List.of(), List.of(), resolver, BOT_ID);

    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldReHoldAResolvedFindingOnlyWhenReRaisedAndStillPresent() {
    var round1 =
        """
        {"findings": [
          {"risk": "high", "file": "src/A.java", "line": 10, "title": "T",
           "description": "d", "suggestion_old": "still_here();"}
        ]}
        """;
    var round2 =
        "{\"findings\": [], \"previous_findings_status\": [{\"id\": 1, \"status\": \"resolved\", \"note\": \"claimed fixed\"}]}";
    var round3 =
        """
        {"findings": [
          {"risk": "high", "file": "src/A.java", "line": 10, "title": "T",
           "description": "d", "suggestion_old": "still_here();"}
        ]}
        """;
    var patch =
        """
        @@ -10,1 +10,1 @@
        -old();
        +still_here();
        """;
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(round3, round2, round1), List.of(), List.of(), resolver, BOT_ID);

    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldNotHoldFindingJustifiedInAnIntermediateRound() {
    var prior =
        List.of(
            roundJson("src/B.java", 5, "B finding", "justified"), roundJson("src/A.java", 10, "A"));
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held = analyzer.unreportedUnresolvedStatuses(prior, List.of(), List.of(), resolver, BOT_ID);

    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldHoldOlderFindingOnlyMarkedUnresolvedThenDropped() {
    var prior =
        List.of(
            roundJson("src/B.java", 5, "B finding", "unresolved"),
            roundJson("src/A.java", 10, "A"));
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            prior,
            List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed B")),
            List.of(),
            resolver,
            BOT_ID);

    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldDeduplicateFindingCarriedAcrossRounds() {
    var prior = List.of(roundJson("src/A.java", 10, "A"), roundJson("src/A.java", 10, "A"));
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));

    var held = analyzer.unreportedUnresolvedStatuses(prior, List.of(), List.of(), resolver, BOT_ID);

    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldHoldDistinctSameTitleFindingsAtDifferentLines() {
    var json =
        """
        {"findings": [
          {"risk": "medium", "file": "src/A.java", "line": 10, "title": "Dup", "description": "d"},
          {"risk": "medium", "file": "src/A.java", "line": 80, "title": "Dup", "description": "d"}
        ]}
        """;
    var resolver =
        new DiffLineResolver(
            Map.of("src/A.java", "@@ -10,1 +10,1 @@\n-o\n+n\n@@ -80,1 +80,1 @@\n-o\n+n"));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(json), List.of(), List.of(), resolver, BOT_ID);

    assertEquals(2, held.size());
  }

  /**
   * Two findings on src/A.java sharing a title and the generic anchor {@code dangerous_call();}.
   */
  private static final String SHARED_ANCHOR_PAIR_JSON =
      """
      {"findings": [
        {"risk": "high", "file": "src/A.java", "line": 10, "title": "Dangerous call",
         "description": "d", "suggestion_old": "dangerous_call();"},
        {"risk": "high", "file": "src/A.java", "line": 90, "title": "Dangerous call",
         "description": "d", "suggestion_old": "dangerous_call();"}
      ]}
      """;

  /** Patch where the shared anchor {@code dangerous_call();} is present at both line 10 and 90. */
  private static DiffLineResolver sharedAnchorResolver() {
    return new DiffLineResolver(
        Map.of(
            "src/A.java",
            "@@ -10,1 +10,1 @@\n-old();\n+dangerous_call();\n@@ -90,1 +90,1 @@\n-old();\n+dangerous_call();"));
  }

  @Test
  void unreportedUnresolvedShouldHoldDistinctFindingsSharingAnAnchorAtDifferentLines() {
    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(SHARED_ANCHOR_PAIR_JSON), List.of(), List.of(), sharedAnchorResolver(), BOT_ID);

    assertEquals(List.of(1, 2), heldIds(held));
    assertTrue(held.stream().allMatch(s -> "unresolved".equals(s.status())));
  }

  @Test
  void unreportedUnresolvedShouldResolveOnlyTheReferencedFindingWhenAnAnchorIsShared() {
    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(SHARED_ANCHOR_PAIR_JSON),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed #1")),
            List.of(),
            sharedAnchorResolver(),
            BOT_ID);

    assertEquals(List.of(2), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldNotHoldOlderAnchoredFindingWhoseCodeIsGone() {
    var round1 =
        """
        {"findings": [
          {"risk": "high", "file": "src/A.java", "line": 42, "title": "Buggy call",
           "description": "d", "suggestion_old": "buggy_42();", "suggestion_new": ""}
        ]}
        """;
    var round2 = roundJson("src/B.java", 5, "Newer finding");
    var patchA =
        """
        @@ -38,6 +38,4 @@
         ctx_38();
         ctx_39();
        -buggy_41();
        -buggy_42();
         ctx_44();
         ctx_45();
        """;
    var resolver = new DiffLineResolver(Map.of("src/A.java", patchA, "src/B.java", patch(5)));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(round2, round1), List.of(), List.of(), resolver, BOT_ID);

    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldStillHoldFindingGivenAnUnrecognizedCurrentRoundVerdict() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(PREVIOUS_JSON),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "pending", "in progress")),
            List.of(),
            resolver,
            BOT_ID);

    assertEquals(List.of(1, 2), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHandleFindingsMissingFileOrTitleAndOutOfRangeStatus() {
    var json =
        """
        {"findings": [
          {"risk": "low", "file": null, "line": 1, "title": "no file", "description": "d"},
          {"risk": "low", "file": "src/C.java", "line": 7, "title": null, "description": "d"},
          {"risk": "medium", "file": "src/D.java", "line": 9, "title": "D finding",
           "description": "d"}
        ]}
        """;
    var resolver = new DiffLineResolver(Map.of("src/C.java", patch(7), "src/D.java", patch(9)));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(json),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "resolved", "null-file finding"),
                new ReviewResponse.PreviousFindingStatus(0, "resolved", "non-positive id"),
                new ReviewResponse.PreviousFindingStatus(99, "resolved", "out of range")),
            List.of(),
            resolver,
            BOT_ID);

    assertEquals(2, held.size());
    assertTrue(held.stream().allMatch(s -> "unresolved".equals(s.status())));
  }

  @Test
  void unreportedUnresolvedShouldHoldStillOpenFindingThatDriftedBeyondTolerance() {
    var json =
        """
        {"findings": [
          {"risk": "high", "file": "src/A.java", "line": 10, "title": "Dangerous call",
           "description": "unsafe", "suggestion_old": "dangerous_call();",
           "suggestion_new": "safe_call();"}
        ]}
        """;
    var patch =
        """
        @@ -10,2 +30,3 @@
         keep_one();
        +inserted_line();
         dangerous_call();
        """;
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch));

    assertFalse(resolver.isLineInDiff("src/A.java", 10, 3)); // old line drifted out of range
    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(json), List.of(), List.of(), resolver, BOT_ID);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldNotHoldFixedFindingWhoseContextSurvives() {
    var json =
        """
        {"findings": [
          {"risk": "high", "file": "src/A.java", "line": 42, "title": "Buggy call",
           "description": "bug", "suggestion_old": "buggy_42();",
           "suggestion_new": ""}
        ]}
        """;
    var patch =
        """
        @@ -38,8 +38,4 @@
         ctx_38();
         ctx_39();
        -buggy_40();
        -buggy_41();
        -buggy_42();
        -buggy_43();
         ctx_44();
         ctx_45();
        """;
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch));

    assertTrue(resolver.isLineInDiff("src/A.java", 42, 3)); // raw proxy would over-block here
    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(json), List.of(), List.of(), resolver, BOT_ID);

    assertTrue(held.isEmpty());
  }

  @Test
  void unreportedUnresolvedShouldHoldFindingWhoseExactDiffKeyIsEmptyButVariantCarriesIt() {
    var json =
        """
        {"findings": [
          {"risk": "high", "file": "dir/Main.java", "line": 1, "title": "Dangerous call",
           "description": "unsafe", "suggestion_old": "dangerous_call()",
           "suggestion_new": "safe_call()"}
        ]}
        """;
    var deletionOnly =
        """
        @@ -1,2 +1,0 @@
        -removed_one
        -removed_two
        """;
    var variantWithAnchor =
        """
        @@ -1,0 +1,1 @@
        +dangerous_call()
        """;
    var resolver =
        new DiffLineResolver(
            Map.of("dir/Main.java", deletionOnly, "src/dir/Main.java", variantWithAnchor));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(json), List.of(), List.of(), resolver, BOT_ID);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void buildPreviousFindingsContextShouldReturnEmptyForNull() {
    assertEquals("", analyzer.buildPreviousFindingsContext(null, BOT_ID));
  }

  @Test
  void buildPreviousFindingsContextShouldReturnEmptyForNullReviewBody() {
    var reviews =
        List.of(
            new GitHubReviewClient.ReviewResponse(
                1L, null, "COMMENTED", "abc", new GitHubReviewClient.ReviewResponse.User(BOT)));

    assertEquals("", analyzer.buildPreviousFindingsContext(reviews, BOT_ID));
  }

  @Test
  void buildPreviousFindingsContextShouldReturnEmptyForEmptyList() {
    assertEquals("", analyzer.buildPreviousFindingsContext(List.of(), BOT_ID));
  }

  @Test
  void buildPreviousFindingsContextShouldReturnEmptyWhenNoBotReview() {
    var reviews =
        List.of(
            new GitHubReviewClient.ReviewResponse(
                1L,
                "some body",
                "COMMENTED",
                "abc",
                new GitHubReviewClient.ReviewResponse.User("other-user")));

    assertEquals("", analyzer.buildPreviousFindingsContext(reviews, BOT_ID));
  }

  @Test
  void buildPreviousFindingsContextShouldFindLastBotReview() {
    var reviews =
        List.of(
            new GitHubReviewClient.ReviewResponse(
                1L,
                "first review",
                "COMMENTED",
                "abc",
                new GitHubReviewClient.ReviewResponse.User(BOT)),
            new GitHubReviewClient.ReviewResponse(
                2L,
                "second review body",
                "REQUEST_CHANGES",
                "def",
                new GitHubReviewClient.ReviewResponse.User(BOT)));

    var context = analyzer.buildPreviousFindingsContext(reviews, BOT_ID);

    assertTrue(context.contains("second review body"));
    assertFalse(context.contains("first review"));
  }

  @Test
  void structuredContextShouldNumberFindingsFromPreviousAiResponse() {
    var json =
        """
        {"findings": [
          {"risk": "high", "file": "src/A.java", "line": 10,
           "title": "SQL injection", "description": "Concatenated query"},
          {"risk": "low", "file": "src/B.java", "line": 5,
           "title": "Magic number", "description": ""}
        ], "previous_findings_status": [], "summary": null}
        """;

    var context = analyzer.buildPreviousFindingsContext(json, List.of(), BOT_ID);

    assertTrue(context.contains("1. [HIGH] src/A.java:10 — SQL injection"));
    assertTrue(context.contains("Concatenated query"));
    assertTrue(context.contains("2. [LOW] src/B.java:5 — Magic number"));
  }

  @Test
  void structuredContextShouldHandleFindingWithoutRiskOrDescription() {
    var json =
        """
        {"findings": [
          {"file": "src/C.java", "line": 3, "title": "Mystery issue"}
        ], "previous_findings_status": [], "summary": null}
        """;

    var context = analyzer.buildPreviousFindingsContext(json, List.of(), BOT_ID);

    assertTrue(context.contains("1. [UNKNOWN] src/C.java:3 — Mystery issue"));
    assertEquals(1, context.lines().count());
  }

  @Test
  void structuredContextShouldFallBackToBodyWhenJsonMissing() {
    var reviews =
        List.of(
            new GitHubReviewClient.ReviewResponse(
                1L,
                "body findings",
                "COMMENTED",
                "abc",
                new GitHubReviewClient.ReviewResponse.User(BOT)));

    assertTrue(
        analyzer.buildPreviousFindingsContext(null, reviews, BOT_ID).contains("body findings"));
    assertTrue(
        analyzer.buildPreviousFindingsContext("  ", reviews, BOT_ID).contains("body findings"));
  }

  @Test
  void structuredContextShouldFallBackToBodyWhenJsonInvalid() {
    var reviews =
        List.of(
            new GitHubReviewClient.ReviewResponse(
                1L,
                "body findings",
                "COMMENTED",
                "abc",
                new GitHubReviewClient.ReviewResponse.User(BOT)));

    var context = analyzer.buildPreviousFindingsContext("{not json", reviews, BOT_ID);

    assertTrue(context.contains("body findings"));
  }

  @Test
  void structuredContextShouldFallBackWhenPreviousResponseHadNoFindings() {
    var json =
        """
        {"findings": [], "previous_findings_status": [], "summary": null}
        """;

    assertEquals("", analyzer.buildPreviousFindingsContext(json, List.of(), BOT_ID));
  }

  private static List<GitHubReviewClient.ReviewResponse> botReviews(String body) {
    return List.of(
        new GitHubReviewClient.ReviewResponse(
            1L, body, "COMMENTED", "abc", new GitHubReviewClient.ReviewResponse.User(BOT)));
  }

  /**
   * #455 — on PR #449 round 2 found nothing and posted the bot's own "N previous finding(s) remain
   * unresolved …" sentence as its review body; round 3's previous-findings section was that
   * sentence verbatim, under a header telling the model those were issues flagged in the previous
   * review. Neither path may hand the bot's own prose back to it: not the zero-finding round (which
   * has a persisted response and must therefore render nothing), and not the legitimate
   * no-persisted-response fallback (which must discard a body the bot generated itself).
   */
  @Test
  void previousFindingsContextShouldNeverCarryTheBotsOwnStatusBody() {
    var reviews = botReviews(ReviewResult.unresolvedPreviousMessage(1));
    var zeroFindingRound =
        """
        {"findings": [], "previous_findings_status": [], "summary": null}
        """;

    assertEquals(
        "",
        analyzer.buildPreviousFindingsContext(zeroFindingRound, reviews, BOT_ID),
        "a persisted round that legitimately found nothing must render no previous findings");
    assertEquals(
        "",
        analyzer.buildPreviousFindingsContext(null, reviews, BOT_ID),
        "the review-body fallback must discard a body the bot wrote about its own verdict");
  }

  /**
   * Every review body the bot generates for a no-new-findings round is its own output, so none of
   * them may be offered as a prior finding. A body it did not generate still is.
   */
  @Test
  void reviewBodyFallbackShouldDropEveryBotGeneratedBodyAndKeepTheRest() {
    assertEquals(
        "",
        analyzer.buildPreviousFindingsContext(
            botReviews(PrSummaryGenerator.ZERO_ISSUES_MESSAGE), BOT_ID));
    assertEquals(
        "",
        analyzer.buildPreviousFindingsContext(
            botReviews(ReviewResult.NO_ISSUES_CI_PENDING_LEAD_IN + "\n- Check **build** is failed"),
            BOT_ID));
    assertEquals(
        "",
        analyzer.buildPreviousFindingsContext(
            botReviews(ReviewResult.NO_ISSUES_CI_UNREADABLE_LEAD_IN), BOT_ID));
    assertEquals(
        "",
        analyzer.buildPreviousFindingsContext(
            botReviews(ReviewResult.truncationNotice(3)), BOT_ID));
    assertEquals("", analyzer.buildPreviousFindingsContext(botReviews("   \n\n"), BOT_ID));
    assertEquals(
        "1. [HIGH] src/A.java:10 — Unsafe regex",
        analyzer.buildPreviousFindingsContext(
            botReviews("1. [HIGH] src/A.java:10 — Unsafe regex"), BOT_ID),
        "a legacy body carrying real findings is still the only context such a session has");

    var humanEcho = "No new issues in this revision, but the null check on line 12 is still wrong.";
    assertEquals(
        humanEcho,
        analyzer.buildPreviousFindingsContext(botReviews(humanEcho), BOT_ID),
        "a body that only opens like the generated sentence carries a real finding and is kept");
  }

  /**
   * The round-selection helpers are the id space every downstream consumer keys off, so an absent
   * or unreadable round must degrade to "no previous round" rather than throw mid-review. Matches
   * how the rest of this class treats absent input ({@code parsePreviousResponses(null)}, {@code
   * toStatuses(null)}).
   */
  @Test
  void effectivePreviousRoundHelpersShouldTreatAbsentRoundsAsNoPreviousRound() {
    assertEquals(-1, FollowUpAnalyzer.effectivePreviousRoundIndex(null));
    assertTrue(FollowUpAnalyzer.effectivePreviousFindings(null).isEmpty());

    var rounds = new ArrayList<ReviewResponse>();
    rounds.add(null);
    rounds.add(analyzer.parseResponse(PREVIOUS_JSON));
    assertEquals(
        1,
        FollowUpAnalyzer.effectivePreviousRoundIndex(rounds),
        "an unreadable slot is skipped, not mistaken for the round that raised findings");
    assertEquals("src/A.java", FollowUpAnalyzer.effectivePreviousFindings(rounds).get(0).file());
  }

  /**
   * #455 — the review-body fallback is gated on whether the bot persisted a response at all, and
   * {@link FollowUpAnalyzer#isPersistedResponse} is the discriminator. A missing or unparseable
   * response is not persisted; a round that legitimately found nothing is, even though the two
   * carry identical (empty) findings.
   */
  @Test
  void isPersistedResponseShouldSeparateAStoredRoundFromAMissingOne() {
    assertFalse(FollowUpAnalyzer.isPersistedResponse(null));
    assertFalse(FollowUpAnalyzer.isPersistedResponse(analyzer.parseResponse(null)));
    assertFalse(FollowUpAnalyzer.isPersistedResponse(analyzer.parseResponse("   ")));
    assertFalse(
        FollowUpAnalyzer.isPersistedResponse(analyzer.parseResponse("{not json")),
        "an unparseable response is the same absence as a missing one");
    assertTrue(
        FollowUpAnalyzer.isPersistedResponse(
            analyzer.parseResponse(
                "{\"findings\":[],\"previous_findings_status\":[],\"summary\":null}")),
        "a round that legitimately found nothing did persist a response");
  }

  @Test
  void toStatusesShouldReturnEmptyForNull() {
    assertTrue(analyzer.toStatuses(null).isEmpty());
  }

  @Test
  void toStatusesShouldConvertAllStatuses() {
    var aiStatuses =
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "resolved", "Fixed"),
            new ReviewResponse.PreviousFindingStatus(2, "unresolved", "Still broken"),
            new ReviewResponse.PreviousFindingStatus(3, "justified", "Known limitation"));

    var statuses = analyzer.toStatuses(aiStatuses);

    assertEquals(3, statuses.size());
    assertEquals(1, statuses.get(0).id());
    assertEquals("resolved", statuses.get(0).status());
    assertEquals("Fixed", statuses.get(0).note());
    assertEquals(2, statuses.get(1).id());
    assertEquals("unresolved", statuses.get(1).status());
    assertEquals("Still broken", statuses.get(1).note());
    assertEquals("justified", statuses.get(2).status());
  }

  @Test
  void toStatusesShouldDropUnrecognizedStatuses() {
    var aiStatuses =
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "wontfix", "nope"),
            new ReviewResponse.PreviousFindingStatus(2, "Resolved", "fixed"),
            new ReviewResponse.PreviousFindingStatus(3, "", "blank"),
            new ReviewResponse.PreviousFindingStatus(4, null, "missing"));

    var statuses = analyzer.toStatuses(aiStatuses);

    assertEquals(1, statuses.size());
    assertEquals(2, statuses.get(0).id());
    assertEquals("Resolved", statuses.get(0).status());
  }

  @Test
  void hasUnresolvedShouldReturnTrueWhenUnresolvedPresent() {
    var statuses =
        List.of(
            new ReviewResult.PreviousFindingStatus(1, "resolved", "done"),
            new ReviewResult.PreviousFindingStatus(2, "unresolved", "still there"));

    assertTrue(analyzer.hasUnresolved(statuses));
  }

  @Test
  void hasUnresolvedShouldReturnFalseWhenNoUnresolved() {
    var statuses =
        List.of(
            new ReviewResult.PreviousFindingStatus(1, "resolved", "done"),
            new ReviewResult.PreviousFindingStatus(2, "justified", "known"));

    assertFalse(analyzer.hasUnresolved(statuses));
  }

  @Test
  void hasUnresolvedShouldReturnFalseForEmptyList() {
    assertFalse(analyzer.hasUnresolved(List.of()));
  }

  @Test
  void unreportedUnresolvedShouldDeduplicateDriftedReRaisedFindingAcrossRounds() {
    var round1 =
        """
        {"findings": [
          {"risk": "medium", "file": "src/A.java", "line": 10, "title": "SQL injection in query",
           "description": "The query concatenates user input directly without sanitization",
           "suggestion_old": "query = \\"SELECT * FROM users WHERE id = \\" + id;"}
        ]}
        """;
    var round2 =
        """
        {"findings": [
          {"risk": "medium", "file": "src/A.java", "line": 32, "title": "SQL injection in database query",
           "description": "The database query concatenates user input directly without sanitization",
           "suggestion_old": "query = \\"SELECT * FROM users WHERE id = \\" + id;"}
        ]}
        """;
    var resolver =
        new DiffLineResolver(
            Map.of(
                "src/A.java",
                "@@ -10,1 +10,1 @@\n-old1\n+query = \"SELECT * FROM users WHERE id = \" + id;\n@@ -32,1 +32,1 @@\n-old2\n+query = \"SELECT * FROM users WHERE id = \" + id;\n"));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(round2, round1), List.of(), List.of(), resolver, BOT_ID);

    assertEquals(1, held.size());
    assertEquals("unresolved", held.get(0).status());
  }

  @Test
  void unreportedUnresolvedShouldExcludeDriftedReRaisedFindingIfAnyMemberHasReply() {
    var round1 =
        """
        {"findings": [
          {"risk": "medium", "file": "src/A.java", "line": 10, "title": "SQL injection in query",
           "description": "The query concatenates user input directly without sanitization",
           "suggestion_old": "query = \\"SELECT * FROM users WHERE id = \\" + id;"}
        ]}
        """;
    var round2 =
        """
        {"findings": [
          {"risk": "medium", "file": "src/A.java", "line": 32, "title": "SQL injection in database query",
           "description": "The database query concatenates user input directly without sanitization",
           "suggestion_old": "query = \\"SELECT * FROM users WHERE id = \\" + id;"}
        ]}
        """;
    var comments =
        List.of(
            comment(100L, null, "src/A.java", "SQL injection in query", BOT),
            comment(101L, 100L, "src/A.java", "intentional", "maintainer"));

    var resolver =
        new DiffLineResolver(
            Map.of(
                "src/A.java",
                "@@ -10,1 +10,1 @@\n-old1\n+query = \"SELECT * FROM users WHERE id = \" + id;\n@@ -32,1 +32,1 @@\n-old2\n+query = \"SELECT * FROM users WHERE id = \" + id;\n"));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(round2, round1), List.of(), comments, resolver, BOT_ID);

    assertTrue(held.isEmpty());
  }

  // --- recheckDeclines: a maintainer's decline is a claim to verify, not ground truth (#169) ---

  private static final String PAUSE_FILE =
      "src/main/java/dev/thiagogonzaga/thrillhousebot/webhook/PrPauseService.java";

  private static final String RACE_TITLE =
      "Race condition in pause() can cause a UniqueConstraint violation under concurrent webhooks";

  /** The dogfood prior finding from PR #160 — correct, low confidence, "verify before acting". */
  private static final List<ReviewResponse.Finding> RACE_PREVIOUS =
      List.of(
          new ReviewResponse.Finding(
              "medium",
              "low",
              PAUSE_FILE,
              60,
              RACE_TITLE,
              "pause() checks for an existing PausedPr and then inserts one; two deliveries can"
                  + " both pass the check before either inserts.",
              null,
              null));

  /**
   * The same PR's command path: every command is handed to the shared review executor, so the
   * "single call site, runs after the ack" premise does not serialize anything.
   */
  private static final String DISPATCHING_DIFF =
      """
      diff --git a/src/main/java/dev/thiagogonzaga/thrillhousebot/webhook/CommentCommandService.java
      @@ -130,7 +130,9 @@ public class CommentCommandService {
      +  private void dispatch(CommandContext ctx) {
      +    executor.execute(() -> execute(ctx));
      +  }
      """;

  private static List<GitHubReviewClient.PullRequestComment> raceThread(String... humanReplies) {
    var comments = new java.util.ArrayList<GitHubReviewClient.PullRequestComment>();
    comments.add(comment(700L, null, PAUSE_FILE, "**MEDIUM — " + RACE_TITLE + "**", BOT));
    for (var i = 0; i < humanReplies.length; i++) {
      comments.add(comment(701L + i, 700L, PAUSE_FILE, humanReplies[i], "maintainer"));
    }
    return List.copyOf(comments);
  }

  private static List<ReviewResponse.PreviousFindingStatus> justified() {
    return List.of(
        new ReviewResponse.PreviousFindingStatus(
            1, "justified", "maintainer says the path cannot run concurrently"));
  }

  @Test
  void recheckShouldReopenDeclineWhoseAsyncAfterAckPremiseTheReviewedCodeContradicts() {
    var comments =
        raceThread(
            "Not changed — pause() is only ever called from the /pause command path, which runs"
                + " asynchronously on the review executor after the webhook has returned 200.");

    var rechecked =
        analyzer.recheckDeclines(
            RACE_PREVIOUS, justified(), comments, BOT_ID, () -> DISPATCHING_DIFF);

    assertEquals(1, rechecked.size());
    assertEquals(
        "unresolved",
        rechecked.get(0).status(),
        "a decline the reviewed code contradicts must not be recorded justified");
    assertTrue(
        rechecked.get(0).note().contains("only ever called from"),
        "the note must quote the maintainer's claim, was: " + rechecked.get(0).note());
    assertTrue(
        rechecked.get(0).note().contains("executor.execute(() -> execute(ctx));"),
        "the note must quote the contradicting line, was: " + rechecked.get(0).note());
  }

  @Test
  void recheckShouldIgnoreARebuttalFromANonWriteAuthor() {
    // The contradicting rebuttal text is supplied by a fork-PR author (author_association
    // CONTRIBUTOR), not a maintainer. It must not drive the decline override — with no maintainer
    // reply, the "exactly one reply" leg fails and the status is left untouched (F1).
    var comments =
        List.of(
            comment(700L, null, PAUSE_FILE, "**MEDIUM — " + RACE_TITLE + "**", BOT),
            comment(
                701L,
                700L,
                PAUSE_FILE,
                "Not changed — pause() is only ever called from the /pause command path, which runs"
                    + " asynchronously on the review executor after the webhook has returned 200.",
                "fork-author",
                "CONTRIBUTOR"));

    var rechecked =
        analyzer.recheckDeclines(
            RACE_PREVIOUS, justified(), comments, BOT_ID, () -> DISPATCHING_DIFF);

    assertEquals(
        "justified",
        rechecked.get(0).status(),
        "a rebuttal from an author without write access must not drive the override");
  }

  @Test
  void recheckShouldKeepDeclineThatRestsOnStyleOrIntent() {
    var comments =
        raceThread("Intentional — this is the house style for command handlers. Not changing it.");

    var rechecked =
        analyzer.recheckDeclines(
            RACE_PREVIOUS, justified(), comments, BOT_ID, () -> DISPATCHING_DIFF);

    assertEquals(
        "justified",
        rechecked.get(0).status(),
        "a rebuttal that is not refutable from the code must be respected");
  }

  @Test
  void recheckShouldDeferOnceTheMaintainerHasAnsweredTwice() {
    var comments =
        raceThread(
            "Not changed — pause() is only ever called from the /pause command path, which runs"
                + " asynchronously on the review executor after the webhook has returned 200.",
            "Still no — I looked, the executor never runs two of these for one PR.");

    var rechecked =
        analyzer.recheckDeclines(
            RACE_PREVIOUS, justified(), comments, BOT_ID, () -> DISPATCHING_DIFF);

    assertEquals(
        "justified",
        rechecked.get(0).status(),
        "a second maintainer reply answers the push-back and always wins");
  }

  @Test
  void recheckShouldBeDisabledByConfig() {
    var disabled = new FollowUpAnalyzer(new ObjectMapper(), false);
    var comments =
        raceThread(
            "Not changed — pause() is only ever called from the /pause command path, which runs"
                + " asynchronously on the review executor after the webhook has returned 200.");

    var rechecked =
        disabled.recheckDeclines(
            RACE_PREVIOUS, justified(), comments, BOT_ID, () -> DISPATCHING_DIFF);

    assertEquals("justified", rechecked.get(0).status());
  }

  @Test
  void recheckShouldLeaveNonJustifiedStatusesAndUnmatchableInputsAlone() {
    var unresolved =
        List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"));
    var comments =
        raceThread(
            "Not changed — pause() is only ever called from the /pause command path, which runs"
                + " asynchronously on the review executor after the webhook has returned 200.");

    assertEquals(
        unresolved,
        analyzer.recheckDeclines(
            RACE_PREVIOUS, unresolved, comments, BOT_ID, () -> DISPATCHING_DIFF));
    // No thread to read the rebuttal from.
    assertEquals(
        "justified",
        analyzer
            .recheckDeclines(RACE_PREVIOUS, justified(), List.of(), BOT_ID, () -> DISPATCHING_DIFF)
            .get(0)
            .status());
    // No reviewed code to check the rebuttal against.
    assertEquals(
        "justified",
        analyzer
            .recheckDeclines(RACE_PREVIOUS, justified(), comments, BOT_ID, () -> "")
            .get(0)
            .status());
    // Status id outside the prior round.
    var outOfRange =
        List.of(new ReviewResponse.PreviousFindingStatus(9, "justified", "no such finding"));
    assertEquals(
        outOfRange,
        analyzer.recheckDeclines(
            RACE_PREVIOUS, outOfRange, comments, BOT_ID, () -> DISPATCHING_DIFF));
    assertTrue(analyzer.recheckDeclines(RACE_PREVIOUS, null, comments, BOT_ID, null).isEmpty());
  }

  /**
   * Each way the re-check can find nothing to work with. Every one must hand the statuses back
   * exactly as the model reported them — the conservative outcome — and none may reach the matcher.
   */
  static Stream<Arguments> recheckNoOpInputs() {
    var thread =
        raceThread(
            "Not changed — pause() is only ever called from the /pause command path, which runs"
                + " asynchronously on the review executor after the webhook has returned 200.");
    return Stream.of(
        arguments(
            "no statuses at all", RACE_PREVIOUS, List.of(), thread, supplier(DISPATCHING_DIFF)),
        arguments("no prior round", null, justified(), thread, supplier(DISPATCHING_DIFF)),
        arguments("empty prior round", List.of(), justified(), thread, supplier(DISPATCHING_DIFF)),
        arguments(
            "no inline comments", RACE_PREVIOUS, justified(), null, supplier(DISPATCHING_DIFF)),
        arguments("no code supplier", RACE_PREVIOUS, justified(), thread, null),
        arguments("supplier yields null", RACE_PREVIOUS, justified(), thread, supplier(null)),
        arguments("supplier yields blank", RACE_PREVIOUS, justified(), thread, supplier("   \n")),
        arguments(
            "id below the prior round",
            RACE_PREVIOUS,
            List.of(new ReviewResponse.PreviousFindingStatus(0, "justified", "bad id")),
            thread,
            supplier(DISPATCHING_DIFF)),
        arguments(
            "thread cannot be located",
            RACE_PREVIOUS,
            justified(),
            List.of(comment(900L, null, "src/Unrelated.java", "**LOW — something else**", BOT)),
            supplier(DISPATCHING_DIFF)));
  }

  private static Supplier<String> supplier(String value) {
    return () -> value;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recheckNoOpInputs")
  void recheckShouldReturnStatusesUntouchedWhenThereIsNothingToVerify(
      String name,
      List<ReviewResponse.Finding> previous,
      List<ReviewResponse.PreviousFindingStatus> statuses,
      List<GitHubReviewClient.PullRequestComment> comments,
      Supplier<String> code) {
    assertEquals(
        statuses,
        analyzer.recheckDeclines(previous, statuses, comments, BOT_ID, code),
        "the decline must survive untouched when the re-check has nothing to verify: " + name);
  }

  @Test
  void recheckShouldOnlyRewriteTheDeclinedEntryOfAMixedStatusList() {
    var twoFindings =
        List.of(
            RACE_PREVIOUS.get(0),
            new ReviewResponse.Finding(
                "low", "high", "src/B.java", 5, "Missing null check", "may NPE", null, null));
    var mixed =
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "justified", "cannot run concurrently"),
            new ReviewResponse.PreviousFindingStatus(2, "resolved", "fixed in abc123"));
    var comments =
        raceThread(
            "Not changed — pause() is only ever called from the /pause command path, which runs"
                + " asynchronously on the review executor after the webhook has returned 200.");

    var rechecked =
        analyzer.recheckDeclines(twoFindings, mixed, comments, BOT_ID, () -> DISPATCHING_DIFF);

    assertEquals("unresolved", rechecked.get(0).status());
    assertEquals(
        mixed.get(1),
        rechecked.get(1),
        "a non-declined status must pass through the re-check byte for byte");
  }

  @Test
  void recheckShouldIgnoreBotAnonymousAndEmptyRepliesWhenCountingTheRebuttal() {
    // Only ONE real maintainer reply is present; the bot's own follow-up, an author-less reply and
    // the body-less ones (null, blank) must not count as a second human answer that would end the
    // re-check.
    var comments =
        List.of(
            comment(700L, null, PAUSE_FILE, "**MEDIUM — " + RACE_TITLE + "**", BOT),
            comment(
                701L,
                700L,
                PAUSE_FILE,
                "Not changed — pause() is only ever called from the /pause command path, which"
                    + " runs asynchronously on the review executor after the webhook has returned"
                    + " 200.",
                "maintainer"),
            comment(702L, 700L, PAUSE_FILE, "Thanks, noted.", BOT),
            new GitHubReviewClient.PullRequestComment(
                703L, 700L, PAUSE_FILE, "anonymous reply", null),
            comment(704L, 700L, PAUSE_FILE, "   ", "maintainer"),
            comment(705L, 700L, PAUSE_FILE, null, "maintainer"),
            comment(706L, 800L, PAUSE_FILE, "reply on a different thread", "maintainer"));

    var rechecked =
        analyzer.recheckDeclines(
            RACE_PREVIOUS, justified(), comments, BOT_ID, () -> DISPATCHING_DIFF);

    assertEquals(
        "unresolved",
        rechecked.get(0).status(),
        "bot, author-less, body-less and other-thread replies are not the maintainer answering"
            + " the push-back");
  }

  @Test
  void injectedAnalyzerShouldTakeTheRecheckFlagFromConfig() {
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    when(review.declineRecheckEnabled()).thenReturn(false);
    var config = mock(ThrillhouseConfig.class);
    when(config.review()).thenReturn(review);
    var comments =
        raceThread(
            "Not changed — pause() is only ever called from the /pause command path, which runs"
                + " asynchronously on the review executor after the webhook has returned 200.");

    var configured = new FollowUpAnalyzer(new ObjectMapper(), config);

    assertEquals(
        "justified",
        configured
            .recheckDeclines(RACE_PREVIOUS, justified(), comments, BOT_ID, () -> DISPATCHING_DIFF)
            .get(0)
            .status(),
        "the injected constructor must honour thrillhousebot.review.decline-recheck-enabled");
  }

  // ---------------------------------------------------------------------------------------------
  // #548 — a finding that never posted inline has no review thread, so the reply hatch (#133/#142)
  // cannot reach it. The PR conversation is its only escape. Over-clearing is the dangerous
  // direction, so each case below pins one leg of the recognition.
  // ---------------------------------------------------------------------------------------------

  /** Names the first PREVIOUS_JSON finding exactly as the summary prints it. */
  private static final String CLEARS_FINDING_ONE =
      "@thrillhousebot resolved `src/A.java:10` — SQL injection (fixed in abc123)";

  private static GitHubCommentClient.IssueComment conversationComment(
      String body, String author, String association) {
    return new GitHubCommentClient.IssueComment(
        900L, body, new GitHubReviewClient.ReviewResponse.User(author), association);
  }

  /** A write-capable maintainer's PR conversation comment. */
  private static GitHubCommentClient.IssueComment maintainerSays(String body) {
    return conversationComment(body, "maintainer", "MEMBER");
  }

  /** The backstop over PREVIOUS_JSON with both findings still present in the diff. */
  private List<ReviewResult.PreviousFindingStatus> backstopWith(
      List<GitHubCommentClient.IssueComment> conversation) {
    return backstopWith(PREVIOUS_JSON, conversation);
  }

  private List<ReviewResult.PreviousFindingStatus> backstopWith(
      String roundJson, List<GitHubCommentClient.IssueComment> conversation) {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));
    return analyzer.unreportedUnresolvedStatusesFromParsed(
        analyzer.parsePreviousResponses(List.of(roundJson)),
        List.of(),
        List.of(),
        conversation,
        resolver,
        BOT_ID,
        Map.of());
  }

  @Test
  void backstopShouldClearOnlyTheThreadlessFindingTheConversationNames() {
    var held = backstopWith(List.of(maintainerSays(CLEARS_FINDING_ONE)));

    assertEquals(
        List.of(2),
        heldIds(held),
        "the named finding must be cleared and the one it does not name must stay held");
  }

  @Test
  void backstopShouldScopeAConversationClearToTheNamedLocator() {
    // Two findings sharing a title in different files: the locator, not the title, decides which
    // one the maintainer named — the same discipline that keeps a recurring marker index from
    // binding a clear to another round's finding.
    var sameTitleTwice =
        """
        {"findings": [
          {"risk": "low", "file": "src/A.java", "line": 10, "title": "Missing null check",
           "description": "may NPE"},
          {"risk": "low", "file": "src/B.java", "line": 5, "title": "Missing null check",
           "description": "may NPE"}
        ]}
        """;

    var held =
        backstopWith(
            sameTitleTwice,
            List.of(maintainerSays("@thrillhousebot resolved `src/A.java:10` Missing null check")));

    assertEquals(List.of(2), heldIds(held));
  }

  static Stream<Arguments> conversationCommentsThatClearNothing() {
    return Stream.of(
        arguments("directive naming no finding", "@thrillhousebot resolved — thanks, all good now"),
        arguments(
            "a pasted marker names nothing across rounds",
            "@thrillhousebot resolved <!-- thrillhousebot:finding=1 -->"),
        arguments(
            "another round's locator for the same title",
            "@thrillhousebot resolved `src/A.java:99` — SQL injection"),
        arguments(
            "one finding's locator with another finding's title",
            "@thrillhousebot resolved `src/A.java:10` — Missing null check"),
        arguments(
            "GitHub quote-reply reproducing every summary row",
            """
            > ### Things to double-check
            > - **CRITICAL:** SQL injection (`src/A.java:10`)
            > - **MEDIUM:** Missing null check (`src/B.java:5`)

            @thrillhousebot resolved
            """),
        arguments(
            "the directive quoted while the findings are named",
            """
            Someone told me to write:
            > @thrillhousebot resolved

            about `src/A.java:10` — SQL injection and `src/B.java:5` — Missing null check.
            """),
        arguments(
            "the directive fenced while the findings are named",
            """
            ```
            @thrillhousebot resolved
            ```
            `src/A.java:10` — SQL injection, `src/B.java:5` — Missing null check
            """),
        arguments(
            "findings named with no directive at all",
            "Fixed `src/A.java:10` — SQL injection and `src/B.java:5` — Missing null check."),
        arguments(
            "the thread-resolving command is not a clear directive",
            "@thrillhousebot resolve `src/A.java:10` — SQL injection"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("conversationCommentsThatClearNothing")
  void backstopShouldClearNothingWhenTheConversationDoesNotNameTheFinding(
      String name, String body) {
    assertEquals(
        List.of(1, 2),
        heldIds(backstopWith(List.of(maintainerSays(body)))),
        name + " must clear nothing");
  }

  static Stream<Arguments> conversationCommentsThatCannotDecide() {
    return Stream.of(
        arguments("fork-PR author", conversationComment(CLEARS_FINDING_ONE, "outsider", "NONE")),
        arguments(
            "contributor without write access",
            conversationComment(CLEARS_FINDING_ONE, "outsider", "CONTRIBUTOR")),
        arguments(
            "absent author association", conversationComment(CLEARS_FINDING_ONE, "someone", null)),
        arguments(
            "the bot quoting its own summary",
            conversationComment(CLEARS_FINDING_ONE, BOT, "OWNER")),
        arguments(
            "comment with no author object",
            new GitHubCommentClient.IssueComment(900L, CLEARS_FINDING_ONE, null, "OWNER")),
        arguments("comment with no body", conversationComment(null, "maintainer", "OWNER")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("conversationCommentsThatCannotDecide")
  void backstopShouldIgnoreAClearDirectiveFromAnIneligibleComment(
      String name, GitHubCommentClient.IssueComment comment) {
    assertEquals(
        List.of(1, 2), heldIds(backstopWith(List.of(comment))), name + " must not clear a hold");
  }

  @Test
  void backstopShouldIgnoreAnAbsentConversationEntry() {
    // Defensive: a null element cannot survive the ReviewContext copy, but the analyzer is called
    // directly by other paths and must never dereference one.
    var conversation = new ArrayList<GitHubCommentClient.IssueComment>();
    conversation.add(null);
    conversation.add(maintainerSays(CLEARS_FINDING_ONE));

    assertEquals(List.of(2), heldIds(backstopWith(conversation)));
  }

  @Test
  void backstopShouldTreatAnAbsentConversationAsNoClear() {
    assertEquals(List.of(1, 2), heldIds(backstopWith(null)));
  }

  static Stream<Arguments> contentAnchorCases() {
    return Stream.of(
        arguments(
            "title", "\"Missing bound check\"", "\"guards nothing\"", "Missing bound check", true),
        arguments(
            "description when the title is null",
            "null",
            "\"guards nothing\"",
            "guards nothing",
            true),
        arguments(
            "description when the title is blank",
            "\"  \"",
            "\"guards nothing\"",
            "guards nothing",
            true),
        arguments("neither title nor description", "null", "null", "src/A.java", false),
        arguments("blank title and blank description", "\"  \"", "\"  \"", "src/A.java", false));
  }

  @ParameterizedTest(name = "cleared by {0}: {4}")
  @MethodSource("contentAnchorCases")
  void backstopShouldClearOnlyOnTheFindingsOwnContent(
      String name, String titleJson, String descriptionJson, String quoted, boolean cleared) {
    var json =
        "{\"findings\": [{\"risk\": \"low\", \"file\": \"src/A.java\", \"line\": 10, \"title\": "
            + titleJson
            + ", \"description\": "
            + descriptionJson
            + "}]}";

    var held =
        backstopWith(
            json, List.of(maintainerSays("@thrillhousebot resolved `src/A.java:10` — " + quoted)));

    assertEquals(
        cleared ? List.of() : List.of(1),
        heldIds(held),
        name + " — a finding with no content of its own must stay held");
  }

  /** One low finding at src/A.java:1, whose locator is a prefix of src/A.java:10's. */
  private static final String FINDING_AT_LINE_ONE =
      """
      {"findings": [
        {"risk": "low", "file": "src/A.java", "line": 1, "title": "SQL injection",
         "description": "raw query"}
      ]}
      """;

  static Stream<Arguments> prefixLocatorCases() {
    return Stream.of(
        arguments(
            "a longer line number must not clear the shorter one",
            "@thrillhousebot resolved `src/A.java:10` — SQL injection",
            false),
        arguments(
            "a later whole match still counts",
            "@thrillhousebot resolved src/A.java:10 and src/A.java:1 — SQL injection",
            true),
        arguments(
            "a match ending the comment counts",
            "@thrillhousebot resolved SQL injection at src/A.java:1",
            true),
        // A following digit used to be the only continuation the guard rejected, so every other
        // way of continuing the line-number token still read as a whole locator and over-cleared.
        arguments(
            "a line range starting at the finding's line must not clear it",
            "@thrillhousebot resolved `src/A.java:1-3` — SQL injection",
            false),
        arguments(
            "a letter continuing the line number must not clear it",
            "@thrillhousebot resolved `src/A.java:1x` — SQL injection",
            false),
        arguments(
            "an underscore continuing the line number must not clear it",
            "@thrillhousebot resolved `src/A.java:1_2` — SQL injection",
            false),
        // Typographic ranges reach the guard as often as the ASCII hyphen does: smart-punctuation
        // autocorrect rewrites a typed 1-3 to an en dash, and a range copied out of prose can carry
        // an em dash or a minus sign. Each one adjacent to the line number is still a range.
        arguments(
            "an en-dash line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1–3` — SQL injection",
            false),
        arguments(
            "an em-dash line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1—3` — SQL injection",
            false),
        arguments(
            "a minus-sign line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1−3` — SQL injection",
            false),
        // A range is written with the separator spaced off the line number about as often as
        // adjacent to it, and the adjacent-character guard cannot see those spellings at all.
        arguments(
            "a spaced hyphen line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1 - 3` — SQL injection",
            false),
        arguments(
            "a spaced en-dash line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1 – 3` — SQL injection",
            false),
        arguments(
            "a dotted line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1..3` — SQL injection",
            false),
        arguments(
            "a triple-dot line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1...3` — SQL injection",
            false),
        // Both guards ask isDash, so a dash either rejects in both spellings or neither. A
        // fullwidth hyphen-minus and a wave dash are Pd but appear in nobody's enumeration.
        arguments(
            "a spaced fullwidth-hyphen line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1 － 3` — SQL injection",
            false),
        arguments(
            "a spaced wave-dash line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1 〜 3` — SQL injection",
            false),
        // Format characters (Cf) are neither token continuation nor Zs space, so before they
        // counted as continuations each of these read :1 as a whole locator and over-cleared the
        // finding at line 1 even though the comment names a range.
        arguments(
            "a zero-width space inside the range spelling must not clear it",
            "@thrillhousebot resolved src/A.java:1\u200B-3 — SQL injection",
            false),
        arguments(
            "a zero-width joiner inside the range spelling must not clear it",
            "@thrillhousebot resolved src/A.java:1\u200D-3 — SQL injection",
            false),
        arguments(
            "a soft hyphen after the line number must not clear it",
            "@thrillhousebot resolved src/A.java:1\u00AD3 — SQL injection",
            false),
        arguments(
            "a zero-width no-break space inside the range spelling must not clear it",
            "@thrillhousebot resolved src/A.java:1\uFEFF-3 — SQL injection",
            false),
        // Cf is classified by code point: a supplementary-plane format character (U+E0001) is a
        // surrogate pair, and a per-char category test reports neither half as FORMAT.
        arguments(
            "an astral format character inside the range spelling must not clear it",
            "@thrillhousebot resolved src/A.java:1\uDB40\uDC01-3 — SQL injection",
            false),
        // The same artifact one position over: a format character inside the range's spacing must
        // be stepped over, or the trailing-digit test fails and the range reads as a whole locator.
        arguments(
            "a zero-width space between the dash and the end line must not clear it",
            "@thrillhousebot resolved src/A.java:1 -\u200B3 — SQL injection",
            false),
        // The accepted cost of requiring a digit: a title opening with one reads as a range and
        // under-clears, which holds the finding a round rather than dropping it.
        arguments(
            "a title opening with a digit under-clears rather than risking the separator",
            "@thrillhousebot resolved src/A.java:1 — 2 call sites of this SQL injection",
            false),
        arguments(
            "a tab-spaced line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1\t-\t3` — SQL injection",
            false),
        // Copy-paste and locale-aware autocorrect put these in comment text; a separator the scan
        // will not step over stops reading as a range.
        arguments(
            "a no-break-space-spaced line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1 - 3` — SQL injection",
            false),
        arguments(
            "a narrow-no-break-space-spaced line range must not clear it",
            "@thrillhousebot resolved `src/A.java:1 - 3` — SQL injection",
            false),
        // A list item on the next line is prose, not a range: newlines must not space a separator.
        arguments(
            "a dash opening the next line is not a range and still clears",
            "@thrillhousebot resolved src/A.java:1\n- 3 of these are SQL injection",
            true),
        // Everything a range scan must not swallow: prose that merely continues after the locator,
        // and a separator too far away or with nothing after it to be one.
        arguments(
            "a sentence-ending period is not a range and still clears",
            "@thrillhousebot resolved src/A.java:1. SQL injection is gone",
            true),
        arguments(
            "a locator trailed by spaces to the end still clears",
            "@thrillhousebot resolved SQL injection at src/A.java:1   ",
            true),
        arguments(
            "a dash ending the comment is not a range and still clears",
            "@thrillhousebot resolved SQL injection at src/A.java:1 -",
            true),
        arguments(
            "dots running to the end of the comment are not a range and still clear",
            "@thrillhousebot resolved SQL injection at src/A.java:1..",
            true),
        arguments(
            "a separator spaced further than a range is written still clears",
            "@thrillhousebot resolved src/A.java:1                    - 3 SQL injection",
            true),
        arguments(
            "the documented em-dash form still clears",
            "@thrillhousebot resolved src/A.java:1 — SQL injection",
            true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("prefixLocatorCases")
  void backstopShouldMatchWholeLocatorsOnly(String name, String body, boolean cleared) {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(1)));
    var held =
        analyzer.unreportedUnresolvedStatusesFromParsed(
            analyzer.parsePreviousResponses(List.of(FINDING_AT_LINE_ONE)),
            List.of(),
            List.of(),
            List.of(maintainerSays(body)),
            resolver,
            BOT_ID,
            Map.of());

    assertEquals(cleared ? List.of() : List.of(1), heldIds(held), name);
  }

  /** One low finding at src/A.java:1 whose title itself opens with a digit. */
  private static final String DIGIT_TITLED_FINDING_AT_LINE_ONE =
      """
      {"findings": [
        {"risk": "low", "file": "src/A.java", "line": 1,
         "title": "2 call sites of this SQL injection", "description": "raw query"}
      ]}
      """;

  private List<ReviewResult.PreviousFindingStatus> digitTitledBackstop(String body) {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(1)));
    return analyzer.unreportedUnresolvedStatusesFromParsed(
        analyzer.parsePreviousResponses(List.of(DIGIT_TITLED_FINDING_AT_LINE_ONE)),
        List.of(),
        List.of(),
        List.of(maintainerSays(body)),
        resolver,
        BOT_ID,
        Map.of());
  }

  @Test
  void aSeparatorLeadingTheFindingsOwnDigitTitleIsANamingNotARange() {
    // The exact form the summary prints: path:line — <title>, with a title opening with a digit.
    // The spaced-range guard alone reads it as a range; the clearing path holds the finding set,
    // so the title after the separator resolves the ambiguity toward the naming (#653).
    var held =
        digitTitledBackstop(
            "@thrillhousebot resolved src/A.java:1 — 2 call sites of this SQL injection");

    assertEquals(List.of(), heldIds(held), "the printed form must clear the finding it names");
  }

  @Test
  void aRangeWhoseEndIsNotTheTitleStaysARangeEvenWithTheTitleElsewhere() {
    var held =
        digitTitledBackstop(
            "@thrillhousebot resolved src/A.java:1 - 3 — 2 call sites of this SQL injection");

    assertEquals(
        List.of(1),
        heldIds(held),
        "a real range names no single finding, whatever else the comment mentions");
  }

  @Test
  void aSpacedHyphenRangeWhoseEndCollidesWithTheTitleStaysARange() {
    // The genuine-range spelling with an end line equal to the title's leading digits. Only the
    // em dash the summary prints may resolve toward the title; a spaced hyphen is how a range is
    // typed, and the printed form never uses it.
    var held =
        digitTitledBackstop(
            "@thrillhousebot resolved src/A.java:1 - 2 call sites of this SQL injection");

    assertEquals(
        List.of(1),
        heldIds(held),
        "a hyphen-spaced range must stay a range even when the full title happens to follow");
  }

  @Test
  void aShortenedTitleAfterTheEmDashStaysHeld() {
    var held = digitTitledBackstop("@thrillhousebot resolved src/A.java:1 — 2 call sites");

    assertEquals(
        List.of(1),
        heldIds(held),
        "only the full title exactly as printed is the summary's own row; a prefix is not");
  }

  @Test
  void aDottedRangeStaysARangeEvenWhenTheTitleFollowsIt() {
    var held =
        digitTitledBackstop(
            "@thrillhousebot resolved src/A.java:1..2 call sites of this SQL injection");

    assertEquals(
        List.of(1),
        heldIds(held),
        "the dotted spelling is git range syntax, never the printed separator");
  }

  private List<ReviewResponse.Finding> previousFindings() {
    return analyzer.parseResponse(PREVIOUS_JSON).findings();
  }

  private static List<ReviewResponse.PreviousFindingStatus> bothUnresolved() {
    return List.of(
        new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still present"),
        new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still present"));
  }

  @Test
  void clearNamedInConversationShouldCloseOnlyTheModelReportedFindingThatWasNamed() {
    var rewritten =
        analyzer.clearNamedInConversation(
            previousFindings(),
            bothUnresolved(),
            List.of(maintainerSays(CLEARS_FINDING_ONE)),
            BOT_ID);

    assertEquals(
        List.of("resolved", "unresolved"),
        rewritten.stream().map(ReviewResponse.PreviousFindingStatus::status).toList());
    assertEquals(FollowUpAnalyzer.conversationClearedNote(BOT_ID), rewritten.get(0).note());
    assertEquals("still present", rewritten.get(1).note(), "an unnamed finding keeps its note");
  }

  @Test
  void clearNamedInConversationShouldLeaveNonUnresolvedAndUnplaceableStatusesAlone() {
    var statuses =
        List.of(
            new ReviewResponse.PreviousFindingStatus(1, "justified", "intentional"),
            new ReviewResponse.PreviousFindingStatus(9, "unresolved", "out of range"),
            new ReviewResponse.PreviousFindingStatus(0, "unresolved", "out of range"));

    assertEquals(
        statuses,
        analyzer.clearNamedInConversation(
            previousFindings(), statuses, List.of(maintainerSays(CLEARS_FINDING_ONE)), BOT_ID),
        "only an in-range unresolved status may be rewritten by a conversation clear");
  }

  @Test
  void clearNamedInConversationShouldPassStatusesThroughWithNothingToMatchAgainst() {
    var statuses = bothUnresolved();
    var conversation = List.of(maintainerSays(CLEARS_FINDING_ONE));

    assertEquals(List.of(), analyzer.clearNamedInConversation(null, null, conversation, BOT_ID));
    assertEquals(
        List.of(),
        analyzer.clearNamedInConversation(previousFindings(), List.of(), conversation, BOT_ID));
    assertSame(
        statuses,
        analyzer.clearNamedInConversation(null, statuses, conversation, BOT_ID),
        "no prior round means no finding to name");
    assertSame(
        statuses, analyzer.clearNamedInConversation(List.of(), statuses, conversation, BOT_ID));
    assertSame(
        statuses, analyzer.clearNamedInConversation(previousFindings(), statuses, null, BOT_ID));
    assertSame(
        statuses,
        analyzer.clearNamedInConversation(previousFindings(), statuses, List.of(), BOT_ID));
  }

  @Test
  void clearNamedInConversationShouldHoldAFindingWithNoFile() {
    var json =
        "{\"findings\": [{\"risk\": \"low\", \"file\": null, \"line\": 10,"
            + " \"title\": \"SQL injection\", \"description\": \"raw query\"}]}";
    var statuses = List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still"));

    assertEquals(
        statuses,
        analyzer.clearNamedInConversation(
            analyzer.parseResponse(json).findings(),
            statuses,
            List.of(maintainerSays("@thrillhousebot resolved null:10 — SQL injection")),
            BOT_ID),
        "a finding that cannot be placed in a file cannot be named by a locator");
  }

  @Test
  void isClearDirectiveShouldRecognizeOnlyAnUnquotedResolvedInstruction() {
    assertTrue(
        FollowUpAnalyzer.isClearDirective(
            "@thrillhousebot resolved src/A.java:10 — title", BOT_ID));
    assertTrue(
        FollowUpAnalyzer.isClearDirective(
            "thanks!\n\n@ThrillhouseBot   RESOLVED all of it", BOT_ID));
    assertFalse(FollowUpAnalyzer.isClearDirective(null, BOT_ID));
    assertFalse(FollowUpAnalyzer.isClearDirective("@thrillhousebot why is this flagged?", BOT_ID));
    assertFalse(
        FollowUpAnalyzer.isClearDirective("@thrillhousebot resolve", BOT_ID),
        "the thread-resolving command must not be read as a finding clear");
    assertFalse(FollowUpAnalyzer.isClearDirective("> @thrillhousebot resolved", BOT_ID));
    assertFalse(FollowUpAnalyzer.isClearDirective("```\n@thrillhousebot resolved\n```", BOT_ID));
    assertFalse(
        FollowUpAnalyzer.isClearDirective(
            "write `@thrillhousebot resolved src/A.java:10 — title`", BOT_ID),
        "a marked-up directive is documentation, not an instruction");
    assertTrue(
        FollowUpAnalyzer.isClearDirective(
            "@thrillhousebot resolved `src/A.java:10` — title", BOT_ID),
        "a backticked locator is how the summary prints it and must still be a directive");
  }

  /**
   * A GitHub mention's {@code @} opens the comment or follows a non-word character; an email
   * address's local part never mentions the bot and must not clear anything. Whitespace between the
   * mention and the word admits Unicode space separators (pasted text carries non-breaking spaces),
   * while the interrogative guard also skips format characters, so no invisible character turns a
   * question into a clearing order.
   */
  @Test
  void isClearDirectiveShouldAnchorTheMentionAndReadUnicodeWhitespace() {
    assertFalse(
        FollowUpAnalyzer.isClearDirective(
            "root@thrillhousebot resolved src/A.java:10 — title", BOT_ID),
        "an email local part is not a mention");
    assertTrue(
        FollowUpAnalyzer.isClearDirective(
            "(@thrillhousebot resolved src/A.java:10 — title)", BOT_ID),
        "punctuation before the @ is how a parenthesized mention is written");
    assertTrue(
        FollowUpAnalyzer.isClearDirective(
            "@thrillhousebot\u00A0resolved src/A.java:10 — title", BOT_ID),
        "a pasted non-breaking space still separates the mention from the word");
    assertFalse(
        FollowUpAnalyzer.isClearDirective("@thrillhousebot resolved\u00A0? src/A.java:10", BOT_ID),
        "a non-breaking space must not smuggle a question past the interrogative guard");
    assertFalse(
        FollowUpAnalyzer.isClearDirective("@thrillhousebot resolved\u200B? src/A.java:10", BOT_ID),
        "a zero-width character must not smuggle a question past the interrogative guard");
  }

  /**
   * The directive is built from the configured bot logins, not a hardcoded slug, so an install
   * whose GitHub App answers to another name still has a working clear directive (#679). The
   * interrogative guard and the case-insensitivity travel with the configured name, and the default
   * slug is a stranger's mention on such an install — it must clear nothing there.
   */
  @Test
  void isClearDirectiveShouldMatchTheConfiguredBotLoginNotAHardcodedSlug() {
    var custom = BotIdentity.of("my-review-bot[bot]");

    assertTrue(
        FollowUpAnalyzer.isClearDirective("@my-review-bot resolved src/A.java:10 — title", custom));
    assertTrue(
        FollowUpAnalyzer.isClearDirective("@My-Review-Bot RESOLVED src/A.java:10 — title", custom),
        "case-insensitivity must hold for a configured login too");
    assertFalse(
        FollowUpAnalyzer.isClearDirective("@my-review-bot resolved? src/A.java:10 — title", custom),
        "the interrogative guard travels with the configured name");
    assertFalse(
        FollowUpAnalyzer.isClearDirective("@thrillhousebot resolved src/A.java:10 — title", custom),
        "the default slug is not this install's bot");
    assertTrue(
        FollowUpAnalyzer.isClearDirective(
            "@thrillhouse-bot resolved src/A.java:10 — title", BotIdentity.from(null)),
        "the shipped alternate slug is a first-class mention under the default identity");
  }

  /**
   * The README, the PR description and any comment explaining the feature all show the directive
   * marked up. A rule that fired on those would let the project's own documentation close findings
   * — the over-clear direction — so quoting the directive must clear nothing, while the locator
   * stays matchable in backticks because that is the shape the summary prints.
   */
  static Stream<Arguments> documentedDirectiveCases() {
    return Stream.of(
        arguments(
            "inline-code directive with the naming inside it",
            "To close it, comment `@thrillhousebot resolved src/A.java:10 — SQL injection`.",
            false),
        arguments(
            "inline-code directive with the naming outside it",
            "Write `@thrillhousebot resolved` and then src/A.java:10 — SQL injection.",
            false),
        arguments(
            "double-backtick directive, as docs showing a span",
            "Use ``@thrillhousebot resolved`` for src/A.java:10 — SQL injection.",
            false),
        arguments(
            "fenced example",
            """
            Like this:

            ```
            @thrillhousebot resolved src/A.java:10 — SQL injection
            ```
            """,
            false),
        arguments(
            "a real directive naming a backticked locator",
            "@thrillhousebot resolved `src/A.java:10` — SQL injection",
            true),
        arguments(
            "a real directive naming a plain locator",
            "@thrillhousebot resolved src/A.java:10 — SQL injection",
            true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("documentedDirectiveCases")
  void backstopShouldSeparateUsingTheDirectiveFromQuotingIt(
      String name, String body, boolean clears) {
    assertEquals(
        clears ? List.of(2) : List.of(1, 2),
        heldIds(backstopWith(List.of(maintainerSays(body)))),
        name);
  }

  /**
   * Asking whether a finding was fixed is not deciding that it was. A word boundary holds before
   * {@code ?}, so the interrogative form quotes the finding well enough to name it and would
   * otherwise close the very finding the question is about — the over-clear direction.
   */
  static Stream<Arguments> interrogativeDirectiveCases() {
    return Stream.of(
        arguments(
            "question mark straight after the token",
            "@thrillhousebot resolved? `src/A.java:10` — SQL injection",
            false),
        arguments(
            "spaced question mark",
            "@thrillhousebot resolved ? `src/A.java:10` — SQL injection",
            false),
        arguments(
            "plain statement still clears",
            "@thrillhousebot resolved `src/A.java:10` — SQL injection",
            true),
        arguments(
            "a directive whose sentence ends in a question still clears",
            "@thrillhousebot resolved `src/A.java:10` — SQL injection, fixed in abc123, ok?",
            true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("interrogativeDirectiveCases")
  void backstopShouldNotReadAQuestionAsADirective(String name, String body, boolean clears) {
    assertEquals(
        clears ? List.of(2) : List.of(1, 2),
        heldIds(backstopWith(List.of(maintainerSays(body)))),
        name);
  }

  @Test
  void namesALocatorShouldProveOnlyThatNothingWasNamed() {
    assertTrue(FollowUpAnalyzer.namesALocator("@thrillhousebot resolved src/A.java:10 — title"));
    assertTrue(
        FollowUpAnalyzer.namesALocator("@thrillhousebot resolved `src/A.java:10` — title"),
        "the summary prints the locator in backticks, which is what maintainers copy");
    assertFalse(FollowUpAnalyzer.namesALocator(null));
    assertFalse(
        FollowUpAnalyzer.namesALocator("@thrillhousebot resolved the null check thing"),
        "no path:line means no finding can be named, whatever the round holds");
    assertFalse(
        FollowUpAnalyzer.namesALocator("> @thrillhousebot resolved src/A.java:10 — title"),
        "a quoted locator names nothing, matching where the clear actually looks");
  }

  @Test
  void namesALocatorShouldReadTheSameShapeOnColonHeavyText() {
    // The shape test is written as a lookbehind so an arbitrarily long comment body cannot drive
    // backtracking. These are the inputs where that rewrite could have drifted from the plain
    // "non-space, colon, digit" reading it replaces.
    assertTrue(
        FollowUpAnalyzer.namesALocator("resolved C:/work/src/A.java:10"),
        "a colon earlier in the token does not hide the one before the line number");
    assertTrue(
        FollowUpAnalyzer.namesALocator("resolved src/A.java::10"),
        "a doubled colon still leaves a colon with a non-space before it and a digit after");
    assertFalse(
        FollowUpAnalyzer.namesALocator("resolved :10"),
        "a colon opening a token has no path in front of it, so it names nothing");
    assertFalse(
        FollowUpAnalyzer.namesALocator("resolved src/A.java: 10"),
        "a space between the colon and the number is not a locator");
    assertFalse(
        FollowUpAnalyzer.namesALocator("a:".repeat(2000)),
        "colon-heavy text with no line number still resolves to no locator");
  }

  /**
   * The ack this feeds must not promise a closure the clearing path refuses, so the forms the
   * whole-locator guard rejects have to read as naming nothing here too.
   */
  @Test
  void namesALocatorShouldRejectTheFormsTheClearingPathRefuses() {
    assertFalse(
        FollowUpAnalyzer.namesALocator("@thrillhousebot resolved `src/A.java:1-3` — title"),
        "a range names no single finding, and the clearing path will not clear it");
    assertFalse(
        FollowUpAnalyzer.namesALocator("@thrillhousebot resolved `src/A.java:1 - 3` — title"),
        "the spaced range is the same naming act as the adjacent one");
    assertFalse(
        FollowUpAnalyzer.namesALocator("@thrillhousebot resolved `src/A.java:1x` — title"),
        "a continued line number is not a locator the clear will match");
    assertTrue(
        FollowUpAnalyzer.namesALocator(
            "@thrillhousebot resolved `src/A.java:1-3` and `src/B.java:7`"),
        "one whole locator among rejected forms is still a naming");
    assertTrue(
        FollowUpAnalyzer.namesALocator("@thrillhousebot resolved SQL injection at src/A.java:10"),
        "a locator ending the comment names it");
    assertFalse(
        FollowUpAnalyzer.namesALocator("@thrillhousebot resolved src/A.java:1２"),
        "a full-width digit continues the token on the clearing side, so it must here too");
    assertFalse(
        FollowUpAnalyzer.namesALocator("@thrillhousebot resolved src/A.java:1\u200B-3"),
        "a format character continues the token on the clearing side, so it must here too");
  }

  @Test
  void namesOnlyAmbiguousRangesShouldFlagExactlyTheShapeTheAckCannotJudge() {
    assertTrue(
        FollowUpAnalyzer.namesOnlyAmbiguousRanges(
            "@thrillhousebot resolved src/A.java:1 — 2 call sites of this SQL injection"),
        "a digit-leading title is spelled exactly like a spaced range, and only the clearing"
            + " path can tell them apart");
    assertTrue(
        FollowUpAnalyzer.namesOnlyAmbiguousRanges(
            "@thrillhousebot resolved `src/A.java:1 - 3` — title"),
        "a real spaced range is the same shape; the ack cannot distinguish it either");
    assertFalse(FollowUpAnalyzer.namesOnlyAmbiguousRanges(null));
    assertFalse(
        FollowUpAnalyzer.namesOnlyAmbiguousRanges("@thrillhousebot resolved src/A.java:10 — title"),
        "a whole locator is a naming, not an ambiguity");
    assertFalse(
        FollowUpAnalyzer.namesOnlyAmbiguousRanges(
            "@thrillhousebot resolved `src/A.java:1 - 3` and `src/B.java:7`"),
        "a whole locator beside a range makes the naming ack the right one");
    assertFalse(
        FollowUpAnalyzer.namesOnlyAmbiguousRanges("@thrillhousebot resolved the null check thing"),
        "no locator shape at all is the no-locator case, not an ambiguity");
    assertFalse(
        FollowUpAnalyzer.namesOnlyAmbiguousRanges("@thrillhousebot resolved `src/A.java:1-3`"),
        "an adjacent range is a continued token, which no title spelling produces");
  }
}
