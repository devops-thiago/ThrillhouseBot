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
 * <p>Loads the PR's diff and current title/body and the repository instructions (via {@link
 * AbstractPrSuggestionGenerator}), asks the {@link ChangelogAssistant} for an entry, then wraps it
 * in a bot comment. Every step fails soft — a failure simply yields {@code null} (post nothing)
 * rather than a noisy error on the PR.
 */
@ApplicationScoped
public class ChangelogEntryGenerator extends AbstractPrSuggestionGenerator {

  private static final String COMMAND = "/changelog";

  /** The assistant returns this sentinel when nothing in the diff warrants a CHANGELOG entry. */
  private static final String NONE = "NONE";

  static final String HEADER = "## 🤖 ThrillhouseBot — suggested CHANGELOG entry\n\n";

  static final String FOOTER =
      """


      ---
      *Suggestion only — nothing was committed. Copy whatever fits into `CHANGELOG.md` \
      under the `[Unreleased]` section. Re-run with `/changelog`.*
      """;

  private final ChangelogAssistant changelogAssistant;

  @Inject
  public ChangelogEntryGenerator(
      @RestClient GitHubPullRequestClient prClient,
      ReviewDiffFormatter diffFormatter,
      InstructionsResolver instructionsResolver,
      ChangelogAssistant changelogAssistant) {
    super(prClient, diffFormatter, instructionsResolver);
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
    var inputs = loadInputs(owner, repo, prNumber, defaultBranch, installationId, auth, COMMAND);
    if (inputs == null) {
      return null;
    }
    String entry = callAssistant(inputs, prNumber);
    return entry == null ? null : HEADER + entry + FOOTER;
  }

  /**
   * Calls the assistant with already-loaded inputs, escaping each for templating. Null on failure.
   */
  private String callAssistant(Inputs inputs, int prNumber) {
    try {
      String entry =
          changelogAssistant.draft(
              PromptTemplateEscaper.escape(inputs.diff()),
              String.valueOf(prNumber),
              PromptTemplateEscaper.escape(inputs.title()),
              PromptTemplateEscaper.escape(inputs.body()),
              PromptTemplateEscaper.escape(inputs.instructions()));
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
}
