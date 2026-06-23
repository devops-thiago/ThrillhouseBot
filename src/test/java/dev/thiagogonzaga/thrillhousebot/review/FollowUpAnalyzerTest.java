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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FollowUpAnalyzerTest {

  private final FollowUpAnalyzer analyzer = new FollowUpAnalyzer(new ObjectMapper());

  private static final String BOT = "thrillhousebot";

  // The bot's identity for analyzer calls; comment(...) authors keep using the raw BOT login
  // string.
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
    return new GitHubReviewClient.PullRequestComment(
        id, inReplyTo, path, body, new GitHubReviewClient.ReviewResponse.User(author));
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

    // Null bodies and null users never match as roots; null-user replies render as @unknown
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
    // Two findings with identical titles on the same file — only the marker can tell them apart
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
    // The model lengthened the path between rounds; the same defect must still be suppressed
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
    // Real corpus shape: lines drifted 29 and the title was fully reworded, but the
    // title+description substance overlaps strongly
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
    // A different defect in the same file with modest token overlap must still post
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
    // Regression (#214): severity must not be part of finding identity. A different defect a couple
    // of lines from a replied prior finding of the same severity must still post — not be dropped
    // as
    // a re-raise just because the severities match.
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
    // The same title exists once per round; the latest round's findings must bind to the
    // newest thread, not the first one posted
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
    // The reply sits on the OLDER same-title thread; the newest one has none. Suppression
    // must still find it.
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
    // Bot-only reply, no reply at all, and no matching root comment — none may suppress
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
  void dropRepliedDuplicatesShouldDropEscalatedReRaiseWithSimilarTitle() {
    // The dogfooding loophole: re-raising an answered medium finding as high slipped the
    // severity-equality check. A similar title at the same location now counts as the same
    // finding regardless of severity.
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
    // The answered finding lives two rounds back; the latest round's response doesn't contain
    // it. With one round of memory it would be rediscovered — the full history must match.
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

    // The same round passed twice must not duplicate entries
    var context =
        analyzer.buildPreviousFindingsContext(
            PREVIOUS_JSON, List.of(), comments, List.of(olderJson, olderJson), BOT_ID);

    assertTrue(context.contains("Answered in earlier rounds"));
    assertTrue(context.contains("src/C.java:7 — Scan does not gate"));
    assertTrue(context.contains("- @maintainer: Report-only by design."));
    assertFalse(context.contains("Unanswered nit"));
    assertEquals(context.indexOf("Scan does not gate"), context.lastIndexOf("Scan does not gate"));
    // The numbered previous-round findings stay present ahead of the answered list
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

    // No structured previous response and no prior reviews: only the answered list renders
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
                // Same spot but an unrelated title — a different defect must post
                new ReviewResponse.Finding(
                    "critical", "high", "src/B.java", 5, "Injection here", "d", null, null),
                // Same title but far away
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 50, "Missing null check", "d", null, null),
                // Same title and line but different file
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
                // Location/severity match but the only reply has no user — not human engagement
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 5, "Missing null check", "d", null, null),
                // Matches prior #1's location/severity but that finding has no root comment
                new ReviewResponse.Finding(
                    "critical", "high", "src/A.java", 11, "SQL injection again", "d", null, null),
                // Null file can never match a location
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
  void unresolvedFindingsShouldReturnEmptyForMissingInputs() {
    var unresolvedStatus = List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "x"));

    assertTrue(analyzer.unresolvedFindings(PREVIOUS_JSON, null).isEmpty());
    assertTrue(analyzer.unresolvedFindings(PREVIOUS_JSON, List.of()).isEmpty());
    assertTrue(analyzer.unresolvedFindings(null, unresolvedStatus).isEmpty());
    assertTrue(analyzer.unresolvedFindings("not json", unresolvedStatus).isEmpty());
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

    // Both findings carry a status, one resolved and one justified, so nothing is held.
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

    // Finding one is reported and the existing gate already holds it; finding two is omitted, so
    // only finding two is the silent drop the backstop reconstructs. Pins the 1-based id mapping.
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

    // a status outside the contract's resolved/justified/unresolved vocabulary does NOT count
    // as the model accounting for the finding. Finding #1 is still open in the diff, so each junk
    // value must fall through to the backstop and be held — otherwise it would escape both the
    // backstop (id present) and the unresolved gate (value != "unresolved").
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
      // #2 carries a recognized status and is accounted for; only #1 (junk status) is held.
      assertEquals(
          List.of(1), heldIds(held), "status \"" + junk + "\" must not suppress the backstop");
    }
  }

  @Test
  void unreportedUnresolvedShouldRecognizeStatusesCaseInsensitively() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    // Recognized statuses are matched case-insensitively, so upper/mixed case still accounts for a
    // finding and nothing is held — the model-reported "unresolved" stays held by the gate, not
    // double-counted here.
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

    // Finding #1's thread carries a maintainer reply → defer to the human; only #2 is held.
    assertEquals(List.of(2), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldSeeMaintainerReplyOnNullTitleFindingViaMarker() {
    // a prior finding persisted with title == null. Its inline thread carries the hidden
    // finding marker and the finding's description (the bot embeds both in every comment), so the
    // maintainer reply must be seen even though the title-only rootCommentsByTitle returns nothing
    // for a null title. Without the marker-keyed lookup the backstop would hold this finding every
    // round with no way for the human to clear it.
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
        // a null-title finding's own thread exists (marker + description locate it) but
        // has
        // no human reply, so the hold stands — the marker path clears only on an actual reply, not
        // on the mere existence of the thread.
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
        // over-clear guard: the still-present null-title finding gives no title key; an
        // EARLIER
        // round reused marker index 1 for a DIFFERENT, answered finding. Matching the finding's own
        // description (not the recurring marker) keeps the hold — the silent
        // approve-over-open.
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
        // over-clear guard. The thread-less finding, summary-only that round, is still
        // present, but an earlier round reused marker index 1 for a different answered finding, so
        // requiring the marked comment to carry this finding's own title keeps the hold.
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
        // dogfood: a short title ("NPE") is a substring of an earlier answered finding's title
        // ("NPE in handler"). Header-framing anchoring (" — NPE**" != " — NPE in handler**") plus
        // the still-present marker=1 comment block the title-only fallback, so the hold stands.
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
        // no-content-key path: a blank title and no description yield no content key, so the
        // marker alone cannot safely identify the thread; the backstop holds the still-present
        // finding (the safe direction) rather than risk binding to a marker-index collision.
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
        // no-content-key path: both title and description blank → no content key, so the
        // backstop holds the still-present finding (the safe direction).
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
    // two same-title findings in the round. The maintainer replied only on finding #2's
    // marked thread; the marker keeps the reply bound to #2, so #1 is still held and #2 is cleared
    // — a title-only check could not tell the two threads apart.
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
    // over-clear guard: a null-title finding's own marked thread exists, but its only reply is
    // the bot itself. The null title forces resolution through the marker-keyed branch (the
    // title-only path cannot see a null-title thread at all), and hasHumanReply must exclude the
    // bot
    // there, so the hold stands. Removing the hasHumanReply check on that branch would flip this to
    // a clear.
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
    // safe-direction lock: a null-title finding with neither a marker thread nor a
    // title-matchable thread. markerRootComment returns null and the title-only fallback finds
    // nothing (rootCommentsByTitle is empty for a null title), so the still-present finding is
    // held.
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

    // src/B.java is absent from this round's diff → finding #2's code is gone; only #1 is held.
    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(PREVIOUS_JSON), List.of(), List.of(), resolver, BOT_ID);

    assertEquals(List.of(1), heldIds(held));
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
    // Null statuses ⇒ the model reported nothing ⇒ every present finding is a silent drop.
    assertEquals(
        2,
        analyzer
            .unreportedUnresolvedStatuses(List.of(PREVIOUS_JSON), null, List.of(), resolver, BOT_ID)
            .size());
  }

  @Test
  void unreportedUnresolvedShouldHoldSilentlyDroppedFindingFromAnOlderRound() {
    // round 1 raised A.java:10; the newest prior round raised B.java:5. The current round
    // marks the newest round's B resolved but never accounts for A (it is no longer a *numbered*
    // previous finding). The backstop must still reconstruct A from the older round.
    var prior = List.of(roundJson("src/B.java", 5, "B finding"), roundJson("src/A.java", 10, "A"));
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            prior,
            List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed B")),
            List.of(),
            resolver,
            BOT_ID);

    // B is cleared by the current round; only the older, silently dropped A is held.
    assertEquals(1, held.size());
    assertTrue(held.stream().allMatch(s -> "unresolved".equals(s.status())));
  }

  @Test
  void unreportedUnresolvedShouldNotHoldFindingResolvedInAnIntermediateRound() {
    // Regression guard for the widened scope: round 1 raised A.java:10; the newest prior round
    // marked it resolved (previous_findings_status id 1 → A) and raised B.java:5. The current round
    // drops everything. A was legitimately addressed, so only B is held — never re-block A.
    var prior =
        List.of(
            roundJson("src/B.java", 5, "B finding", "resolved"), roundJson("src/A.java", 10, "A"));
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held = analyzer.unreportedUnresolvedStatuses(prior, List.of(), List.of(), resolver, BOT_ID);

    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldReHoldAResolvedFindingOnlyWhenReRaisedAndStillPresent() {
    // A finding resolved in round 2 but RE-RAISED in round 3 is re-opened: a re-raise means the
    // model flagged it again, so the round-2 closure is intentionally superseded. The hold is
    // downgrade-only and, crucially, gated by presence — it fires only because the suggestion_old
    // anchor is still in the diff (the resolution did not actually remove the code). Had the code
    // been fixed, the anchor would be gone and isFindingPresent would drop it.
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

    // newest-first [round3, round2, round1]; the current round silently drops the re-raised
    // finding.
    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(round3, round2, round1), List.of(), List.of(), resolver, BOT_ID);

    // Re-raised and the flagged code still present → held (safe, downgrade-only).
    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldNotHoldFindingJustifiedInAnIntermediateRound() {
    // A "justified" verdict closes a finding just like "resolved": round 1 raised A, the newest
    // prior round justified it and raised B, the current round drops everything → only B is held.
    var prior =
        List.of(
            roundJson("src/B.java", 5, "B finding", "justified"), roundJson("src/A.java", 10, "A"));
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held = analyzer.unreportedUnresolvedStatuses(prior, List.of(), List.of(), resolver, BOT_ID);

    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldHoldOlderFindingOnlyMarkedUnresolvedThenDropped() {
    // An intermediate "unresolved" verdict does NOT close a finding (only resolved/justified do):
    // round 1 raised A; the newest prior round marked A unresolved and raised B; the current round
    // resolves B and drops A. A is still open and must be held.
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
    // The same finding (same file + line + title) raised in two rounds is held once, not twice.
    var prior = List.of(roundJson("src/A.java", 10, "A"), roundJson("src/A.java", 10, "A"));
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));

    var held = analyzer.unreportedUnresolvedStatuses(prior, List.of(), List.of(), resolver, BOT_ID);

    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldHoldDistinctSameTitleFindingsAtDifferentLines() {
    // Two genuinely-distinct anchorless findings share a file and title but sit at different lines.
    // The dedup key discriminates anchorless findings by line, so neither evicts the other — both
    // silent drops are held (a missed hold would let APPROVE sail over one of them).
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
    // Two genuinely-distinct findings share a file, title, AND a generic suggestion_old anchor but
    // sit at different lines. The dedup key is keyed on the LINE (not the anchor), so they never
    // collapse — both silent drops are held. Collapsing them (keying on the anchor and dropping the
    // line) would drop one and let APPROVE sail over a still-open finding — the missed hold.
    // The accepted price is that a single finding re-raised at a drifted line counts twice; the two
    // cases present the identical signal, and an over-count is the safe direction, a missed hold is
    // not.
    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(SHARED_ANCHOR_PAIR_JSON), List.of(), List.of(), sharedAnchorResolver(), BOT_ID);

    assertEquals(List.of(1, 2), heldIds(held));
    assertTrue(held.stream().allMatch(s -> "unresolved".equals(s.status())));
  }

  @Test
  void unreportedUnresolvedShouldResolveOnlyTheReferencedFindingWhenAnAnchorIsShared() {
    // Same shared-anchor pair, but the current round resolves finding #1 (line 10). Because each
    // finding has its own line-keyed identity, the resolution removes only #1 — the distinct #2
    // (line 90) stays held. A key that collapsed the pair on the anchor would let resolving #1
    // evict
    // #2, dropping a still-open finding.
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
    // an OLDER round's finding whose suggestion_old anchor was deleted (it lives only
    // on the diff's left side) must not be held by the multi-round replay, even though its stale
    // line still sits within tolerance of surviving context — the raw-line proxy would over-block.
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

    // A's deleted anchor is gone from the right side → not held; only the still-present B is held.
    assertEquals(1, held.size());
  }

  @Test
  void unreportedUnresolvedShouldStillHoldFindingGivenAnUnrecognizedCurrentRoundVerdict() {
    // The backstop must not trust the model's vocabulary: a non-standard current-round verdict
    // ("pending") does NOT account for a still-open finding, so it stays held — otherwise a
    // malformed status would sneak a silent drop past the APPROVE gate (the hole).
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(PREVIOUS_JSON),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "pending", "in progress")),
            List.of(),
            resolver,
            BOT_ID);

    // Finding #1 carries an unrecognized verdict and is still held alongside the unreported #2.
    assertEquals(List.of(1, 2), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHandleFindingsMissingFileOrTitleAndOutOfRangeStatus() {
    // A finding without a file cannot be placed in the diff → never keyed or held; a finding
    // without a title falls back to its line for identity; a status id outside the round's range
    // (and one pointing at the un-keyable null-file finding) is ignored without error.
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

    // Only the null-title (C) and titled (D) findings survive; the null-file one is dropped.
    assertEquals(2, held.size());
    assertTrue(held.stream().allMatch(s -> "unresolved".equals(s.status())));
  }

  @Test
  void unreportedUnresolvedShouldHoldStillOpenFindingThatDriftedBeyondTolerance() {
    // a force-push moved the still-open code from old line 10 to line 32. The raw
    // line-number proxy would drop it (re-opening); the suggestion_old anchor still matches.
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
    // line 42's bug was deleted, but GitHub still emits surrounding context lines, so the
    // raw line-number proxy is fooled into holding. The deleted anchor is gone from the right side.
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

    // Stale line 42 is within ±3 of the surviving context line now at right-line 41.
    assertTrue(resolver.isLineInDiff("src/A.java", 42, 3)); // raw proxy would over-block here
    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(json), List.of(), List.of(), resolver, BOT_ID);

    assertTrue(held.isEmpty());
  }

  @Test
  void unreportedUnresolvedShouldHoldFindingWhoseExactDiffKeyIsEmptyButVariantCarriesIt() {
    // end-to-end: the finding's own file is in the diff as a deletion-only patch, so its
    // exact key has an empty right side; the flagged code actually survives under a longer path
    // variant. The empty exact entry must not mask the variant, or the backstop would silently
    // approve over a still-open finding.
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
    // No description line is emitted when the finding has none
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
    // an unrecognized status is meaningless, and for a still-open finding the backstop
    // already emits a synthetic "unresolved" for that id — passing the raw value through too would
    // leave two entries with the same id in previousStatuses. Only recognized values survive,
    // case-insensitively.
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
    // A finding raised in round 1 and re-raised in round 2 (with line/path drift)
    // is held once, not twice, if both are dropped (unreported) in the current round.
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
    // Both anchors are present in the current diff.
    var resolver =
        new DiffLineResolver(
            Map.of(
                "src/A.java",
                "@@ -10,1 +10,1 @@\n-old1\n+query = \"SELECT * FROM users WHERE id = \" + id;\n@@ -32,1 +32,1 @@\n-old2\n+query = \"SELECT * FROM users WHERE id = \" + id;\n"));

    var held =
        analyzer.unreportedUnresolvedStatuses(
            List.of(round2, round1), List.of(), List.of(), resolver, BOT_ID);

    // Only one status is held (deduplicated).
    assertEquals(1, held.size());
    assertEquals("unresolved", held.get(0).status());
  }

  @Test
  void unreportedUnresolvedShouldExcludeDriftedReRaisedFindingIfAnyMemberHasReply() {
    // If a finding was re-raised with drift, but one of the copies has a maintainer reply,
    // the entire cluster is considered replied to and is not held.
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

    // One of the cluster members has a reply → entire cluster is considered replied → empty held
    // list.
    assertTrue(held.isEmpty());
  }
}
