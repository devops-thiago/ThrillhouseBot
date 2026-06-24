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

import dev.thiagogonzaga.thrillhousebot.github.GitHubCheckRunClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Resolves a PR's required status-check contexts and evaluates which CI checks on a commit are
 * offending (pending, failing, or missing) — the deterministic, read-only input the review verdict
 * uses to hold approval back until required CI is green. Extracted from {@code ReviewOrchestrator}
 * as the self-contained CI-status subsystem.
 */
@ApplicationScoped
public class CiStatusEvaluator {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String CHECK_NAME = "ThrillhouseBot Review";
  private static final String CHECK_STATUS_COMPLETED = "completed";
  private static final String CONCLUSION_FAILURE = "failure";
  private static final String CI_SUCCESS = "success";
  private static final String CI_PENDING = "pending";
  private static final String CI_FAILING = "failing";
  private static final String THRILLHOUSEBOT_TOKEN = "thrillhousebot";
  private static final Set<String> PASSING_CONCLUSIONS = Set.of(CI_SUCCESS, "skipped", "neutral");

  // GitHub serves 30 rows per page by default; request the 100 max and bound the page walk.
  static final int CI_PER_PAGE = 100;
  static final int CI_MAX_PAGES = 50;

  private final GitHubCheckRunClient checkRunClient;
  private final GitHubPullRequestClient prClient;

  @Inject
  public CiStatusEvaluator(
      @RestClient GitHubCheckRunClient checkRunClient,
      @RestClient GitHubPullRequestClient prClient) {
    this.checkRunClient = checkRunClient;
    this.prClient = prClient;
  }

