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
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.dashboard.SessionEventBroadcaster;
import dev.thiagogonzaga.thrillhousebot.github.*;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

  private static final String BOT_LOGIN = "thrillhousebot[bot]";
  private static final BotIdentity BOT_ID = BotIdentity.of(BOT_LOGIN);

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

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
  private final FrameworkFalsePositiveFilter frameworkFilter = new FrameworkFalsePositiveFilter();

  private final FindingDeduplicator deduplicator = new FindingDeduplicator();

  @Mock private SessionEventBroadcaster broadcaster;

  @Mock private ReviewSessionPersistence sessionPersistence;

  @Mock private SuggestionFormatter suggestionFormatter;

  @Mock private PrSummaryGenerator summaryGenerator;

  @Mock private FollowUpAnalyzer followUpAnalyzer;

  @Mock private PrLabeler labeler;

  private final ObjectMapper mapper = new ObjectMapper();

  private ReviewDiffFormatter diffFormatter;

  private ReviewPublisher reviewPublisher;

  private VerdictBuilder verdictBuilder;

  private FindingPipeline findingPipeline;

  private final ExecutorService reviewExecutor = Executors.newVirtualThreadPerTaskExecutor();

  private ReviewOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    ThrillhouseConfig.GitHubConfig githubConfig = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.botLogins()).thenReturn(List.of(BOT_LOGIN));
    diffFormatter = new ReviewDiffFormatter(List.of(), 5000);
    reviewPublisher =
        new ReviewPublisher(
            reviewClient,
            commentClient,
            reviewThreadService,
            suggestionFormatter,
            followUpAnalyzer,
            labeler,
            config,
            BOT_ID);
    verdictBuilder =
        new VerdictBuilder(summaryGenerator, followUpAnalyzer, BOT_ID, BlockingStrictness.BALANCED);
    findingPipeline =
        new FindingPipeline(
            aiReviewService,
            quoteValidator,
            frameworkFilter,
            deduplicator,
            findingVerificationService,
            followUpAnalyzer,
            mapper,
            BOT_ID,
            new DiffBudgetPlanner(
                diffFormatter, new TokenCounter(), config, new ActiveModelSettings(config, "m")),
            new TokenCounter());
    orchestrator = newOrchestrator();
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
    lenient()
        .when(checkRunClient.getAllCheckRuns(any(), any(), any(), any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(checkRunClient.getAllCombinedStatus(any(), any(), any(), any(), any()))
        .thenReturn(List.of());
    diagramConfig = mock(ThrillhouseConfig.DiagramConfig.class);
    when(reviewConfig.diagram()).thenReturn(diagramConfig);
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
    when(findingVerificationService.verify(any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(followUpAnalyzer.dropRepliedDuplicates(any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(reviewClient.createPullRequestComment(
            anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.PullRequestCommentResponse(1L, "ok", "main.py", 10));
  }

  private ReviewOrchestrator newOrchestrator() {
    return new ReviewOrchestrator(
        config,
        authClient,
        broadcaster,
        sessionPersistence,
        new CiStatusEvaluator(checkRunClient, BOT_ID),
        new CheckRunManager(checkRunClient),
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
            BOT_ID,
            new ActiveModelSettings(config, "m")),
        new ReviewPromptAssembler(config, labeler, diffFormatter),
        new DiffBudgetPlanner(
            diffFormatter, new TokenCounter(), config, new ActiveModelSettings(config, "m")),
        reviewPublisher,
        verdictBuilder,
        findingPipeline,
        mock(FindingFeedbackCaptureService.class),
        reviewExecutor);
  }

  private DiffLineResolver resolverFor(GitHubPullRequestClient.FileDiff... files) {
    return new DiffLineResolver(diffFormatter.patchesByFile(List.of(files)));
  }

  private static GitHubPullRequestClient.FileDiff fileDiffWithLine(
      String filename, int lineInPatch) {
    // Plain concatenation: patches always use \n regardless of platform, so %n would be wrong
    var patch = "@@ -" + lineInPatch + ",1 +" + lineInPatch + ",1 @@\n-old\n+new";
    return new GitHubPullRequestClient.FileDiff(filename, "modified", 1, 1, 2, patch);
  }

  @Nested
  class BuildResult {

    @Test
    void shouldProduceEmptyResultWhenNoFindings() {
      var aiResponse = new ReviewResponse(List.of(), List.of(), null);

      var result =
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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

      var result =
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(120, 4000, 4000, 7),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

      assertEquals(ReviewState.COMMENT, result.reviewState());
      assertTrue(result.summaryMarkdown().contains("partial review"));
      assertTrue(result.summaryMarkdown().contains("7 file"));
    }

    @Test
    void diffStatsWithAuthoritativeTotalsOverridesCountsButKeepsOmittedFileCount() {
      var reviewed = new VerdictBuilder.DiffStats(26, 958, 186, 3);

      var authoritative =
          reviewed.withAuthoritativeTotals(new ReviewContextLoader.PrTotals(27, 975, 196));

      assertEquals(27, authoritative.filesChanged());
      assertEquals(975, authoritative.additions());
      assertEquals(196, authoritative.deletions());
      assertEquals(3, authoritative.omittedFiles());
      assertTrue(authoritative.truncated());
    }

    @Test
    void diffStatsWithNullAuthoritativeTotalsFallsBackToDiffDerivedCounts() {
      var reviewed = new VerdictBuilder.DiffStats(26, 958, 186, 0);

      assertSame(reviewed, reviewed.withAuthoritativeTotals(null));
    }

    @Test
    void truncatedCleanSummaryBodyDoesNotCelebrateEndToEnd() {
      var realVerdict =
          new VerdictBuilder(
              new PrSummaryGenerator(false), followUpAnalyzer, BOT_ID, BlockingStrictness.BALANCED);
      var aiResponse = new ReviewResponse(List.of(), List.of(), null);

      var result =
          realVerdict.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(120, 4000, 4000, 7),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

      var summary = result.summaryMarkdown();
      assertFalse(summary.contains("Everything's coming up Thrillhouse"), summary);
      assertTrue(summary.contains("partial review"), summary);
    }

    @Test
    void truncatedFollowUpReviewBodyDisclosesPartialAndOmitsZeroUnresolved() {
      var aiResponse = new ReviewResponse(List.of(), List.of(), null);
      var result =
          verdictBuilder.buildResult(
              aiResponse,
              false,
              new VerdictBuilder.DiffStats(120, 4000, 4000, 7),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());
      assertEquals(ReviewState.COMMENT, result.reviewState());

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      var body = captor.getValue().body();
      assertTrue(body.contains("partial review"), body);
      assertTrue(body.contains("7 file"), body);
      assertFalse(body.contains("remain unresolved"), body);
      assertEquals("COMMENT", captor.getValue().event());
    }

    @Test
    void truncatedFollowUpBodyAppendsTruncationAfterUnresolved() {
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
              List.of(),
              7);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      var body = captor.getValue().body();
      assertTrue(body.contains("1 previous finding(s) remain unresolved"), body);
      assertTrue(body.contains("partial review"), body);
      assertTrue(body.contains("7 file"), body);
    }

    @Test
    void truncatedFirstReviewBodyNowDisclosesPartialReview() {
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
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "")),
              List.of(),
              7);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      var body = captor.getValue().body();
      assertTrue(body.contains("1 previous finding(s) remain unresolved"), body);
      assertTrue(body.contains("partial review"), body);
      assertTrue(body.contains("7 file"), body);
    }

    @Test
    void truncationOnlyHeldFirstReviewStillPostsThePartialReviewBody() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), List.of(), 4);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      var body = captor.getValue().body();
      assertTrue(body.contains("partial review"), body);
      assertTrue(body.contains("4 file"), body);
      assertEquals("COMMENT", captor.getValue().event());
    }

    @Test
    void truncatedFirstReviewWithFindingsStillPostsBodyDisclosingPartialReview() {
      var aiResponse =
          new ReviewResponse(
              List.of(
                  new ReviewResponse.Finding(
                      "medium", "src/Main.java", 10, "Bug", "desc", "old", "new")),
              List.of(),
              null);
      var result =
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(120, 4000, 4000, 7),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());
      assertTrue(result.truncated());
      when(suggestionFormatter.formatReviewComment(any(), anyBoolean(), anyInt()))
          .thenReturn("inline body");

      reviewPublisher.postReview(
          "auth",
          "owner",
          "repo",
          5,
          "sha",
          result,
          resolverFor(fileDiffWithLine("src/Main.java", 10)));

      verify(reviewClient)
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());
      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      var body = captor.getValue().body();
      assertTrue(body.contains("partial review"), body);
      assertTrue(body.contains("7 file"), body);
    }

    @Test
    void shouldHandleNullFindingsInAiResponse() {
      var aiResponse = new ReviewResponse(null, null, null);

      var result =
          verdictBuilder.buildResult(
              aiResponse,
              false,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          verdictBuilder.buildResult(
              aiResponse,
              false,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          verdictBuilder.buildResult(
              aiResponse,
              false,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(4, 120, 45),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

      assertTrue(result.summaryMarkdown().contains("Test summary content"));
      verify(summaryGenerator).generate(eq(4), eq(120), eq(45), any(), any(), any());
    }

    @Test
    void shouldGenerateSummaryEvenWhenNoFindings() {
      var aiResponse = new ReviewResponse(List.of(), List.of(), null);
      when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
          .thenReturn("Clean summary with celebration");

      var result =
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          verdictBuilder.buildResult(
              aiResponse,
              true,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

      String summary = VerdictBuilder.checkSummaryForResult(result);

      assertTrue(summary.contains("Everything's coming up Thrillhouse"));
      assertTrue(summary.contains("No issues found"));
    }

    @Test
    void checkSummaryForResultShouldDiscloseTruncationInsteadOfCelebrating() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), List.of(), 3);

      String summary = VerdictBuilder.checkSummaryForResult(result);

      assertFalse(summary.contains("Everything's coming up Thrillhouse"));
      assertTrue(summary.contains("partial review"));
      assertTrue(summary.contains("3 file(s) omitted"));
    }

    @Test
    void checkSummaryForResultShouldDiscloseTruncationAlongsideACiHold() {
      var checks = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", null));
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), checks, 2);

      String summary = VerdictBuilder.checkSummaryForResult(result);

      assertTrue(summary.contains("required CI check(s)"), summary);
      assertTrue(summary.contains("too large to review in full"), summary);
      assertTrue(summary.contains("2 file(s) omitted"), summary);
    }

    @Test
    void checkSummaryForResultShouldDiscloseUnreadableCiAlongsideAnOffendingCheck() {
      var checks = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", null));
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
              checks,
              0,
              true);

      String summary = VerdictBuilder.checkSummaryForResult(result);

      assertTrue(summary.contains("required CI check(s)"), summary);
      assertTrue(summary.contains("could not be read"), summary);
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
              List.of(),
              List.of(),
              0);

      String summary = VerdictBuilder.checkSummaryForResult(result);

      assertEquals("1 findings: 0 critical, 1 high, 0 medium, 0 low", summary);
    }

    @Test
    void checkSummaryForResultShouldDiscloseTruncationAlongsideFindingCounts() {
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
              List.of(),
              List.of(),
              5);

      String summary = VerdictBuilder.checkSummaryForResult(result);

      assertTrue(summary.contains("1 findings: 0 critical, 1 high, 0 medium, 0 low"), summary);
      assertTrue(summary.contains("5 file(s) omitted"), summary);
      assertTrue(summary.contains("partial review"), summary);
    }

    @Test
    void checkTitleForResultShouldAppendCheckmarkWhenClean() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

      String title = VerdictBuilder.checkTitleForResult(result);

      assertEquals("ThrillhouseBot Review ✅", title);
    }

    @Test
    void checkTitleForResultShouldNotCelebrateTruncatedButOtherwiseCleanReview() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), List.of(), 3);

      assertFalse(VerdictBuilder.checkTitleForResult(result).contains("✅"));
    }

    @Test
    void unreadableCiHoldsApprovalAndIsDisclosedAsItsOwnSignal() {
      var clean = new ReviewResponse(List.of(), List.of(), null);
      var result =
          verdictBuilder.buildResult(
              clean,
              true,
              new VerdictBuilder.DiffStats(1, 1, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), true),
              List.of());

      assertEquals(ReviewState.COMMENT, result.reviewState());
      assertTrue(result.ciUnreadable());
      assertTrue(result.offendingCiChecks().isEmpty());
      assertFalse(VerdictBuilder.checkTitleForResult(result).contains("✅"));
      assertEquals("neutral", VerdictBuilder.conclusionForResult(result));
      assertTrue(
          VerdictBuilder.checkSummaryForResult(result).contains("CI status could not be read"));
    }

    @Test
    void shouldNotCelebrateWhenCiChecksAreOffendingDespiteNoFindings() {
      var offending = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", "failure"));
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), offending, 0);

      assertFalse(VerdictBuilder.checkTitleForResult(result).contains("✅"));
      String summary = VerdictBuilder.checkSummaryForResult(result);
      assertFalse(summary.contains("Everything's coming up Thrillhouse"));
      assertTrue(summary.contains("required CI check(s) are still pending or failing"));
    }

    @Test
    void checkSummaryUsesNeutralCiWordingWhenRequiredSetUnknown() {
      var offending = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", "failure"));
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
              offending,
              0,
              false,
              false,
              ReviewResult.TruncationDetail.EMPTY);

      String summary = VerdictBuilder.checkSummaryForResult(result);

      assertTrue(summary.contains("1 CI check(s) are still pending or failing"), summary);
      assertFalse(summary.contains("required CI check(s)"), summary);
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
    void checkRunConclusionFailureAfterReviewKeepsPostedReviewAndCompletesSession() {
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

        verify(reviewClient)
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
        verify(session, never()).setStatus(ReviewSession.STATUS_FAILED);
        verify(broadcaster, times(2)).broadcast(any());
        verify(commentClient, never())
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
    void summaryCommentFailureDoesNotAbortTheReview() {
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
            .thenReturn(new GitHubCheckRunClient.RequiredStatusChecks(List.of(), List.of()));
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
            .thenReturn("## PR summary");
        doThrow(new RuntimeException("comment 500"))
            .when(commentClient)
            .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());

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
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
        verify(session, never()).setStatus(ReviewSession.STATUS_FAILED);
      }
    }

    @Test
    void postReviewFailureMarksReviewFailedInsteadOfLeavingAConcludedCheckRun() {
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
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));
        when(reviewClient.createReview(
                anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
            .thenThrow(new RuntimeException("502 Bad Gateway"));

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
    void postResultPersistenceFailureDoesNotBroadcastAFalseCompletion() {
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
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));
        doThrow(new RuntimeException("db down")).when(sessionPersistence).update(anyLong(), any());

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
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
        verify(session, never()).setStatus(ReviewSession.STATUS_FAILED);
        verify(broadcaster, times(1)).broadcast(any());
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
        when(labeler.fetchExistingLabels(anyString(), anyString(), anyString()))
            .thenReturn(List.of(new GitHubLabelClient.Label("bug", null, "ededed")));
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

        verify(reviewClient)
            .createReview(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> "APPROVE".equals(req.event())));
        verify(commentClient, never())
            .createComment(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> req.body().contains("could not be completed")));
        verify(session, never()).setStatus(ReviewSession.STATUS_FAILED);
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
        verify(broadcaster, times(2)).broadcast(any());
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

        verify(reviewClient, times(1))
            .listReviews(anyString(), anyString(), anyString(), anyString(), anyInt());
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
    void shouldRepostSummaryWhenForceSummarySetEvenThoughReviewExists() {
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
            .thenReturn(new GitHubCheckRunClient.RequiredStatusChecks(List.of(), List.of()));
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
        when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
            .thenReturn(PrSummaryGenerator.SUMMARY_HEADING);
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "(manual summary)",
                "",
                "base1234567",
                "main",
                123L,
                true,
                "main",
                true));

        var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
        verify(commentClient)
            .createComment(
                anyString(), anyString(), anyString(), anyString(), anyInt(), body.capture());
        assertTrue(body.getValue().body().startsWith(PrSummaryGenerator.SUMMARY_HEADING));
        verify(reviewClient, never())
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
      }
    }

    @Test
    void shouldStillPostReviewWhenForceSummarySetButSummaryPostFails() {
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
            .thenReturn(new GitHubCheckRunClient.RequiredStatusChecks(List.of(), List.of()));
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
        when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
            .thenReturn(PrSummaryGenerator.SUMMARY_HEADING);
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));
        doThrow(new RuntimeException("summary post failed"))
            .when(commentClient)
            .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());

        orchestrator.review(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "abcdefgh",
                "(manual summary)",
                "",
                "base1234567",
                "main",
                123L,
                true,
                "main",
                true));

        var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
        verify(reviewClient)
            .createReview(
                anyString(), anyString(), eq("owner"), eq("repo"), eq(42), captor.capture());
        assertEquals("APPROVE", captor.getValue().event());
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
      }
    }

    @Test
    void shouldSkipSummaryWhenABotSummaryCommentAlreadyExistsButNoReviewDoes() {
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

        verify(commentClient, never())
            .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
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
  class GitHubReviewSubmission {

    @Test
    void shouldDismissPendingBotReviews() {
      var pending =
          new GitHubReviewClient.ReviewResponse(
              99L, "", "PENDING", "sha", new GitHubReviewClient.ReviewResponse.User(BOT_LOGIN));
      var approved =
          new GitHubReviewClient.ReviewResponse(
              1L, "", "APPROVED", "sha", new GitHubReviewClient.ReviewResponse.User("other-user"));
      var pendingHuman =
          new GitHubReviewClient.ReviewResponse(
              2L, "", "PENDING", "sha", new GitHubReviewClient.ReviewResponse.User("other-user"));

      reviewPublisher.dismissPendingBotReviews(
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
    void shouldDismissBotPendingReviewDespiteAGhostReviewWithNullUser() {
      var ghost = new GitHubReviewClient.ReviewResponse(5L, "", "PENDING", "sha", null);
      var botPending =
          new GitHubReviewClient.ReviewResponse(
              99L, "", "PENDING", "sha", new GitHubReviewClient.ReviewResponse.User(BOT_LOGIN));

      reviewPublisher.dismissPendingBotReviews(
          "Bearer tok", "owner", "repo", 7, List.of(ghost, botPending));

      verify(reviewClient)
          .deletePendingReview(
              eq("Bearer tok"), anyString(), eq("owner"), eq("repo"), eq(7), eq(99L));
      verify(reviewClient, never())
          .deletePendingReview(
              anyString(), anyString(), anyString(), anyString(), anyInt(), eq(5L));
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

      assertDoesNotThrow(
          () ->
              reviewPublisher.dismissPendingBotReviews(
                  "Bearer tok", "owner", "repo", 7, List.of(pending)));
    }

    @Test
    void shouldCreateReviewWithoutFallbackWhenFirstAttemptSucceeds() {
      var req = new GitHubReviewClient.CreateReviewRequest("sha", "body", "COMMENT", List.of());

      reviewPublisher.createReviewWithFallback("Bearer tok", "owner", "repo", 7, req);

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

      reviewPublisher.createReviewWithFallback("Bearer tok", "owner", "repo", 7, req);

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
              () ->
                  reviewPublisher.createReviewWithFallback("Bearer tok", "owner", "repo", 7, req));

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
              List.of(),
              0,
              0,
              0,
              0,
              RiskLevel.LOW,
              ReviewState.APPROVE,
              true,
              "",
              List.of(),
              List.of(),
              0);

      String conclusion = VerdictBuilder.conclusionForResult(result);

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
              List.of(),
              List.of(),
              0);

      assertEquals("failure", VerdictBuilder.conclusionForResult(result));
    }

    @Test
    void shouldReturnNeutralConclusionForCommentReview() {
      var result =
          new ReviewResult(
              List.of(),
              0,
              0,
              1,
              0,
              RiskLevel.MEDIUM,
              ReviewState.COMMENT,
              true,
              "",
              List.of(),
              List.of(),
              0);

      assertEquals("neutral", VerdictBuilder.conclusionForResult(result));
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
          List.of(),
          List.of(),
          0);
    }

    @Test
    void shouldSkipFindingsOutsideDiff() {
      var finding = new Finding(RiskLevel.MEDIUM, "missing.java", 10, "Bug", "desc", null, null);
      var result = resultWithFinding(finding, ReviewState.COMMENT);
      var resolver = new DiffLineResolver(Map.of("src/Main.java", "@@ +1,1 @@\n+line"));

      var posted =
          reviewPublisher
              .postInlineComments("Bearer tok", "owner", "repo", 7, "sha", result, resolver)
              .posted();

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
          reviewPublisher
              .postInlineComments("Bearer tok", "owner", "repo", 7, "sha", result, resolver)
              .posted();

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
          reviewPublisher
              .postInlineComments("Bearer tok", "owner", "repo", 7, "sha", result, resolver)
              .posted();

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
          reviewPublisher
              .postInlineComments("Bearer tok", "owner", "repo", 7, "sha", result, resolver)
              .posted();

      assertEquals(0, posted);
      verify(reviewClient, times(2))
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldPostWithoutSuggestionWhenAMultiLineSuggestionCannotResolveItsRange() {
      var finding =
          new Finding(
              RiskLevel.HIGH,
              "src/Main.java",
              10,
              "Bug",
              "desc",
              "unmatched old line one\nunmatched old line two",
              "new one\nnew two");
      var result = resultWithFinding(finding, ReviewState.REQUEST_CHANGES);
      var resolver =
          new DiffLineResolver(
              Map.of("src/Main.java", fileDiffWithLine("src/Main.java", 10).patch()));
      when(suggestionFormatter.formatReviewComment(eq(finding), anyBoolean(), anyInt()))
          .thenReturn("body");

      var posted =
          reviewPublisher
              .postInlineComments("Bearer tok", "owner", "repo", 7, "sha", result, resolver)
              .posted();

      assertEquals(1, posted);
      verify(suggestionFormatter).formatReviewComment(eq(finding), eq(false), anyInt());
      verify(suggestionFormatter, never()).formatReviewComment(eq(finding), eq(true), anyInt());
      verify(reviewClient, times(1))
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
              List.of(),
              List.of(),
              0);
      var resolver =
          new DiffLineResolver(
              Map.of(
                  "src/A.java", fileDiffWithLine("src/A.java", 10).patch(),
                  "src/B.java", fileDiffWithLine("src/B.java", 20).patch()));
      when(suggestionFormatter.formatReviewComment(any(), eq(true))).thenReturn("body");

      var inline =
          reviewPublisher.postInlineComments(
              "Bearer tok", "owner", "repo", 7, "sha", result, resolver);

      assertEquals(1, inline.posted());
      verify(reviewClient, times(1))
          .createPullRequestComment(
              anyString(), anyString(), anyString(), anyString(), anyInt(), any());
      assertTrue(inline.unanchored().isEmpty());
      assertEquals(1, inline.capSkipped().size());
      assertEquals("Two", inline.capSkipped().get(0).title());
    }

    @Test
    void shouldDiscloseCapSkippedFindingsInReviewBodyWithCapReasonNotAnchoringReason() {
      when(reviewConfig.maxReviewComments()).thenReturn(1);
      var first = new Finding(RiskLevel.MEDIUM, "src/A.java", 10, "One", "desc one", null, null);
      var second = new Finding(RiskLevel.HIGH, "src/B.java", 20, "Two", "desc two", null, null);
      var result =
          new ReviewResult(
              List.of(first, second),
              0,
              0,
              2,
              0,
              RiskLevel.HIGH,
              ReviewState.REQUEST_CHANGES,
              false,
              "",
              List.of(),
              List.of(),
              0);
      var resolver =
          new DiffLineResolver(
              Map.of(
                  "src/A.java", fileDiffWithLine("src/A.java", 10).patch(),
                  "src/B.java", fileDiffWithLine("src/B.java", 20).patch()));
      when(suggestionFormatter.formatReviewComment(any(), eq(true), anyInt())).thenReturn("body");

      reviewPublisher.postReview("Bearer tok", "owner", "repo", 7, "sha", result, resolver);

      verify(reviewClient)
          .createReview(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(
                  req ->
                      req.body().contains("comment cap was reached")
                          && req.body().contains("Two")
                          && req.body().contains("desc two")
                          && !req.body().contains("could not be anchored")));
    }

    @Test
    void shouldListFindingsWithDescriptionsInReviewBodyWhenNoneAnchorInlineOnFirstReview() {
      var finding =
          new Finding(RiskLevel.MEDIUM, "missing.java", 10, "Bug", "the X path NPEs", null, null);
      var result = resultWithFinding(finding, ReviewState.COMMENT); // isFirstReview = true

      reviewPublisher.postReview(
          "Bearer tok",
          "owner",
          "repo",
          7,
          "sha",
          result,
          resolverFor(fileDiffWithLine("src/Main.java", 10)));

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
                          && req.body().contains("Bug")
                          && req.body().contains("missing.java:10")
                          && req.body().contains("the X path NPEs")
                          && !req.body().contains("PR summary")));
    }

    @Test
    void truncatedReviewDisclosesPartialReviewWhenNoFindingsAnchorInline() {
      var finding = new Finding(RiskLevel.MEDIUM, "missing.java", 10, "Bug", "desc", null, null);
      var result =
          new ReviewResult(
              List.of(finding),
              0,
              0,
              1,
              0,
              RiskLevel.MEDIUM,
              ReviewState.COMMENT,
              true,
              "",
              List.of(),
              List.of(),
              6);

      reviewPublisher.postReview(
          "Bearer tok",
          "owner",
          "repo",
          7,
          "sha",
          result,
          resolverFor(fileDiffWithLine("src/Main.java", 10)));

      verify(reviewClient)
          .createReview(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(
                  req ->
                      req.body().contains("Bug")
                          && req.body().contains("missing.java:10")
                          && req.body().contains("partial review")
                          && req.body().contains("6 file")));
    }

    @Test
    void shouldListFindingsInReviewBodyWhenNoneAnchorInlineOnFollowUp() {
      var finding =
          new Finding(RiskLevel.CRITICAL, "missing.java", 10, "Auth bypass", "desc", null, null);
      var blankDescription =
          new Finding(RiskLevel.MEDIUM, "gone.java", 20, "Dead code", "  ", null, null);
      var nullDescription =
          new Finding(RiskLevel.LOW, "old.java", 30, "Unused import", null, null, null);
      var result =
          new ReviewResult(
              List.of(finding, blankDescription, nullDescription),
              1,
              1,
              1,
              0,
              RiskLevel.CRITICAL,
              ReviewState.REQUEST_CHANGES,
              false, // follow-up: no summary comment is posted
              "",
              List.of(),
              List.of(),
              0);

      reviewPublisher.postReview(
          "Bearer tok",
          "owner",
          "repo",
          7,
          "sha",
          result,
          resolverFor(fileDiffWithLine("src/Main.java", 10)));

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
                          && req.body().contains("missing.java:10")
                          && req.body().contains("Dead code")
                          && req.body().contains("gone.java:20")
                          && req.body().contains("Unused import")
                          && req.body().contains("old.java:30")));
    }

    @Test
    void shouldSurfaceAnUnanchoredFindingInBodyEvenWhenOthersAnchorInline() {
      var anchored =
          new Finding(RiskLevel.HIGH, "src/Main.java", 10, "Anchored bug", "a", null, null);
      var floating =
          new Finding(
              RiskLevel.CRITICAL,
              "missing.java",
              99,
              "Floating bug",
              "Null deref when the account is deleted.",
              null,
              null);
      var result =
          new ReviewResult(
              List.of(anchored, floating),
              1,
              1,
              0,
              0,
              RiskLevel.CRITICAL,
              ReviewState.REQUEST_CHANGES,
              false,
              "",
              List.of(),
              List.of(),
              0);

      reviewPublisher.postReview(
          "Bearer tok",
          "owner",
          "repo",
          7,
          "sha",
          result,
          resolverFor(fileDiffWithLine("src/Main.java", 10)));

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
              argThat(
                  req ->
                      req.body().contains("Floating bug")
                          && req.body().contains("missing.java:99")
                          && req.body().contains("Null deref when the account is deleted.")
                          && !req.body().contains("Anchored bug")));
    }

    @Test
    void firstReviewStillReportsAnUnanchoredTopFindingWithItsDescriptionInTheBody() {
      var anchored =
          new Finding(RiskLevel.HIGH, "src/Main.java", 10, "Anchored bug", "a", null, null);
      var floating =
          new Finding(
              RiskLevel.CRITICAL,
              "missing.java",
              99,
              "Floating bug",
              "Null deref when the account is deleted.",
              null,
              null);
      var result =
          new ReviewResult(
              List.of(anchored, floating),
              1,
              1,
              0,
              0,
              RiskLevel.CRITICAL,
              ReviewState.REQUEST_CHANGES,
              true, // first review: summary comment carries the Key Findings (brief, no
              "",
              List.of(),
              List.of(),
              0);

      reviewPublisher.postReview(
          "Bearer tok",
          "owner",
          "repo",
          7,
          "sha",
          result,
          resolverFor(fileDiffWithLine("src/Main.java", 10)));

      verify(reviewClient)
          .createReview(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(
                  req ->
                      req.body().contains("requested changes")
                          && req.body().contains("Floating bug")
                          && req.body().contains("Null deref when the account is deleted.")));
    }

    @Test
    void firstReviewStillListsAnUnanchoredFindingBeyondTheSummaryTopFive() {
      var findings = new java.util.ArrayList<Finding>();
      for (int i = 1; i <= 5; i++) {
        findings.add(new Finding(RiskLevel.HIGH, "src/Main.java", 10, "Top " + i, "d", null, null));
      }
      var beyond =
          new Finding(RiskLevel.LOW, "missing.java", 99, "Beyond top five", "d", null, null);
      findings.add(beyond);
      var result =
          new ReviewResult(
              findings,
              0,
              5,
              0,
              1,
              RiskLevel.HIGH,
              ReviewState.REQUEST_CHANGES,
              true,
              "",
              List.of(),
              List.of(),
              0);

      reviewPublisher.postReview(
          "Bearer tok",
          "owner",
          "repo",
          7,
          "sha",
          result,
          resolverFor(fileDiffWithLine("src/Main.java", 10)));

      verify(reviewClient)
          .createReview(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(req -> req.body().contains("Beyond top five")));
    }

    @Test
    void shouldApproveCleanPrFromPostReview() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

      reviewPublisher.postReview("Bearer tok", "owner", "repo", 7, "sha", result, resolverFor());

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
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, false, "", List.of(), List.of(), 0);

      reviewPublisher.postReview("Bearer tok", "owner", "repo", 7, "sha", result, resolverFor());

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

      reviewPublisher.postReview(
          "Bearer tok",
          "owner",
          "repo",
          7,
          "sha",
          result,
          resolverFor(
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
          reviewPublisher
              .postInlineComments("Bearer tok", "owner", "repo", 7, "sha", result, resolver)
              .posted();

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
          reviewPublisher
              .postInlineComments("Bearer tok", "owner", "repo", 7, "sha", result, resolver)
              .posted();

      assertEquals(1, posted);
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
          reviewPublisher
              .postInlineComments("Bearer tok", "owner", "repo", 7, "sha", result, resolver)
              .posted();

      assertEquals(1, posted);
      verify(reviewClient)
          .createPullRequestComment(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyInt(),
              argThat(req -> req.startLine() != null && req.startLine() == 11 && req.line() == 12));
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

      VerdictBuilder.DiffStats stats = VerdictBuilder.DiffStats.fromFiles(files, 0);

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

      var changed = VerdictBuilder.toChangedFiles(files);

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
              List.of(),
              List.of(),
              0);

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
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

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

      findingPipeline.persistAiResponse(session, response);

      assertNotNull(session.getAiResponseJson());
    }

    @Test
    void shouldHandleSerializationFailureGracefully() throws Exception {
      var session = new ReviewSession();

      ObjectMapper badMapper = mock(ObjectMapper.class);
      when(badMapper.writeValueAsString(any()))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("fail") {});
      var failingPipeline =
          new FindingPipeline(
              aiReviewService,
              quoteValidator,
              frameworkFilter,
              deduplicator,
              findingVerificationService,
              followUpAnalyzer,
              badMapper,
              BOT_ID,
              new DiffBudgetPlanner(
                  diffFormatter, new TokenCounter(), config, new ActiveModelSettings(config, "m")),
              new TokenCounter());

      var response = new ReviewResponse(List.of(), List.of(), null);
      failingPipeline.persistAiResponse(session, response);

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

      var updated = findingPipeline.populateMissingAnchors(response, lineResolver);

      assertEquals(4, updated.findings().size());

      assertEquals("existing_anchor", updated.findings().get(0).suggestionOld());

      assertEquals("    new_line()", updated.findings().get(1).suggestionOld());

      assertEquals("def unchanged():", updated.findings().get(2).suggestionOld());

      assertNull(updated.findings().get(3).suggestionOld());
    }

    @Test
    void shouldReturnOriginalResponseWhenFindingsIsEmpty() {
      var lineResolver = new DiffLineResolver(Map.of());
      var response = new ReviewResponse(List.of(), List.of(), null);
      var updated = findingPipeline.populateMissingAnchors(response, lineResolver);
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
      var updated = findingPipeline.populateMissingAnchors(response, lineResolver);
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
      var updated = findingPipeline.populateMissingAnchors(response, lineResolver);
      assertSame(response, updated);
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
          verdictBuilder.buildResult(
              aiResponse,
              false,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              unresolvedPrevious,
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

      assertEquals(ReviewState.COMMENT, result.reviewState());
      assertFalse(VerdictBuilder.checkTitleForResult(result).contains("✅"));
      assertTrue(
          VerdictBuilder.checkSummaryForResult(result)
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
          verdictBuilder.buildResult(
              aiResponse,
              false,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              unresolvedPrevious,
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

      assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    }

    @Test
    void shouldHoldApprovalWhenUnresolvedStatusCannotBeMappedToAFinding() {
      delegateFollowUpAnalyzer();
      var aiResponse =
          responseWithStatuses(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still"));

      var result =
          verdictBuilder.buildResult(
              aiResponse,
              false,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

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
          verdictBuilder.buildResult(
              aiResponse,
              false,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(List.of(), false),
              List.of());

      assertEquals(ReviewState.APPROVE, result.reviewState());
      assertTrue(VerdictBuilder.checkTitleForResult(result).contains("✅"));
      assertTrue(VerdictBuilder.checkSummaryForResult(result).contains("No issues found"));
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
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still")),
              List.of(),
              0);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

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
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still")),
              List.of(),
              0);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("REQUEST_CHANGES", captor.getValue().event());
    }

    @Test
    void postReviewShouldSkipDuplicateCommentReviewOnFirstReviewWhenOnlyCiPending() {
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
              List.of(new ReviewResult.CiCheck("build", "check-run", "pending", null)),
              0);

      reviewPublisher.postReview(
          new ReviewPublisher.PostReviewRequest(
              "auth", "owner", "repo", 5, "sha", result, resolverFor(), true));

      verify(reviewClient, never())
          .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void postReviewShouldStillPostUnresolvedCommentOnFirstReviewWhenSummaryPosted() {
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
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still")),
              List.of(new ReviewResult.CiCheck("build", "check-run", "pending", null)),
              0);

      reviewPublisher.postReview(
          new ReviewPublisher.PostReviewRequest(
              "auth", "owner", "repo", 5, "sha", result, resolverFor(), true));

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("COMMENT", captor.getValue().event());
      assertTrue(captor.getValue().body().contains("remain unresolved"));
    }

    @Test
    void postReviewShouldStillPostTruncatedCommentOnFirstReviewWhenSummaryPosted() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, true, "", List.of(), List.of(), 3);

      reviewPublisher.postReview(
          new ReviewPublisher.PostReviewRequest(
              "auth", "owner", "repo", 5, "sha", result, resolverFor(), true));

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("COMMENT", captor.getValue().event());
      assertTrue(captor.getValue().body().contains("partial review"));
    }

    @Test
    void postReviewShouldStillPostRequestChangesWhenSummaryPosted() {
      var result =
          new ReviewResult(
              List.of(),
              0,
              0,
              0,
              0,
              null,
              ReviewState.REQUEST_CHANGES,
              true,
              "",
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still")),
              List.of(),
              0);

      reviewPublisher.postReview(
          new ReviewPublisher.PostReviewRequest(
              "auth", "owner", "repo", 5, "sha", result, resolverFor(), true));

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("REQUEST_CHANGES", captor.getValue().event());
    }

    @Test
    void postReviewShouldStillPostCiPendingCommentOnFirstReviewWhenSummaryDidNotPost() {
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
              List.of(new ReviewResult.CiCheck("build", "check-run", "pending", null)),
              0);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("COMMENT", captor.getValue().event());
      assertTrue(captor.getValue().body().contains("**build**"));
    }

    @Test
    void postReviewShouldStillPostCommentReviewOnFollowUpWhenCiPending() {
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
              List.of(new ReviewResult.CiCheck("build", "check-run", "pending", null)),
              0);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("COMMENT", captor.getValue().event());
      assertTrue(captor.getValue().body().contains("**build**"));
    }

    @Test
    void postReviewShouldSkipNoIssuesReviewOnSummaryOnlyRerun() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, false, "", List.of(), List.of(), 0);

      reviewPublisher.postReview(
          new ReviewPublisher.PostReviewRequest(
              "auth", "owner", "repo", 5, "sha", result, resolverFor(), true));

      verify(reviewClient, never())
          .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void postReviewShouldSkipCiPendingCommentReviewOnSummaryOnlyRerun() {
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
              List.of(new ReviewResult.CiCheck("build", "check-run", "pending", null)),
              0);

      reviewPublisher.postReview(
          new ReviewPublisher.PostReviewRequest(
              "auth", "owner", "repo", 5, "sha", result, resolverFor(), true));

      verify(reviewClient, never())
          .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void postReviewShouldStillPostUnresolvedCommentReviewOnSummaryOnlyRerun() {
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
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still")),
              List.of(),
              0);

      reviewPublisher.postReview(
          new ReviewPublisher.PostReviewRequest(
              "auth", "owner", "repo", 5, "sha", result, resolverFor(), true));

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("COMMENT", captor.getValue().event());
      assertTrue(captor.getValue().body().contains("remain unresolved"));
    }

    @Test
    void postReviewShouldStillPostNoIssuesReviewOnSummaryTriggeredFirstReview() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);

      reviewPublisher.postReview(
          new ReviewPublisher.PostReviewRequest(
              "auth", "owner", "repo", 5, "sha", result, resolverFor(), true));

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("APPROVE", captor.getValue().event());
    }

    @Test
    void postReviewShouldStillPostReviewOnSummaryOnlyRerunWhenTruncated() {
      var result =
          new ReviewResult(
              List.of(), 0, 0, 0, 0, null, ReviewState.COMMENT, false, "", List.of(), List.of(), 3);

      reviewPublisher.postReview(
          new ReviewPublisher.PostReviewRequest(
              "auth", "owner", "repo", 5, "sha", result, resolverFor(), true));

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("COMMENT", captor.getValue().event());
      assertTrue(captor.getValue().body().contains("partial review"));
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
      return verdictBuilder.buildResult(
          new ReviewResponse(List.of(), List.of(), null),
          false,
          new VerdictBuilder.DiffStats(0, 0, 0),
          List.of(), // changedFiles
          List.of(),
          new CiStatusEvaluator.CiEvaluation(List.of(), false),
          backstop);
    }

    @Test
    void shouldDowngradeApproveToCommentWhenBackstopHoldsUnresolved() {
      delegateStatusGate();

      var result =
          buildWithBackstop(
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still present")));

      assertEquals(ReviewState.COMMENT, result.reviewState());
      assertFalse(VerdictBuilder.checkTitleForResult(result).contains("✅"));
      assertTrue(
          VerdictBuilder.checkSummaryForResult(result)
              .contains("1 previous finding(s) remain unresolved"));
    }

    @Test
    void shouldApproveWhenBackstopHoldsNothing() {
      delegateStatusGate();

      var result = buildWithBackstop(List.of());

      assertEquals(ReviewState.APPROVE, result.reviewState());
      assertTrue(VerdictBuilder.checkTitleForResult(result).contains("✅"));
      assertTrue(VerdictBuilder.checkSummaryForResult(result).contains("No issues found"));
    }

    @Test
    void shouldNeverEscalateBackstopBeyondComment() {
      delegateStatusGate();

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

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      assertEquals("COMMENT", captor.getValue().event());
      assertTrue(captor.getValue().body().contains("remain unresolved"));
    }

    @Test
    void shouldQualifyReplyGuidanceSinceABackstopFindingMayHaveNoThread() {
      delegateStatusGate();
      var result =
          buildWithBackstop(
              List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still present")));

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

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
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));
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
        when(sessionPersistence.findAllPriorAiResponseJsons("owner/repo", 42, 1L))
            .thenReturn(List.of(PRIOR_FINDING_JSON));
        when(followUpAnalyzer.buildPreviousFindingsContext(any(), any(), any(), any(), eq(BOT_ID)))
            .thenReturn("previous context");
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));

        orchestrator.review(followUpRequest());

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
        when(sessionPersistence.findAllPriorAiResponseJsons("owner/repo", 42, 1L))
            .thenReturn(List.of(PRIOR_FINDING_JSON));
        when(followUpAnalyzer.buildPreviousFindingsContext(any(), any(), any(), any(), eq(BOT_ID)))
            .thenReturn("previous context");
        when(aiReviewService.review(any(ReviewSession.class), any()))
            .thenReturn(new ReviewResponse(List.of(), List.of(), null));
        when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
            .thenReturn("ThrillhouseBot PR Summary\n\nAll clear!");

        orchestrator.review(followUpRequest());

        verify(commentClient)
            .createComment(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                argThat(req -> req.body().contains("ThrillhouseBot PR Summary")));
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
      when(reviewThreadService.resolve(AUTH, "T4")).thenReturn(false);

      reviewPublisher.resolveAddressedThreads(
          AUTH, request(), "{}", List.of(rootComment()), statuses);

      verify(reviewThreadService).resolve(AUTH, "T1");
      verify(reviewThreadService).resolve(AUTH, "T4");
      verify(reviewThreadService, never()).resolve(AUTH, "T2");
      verify(reviewThreadService, never()).resolve(AUTH, "T3");
    }

    @Test
    void shouldSkipFindingsWithoutAMatchedThread() {
      var statuses = List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"));
      when(followUpAnalyzer.matchFindingThreads(any(), any(), any())).thenReturn(Map.of());
      when(reviewThreadService.threadsByRootComment(AUTH, "owner", "repo", 5)).thenReturn(Map.of());

      reviewPublisher.resolveAddressedThreads(
          AUTH, request(), "{}", List.of(rootComment()), statuses);

      verify(reviewThreadService, never()).resolve(anyString(), anyString());
    }

    @Test
    void shouldDoNothingWhenNoFindingWasAddressedOrNoComments() {
      var unresolvedOnly =
          List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still"));
      var resolved = List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed"));

      reviewPublisher.resolveAddressedThreads(
          AUTH, request(), "{}", List.of(rootComment()), unresolvedOnly);
      reviewPublisher.resolveAddressedThreads(AUTH, request(), "{}", List.of(), resolved);

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
              reviewPublisher.resolveAddressedThreads(
                  AUTH, request(), "{}", List.of(rootComment()), statuses));
    }
  }

  @Nested
  class ResolveMissingPrDetailsIntegration {

    private ReviewOrchestrator.ReviewRequest manualRequest(String sha) {
      return new ReviewOrchestrator.ReviewRequest(
          "owner", "repo", 7, sha, "(manual review)", "", "", "main", 123L, true);
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

        verify(checkRunClient)
            .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        verify(reviewClient)
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
      }
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
      when(checkRunClient.getAllCheckRuns(any(), any(), any(), any(), any()))
          .thenReturn(checkRuns.checkRuns());
      when(checkRunClient.getAllCombinedStatus(any(), any(), any(), any(), any()))
          .thenReturn(List.of());
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
          "owner",
          "repo",
          42,
          "abcdefgh",
          "Test PR",
          "",
          "base1234567",
          "main",
          123L,
          false,
          "main");
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
    void reviewSurvivesAPriorReviewFromADeletedAccount() {
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
        when(reviewClient.listReviews(anyString(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(
                List.of(
                    new GitHubReviewClient.ReviewResponse(
                        9L, "ghost", "COMMENTED", "abcdefgh", null),
                    new GitHubReviewClient.ReviewResponse(
                        10L,
                        "lgtm",
                        "APPROVED",
                        "abcdefgh",
                        new GitHubReviewClient.ReviewResponse.User("some-user"))));

        orchestrator.review(request());

        verify(reviewClient)
            .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(session).setStatus(ReviewSession.STATUS_COMPLETED);
        verify(session, never()).setStatus(ReviewSession.STATUS_FAILED);
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

        stubCommonReviewMocks(
            new GitHubCheckRunClient.CheckRunsResponse(
                1,
                List.of(
                    new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                        1L, "build", "completed", "failure", null))));
        when(summaryGenerator.generate(anyInt(), anyInt(), anyInt(), any(), any(), any()))
            .thenReturn("## Summary\nNo new issues, but required check **build** failed.");

        orchestrator.review(request());

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
        var captor = ArgumentCaptor.forClass(GitHubCheckRunClient.UpdateCheckRunRequest.class);
        verify(checkRunClient)
            .updateCheckRun(
                anyString(), anyString(), anyString(), anyString(), anyLong(), captor.capture());
        assertEquals("neutral", captor.getValue().conclusion());
      }
    }

    @Test
    void reviewShouldFallBackToAllChecksWhenBaseRefBlank() {
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
        var noBaseRef =
            new ReviewOrchestrator.ReviewRequest(
                "owner", "repo", 42, "abcdefgh", "Test PR", "", "base1234567", "main", 123L, false);

        orchestrator.review(noBaseRef);

        verify(checkRunClient, never())
            .getRequiredStatusChecks(
                anyString(), anyString(), anyString(), anyString(), anyString());
        verify(checkRunClient, never())
            .getBranchRules(anyString(), anyString(), anyString(), anyString(), anyString());
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
          new GitHubCheckRunClient.BranchRule("pull_request", null),
          new GitHubCheckRunClient.BranchRule(
              "required_status_checks", new GitHubCheckRunClient.BranchRule.Parameters(checks)));
    }
  }

  @Nested
  class CiChecksGating {

    @Test
    void shouldDowngradeApproveToCommentWhenOffendingCiChecksExist() {
      var aiResponse = new ReviewResponse(List.of(), List.of(), null);
      var offending = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", "failure"));

      var result =
          verdictBuilder.buildResult(
              aiResponse,
              false,
              new VerdictBuilder.DiffStats(0, 0, 0),
              List.of(),
              List.of(),
              new CiStatusEvaluator.CiEvaluation(offending, false),
              List.of());

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
              offending,
              0);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

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

    @Test
    void shouldDiscloseUnreadableCiInTheFollowUpPostReviewBody() {
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
              List.of(),
              0,
              true);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      var body = captor.getValue().body();
      assertTrue(body.contains("CI status could not be read"));
      assertTrue(body.contains("Additionally, No new issues in this revision"));
    }

    @Test
    void shouldDiscloseUnreadableCiWithoutUnresolvedFindingsInTheFollowUpBody() {
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
              List.of(),
              0,
              true);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      var body = captor.getValue().body();
      assertTrue(body.contains("CI status could not be read"));
      assertFalse(body.contains("Additionally,"));
    }

    @Test
    void shouldDiscloseBothOffendingChecksAndUnreadableCiWhenHeldByEach() {
      var offending = List.of(new ReviewResult.CiCheck("build", "check-run", "failing", "failure"));
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
              offending,
              0,
              true);

      reviewPublisher.postReview("auth", "owner", "repo", 5, "sha", result, resolverFor());

      var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
      verify(reviewClient)
          .createReview(eq("auth"), anyString(), eq("owner"), eq("repo"), eq(5), captor.capture());
      var body = captor.getValue().body();
      assertTrue(body.contains("some checks are still pending or failed:"));
      assertTrue(body.contains("- Check **build** is failed"));
      assertTrue(body.contains("CI status could not be read"));
    }
  }

  @Nested
  class DtoCoverage {
    @Test
    void shouldCoverCiCheckMethods() {
      var nullChecksResult =
          new ReviewResult(
              List.of(),
              0,
              0,
              0,
              0,
              RiskLevel.LOW,
              ReviewState.APPROVE,
              true,
              "",
              List.of(),
              null,
              0);
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
}
