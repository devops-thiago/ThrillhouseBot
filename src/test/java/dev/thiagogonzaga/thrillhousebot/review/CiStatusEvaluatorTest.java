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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCheckRunClient;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/**
 * Unit tests for {@link CiStatusEvaluator} — the CI-status subsystem extracted from {@code
 * ReviewOrchestrator}. Carries the original {@code EvaluateCiChecks} and {@code
 * ResolveRequiredContexts} cases verbatim, including the unknown-state-holds-approval coverage.
 */
class CiStatusEvaluatorTest {

  private GitHubCheckRunClient checkRunClient;

  private CiStatusEvaluator evaluator;

  @BeforeEach
  void setUp() {
    checkRunClient =
        mock(
            GitHubCheckRunClient.class,
            invocation ->
                invocation.getMethod().isDefault()
                    ? invocation.callRealMethod()
                    : Answers.RETURNS_DEFAULTS.answer(invocation));
    evaluator = new CiStatusEvaluator(checkRunClient, new BotIdentity(Set.of()));
  }

  @Nested
  class EvaluateCiChecks {

    @Test
    void shouldIgnoreThrillhouseBotChecks() {
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

      assertEquals(2, result.offendingChecks().size());
      assertTrue(result.offendingChecks().stream().anyMatch(c -> "build".equals(c.name())));
      assertTrue(result.offendingChecks().stream().anyMatch(c -> "lint".equals(c.name())));
      assertFalse(
          result.offendingChecks().stream().anyMatch(c -> c.name().contains("thrillhousebot")));
    }

