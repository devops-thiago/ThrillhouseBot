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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCheckRunClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Resolves a PR's required status-check contexts and evaluates which CI checks on a commit are
 * offending (pending, failing, or missing) — the deterministic, read-only input the review verdict
 * uses to hold approval back until required CI is green.
 */
@ApplicationScoped
public class CiStatusEvaluator {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String CHECK_STATUS_COMPLETED = "completed";
  private static final String CONCLUSION_FAILURE = "failure";
  private static final String CI_SUCCESS = "success";
  private static final String CI_PENDING = "pending";
  private static final String CI_FAILING = "failing";
  private static final Set<String> PASSING_CONCLUSIONS = Set.of(CI_SUCCESS, "skipped", "neutral");

  private final GitHubCheckRunClient checkRunClient;

  // Slug tokens of the configured "<slug>[bot]" logins, used for check/app matching.
  private final Set<String> botTokens;

  private final CiGatingMode ciGating;

  // Whether CI is read for the opt-in CI-failure review context (#59) even when the gate itself is
  // off; with the gate on, the checks it reads already carry everything the context needs.
  private final boolean readForContext;

  @Inject
  public CiStatusEvaluator(
      @RestClient GitHubCheckRunClient checkRunClient,
      BotIdentity botIdentity,
      ThrillhouseConfig config) {
    this(
        checkRunClient,
        botIdentity,
        CiGatingMode.parse(config.review().ciGating()),
        config.review().ciContext().enabled());
  }

  /** Visible for tests; defaults to fail-closed {@link CiGatingMode#STRICT}. */
  CiStatusEvaluator(GitHubCheckRunClient checkRunClient, BotIdentity botIdentity) {
    this(checkRunClient, botIdentity, CiGatingMode.STRICT);
  }

  CiStatusEvaluator(
      GitHubCheckRunClient checkRunClient, BotIdentity botIdentity, CiGatingMode ciGating) {
    this(checkRunClient, botIdentity, ciGating, false);
  }

  CiStatusEvaluator(
      GitHubCheckRunClient checkRunClient,
      BotIdentity botIdentity,
      CiGatingMode ciGating,
      boolean readForContext) {
    this.readForContext = readForContext;
    this.checkRunClient = checkRunClient;
    this.botTokens =
        botIdentity.logins().stream()
            .map(CiStatusEvaluator::loginToken)
            .collect(Collectors.toUnmodifiableSet());
    this.ciGating = ciGating == null ? CiGatingMode.STRICT : ciGating;
  }

  /**
   * The slug part of a {@code <slug>[bot]} login, used as the substring token for check matching.
   */
  private static String loginToken(String login) {
    return login.endsWith("[bot]") ? login.substring(0, login.length() - "[bot]".length()) : login;
  }

  /**
   * Resolves the required status-check contexts for the PR's target branch. The contexts come from
   * two GitHub mechanisms, unioned: repository/organization <em>rulesets</em> (modern; needs only
   * read access) and <em>classic branch protection</em> (legacy; needs admin). Returns an empty
   * {@link Optional} — which the caller maps to {@code null}, gating on every check — only when
   * neither mechanism governs the branch or both lookups fail. When CI gating is {@link
   * CiGatingMode#OFF}, returns a present empty list without calling GitHub.
   */
  Optional<List<String>> resolveRequiredContexts(
      String auth, String owner, String repo, String baseBranch) {
    if (!ciGating.evaluatesCi()) {
      return Optional.of(List.of());
    }
    if (baseBranch == null || baseBranch.isBlank()) {
      return Optional.empty();
    }
    String branch = baseBranch;

    var contexts = new LinkedHashSet<String>();
    boolean resolved = false;
    var fromRulesets = requiredContextsFromRulesets(auth, owner, repo, branch);
    if (fromRulesets.isPresent()) {
      resolved = true;
      contexts.addAll(fromRulesets.get());
    }
    var fromClassic = requiredContextsFromClassicProtection(auth, owner, repo, branch);
    if (fromClassic.isPresent()) {
      resolved = true;
      contexts.addAll(fromClassic.get());
    }

    return resolved ? Optional.of(List.copyOf(contexts)) : Optional.empty();
  }

