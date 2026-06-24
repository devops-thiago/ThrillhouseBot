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
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.dashboard.SessionEventBroadcaster;
import dev.thiagogonzaga.thrillhousebot.github.*;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link ReviewOrchestrator}.
 *
 * <p>Package-private helper methods are tested directly. The main {@code review()} method uses
 * short DB transactions via {@link ReviewSessionPersistence} and is covered by mocked integration
 * tests.
 */
class ReviewOrchestratorTest {

  private static final String SESSION_URL = "https://bot.example/session/test-public-id";

  // The bot login this test deployment runs under; the orchestrator resolves it from config.
  private static final String BOT_LOGIN = "thrillhousebot[bot]";
  private static final BotIdentity BOT_ID = BotIdentity.of(BOT_LOGIN);

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  // Created in setUp() (not @Mock) so individual tests can opt into the diagram feature.
  private ThrillhouseConfig.DiagramConfig diagramConfig;

  @Mock private GitHubAuthClient authClient;

  @Mock private GitHubCheckRunClient checkRunClient;

  @Mock private GitHubReviewClient reviewClient;

  @Mock private GitHubCommentClient commentClient;

  @Mock private GitHubPullRequestClient prClient;

  @Mock private ReviewThreadService reviewThreadService;

  @Mock private InstructionsResolver instructionsResolver;

  @Mock private ProjectStackResolver projectStackResolver;

  @Mock private AiReviewService aiReviewService;

  @Mock private FindingVerificationService findingVerificationService;

  private final FindingQuoteValidator quoteValidator = new FindingQuoteValidator();

  private final FindingDeduplicator deduplicator = new FindingDeduplicator();

  @Mock private SessionEventBroadcaster broadcaster;

  @Mock private ReviewSessionPersistence sessionPersistence;

  @Mock private SuggestionFormatter suggestionFormatter;

  @Mock private PrSummaryGenerator summaryGenerator;

  @Mock private FollowUpAnalyzer followUpAnalyzer;

  @Mock private PrLabeler labeler;

  private final ObjectMapper mapper = new ObjectMapper();

  private ReviewDiffFormatter diffFormatter;