    @Test
    void usesConfiguredBotIdentityToIgnoreItsOwnChecks() {
      var custom = new CiStatusEvaluator(checkRunClient, BotIdentity.of("acme-reviewer[bot]"));
      var botRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              1L,
              "Some Check",
              "completed",
              "failure",
              new GitHubCheckRunClient.CheckRunsResponse.CheckRun.App(
                  1L, "acme-reviewer", "Acme Reviewer"));
      var otherRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              2L, "build", "completed", "failure", null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(2, List.of(botRun, otherRun)));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));

      var result = custom.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertTrue(result.offendingChecks().stream().anyMatch(c -> "build".equals(c.name())));
      assertFalse(result.offendingChecks().stream().anyMatch(c -> "Some Check".equals(c.name())));
    }

    @Test
    void botTokenMatchingHandlesAConfiguredLoginWithoutTheBotSuffix() {
      var custom = new CiStatusEvaluator(checkRunClient, BotIdentity.of("acme-bot-login"));
      var botRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              1L, "acme-bot-login check", "completed", "failure", null);
      var otherRun =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              2L, "build", "completed", "failure", null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(2, List.of(botRun, otherRun)));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));

      var result = custom.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertTrue(result.offendingChecks().stream().anyMatch(c -> "build".equals(c.name())));
      assertFalse(
          result.offendingChecks().stream().anyMatch(c -> c.name().contains("acme-bot-login")));
    }

    @Test
    void shouldExcludePassingChecksFromOffendingList() {
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

      var result =
          evaluator.evaluateCiChecks(
              "auth", "owner", "repo", "sha", List.of("build", "docs", "lint"));

      assertTrue(result.offendingChecks().isEmpty());
    }

    @Test
    void unreadableCheckRunsHoldApproveInGateAllMode() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenThrow(new RuntimeException("rate limited"));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertTrue(result.unreadable(), "an unreadable CI source must hold the verdict");
      assertFalse(
          result.offendingChecks().stream().anyMatch(c -> "CI status unavailable".equals(c.name())),
          "unreadable CI is a first-class signal, not a synthetic offending check");
    }

    @Test
    void nullCombinedStatusBodyHoldsApproveInGateAllMode() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(0, List.of()));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(null);

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertTrue(result.unreadable());
    }

    @Test
    void readableGreenCiInGateAllModeStillApproves() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CheckRunsResponse(
                  1,
                  List.of(
                      new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                          1L, "build", "completed", "success", null))));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertTrue(result.offendingChecks().isEmpty());
      assertFalse(result.unreadable());
    }

    @Test
    void requiredContextsKnownReflectsWhetherRequiredListWasResolved() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CheckRunsResponse(
                  1,
                  List.of(
                      new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                          1L, "build", "completed", "success", null))));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));

      assertFalse(
          evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null).requiredContextsKnown(),
          "a null required set (gate-all mode) is not a resolved list");
      assertTrue(
          evaluator
              .evaluateCiChecks("auth", "owner", "repo", "sha", List.of("build"))
              .requiredContextsKnown(),
          "a resolved required list is recorded as known");
    }

    @Test
    void unreadableCiInGateSpecificModeAlsoHoldsApproval() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenThrow(new RuntimeException("boom"));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("success", 0, List.of()));

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", List.of("build"));

      assertTrue(result.offendingChecks().stream().anyMatch(c -> "build".equals(c.name())));
      assertTrue(result.unreadable());
    }

    @Test
    void shouldHoldApprovalWhenAStatusStateIsUnrecognized() {
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

      assertEquals(1, result.offendingChecks().size());
      var check = result.offendingChecks().get(0);
      assertEquals("deploy", check.name());
      assertTrue(check.isPending());
      assertFalse(check.isFailing());
    }

    @Test
    void shouldFilterByRequiredContextsWhenProvided() {
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

      assertEquals(2, result.offendingChecks().size());
      assertTrue(result.offendingChecks().stream().anyMatch(c -> "build".equals(c.name())));
      assertTrue(result.offendingChecks().stream().anyMatch(c -> "lint".equals(c.name())));
      assertFalse(result.offendingChecks().stream().anyMatch(c -> "test".equals(c.name())));
      assertFalse(result.offendingChecks().stream().anyMatch(c -> "deploy".equals(c.name())));
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

      assertEquals(1, result.offendingChecks().size());
      var check = result.offendingChecks().get(0);
      assertEquals("build", check.name());
      assertEquals("missing", check.type());
      assertEquals("pending", check.status());
    }

    @Test
    void shouldEvaluateStatusesAndConclusionsCorrectly() {
      var runPending =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              1L, "build", "in_progress", null, null);
      var runFailed =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              2L, "test", "completed", "failure", null);
      var runSkipped =
          new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
              3L, "docs", "completed", "skipped", null);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CheckRunsResponse(
                  3, List.of(runPending, runFailed, runSkipped)));

      var statusPending =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(1L, "pending", "lint", "desc");
      var statusError =
          new GitHubCheckRunClient.CombinedStatus.StatusDetail(2L, "error", "security", "desc");
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CombinedStatus(
                  "pending", 2, List.of(statusPending, statusError)));

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertEquals(4, result.offendingChecks().size());

      var build =
          result.offendingChecks().stream()
              .filter(c -> "build".equals(c.name()))
              .findFirst()
              .orElseThrow();
      assertEquals("pending", build.status());

      var test =
          result.offendingChecks().stream()
              .filter(c -> "test".equals(c.name()))
              .findFirst()
              .orElseThrow();
      assertEquals("failing", test.status());

      assertFalse(result.offendingChecks().stream().anyMatch(c -> "docs".equals(c.name())));

      var lint =
          result.offendingChecks().stream()
              .filter(c -> "lint".equals(c.name()))
              .findFirst()
              .orElseThrow();
      assertEquals("pending", lint.status());

      var security =
          result.offendingChecks().stream()
              .filter(c -> "security".equals(c.name()))
              .findFirst()
              .orElseThrow();
      assertEquals("failing", security.status());
    }

    @Test
    void shouldPageThroughCheckRunsUntilAShortPageIsReturned() {
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

      assertEquals(101, result.offendingChecks().size());
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

      assertEquals(101, result.offendingChecks().size());
      verify(checkRunClient).getCombinedStatus(any(), any(), any(), any(), any(), eq(100), eq(2));
    }

    @Test
    void shouldStopPagingAtTheMaxPagesGuard() {
      var fullRuns = new java.util.ArrayList<GitHubCheckRunClient.CheckRunsResponse.CheckRun>();
      var fullStatuses =
          new java.util.ArrayList<GitHubCheckRunClient.CombinedStatus.StatusDetail>();
      for (int i = 0; i < GitHubCheckRunClient.CI_PER_PAGE; i++) {
        fullRuns.add(
            new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                i, "run-" + i, "completed", "failure", null));
        fullStatuses.add(
            new GitHubCheckRunClient.CombinedStatus.StatusDetail(i, "failure", "status-" + i, "d"));
      }
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CheckRunsResponse(
                  GitHubCheckRunClient.CI_PER_PAGE, fullRuns));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CombinedStatus(
                  "failure", GitHubCheckRunClient.CI_PER_PAGE, fullStatuses));

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertEquals(2 * GitHubCheckRunClient.CI_PER_PAGE, result.offendingChecks().size());
      verify(checkRunClient, times(GitHubCheckRunClient.CI_MAX_PAGES))
          .getCheckRuns(
              any(), any(), any(), any(), any(), eq(GitHubCheckRunClient.CI_PER_PAGE), anyInt());
      verify(checkRunClient, times(GitHubCheckRunClient.CI_MAX_PAGES))
          .getCombinedStatus(
              any(), any(), any(), any(), any(), eq(GitHubCheckRunClient.CI_PER_PAGE), anyInt());
    }

    @Test
    void shouldBreakWhenCheckRunsAndStatusResponsesAreNull() {
      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);
      assertTrue(result.unreadable());
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

      assertEquals(1, result.offendingChecks().size());
      assertEquals("failing", result.offendingChecks().get(0).status());
    }

    @Test
    void shouldDeduplicateContextReportedAsBothCheckRunAndStatus() {
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

      assertEquals(1, result.offendingChecks().size());
      assertEquals("build", result.offendingChecks().get(0).name());
      assertEquals("check-run", result.offendingChecks().get(0).type());
    }

    @Test
    void shouldKeepNonBotChecksWhoseAppAndNameDoNotMatch() {
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

      assertEquals(3, result.offendingChecks().size());
      assertTrue(result.offendingChecks().stream().anyMatch(c -> "build".equals(c.name())));
      assertTrue(result.offendingChecks().stream().anyMatch(c -> "deploy".equals(c.name())));
    }

    @Test
    void shouldHandleNullChecksAndStatusesGracefully() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(0, null));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("pending", 0, null));

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);
      assertTrue(result.offendingChecks().isEmpty());
    }

    @Test
    void shouldHandleExceptionsGracefully() {
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenThrow(new RuntimeException("failed"));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenThrow(new RuntimeException("failed"));

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);
      assertTrue(result.unreadable());
      assertTrue(result.offendingChecks().isEmpty());
    }
  }

  @Nested
  class ResolveRequiredContexts {

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
    void returnsEmptyWhenBaseBranchIsBlank() {
      assertTrue(evaluator.resolveRequiredContexts("auth", "owner", "repo", "").isEmpty());
      assertTrue(evaluator.resolveRequiredContexts("auth", "owner", "repo", null).isEmpty());
      verify(checkRunClient, never())
          .getBranchRules(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void fallsBackToClassicWhenRulesetLookupThrows() {
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("rules boom"));
      stubClassic(new GitHubCheckRunClient.RequiredStatusChecks(List.of("build"), List.of()));

      assertEquals(
          List.of("build"),
          evaluator.resolveRequiredContexts("auth", "owner", "repo", "main").orElseThrow());
    }

    @Test
    void fallsBackToClassicWhenNoRulesetGovernsBranch() {
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(List.of());
      stubClassic(new GitHubCheckRunClient.RequiredStatusChecks(List.of("build"), List.of()));

      assertEquals(
          List.of("build"),
          evaluator.resolveRequiredContexts("auth", "owner", "repo", "main").orElseThrow());
    }

    @Test
    void skipsRequiredStatusChecksRuleWithNullParameters() {
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(List.of(new GitHubCheckRunClient.BranchRule("required_status_checks", null)));
      when(checkRunClient.getRequiredStatusChecks(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("Not Found, status code 404"));

      var result = evaluator.resolveRequiredContexts("auth", "owner", "repo", "main");
      assertTrue(result.isPresent());
      assertTrue(result.orElseThrow().isEmpty());
    }

    @Test
    void skipsRequiredCheckWithNullContext() {
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(List.of(statusCheckRule(check(null), check("build"))));
      when(checkRunClient.getRequiredStatusChecks(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("Not Found, status code 404"));

      assertEquals(
          List.of("build"),
          evaluator.resolveRequiredContexts("auth", "owner", "repo", "main").orElseThrow());
    }

    @Test
    void unionsRulesetAndClassicContextsWithoutDuplicates() {
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(List.of(statusCheckRule(check("build"), check("lint"))));
      stubClassic(
          new GitHubCheckRunClient.RequiredStatusChecks(List.of("lint", "deploy"), List.of()));

      assertEquals(
          List.of("build", "lint", "deploy"),
          evaluator.resolveRequiredContexts("auth", "owner", "repo", "main").orElseThrow());
    }

    @Test
    void returnsEmptyWhenNeitherMechanismGovernsBranch() {
      when(checkRunClient.getBranchRules(
              anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(null);
      stubClassic(null);

      assertTrue(evaluator.resolveRequiredContexts("auth", "owner", "repo", "main").isEmpty());
    }
  }

  @Nested
  class CiGatingModes {

    @Test
    void offModeSkipsCiFetchAndReturnsClearEvaluation() {
      var off = new CiStatusEvaluator(checkRunClient, new BotIdentity(Set.of()), CiGatingMode.OFF);

      var result = off.evaluateCiChecks("auth", "owner", "repo", "sha", List.of("build"));

      assertTrue(result.offendingChecks().isEmpty());
      assertFalse(result.unreadable());
      assertTrue(result.requiredContextsKnown());
      verify(checkRunClient, never()).getAllCheckRuns(any(), any(), any(), any(), any());
      verify(checkRunClient, never()).getAllCombinedStatus(any(), any(), any(), any(), any());
    }

    @Test
    void offModeSkipsRequiredContextLookup() {
      var off = new CiStatusEvaluator(checkRunClient, new BotIdentity(Set.of()), CiGatingMode.OFF);

      assertEquals(
          List.of(), off.resolveRequiredContexts("auth", "owner", "repo", "main").orElseThrow());
      verify(checkRunClient, never())
          .getBranchRules(anyString(), anyString(), anyString(), anyString(), anyString());
      verify(checkRunClient, never())
          .getRequiredStatusChecks(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void warnModeStillEvaluatesOffendingChecks() {
      var warn =
          new CiStatusEvaluator(checkRunClient, new BotIdentity(Set.of()), CiGatingMode.WARN);
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(
              new GitHubCheckRunClient.CheckRunsResponse(
                  1,
                  List.of(
                      new GitHubCheckRunClient.CheckRunsResponse.CheckRun(
                          1L, "build", "completed", "failure", null))));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CombinedStatus("failure", 0, List.of()));

      var result = warn.evaluateCiChecks("auth", "owner", "repo", "sha", List.of("build"));

      assertEquals(1, result.offendingChecks().size());
      assertEquals("build", result.offendingChecks().get(0).name());
      assertFalse(result.unreadable());
    }
  }
}
