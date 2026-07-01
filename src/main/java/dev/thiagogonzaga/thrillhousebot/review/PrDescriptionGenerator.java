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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Builds the {@code /describe} suggestion: an improved PR title and description generated from the
 * diff, posted as a comment the author may copy in. It never edits the pull request, so the
 * author's own title and body are never overwritten.
 *
 * <p>Loads the PR's current title/body and diff and the repository instructions (via {@link
 * AbstractPrSuggestionGenerator}), asks the {@link PrDescribeAssistant} for a suggestion, then
 * wraps it in a bot comment. Every step fails soft — a failure simply yields {@code null} (post
 * nothing) rather than a noisy error on the PR.
 */
@ApplicationScoped
public class PrDescriptionGenerator extends AbstractPrSuggestionGenerator {

  private static final String COMMAND = "/describe";

  static final String HEADER = "## 🤖 ThrillhouseBot — suggested title & description\n\n";

  static final String FOOTER =
      """


      ---
      *Suggestion only — your PR was not modified. Copy whatever is useful into the title and \
      description. Re-run with `/describe`.*
      """;

  private final PrDescribeAssistant describeAssistant;

  @Inject
  public PrDescriptionGenerator(
      @RestClient GitHubPullRequestClient prClient,
      ReviewDiffFormatter diffFormatter,
      InstructionsResolver instructionsResolver,
      PrDescribeAssistant describeAssistant) {
    super(prClient, diffFormatter, instructionsResolver);
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
    var inputs = loadInputs(owner, repo, prNumber, defaultBranch, installationId, auth, COMMAND);
    if (inputs == null) {
      return null;
    }
    String suggestion =
        callAssistant(
            COMMAND,
            () ->
                describeAssistant.describe(
                    PromptTemplateEscaper.fence(inputs.diff()),
                    PromptTemplateEscaper.escape(inputs.title()),
                    PromptTemplateEscaper.escape(inputs.body()),
                    PromptTemplateEscaper.escape(inputs.instructions())));
    return suggestion == null
        ? null
        : HEADER + suggestion + FOOTER + ReviewResult.truncationDisclosure(inputs.omittedFiles());
  }
}