  /**
   * Required contexts declared by rulesets governing {@code branch}. An empty {@link Optional}
   * means no ruleset applies (or the lookup failed); a present-but-empty list means a ruleset
   * applies but mandates no status checks.
   */
  private Optional<List<String>> requiredContextsFromRulesets(
      String auth, String owner, String repo, String branch) {
    try {
      var rules = checkRunClient.getBranchRules(auth, ACCEPT, owner, repo, branch);
      if (rules == null || rules.isEmpty()) {
        return Optional.empty();
      }
      var contexts = new ArrayList<String>();
      for (var rule : rules) {
        if (rule.isRequiredStatusChecks() && rule.parameters() != null) {
          for (var check : rule.parameters().requiredStatusChecks()) {
            if (check.context() != null) {
              contexts.add(check.context());
            }
          }
        }
      }
      return Optional.of(contexts);
    } catch (Exception e) {
      Log.warnf(
          e, "Could not fetch branch rules (rulesets) for %s; trying classic protection", branch);
      return Optional.empty();
    }
  }

  /**
   * Required contexts declared by classic branch protection. An empty {@link Optional} means the
   * branch is not protected this way — expected, not an error, for ruleset-only repositories — or
   * the lookup failed.
   */
  private Optional<List<String>> requiredContextsFromClassicProtection(
      String auth, String owner, String repo, String branch) {
    try {
      var protection = checkRunClient.getRequiredStatusChecks(auth, ACCEPT, owner, repo, branch);
      return protection == null ? Optional.empty() : Optional.of(protection.contexts());
    } catch (Exception e) {
      // A 404 here is normal when the repo uses rulesets instead of classic branch protection.
      Log.debugf(e, "No classic branch protection for %s", branch);
      return Optional.empty();
    }
  }

  /**
   * The outcome of evaluating a commit's CI: the <em>offending</em> checks (pending, failing, or
   * missing), whether a CI source could not be read at all, and whether the required-context set
   * was actually resolved. The first two both hold the APPROVE decision back but are distinct
   * concepts — unreadable is not a check — so the verdict and the rendered summary can treat them
   * separately. {@code requiredContextsKnown} is false in fail-closed gate-all mode (the required
   * set could not be resolved, so every check is gated): the rendered copy then drops the word
   * "required", which would misdescribe checks branch protection never named.
   */
  record CiEvaluation(
      List<ReviewResult.CiCheck> offendingChecks,
      boolean unreadable,
      boolean requiredContextsKnown,
      CiFailures failures) {

    CiEvaluation {
      failures = failures == null ? CiFailures.NONE : failures;
    }

    /** An evaluation that carries no CI-failure detail for the review context. */
    CiEvaluation(
        List<ReviewResult.CiCheck> offendingChecks,
        boolean unreadable,
        boolean requiredContextsKnown) {
      this(offendingChecks, unreadable, requiredContextsKnown, CiFailures.NONE);
    }

    /**
     * Convenience for callers/tests that predate the required-context flag: assumes the required
     * set was resolved, so rendered copy keeps the accurate "required" wording.
     */
    CiEvaluation(List<ReviewResult.CiCheck> offendingChecks, boolean unreadable) {
      this(offendingChecks, unreadable, true);
    }
  }

  /**
   * A check that had completed without passing when CI was read, with what it reported about itself
   * — the raw material of the opt-in CI-failure review context (#59). {@code checkRunId} is {@code
   * 0} for a commit status, which has no annotations or log to fetch; {@code appSlug} names the
   * check run's app ({@code github-actions} for an Actions job) and is {@code null} for a status.
   * Every text field is written by the checked code's own tooling and is untrusted.
   */
  record FailedCheck(
      String name,
      String conclusion,
      long checkRunId,
      String appSlug,
      String title,
      String summary,
      int annotationsCount) {}

  /**
   * The failing and still-pending checks one CI read saw, required or not, the bot's own excluded,
   * deduplicated by name across the Check Runs and Commit Status APIs. Unlike the offending list,
   * this is not filtered to the required contexts: an optional lint job that fails still explains a
   * defect.
   */
  record CiFailures(List<FailedCheck> failed, int pending) {
    static final CiFailures NONE = new CiFailures(List.of(), 0);

    CiFailures {
      failed = List.copyOf(failed);
    }
  }

  /**
   * The whole gate read for one head: the required contexts of the base branch, then the checks on
   * the commit against them. The verdict path and the CI-hold re-evaluation (#825) both go through
   * here, so a hold and the completion that lifts it are decided on the same data.
   */
  CiEvaluation evaluate(String auth, String owner, String repo, String commitSha, String baseRef) {
    List<String> requiredContexts =
        resolveRequiredContexts(auth, owner, repo, baseRef).orElse(null);
    return evaluateCiChecks(auth, owner, repo, commitSha, requiredContexts);
  }

