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
 * ReviewOrchestrator} (#250). Carries the original {@code EvaluateCiChecks} and {@code
 * ResolveRequiredContexts} cases verbatim, including the #217 unknown-state-holds-approval
 * coverage.
 */
class CiStatusEvaluatorTest {

  private GitHubCheckRunClient checkRunClient;

  private CiStatusEvaluator evaluator;

  @BeforeEach
  void setUp() {
    // getAllCheckRuns/getAllCombinedStatus are default methods that page over the abstract
    // getCheckRuns/getCombinedStatus the tests stub, so the mock must invoke real default methods
    // while still mocking the abstract calls.
    checkRunClient =
        mock(
            GitHubCheckRunClient.class,
            invocation ->
                invocation.getMethod().isDefault()
                    ? invocation.callRealMethod()
                    : Answers.RETURNS_DEFAULTS.answer(invocation));
    // Empty set falls back to BotIdentity.DEFAULT_LOGINS (thrillhousebot[bot],
    // thrillhouse-bot[bot]),
    // so the bot-token detection keeps recognizing the "thrillhousebot" checks these tests use.
    evaluator = new CiStatusEvaluator(checkRunClient, new BotIdentity(Set.of()));
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

      assertEquals(2, result.offendingChecks().size());
      assertTrue(result.offendingChecks().stream().anyMatch(c -> "build".equals(c.name())));
      assertTrue(result.offendingChecks().stream().anyMatch(c -> "lint".equals(c.name())));
      assertFalse(
          result.offendingChecks().stream().anyMatch(c -> c.name().contains("thrillhousebot")));
    }

    @Test
    void usesConfiguredBotIdentityToIgnoreItsOwnChecks() {
      // #9: bot detection is driven by the shared BotIdentity, not a hardcoded literal — a
      // deployment under a custom slug recognizes its own app's checks (here "acme-reviewer"),
      // which the old hardcoded "thrillhousebot" token would have missed.
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
      // A configured login that does not end in "[bot]" is used as the token verbatim, so a check
      // whose name contains that bare token is still recognized as the bot's and dropped.
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

      assertTrue(result.offendingChecks().isEmpty());
    }

    @Test
    void unreadableCheckRunsHoldApproveInGateAllMode() {
      // Check Runs API throws; combined status reads clean. In gate-all mode (no required-context
      // list to backfill) we cannot confirm CI is green, so the evaluation reports a first-class
      // unreadable signal that holds the verdict to COMMENT rather than approving over CI we never
      // saw — and it is NOT smuggled in as a synthetic offending check.
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
      // A null body (not an empty list) means the response could not be read — must not count
      // green.
      when(checkRunClient.getCheckRuns(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(new GitHubCheckRunClient.CheckRunsResponse(0, List.of()));
      when(checkRunClient.getCombinedStatus(any(), any(), any(), any(), any(), anyInt(), anyInt()))
          .thenReturn(null);

      var result = evaluator.evaluateCiChecks("auth", "owner", "repo", "sha", null);

      assertTrue(result.unreadable());
    }

    @Test
    void readableGreenCiInGateAllModeStillApproves() {
      // Both sources read cleanly and green — nothing offending and CI is readable, APPROVE not
      // gated.
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
    void unreadableCiInGateSpecificModeAlsoHoldsApproval() {
      // #5: gate-specific mode is NOT automatically safe. The Check Runs source throws, so a
      // required check reporting only there could be hidden; the missing-required backfill still
      // flags "build", and the unread source is now reported as a first-class unreadable signal too
      // — closing the gap where a gate-specific review could approve over a source it never read.
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

      assertEquals(1, result.offendingChecks().size());
      var check = result.offendingChecks().get(0);
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

      // thrillhousebot is ignored, so only "build" is missing
      assertEquals(1, result.offendingChecks().size());
      var check = result.offendingChecks().get(0);
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
      // Every page is full (CI_PER_PAGE rows) so the short-page break never fires; the loop must
      // terminate at the CI_MAX_PAGES guard rather than run forever.
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

      // Deduped to the distinct names across the repeated pages.
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
      // Unmocked endpoints return null; the page loops break immediately without error, but a null
      // body is "could not read", so gate-all mode holds the verdict rather than allowing APPROVE
      // over CI it never saw (#253).
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

      assertEquals(1, result.offendingChecks().size());
      assertEquals("build", result.offendingChecks().get(0).name());
      assertEquals("check-run", result.offendingChecks().get(0).type());
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
      // Exceptions are swallowed (no crash), but unreadable CI must hold the verdict — not come
      // back
      // empty and silently allow APPROVE (#253).
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
      // The base branch is resolved upstream (webhook payload / resolveMissingPrDetails); when it
      // is
      // absent we gate on all checks and never look up branch rules — no PR fetch happens here.
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
      // Classic 404s — a ruleset governs the branch but contributes no required contexts.
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
}
