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
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FollowUpAnalyzerTest {

  private final FollowUpAnalyzer analyzer = new FollowUpAnalyzer(new ObjectMapper());

  private static final String BOT = "thrillhousebot";

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

    var context = analyzer.buildPreviousFindingsContext(PREVIOUS_JSON, List.of(), comments, BOT);

    assertTrue(context.contains("Thread replies:"));
    assertTrue(context.contains("- @maintainer: Fixed in abc123"));
    assertTrue(context.contains("- @thrillhousebot: Confirmed"));
  }

  @Test
  void contextShouldOmitRepliesSectionWhenThreadHasNone() {
    var comments = List.of(comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT));

    var context = analyzer.buildPreviousFindingsContext(PREVIOUS_JSON, List.of(), comments, BOT);

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

    var context = analyzer.buildPreviousFindingsContext(PREVIOUS_JSON, List.of(), comments, BOT);

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

    var context = analyzer.buildPreviousFindingsContext(json, List.of(), comments, BOT);

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

    var threads = analyzer.matchFindingThreads(PREVIOUS_JSON, comments, BOT);

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

    var threads = analyzer.matchFindingThreads(json, comments, BOT);

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

    assertSame(withFinding, analyzer.dropRepliedDuplicates(withFinding, null, comments, BOT));
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

    var threads = analyzer.matchFindingThreads(json, comments, BOT);

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

    var result = analyzer.dropRepliedDuplicates(reRaised, List.of(PREVIOUS_JSON), comments, BOT);

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

    var result = analyzer.dropRepliedDuplicates(paraphrased, List.of(priorJson), comments, BOT);

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
        distinct, analyzer.dropRepliedDuplicates(distinct, List.of(priorJson), comments, BOT));
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

    var threads = analyzer.matchFindingThreads(json, comments, BOT);

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

    var threads = analyzer.matchFindingThreads(json, comments, BOT);

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

    var result = analyzer.dropRepliedDuplicates(reRaised, List.of(PREVIOUS_JSON), comments, BOT);

    assertTrue(result.findings().isEmpty());
  }

  @Test
  void matchFindingThreadsShouldSkipFindingsWithoutMatchingComment() {
    var comments = List.of(comment(100L, null, "src/A.java", "**CRITICAL — SQL injection**", BOT));

    var threads = analyzer.matchFindingThreads(PREVIOUS_JSON, comments, BOT);

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
                    "medium", "high", "src/B.java", 7, "Null check still absent", "d", null, null),
                new ReviewResponse.Finding(
                    "high", "high", "src/C.java", 30, "Genuinely new bug", "d", null, null)),
            List.of(),
            new ReviewResponse.Summary(2, 0, 1, 1, 0, "assessment", "purpose", List.of()));

    var result = analyzer.dropRepliedDuplicates(reRaised, List.of(PREVIOUS_JSON), comments, BOT);

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
        reRaised, analyzer.dropRepliedDuplicates(reRaised, List.of(PREVIOUS_JSON), comments, BOT));
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

    var result = analyzer.dropRepliedDuplicates(escalated, List.of(PREVIOUS_JSON), comments, BOT);

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
            reRaised, List.of(latestRoundJson, PREVIOUS_JSON), comments, BOT);

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
            PREVIOUS_JSON, List.of(), comments, List.of(olderJson, olderJson), BOT);

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
        analyzer.buildPreviousFindingsContext(PREVIOUS_JSON, List.of(), List.of(), List.of(), BOT);

    assertFalse(context.contains("Answered in earlier rounds"));

    var nullOlder =
        analyzer.buildPreviousFindingsContext(PREVIOUS_JSON, List.of(), List.of(), null, BOT);
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
        analyzer.buildPreviousFindingsContext(null, List.of(), comments, List.of(olderJson), BOT);

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
            PREVIOUS_JSON, List.of(), comments, List.of(olderJson), BOT);

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
        fileless, analyzer.dropRepliedDuplicates(fileless, List.of(PREVIOUS_JSON), comments, BOT));
  }

  @Test
  void dropRepliedDuplicatesShouldRequireSameLocationAndSeverity() {
    var comments =
        List.of(
            comment(100L, null, "src/B.java", "**MEDIUM — Missing null check**", BOT),
            comment(101L, 100L, "src/B.java", "Declining.", "maintainer"));
    var different =
        new ReviewResponse(
            List.of(
                // Same spot but different severity — a genuinely worse issue must post
                new ReviewResponse.Finding(
                    "critical", "high", "src/B.java", 5, "Injection here", "d", null, null),
                // Same severity but far away
                new ReviewResponse.Finding(
                    "medium", "high", "src/B.java", 50, "Missing null check", "d", null, null),
                // Same severity and line but different file
                new ReviewResponse.Finding(
                    "medium", "high", "src/Z.java", 5, "Missing null check", "d", null, null)),
            List.of(),
            null);

    assertSame(
        different,
        analyzer.dropRepliedDuplicates(different, List.of(PREVIOUS_JSON), comments, BOT));
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
        response, analyzer.dropRepliedDuplicates(response, List.of(PREVIOUS_JSON), comments, BOT));
  }

  @Test
  void dropRepliedDuplicatesShouldSkipTriviallyEmptyInputs() {
    var finding = new ReviewResponse.Finding("low", "high", "f", 1, "t", "d", null, null);
    var withFinding = new ReviewResponse(List.of(finding), List.of(), null);
    var noFindings = new ReviewResponse(List.of(), List.of(), null);
    var comments = List.of(comment(1L, null, "f", "x", BOT));

    assertSame(
        noFindings,
        analyzer.dropRepliedDuplicates(noFindings, List.of(PREVIOUS_JSON), comments, BOT));
    assertSame(
        withFinding,
        analyzer.dropRepliedDuplicates(withFinding, List.of(PREVIOUS_JSON), List.of(), BOT));
    assertSame(withFinding, analyzer.dropRepliedDuplicates(withFinding, List.of(), comments, BOT));
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

  @Test
  void unreportedUnresolvedShouldHoldSilentlyDroppedFindingsStillInDiff() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    var held =
        analyzer.unreportedUnresolvedStatuses(PREVIOUS_JSON, List.of(), List.of(), resolver, BOT);

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
                PREVIOUS_JSON,
                List.of(
                    new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"),
                    new ReviewResponse.PreviousFindingStatus(2, "justified", "intentional")),
                List.of(),
                resolver,
                BOT)
            .isEmpty());

    // Finding one is reported and the existing gate already holds it; finding two is omitted, so
    // only finding two is the silent drop the backstop reconstructs. Pins the 1-based id mapping.
    var held =
        analyzer.unreportedUnresolvedStatuses(
            PREVIOUS_JSON,
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still")),
            List.of(),
            resolver,
            BOT);
    assertEquals(List.of(2), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHoldFindingsWithUnrecognizedStatus() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    // #131: a status outside the contract's resolved/justified/unresolved vocabulary does NOT count
    // as the model accounting for the finding. Finding #1 is still open in the diff, so each junk
    // value must fall through to the backstop and be held — otherwise it would escape both the
    // backstop (id present) and the unresolved gate (value != "unresolved").
    for (String junk : new String[] {"wontfix", "open", "RESOLVE", "", null}) {
      var held =
          analyzer.unreportedUnresolvedStatuses(
              PREVIOUS_JSON,
              List.of(
                  new ReviewResponse.PreviousFindingStatus(1, junk, "?"),
                  new ReviewResponse.PreviousFindingStatus(2, "resolved", "fixed")),
              List.of(),
              resolver,
              BOT);
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
                PREVIOUS_JSON,
                List.of(
                    new ReviewResponse.PreviousFindingStatus(1, "RESOLVED", "fixed"),
                    new ReviewResponse.PreviousFindingStatus(2, "Justified", "intentional")),
                List.of(),
                resolver,
                BOT)
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
        analyzer.unreportedUnresolvedStatuses(PREVIOUS_JSON, List.of(), comments, resolver, BOT);

    // Finding #1's thread carries a maintainer reply → defer to the human; only #2 is held.
    assertEquals(List.of(2), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldSeeMaintainerReplyOnNullTitleFindingViaMarker() {
    // #133(b): a prior finding persisted with title == null. Its inline thread carries the hidden
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

    var held = analyzer.unreportedUnresolvedStatuses(json, List.of(), comments, resolver, BOT);

    assertTrue(
        held.isEmpty(),
        "a maintainer reply on a null-title finding's marked thread must clear the hold");
  }

  @Test
  void unreportedUnresolvedShouldStillHoldNullTitleFindingWhenThreadHasNoReply() {
    // #133(b): the marker plus the finding's description locate the null-title finding's thread,
    // but
    // with no human reply on it the hold stands. Pins that the marker path clears the hold only on
    // an actual reply, not on the mere existence of the thread.
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
                BOT));

    var held = analyzer.unreportedUnresolvedStatuses(json, List.of(), comments, resolver, BOT);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHoldNullTitleFindingWhenEarlierRoundReusedItsMarkerIndex() {
    // #133 over-clear guard for null-title findings: the newest round's finding #1 has title ==
    // null
    // and was summary-only that round (no thread of its own); its code is still present. An
    // EARLIER,
    // unrelated round posted a DIFFERENT finding at the same marker index 1 on the same file that a
    // maintainer answered. The marker index recurs every round and a null title gives no title key,
    // so without content correspondence the marker would bind to that earlier answered thread and
    // clear a still-open finding — the #118 silent approve-over-open. Matching the finding's own
    // description keeps the hold.
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
                "**LOW — Naming nit**\n\nrename the local for clarity\n<!-- thrillhousebot:finding=1 -->",
                BOT),
            comment(101L, 100L, "src/A.java", "fine as-is", "maintainer"));

    var held = analyzer.unreportedUnresolvedStatuses(json, List.of(), comments, resolver, BOT);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldBindReplyToTheFindingsOwnMarkedThread() {
    // #133(b): two same-title findings in the round. The maintainer replied only on finding #2's
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

    var held = analyzer.unreportedUnresolvedStatuses(json, List.of(), comments, resolver, BOT);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHoldThreadlessFindingDespiteEarlierRoundReusingItsMarkerIndex() {
    // #133 over-clear guard: the newest prior round's finding #1 ("Use-after-free") was
    // summary-only
    // that round (its flagged line was outside the diff), so it has no thread of its own; its code
    // is still present now. An EARLIER, unrelated round posted a DIFFERENT finding at the same
    // marker index 1 on the same file ("Naming nit") that a maintainer answered. The marker index
    // recurs every round, so a marker-only lookup would bind to that earlier answered thread and
    // clear a still-open finding — re-introducing the #118 silent approve-over-open. Requiring the
    // marked comment to carry this finding's own title keeps the hold.
    var json =
        """
        {"findings": [
          {"risk": "high", "file": "src/A.java", "line": 10, "title": "Use-after-free",
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
                "**LOW — Naming nit**\n<!-- thrillhousebot:finding=1 -->",
                BOT),
            comment(101L, 100L, "src/A.java", "fine as-is", "maintainer"));

    var held = analyzer.unreportedUnresolvedStatuses(json, List.of(), comments, resolver, BOT);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHoldFindingWhenItsMarkedThreadHasOnlyABotReply() {
    // #133 over-clear guard: a null-title finding's own marked thread exists, but its only reply is
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

    var held = analyzer.unreportedUnresolvedStatuses(json, List.of(), comments, resolver, BOT);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldHoldNullTitleFindingWhenNoThreadExistsAtAll() {
    // #133(b) safe-direction lock: a null-title finding with neither a marker thread nor a
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

    var held = analyzer.unreportedUnresolvedStatuses(json, List.of(), List.of(), resolver, BOT);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldExcludeFindingsNotInCurrentDiff() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10)));

    // src/B.java is absent from this round's diff → finding #2's code is gone; only #1 is held.
    var held =
        analyzer.unreportedUnresolvedStatuses(PREVIOUS_JSON, List.of(), List.of(), resolver, BOT);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldReturnEmptyForMissingInputs() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", patch(10), "src/B.java", patch(5)));

    assertTrue(
        analyzer.unreportedUnresolvedStatuses(null, List.of(), List.of(), resolver, BOT).isEmpty());
    assertTrue(
        analyzer
            .unreportedUnresolvedStatuses("not json", List.of(), List.of(), resolver, BOT)
            .isEmpty());
    assertTrue(
        analyzer
            .unreportedUnresolvedStatuses(PREVIOUS_JSON, List.of(), List.of(), null, BOT)
            .isEmpty());
    // Null statuses ⇒ the model reported nothing ⇒ every present finding is a silent drop.
    assertEquals(
        2,
        analyzer
            .unreportedUnresolvedStatuses(PREVIOUS_JSON, null, List.of(), resolver, BOT)
            .size());
  }

  @Test
  void unreportedUnresolvedShouldHoldStillOpenFindingThatDriftedBeyondTolerance() {
    // #129(a): a force-push moved the still-open code from old line 10 to line 32. The raw
    // line-number proxy would drop it (re-opening #118); the suggestion_old anchor still matches.
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
    var held = analyzer.unreportedUnresolvedStatuses(json, List.of(), List.of(), resolver, BOT);

    assertEquals(List.of(1), heldIds(held));
  }

  @Test
  void unreportedUnresolvedShouldNotHoldFixedFindingWhoseContextSurvives() {
    // #129(b): line 42's bug was deleted, but GitHub still emits surrounding context lines, so the
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
    var held = analyzer.unreportedUnresolvedStatuses(json, List.of(), List.of(), resolver, BOT);

    assertTrue(held.isEmpty());
  }

  @Test
  void buildPreviousFindingsContextShouldReturnEmptyForNull() {
    assertEquals("", analyzer.buildPreviousFindingsContext(null, "thrillhousebot"));
  }

  @Test
  void buildPreviousFindingsContextShouldReturnEmptyForNullReviewBody() {
    var reviews =
        List.of(
            new GitHubReviewClient.ReviewResponse(
                1L,
                null,
                "COMMENTED",
                "abc",
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot")));

    assertEquals("", analyzer.buildPreviousFindingsContext(reviews, "thrillhousebot"));
  }

  @Test
  void buildPreviousFindingsContextShouldReturnEmptyForEmptyList() {
    assertEquals("", analyzer.buildPreviousFindingsContext(List.of(), "thrillhousebot"));
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

    assertEquals("", analyzer.buildPreviousFindingsContext(reviews, "thrillhousebot"));
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
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot")),
            new GitHubReviewClient.ReviewResponse(
                2L,
                "second review body",
                "REQUEST_CHANGES",
                "def",
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot")));

    var context = analyzer.buildPreviousFindingsContext(reviews, "thrillhousebot");

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

    var context = analyzer.buildPreviousFindingsContext(json, List.of(), "thrillhousebot");

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

    var context = analyzer.buildPreviousFindingsContext(json, List.of(), "thrillhousebot");

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
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot")));

    assertTrue(
        analyzer
            .buildPreviousFindingsContext(null, reviews, "thrillhousebot")
            .contains("body findings"));
    assertTrue(
        analyzer
            .buildPreviousFindingsContext("  ", reviews, "thrillhousebot")
            .contains("body findings"));
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
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot")));

    var context = analyzer.buildPreviousFindingsContext("{not json", reviews, "thrillhousebot");

    assertTrue(context.contains("body findings"));
  }

  @Test
  void structuredContextShouldFallBackWhenPreviousResponseHadNoFindings() {
    var json =
        """
        {"findings": [], "previous_findings_status": [], "summary": null}
        """;

    assertEquals("", analyzer.buildPreviousFindingsContext(json, List.of(), "thrillhousebot"));
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
    // #131: an unrecognized status is meaningless, and for a still-open finding the backstop
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
}