  /**
   * Evaluates the CI checks on {@code commitSha}: the <em>offending</em> ones — pending, failing,
   * or (when required) missing entirely — plus whether either CI source could not be read. Passing
   * checks are deliberately excluded so the caller can gate APPROVE on a non-empty result; a
   * successful required check is recorded in {@code seen} so it is not later mistaken for a missing
   * one. When CI gating is {@link CiGatingMode#OFF}, returns a clear evaluation without fetching.
   */
  CiEvaluation evaluateCiChecks(
      String auth, String owner, String repo, String commitSha, List<String> requiredContexts) {
    if (!ciGating.evaluatesCi() && !readForContext) {
      return new CiEvaluation(List.of(), false, true);
    }
    var offending = new ArrayList<ReviewResult.CiCheck>();
    // Required contexts that reported in any state, so the missing-check pass does not re-flag a
    // green check as pending.
    var seen = new HashSet<String>();
    // Contexts already recorded as offending, so a check reported through BOTH the Check Runs API
    // and the Commit Status API is not listed twice.
    var offendingNames = new HashSet<String>();

    var tracker =
        new OffendingTracker(
            requiredContexts,
            seen,
            offendingNames,
            offending,
            new LinkedHashMap<>(),
            new HashSet<>());
    var checkRunsReadable =
        collectReadable(
            () -> checkRunClient.getAllCheckRuns(auth, ACCEPT, owner, repo, commitSha),
            run -> addOffendingCheckRun(run, tracker),
            "check runs for commit " + commitSha);

    var statusReadable =
        collectReadable(
            () -> checkRunClient.getAllCombinedStatus(auth, ACCEPT, owner, repo, commitSha),
            status -> addOffendingStatus(status, tracker),
            "combined status for commit " + commitSha);

    var failures = tracker.failures();
    if (!ciGating.evaluatesCi()) {
      // Read only for the review context: the gate is off, so nothing here may hold approval.
      return new CiEvaluation(List.of(), false, true, failures);
    }

    addMissingRequiredChecks(requiredContexts, seen, offending);

    // GitHub returns an empty list, never null, past the last page — an exception or null body
    // means the source was unreadable, which must hold approval even in gate-specific mode.
    boolean unreadable = !checkRunsReadable || !statusReadable;
    return new CiEvaluation(offending, unreadable, requiredContexts != null, failures);
  }

  /**
   * Applies {@code consume} to every row from {@code fetchAll} (the client's paging helper) and
   * reports whether the fetch was actually readable. Returns {@code false} when the call throws —
   * including the client's null-body-means-unreadable signal — so the caller can tell "no checks"
   * from "could not read" (GitHub returns an empty list, never null, when there are none).
   */
  private <T> boolean collectReadable(
      Supplier<List<T>> fetchAll, Consumer<T> consume, String what) {
    try {
      fetchAll.get().forEach(consume);
      return true;
    } catch (Exception e) {
      Log.warnf(e, "Failed to fetch %s", what);
      return false;
    }
  }

  /**
   * Mutable state threaded through one {@link #evaluateCiChecks} call as both CI sources are
   * walked: the required contexts to gate against, the contexts seen in any state (so the
   * missing-check pass does not re-flag them), the names already recorded as offending (deduped
   * across the Check Runs and Commit Status APIs), and the offending list itself.
   */
  private record OffendingTracker(
      List<String> requiredContexts,
      Set<String> seen,
      Set<String> offendingNames,
      List<ReviewResult.CiCheck> offending,
      Map<String, FailedCheck> failed,
      Set<String> pending) {

    /**
     * Records a non-bot check for the review context, required or not: its failure detail the first
     * time a name fails (the check-run source is walked first and carries the richer detail), or
     * its name while it is still pending.
     */
    void noteForContext(String name, String ciStatus, Supplier<FailedCheck> failure) {
      if (name == null) {
        return;
      }
      if (CI_FAILING.equals(ciStatus)) {
        failed.computeIfAbsent(name, ignored -> failure.get());
      } else if (CI_PENDING.equals(ciStatus)) {
        pending.add(name);
      }
    }

    /** The context snapshot; a name that failed in one source is not also counted pending. */
    CiFailures failures() {
      var stillPending = new HashSet<>(pending);
      stillPending.removeAll(failed.keySet());
      return new CiFailures(List.copyOf(failed.values()), stillPending.size());
    }
  }

