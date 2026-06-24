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

import dev.thiagogonzaga.thrillhousebot.github.GitHubCheckRunClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link CiStatusEvaluator} — the CI-status subsystem extracted from {@code
 * ReviewOrchestrator} (#250). Carries the original {@code EvaluateCiChecks} and {@code
 * ResolveRequiredContexts} cases verbatim, including the #217 unknown-state-holds-approval
 * coverage.
 */
class CiStatusEvaluatorTest {

  @Mock private GitHubCheckRunClient checkRunClient;
  @Mock private GitHubPullRequestClient prClient;

  private CiStatusEvaluator evaluator;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    evaluator = new CiStatusEvaluator(checkRunClient, prClient);
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

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

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
          evaluator.evaluateCiChecks(
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

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

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
          evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", List.of("build", "lint"));

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
          evaluator.evaluateCiChecks(
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

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

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

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

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

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

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
      for (int i = 0; i < CiStatusEvaluator.CI_PER_PAGE; i++) {
        fullRuns.add(
            new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                i, "run-" + i, "completed", "failure", null));
        fullStatuses.add(
            new GitHubCheckRunClient.CombinedStatus.StatusDetail(i, "failure", "status-" + i, "d"));
      }
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CheckRunsResponse(CiStatusEvaluator.CI_PER_PAGE, fullRuns));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CombinedStatus(
                  "failure", CiStatusEvaluator.CI_PER_PAGE, fullStatuses));

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      // Deduped to the distinct names across the repeated pages.
      assertEquals(2 * CiStatusEvaluator.CI_PER_PAGE, result.size());
      verify(checkRunClient, times(CiStatusEvaluator.CI_MAX_PAGES))
          .getCheckRuns(
              any(), any(), any(), any(), any(), eq(CiStatusEvaluator.CI_PER_PAGE), anyInt());
      verify(checkRunClient, times(CiStatusEvaluator.CI_MAX_PAGES))
          .getCombinedStatus(
              any(), any(), any(), any(), any(), eq(CiStatusEvaluator.CI_PER_PAGE), anyInt());
    }

    @Test
    void shouldBreakWhenCheckRunsAndStatusResponsesAreNull() {
      // Unmocked endpoints return null; the page loops must break immediately without error.
      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);
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

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

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

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

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

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

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

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);
      assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleExceptionsGracefully() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenThrow(new RuntimeException("failed"));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenThrow(new RuntimeException("failed"));

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);
      assertTrue(result.isEmpty());
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

      assertTrue(evaluator.resolveRequiredContexts("auth", req()).isEmpty());
      verifyNoInteractions(checkRunClient);
    }

    @Test
    void returnsEmptyWhenPullRequestDetailsAreNull() {
      when(prClient.getPullRequest(any(), any(), eq("owner"), eq("repo"), eq(42))).thenReturn(null);

      assertTrue(evaluator.resolveRequiredContexts("auth", req()).isEmpty());
    }

    @Test
    void returnsEmptyWhenBaseRefIsNull() {
      stubBaseRef(null);

      assertTrue(evaluator.resolveRequiredContexts("auth", req()).isEmpty());
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
          List.of("build"), evaluator.resolveRequiredContexts("auth", req()).orElseThrow());
    }

    @Test
    void fallsBackToClassicWhenNoRulesetGovernsBranch() {
      stubBaseRef("main");
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(List.of());
      stubClassic(new GitHubCheckRunClient.RequiredStatusChecks(List.of("build"), List.of()));

      assertEquals(
          List.of("build"), evaluator.resolveRequiredContexts("auth", req()).orElseThrow());
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

      var result = evaluator.resolveRequiredContexts("auth", req());
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
          List.of("build"), evaluator.resolveRequiredContexts("auth", req()).orElseThrow());
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
          evaluator.resolveRequiredContexts("auth", req()).orElseThrow());
    }

    @Test
    void returnsEmptyWhenNeitherMechanismGovernsBranch() {
      stubBaseRef("main");
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(null);
      stubClassic(null);

      assertTrue(evaluator.resolveRequiredContexts("auth", req()).isEmpty());
    }
  }
}