  /**
   * Resolves the required status-check contexts for the PR's target branch. The contexts come from
   * two GitHub mechanisms, unioned: repository/organization <em>rulesets</em> (modern; needs only
   * read access) and <em>classic branch protection</em> (legacy; needs admin). Returns an empty
   * {@link Optional} — which the caller maps to {@code null}, gating on every check — only when
   * neither mechanism governs the branch or both lookups fail.
   */
  Optional<List<String>> resolveRequiredContexts(
      String auth, ReviewOrchestrator.ReviewRequest req) {
    String branch;
    try {
      var prDetails =
          prClient.getPullRequest(auth, ACCEPT, req.owner(), req.repo(), req.prNumber());
      if (prDetails == null || prDetails.base() == null || prDetails.base().ref() == null) {
        return Optional.empty();
      }
      branch = prDetails.base().ref();
    } catch (Exception e) {
      Log.warnf(e, "Could not resolve target branch; gating CI on all checks");
      return Optional.empty();
    }

    var contexts = new LinkedHashSet<String>();
    boolean resolved = false;
    var fromRulesets = requiredContextsFromRulesets(auth, req, branch);
    if (fromRulesets.isPresent()) {
      resolved = true;
      contexts.addAll(fromRulesets.get());
    }
    var fromClassic = requiredContextsFromClassicProtection(auth, req, branch);
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
      String auth, ReviewOrchestrator.ReviewRequest req, String branch) {
    try {
      var rules = checkRunClient.getBranchRules(auth, ACCEPT, req.owner(), req.repo(), branch);
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
      String auth, ReviewOrchestrator.ReviewRequest req, String branch) {
    try {
      var protection =
          checkRunClient.getRequiredStatusChecks(auth, ACCEPT, req.owner(), req.repo(), branch);
      return protection == null ? Optional.empty() : Optional.of(protection.contexts());
    } catch (Exception e) {
      // A 404 here is normal when the repo uses rulesets instead of classic branch protection.
      Log.debugf(e, "No classic branch protection for %s", branch);
      return Optional.empty();
    }
  }

  /**
   * Returns only the <em>offending</em> CI checks on {@code commitSha} — those that are pending,
   * failing, or (when required) missing entirely. Passing checks are deliberately excluded so the
   * caller can gate APPROVE on a non-empty result; a successful required check is recorded in
   * {@code seen} so it is not later mistaken for a missing one.
   */
  List<ReviewResult.CiCheck> evaluateCiChecks(
      String auth, String owner, String repo, String commitSha, List<String> requiredContexts) {
    var offending = new ArrayList<ReviewResult.CiCheck>();
    // Required contexts that reported in any state, so the missing-check pass does not re-flag a
    // green check as pending.
    var seen = new HashSet<String>();
    // Contexts already recorded as offending, so a check reported through BOTH the Check Runs API
    // and the Commit Status API is not listed twice.
    var offendingNames = new HashSet<String>();

    try {
      collectPaged(
          page -> {
            var resp =
                checkRunClient.getCheckRuns(
                    auth, ACCEPT, owner, repo, commitSha, CI_PER_PAGE, page);
            return resp == null ? null : resp.checkRuns();
          },
          run -> addOffendingCheckRun(run, requiredContexts, seen, offendingNames, offending));
    } catch (Exception e) {
      Log.warnf(e, "Failed to fetch check runs for commit %s", commitSha);
    }

    try {
      collectPaged(
          page -> {
            var resp =
                checkRunClient.getCombinedStatus(
                    auth, ACCEPT, owner, repo, commitSha, CI_PER_PAGE, page);
            return resp == null ? null : resp.statuses();
          },
          status -> addOffendingStatus(status, requiredContexts, seen, offendingNames, offending));
    } catch (Exception e) {
      Log.warnf(e, "Failed to fetch combined status for commit %s", commitSha);
    }

    addMissingRequiredChecks(requiredContexts, seen, offending);
    return offending;
  }

  /**
   * Pages through a GitHub list endpoint, applying {@code consume} to every row. Stops on an empty
   * or short page (GitHub's last-page marker) or once {@link #CI_MAX_PAGES} is reached.
   */
  private static <T> void collectPaged(IntFunction<List<T>> fetchPage, Consumer<T> consume) {
    List<T> page = null;
    for (int p = 1; p <= CI_MAX_PAGES && (page == null || page.size() == CI_PER_PAGE); p++) {
      page = fetchPage.apply(p);
      if (page == null) {
        page = List.of();
      }
      page.forEach(consume);
    }
  }

  private void addOffendingCheckRun(
      GitHubCheckRunClient.CheckRunsResponse.CheckRun run,
      List<String> requiredContexts,
      Set<String> seen,
      Set<String> offendingNames,
      List<ReviewResult.CiCheck> offending) {
    if (isThrillhouseBotCheck(run.name(), run.app())
        || isNotRequired(run.name(), requiredContexts)) {
      return;
    }
    seen.add(run.name());
    String ciStatus = classifyCheckRun(run.status(), run.conclusion());
    if (!CI_SUCCESS.equals(ciStatus) && offendingNames.add(run.name())) {
      offending.add(new ReviewResult.CiCheck(run.name(), "check-run", ciStatus, run.conclusion()));
    }
  }

  private void addOffendingStatus(
      GitHubCheckRunClient.CombinedStatus.StatusDetail status,
      List<String> requiredContexts,
      Set<String> seen,
      Set<String> offendingNames,
      List<ReviewResult.CiCheck> offending) {
    if (isThrillhouseBotCheck(status.context(), null)
        || isNotRequired(status.context(), requiredContexts)) {
      return;
    }
    seen.add(status.context());
    String ciStatus = classifyStatus(status.state());
    if (!CI_SUCCESS.equals(ciStatus) && offendingNames.add(status.context())) {
      offending.add(new ReviewResult.CiCheck(status.context(), "status", ciStatus, status.state()));
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
    // "pending", null, or any unrecognized state is not a confirmed pass: classify it as pending so
    // an indeterminate required check holds the approval rather than being mistaken for success
    // . Only an explicit "success" clears the gate.
    return CI_PENDING;
  }

  private boolean isThrillhouseBotCheck(
      String name, GitHubCheckRunClient.CheckRunsResponse.CheckRun.App app) {
    if (name != null && (name.equalsIgnoreCase(CHECK_NAME) || containsBotToken(name))) {
      return true;
    }
    return app != null && (containsBotToken(app.slug()) || containsBotToken(app.name()));
  }

  private static boolean containsBotToken(String value) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(THRILLHOUSEBOT_TOKEN);
  }
}
