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
import dev.thiagogonzaga.thrillhousebot.review.ai.ChangelogAssistant;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.Locale;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Builds the {@code /changelog} suggestion: a CHANGELOG entry drafted from the PR diff in the Keep
 * a Changelog format (Added/Changed/Fixed/Security…), posted as a comment the author may copy into
 * {@code CHANGELOG.md}. It never edits any file, so the changelog is never overwritten.
 *
 * <p>Loads the PR's diff and current title/body, the repository instructions, and asks the {@link
 * ChangelogAssistant} for an entry, then wraps it in a bot comment. Every step fails soft — a
 * failure simply yields {@code null} (post nothing) rather than a noisy error on the PR.
 */
@ApplicationScoped
public class ChangelogEntryGenerator {

  private static final String ACCEPT = "application/vnd.github+json";

  /** The assistant returns this sentinel when nothing in the diff warrants a CHANGELOG entry. */
  private static final String NONE = "NONE";

  static final String HEADER = "## 🤖 ThrillhouseBot — suggested CHANGELOG entry\n\n";

  static final String FOOTER =
      "\n\n---\n*Suggestion only — nothing was committed. Copy whatever fits into `CHANGELOG.md` "
          + "under the `[Unreleased]` section. Re-run with `/changelog`.*\n";

  private final GitHubPullRequestClient prClient;
  private final ReviewDiffFormatter diffFormatter;
  private final InstructionsResolver instructionsResolver;
  private final ChangelogAssistant changelogAssistant;

  @Inject
  public ChangelogEntryGenerator(
      @RestClient GitHubPullRequestClient prClient,
      ReviewDiffFormatter diffFormatter,
      InstructionsResolver instructionsResolver,
      ChangelogAssistant changelogAssistant) {
    this.prClient = prClient;
    this.diffFormatter = diffFormatter;
    this.instructionsResolver = instructionsResolver;
    this.changelogAssistant = changelogAssistant;
  }

  /**
   * Generates the suggested CHANGELOG entry comment for a PR, or {@code null} when there is nothing
   * to suggest (no diff, the model judged nothing changelog-worthy, or no usable answer). The
   * caller is responsible for posting it.
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
      Log.debugf(
          "No diff to draft a changelog for on %s/%s #%d — posting nothing", owner, repo, prNumber);
      return null;
    }

    var details = fetchDetails(auth, owner, repo, prNumber);
    String currentTitle = details != null && details.title() != null ? details.title() : "";
    String currentBody = details != null && details.body() != null ? details.body() : "";
    String instructions = resolveInstructions(owner, repo, defaultBranch, installationId);

    String entry = callAssistant(diff, prNumber, currentTitle, currentBody, instructions);
    if (entry == null) {
      return null;
    }
    return HEADER + entry + FOOTER;
  }

  /** Calls the assistant with already-raw inputs, escaping each for templating. Null on failure. */
  private String callAssistant(
      String diff,
      int prNumber,
      String currentTitle,
      String currentDescription,
      String instructions) {
    try {
      String entry =
          changelogAssistant.draft(
              PromptTemplateEscaper.escape(diff),
              String.valueOf(prNumber),
              PromptTemplateEscaper.escape(currentTitle),
              PromptTemplateEscaper.escape(currentDescription),
              PromptTemplateEscaper.escape(instructions));
      if (entry == null || entry.isBlank()) {
        Log.debug("Changelog assistant produced an empty entry — posting nothing");
        return null;
      }
      entry = entry.strip();
      if (NONE.equals(entry.toUpperCase(Locale.ROOT))) {
        Log.debug("Changelog assistant judged the change not changelog-worthy — posting nothing");
        return null;
      }
      return entry;
    } catch (RuntimeException e) {
      Log.warn("Changelog assistant call failed — posting nothing", e);
      return null;
    }
  }

  private String fetchDiff(String auth, String owner, String repo, int prNumber) {
    try {
      var files = prClient.getPullRequestFiles(auth, ACCEPT, owner, repo, prNumber);
      return diffFormatter.buildDiffString(files);
    } catch (RuntimeException e) {
      Log.warnf(e, "Failed to fetch diff for /changelog on %s/%s #%d", owner, repo, prNumber);
      return null;
    }
  }

  private GitHubPullRequestClient.PullRequestDetails fetchDetails(
      String auth, String owner, String repo, int prNumber) {
    try {
      return prClient.getPullRequest(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      // The current title/body are best-effort context; the entry still works from the diff alone.
      Log.warnf(e, "Failed to fetch PR details for /changelog on %s/%s #%d", owner, repo, prNumber);
      return null;
    }
  }

  private String resolveInstructions(
      String owner, String repo, String defaultBranch, long installationId) {
    try {
      return instructionsResolver.resolve(owner, repo, defaultBranch, installationId).content();
    } catch (RuntimeException e) {
      Log.warnf(e, "Failed to resolve instructions for /changelog on %s/%s", owner, repo);
      return "";
    }
  }
}
