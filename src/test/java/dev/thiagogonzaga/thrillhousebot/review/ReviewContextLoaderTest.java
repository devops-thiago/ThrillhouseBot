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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.github.*;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link ReviewContextLoader} — the read side of the review pipeline extracted from
 * {@code ReviewOrchestrator}. Carries the diff/base-comparison, PR-files, prior-reviews,
 * inline-comment, bot-summary-detection, resolve-missing-details and project-stack cases verbatim.
 */
class ReviewContextLoaderTest {

  private static final String BOT_LOGIN = "thrillhousebot[bot]";

  @Mock private GitHubPullRequestClient prClient;
  @Mock private GitHubReviewClient reviewClient;
  @Mock private GitHubCommentClient commentClient;
  @Mock private InstructionsResolver instructionsResolver;
  @Mock private ProjectStackResolver projectStackResolver;
  @Mock private PrLabeler labeler;
  @Mock private FollowUpAnalyzer followUpAnalyzer;
  @Mock private ReviewSessionPersistence sessionPersistence;
  @Mock private ActiveModelSettings activeModel;

  private ReviewContextLoader loader;
  private final ReviewDiffFormatter diffFormatter = new ReviewDiffFormatter(List.of(), 5000);

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    // Default: budgeting off so existing formatter-driven assertions keep the line-capped path.
    when(activeModel.maxInputTokens()).thenReturn(0);
    lenient().when(followUpAnalyzer.parsePreviousResponses(any())).thenReturn(List.of());
    loader =
        new ReviewContextLoader(
            prClient,
            reviewClient,
            commentClient,
            instructionsResolver,
            projectStackResolver,
            diffFormatter,
            labeler,
            followUpAnalyzer,
            sessionPersistence,
            BotIdentity.from(List.of(BOT_LOGIN)),
            activeModel);
  }

  @Nested
  class BuildDiffString {

    @Test
    void shouldReturnNoChangesForNullList() {
      var result = diffFormatter.buildDiffString(null);
      assertEquals("(no changes detected)", result);
    }

    @Test
    void shouldReturnNoChangesForEmptyList() {
      var result = diffFormatter.buildDiffString(Collections.emptyList());
      assertEquals("(no changes detected)", result);
    }

    @Test
    void shouldFormatSingleFileWithPatch() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "src/main/Foo.java",
                  "modified",
                  5,
                  3,
                  8,
                  "@@ -1,3 +1,5 @@\n unchanged\n+added line\n+another line"));

      var result = diffFormatter.buildDiffString(files);

      assertTrue(result.contains("## Overview: 1 files (+5 -3)"));
      assertTrue(result.contains("### src/main/Foo.java (modified, +5 -3)"));
      assertTrue(result.contains("```diff"));
      assertTrue(result.contains("+added line"));
    }

    @Test
    void shouldFormatSingleFileWithoutPatch() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff("binary-file.png", "modified", 0, 0, 0, null));

      var result = diffFormatter.buildDiffString(files);

      assertTrue(result.contains("binary-file.png (modified, +0 -0)"));
      assertFalse(result.contains("```diff"));
    }

    @Test
    void shouldAccumulateTotalsAcrossMultipleFiles() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff("a.java", "modified", 10, 2, 12, "@@ patch a"),
              new GitHubPullRequestClient.FileDiff("b.java", "added", 25, 0, 25, "@@ patch b"),
              new GitHubPullRequestClient.FileDiff("c.java", "modified", 3, 5, 8, null));

      var result = diffFormatter.buildDiffString(files);

      assertTrue(result.contains("## Overview: 3 files (+38 -7)"));
      assertTrue(result.contains("a.java"));
      assertTrue(result.contains("b.java"));
      assertTrue(result.contains("c.java"));
    }

    @Test
    void shouldHandleFilesWithZeroChanges() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff("renamed-only.txt", "renamed", 0, 0, 0, null));

      var result = diffFormatter.buildDiffString(files);

      assertTrue(result.contains("## Overview: 1 files (+0 -0)"));
      assertTrue(result.contains("renamed-only.txt (renamed, +0 -0)"));
    }
  }

  @Nested
  class BuildBaseComparison {

    @Test
    void shouldReturnSafeMessageForNullBase() {
      var result =
          loader.buildBaseComparisonWithStats("auth", "owner", "repo", null, "abcdefgh").text();
      assertEquals("(regression comparison unavailable — refs too short)", result);
    }

    @Test
    void shouldReturnSafeMessageForNullHead() {
      var result =
          loader.buildBaseComparisonWithStats("auth", "owner", "repo", "abcdefgh", null).text();
      assertEquals("(regression comparison unavailable — refs too short)", result);
    }

    @Test
    void shouldReturnSafeMessageForShortBaseSha() {
      var result =
          loader.buildBaseComparisonWithStats("auth", "owner", "repo", "abc", "abcdefgh").text();
      assertEquals("(regression comparison unavailable — refs too short)", result);
    }

    @Test
    void shouldReturnSafeMessageForShortHeadSha() {
      var result =
          loader.buildBaseComparisonWithStats("auth", "owner", "repo", "abcdefgh", "def").text();
      assertEquals("(regression comparison unavailable — refs too short)", result);
    }

    @Test
    void shouldReturnSafeMessageWhenBothNull() {
      var result = loader.buildBaseComparisonWithStats("auth", "owner", "repo", null, null).text();
      assertEquals("(regression comparison unavailable — refs too short)", result);
    }

    @Test
    void shouldReturnNoChangesMessageWhenComparisonHasEmptyFiles() {
      var emptyComparison = new GitHubPullRequestClient.CompareResponse(3, List.of());
      when(prClient.compareCommits(
              any(), any(), eq("owner"), eq("repo"), eq("abcdefgh"), eq("hijklmn")))
          .thenReturn(emptyComparison);

      var result =
          loader
              .buildBaseComparisonWithStats("Bearer tok", "owner", "repo", "abcdefgh", "hijklmn")
              .text();

      assertEquals("(no changes between abcdefg and hijklmn)", result);
    }

    @Test
    void shouldReturnNoChangesMessageWhenComparisonHasNullFiles() {
      var nullFilesComparison = new GitHubPullRequestClient.CompareResponse(0, null);
      when(prClient.compareCommits(
              any(), any(), eq("owner"), eq("repo"), eq("abcdefgh"), eq("hijklmn")))
          .thenReturn(nullFilesComparison);

      var result =
          loader
              .buildBaseComparisonWithStats("Bearer tok", "owner", "repo", "abcdefgh", "hijklmn")
              .text();

      assertEquals("(no changes between abcdefg and hijklmn)", result);
    }

    @Test
    void shouldBuildComparisonWithFiles() {
      var comparison =
          new GitHubPullRequestClient.CompareResponse(
              2,
              List.of(
                  new GitHubPullRequestClient.FileDiff(
                      "src/Bar.java", "modified", 3, 1, 4, "@@ -1 +1,3 @@\n-old\n+new\n+extra")));
      when(prClient.compareCommits(
              any(), any(), eq("owner"), eq("repo"), eq("abcdefgh"), eq("hijklmn")))
          .thenReturn(comparison);

      var result =
          loader
              .buildBaseComparisonWithStats("Bearer tok", "owner", "repo", "abcdefgh", "hijklmn")
              .text();

      assertTrue(result.contains("## Changes between base and head"));
      assertTrue(result.contains("abcdefg..hijklmn: 2"));
      assertTrue(result.contains("src/Bar.java"));
      assertTrue(result.contains("```diff"));
      assertTrue(result.contains("+new"));
    }

    @Test
    void shouldSkipFilesWithoutPatchInComparison() {
      var comparison =
          new GitHubPullRequestClient.CompareResponse(
              1,
              List.of(
                  new GitHubPullRequestClient.FileDiff("binary.bin", "modified", 0, 0, 0, null),
                  new GitHubPullRequestClient.FileDiff(
                      "src/Text.java", "modified", 1, 1, 2, "@@ patch")));
      when(prClient.compareCommits(
              any(), any(), eq("owner"), eq("repo"), eq("abcdefgh"), eq("hijklmn")))
          .thenReturn(comparison);

      var result =
          loader
              .buildBaseComparisonWithStats("Bearer tok", "owner", "repo", "abcdefgh", "hijklmn")
              .text();

      assertTrue(result.contains("src/Text.java"));
      assertFalse(result.contains("binary.bin"));
    }

    @Test
    void shouldReturnUnavailableOnException() {
      when(prClient.compareCommits(
              any(), any(), eq("owner"), eq("repo"), eq("abcdefgh"), eq("hijklmn")))
          .thenThrow(new RuntimeException("API down"));

      var result =
          loader
              .buildBaseComparisonWithStats("Bearer tok", "owner", "repo", "abcdefgh", "hijklmn")
              .text();

      assertEquals("(regression comparison unavailable)", result);
    }
  }

  @Nested
  class LoadWithTokenBudgeting {

    private static ReviewOrchestrator.ReviewRequest request() {
      return new ReviewOrchestrator.ReviewRequest(
          "owner",
          "repo",
          1,
          "headsha1",
          "Title",
          "body",
          "basesha1",
          "main",
          99L,
          true,
          "main",
          false);
    }

    private void stubCommonLoadDeps(List<GitHubPullRequestClient.FileDiff> files) {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(files);
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(
              new GitHubPullRequestClient.PullRequestDetails(
                  "Title",
                  "body",
                  new GitHubPullRequestClient.Ref("headsha1"),
                  new GitHubPullRequestClient.Ref("basesha1"),
                  2,
                  10,
                  3));
      when(prClient.compareCommits(
              any(), any(), eq("owner"), eq("repo"), eq("basesha1"), eq("headsha1")))
          .thenReturn(
              new GitHubPullRequestClient.CompareResponse(
                  1,
                  List.of(
                      new GitHubPullRequestClient.FileDiff(
                          "a.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+a"),
                      new GitHubPullRequestClient.FileDiff(
                          "b.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+b"))));
      when(reviewClient.listReviews(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(List.of());
      when(commentClient.listComments(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(List.of());
      when(sessionPersistence.findAllPriorAiResponseJsons(any(), eq(1), anyLong()))
          .thenReturn(List.of());
      when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
          .thenReturn(new InstructionsResolver.ResolvedInstructions("", ""));
      when(labeler.fetchExistingLabels(any(), any(), any())).thenReturn(List.of());
      when(projectStackResolver.resolve(any(), any(), any(), anyLong())).thenReturn("");
    }

    @Test
    void tokenBudgetedSkipsLineCappedMegaDiffAndDoesNotOmitByLines() {
      when(activeModel.maxInputTokens()).thenReturn(48_000);
      var manyLines = "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10";
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff("a.java", "modified", 5, 0, 5, manyLines),
              new GitHubPullRequestClient.FileDiff("b.java", "modified", 5, 0, 5, manyLines),
              new GitHubPullRequestClient.FileDiff("c.java", "modified", 5, 0, 5, manyLines));
      stubCommonLoadDeps(files);
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;

      var ctx = loader.load("auth", request(), session, "owner/repo");

      assertEquals("", ctx.diff());
      assertEquals("", ctx.baseComparison());
      assertEquals(0, ctx.omittedFiles());
      assertEquals(3, ctx.reviewableFiles().size());
      // Line-capped mega-diff and uncapped base comparison must not run when budgeting is on —
      // both would only inflate shared prompt overhead for multi-call batches.
      verify(prClient).getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(1));
      verify(prClient, never()).compareCommits(any(), any(), any(), any(), any(), any());
    }

    @Test
    void budgetingDisabledStillBuildsLineCappedDiff() {
      when(activeModel.maxInputTokens()).thenReturn(0);
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "a.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+x"));
      stubCommonLoadDeps(files);
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;

      var ctx = loader.load("auth", request(), session, "owner/repo");

      assertTrue(ctx.diff().contains("## Overview"));
      assertTrue(ctx.diff().contains("### a.java"));
      assertEquals(0, ctx.omittedFiles());
    }
  }

  @Nested
  class FetchPrFiles {

    @Test
    void shouldPropagateExceptionSoTheReviewFailsInsteadOfApproving() {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenThrow(new RuntimeException("GitHub API error"));

      assertThrows(RuntimeException.class, () -> loader.fetchPrFiles("auth", "owner", "repo", 42));
    }

    @Test
    void shouldPropagateNotAuthorizedRatherThanReturnEmpty() {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(123)))
          .thenThrow(new jakarta.ws.rs.NotAuthorizedException("Bad credentials"));

      assertThrows(
          jakarta.ws.rs.NotAuthorizedException.class,
          () -> loader.fetchPrFiles("auth", "owner", "repo", 123));
    }

    @Test
    void shouldReturnFilesOnSuccess() {
      var expected =
          List.of(
              new GitHubPullRequestClient.FileDiff("README.md", "modified", 2, 1, 3, "@@ patch"));
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(expected);

      var result = loader.fetchPrFiles("auth", "owner", "repo", 1);

      assertEquals(expected, result);
      assertEquals(1, result.size());
    }
  }

  @Nested
  class FetchPriorReviews {

    @Test
    void shouldReturnEmptyListOnException() {
      when(reviewClient.listReviews(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenThrow(new RuntimeException("GitHub API error"));

      var result = loader.fetchPriorReviews("auth", "owner", "repo", 42);

      assertNotNull(result);
      assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyListOnNotFound() {
      when(reviewClient.listReviews(any(), any(), eq("owner"), eq("repo"), eq(99)))
          .thenThrow(new jakarta.ws.rs.NotFoundException("Not found"));

      var result = loader.fetchPriorReviews("auth", "owner", "repo", 99);

      assertNotNull(result);
      assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnReviewsOnSuccess() {
      var expected =
          List.of(
              new GitHubReviewClient.ReviewResponse(
                  1L,
                  "Looks good",
                  "APPROVED",
                  "abc123",
                  new GitHubReviewClient.ReviewResponse.User("some-user")));
      when(reviewClient.listReviews(any(), any(), eq("owner"), eq("repo"), eq(10)))
          .thenReturn(expected);

      var result = loader.fetchPriorReviews("auth", "owner", "repo", 10);

      assertEquals(1, result.size());
      assertEquals("Looks good", result.get(0).body());
    }
  }

  @Nested
  class ResolveMissingPrDetails {

    private ReviewOrchestrator.ReviewRequest manualRequest(String sha) {
      return new ReviewOrchestrator.ReviewRequest(
          "owner", "repo", 7, sha, "(manual review)", "", "", "main", 123L, true);
    }

    @Test
    void shouldFetchPrDetailsWhenShaIsBlank() {
      when(prClient.getPullRequest(anyString(), anyString(), eq("owner"), eq("repo"), eq(7)))
          .thenReturn(
              new GitHubPullRequestClient.PullRequestDetails(
                  "add new API",
                  "Adds a new API endpoint",
                  new GitHubPullRequestClient.Ref("headsha1234"),
                  new GitHubPullRequestClient.Ref("basesha5678")));

      var resolved = loader.resolveMissingPrDetails("Bearer tok", manualRequest(""));

      assertEquals("headsha1234", resolved.commitSha());
      assertEquals("basesha5678", resolved.baseSha());
      assertEquals("add new API", resolved.prTitle());
      assertEquals("Adds a new API endpoint", resolved.prDescription());
      assertTrue(resolved.isManualTrigger());
    }

    @Test
    void shouldNotFetchWhenShaIsPresent() {
      var req = manualRequest("abcdefgh");

      var resolved = loader.resolveMissingPrDetails("Bearer tok", req);

      assertSame(req, resolved);
      verifyNoInteractions(prClient);
    }

    @Test
    void shouldTolerateMissingRefsInPrDetails() {
      when(prClient.getPullRequest(anyString(), anyString(), eq("owner"), eq("repo"), eq(7)))
          .thenReturn(new GitHubPullRequestClient.PullRequestDetails(null, null, null, null));

      var resolved = loader.resolveMissingPrDetails("Bearer tok", manualRequest(null));

      assertEquals("", resolved.commitSha());
      assertEquals("", resolved.baseSha());
      assertEquals("(manual review)", resolved.prTitle());
      assertEquals("", resolved.prDescription());
    }
  }

  @Nested
  class FetchPrTotals {

    @Test
    void returnsGitHubAuthoritativeTotalsFromThePullRequest() {
      when(prClient.getPullRequest(anyString(), anyString(), eq("owner"), eq("repo"), eq(46)))
          .thenReturn(
              new GitHubPullRequestClient.PullRequestDetails(
                  "add API",
                  "body",
                  new GitHubPullRequestClient.Ref("headsha"),
                  new GitHubPullRequestClient.Ref("basesha"),
                  27,
                  975,
                  196));

      var totals = loader.fetchPrTotals("Bearer tok", "owner", "repo", 46);

      assertNotNull(totals);
      assertEquals(27, totals.filesChanged());
      assertEquals(975, totals.additions());
      assertEquals(196, totals.deletions());
    }

    @Test
    void returnsNullWhenTheFetchThrowsSoTheSummaryFallsBackToDiffCounts() {
      when(prClient.getPullRequest(anyString(), anyString(), eq("owner"), eq("repo"), eq(46)))
          .thenThrow(new RuntimeException("PR fetch failed"));

      assertNull(loader.fetchPrTotals("Bearer tok", "owner", "repo", 46));
    }
  }

  @Nested
  class FetchPullRequestComments {

    @Test
    void shouldReturnEmptyListWhenClientThrows() {
      when(reviewClient.listPullRequestComments(
              anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenThrow(new RuntimeException("boom"));

      assertTrue(loader.fetchPullRequestComments("auth", "owner", "repo", 1).isEmpty());
    }

    @Test
    void shouldReturnCommentsFromClient() {
      var comment =
          new GitHubReviewClient.PullRequestComment(
              1L, null, "f", "body", new GitHubReviewClient.ReviewResponse.User("u"));
      when(reviewClient.listPullRequestComments(
              anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenReturn(List.of(comment));

      assertEquals(List.of(comment), loader.fetchPullRequestComments("auth", "owner", "repo", 1));
    }
  }

  @Nested
  class BotSummaryCommentDetection {

    private GitHubCommentClient.IssueComment comment(String body, String login) {
      return new GitHubCommentClient.IssueComment(
          body, login == null ? null : new GitHubReviewClient.ReviewResponse.User(login));
    }

    private void stubComments(GitHubCommentClient.IssueComment... comments) {
      when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenReturn(List.of(comments));
    }

    @Test
    void shouldDetectABotSummaryComment() {
      stubComments(
          comment("@thrillhousebot please review", "someuser"),
          comment(PrSummaryGenerator.SUMMARY_HEADING + "\n\nbody", "thrillhousebot[bot]"));

      assertTrue(loader.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldTolerateLeadingWhitespaceBeforeTheHeading() {
      stubComments(
          comment("\n  " + PrSummaryGenerator.SUMMARY_HEADING + "\n", "thrillhousebot[bot]"));

      assertTrue(loader.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldDetectSummaryCommentWithTruncationBannerPrepended() {
      stubComments(
          comment(
              ReviewResult.truncationNotice(87) + PrSummaryGenerator.SUMMARY_HEADING + "\n\nbody",
              "thrillhousebot[bot]"));

      assertTrue(loader.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldIgnoreASummaryHeadingPostedBySomeoneElse() {
      stubComments(comment(PrSummaryGenerator.SUMMARY_HEADING + "\n\nspoof", "impersonator"));

      assertFalse(loader.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldIgnoreBotCommentsThatAreNotTheSummary() {
      stubComments(comment("I don't have enough context to answer that.", "thrillhousebot[bot]"));

      assertFalse(loader.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldReturnFalseForEmptyComments() {
      stubComments();

      assertFalse(loader.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldNotMatchTheHeadingMidComment() {
      stubComments(
          comment("As noted in the " + PrSummaryGenerator.SUMMARY_HEADING, "thrillhousebot[bot]"));

      assertFalse(loader.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldTolerateNullUserAndNullBody() {
      stubComments(
          comment("body without user", null),
          comment(null, "thrillhousebot[bot]"),
          comment(PrSummaryGenerator.SUMMARY_HEADING, "thrillhousebot[bot]"));

      assertTrue(loader.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldBeBestEffortWhenTheFetchThrows() {
      when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenThrow(new RuntimeException("boom"));

      assertFalse(loader.botSummaryCommentExists("auth", "owner", "repo", 1));
      assertTrue(loader.fetchIssueComments("auth", "owner", "repo", 1).isEmpty());
    }
  }

  @Nested
  class ResolveProjectStack {

    @Test
    void shouldReturnEmptyWhenStackResolverThrows() {
      when(projectStackResolver.resolve(any(), any(), any(), anyLong()))
          .thenThrow(new RuntimeException("github down"));

      var stack =
          loader.resolveProjectStack(
              new ReviewOrchestrator.ReviewRequest(
                  "owner", "repo", 1, "sha", "title", "", "base", "main", 123L, false));

      assertEquals("", stack);
    }
  }

  /**
   * #135 — one memoized {@link DiffLineResolver} per review; prior AI responses deserialized once
   * at load time.
   */
  @Nested
  class DedupeHotPathLoad {

    private static ReviewOrchestrator.ReviewRequest request() {
      return new ReviewOrchestrator.ReviewRequest(
          "owner",
          "repo",
          1,
          "headsha1",
          "Title",
          "body",
          "basesha1",
          "main",
          99L,
          true,
          "main",
          false);
    }

    private void stubLoad(List<GitHubPullRequestClient.FileDiff> files, List<String> priorJsons) {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(files);
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(
              new GitHubPullRequestClient.PullRequestDetails(
                  "Title",
                  "body",
                  new GitHubPullRequestClient.Ref("headsha1"),
                  new GitHubPullRequestClient.Ref("basesha1"),
                  1,
                  1,
                  0));
      when(reviewClient.listReviews(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(List.of());
      when(commentClient.listComments(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(List.of());
      when(reviewClient.listPullRequestComments(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(List.of());
      when(sessionPersistence.findAllPriorAiResponseJsons(any(), eq(1), anyLong()))
          .thenReturn(priorJsons);
      when(instructionsResolver.resolve(any(), any(), any(), anyLong()))
          .thenReturn(new InstructionsResolver.ResolvedInstructions("", ""));
      when(labeler.fetchExistingLabels(any(), any(), any())).thenReturn(List.of());
      when(projectStackResolver.resolve(any(), any(), any(), anyLong())).thenReturn("");
    }

    @Test
    void lineResolverIsBuiltOnceAndSharedAcrossAccesses() {
      stubLoad(
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "a.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+x")),
          List.of());
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;
      DiffLineResolver.CONSTRUCTION_COUNT.set(0);

      var ctx = loader.load("auth", request(), session, "owner/repo");
      assertEquals(0, DiffLineResolver.CONSTRUCTION_COUNT.get());

      var first = ctx.lineResolver();
      var second = ctx.lineResolver();

      assertSame(first, second);
      assertEquals(1, DiffLineResolver.CONSTRUCTION_COUNT.get());
    }

    @Test
    void lineResolverIsNotBuiltWhenNeverAccessed() {
      stubLoad(
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "a.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+x")),
          List.of());
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;
      DiffLineResolver.CONSTRUCTION_COUNT.set(0);

      loader.load("auth", request(), session, "owner/repo");

      assertEquals(0, DiffLineResolver.CONSTRUCTION_COUNT.get());
    }

    @Test
    void priorAiResponsesAreParsedOnceAtLoad() {
      var priorJson =
          """
          {"findings":[{"risk":"medium","confidence":"high","file":"a.java","line":1,\
          "title":"T","description":"d","suggestion_old":"o","suggestion_new":"n"}],\
          "previous_findings_status":[],"summary":null}
          """;
      var olderJson =
          """
          {"findings":[{"risk":"low","confidence":"high","file":"b.java","line":2,\
          "title":"U","description":"d","suggestion_old":"o","suggestion_new":"n"}],\
          "previous_findings_status":[],"summary":null}
          """;
      var parsed =
          List.of(
              new ReviewResponse(
                  List.of(
                      new ReviewResponse.Finding(
                          "medium", "high", "a.java", 1, "T", "d", "o", "n")),
                  List.of(),
                  null),
              new ReviewResponse(
                  List.of(
                      new ReviewResponse.Finding("low", "high", "b.java", 2, "U", "d", "o", "n")),
                  List.of(),
                  null));
      when(followUpAnalyzer.parsePreviousResponses(List.of(priorJson, olderJson)))
          .thenReturn(parsed);
      when(followUpAnalyzer.buildPreviousFindingsContext(
              anyList(), any(), any(), any(), any(BotIdentity.class)))
          .thenReturn("ctx");
      stubLoad(
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "a.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+x")),
          List.of(priorJson, olderJson));
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;

      var ctx = loader.load("auth", request(), session, "owner/repo");

      assertEquals(parsed, ctx.priorAiResponses());
      assertEquals(1, ctx.previousFindingsList().size());
      assertEquals("a.java", ctx.previousFindingsList().get(0).file());
      verify(followUpAnalyzer, times(1)).parsePreviousResponses(List.of(priorJson, olderJson));
      verify(followUpAnalyzer)
          .buildPreviousFindingsContext(
              eq(parsed.get(0).findings()),
              any(),
              any(),
              eq(parsed.subList(1, parsed.size())),
              any(BotIdentity.class));
    }
  }
}