  private void addOffendingCheckRun(
      GitHubCheckRunClient.CheckRunsResponse.CheckRun run, OffendingTracker tracker) {
    var isBotCheck = isThrillhouseBotCheck(run.name(), run.app());
    var ciStatus = classifyCheckRun(run.status(), run.conclusion());
    if (!isBotCheck) {
      tracker.noteForContext(run.name(), ciStatus, () -> failedCheck(run));
    }
    addOffending(run.name(), isBotCheck, ciStatus, "check-run", run.conclusion(), tracker);
  }

  private void addOffendingStatus(
      GitHubCheckRunClient.CombinedStatus.StatusDetail status, OffendingTracker tracker) {
    var isBotCheck = isThrillhouseBotCheck(status.context(), null);
    var ciStatus = classifyStatus(status.state());
    if (!isBotCheck) {
      tracker.noteForContext(
          status.context(),
          ciStatus,
          () ->
              new FailedCheck(
                  status.context(), status.state(), 0L, null, null, status.description(), 0));
    }
    addOffending(status.context(), isBotCheck, ciStatus, "status", status.state(), tracker);
  }

  private static FailedCheck failedCheck(GitHubCheckRunClient.CheckRunsResponse.CheckRun run) {
    var output = run.output();
    return new FailedCheck(
        run.name(),
        run.conclusion(),
        run.id(),
        run.app() == null ? null : run.app().slug(),
        output == null ? null : output.title(),
        output == null ? null : output.summary(),
        output == null ? 0 : output.annotationsCount());
  }

  /**
   * Shared offending-check gating for both CI sources: skip the bot's own and non-required checks,
   * record the context as seen (so the missing-check pass does not re-flag it), and add it to the
   * offending list — deduped by name across the Check Runs and Commit Status APIs — unless it is a
   * confirmed pass.
   */
  private void addOffending(
      String name,
      boolean isBotCheck,
      String ciStatus,
      String type,
      String rawConclusion,
      OffendingTracker tracker) {
    if (isBotCheck || isNotRequired(name, tracker.requiredContexts())) {
      return;
    }
    tracker.seen().add(name);
    if (!CI_SUCCESS.equals(ciStatus) && tracker.offendingNames().add(name)) {
      tracker.offending().add(new ReviewResult.CiCheck(name, type, ciStatus, rawConclusion));
    }
  }

  /** Required contexts that never reported are implicitly pending. */
  private void addMissingRequiredChecks(
      List<String> requiredContexts, Set<String> seen, List<ReviewResult.CiCheck> offending) {
    if (requiredContexts == null) {
      return;
    }
    for (String required : requiredContexts) {
      // seen.add is false when the context already reported (or is a duplicate entry) — skip those.
      if (!isThrillhouseBotCheck(required, null) && seen.add(required)) {
        offending.add(new ReviewResult.CiCheck(required, "missing", CI_PENDING, null));
      }
    }
  }

  private static boolean isNotRequired(String name, List<String> requiredContexts) {
    return requiredContexts != null && !requiredContexts.contains(name);
  }

  /** Maps a check-run status/conclusion pair to one of success/pending/failing. */
  private static String classifyCheckRun(String status, String conclusion) {
    if (!CHECK_STATUS_COMPLETED.equalsIgnoreCase(status)) {
      return CI_PENDING;
    }
    if (conclusion == null || !PASSING_CONCLUSIONS.contains(conclusion.toLowerCase(Locale.ROOT))) {
      return CI_FAILING;
    }
    return CI_SUCCESS;
  }

  /** Maps a commit-status state to one of success/pending/failing. */
  private static String classifyStatus(String state) {
    if (CI_SUCCESS.equalsIgnoreCase(state)) {
      return CI_SUCCESS;
    }
    if (CONCLUSION_FAILURE.equalsIgnoreCase(state) || "error".equalsIgnoreCase(state)) {
      return CI_FAILING;
    }
    // "pending", null, or any unrecognized state is not a confirmed pass — only an explicit
    // "success" clears the gate.
    return CI_PENDING;
  }

  private boolean isThrillhouseBotCheck(
      String name, GitHubCheckRunClient.CheckRunsResponse.CheckRun.App app) {
    if (name != null
        && (name.equalsIgnoreCase(CheckRunManager.CHECK_NAME) || containsBotToken(name))) {
      return true;
    }
    return app != null && (containsBotToken(app.slug()) || containsBotToken(app.name()));
  }

  // Substring test, not BotIdentity.matches: a check's app slug/name lacks the "[bot]" suffix,
  // so an exact login match would never recognise the bot's own check.
  private boolean containsBotToken(String value) {
    if (value == null) {
      return false;
    }
    var lower = value.toLowerCase(Locale.ROOT);
    return botTokens.stream().anyMatch(lower::contains);
  }
}
