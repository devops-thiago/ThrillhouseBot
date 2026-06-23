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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import io.quarkus.logging.Log;

/**
 * Shared loading for the on-request "suggestion from the diff" commands ({@code /describe}, {@code
 * /changelog}). Both load the same inputs — the PR diff, its current title/body, and the resolved
 * repository instructions — before handing them to their own assistant, so that fetch-and-degrade
 * logic lives here once. Every fetch fails soft: a failure degrades to empty context (or, for the
 * diff, to {@code null} so the caller posts nothing) rather than a noisy error on the PR.
 */
public abstract class AbstractPrSuggestionGenerator {

  protected static final String ACCEPT = "application/vnd.github+json";

  /** The PR context a suggestion is generated from. */
  protected record Inputs(String diff, String title, String body, String instructions) {}

  private GitHubPullRequestClient prClient;
  private ReviewDiffFormatter diffFormatter;
  private InstructionsResolver instructionsResolver;

  /**
   * No-args constructor required so CDI can synthesize the client-proxy subclass for the
   * {@code @ApplicationScoped} concrete generators; the proxy delegates to the real bean and never
   * reads these fields. Not for direct use.
   */
  protected AbstractPrSuggestionGenerator() {}

  protected AbstractPrSuggestionGenerator(
      GitHubPullRequestClient prClient,
      ReviewDiffFormatter diffFormatter,
      InstructionsResolver instructionsResolver) {
    this.prClient = prClient;
    this.diffFormatter = diffFormatter;
    this.instructionsResolver = instructionsResolver;
  }

  /**
   * Loads the diff, current title/body, and resolved instructions for a PR, or {@code null} when
   * there is no diff to work from (so the caller posts nothing). {@code command} labels the
   * operation in logs (e.g. {@code "/describe"}).
   */
  protected Inputs loadInputs(
      String owner,
      String repo,
      int prNumber,
      String defaultBranch,
      long installationId,
      String auth,
      String command) {
    String diff = fetchDiff(auth, owner, repo, prNumber, command);
    if (diff == null || diff.isBlank() || "(no changes detected)".equals(diff)) {
      Log.debugf("No diff for %s on %s/%s #%d — posting nothing", command, owner, repo, prNumber);
      return null;
    }
    var details = fetchDetails(auth, owner, repo, prNumber, command);
    String title = details != null && details.title() != null ? details.title() : "";
    String body = details != null && details.body() != null ? details.body() : "";
    String instructions = resolveInstructions(owner, repo, defaultBranch, installationId, command);
    return new Inputs(diff, title, body, instructions);
  }

  private String fetchDiff(String auth, String owner, String repo, int prNumber, String command) {
    try {
      var files = prClient.getPullRequestFiles(auth, ACCEPT, owner, repo, prNumber);
      return diffFormatter.buildDiffString(files);
    } catch (RuntimeException e) {
      Log.warnf(e, "Failed to fetch diff for %s on %s/%s #%d", command, owner, repo, prNumber);
      return null;
    }
  }

  private GitHubPullRequestClient.PullRequestDetails fetchDetails(
      String auth, String owner, String repo, int prNumber, String command) {
    try {
      return prClient.getPullRequest(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      // The current title/body are best-effort context; the command still works from the diff
      // alone.
      Log.warnf(
          e, "Failed to fetch PR details for %s on %s/%s #%d", command, owner, repo, prNumber);
      return null;
    }
  }

  private String resolveInstructions(
      String owner, String repo, String defaultBranch, long installationId, String command) {
    try {
      return instructionsResolver.resolve(owner, repo, defaultBranch, installationId).content();
    } catch (RuntimeException e) {
      Log.warnf(e, "Failed to resolve instructions for %s on %s/%s", command, owner, repo);
      return "";
    }
  }
}
