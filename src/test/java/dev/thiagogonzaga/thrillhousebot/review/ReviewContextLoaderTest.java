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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.github.*;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
  @Mock private RepoSettingsResolver repoSettingsResolver;
  @Mock private ProjectStackResolver projectStackResolver;
  @Mock private ConfigKeyContextResolver configKeyContextResolver;
  @Mock private PatchCoverageResolver patchCoverageResolver;
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
            repoSettingsResolver,
            projectStackResolver,
            diffFormatter,
            labeler,
            followUpAnalyzer,
            new BugFixContextResolver(commentClient),
            configKeyContextResolver,
            patchCoverageResolver,
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
              new GitHubPullRequestClient.FileDiff(
                  "renamed-only.txt", "renamed", 0, 0, 0, null, "old-name.txt"));

      var result = diffFormatter.buildDiffString(files);

      assertTrue(result.contains("## Overview: 1 files (+0 -0)"));
      // Pure renames are disclosed in a rollup, not as empty ### sections (#386).
      assertTrue(
          result.contains(
              "1 pure rename omitted from AI review (old-name.txt → renamed-only.txt)"));
      assertFalse(result.contains("### renamed-only.txt"));
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

    /**
     * Per-repo ignore globs (#51): what the repository declares in {@code
     * .github/thrillhousebot.yml} is unioned with the deployment-wide list before the
     * reviewable-file set is computed, so the extra paths never reach the model.
     */
    @Test
    void perRepoIgnorePatternsNarrowTheReviewableFileSet() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "src/App.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+a"),
              new GitHubPullRequestClient.FileDiff(
                  "docs/generated/api.md", "modified", 90, 0, 90, "@@ -1 +1 @@\n+gen"));
      stubCommonLoadDeps(files);
      when(repoSettingsResolver.resolve("owner", "repo", "main", 99L))
          .thenReturn(
              new RepoSettings(
                  List.of("docs/generated/**"), List.of(), ".github/thrillhousebot.yml"));
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;

      var ctx = loader.load("auth", request(), session, "owner/repo");

      assertEquals(1, ctx.reviewableFiles().size());
      assertEquals("src/App.java", ctx.reviewableFiles().get(0).filename());
      assertTrue(
          ctx.diff().contains("(docs/generated/api.md skipped: matches ignored pattern"),
          ctx.diff());
      assertFalse(ctx.diff().contains("+gen"), ctx.diff());
    }

    /**
     * #108 meets #51: config-key resolution reads the post-ignore-filter file set, so a key
     * documented only in an ignored Markdown file is never resolved. The resolver takes the
     * reviewable list rather than the raw one precisely so it inherits every ignore rule.
     */
    @Test
    void configKeyResolutionSeesOnlyFilesThatSurvivedTheIgnoreFilter() {
      var ignoredDoc =
          new GitHubPullRequestClient.FileDiff(
              "docs/generated/api.md", "modified", 1, 0, 1, "@@ -1 +1 @@\n+`IGNORED_DOC_KEY`");
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "src/App.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+a"),
              ignoredDoc);
      stubCommonLoadDeps(files);
      when(repoSettingsResolver.resolve("owner", "repo", "main", 99L))
          .thenReturn(
              new RepoSettings(
                  List.of("docs/generated/**"), List.of(), ".github/thrillhousebot.yml"));
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;

      var ctx = loader.load("auth", request(), session, "owner/repo");

      assertFalse(
          ctx.reviewableFiles().contains(ignoredDoc),
          () -> "an ignored doc file must not reach config-key resolution: " + ctx.files());
      verify(configKeyContextResolver)
          .resolve("auth", "owner", "repo", "headsha1", ctx.reviewableFiles());
    }

    @Test
    void repoWithNoDeclaredPatternsKeepsEveryFileTheGlobalListAllows() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "src/App.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+a"),
              new GitHubPullRequestClient.FileDiff(
                  "docs/generated/api.md", "modified", 90, 0, 90, "@@ -1 +1 @@\n+gen"));
      stubCommonLoadDeps(files);
      when(repoSettingsResolver.resolve("owner", "repo", "main", 99L))
          .thenReturn(RepoSettings.EMPTY);
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;

      var ctx = loader.load("auth", request(), session, "owner/repo");

      assertEquals(2, ctx.reviewableFiles().size());
      assertTrue(ctx.diff().contains("+gen"), ctx.diff());
    }

    @Test
    void aFailingRepoSettingsResolverFallsBackToTheGlobalListInsteadOfFailingTheReview() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "src/App.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+a"));
      stubCommonLoadDeps(files);
      when(repoSettingsResolver.resolve("owner", "repo", "main", 99L))
          .thenThrow(new IllegalStateException("boom"));
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;

      var ctx = assertDoesNotThrow(() -> loader.load("auth", request(), session, "owner/repo"));

      assertEquals(1, ctx.reviewableFiles().size());
      assertEquals("src/App.java", ctx.reviewableFiles().get(0).filename());
      assertTrue(ctx.pathInstructions().isEmpty());
    }

    /**
     * Path-scoped review rules (#33) come from the same settings read as the ignore globs, are
     * resolved once against the post-ignore-filter file list, and are carried on the context so no
     * later stage re-walks a glob.
     */
    @Test
    void resolvesPathScopedRulesOnceAgainstTheReviewableFiles() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "payments/Charge.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+a"),
              new GitHubPullRequestClient.FileDiff(
                  "web/Landing.tsx", "modified", 1, 0, 1, "@@ -1 +1 @@\n+b"),
              new GitHubPullRequestClient.FileDiff(
                  "payments/generated/Api.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+c"));
      stubCommonLoadDeps(files);
      when(repoSettingsResolver.resolve("owner", "repo", "main", 99L))
          .thenReturn(
              new RepoSettings(
                  // The ignore glob wins first: a scope can never pull an ignored file back in.
                  List.of("**/generated/**"),
                  List.of(
                      new RepoSettings.PathInstructions("payments/**", "Money is in cents."),
                      new RepoSettings.PathInstructions("infra/**", "Untouched by this PR.")),
                  ".github/thrillhousebot.yml"));
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;

      var ctx = loader.load("auth", request(), session, "owner/repo");

      assertEquals(1, ctx.pathInstructions().scopes().size());
      var scope = ctx.pathInstructions().scopes().get(0);
      assertEquals("payments/**", scope.glob());
      assertEquals("Money is in cents.", scope.instructions());
      assertEquals(List.of("payments/Charge.java"), scope.files());
      assertEquals(".github/thrillhousebot.yml", ctx.pathInstructions().source());
      // One settings read for the ignore globs and the scopes alike.
      verify(repoSettingsResolver, times(1)).resolve("owner", "repo", "main", 99L);
    }

    @Test
    void repoWithNoDeclaredScopesCarriesNoPathScopedRules() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "payments/Charge.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+a"));
      stubCommonLoadDeps(files);
      when(repoSettingsResolver.resolve("owner", "repo", "main", 99L))
          .thenReturn(RepoSettings.EMPTY);
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;

      var ctx = loader.load("auth", request(), session, "owner/repo");

      assertTrue(ctx.pathInstructions().isEmpty());
    }

    /**
     * #115 — patch coverage rides the same single repo-settings read and the same post-ignore file
     * list, so an ignored file can never be reported as under-tested and no second settings fetch
     * is added.
     */
    @Test
    void patchCoverageIsResolvedFromTheOneSettingsReadAndReachesTheContext() {
      var ignored =
          new GitHubPullRequestClient.FileDiff(
              "payments/generated/Api.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+c");
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff(
                  "payments/Charge.java", "modified", 1, 0, 1, "@@ -1 +1 @@\n+a"),
              ignored);
      stubCommonLoadDeps(files);
      var settings =
          new RepoSettings(
              List.of("**/generated/**"),
              List.of(),
              "coverage-report",
              ".github/thrillhousebot.yml");
      when(repoSettingsResolver.resolve("owner", "repo", "main", 99L)).thenReturn(settings);
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;
      when(patchCoverageResolver.resolve(eq("auth"), any(), eq(settings), anyList()))
          .thenReturn("### uncovered");

      var ctx = loader.load("auth", request(), session, "owner/repo");

      assertEquals("### uncovered", ctx.patchCoverage());
      verify(patchCoverageResolver).resolve("auth", request(), settings, ctx.reviewableFiles());
      assertFalse(ctx.reviewableFiles().contains(ignored));
      verify(repoSettingsResolver, times(1)).resolve("owner", "repo", "main", 99L);
    }
  }

  @Nested
  class StaleHeadValidation {

    private ReviewOrchestrator.ReviewRequest request() {
      return request("expected-sha");
    }

    private ReviewOrchestrator.ReviewRequest request(String commitSha) {
      return new ReviewOrchestrator.ReviewRequest(
          "owner", "repo", 42, commitSha, "Title", "", "base-sha", "main", 9L, false);
    }

    private ReviewSession session() {
      var session = ReviewSession.create("owner/repo", 42, "Title", "expected-sha");
      session.id = 1L;
      return session;
    }

    @Test
    void shouldRejectWhenHeadChangesAfterFilesAreFetched() {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenReturn(List.of());
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenReturn(
              new GitHubPullRequestClient.PullRequestDetails(
                  "Title",
                  "",
                  new GitHubPullRequestClient.Ref("new-sha"),
                  new GitHubPullRequestClient.Ref("base-sha")));

      var error =
          assertThrows(
              ReviewContextLoader.StaleReviewException.class,
              () -> loader.load("auth", request(), session(), "owner/repo"));

      assertTrue(error.getMessage().contains("expected expected-sha, current new-sha"));
    }

    @Test
    void shouldRejectNullPullRequestHeadAndCurrentSha() {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenReturn(List.of());
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenReturn(
              null,
              new GitHubPullRequestClient.PullRequestDetails(
                  "Title", "", null, new GitHubPullRequestClient.Ref("base-sha")),
              new GitHubPullRequestClient.PullRequestDetails(
                  "Title",
                  "",
                  new GitHubPullRequestClient.Ref(null),
                  new GitHubPullRequestClient.Ref("base-sha")));

      for (var ignored = 0; ignored < 3; ignored++) {
        var error =
            assertThrows(
                ReviewContextLoader.StaleReviewException.class,
                () -> loader.load("auth", request(), session(), "owner/repo"));
        assertTrue(error.getMessage().contains("current null"));
      }
    }

    @Test
    void shouldRejectNullRequestedSha() {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenReturn(List.of());
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenReturn(
              new GitHubPullRequestClient.PullRequestDetails(
                  "Title",
                  "",
                  new GitHubPullRequestClient.Ref("current-sha"),
                  new GitHubPullRequestClient.Ref("base-sha")));

      var error =
          assertThrows(
              ReviewContextLoader.StaleReviewException.class,
              () -> loader.load("auth", request(null), session(), "owner/repo"));

      assertTrue(error.getMessage().contains("expected null, current current-sha"));
    }

    @Test
    void shouldFailClosedWhenCurrentHeadCannotBeRead() {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenReturn(List.of());
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenThrow(new RuntimeException("GitHub unavailable"));

      var reviewRequest = request();
      var reviewSession = session();
      var error =
          assertThrows(
              IllegalStateException.class,
              () -> loader.load("auth", reviewRequest, reviewSession, "owner/repo"));

      assertTrue(error.getMessage().contains("refusing to review potentially mixed revisions"));
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

  /**
   * #548 — the conversation is where a thread-less finding is cleared, and the walk that reads it
   * is bounded. GitHub serves issue comments oldest first with no reverse order on this endpoint,
   * so a walk that stops at the ceiling keeps the oldest window and drops the newest — where a
   * freshly written directive lives. The ceiling has to be recognized, not assumed away.
   */
  @Nested
  class ConversationReadCeiling {

    private List<GitHubCommentClient.IssueComment> comments(int count) {
      var all = new ArrayList<GitHubCommentClient.IssueComment>(count);
      for (var i = 0; i < count; i++) {
        all.add(
            new GitHubCommentClient.IssueComment(
                "comment " + i, new GitHubReviewClient.ReviewResponse.User("maintainer")));
      }
      return all;
    }

    @Test
    void shouldNameTheCeilingAsTheFullPagedWalk() {
      assertEquals(
          ReviewContextLoader.MAX_CONVERSATION_COMMENTS,
          GitHubCommentClient.COMMENTS_PER_PAGE * GitHubCommentClient.MAX_COMMENT_PAGES,
          "the walk the client actually performs must reach the documented ceiling");
    }

    @Test
    void shouldRecognizeAWalkThatStoppedAtTheCeiling() {
      assertTrue(
          ReviewContextLoader.conversationWalkCapped(
              comments(ReviewContextLoader.MAX_CONVERSATION_COMMENTS)),
          "a full window means the walk stopped at the bound, not at the end of the thread");
    }

    @Test
    void shouldNotFlagAConversationThatFitUnderTheCeiling() {
      assertFalse(
          ReviewContextLoader.conversationWalkCapped(
              comments(ReviewContextLoader.MAX_CONVERSATION_COMMENTS - 1)));
      assertFalse(ReviewContextLoader.conversationWalkCapped(List.of()));
    }

    @Test
    void shouldReturnEveryCommentTheWalkRead() {
      when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenReturn(comments(ReviewContextLoader.MAX_CONVERSATION_COMMENTS));

      assertEquals(
          ReviewContextLoader.MAX_CONVERSATION_COMMENTS,
          loader.fetchConversationComments("auth", "owner", "repo", 1).size(),
          "disclosing the ceiling must not drop what was read");
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

  /** #108 — config-key definitions are best-effort enrichment read at the PR head. */
  @Nested
  class ResolveConfigKeyContext {

    private static final ReviewOrchestrator.ReviewRequest REQUEST =
        new ReviewOrchestrator.ReviewRequest(
            "owner", "repo", 1, "headsha", "title", "", "base", "main", 123L, false);

    @Test
    void shouldResolveAtThePrHeadSha() {
      var files =
          List.of(new GitHubPullRequestClient.FileDiff("README.md", "modified", 1, 0, 1, ""));
      when(configKeyContextResolver.resolve("auth", "owner", "repo", "headsha", files))
          .thenReturn("### definitions");

      assertEquals("### definitions", loader.resolveConfigKeyContext("auth", REQUEST, files));
    }

    @Test
    void shouldReturnEmptyWhenTheResolverThrows() {
      when(configKeyContextResolver.resolve(any(), any(), any(), any(), any()))
          .thenThrow(new RuntimeException("github down"));

      assertEquals("", loader.resolveConfigKeyContext("auth", REQUEST, List.of()));
    }

    @Test
    void shouldReturnEmptyWithoutResolvingWhenNoRefIsKnown() {
      var blankRefs =
          new ReviewOrchestrator.ReviewRequest(
              "owner", "repo", 1, "", "title", "", "base", "", 123L, false);
      var nullRefs =
          new ReviewOrchestrator.ReviewRequest(
              "owner", "repo", 1, null, "title", "", "base", null, 123L, false);

      assertEquals("", loader.resolveConfigKeyContext("auth", blankRefs, List.of()));
      assertEquals("", loader.resolveConfigKeyContext("auth", nullRefs, List.of()));
      verifyNoInteractions(configKeyContextResolver);
    }

    @Test
    void shouldFallBackToTheDefaultBranchWhenTheHeadShaIsAbsent() {
      var noSha =
          new ReviewOrchestrator.ReviewRequest(
              "owner", "repo", 1, null, "title", "", "base", "main", 123L, false);
      when(configKeyContextResolver.resolve("auth", "owner", "repo", "main", List.of()))
          .thenReturn("### from default branch");

      assertEquals(
          "### from default branch", loader.resolveConfigKeyContext("auth", noSha, List.of()));
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
              anyList(), anyBoolean(), any(), any(), any(), any(BotIdentity.class), any()))
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
              eq(true),
              any(),
              any(),
              eq(parsed.subList(1, parsed.size())),
              any(BotIdentity.class),
              any());
    }
  }

  /**
   * #455 — a follow-up round that finds nothing must not evict the finding raised before it. These
   * drive {@link ReviewContextLoader#load} with the real {@link FollowUpAnalyzer}, so the section
   * asserted on is the one the model is actually handed.
   */
  @Nested
  class PreviousFindingsAcrossAZeroFindingRound {

    private static final String CARRIED_FILE =
        "src/main/java/dev/thiagogonzaga/thrillhousebot/github/RepoSettingsResolver.java";
    private static final String OTHER_FILE =
        "src/main/java/dev/thiagogonzaga/thrillhousebot/github/RepoSettingsParser.java";
    private static final String CARRIED_TITLE =
        "Undecodable content causes fallback to alternate config name";
    private static final String OTHER_TITLE = "Unbounded YAML document size";
    private static final String CARRIED_ANCHOR = "var name = decode(content);";

    private static String findingJson(String file, int line, String title) {
      return "{\"risk\":\"medium\",\"file\":\""
          + file
          + "\",\"line\":"
          + line
          + ",\"title\":\""
          + title
          + "\",\"description\":\"The first existing config file must be selected.\","
          + "\"suggestion_old\":\""
          + CARRIED_ANCHOR
          + "\"}";
    }

    /** Round 1 of the PR #449 sequence: one MEDIUM finding, summary-only (no inline thread). */
    private static final String ROUND_ONE_JSON =
        "{\"findings\":["
            + findingJson(CARRIED_FILE, 166, CARRIED_TITLE)
            + "],\"previous_findings_status\":[],\"summary\":null}";

    private static final String ROUND_ONE_TWO_FINDINGS_JSON =
        "{\"findings\":["
            + findingJson(CARRIED_FILE, 166, CARRIED_TITLE)
            + ","
            + findingJson(OTHER_FILE, 42, OTHER_TITLE)
            + "],\"previous_findings_status\":[],\"summary\":null}";

    /** Round 2: no new issues, reporting round 1's finding still unresolved. */
    private static final String ROUND_TWO_JSON =
        "{\"findings\":[],\"previous_findings_status\":"
            + "[{\"id\":1,\"status\":\"unresolved\",\"note\":\"still there\"}],\"summary\":null}";

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

    private ReviewContextLoader realAnalyzerLoader() {
      return new ReviewContextLoader(
          prClient,
          reviewClient,
          commentClient,
          instructionsResolver,
          repoSettingsResolver,
          projectStackResolver,
          diffFormatter,
          labeler,
          new FollowUpAnalyzer(new ObjectMapper()),
          new BugFixContextResolver(commentClient),
          configKeyContextResolver,
          patchCoverageResolver,
          sessionPersistence,
          BotIdentity.from(List.of(BOT_LOGIN)),
          activeModel);
    }

    /** Round 2 posted the bot's own "1 previous finding(s) remain unresolved" body, as on #449. */
    private void stubRoundThree(List<String> priorJsons) {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(
              List.of(
                  new GitHubPullRequestClient.FileDiff(
                      CARRIED_FILE, "modified", 1, 0, 1, "@@ -166 +166 @@\n+" + CARRIED_ANCHOR)));
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
          .thenReturn(
              List.of(
                  new GitHubReviewClient.ReviewResponse(
                      1L,
                      ReviewResult.unresolvedPreviousMessage(1),
                      "COMMENTED",
                      "abc",
                      new GitHubReviewClient.ReviewResponse.User(BOT_LOGIN))));
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

    private ReviewContextLoader.ReviewContext loadRoundThree(List<String> priorJsons) {
      stubRoundThree(priorJsons);
      var session = ReviewSession.create("owner/repo", 1, "Title", "headsha1");
      session.id = 1L;
      return realAnalyzerLoader().load("auth", request(), session, "owner/repo");
    }

    @Test
    void findingFromRoundOneSurvivesAZeroFindingRoundNumberedAndInTheIdSpace() {
      var ctx = loadRoundThree(List.of(ROUND_TWO_JSON, ROUND_ONE_JSON));

      assertEquals(
          1,
          ctx.previousFindingsList().size(),
          "the zero-finding round evicted the still-open finding from the id space");
      assertEquals(CARRIED_TITLE, ctx.previousFindingsList().get(0).title());
      assertTrue(
          ctx.previousFindings()
              .contains("1. [MEDIUM] " + CARRIED_FILE + ":166 — " + CARRIED_TITLE),
          "previous-findings section was: " + ctx.previousFindings());
      assertFalse(
          ctx.previousFindings().contains("remain unresolved"),
          "the bot's own review body was handed back to it as a previous finding");
    }

    /**
     * The #169 id-space pin: with nothing to carry, a zero-finding round leaves {@code previous}
     * empty — {@link VerdictBuilder} passes it to {@code recheckDeclines} as the id space, and a
     * pseudo-finding fabricated from a review body must never enter it.
     */
    @Test
    void zeroFindingRoundWithNothingToCarryLeavesTheIdSpaceEmptyAndTheSectionAbsent() {
      var ctx = loadRoundThree(List.of(ROUND_TWO_JSON));

      assertTrue(
          ctx.previousFindingsList().isEmpty(),
          "a review body must never be turned into a previous finding");
      assertEquals(
          "",
          ctx.previousFindings(),
          "an absent previous-findings section is what suppresses the prompt block entirely");
    }

    /**
     * The carry-forward pin: a real prior finding keeps the id its own round gave it — the id its
     * inline comment's marker carries and the one {@code previous_findings_status} references.
     */
    @Test
    void carriedFindingsKeepTheIdsTheirOwnRoundGaveThem() {
      var analyzer = new FollowUpAnalyzer(new ObjectMapper());

      var ctx = loadRoundThree(List.of(ROUND_TWO_JSON, ROUND_ONE_TWO_FINDINGS_JSON));
      var previous = ctx.previousFindingsList();

      assertEquals(
          Map.of(1, CARRIED_FILE, 2, OTHER_FILE), analyzer.previousFindingFilesById(previous));
      var unresolved =
          analyzer.unresolvedFindings(
              previous, List.of(new ReviewResponse.PreviousFindingStatus(2, "unresolved", "n")));
      assertEquals(1, unresolved.size());
      assertEquals(OTHER_FILE, unresolved.get(0).file(), "id 2 no longer names the same finding");
    }
  }
}