  private ReviewOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    // The orchestrator resolves its BotIdentity from config in the constructor, so stub github()
    // before building it.
    ThrillhouseConfig.GitHubConfig githubConfig = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.botLogins()).thenReturn(List.of(BOT_LOGIN));
    diffFormatter = new ReviewDiffFormatter(List.of(), 5000);
    orchestrator = newOrchestrator(mapper);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
    // Walkthrough diagram is off by default; individual tests opt in by re-stubbing enabled().
    diagramConfig = mock(ThrillhouseConfig.DiagramConfig.class);
    when(reviewConfig.diagram()).thenReturn(diagramConfig);
    // Mimic the real create(): persist assigns an id to new entities only
    doAnswer(
            invocation -> {
              var created = invocation.getArgument(0, ReviewSession.class);
              if (created.id == null) {
                created.id = 42L;
              }
              created.setPublicId("test-public-id");
              return null;
            })
        .when(sessionPersistence)
        .create(any());
    ThrillhouseConfig.DashboardConfig dashboardConfig =
        mock(ThrillhouseConfig.DashboardConfig.class);
    when(config.dashboard()).thenReturn(dashboardConfig);
    when(dashboardConfig.url()).thenReturn("https://bot.example");
    doNothing().when(sessionPersistence).update(anyLong(), any());
    when(projectStackResolver.resolve(any(), any(), any(), anyLong())).thenReturn("");
    when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
        .thenReturn("");
    // The verifier and dedup pass findings through untouched unless a test overrides them
    when(findingVerificationService.verify(any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(followUpAnalyzer.dropRepliedDuplicates(any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(reviewClient.createPullRequestComment(
            anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.PullRequestCommentResponse(1L, "ok", "main.py", 10));
  }

  private ReviewOrchestrator newOrchestrator(ObjectMapper objectMapper) {
    return new ReviewOrchestrator(
        config,
        authClient,
        checkRunClient,
        reviewClient,
        commentClient,
        prClient,
        reviewThreadService,
        instructionsResolver,
        projectStackResolver,
        aiReviewService,
        findingVerificationService,
        quoteValidator,
        deduplicator,
        broadcaster,
        sessionPersistence,
        suggestionFormatter,
        summaryGenerator,
        followUpAnalyzer,
        diffFormatter,
        labeler,
        objectMapper);
  }

  private static GitHubPullRequestClient.FileDiff fileDiffWithLine(
      String filename, int lineInPatch) {
    // Plain concatenation: patches always use \n regardless of platform, so %n would be wrong
    var patch = "@@ -" + lineInPatch + ",1 +" + lineInPatch + ",1 @@\n-old\n+new";
    return new GitHubPullRequestClient.FileDiff(filename, "modified", 1, 1, 2, patch);
  }

  @Nested
  class BuildDiffString {

    @Test
    void shouldReturnNoChangesForNullList() {
      var result = orchestrator.buildDiffString(null);
      assertEquals("(no changes detected)", result);
    }

    @Test
    void shouldReturnNoChangesForEmptyList() {
      var result = orchestrator.buildDiffString(Collections.emptyList());
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

      var result = orchestrator.buildDiffString(files);

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

      var result = orchestrator.buildDiffString(files);

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

      var result = orchestrator.buildDiffString(files);

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

      var result = orchestrator.buildDiffString(files);

      assertTrue(result.contains("## Overview: 1 files (+0 -0)"));
      assertTrue(result.contains("renamed-only.txt (renamed, +0 -0)"));
    }
  }

  @Nested
  class BuildBaseComparison {

    @Test
    void shouldReturnSafeMessageForNullBase() {
      var result = orchestrator.buildBaseComparison("auth", "owner", "repo", null, "abcdefgh");
      assertEquals("(regression comparison unavailable — refs too short)", result);
    }

    @Test
    void shouldReturnSafeMessageForNullHead() {
      var result = orchestrator.buildBaseComparison("auth", "owner", "repo", "abcdefgh", null);
      assertEquals("(regression comparison unavailable — refs too short)", result);
    }

    @Test
    void shouldReturnSafeMessageForShortBaseSha() {
      var result = orchestrator.buildBaseComparison("auth", "owner", "repo", "abc", "abcdefgh");
      assertEquals("(regression comparison unavailable — refs too short)", result);
    }

    @Test
    void shouldReturnSafeMessageForShortHeadSha() {
      var result = orchestrator.buildBaseComparison("auth", "owner", "repo", "abcdefgh", "def");
      assertEquals("(regression comparison unavailable — refs too short)", result);
    }

    @Test
    void shouldReturnSafeMessageWhenBothNull() {
      var result = orchestrator.buildBaseComparison("auth", "owner", "repo", null, null);
      assertEquals("(regression comparison unavailable — refs too short)", result);
    }

    @Test
    void shouldReturnNoChangesMessageWhenComparisonHasEmptyFiles() {
      var emptyComparison = new GitHubPullRequestClient.CompareResponse(3, List.of());
      when(prClient.compareCommits(
              any(), any(), eq("owner"), eq("repo"), eq("abcdefgh"), eq("hijklmn")))
          .thenReturn(emptyComparison);

      var result =
          orchestrator.buildBaseComparison("Bearer tok", "owner", "repo", "abcdefgh", "hijklmn");

      assertEquals("(no changes between abcdefg and hijklmn)", result);
    }

    @Test
    void shouldReturnNoChangesMessageWhenComparisonHasNullFiles() {
      var nullFilesComparison = new GitHubPullRequestClient.CompareResponse(0, null);
      when(prClient.compareCommits(
              any(), any(), eq("owner"), eq("repo"), eq("abcdefgh"), eq("hijklmn")))
          .thenReturn(nullFilesComparison);

      var result =
          orchestrator.buildBaseComparison("Bearer tok", "owner", "repo", "abcdefgh", "hijklmn");

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
          orchestrator.buildBaseComparison("Bearer tok", "owner", "repo", "abcdefgh", "hijklmn");

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
          orchestrator.buildBaseComparison("Bearer tok", "owner", "repo", "abcdefgh", "hijklmn");

      assertTrue(result.contains("src/Text.java"));
      assertFalse(result.contains("binary.bin"));
    }

    @Test
    void shouldReturnUnavailableOnException() {
      when(prClient.compareCommits(
              any(), any(), eq("owner"), eq("repo"), eq("abcdefgh"), eq("hijklmn")))
          .thenThrow(new RuntimeException("API down"));

      var result =
          orchestrator.buildBaseComparison("Bearer tok", "owner", "repo", "abcdefgh", "hijklmn");

      assertEquals("(regression comparison unavailable)", result);
    }
  }

  @Nested
  class FetchPrFiles {

    @Test
    void shouldPropagateExceptionSoTheReviewFailsInsteadOfApproving() {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenThrow(new RuntimeException("GitHub API error"));

      // Swallowing the failure into an empty list would yield an empty diff and a false APPROVE on
      // unreviewed code; it must propagate so review() takes the failure path.
      assertThrows(
          RuntimeException.class, () -> orchestrator.fetchPrFiles("auth", "owner", "repo", 42));
    }

    @Test
    void shouldPropagateNotAuthorizedRatherThanReturnEmpty() {
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(123)))
          .thenThrow(new jakarta.ws.rs.NotAuthorizedException("Bad credentials"));

      assertThrows(
          jakarta.ws.rs.NotAuthorizedException.class,
          () -> orchestrator.fetchPrFiles("auth", "owner", "repo", 123));
    }

    @Test
    void shouldReturnFilesOnSuccess() {
      var expected =
          List.of(
              new GitHubPullRequestClient.FileDiff("README.md", "modified", 2, 1, 3, "@@ patch"));
      when(prClient.getPullRequestFiles(any(), any(), eq("owner"), eq("repo"), eq(1)))
          .thenReturn(expected);

      var result = orchestrator.fetchPrFiles("auth", "owner", "repo", 1);

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

      var result = orchestrator.fetchPriorReviews("auth", "owner", "repo", 42);

      assertNotNull(result);
      assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyListOnNotFound() {
      when(reviewClient.listReviews(any(), any(), eq("owner"), eq("repo"), eq(99)))
          .thenThrow(new jakarta.ws.rs.NotFoundException("Not found"));

      var result = orchestrator.fetchPriorReviews("auth", "owner", "repo", 99);

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

      var result = orchestrator.fetchPriorReviews("auth", "owner", "repo", 10);

      assertEquals(1, result.size());
      assertEquals("Looks good", result.get(0).body());
    }
  }

  @Nested
  class BuildResult {

    @Test
    void shouldProduceEmptyResultWhenNoFindings() {
      var aiResponse = new ReviewResponse(List.of(), List.of(), null);

      var result =
          orchestrator.buildResult(
              aiResponse, true, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertNotNull(result);
      assertTrue(result.findings().isEmpty());
      assertEquals(0, result.totalFindings());
      assertEquals(ReviewState.APPROVE, result.reviewState());
      assertNull(result.highestRisk());
      assertTrue(result.isFirstReview());
    }

    @Test
    void shouldHoldApproveAndDiscloseWhenDiffTruncated() {
      var aiResponse = new ReviewResponse(List.of(), List.of(), null);

      // A clean review that would APPROVE, but 7 files were omitted by the line budget.
      var result =
          orchestrator.buildResult(
              aiResponse, true, new ReviewOrchestrator.DiffStats(120, 4000, 4000, 7), List.of());

      // The verdict is held back from APPROVE — a partial review must not gate-approve the PR...
      assertEquals(ReviewState.COMMENT, result.reviewState());
      // ...and the summary discloses the omission (summaryGenerator is mocked to "").
      assertTrue(result.summaryMarkdown().contains("partial review"));
      assertTrue(result.summaryMarkdown().contains("7 file"));
    }

    @Test
    void shouldHandleNullFindingsInAiResponse() {
      var aiResponse = new ReviewResponse(null, null, null);

      var result =
          orchestrator.buildResult(
              aiResponse, false, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertNotNull(result);
      assertTrue(result.findings().isEmpty());
      assertEquals(ReviewState.APPROVE, result.reviewState());
      assertFalse(result.isFirstReview());
    }

    @Test
    void shouldOnlyCommentWhenCriticalFindingHasLowConfidence() {
      var aiResponse =
          new ReviewResponse(
              List.of(
                  new ReviewResponse.Finding(
                      "critical",
                      "medium",
                      "src/Auth.java",
                      10,
                      "Speculative framework claim",
                      "Might fail at runtime",
                      "old",
                      "new")),
              List.of(),
              null);

      var result =
          orchestrator.buildResult(
              aiResponse, true, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals(1, result.criticalCount());
      assertEquals(RiskLevel.CRITICAL, result.highestRisk());
      assertEquals(ReviewState.COMMENT, result.reviewState());
    }

    @Test
    void shouldClassifyCriticalFinding() {
      var aiResponse =
          new ReviewResponse(
              List.of(
                  new ReviewResponse.Finding(
                      "critical",
                      "src/Auth.java",
                      10,
                      "SQL Injection",
                      "Unsanitized input",
                      "old",
                      "new")),
              List.of(),
              null);

      var result =
          orchestrator.buildResult(
              aiResponse, true, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals(1, result.totalFindings());
      assertEquals(1, result.criticalCount());
      assertEquals(0, result.highCount());
      assertEquals(RiskLevel.CRITICAL, result.highestRisk());
      assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    }

    @Test
    void shouldClassifyHighFinding() {
      var aiResponse =
          new ReviewResponse(
              List.of(
                  new ReviewResponse.Finding(
                      "high",
                      "src/Service.java",
                      20,
                      "NPE Risk",
                      "Missing null check",
                      null,
                      null)),
              List.of(),
              null);

      var result =
          orchestrator.buildResult(
              aiResponse, true, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals(1, result.highCount());
      assertEquals(RiskLevel.HIGH, result.highestRisk());
      assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    }

    @Test
    void shouldClassifyMediumFindingAsComment() {
      var aiResponse =
          new ReviewResponse(
              List.of(
                  new ReviewResponse.Finding(
                      "medium",
                      "src/Util.java",
                      5,
                      "Naming",
                      "Poor variable name",
                      "oldName",
                      "newName")),
              List.of(),
              null);

      var result =
          orchestrator.buildResult(
              aiResponse, false, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals(1, result.mediumCount());
      assertEquals(RiskLevel.MEDIUM, result.highestRisk());
      assertEquals(ReviewState.COMMENT, result.reviewState());
    }

    @Test
    void shouldClassifyLowFindingAsComment() {
      var aiResponse =
          new ReviewResponse(
              List.of(
                  new ReviewResponse.Finding(
                      "low",
                      "src/Style.java",
                      1,
                      "Formatting",
                      "Missing trailing newline",
                      null,
                      "\n")),
              List.of(),
              null);

      var result =
          orchestrator.buildResult(
              aiResponse, true, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals(1, result.lowCount());
      assertEquals(RiskLevel.LOW, result.highestRisk());
      assertEquals(ReviewState.COMMENT, result.reviewState());
    }

    @Test
    void shouldTrackHighestRiskAcrossMixedFindings() {
      var aiResponse =
          new ReviewResponse(
              List.of(
                  new ReviewResponse.Finding("low", "f1", 1, "Low risk", "desc", null, null),
                  new ReviewResponse.Finding(
                      "critical", "f2", 2, "Critical risk", "desc", "old", "new"),
                  new ReviewResponse.Finding("medium", "f3", 3, "Medium risk", "desc", null, null),
                  new ReviewResponse.Finding("high", "f4", 4, "High risk", "desc", "old", "new")),
              List.of(),
              null);

      var result =
          orchestrator.buildResult(
              aiResponse, true, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals(4, result.totalFindings());
      assertEquals(1, result.criticalCount());
      assertEquals(1, result.highCount());
      assertEquals(1, result.mediumCount());
      assertEquals(1, result.lowCount());
      assertEquals(RiskLevel.CRITICAL, result.highestRisk());
      assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    }

    @Test
    void shouldIncludePreviousFindingStatuses() {
      var aiStatuses =
          List.of(
              new ReviewResponse.PreviousFindingStatus(1, "resolved", "Fixed in this PR"),
              new ReviewResponse.PreviousFindingStatus(2, "unresolved", "Still an issue"));

      var mappedStatuses =
          List.of(
              new ReviewResult.PreviousFindingStatus(1, "resolved", "Fixed in this PR"),
              new ReviewResult.PreviousFindingStatus(2, "unresolved", "Still an issue"));

      when(followUpAnalyzer.toStatuses(aiStatuses)).thenReturn(mappedStatuses);

      var aiResponse =
          new ReviewResponse(
              List.of(
                  new ReviewResponse.Finding(
                      "high", "src/X.java", 1, "Old issue", "Still present", "old", "new")),
              aiStatuses,
              null);

      var result =
          orchestrator.buildResult(
              aiResponse, false, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals(2, result.previousStatuses().size());
      verify(followUpAnalyzer).toStatuses(aiStatuses);
    }

    @Test
    void shouldGenerateSummaryForFirstReviewWithFindings() {
      when(summaryGenerator.generate(eq(4), eq(120), eq(45), any(), any(), any()))
          .thenReturn("## Summary\n\nTest summary content");

      var aiResponse =
          new ReviewResponse(
              List.of(
                  new ReviewResponse.Finding(
                      "medium", "src/Y.java", 12, "Issue", "Description", null, null)),
              List.of(),
              null);

      var result =
          orchestrator.buildResult(
              aiResponse, true, new ReviewOrchestrator.DiffStats(4, 120, 45), List.of());

      assertTrue(result.summaryMarkdown().contains("Test summary content"));
      verify(summaryGenerator).generate(eq(4), eq(120), eq(45), any(), any(), any());
    }

    @Test
    void shouldGenerateSummaryEvenWhenNoFindings() {
      var aiResponse = new ReviewResponse(List.of(), List.of(), null);
      when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
          .thenReturn("Clean summary with celebration");

      var result =
          orchestrator.buildResult(
              aiResponse, true, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals("Clean summary with celebration", result.summaryMarkdown());
      verify(summaryGenerator).generate(eq(0), eq(0), eq(0), any(), any(), any());
    }

    @Test
    void shouldMapUnknownRiskToLow() {
      var aiResponse =
          new ReviewResponse(
              List.of(
                  new ReviewResponse.Finding(
                      "unknown-severity",
                      "src/Z.java",
                      5,
                      "Weird",
                      "Unknown risk level from AI",
                      null,
                      null)),
              List.of(),
              null);

      var result =
          orchestrator.buildResult(
              aiResponse, true, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals(1, result.lowCount());
      assertEquals(RiskLevel.LOW, result.highestRisk());
      assertEquals(ReviewState.COMMENT, result.reviewState());
    }
  }

  @Nested
  class CheckRunPresentation {

    @Test
    void checkSummaryForResultShouldUseZeroIssuesMessageWhenClean() {
      var result =
          new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

      String summary = ReviewOrchestrator.checkSummaryForResult(result);

      assertTrue(summary.contains("Everything's coming up Thrillhouse"));
      assertTrue(summary.contains("No issues found"));
    }

    @Test
    void checkSummaryForResultShouldSummarizeFindingCountsWhenIssuesPresent() {
      var result =
          new ReviewResult(
              List.of(new Finding(RiskLevel.HIGH, "f", 1, "t", "d", null, null)),
              0,
              1,
              0,
              0,
              RiskLevel.HIGH,
              ReviewState.REQUEST_CHANGES,
              true,
              "",
              List.of());

      String summary = ReviewOrchestrator.checkSummaryForResult(result);

      assertEquals("1 findings: 0 critical, 1 high, 0 medium, 0 low", summary);
    }

    @Test
    void checkTitleForResultShouldAppendCheckmarkWhenClean() {
      var result =
          new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

      String title = ReviewOrchestrator.checkTitleForResult(result);

      assertEquals("ThrillhouseBot Review ✅", title);
    }

    @Test
    void shouldNotCelebrateWhenCiChecksAreOffendingDespiteNoFindings() {
      // No findings and nothing unresolved, but a required check is failing: the bot's own check
      // run must not show the green ✅ title nor the zero-issues celebration summary.
      var offending = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", "failure"));
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), offending);

      assertFalse(ReviewOrchestrator.checkTitleForResult(result).contains("✅"));
      String summary = ReviewOrchestrator.checkSummaryForResult(result);
      assertFalse(summary.contains("Everything's coming up Thrillhouse"));
      assertTrue(summary.contains("required CI check(s) are still pending or failing"));
    }
  }

  @Nested
  class ReviewErrorPaths {

    @Test
    void shouldPassResolvedInstructionsContentToAiReview() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(
                new GitHubCheckRunClient.RequiredStatusChecks(
                    List.of("required-build"), List.of()));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(
                new InstructionsResolver.ResolvedInstructions(
                    "Focus on security", ".github/thrillhousebot.md"));
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenThrow(new RuntimeException("stop early"));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        var inputsCaptor = ArgumentCaptor.forClass(AiReviewService.PromptInputs.class);
        verify(aiReviewService).review(eq(session), inputsCaptor.capture());
        var instructionsSection = inputsCaptor.getValue().repoInstructions();
        assertTrue(instructionsSection.contains(PromptTemplateEscaper.escape("Focus on security")));
        assertTrue(instructionsSection.contains("(from .github/thrillhousebot.md)"));
      }
    }

    @Test
    void shouldInjectDiagramRequestIntoPromptWhenDiagramEnabled() {
      when(diagramConfig.enabled()).thenReturn(true);

      assertTrue(captureRepoInstructions().contains("Control-Flow Diagram Request"));
    }

    @Test
    void shouldNotInjectDiagramRequestWhenDiagramDisabled() {
      // diagramConfig.enabled() defaults to false from setUp()
      assertFalse(captureRepoInstructions().contains("Control-Flow Diagram Request"));
    }

    /** Runs review() far enough to capture the prompt and returns its repoInstructions slot. */
    private String captureRepoInstructions() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        // Stop right after the prompt is built so the test stays focused on prompt assembly.
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenThrow(new RuntimeException("stop early"));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        var inputsCaptor = ArgumentCaptor.forClass(AiReviewService.PromptInputs.class);
        verify(aiReviewService).review(eq(session), inputsCaptor.capture());
        return inputsCaptor.getValue().repoInstructions();
      }
    }

    @Test
    void shouldPersistFailedStatusAndBroadcastWhenPrReviewerThrows() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(
                List.of(
                    new GitHubPullRequestClient.FileDiff(
                        "src/Main.java", "modified", 5, 3, 8, "@@ patch")));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);

        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenThrow(new RuntimeException("AI service unavailable"));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        verify(session).setStatus(ReviewSession.STATUS_FAILED);
        verify(session)
            .setErrorMessage(argThat(msg -> msg != null && msg.contains("AI service unavailable")));

        @SuppressWarnings("unchecked") // Generic <SessionEvent> capture unavoidable with Mockito
        var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
        verify(broadcaster, times(2)).broadcast(captor.capture());
        var events = captor.getAllValues();
        assertEquals("review.started", events.get(0).type());
        assertEquals("review.failed", events.get(1).type());

        verify(commentClient)
            .createComment(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(
                    req ->
                        req.body().contains("ThrillhouseBot review could not" + " be completed")));
      }
    }

    @Test
    void shouldHandleFailureWithoutUpdatingCheckRunWhenCreateFails() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("GitHub check run API error"));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        verify(checkRunClient, never())
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        verify(session).setStatus(ReviewSession.STATUS_FAILED);
        verify(commentClient)
            .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
      }
    }

    @Test
    void shouldCompleteFailureFlowWhenCheckRunUpdateAlsoFails() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(
                new GitHubCheckRunClient.RequiredStatusChecks(
                    List.of("required-build"), List.of()));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenThrow(new RuntimeException("AI service unavailable"));
        doThrow(new RuntimeException("422 Unprocessable Entity"))
            .doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        verify(checkRunClient, times(2))
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), eq(1L), any());
        verify(session).setStatus(ReviewSession.STATUS_FAILED);
        verify(commentClient)
            .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(broadcaster, times(2)).broadcast(any());
      }
    }

    @Test
    void shouldNotPostPrReviewWhenCheckRunUpdateFailsAfterCleanAiReview() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(
                new GitHubCheckRunClient.RequiredStatusChecks(
                    List.of("required-build"), List.of()));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));
        doThrow(new RuntimeException("422 Unprocessable Entity"))
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        verify(reviewClient, never())
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(session).setStatus(ReviewSession.STATUS_FAILED);
        verify(commentClient)
            .createComment(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> req.body().contains("review could not be completed")));
      }
    }

    @Test
    void shouldNotApproveWhenPrFilesFetchFails() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        // A transient files-fetch failure must not be swallowed into an empty diff + false APPROVE.
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("500 Server Error"));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        // No review event of any kind (no APPROVE); the review takes the failure path instead.
        verify(reviewClient, never())
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(session).setStatus(ReviewSession.STATUS_FAILED);
        verify(commentClient)
            .createComment(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> req.body().contains("could not be completed")));
      }
    }

    @Test
    void shouldApproveWithBodylessReviewAndPostCelebrationInsideSummaryWhenNoFindings() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(
                new GitHubCheckRunClient.RequiredStatusChecks(
                    List.of("required-build"), List.of()));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));
        when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
            .thenReturn("## Summary\nEverything's coming up Thrillhouse! 🎉");

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        // The approval itself carries no body; the celebration lives in the summary comment
        verify(reviewClient)
            .createReview(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> "APPROVE".equals(req.event()) && req.body().isEmpty()));
        verify(commentClient)
            .createComment(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> req.body().contains("Everything's coming up Thrillhouse")));
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
      }
    }

    @Test
    void shouldForwardModelSuggestedLabelsToTheLabeler() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        // Return a real label so buildLabelGuidance produces a non-blank string — this exercises
        // the labelGuidance.isBlank() = false branch in the orchestrator.
        when(labeler.fetchExistingLabels(anyString(), anyString(), anyString()))
            .thenReturn(List.of(new GitHubLabelClient.Label("bug", null, "ededed")));
        // Non-null summary carrying suggested labels — exercises the orchestrator's label hand-off.
        var summary =
            new ReviewResponse.Summary(
                0, 0, 0, 0, 0, "looks good", "adds a thing", List.of(), List.of("bug", "docs"));
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), summary));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        var captor = ArgumentCaptor.forClass(PrLabeler.LabelRequest.class);
        verify(labeler).applyOrSuggest(captor.capture());
        assertEquals(List.of("bug", "docs"), captor.getValue().suggested());
        assertTrue(captor.getValue().isFirstReview());
      }
    }

    @Test
    void shouldNotPostRetryNoticeWhenAStepFailsAfterTheResultIsPosted() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(labeler.fetchExistingLabels(anyString(), anyString(), anyString()))
            .thenReturn(List.of());
        var summary =
            new ReviewResponse.Summary(
                0, 0, 0, 0, 0, "looks good", "adds a thing", List.of(), List.of());
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), summary));
        // A trailing step blows up only AFTER the verdict, summary and review are already posted.
        doThrow(new RuntimeException("labeler boom")).when(labeler).applyOrSuggest(any());

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        // The clean approval was posted — the result is surfaced.
        verify(reviewClient)
            .createReview(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> "APPROVE".equals(req.event())));
        // ...so the post-result failure must NOT post a "could not be completed — retry" notice,
        // flip the session to failed, or broadcast a failure over the posted result.
        verify(commentClient, never())
            .createComment(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> req.body().contains("could not be completed")));
        verify(session, never()).setStatus(ReviewSession.STATUS_FAILED);
      }
    }

    @Test
    void shouldReuseFetchedReviewsToDismissPendingInsteadOfReListing() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        // A stale pending review left by the bot, returned by the single listReviews fetch.
        var pending =
            new GitHubReviewClient.ReviewResponse(
                99L, "", "PENDING", "sha", new GitHubReviewClient.ReviewResponse.User(BOT_LOGIN));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of(pending));
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(labeler.fetchExistingLabels(anyString(), anyString(), anyString()))
            .thenReturn(List.of());
        var summary =
            new ReviewResponse.Summary(
                0, 0, 0, 0, 0, "looks good", "adds a thing", List.of(), List.of());
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), summary));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        // listReviews is fetched exactly once for the whole run — dismissal reuses that list
        // ...
        verify(reviewClient, times(1))
            .listReviews(anyString(), anyString(), anyString(), anyString(), anyInt());
        // ...and the bot's stale pending review is still dismissed from the reused list.
        verify(reviewClient)
            .deletePendingReview(
                anyString(), anyString(), eq("owner"), eq("repo"), eq(42), eq(99L));
      }
    }

    @Test
    void shouldPersistAiResponseJsonWhenUpdatingCompletedSession() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(
                new GitHubCheckRunClient.RequiredStatusChecks(
                    List.of("required-build"), List.of()));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));

        doAnswer(
                invocation -> {
                  when(session.getAiResponseJson()).thenReturn(invocation.getArgument(0));
                  return null;
                })
            .when(session)
            .setAiResponseJson(anyString());

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        var updater = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(sessionPersistence).update(eq(1L), updater.capture());

        var managed = new ReviewSession();
        updater.getValue().accept(managed);
        assertNotNull(managed.getAiResponseJson());
        assertTrue(managed.getAiResponseJson().contains("findings"));
      }
    }

    @Test
    void shouldPostInlineCommentsWithoutHollowPrReview() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of(fileDiffWithLine("src/Main.java", 10)));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(suggestionFormatter.formatReviewComment(any())).thenReturn("**Medium** — fix this");
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(
                new ReviewResponse(
                    List.of(
                        new ReviewResponse.Finding(
                            "medium", "src/Main.java", 10, "Style", "Improve naming", null, null)),
                    List.of(),
                    null));
        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        verify(reviewClient)
            .createPullRequestComment(
                anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(reviewClient, never())
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
      }
    }

    @Test
    void shouldPostSummaryIssueCommentOnFirstReviewWithFindings() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of(fileDiffWithLine("src/Main.java", 10)));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(summaryGenerator.generate(eq(1), eq(1), eq(1), any(), any(), any()))
            .thenReturn("## 🤖 ThrillhouseBot PR Summary");
        when(suggestionFormatter.formatReviewComment(any())).thenReturn("**Medium** — fix this");
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(
                new ReviewResponse(
                    List.of(
                        new ReviewResponse.Finding(
                            "medium", "src/Main.java", 10, "Style", "Improve naming", null, null)),
                    List.of(),
                    null));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        verify(reviewClient, never())
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        // The summary must top the PR conversation: posted before the inline finding comments
        var inOrder = inOrder(commentClient, reviewClient);
        inOrder
            .verify(commentClient)
            .createComment(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> req.body().contains("ThrillhouseBot PR Summary")));
        inOrder
            .verify(reviewClient)
            .createPullRequestComment(
                anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
      }
    }

    @Test
    void shouldSkipSummaryIssueCommentOnFollowUpReviewWithFindings() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(
                new GitHubCheckRunClient.RequiredStatusChecks(
                    List.of("required-build"), List.of()));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(
                List.of(
                    new GitHubReviewClient.ReviewResponse(
                        1L,
                        "",
                        "APPROVED",
                        "abc12345",
                        new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]"))));
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(followUpAnalyzer.buildPreviousFindingsContext(any(), any(), any(), any(), eq(BOT_ID)))
            .thenReturn("Previous finding context");
        // Two prior rounds: the newest becomes the numbered context, the rest the history
        when(sessionPersistence.findAllPriorAiResponseJsons("owner/repo", 42, 1L))
            .thenReturn(List.of("{\"round\":2}", "{\"round\":1}"));
        when(followUpAnalyzer.toStatuses(any())).thenReturn(List.of());
        when(suggestionFormatter.formatReviewComment(any())).thenReturn("**Medium** — fix this");
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(
                new ReviewResponse(
                    List.of(
                        new ReviewResponse.Finding(
                            "medium", "src/Main.java", 10, "Style", "Improve naming", null, null)),
                    List.of(),
                    null));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        verify(commentClient, never())
            .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        // The newest prior round feeds the numbered context; the older ones the answered list
        verify(followUpAnalyzer)
            .buildPreviousFindingsContext(
                eq("{\"round\":2}"), any(), any(), eq(List.of("{\"round\":1}")), eq(BOT_ID));
        verify(followUpAnalyzer)
            .dropRepliedDuplicates(
                any(), eq(List.of("{\"round\":2}", "{\"round\":1}")), any(), eq(BOT_ID));
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
      }
    }

    @Test
    void shouldSkipSummaryWhenABotSummaryCommentAlreadyExistsButNoReviewDoes() {
      // Regression for the duplicate-summary bug: a first round held back only by pending CI posts
      // the summary issue-comment but leaves no review. On the next round no bot review
      // exists, yet the summary must not be re-posted — the prior summary comment is the signal.
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of(fileDiffWithLine("src/Main.java", 10)));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        // No bot review on the PR ...
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        // ... but the bot already posted its summary comment on an earlier round.
        when(commentClient.listComments(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(
                List.of(
                    new GitHubCommentClient.IssueComment(
                        PrSummaryGenerator.SUMMARY_HEADING + "\n\nAlready posted earlier",
                        new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]"))));
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(summaryGenerator.generate(eq(1), eq(1), eq(1), any(), any(), any()))
            .thenReturn(PrSummaryGenerator.SUMMARY_HEADING);
        when(suggestionFormatter.formatReviewComment(any())).thenReturn("**Medium** — fix this");
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(
                new ReviewResponse(
                    List.of(
                        new ReviewResponse.Finding(
                            "medium", "src/Main.java", 10, "Style", "Improve naming", null, null)),
                    List.of(),
                    null));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        // The summary issue-comment is NOT posted a second time ...
        verify(commentClient, never())
            .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        // ... but the rest of the review still runs: the finding is posted inline.
        verify(reviewClient)
            .createPullRequestComment(
                anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
      }
    }

    @Test
    void shouldUseFallbackMessageWhenCompareCommitsThrows() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(
                List.of(
                    new GitHubPullRequestClient.FileDiff(
                        "src/Main.java", "modified", 5, 3, 8, "@@ patch")));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Compare API error"));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);

        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        verify(aiReviewService).review(any(ReviewSession.class), any());
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
      }
    }
  }

  @Nested
  class CheckRunUpdates {

    @Test
    void shouldCreateCheckRunAsInProgress() {
      when(checkRunClient.createCheckRun(anyString(), anyString(), eq("owner"), eq("repo"), any()))
          .thenReturn(new GitHubCheckRunClient.CheckRunResponse(99L, "http://check"));

      var id = orchestrator.createCheckRun("Bearer tok", "owner", "repo", "abcdefgh", SESSION_URL);

      assertEquals(99L, id);
      var captor = ArgumentCaptor.forClass(GitHubCheckRunClient.CreateCheckRunRequest.class);
      verify(checkRunClient)
          .createCheckRun(anyString(), anyString(), eq("owner"), eq("repo"), captor.capture());
      assertEquals("in_progress", captor.getValue().status());
      assertEquals(SESSION_URL, captor.getValue().detailsUrl());
    }

    @Test
    void shouldIncludeCompletedAtOnlyForCompletedStatusUpdate() {
      doNothing()
          .when(checkRunClient)
          .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());

      orchestrator.updateCheckRun(newCheckRunUpdate("completed", "success"));

      var completedCaptor =
          ArgumentCaptor.forClass(GitHubCheckRunClient.UpdateCheckRunRequest.class);
      verify(checkRunClient)
          .updateCheckRun(
              anyString(),
              anyString(),
              eq("owner"),
              eq("repo"),
              eq(42L),
              completedCaptor.capture());
      assertNull(completedCaptor.getValue().status());
      assertEquals("success", completedCaptor.getValue().conclusion());
      assertNotNull(completedCaptor.getValue().completedAt());
      assertTrue(completedCaptor.getValue().completedAt().endsWith("Z"));
      assertFalse(completedCaptor.getValue().completedAt().contains("."));

      clearInvocations(checkRunClient);

      orchestrator.updateCheckRun(newCheckRunUpdate("in_progress", null));

      var inProgressCaptor =
          ArgumentCaptor.forClass(GitHubCheckRunClient.UpdateCheckRunRequest.class);
      verify(checkRunClient)
          .updateCheckRun(
              anyString(),
              anyString(),
              eq("owner"),
              eq("repo"),
              eq(42L),
              inProgressCaptor.capture());
      assertEquals("in_progress", inProgressCaptor.getValue().status());
      assertNull(inProgressCaptor.getValue().conclusion());
      assertNull(inProgressCaptor.getValue().completedAt());
    }

    @Test
    void shouldRetryWithConclusionOnlyWhenCompletionUpdateFails() {
      doThrow(new RuntimeException("422 Unprocessable Entity"))
          .doNothing()
          .when(checkRunClient)
          .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());

      orchestrator.updateCheckRun(newCheckRunUpdate("completed", "failure"));

      var captor = ArgumentCaptor.forClass(GitHubCheckRunClient.UpdateCheckRunRequest.class);
      verify(checkRunClient, times(2))
          .updateCheckRun(
              anyString(), anyString(), eq("owner"), eq("repo"), eq(42L), captor.capture());

      var fallback = captor.getAllValues().get(1);
      assertNull(fallback.status());
      assertEquals("failure", fallback.conclusion());
      assertNull(fallback.completedAt());
      assertNull(fallback.output());
    }

    private ReviewOrchestrator.CheckRunUpdate newCheckRunUpdate(String status, String conclusion) {
      return new ReviewOrchestrator.CheckRunUpdate(
          "Bearer tok", "owner", "repo", 42L, status, conclusion, "Title", "Summary", SESSION_URL);
    }
  }

  @Nested
  class GitHubReviewSubmission {

    @Test
    void shouldDismissPendingBotReviews() {
      var pending =
          new GitHubReviewClient.ReviewResponse(
              99L, "", "PENDING", "sha", new GitHubReviewClient.ReviewResponse.User(BOT_LOGIN));
      var approved =
          new GitHubReviewClient.ReviewResponse(
              1L, "", "APPROVED", "sha", new GitHubReviewClient.ReviewResponse.User("other-user"));
      // A pending review by a human must be left alone: state is PENDING but the author is not the
      // bot, so only the bot-identity check (not the state check) keeps it.
      var pendingHuman =
          new GitHubReviewClient.ReviewResponse(
              2L, "", "PENDING", "sha", new GitHubReviewClient.ReviewResponse.User("other-user"));

      orchestrator.dismissPendingBotReviews(
          "Bearer tok", "owner", "repo", 7, List.of(pending, approved, pendingHuman));

      verify(reviewClient)
          .deletePendingReview(
              eq("Bearer tok"), anyString(), eq("owner"), eq("repo"), eq(7), eq(99L));
      verify(reviewClient, never())
          .deletePendingReview(
              anyString(), anyString(), anyString(), anyString(), anyInt(), eq(1L));
      verify(reviewClient, never())
          .deletePendingReview(
              anyString(), anyString(), anyString(), anyString(), anyInt(), eq(2L));
    }

    @Test
    void shouldContinueWhenDismissPendingReviewsFails() {
      var pending =
          new GitHubReviewClient.ReviewResponse(
              99L, "", "PENDING", "sha", new GitHubReviewClient.ReviewResponse.User(BOT_LOGIN));
      doThrow(new RuntimeException("GitHub unavailable"))
          .when(reviewClient)
          .deletePendingReview(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong());

      // A delete that fails (e.g. GitHub unavailable) must not propagate out of dismissal.
      assertDoesNotThrow(
          () ->
              orchestrator.dismissPendingBotReviews(
                  "Bearer tok", "owner", "repo", 7, List.of(pending)));
    }

    @Test
    void shouldCreateReviewWithoutFallbackWhenFirstAttemptSucceeds() {
      var req = new GitHubReviewClient.CreateReviewRequest("sha", "body", "COMMENT", List.of());

      orchestrator.createReviewWithFallback("Bearer tok", "owner", "repo", 7, req);

      verify(reviewClient)
          .createReview(eq("Bearer tok"), anyString(), eq("owner"), eq("repo"), eq(7), same(req));
    }

    @Test
    void shouldSubmitRequestChangesReviewWhenCriticalFindingsPostedInline() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getPrTitle()).thenReturn("Test PR");
        when(session.getCommitSha()).thenReturn("abcdefgh");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        doNothing()
            .when(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of(fileDiffWithLine("src/Main.java", 10)));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(suggestionFormatter.formatReviewComment(any(), anyBoolean())).thenReturn("critical");
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(
                new ReviewResponse(
                    List.of(
                        new ReviewResponse.Finding(
                            "critical",
                            "src/Main.java",
                            10,
                            "SQLi",
                            "Unsanitized input",
                            "old",
                            "new")),
                    List.of(),
                    null));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "Test PR",
                "",
                "base1234567",
                "main",
                123L,
                false));

        verify(reviewClient)
            .createPullRequestComment(
                anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(reviewClient)
            .createReview(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> "REQUEST_CHANGES".equals(req.event())));
      }
    }

    @Test
    void shouldFallbackWhenInlineCommentsRejected() {
      var comment =
          new GitHubReviewClient.ReviewComment(
              "src/Main.java", 10, null, null, "RIGHT", "Fix this");
      var req =
          new GitHubReviewClient.CreateReviewRequest("sha", "body", "COMMENT", List.of(comment));

      doThrow(new RuntimeException("422"))
          .doReturn(
              new GitHubReviewClient.ReviewResponse(
                  1L, "ok", "COMMENTED", "sha", new GitHubReviewClient.ReviewResponse.User("bot")))
          .when(reviewClient)
          .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());

      orchestrator.createReviewWithFallback("Bearer tok", "owner", "repo", 7, req);

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient, times(2))
          .createReview(
              eq("Bearer tok"), anyString(), eq("owner"), eq("repo"), eq(7), captor.capture());
      assertTrue(captor.getAllValues().get(1).comments().isEmpty());
    }

    @Test
    void shouldThrowReviewPostExceptionWhenFallbackImpossible() {
      var req = new GitHubReviewClient.CreateReviewRequest("sha", "body", "COMMENT", List.of());

      var rejection = new RuntimeException("422");
      when(reviewClient.createReview(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
          .thenThrow(rejection);

      ReviewPostException ex =
          assertThrows(
              ReviewPostException.class,
              () -> orchestrator.createReviewWithFallback("Bearer tok", "owner", "repo", 7, req));

      assertTrue(ex.getMessage().contains("owner/repo #7"));
      assertSame(rejection, ex.getCause());
      verify(reviewClient, times(1))
          .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }
  }

  @Nested
  class ReviewFailureHandling {

    private ReviewOrchestrator.ReviewRequest reviewRequest() {
      return new ReviewOrchestrator.ReviewRequest(
          "owner", "repo", 42, "abcdefgh", "Test PR", "", "base1234567", "main", 123L, false);
    }

    @Test
    void shouldHandleReviewFailureWithoutCheckRun() {
      var session = new ReviewSession();
      session.id = 1L;
      session.setRepository("owner/repo");
      session.setPrNumber(42);
      session.setPrTitle("Test PR");
      session.setCommitSha("abcdefgh");
      session.setTimestamp(java.time.Instant.parse("2025-06-01T12:00:00Z"));

      orchestrator.handleReviewFailure(
          "Bearer tok", reviewRequest(), session, 0L, new RuntimeException("boom"));

      verify(commentClient)
          .createComment(eq("Bearer tok"), anyString(), eq("owner"), eq("repo"), eq(42), any());
      var updater = ArgumentCaptor.forClass(java.util.function.Consumer.class);
      verify(sessionPersistence).update(eq(1L), updater.capture());
      var managed = new ReviewSession();
      updater.getValue().accept(managed);
      assertEquals(ReviewSession.STATUS_FAILED, managed.getStatus());
      assertEquals("boom", managed.getErrorMessage());
      verify(broadcaster).broadcast(any(SessionEventBroadcaster.SessionEvent.class));
      assertEquals(ReviewSession.STATUS_FAILED, session.getStatus());
      assertEquals("boom", session.getErrorMessage());
    }

    @Test
    void shouldUseUnknownErrorWhenFailureHasNoMessage() {
      var session = new ReviewSession();
      session.id = 3L;
      session.setRepository("owner/repo");
      session.setPrNumber(42);
      session.setPrTitle("Test PR");
      session.setCommitSha("abcdefgh");
      session.setTimestamp(java.time.Instant.parse("2025-06-01T12:00:00Z"));

      orchestrator.handleReviewFailure(
          "Bearer tok", reviewRequest(), session, 0L, new RuntimeException());

      assertEquals("Unknown error", session.getErrorMessage());
    }

    @Test
    void shouldContinueWhenCheckRunFailureUpdateFails() {
      var session = new ReviewSession();
      session.id = 2L;
      session.setRepository("owner/repo");
      session.setPrNumber(42);
      session.setPrTitle("Test PR");
      session.setCommitSha("abcdefgh");
      session.setTimestamp(java.time.Instant.parse("2025-06-01T12:00:00Z"));

      doThrow(new RuntimeException("check run update failed"))
          .when(checkRunClient)
          .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());

      orchestrator.handleReviewFailure(
          "Bearer tok", reviewRequest(), session, 99L, new RuntimeException("boom"));

      verify(commentClient)
          .createComment(eq("Bearer tok"), anyString(), eq("owner"), eq("repo"), eq(42), any());
      verify(sessionPersistence).update(eq(2L), any());
    }
  }

  @Nested
  class DiffAndConclusionHelpers {

    @Test
    void shouldReturnSuccessConclusionForApprovedReview() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, RiskLevel.LOW, ReviewState.APPROVE, true, "", List.of());

      String conclusion = ReviewOrchestrator.conclusionForResult(result);

      assertEquals("success", conclusion);
    }

    @Test
    void shouldReturnFailureConclusionForRequestChangesReview() {
      var result =
          new ReviewResult(
              List.of(),
              0,
              1,
              0,
              0,
              RiskLevel.HIGH,
              ReviewState.REQUEST_CHANGES,
              true,
              "",
              List.of());

      assertEquals("failure", ReviewOrchestrator.conclusionForResult(result));
    }

    @Test
    void shouldReturnNeutralConclusionForCommentReview() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 1, 0, RiskLevel.MEDIUM, ReviewState.COMMENT, true, "", List.of());

      assertEquals("neutral", ReviewOrchestrator.conclusionForResult(result));
    }
  }

  @Nested
  class PostInlineComments {

    private ReviewResult resultWithFinding(Finding finding, ReviewState state) {
      return new ReviewResult(
          List.of(finding),
          finding.risk() == RiskLevel.CRITICAL ? 1 : 0,
          finding.risk() == RiskLevel.HIGH ? 1 : 0,
          finding.risk() == RiskLevel.MEDIUM ? 1 : 0,
          finding.risk() == RiskLevel.LOW ? 1 : 0,
          finding.risk(),
          state,
          true,
          "",
          List.of());
    }

    @Test
    void shouldSkipFindingsOutsideDiff() {
      var finding = new Finding(RiskLevel.MEDIUM, "missing.java", 10, "Bug", "desc", null, null);
      var result = resultWithFinding(finding, ReviewState.COMMENT);
      var resolver = new DiffLineResolver(Map.of("src/Main.java", "@@ +1,1 @@\n+line"));

      var posted =
          orchestrator.postInlineComments(
              "Bearer tok", "owner", "repo", 7, "sha", result, resolver);

      assertEquals(0, posted);
      verify(reviewClient, never())
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldPostToNearestLineWhenExactLineMissing() {
      var finding = new Finding(RiskLevel.HIGH, "src/Main.java", 15, "Bug", "desc", null, null);
      var result = resultWithFinding(finding, ReviewState.REQUEST_CHANGES);
      var resolver =
          new DiffLineResolver(
              Map.of("src/Main.java", fileDiffWithLine("src/Main.java", 10).patch()));

      when(suggestionFormatter.formatReviewComment(finding, true)).thenReturn("comment body");

      var posted =
          orchestrator.postInlineComments(
              "Bearer tok", "owner", "repo", 7, "sha", result, resolver);

      assertEquals(1, posted);
      verify(reviewClient)
          .createPullRequestComment(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(req -> req.line() == 10));
    }

    @Test
    void shouldRetryWithoutSuggestionWhenFirstInlineCommentRejected() {
      var finding =
          new Finding(RiskLevel.HIGH, "src/Main.java", 10, "Bug", "desc", "old code", "new code");
      var result = resultWithFinding(finding, ReviewState.REQUEST_CHANGES);
      var resolver =
          new DiffLineResolver(
              Map.of("src/Main.java", fileDiffWithLine("src/Main.java", 10).patch()));

      when(suggestionFormatter.formatReviewComment(finding, true)).thenReturn("with suggestion");
      when(suggestionFormatter.formatReviewComment(finding, false))
          .thenReturn("without suggestion");
      doThrow(new RuntimeException("422"))
          .doReturn(
              new GitHubReviewClient.PullRequestCommentResponse(1L, "ok", "src/Main.java", 10))
          .when(reviewClient)
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());

      var posted =
          orchestrator.postInlineComments(
              "Bearer tok", "owner", "repo", 7, "sha", result, resolver);

      assertEquals(1, posted);
      verify(reviewClient, times(2))
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldSkipFindingWhenInlineCommentRejectedTwice() {
      var finding =
          new Finding(RiskLevel.HIGH, "src/Main.java", 10, "Bug", "desc", "old code", "new code");
      var result = resultWithFinding(finding, ReviewState.REQUEST_CHANGES);
      var resolver =
          new DiffLineResolver(
              Map.of("src/Main.java", fileDiffWithLine("src/Main.java", 10).patch()));

      when(suggestionFormatter.formatReviewComment(any(), anyBoolean())).thenReturn("body");
      doThrow(new RuntimeException("422"))
          .when(reviewClient)
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());

      var posted =
          orchestrator.postInlineComments(
              "Bearer tok", "owner", "repo", 7, "sha", result, resolver);

      assertEquals(0, posted);
      verify(reviewClient, times(2))
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldRespectMaxReviewCommentsLimit() {
      when(reviewConfig.maxReviewComments()).thenReturn(1);
      var first = new Finding(RiskLevel.MEDIUM, "src/A.java", 10, "One", "desc", null, null);
      var second = new Finding(RiskLevel.MEDIUM, "src/B.java", 20, "Two", "desc", null, null);
      var result =
          new ReviewResult(
              List.of(first, second),
              0,
              0,
              2,
              0,
              RiskLevel.MEDIUM,
              ReviewState.COMMENT,
              true,
              "",
              List.of());
      var resolver =
          new DiffLineResolver(
              Map.of(
                  "src/A.java", fileDiffWithLine("src/A.java", 10).patch(),
                  "src/B.java", fileDiffWithLine("src/B.java", 20).patch()));
      when(suggestionFormatter.formatReviewComment(any(), eq(true))).thenReturn("body");

      var posted =
          orchestrator.postInlineComments(
              "Bearer tok", "owner", "repo", 7, "sha", result, resolver);

      assertEquals(1, posted);
      verify(reviewClient, times(1))
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldPointToSummaryInReviewBodyWhenNoneAnchorInlineOnFirstReview() {
      // The finding's file is not in the diff, so no inline comment anchors (posted == 0). On a
      // first review the findings are in the summary comment, so the review body points there — but
      // a
      // review event is still posted so the findings never vanish behind a bare check.
      var finding = new Finding(RiskLevel.MEDIUM, "missing.java", 10, "Bug", "desc", null, null);
      var result = resultWithFinding(finding, ReviewState.COMMENT); // isFirstReview = true

      orchestrator.postReview(
          "Bearer tok",
          "owner",
          "repo",
          7,
          "sha",
          result,
          List.of(fileDiffWithLine("src/Main.java", 10)));

      verify(reviewClient)
          .createReview(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(
                  req ->
                      "COMMENT".equals(req.event())
                          && req.body().contains("could not be anchored")
                          && req.body().contains("PR summary")));
    }

    @Test
    void shouldListFindingsInReviewBodyWhenNoneAnchorInlineOnFollowUp() {
      // Regression: on a follow-up review (which posts no summary comment) findings whose
      // lines fall outside the diff must still be listed in the review body, not show only as a red
      // check run.
      var finding =
          new Finding(RiskLevel.CRITICAL, "missing.java", 10, "Auth bypass", "desc", null, null);
      var result =
          new ReviewResult(
              List.of(finding),
              1,
              0,
              0,
              0,
              RiskLevel.CRITICAL,
              ReviewState.REQUEST_CHANGES,
              false, // follow-up: no summary comment is posted
              "",
              List.of());

      orchestrator.postReview(
          "Bearer tok",
          "owner",
          "repo",
          7,
          "sha",
          result,
          List.of(fileDiffWithLine("src/Main.java", 10)));

      verify(reviewClient)
          .createReview(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(
                  req ->
                      "REQUEST_CHANGES".equals(req.event())
                          && req.body().contains("Auth bypass")
                          && req.body().contains("missing.java:10")));
    }

    @Test
    void shouldApproveCleanPrFromPostReview() {
      var result =
          new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

      orchestrator.postReview("Bearer tok", "owner", "repo", 7, "sha", result, List.of());

      // First review: the celebration lives in the summary comment, not the approval body
      verify(reviewClient)
          .createReview(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(req -> "APPROVE".equals(req.event()) && req.body().isEmpty()));
      verify(reviewClient, never())
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldApproveCleanFollowUpWithCelebrationBody() {
      // Follow-up reviews post no summary, so the celebration stays in the approval body
      var result =
          new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, false, "", List.of());

      orchestrator.postReview("Bearer tok", "owner", "repo", 7, "sha", result, List.of());

      verify(reviewClient)
          .createReview(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(
                  req ->
                      "APPROVE".equals(req.event())
                          && req.body().contains("Everything's coming up Thrillhouse")));
    }

    @Test
    void shouldIgnoreFilesWithoutPatchContent() {
      var finding = new Finding(RiskLevel.MEDIUM, "src/Main.java", 10, "Bug", "desc", null, null);
      var result = resultWithFinding(finding, ReviewState.COMMENT);
      when(suggestionFormatter.formatReviewComment(finding, true)).thenReturn("comment");

      orchestrator.postReview(
          "Bearer tok",
          "owner",
          "repo",
          7,
          "sha",
          result,
          List.of(
              new GitHubPullRequestClient.FileDiff("empty.java", "modified", 0, 0, 0, ""),
              new GitHubPullRequestClient.FileDiff("nopatch.java", "modified", 1, 1, 2, null),
              fileDiffWithLine("src/Main.java", 10)));

      verify(reviewClient, times(1))
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldNotRetryWithoutSuggestionWhenFindingHasNoSuggestionBlock() {
      var finding = new Finding(RiskLevel.HIGH, "src/Main.java", 10, "Bug", "desc", null, null);
      var result = resultWithFinding(finding, ReviewState.REQUEST_CHANGES);
      var resolver =
          new DiffLineResolver(
              Map.of("src/Main.java", fileDiffWithLine("src/Main.java", 10).patch()));

      when(suggestionFormatter.formatReviewComment(finding, true)).thenReturn("body");
      doThrow(new RuntimeException("422"))
          .when(reviewClient)
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());

      var posted =
          orchestrator.postInlineComments(
              "Bearer tok", "owner", "repo", 7, "sha", result, resolver);

      assertEquals(0, posted);
      verify(reviewClient, times(1))
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());
      verify(suggestionFormatter, never()).formatReviewComment(finding, false);
    }

    @Test
    void shouldPostMultiLineCommentWhenSuggestionSpansSeveralLines() {
      var patch =
          """
          @@ -10,1 +10,4 @@
           keep()
          +first_new()
          +second_new()
          +third_new()
          """;
      var finding =
          new Finding(
              RiskLevel.HIGH,
              "src/Main.java",
              13,
              "Bug",
              "desc",
              "first_new()\nsecond_new()\nthird_new()",
              "fixed()");
      var result = resultWithFinding(finding, ReviewState.REQUEST_CHANGES);
      var resolver = new DiffLineResolver(Map.of("src/Main.java", patch));

      var posted =
          orchestrator.postInlineComments(
              "Bearer tok", "owner", "repo", 7, "sha", result, resolver);

      assertEquals(1, posted);
      // The suggestion's old code spans lines 11-13, so the comment is posted as that range.
      verify(reviewClient, times(1))
          .createPullRequestComment(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(
                  req ->
                      req.line() == 13
                          && req.startLine() != null
                          && req.startLine() == 11
                          && "RIGHT".equals(req.side())
                          && "RIGHT".equals(req.startSide())));
    }

    @Test
    void shouldFallBackToSingleLineWhenMultiLineCommentRejected() {
      var patch =
          """
          @@ -10,1 +10,3 @@
           keep()
          +alpha()
          +beta()
          """;
      var finding =
          new Finding(
              RiskLevel.HIGH, "src/Main.java", 12, "Bug", "desc", "alpha()\nbeta()", "fixed()");
      var result = resultWithFinding(finding, ReviewState.REQUEST_CHANGES);
      var resolver = new DiffLineResolver(Map.of("src/Main.java", patch));

      doThrow(new RuntimeException("422"))
          .doReturn(
              new GitHubReviewClient.PullRequestCommentResponse(1L, "ok", "src/Main.java", 12))
          .when(reviewClient)
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());

      var posted =
          orchestrator.postInlineComments(
              "Bearer tok", "owner", "repo", 7, "sha", result, resolver);

      assertEquals(1, posted);
      // First attempt is the multi-line range [11, 12] ...
      verify(reviewClient)
          .createPullRequestComment(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(req -> req.startLine() != null && req.startLine() == 11 && req.line() == 12));
      // ... the retry without a suggestion drops the range back to a single line.
      verify(reviewClient)
          .createPullRequestComment(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(req -> req.startLine() == null && req.line() == 12));
    }
  }

  @Nested
  class HandleReviewFailure {

    private ReviewOrchestrator.ReviewRequest reviewRequest() {
      return new ReviewOrchestrator.ReviewRequest(
          "owner", "repo", 7, "sha", "title", "", "base", "main", 99L, false);
    }

    private ReviewSession reviewSession() {
      var session = new ReviewSession();
      session.id = 42L;
      session.setPublicId("test-public-id");
      session.setRepository("owner/repo");
      session.setPrNumber(7);
      session.setPrTitle("title");
      session.setCommitSha("sha");
      session.setTimestamp(java.time.Instant.parse("2025-06-01T12:00:00Z"));
      return session;
    }

    @Test
    void shouldBroadcastFailedEventWhenCommentPostFails() {
      var session = reviewSession();
      doThrow(new RuntimeException("PR closed"))
          .when(commentClient)
          .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());

      orchestrator.handleReviewFailure(
          "Bearer tok", reviewRequest(), session, 123L, new RuntimeException("AI failed"));

      verify(broadcaster).broadcast(any(SessionEventBroadcaster.SessionEvent.class));
      verify(sessionPersistence).update(eq(42L), any());
      assertEquals(ReviewSession.STATUS_FAILED, session.getStatus());
    }

    @Test
    void shouldBroadcastFailedEventEvenWhenPersistenceFails() {
      var session = reviewSession();
      doThrow(new RuntimeException("DB unavailable"))
          .when(sessionPersistence)
          .update(eq(42L), any());

      orchestrator.handleReviewFailure(
          "Bearer tok", reviewRequest(), session, -1L, new RuntimeException("AI failed"));

      verify(sessionPersistence).update(eq(42L), any());
      verify(broadcaster).broadcast(any(SessionEventBroadcaster.SessionEvent.class));
      assertEquals(ReviewSession.STATUS_FAILED, session.getStatus());
    }

    @Test
    void shouldIncludeSessionLinkInCheckRunButNotFailureComment() {
      var session = reviewSession();

      orchestrator.handleReviewFailure(
          "Bearer tok", reviewRequest(), session, 123L, new RuntimeException("AI failed"));

      var commentCaptor = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
      verify(commentClient)
          .createComment(
              anyString(), anyString(), eq("owner"), eq("repo"), eq(7), commentCaptor.capture());
      assertFalse(commentCaptor.getValue().body().contains(SESSION_URL));

      var checkRunCaptor =
          ArgumentCaptor.forClass(GitHubCheckRunClient.UpdateCheckRunRequest.class);
      verify(checkRunClient)
          .updateCheckRun(
              anyString(),
              anyString(),
              eq("owner"),
              eq("repo"),
              eq(123L),
              checkRunCaptor.capture());
      assertEquals(SESSION_URL, checkRunCaptor.getValue().detailsUrl());
    }
  }

  @Nested
  class SessionUrlHelper {

    private ReviewSession sessionWithPublicId(String publicId) {
      var session = new ReviewSession();
      session.setPublicId(publicId);
      return session;
    }

    @Test
    void shouldBuildSessionDeepLinkFromPublicId() {
      assertEquals(
          "https://bot.example/session/test-public-id",
          orchestrator.sessionUrl(sessionWithPublicId("test-public-id")));
    }

    @Test
    void shouldStripTrailingSlashFromConfiguredUrl() {
      ThrillhouseConfig.DashboardConfig slashConfig = mock(ThrillhouseConfig.DashboardConfig.class);
      when(config.dashboard()).thenReturn(slashConfig);
      when(slashConfig.url()).thenReturn("https://bot.example/");

      assertEquals(
          "https://bot.example/session/test-public-id",
          orchestrator.sessionUrl(sessionWithPublicId("test-public-id")));
    }

    @Test
    void shouldFallBackToSessionsListWhenPublicIdMissing() {
      assertEquals(
          "https://bot.example/dashboard/sessions/",
          orchestrator.sessionUrl(sessionWithPublicId(null)));
      assertEquals(
          "https://bot.example/dashboard/sessions/",
          orchestrator.sessionUrl(sessionWithPublicId("  ")));
    }
  }

  @Nested
  class DiffStatsHelper {

    @Test
    void shouldAggregateAdditionsAndDeletionsFromFiles() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff("a.java", "modified", 3, 2, 5, "patch"),
              new GitHubPullRequestClient.FileDiff("b.java", "modified", 1, 4, 5, "patch"));

      ReviewOrchestrator.DiffStats stats = ReviewOrchestrator.DiffStats.fromFiles(files, 0);

      assertEquals(2, stats.filesChanged());
      assertEquals(4, stats.additions());
      assertEquals(6, stats.deletions());
      assertEquals(0, stats.omittedFiles());
      assertFalse(stats.truncated());
    }

    @Test
    void toChangedFilesShouldProjectPathAndStatusInOrder() {
      var files =
          List.of(
              new GitHubPullRequestClient.FileDiff("a.java", "added", 3, 0, 3, "patch"),
              new GitHubPullRequestClient.FileDiff("b.java", "renamed", 0, 0, 0, null));

      var changed = ReviewOrchestrator.toChangedFiles(files);

      assertEquals(2, changed.size());
      assertEquals("a.java", changed.get(0).path());
      assertEquals("added", changed.get(0).changeType());
      assertEquals("b.java", changed.get(1).path());
      assertEquals("renamed", changed.get(1).changeType());
    }
  }

  @Nested
  class ApplyReviewResult {

    @Test
    void shouldApplyCompletionFieldsToSessionAndPersistence() {
      var session = new ReviewSession();
      session.id = 7L;
      session.setAiResponseJson("{\"findings\":[]}");
      var result =
          new ReviewResult(
              List.of(),
              1,
              2,
              3,
              4,
              RiskLevel.HIGH,
              ReviewState.REQUEST_CHANGES,
              true,
              "",
              List.of());

      orchestrator.applyReviewResult(session, result);

      assertEquals(ReviewSession.STATUS_COMPLETED, session.getStatus());
      assertEquals(1, session.getCriticalFindings());
      assertEquals(2, session.getHighFindings());
      assertEquals(3, session.getMediumFindings());
      assertEquals(4, session.getLowFindings());

      var updater = ArgumentCaptor.forClass(java.util.function.Consumer.class);
      verify(sessionPersistence).update(eq(7L), updater.capture());
      var managed = new ReviewSession();
      updater.getValue().accept(managed);
      assertEquals(ReviewSession.STATUS_COMPLETED, managed.getStatus());
      assertEquals(1, managed.getCriticalFindings());
      assertEquals(2, managed.getHighFindings());
      assertEquals(3, managed.getMediumFindings());
      assertEquals(4, managed.getLowFindings());
      assertEquals("{\"findings\":[]}", managed.getAiResponseJson());
    }

    @Test
    void shouldSkipAiResponseJsonWhenAbsent() {
      var session = new ReviewSession();
      session.id = 8L;
      var result =
          new ReviewResult(List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of());

      orchestrator.applyReviewResult(session, result);

      var updater = ArgumentCaptor.forClass(java.util.function.Consumer.class);
      verify(sessionPersistence).update(eq(8L), updater.capture());
      var managed = new ReviewSession();
      updater.getValue().accept(managed);
      assertNull(managed.getAiResponseJson());
    }
  }

  @Nested
  class PersistAiResponse {

    @Test
    void shouldSerializeReviewResponseIntoSession() {
      var session = new ReviewSession();
      var response = new ReviewResponse(List.of(), List.of(), null);

      orchestrator.persistAiResponse(session, response);

      assertNotNull(session.getAiResponseJson());
    }

    @Test
    void shouldHandleSerializationFailureGracefully() throws Exception {
      var session = new ReviewSession();

      // ObjectMapper that always fails — passed through the constructor
      ObjectMapper badMapper = mock(ObjectMapper.class);
      when(badMapper.writeValueAsString(any()))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("fail") {});
      var failingOrchestrator = newOrchestrator(badMapper);

      var response = new ReviewResponse(List.of(), List.of(), null);
      failingOrchestrator.persistAiResponse(session, response);

      assertNull(session.getAiResponseJson());
    }
  }

  @Nested
  class PopulateMissingAnchors {

    @Test
    void shouldPopulateMissingAnchorsFromDiff() {
      var patch =
          """
          @@ -10,3 +10,4 @@
           def unchanged():
          -    old_line()
          +    new_line()
          +    added_line()
          """;
      var lineResolver = new DiffLineResolver(Map.of("main.py", patch));
      var findingWithAnchor =
          new ReviewResponse.Finding(
              "high", "high", "main.py", 11, "Title", "Desc", "existing_anchor", "new_line()");
      var findingWithoutAnchor =
          new ReviewResponse.Finding(
              "high", "high", "main.py", 11, "Title", "Desc", null, "new_line()");
      var findingOnContextLine =
          new ReviewResponse.Finding(
              "high", "high", "main.py", 10, "Title", "Desc", "   ", "new_line()");
      var findingOutsideDiff =
          new ReviewResponse.Finding(
              "high", "high", "main.py", 99, "Title", "Desc", null, "new_line()");

      var response =
          new ReviewResponse(
              List.of(
                  findingWithAnchor,
                  findingWithoutAnchor,
                  findingOnContextLine,
                  findingOutsideDiff),
              List.of(),
              null);

      var updated = orchestrator.populateMissingAnchors(response, lineResolver);

      assertEquals(4, updated.findings().size());

      // 1. Finding with existing anchor is left unchanged
      assertEquals("existing_anchor", updated.findings().get(0).suggestionOld());

      // 2. Finding without anchor is populated from diff
      assertEquals("    new_line()", updated.findings().get(1).suggestionOld());

      // 3. Finding on context line is populated from diff
      assertEquals("def unchanged():", updated.findings().get(2).suggestionOld());

      // 4. Finding outside diff has no anchor and is left null
      assertNull(updated.findings().get(3).suggestionOld());
    }

    @Test
    void shouldReturnOriginalResponseWhenFindingsIsEmpty() {
      var lineResolver = new DiffLineResolver(Map.of());
      var response = new ReviewResponse(List.of(), List.of(), null);
      var updated = orchestrator.populateMissingAnchors(response, lineResolver);
      assertSame(response, updated);
    }

    @Test
    void shouldNotPopulateWhenSuggestionOldIsNotBlank() {
      var patch =
          """
          @@ -10,3 +10,4 @@
           def unchanged():
          -    old_line()
          +    new_line()
          +    added_line()
          """;
      var lineResolver = new DiffLineResolver(Map.of("main.py", patch));
      var finding =
          new ReviewResponse.Finding(
              "high", "high", "main.py", 11, "Title", "Desc", "  anchor  ", "new_line()");
      var response = new ReviewResponse(List.of(finding), List.of(), null);
      var updated = orchestrator.populateMissingAnchors(response, lineResolver);
      assertSame(response, updated);
    }

    @Test
    void shouldNotPopulateWhenFallbackIsBlank() {
      var patch =
          """
          @@ -1,3 +1,3 @@
          +first()
          +
          +third()
          """;
      var lineResolver = new DiffLineResolver(Map.of("main.py", patch));
      var finding =
          new ReviewResponse.Finding(
              "high", "high", "main.py", 2, "Title", "Desc", null, "new_line()");
      var response = new ReviewResponse(List.of(finding), List.of(), null);
      var updated = orchestrator.populateMissingAnchors(response, lineResolver);
      assertSame(response, updated);
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

      var resolved = orchestrator.resolveMissingPrDetails("Bearer tok", manualRequest(""));

      assertEquals("headsha1234", resolved.commitSha());
      assertEquals("basesha5678", resolved.baseSha());
      assertEquals("add new API", resolved.prTitle());
      assertEquals("Adds a new API endpoint", resolved.prDescription());
      assertTrue(resolved.isManualTrigger());
    }

    @Test
    void shouldNotFetchWhenShaIsPresent() {
      var req = manualRequest("abcdefgh");

      var resolved = orchestrator.resolveMissingPrDetails("Bearer tok", req);

      assertSame(req, resolved);
      verifyNoInteractions(prClient);
    }

    @Test
    void shouldTolerateMissingRefsInPrDetails() {
      when(prClient.getPullRequest(anyString(), anyString(), eq("owner"), eq("repo"), eq(7)))
          .thenReturn(new GitHubPullRequestClient.PullRequestDetails(null, null, null, null));

      var resolved = orchestrator.resolveMissingPrDetails("Bearer tok", manualRequest(null));

      assertEquals("", resolved.commitSha());
      assertEquals("", resolved.baseSha());
      assertEquals("(manual review)", resolved.prTitle());
      assertEquals("", resolved.prDescription());
    }

    @Test
    void reviewShouldPostFailureCommentWhenPrResolutionThrows() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(7);
        when(session.getPrTitle()).thenReturn("(manual review)");
        when(session.getCommitSha()).thenReturn("");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(prClient.getPullRequest(anyString(), anyString(), eq("owner"), eq("repo"), eq(7)))
            .thenThrow(new RuntimeException("PR was deleted"));

        orchestrator.review(manualRequest(""));

        // A failing PR fetch takes the standard failure path instead of escaping to the
        // dispatcher: failure comment, failed session, broadcast — and no check run calls
        verify(commentClient)
            .createComment(
                anyString(),
                anyString(),
                eq("owner"),
                eq("repo"),
                eq(7),
                argThat(req -> req.body().contains("could not be completed")));
        verify(session).setStatus(ReviewSession.STATUS_FAILED);
        verify(broadcaster, times(2)).broadcast(any(SessionEventBroadcaster.SessionEvent.class));
        verify(checkRunClient, never())
            .createCheckRun(anyString(), anyString(), anyString(), anyString(), any());
      }
    }

    @Test
    void reviewShouldUpdateSessionWithResolvedTitleAndSha() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(7);
        when(session.getPrTitle()).thenReturn("(manual review)");
        when(session.getCommitSha()).thenReturn("");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
            .thenReturn(
                new GitHubPullRequestClient.PullRequestDetails(
                    "add new API",
                    "Adds a new API endpoint",
                    new GitHubPullRequestClient.Ref("headsha1234", "feature"),
                    new GitHubPullRequestClient.Ref("basesha5678", "main")));
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(
                new GitHubCheckRunClient.RequiredStatusChecks(
                    List.of("required-build"), List.of()));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));

        orchestrator.review(manualRequest(""));

        // The session is created with the placeholder, then updated with resolved PR details
        verify(session).setPrTitle("add new API");
        verify(session).setCommitSha("headsha1234");
        var checkRunCaptor =
            ArgumentCaptor.forClass(GitHubCheckRunClient.CreateCheckRunRequest.class);
        verify(checkRunClient)
            .createCheckRun(
                anyString(), anyString(), eq("owner"), eq("repo"), checkRunCaptor.capture());
        assertEquals("headsha1234", checkRunCaptor.getValue().headSha());
      }
    }

    @Test
    void reviewShouldCatchExceptionWhenFetchingStatusChecks() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(7);
        when(session.getPrTitle()).thenReturn("(manual review)");
        when(session.getCommitSha()).thenReturn("");
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
        when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(7)))
            .thenReturn(
                new GitHubPullRequestClient.PullRequestDetails(
                    "add new API",
                    "Adds a new API endpoint",
                    new GitHubPullRequestClient.Ref("headsha1234", "feature"),
                    new GitHubPullRequestClient.Ref("basesha5678", "main")));
        when(prClient.getPullRequestFiles(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("failed"));
        when(checkRunClient.createCheckRun(
                anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
        when(prClient.compareCommits(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner", "repo", 7, "", "(manual review)", "", "", "main", 123L, true));

        // The thrown getRequiredStatusChecks must be swallowed: the review still finalizes its
        // check run and posts a review rather than failing the whole run.
        verify(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        verify(reviewClient)
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
      }
    }
  }

  @Nested
  class UnresolvedPreviousGating {

    private final FollowUpAnalyzer realAnalyzer = new FollowUpAnalyzer(new ObjectMapper());

    private void delegateFollowUpAnalyzer() {
      when(followUpAnalyzer.toStatuses(any()))
          .thenAnswer(invocation -> realAnalyzer.toStatuses(invocation.getArgument(0)));
      when(followUpAnalyzer.hasUnresolved(any()))
          .thenAnswer(invocation -> realAnalyzer.hasUnresolved(invocation.getArgument(0)));
    }

    private ReviewResponse responseWithStatuses(ReviewResponse.PreviousFindingStatus... statuses) {
      return new ReviewResponse(List.of(), List.of(statuses), null);
    }

    @Test
    void shouldCommentInsteadOfApproveWhenPreviousFindingUnresolved() {
      delegateFollowUpAnalyzer();
      var aiResponse =
          responseWithStatuses(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still"));
      var unresolvedPrevious = List.of(new Finding(RiskLevel.MEDIUM, "f", 1, "t", "d", null, null));

      var result =
          orchestrator.buildResult(
              aiResponse, false, new ReviewOrchestrator.DiffStats(0, 0, 0), unresolvedPrevious);

      assertEquals(ReviewState.COMMENT, result.reviewState());
      assertFalse(ReviewOrchestrator.checkTitleForResult(result).contains("✅"));
      assertTrue(
          ReviewOrchestrator.checkSummaryForResult(result)
              .contains("1 previous finding(s) remain unresolved"));
    }

    @Test
    void shouldRequestChangesWhenUnresolvedPreviousFindingIsBlocking() {
      delegateFollowUpAnalyzer();
      var aiResponse =
          responseWithStatuses(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still"));
      var unresolvedPrevious =
          List.of(new Finding(RiskLevel.CRITICAL, Confidence.HIGH, "f", 1, "t", "d", null, null));

      var result =
          orchestrator.buildResult(
              aiResponse, false, new ReviewOrchestrator.DiffStats(0, 0, 0), unresolvedPrevious);

      assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    }

    @Test
    void shouldHoldApprovalWhenUnresolvedStatusCannotBeMappedToAFinding() {
      delegateFollowUpAnalyzer();
      var aiResponse =
          responseWithStatuses(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still"));

      var result =
          orchestrator.buildResult(
              aiResponse, false, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals(ReviewState.COMMENT, result.reviewState());
    }

    @Test
    void shouldApproveWhenAllPreviousFindingsAddressed() {
      delegateFollowUpAnalyzer();
      var aiResponse =
          responseWithStatuses(
              new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"),
              new ReviewResponse.PreviousFindingStatus(2, "justified", "intentional"));

      var result =
          orchestrator.buildResult(
              aiResponse, false, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of());

      assertEquals(ReviewState.APPROVE, result.reviewState());
      assertTrue(ReviewOrchestrator.checkTitleForResult(result).contains("✅"));
      assertTrue(ReviewOrchestrator.checkSummaryForResult(result).contains("No issues found"));
    }

    @Test
    void postReviewShouldPostCommentReviewWhenUnresolvedRemain() {
      var result =
          new ReviewResult(
              List.of(),
              0,
              0,
              0,
              0,
              null,
              ReviewState.COMMENT,
              false,
              "",
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still")));

      orchestrator.postReview("auth", "owner", "repo", 5, "sha", result, List.of());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("COMMENT", captor.getValue().event());
      assertTrue(captor.getValue().body().contains("remain unresolved"));
    }

    @Test
    void postReviewShouldRequestChangesWhenUnresolvedRemainBlocking() {
      var result =
          new ReviewResult(
              List.of(),
              0,
              0,
              0,
              0,
              null,
              ReviewState.REQUEST_CHANGES,
              false,
              "",
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still")));

      orchestrator.postReview("auth", "owner", "repo", 5, "sha", result, List.of());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("REQUEST_CHANGES", captor.getValue().event());
    }

    @Test
    void postReviewShouldSkipDuplicateCommentReviewOnFirstReviewWhenOnlyCiPending() {
      // First review, no findings, but a required check is still pending: the summary comment
      // already conveys this, so postReview must not add a COMMENT review that duplicates it.
      var result =
          new ReviewResult(
              List.of(),
              0,
              0,
              0,
              0,
              null,
              ReviewState.COMMENT,
              true,
              "",
              List.of(),
              List.of(new ReviewResult.CiCheck("build", "check-run", "pending", null)));

      orchestrator.postReview("auth", "owner", "repo", 5, "sha", result, List.of());

      verify(reviewClient, never())
          .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void postReviewShouldStillPostCommentReviewOnFollowUpWhenCiPending() {
      // A follow-up review posts no summary comment, so the COMMENT review remains its only signal.
      var result =
          new ReviewResult(
              List.of(),
              0,
              0,
              0,
              0,
              null,
              ReviewState.COMMENT,
              false,
              "",
              List.of(),
              List.of(new ReviewResult.CiCheck("build", "check-run", "pending", null)));

      orchestrator.postReview("auth", "owner", "repo", 5, "sha", result, List.of());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("COMMENT", captor.getValue().event());
      assertTrue(captor.getValue().body().contains("**build**"));
    }
  }

  @Nested
  class ApproveBackstop {

    private final FollowUpAnalyzer realAnalyzer = new FollowUpAnalyzer(new ObjectMapper());

    private void delegateStatusGate() {
      when(followUpAnalyzer.toStatuses(any()))
          .thenAnswer(invocation -> realAnalyzer.toStatuses(invocation.getArgument(0)));
      when(followUpAnalyzer.hasUnresolved(any()))
          .thenAnswer(invocation -> realAnalyzer.hasUnresolved(invocation.getArgument(0)));
    }

    private ReviewResult buildWithBackstop(List<ReviewResult.PreviousFindingStatus> backstop) {
      return orchestrator.buildResult(
          new ReviewResponse(List.of(), List.of(), null),
          false,
          new ReviewOrchestrator.DiffStats(0, 0, 0),
          List.of(), // changedFiles
          List.of(),
          List.of(),
          backstop);
    }

    @Test
    void shouldDowngradeApproveToCommentWhenBackstopHoldsUnresolved() {
      delegateStatusGate();

      var result =
          buildWithBackstop(
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still present")));

      assertEquals(ReviewState.COMMENT, result.reviewState());
      assertFalse(ReviewOrchestrator.checkTitleForResult(result).contains("✅"));
      // The message reflects the held finding — never the contradictory "0 previous finding(s)".
      assertTrue(
          ReviewOrchestrator.checkSummaryForResult(result)
              .contains("1 previous finding(s) remain unresolved"));
    }

    @Test
    void shouldApproveWhenBackstopHoldsNothing() {
      delegateStatusGate();

      var result = buildWithBackstop(List.of());

      assertEquals(ReviewState.APPROVE, result.reviewState());
      assertTrue(ReviewOrchestrator.checkTitleForResult(result).contains("✅"));
      assertTrue(ReviewOrchestrator.checkSummaryForResult(result).contains("No issues found"));
    }

    @Test
    void shouldNeverEscalateBackstopBeyondComment() {
      delegateStatusGate();

      // Even several silently dropped findings only neutralize approval — never REQUEST_CHANGES.
      var result =
          buildWithBackstop(
              List.of(
                  new ReviewResult.PreviousFindingStatus(1, "unresolved", "a"),
                  new ReviewResult.PreviousFindingStatus(2, "unresolved", "b")));

      assertEquals(ReviewState.COMMENT, result.reviewState());
    }

    @Test
    void shouldPostCommentReviewWhenBackstopHolds() {
      delegateStatusGate();
      var result =
          buildWithBackstop(
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still present")));

      orchestrator.postReview("auth", "owner", "repo", 5, "sha", result, List.of());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("COMMENT", captor.getValue().event());
      assertTrue(captor.getValue().body().contains("remain unresolved"));
    }

    @Test
    void shouldQualifyReplyGuidanceSinceABackstopFindingMayHaveNoThread() {
      // Issue 133 case a: a backstop-held finding can be summary-only when its flagged line was
      // outside the diff at the time it was first raised, so no inline thread was ever posted for
      // it. The COMMENT guidance must then qualify the reply path rather than point the maintainer
      // at a thread that is not present.
      delegateStatusGate();
      var result =
          buildWithBackstop(
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still present")));

      orchestrator.postReview("auth", "owner", "repo", 5, "sha", result, List.of());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      var body = captor.getValue().body();
      assertTrue(body.contains("where one exists"), "the reply guidance must be qualified");
      assertFalse(
          body.contains("reply on their threads"),
          "must not unconditionally promise a thread that may not exist");
    }

    @Test
    void shouldHoldApproveOverSilentlyDroppedPriorFindingThroughReview() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = followUpSession();
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);
        delegateStatusGate();
        when(reviewClient.listPullRequestComments(
                anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(
                List.of(
                    new GitHubReviewClient.PullRequestComment(
                        1L,
                        null,
                        "src/Main.java",
                        "body",
                        new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]"))));
        when(sessionPersistence.findAllPriorAiResponseJsons("owner/repo", 42, 1L))
            .thenReturn(List.of(PRIOR_FINDING_JSON));
        when(followUpAnalyzer.buildPreviousFindingsContext(any(), any(), any(), any(), eq(BOT_ID)))
            .thenReturn("1. [MEDIUM] src/Main.java:10 — Dropped finding");
        // The model silently drops the still-open prior finding: no new findings, empty status.
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));
        // The deterministic backstop reconstructs it as unresolved from persistence.
        when(followUpAnalyzer.unreportedUnresolvedStatuses(any(), any(), any(), any(), eq(BOT_ID)))
            .thenReturn(
                List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still present")));

        orchestrator.review(followUpRequest());

        var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
        verify(reviewClient)
            .createReview(
                anyString(), anyString(), anyString(), anyString(), anyInt(), captor.capture());
        assertEquals("COMMENT", captor.getValue().event());
        assertTrue(captor.getValue().body().contains("remain unresolved"));
      }
    }

    @Test
    void shouldLoadContextFromPersistenceEvenWithoutBotReview() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = followUpSession();
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);
        // No formal bot review exists (listReviews defaults to empty), but a prior round persisted
        // its findings — context must still be reconstructed for follow-up analysis.
        when(sessionPersistence.findAllPriorAiResponseJsons("owner/repo", 42, 1L))
            .thenReturn(List.of(PRIOR_FINDING_JSON));
        when(followUpAnalyzer.buildPreviousFindingsContext(any(), any(), any(), any(), eq(BOT_ID)))
            .thenReturn("previous context");
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));

        orchestrator.review(followUpRequest());

        // Previous-findings context IS reconstructed without any formal bot review
        verify(followUpAnalyzer)
            .buildPreviousFindingsContext(any(), any(), any(), any(), eq(BOT_ID));
      }
    }

    @Test
    void shouldPostSummaryOnPersistedButUnreviewedPr() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = followUpSession();
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);
        // No formal bot review exists, but persistence holds a prior round's AI response.
        // The summary must still be posted: isFirstVisibleReview is true.
        when(sessionPersistence.findAllPriorAiResponseJsons("owner/repo", 42, 1L))
            .thenReturn(List.of(PRIOR_FINDING_JSON));
        when(followUpAnalyzer.buildPreviousFindingsContext(any(), any(), any(), any(), eq(BOT_ID)))
            .thenReturn("previous context");
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));
        when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
            .thenReturn("ThrillhouseBot PR Summary\n\nAll clear!");

        orchestrator.review(followUpRequest());

        // Summary IS posted because no bot review exists on the PR (first visible review)
        verify(commentClient)
            .createComment(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> req.body().contains("ThrillhouseBot PR Summary")));
        // Context IS still loaded from persistence
        verify(followUpAnalyzer)
            .buildPreviousFindingsContext(any(), any(), any(), any(), eq(BOT_ID));
      }
    }

    private static final String PRIOR_FINDING_JSON =
        "{\"findings\":[{\"risk\":\"medium\",\"file\":\"src/Main.java\",\"line\":10,"
            + "\"title\":\"Dropped finding\",\"description\":\"d\"}]}";

    private ReviewSession followUpSession() {
      var session = mock(ReviewSession.class);
      session.id = 1L;
      when(session.getRepository()).thenReturn("owner/repo");
      when(session.getPrNumber()).thenReturn(42);
      when(session.getPrTitle()).thenReturn("Test PR");
      when(session.getCommitSha()).thenReturn("abcdefgh");
      when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
      when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
      when(checkRunClient.createCheckRun(anyString(), anyString(), anyString(), anyString(), any()))
          .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
      doNothing()
          .when(checkRunClient)
          .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
      when(prClient.getPullRequestFiles(
              anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenReturn(List.of());
      when(prClient.compareCommits(
              anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
      when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
          .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
      return session;
    }

    private ReviewOrchestrator.ReviewRequest followUpRequest() {
      return new ReviewOrchestrator.ReviewRequest(
          "owner", "repo", 42, "abcdefgh", "Test PR", "", "base1234567", "main", 123L, false);
    }
  }

  @Nested
  class ResolveAddressedThreads {

    private static final String AUTH = "auth";

    private ReviewOrchestrator.ReviewRequest request() {
      return new ReviewOrchestrator.ReviewRequest(
          "owner", "repo", 5, "sha", "t", "", "base", "main", 1L, false);
    }

    private GitHubReviewClient.PullRequestComment rootComment() {
      return new GitHubReviewClient.PullRequestComment(
          100L,
          null,
          "f",
          "**CRITICAL — t**",
          new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]"));
    }

    @Test
    void shouldResolveThreadsForResolvedAndJustifiedFindings() {
      var statuses =
          List.of(
              new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"),
              new ReviewResponse.PreviousFindingStatus(2, "justified", "intentional"),
              new ReviewResponse.PreviousFindingStatus(3, "unresolved", "still"),
              new ReviewResponse.PreviousFindingStatus(4, "resolved", "fixed"));
      when(followUpAnalyzer.matchFindingThreads(any(), any(), any()))
          .thenReturn(Map.of(1, 100L, 2, 200L, 3, 300L, 4, 400L));
      when(reviewThreadService.threadsByRootComment(AUTH, "owner", "repo", 5))
          .thenReturn(
              Map.of(
                  100L, new ReviewThreadService.ThreadRef("T1", false),
                  200L, new ReviewThreadService.ThreadRef("T2", true),
                  300L, new ReviewThreadService.ThreadRef("T3", false),
                  400L, new ReviewThreadService.ThreadRef("T4", false)));
      when(reviewThreadService.resolve(AUTH, "T1")).thenReturn(true);
      // T4's resolution is attempted but GitHub does not confirm it — must not throw
      when(reviewThreadService.resolve(AUTH, "T4")).thenReturn(false);

      orchestrator.resolveAddressedThreads(AUTH, request(), "{}", List.of(rootComment()), statuses);

      verify(reviewThreadService).resolve(AUTH, "T1");
      verify(reviewThreadService).resolve(AUTH, "T4");
      // T2 is already resolved and T3's finding is still unresolved — neither is touched
      verify(reviewThreadService, never()).resolve(AUTH, "T2");
      verify(reviewThreadService, never()).resolve(AUTH, "T3");
    }

    @Test
    void shouldSkipFindingsWithoutAMatchedThread() {
      var statuses = List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"));
      when(followUpAnalyzer.matchFindingThreads(any(), any(), any())).thenReturn(Map.of());
      when(reviewThreadService.threadsByRootComment(AUTH, "owner", "repo", 5)).thenReturn(Map.of());

      orchestrator.resolveAddressedThreads(AUTH, request(), "{}", List.of(rootComment()), statuses);

      verify(reviewThreadService, never()).resolve(anyString(), anyString());
    }

    @Test
    void shouldDoNothingWhenNoFindingWasAddressedOrNoComments() {
      var unresolvedOnly =
          List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still"));
      var resolved = List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"));

      orchestrator.resolveAddressedThreads(
          AUTH, request(), "{}", List.of(rootComment()), unresolvedOnly);
      orchestrator.resolveAddressedThreads(AUTH, request(), "{}", List.of(), resolved);

      verifyNoInteractions(reviewThreadService);
    }

    @Test
    void shouldSwallowThreadResolutionFailures() {
      var statuses = List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"));
      when(followUpAnalyzer.matchFindingThreads(any(), any(), any())).thenReturn(Map.of(1, 100L));
      when(reviewThreadService.threadsByRootComment(
              anyString(), anyString(), anyString(), anyInt()))
          .thenThrow(new RuntimeException("graphql down"));

      assertDoesNotThrow(
          () ->
              orchestrator.resolveAddressedThreads(
                  AUTH, request(), "{}", List.of(rootComment()), statuses));
    }
  }

  @Nested
  class FetchPullRequestComments {

    @Test
    void shouldReturnEmptyListWhenClientThrows() {
      // The paginating client never returns null, so the only failure to absorb is the fetch
      // throwing — it degrades to an empty list rather than aborting the review.
      when(reviewClient.listPullRequestComments(
              anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenThrow(new RuntimeException("boom"));

      assertTrue(orchestrator.fetchPullRequestComments("auth", "owner", "repo", 1).isEmpty());
    }

    @Test
    void shouldReturnCommentsFromClient() {
      var comment =
          new GitHubReviewClient.PullRequestComment(
              1L, null, "f", "body", new GitHubReviewClient.ReviewResponse.User("u"));
      when(reviewClient.listPullRequestComments(
              anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenReturn(List.of(comment));

      assertEquals(
          List.of(comment), orchestrator.fetchPullRequestComments("auth", "owner", "repo", 1));
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

      assertTrue(orchestrator.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldTolerateLeadingWhitespaceBeforeTheHeading() {
      stubComments(
          comment("\n  " + PrSummaryGenerator.SUMMARY_HEADING + "\n", "thrillhousebot[bot]"));

      assertTrue(orchestrator.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldIgnoreASummaryHeadingPostedBySomeoneElse() {
      // A human (or another bot) quoting the heading must not be mistaken for the bot's own
      // summary.
      stubComments(comment(PrSummaryGenerator.SUMMARY_HEADING + "\n\nspoof", "impersonator"));

      assertFalse(orchestrator.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldIgnoreBotCommentsThatAreNotTheSummary() {
      // The bot also posts conversational replies (e.g. answering /review questions); only the
      // summary heading counts.
      stubComments(comment("I don't have enough context to answer that.", "thrillhousebot[bot]"));

      assertFalse(orchestrator.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldReturnFalseForEmptyComments() {
      stubComments();

      assertFalse(orchestrator.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldNotMatchTheHeadingMidComment() {
      // Only a comment that *starts* with the heading is the bot's summary; an embedded mention is
      // a
      // quote in a discussion, not the summary artifact.
      stubComments(
          comment("As noted in the " + PrSummaryGenerator.SUMMARY_HEADING, "thrillhousebot[bot]"));

      assertFalse(orchestrator.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldTolerateNullUserAndNullBody() {
      stubComments(
          comment("body without user", null),
          comment(null, "thrillhousebot[bot]"),
          comment(PrSummaryGenerator.SUMMARY_HEADING, "thrillhousebot[bot]"));

      assertTrue(orchestrator.botSummaryCommentExists("auth", "owner", "repo", 1));
    }

    @Test
    void shouldBeBestEffortWhenTheFetchThrows() {
      when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenThrow(new RuntimeException("boom"));

      // A thrown fetch falls back to "no summary seen" rather than blocking the review.
      // (listComments paginates internally and never returns null, so that case can't arise.)
      assertFalse(orchestrator.botSummaryCommentExists("auth", "owner", "repo", 1));
      assertTrue(orchestrator.fetchIssueComments("auth", "owner", "repo", 1).isEmpty());
    }
  }

  @Nested
  class ResolveProjectStack {

    @Test
    void shouldReturnEmptyWhenStackResolverThrows() {
      when(projectStackResolver.resolve(any(), any(), any(), anyLong()))
          .thenThrow(new RuntimeException("github down"));

      var stack =
          orchestrator.resolveProjectStack(
              new ReviewOrchestrator.ReviewRequest(
                  "owner", "repo", 1, "sha", "title", "", "base", "main", 123L, false));

      assertEquals("", stack);
    }
  }

  @Nested
  class EvaluateCiChecks {

    @Test
    void shouldIgnoreThrillhouseBotChecks() {
      // All checks below are failing; only the genuinely-non-bot ones must surface as offending,
      // proving the ThrillhouseBot checks are dropped by bot-detection rather than by being green.
      var app =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun.App(
              1L, "thrillhousebot", "ThrillhouseBot");
      var tbRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              1L, "ThrillhouseBot Review", "completed", "failure", app);
      var appSlugRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              10L,
              "Some Checks",
              "completed",
              "failure",
              new GitHubCheckRunClient.CheckRunsResponse.CheckRun.App(2L, "thrillhousebot", null));
      var appNameRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              11L,
              "Other Checks",
              "completed",
              "failure",
              new GitHubCheckRunClient.CheckRunsResponse.CheckRun.App(3L, null, "ThrillhouseBot"));
      var otherRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              2L, "build", "completed", "failure", null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CheckRunsResponse(
                  4, List.of(tbRun, appSlugRun, appNameRun, otherRun)));

      // Setup statuses
      var tbStatus =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(
              1L, "failure", "thrillhousebot-status", "desc");
      var otherStatus =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(2L, "failure", "lint", "desc");
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CombinedStatus(
                  "failure", 2, List.of(tbStatus, otherStatus)));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertEquals(2, result.size());
      assertTrue(result.stream().anyMatch(c -> "build".equals(c.name())));
      assertTrue(result.stream().anyMatch(c -> "lint".equals(c.name())));
      assertFalse(result.stream().anyMatch(c -> c.name().contains("thrillhousebot")));
    }

    @Test
    void shouldExcludePassingChecksFromOffendingList() {
      // Every check is green — the offending list must come back empty so APPROVE is not gated.
      var passRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              1L, "build", "completed", "success", null);
      var skippedRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              2L, "docs", "completed", "skipped", null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(2, List.of(passRun, skippedRun)));
      var passStatus =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(1L, "success", "lint", "desc");
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 1, List.of(passStatus)));

      // requiredContexts lists checks that are all green and already reported — none is missing.
      var result =
          orchestrator.evaluateCiChecks(
              "auth", "owner", "repo", "sha", List.of("build", "docs", "lint"));

      assertTrue(result.isEmpty());
    }

    @Test
    void shouldHoldApprovalWhenAStatusStateIsUnrecognized() {
      // A commit status whose state is neither success/pending/failure/error (here a malformed
      // value; null behaves identically) is not a confirmed pass — it must surface as a pending
      // offending check so it holds the approval rather than clearing the gate.
      var passRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              1L, "build", "completed", "success", null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(1, List.of(passRun)));
      var unknownStatus =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(2L, "weird", "deploy", "desc");
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CombinedStatus("pending", 1, List.of(unknownStatus)));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertEquals(1, result.size());
      var check = result.get(0);
      assertEquals("deploy", check.name());
      assertTrue(check.isPending());
      assertFalse(check.isFailing());
    }

    @Test
    void shouldFilterByRequiredContextsWhenProvided() {
      // All four checks are failing; only the two in requiredContexts must surface as offending.
      var run1 =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              1L, "build", "completed", "failure", null);
      var run2 =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              2L, "test", "completed", "failure", null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(2, List.of(run1, run2)));

      var status1 =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(1L, "failure", "lint", "desc");
      var status2 =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(2L, "failure", "deploy", "desc");
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CombinedStatus("failure", 2, List.of(status1, status2)));

      var result =
          orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", List.of("build", "lint"));

      assertEquals(2, result.size());
      assertTrue(result.stream().anyMatch(c -> "build".equals(c.name())));
      assertTrue(result.stream().anyMatch(c -> "lint".equals(c.name())));
      assertFalse(result.stream().anyMatch(c -> "test".equals(c.name())));
      assertFalse(result.stream().anyMatch(c -> "deploy".equals(c.name())));
    }

    @Test
    void shouldMarkMissingRequiredChecksAsPending() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(0, List.of()));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));

      var result =
          orchestrator.evaluateCiChecks(
              "auth", "owner", "repo", "sha", List.of("build", "thrillhousebot"));

      // thrillhousebot is ignored, so only "build" is missing
      assertEquals(1, result.size());
      var check = result.get(0);
      assertEquals("build", check.name());
      assertEquals("missing", check.type());
      assertEquals("pending", check.status());
    }

    @Test
    void shouldEvaluateStatusesAndConclusionsCorrectly() {
      // 1. Pending check run
      var runPending =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              1L, "build", "in_progress", null, null);
      // 2. Failed check run
      var runFailed =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              2L, "test", "completed", "failure", null);
      // 3. Skipped check run (success)
      var runSkipped =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              3L, "docs", "completed", "skipped", null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CheckRunsResponse(
                  3, List.of(runPending, runFailed, runSkipped)));

      // 4. Pending status
      var statusPending =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(1L, "pending", "lint", "desc");
      // 5. Error status
      var statusError =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(2L, "error", "security", "desc");
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CombinedStatus(
                  "pending", 2, List.of(statusPending, statusError)));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      // docs (skipped → passing) is excluded; only the four offending checks remain.
      assertEquals(4, result.size());

      var build = result.stream().filter(c -> "build".equals(c.name())).findFirst().orElseThrow();
      assertEquals("pending", build.status());

      var test = result.stream().filter(c -> "test".equals(c.name())).findFirst().orElseThrow();
      assertEquals("failing", test.status());

      assertFalse(result.stream().anyMatch(c -> "docs".equals(c.name())));

      var lint = result.stream().filter(c -> "lint".equals(c.name())).findFirst().orElseThrow();
      assertEquals("pending", lint.status());

      var security =
          result.stream().filter(c -> "security".equals(c.name())).findFirst().orElseThrow();
      assertEquals("failing", security.status());
    }

    @Test
    void shouldPageThroughCheckRunsUntilAShortPageIsReturned() {
      // A full first page (100 rows) must trigger a second fetch; the short second page stops it.
      var fullPage = new java.util.ArrayList<GitHubCheckRunClient.CheckRunsResponse.CheckRun>();
      for (int i = 0; i < 100; i++) {
        fullPage.add(
            new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                i, "check-" + i, "completed", "failure", null));
      }
      var lastPage =
          List.of(
              new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                  100L, "check-100", "completed", "failure", null));
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(101, fullPage))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(101, lastPage));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      // Both pages were consumed: 100 + 1 failing check runs.
      assertEquals(101, result.size());
      verify(checkRunClient).getCheckRuns(any(), any(), any(), any(), any(), eq(100), eq(1));
      verify(checkRunClient).getCheckRuns(any(), any(), any(), any(), any(), eq(100), eq(2));
    }

    @Test
    void shouldPageThroughCombinedStatusUntilAShortPageIsReturned() {
      var fullStatusPage =
          new java.util.ArrayList<GitHubCheckRunClient.CombinedStatus.StatusDetail>();
      for (int i = 0; i < 100; i++) {
        fullStatusPage.add(
            new GitHubCheckRunClient.CombinedStatus.StatusDetail(i, "failure", "ctx-" + i, "d"));
      }
      var lastStatusPage =
          List.of(
              new GitHubCheckRunClient.CombinedStatus.StatusDetail(
                  100L, "failure", "ctx-100", "d"));
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(0, List.of()));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("failure", 101, fullStatusPage))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("failure", 101, lastStatusPage));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertEquals(101, result.size());
      verify(checkRunClient).getCombinedStatus(any(), any(), any(), any(), any(), eq(100), eq(2));
    }

    @Test
    void shouldStopPagingAtTheMaxPagesGuard() {
      // Every page is full (CI_PER_PAGE rows) so the short-page break never fires; the loop must
      // terminate at the CI_MAX_PAGES guard rather than run forever.
      var fullRuns = new java.util.ArrayList<GitHubCheckRunClient.CheckRunsResponse.CheckRun>();
      var fullStatuses =
          new java.util.ArrayList<GitHubCheckRunClient.CombinedStatus.StatusDetail>();
      for (int i = 0; i < ReviewOrchestrator.CI_PER_PAGE; i++) {
        fullRuns.add(
            new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                i, "run-" + i, "completed", "failure", null));
        fullStatuses.add(
            new GitHubCheckRunClient.CombinedStatus.StatusDetail(i, "failure", "status-" + i, "d"));
      }
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CheckRunsResponse(ReviewOrchestrator.CI_PER_PAGE, fullRuns));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CombinedStatus(
                  "failure", ReviewOrchestrator.CI_PER_PAGE, fullStatuses));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      // Deduped to the distinct names across the repeated pages.
      assertEquals(2 * ReviewOrchestrator.CI_PER_PAGE, result.size());
      verify(checkRunClient, times(ReviewOrchestrator.CI_MAX_PAGES))
          .getCheckRuns(
              any(), any(), any(), any(), any(), eq(ReviewOrchestrator.CI_PER_PAGE), anyInt());
      verify(checkRunClient, times(ReviewOrchestrator.CI_MAX_PAGES))
          .getCombinedStatus(
              any(), any(), any(), any(), any(), eq(ReviewOrchestrator.CI_PER_PAGE), anyInt());
    }

    @Test
    void shouldBreakWhenCheckRunsAndStatusResponsesAreNull() {
      // Unmocked endpoints return null; the page loops must break immediately without error.
      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);
      assertTrue(result.isEmpty());
    }

    @Test
    void shouldTreatCompletedRunWithNullConclusionAsFailing() {
      var run =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(1L, "build", "completed", null, null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(1, List.of(run)));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertEquals(1, result.size());
      assertEquals("failing", result.get(0).status());
    }

    @Test
    void shouldDeduplicateContextReportedAsBothCheckRunAndStatus() {
      // The same failing context reported twice via check runs and once via status → listed once.
      var run =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              1L, "build", "completed", "failure", null);
      var dupRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              2L, "build", "completed", "failure", null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(2, List.of(run, dupRun)));
      var status =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(1L, "failure", "build", "d");
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("failure", 1, List.of(status)));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertEquals(1, result.size());
      assertEquals("build", result.get(0).name());
      assertEquals("check-run", result.get(0).type());
    }

    @Test
    void shouldKeepNonBotChecksWhoseAppAndNameDoNotMatch() {
      // Apps present but not ThrillhouseBot (one with a name, one without), plus a null-named run.
      var appWithName =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun.App(
              9L, "github-actions", "GitHub Actions");
      var appNoName = new GitHubCheckRunClient.CheckRunsResponse.CheckRun.App(8L, "circleci", null);
      var named =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              1L, "build", "completed", "failure", appWithName);
      var namelessApp =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              3L, "deploy", "completed", "failure", appNoName);
      var nullNamed =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              2L, null, "completed", "failure", null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CheckRunsResponse(
                  3, List.of(named, namelessApp, nullNamed)));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertEquals(3, result.size());
      assertTrue(result.stream().anyMatch(c -> "build".equals(c.name())));
      assertTrue(result.stream().anyMatch(c -> "deploy".equals(c.name())));
    }

    @Test
    void shouldHandleNullChecksAndStatusesGracefully() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(0, null));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("pending", 0, null));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);
      assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleExceptionsGracefully() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenThrow(new RuntimeException("failed"));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenThrow(new RuntimeException("failed"));

      var result = orchestrator.evaluateCiChecks("auth", "owner", "repo", "sha", null);
      assertTrue(result.isEmpty());
    }
  }

  @Nested
  class CiGatingThroughReview {

    private void stubCommonReviewMocks(GitHubCheckRunClient.CheckRunsResponse checkRuns) {
      when(authClient.getAuthHeader(123L)).thenReturn("Bearer test");
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenReturn(
              new GitHubPullRequestClient.PullRequestDetails(
                  "Test PR",
                  "",
                  new GitHubPullRequestClient.Ref("abcdefgh", "feature"),
                  new GitHubPullRequestClient.Ref("base1234567", "main")));
      when(checkRunClient.createCheckRun(anyString(), anyString(), anyString(), anyString(), any()))
          .thenReturn(new GitHubCheckRunClient.CheckRunResponse(1L, "http://check"));
      doNothing()
          .when(checkRunClient)
          .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
      when(prClient.getPullRequestFiles(
              anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenReturn(List.of());
      when(checkRunClient.getRequiredStatusChecks(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(new GitHubCheckRunClient.RequiredStatusChecks(List.of("build"), List.of()));
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(checkRuns);
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));
      when(prClient.compareCommits(
              anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(new GitHubPullRequestClient.CompareResponse(0, List.of()));
      when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
          .thenReturn(List.of());
      when(instructionsResolver.resolve(anyString(), anyString(), anyString(), anyLong()))
          .thenReturn(InstructionsResolver.ResolvedInstructions.EMPTY);
      when(aiReviewService.review(any(ReviewSession.class), any()))
          .thenReturn(new ReviewResponse(List.of(), List.of(), null));
    }

    private ReviewOrchestrator.ReviewRequest request() {
      return new ReviewOrchestrator.ReviewRequest(
          "owner", "repo", 42, "abcdefgh", "Test PR", "", "base1234567", "main", 123L, false);
    }

    @Test
    void reviewShouldApproveWhenRequiredChecksPass() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        // The single required check ("build") has already succeeded — APPROVE must stand.
        stubCommonReviewMocks(
            new GitHubCheckRunClient.CheckRunsResponse(
                1,
                List.of(
                    new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                        1L, "build", "completed", "success", null))));

        orchestrator.review(request());

        verify(reviewClient)
            .createReview(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> "APPROVE".equals(req.event())));
      }
    }

    @Test
    void reviewShouldDowngradeToNeutralAndPostOnlySummaryWhenRequiredCheckFails() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        // The required check ("build") is failing — a would-be APPROVE must downgrade so the check
        // run is neutral rather than green.
        stubCommonReviewMocks(
            new GitHubCheckRunClient.CheckRunsResponse(
                1,
                List.of(
                    new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                        1L, "build", "completed", "failure", null))));
        when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
            .thenReturn("## Summary\nNo new issues, but required check **build** failed.");

        orchestrator.review(request());

        // No new findings: the summary comment already lists the pending/failed checks, so the bot
        // must NOT also post a COMMENT review that merely restates it (regression guard for).
        verify(reviewClient, never())
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(commentClient)
            .createComment(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> req.body().contains("**build**")));
        // The downgrade is still enforced where it matters — on the bot's own check run.
        var captor = ArgumentCaptor.forClass(GitHubCheckRunClient.UpdateCheckRunRequest.class);
        verify(checkRunClient)
            .updateCheckRun(
                anyString(), anyString(), anyString(), anyString(), anyLong(), captor.capture());
        assertEquals("neutral", captor.getValue().conclusion());
      }
    }

    @Test
    void reviewShouldFallBackWhenBaseRefMissing() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        stubCommonReviewMocks(
            new GitHubCheckRunClient.CheckRunsResponse(
                1,
                List.of(
                    new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                        1L, "build", "completed", "success", null))));
        // Base ref is null: required-status-checks must not be fetched and gating falls back to
        // all checks (which are green here), so the review still approves.
        when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42)))
            .thenReturn(
                new GitHubPullRequestClient.PullRequestDetails(
                    "Test PR",
                    "",
                    new GitHubPullRequestClient.Ref("abcdefgh", "feature"),
                    new GitHubPullRequestClient.Ref("base1234567", null)));

        orchestrator.review(request());

        verify(checkRunClient, never())
            .getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString());
        verify(reviewClient)
            .createReview(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> "APPROVE".equals(req.event())));
      }
    }

    @Test
    void reviewShouldHandleNullProtectionResponse() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        stubCommonReviewMocks(
            new GitHubCheckRunClient.CheckRunsResponse(
                1,
                List.of(
                    new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                        1L, "build", "completed", "success", null))));
        // Branch protection lookup returns null (e.g. unprotected branch) — gating falls back to
        // all checks; all green here, so the review approves.
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(null);

        orchestrator.review(request());

        verify(reviewClient)
            .createReview(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> "APPROVE".equals(req.event())));
      }
    }

    @Test
    void reviewShouldFallBackWhenBaseIsNull() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        stubCommonReviewMocks(
            new GitHubCheckRunClient.CheckRunsResponse(
                1,
                List.of(
                    new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                        1L, "build", "completed", "success", null))));
        // The PR has no base ref object at all — required checks are not fetched.
        when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42)))
            .thenReturn(
                new GitHubPullRequestClient.PullRequestDetails(
                    "Test PR", "", new GitHubPullRequestClient.Ref("abcdefgh", "feature"), null));

        orchestrator.review(request());

        verify(checkRunClient, never())
            .getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString());
        verify(reviewClient)
            .createReview(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> "APPROVE".equals(req.event())));
      }
    }

    @Test
    void reviewShouldGateOnRulesetRequiredChecksWhenClassicProtectionIs404() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        // Repo uses rulesets, not classic protection: the classic endpoint 404s. The ruleset marks
        // only "build" as required, so the failing-but-unrequired "flaky" check must be ignored and
        // APPROVE must stand. (Were we falling back to gating on ALL checks, "flaky" would block
        // it.)
        stubCommonReviewMocks(
            new GitHubCheckRunClient.CheckRunsResponse(
                2,
                List.of(
                    new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                        1L, "build", "completed", "success", null),
                    new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                        2L, "flaky", "completed", "failure", null))));
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Not Found, status code 404"));
        when(checkRunClient.getBranchRules(
                anyString(), anyString(), eq("owner"), eq("repo"), eq("main")))
            .thenReturn(requiredStatusCheckRuleset("build"));

        orchestrator.review(request());

        verify(reviewClient)
            .createReview(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> "APPROVE".equals(req.event())));
      }
    }

    @Test
    void reviewShouldDowngradeWhenRulesetRequiresAMissingCheck() {
      try (var mockedStatic = mockStatic(ReviewSession.class)) {
        var session = mock(ReviewSession.class);
        session.id = 1L;
        when(session.getRepository()).thenReturn("owner/repo");
        when(session.getPrNumber()).thenReturn(42);
        when(session.getTimestamp()).thenReturn(java.time.Instant.parse("2025-06-01T12:00:00Z"));
        mockedStatic
            .when(() -> ReviewSession.create(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(session);

        // The ruleset requires "integration", which never reported on the head SHA — it is
        // therefore
        // pending, so a would-be APPROVE must downgrade to neutral.
        stubCommonReviewMocks(
            new GitHubCheckRunClient.CheckRunsResponse(
                1,
                List.of(
                    new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                        1L, "build", "completed", "success", null))));
        when(checkRunClient.getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Not Found, status code 404"));
        when(checkRunClient.getBranchRules(
                anyString(), anyString(), eq("owner"), eq("repo"), eq("main")))
            .thenReturn(requiredStatusCheckRuleset("integration"));
        when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
            .thenReturn("## Summary\nRequired check **integration** has not reported yet.");

        orchestrator.review(request());

        var captor = ArgumentCaptor.forClass(GitHubCheckRunClient.UpdateCheckRunRequest.class);
        verify(checkRunClient)
            .updateCheckRun(
                anyString(), anyString(), anyString(), anyString(), anyLong(), captor.capture());
        assertEquals("neutral", captor.getValue().conclusion());
      }
    }

    private List<GitHubCheckRunClient.BranchRule> requiredStatusCheckRuleset(String... contexts) {
      var checks =
          new java.util.ArrayList<GitHubCheckRunClient.BranchRule.Parameters.RequiredCheck>();
      for (String context : contexts) {
        checks.add(new GitHubCheckRunClient.BranchRule.Parameters.RequiredCheck(context, null));
      }
      return List.of(
          // A non-status-check rule (pull_request) is included to prove it is skipped.
          new GitHubCheckRunClient.BranchRule("pull_request", null),
          new GitHubCheckRunClient.BranchRule(
              "required_status_checks", new GitHubCheckRunClient.BranchRule.Parameters(checks)));
    }
  }

  @Nested
  class ResolveRequiredContexts {

    private ReviewOrchestrator.ReviewRequest req() {
      return new ReviewOrchestrator.ReviewRequest(
          "owner", "repo", 42, "sha", "Test PR", "", "base", "main", 123L, false);
    }

    private void stubBaseRef(String ref) {
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenReturn(
              new GitHubPullRequestClient.PullRequestDetails(
                  "Test PR",
                  "",
                  new GitHubPullRequestClient.Ref("head", "feature"),
                  new GitHubPullRequestClient.Ref("base", ref)));
    }

    private GitHubCheckRunClient.BranchRule.Parameters.RequiredCheck check(String context) {
      return new GitHubCheckRunClient.BranchRule.Parameters.RequiredCheck(context, null);
    }

    private GitHubCheckRunClient.BranchRule statusCheckRule(
        GitHubCheckRunClient.BranchRule.Parameters.RequiredCheck... checks) {
      return new GitHubCheckRunClient.BranchRule(
          "required_status_checks",
          new GitHubCheckRunClient.BranchRule.Parameters(List.of(checks)));
    }

    private void stubClassic(GitHubCheckRunClient.RequiredStatusChecks protection) {
      when(checkRunClient.getRequiredStatusChecks(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(protection);
    }

    @Test
    void returnsEmptyWhenPullRequestLookupThrows() {
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42)))
          .thenThrow(new RuntimeException("boom"));

      assertTrue(orchestrator.resolveRequiredContexts("auth", req()).isEmpty());
      verifyNoInteractions(checkRunClient);
    }

    @Test
    void returnsEmptyWhenPullRequestDetailsAreNull() {
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42))).thenReturn(null);

      assertTrue(orchestrator.resolveRequiredContexts("auth", req()).isEmpty());
    }

    @Test
    void returnsEmptyWhenBaseRefIsNull() {
      stubBaseRef(null);

      assertTrue(orchestrator.resolveRequiredContexts("auth", req()).isEmpty());
      verify(checkRunClient, never())
          .getBranchRules(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void fallsBackToClassicWhenRulesetLookupThrows() {
      stubBaseRef("main");
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("rules boom"));
      stubClassic(new GitHubCheckRunClient.RequiredStatusChecks(List.of("build"), List.of()));

      assertEquals(
          List.of("build"), orchestrator.resolveRequiredContexts("auth", req()).orElseThrow());
    }

    @Test
    void fallsBackToClassicWhenNoRulesetGovernsBranch() {
      stubBaseRef("main");
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(List.of());
      stubClassic(new GitHubCheckRunClient.RequiredStatusChecks(List.of("build"), List.of()));

      assertEquals(
          List.of("build"), orchestrator.resolveRequiredContexts("auth", req()).orElseThrow());
    }

    @Test
    void skipsRequiredStatusChecksRuleWithNullParameters() {
      stubBaseRef("main");
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(List.of(new GitHubCheckRunClient.BranchRule("required_status_checks", null)));
      // Classic 404s — a ruleset governs the branch but contributes no required contexts.
      when(checkRunClient.getRequiredStatusChecks(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("Not Found, status code 404"));

      var result = orchestrator.resolveRequiredContexts("auth", req());
      assertTrue(result.isPresent());
      assertTrue(result.orElseThrow().isEmpty());
    }

    @Test
    void skipsRequiredCheckWithNullContext() {
      stubBaseRef("main");
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(List.of(statusCheckRule(check(null), check("build"))));
      when(checkRunClient.getRequiredStatusChecks(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("Not Found, status code 404"));

      assertEquals(
          List.of("build"), orchestrator.resolveRequiredContexts("auth", req()).orElseThrow());
    }

    @Test
    void unionsRulesetAndClassicContextsWithoutDuplicates() {
      stubBaseRef("main");
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(List.of(statusCheckRule(check("build"), check("lint"))));
      stubClassic(
          new GitHubCheckRunClient.RequiredStatusChecks(List.of("lint", "deploy"), List.of()));

      assertEquals(
          List.of("build", "lint", "deploy"),
          orchestrator.resolveRequiredContexts("auth", req()).orElseThrow());
    }

    @Test
    void returnsEmptyWhenNeitherMechanismGovernsBranch() {
      stubBaseRef("main");
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(null);
      stubClassic(null);

      assertTrue(orchestrator.resolveRequiredContexts("auth", req()).isEmpty());
    }
  }

  @Nested
  class CiChecksGating {

    @Test
    void shouldDowngradeApproveToCommentWhenOffendingCiChecksExist() {
      var aiResponse = new ReviewResponse(List.of(), List.of(), null);
      var offending = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", "failure"));

      var result =
          orchestrator.buildResult(
              aiResponse, false, new ReviewOrchestrator.DiffStats(0, 0, 0), List.of(), offending);

      assertEquals(ReviewState.COMMENT, result.reviewState());
    }

    @Test
    void shouldFormatPostReviewBodyWithOffendingCiChecks() {
      var offending =
          List.of(
              new ReviewResult.CiCheck("build", "check-run", "failing", "failure"),
              new ReviewResult.CiCheck("lint", "status", "pending", null));
      var result =
          new ReviewResult(
              List.of(),
              0,
              0,
              0,
              0,
              null,
              ReviewState.COMMENT,
              false,
              "",
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "")),
              offending);

      orchestrator.postReview("auth", "owner", "repo", 5, "sha", result, List.of());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());

      var body = captor.getValue().body();
      assertTrue(body.contains("some checks are still pending or failed:"));
      assertTrue(body.contains("- Check **build** is failed"));
      assertTrue(body.contains("- Check **lint** is pending"));
      assertTrue(
          body.contains(
              "Additionally, No new issues in this revision, but 1 previous finding(s) remain unresolved"));
    }
  }

  @Nested
  class DtoCoverage {
    @Test
    void shouldCoverCiCheckMethods() {
      var nullChecksResult =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, RiskLevel.LOW, ReviewState.APPROVE, true, "", List.of(), null);
      assertTrue(nullChecksResult.offendingCiChecks().isEmpty());
      var failing = new ReviewResult.CiCheck("build", "check-run", "failing", "failure");
      var pending = new ReviewResult.CiCheck("lint", "status", "pending", "pending");

      assertTrue(failing.isFailing());
      assertFalse(failing.isPending());

      assertTrue(pending.isPending());
      assertFalse(pending.isFailing());
    }

    @Test
    void shouldCoverGitHubCheckRunClientDTOs() {
      var reqChecksNull = new GitHubCheckRunClient.RequiredStatusChecks(null, null);
      assertTrue(reqChecksNull.contexts().isEmpty());
      assertTrue(reqChecksNull.checks().isEmpty());

      var reqChecksVal =
          new GitHubCheckRunClient.RequiredStatusChecks(
              List.of("ctx"),
              List.of(new GitHubCheckRunClient.RequiredStatusChecks.Check("ctx", 1L)));
      assertFalse(reqChecksVal.contexts().isEmpty());
      assertFalse(reqChecksVal.checks().isEmpty());

      var checkRunsNull = new GitHubCheckRunClient.CheckRunsResponse(0, null);
      assertTrue(checkRunsNull.checkRuns().isEmpty());

      var checkRunsVal =
          new GitHubCheckRunClient.CheckRunsResponse(
              1,
              List.of(
                  new GitHubCheckRunClient.CheckRunsResponse.CheckRun(1L, "n", "s", "c", null)));
      assertFalse(checkRunsVal.checkRuns().isEmpty());

      var statusNull = new GitHubCheckRunClient.CombinedStatus("pending", 0, null);
      assertTrue(statusNull.statuses().isEmpty());

      var statusVal =
          new GitHubCheckRunClient.CombinedStatus(
              "pending",
              1,
              List.of(new GitHubCheckRunClient.CombinedStatus.StatusDetail(1L, "s", "c", "d")));
      assertFalse(statusVal.statuses().isEmpty());
    }
  }

  @Nested
  class CombineSections {

    @Test
    void shouldReturnSecondWhenFirstIsBlank() {
      assertEquals("b", ReviewOrchestrator.combineSections("", "b"));
      assertEquals("b", ReviewOrchestrator.combineSections("  ", "b"));
    }

    @Test
    void shouldReturnFirstWhenSecondIsBlank() {
      assertEquals("a", ReviewOrchestrator.combineSections("a", ""));
      assertEquals("a", ReviewOrchestrator.combineSections("a", "  "));
    }

    @Test
    void shouldJoinBothWithBlankLineSeparator() {
      assertEquals("a\n\nb", ReviewOrchestrator.combineSections("a", "b"));
    }
  }
}
