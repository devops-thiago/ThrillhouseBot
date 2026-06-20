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
import dev.thiagogonzaga.thrillhousebot.review.ai.PrDescribeAssistant;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Builds the {@code /describe} suggestion: an improved PR title and description generated from the
 * diff, posted as a comment the author may copy in. It never edits the pull request, so the
 * author's own title and body are never overwritten.
 *
 * <p>Loads the PR's current title/body and diff, the repository instructions, and asks the {@link
 * PrDescribeAssistant} for a suggestion, then wraps it in a bot comment. Every step fails soft — a
 * failure simply yields {@code null} (post nothing) rather than a noisy error on the PR.
 */
@ApplicationScoped
public class PrDescriptionGenerator {

  private static final String ACCEPT = "application/vnd.github+json";

  static final String HEADER = "## 🤖 ThrillhouseBot — suggested title & description\n\n";

  static final String FOOTER =
      "\n\n---\n*Suggestion only — your PR was not modified. Copy whatever is useful into the "
          + "title and description. Re-run with `/describe`.*\n";

  private final GitHubPullRequestClient prClient;
  private final ReviewDiffFormatter diffFormatter;
  private final InstructionsResolver instructionsResolver;
  private final PrDescribeAssistant describeAssistant;

  @Inject
  public PrDescriptionGenerator(
      @RestClient GitHubPullRequestClient prClient,
      ReviewDiffFormatter diffFormatter,
      InstructionsResolver instructionsResolver,
      PrDescribeAssistant describeAssistant) {
    this.prClient = prClient;
    this.diffFormatter = diffFormatter;
    this.instructionsResolver = instructionsResolver;
    this.describeAssistant = describeAssistant;
  }

  /**
   * Generates the suggestion comment body for a PR, or {@code null} when there is nothing to
   * suggest (no diff) or the model produced no usable answer. The caller is responsible for posting
   * it.
   *
   * @param auth the {@code Authorization} header for the installation (already minted by the
   *     caller)
   */
  @ActivateRequestContext
  public String generate(
      String owner,
      String repo,
      int prNumber,
      String defaultBranch,
      long installationId,
      String auth) {
    String diff = fetchDiff(auth, owner, repo, prNumber);
    if (diff == null || diff.isBlank() || "(no changes detected)".equals(diff)) {
      Log.debugf("No diff to describe on %s/%s #%d — posting nothing", owner, repo, prNumber);
      return null;
    }

    var details = fetchDetails(auth, owner, repo, prNumber);
    String currentTitle = details != null && details.title() != null ? details.title() : "";
    String currentBody = details != null && details.body() != null ? details.body() : "";
    String instructions = resolveInstructions(owner, repo, defaultBranch, installationId);

    String suggestion = callAssistant(diff, currentTitle, currentBody, instructions);
    if (suggestion == null) {
      return null;
    }
    return HEADER + suggestion + FOOTER;
  }

  /** Calls the assistant with already-raw inputs, escaping each for templating. Null on failure. */
  private String callAssistant(
      String diff, String currentTitle, String currentDescription, String instructions) {
    try {
      String suggestion =
          describeAssistant.describe(
              PromptTemplateEscaper.escape(diff),
              PromptTemplateEscaper.escape(currentTitle),
              PromptTemplateEscaper.escape(currentDescription),
              PromptTemplateEscaper.escape(instructions));
      if (suggestion == null || suggestion.isBlank()) {
        Log.debug("Describe assistant produced an empty suggestion — posting nothing");
        return null;
      }
      return suggestion.strip();
    } catch (RuntimeException e) {
      Log.warn("Describe assistant call failed — posting nothing", e);
      return null;
    }
  }

  private String fetchDiff(String auth, String owner, String repo, int prNumber) {
    try {
      var files = prClient.getPullRequestFiles(auth, ACCEPT, owner, repo, prNumber);
      return diffFormatter.buildDiffString(files);
    } catch (RuntimeException e) {
      Log.warnf(e, "Failed to fetch diff for /describe on %s/%s #%d", owner, repo, prNumber);
      return null;
    }
  }

  private GitHubPullRequestClient.PullRequestDetails fetchDetails(
      String auth, String owner, String repo, int prNumber) {
    try {
      return prClient.getPullRequest(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      // The current title/body are best-effort context; describe still works from the diff alone.
      Log.warnf(e, "Failed to fetch PR details for /describe on %s/%s #%d", owner, repo, prNumber);
      return null;
    }
  }

  private String resolveInstructions(
      String owner, String repo, String defaultBranch, long installationId) {
    try {
      return instructionsResolver.resolve(owner, repo, defaultBranch, installationId).content();
    } catch (RuntimeException e) {
      Log.warnf(e, "Failed to resolve instructions for /describe on %s/%s", owner, repo);
      return "";
    }
  }
}
