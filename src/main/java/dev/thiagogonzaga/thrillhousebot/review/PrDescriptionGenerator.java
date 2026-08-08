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

import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrDescribeAssistant;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrDescribeAssistantPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrSuggestionPrompts;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>Coverage is token-budgeted rather than line-capped: the change set is planned into batches
 * over the whole file list, so a PR longer than {@code max-diff-lines} is no longer described from
 * its first N lines with the rest of the files unseen. A PR that needs more than one batch is
 * described in parts and the parts are <em>synthesized</em> by one further model call — one
 * description of one change, not several partial descriptions stapled together. That call is
 * reserved out of the {@code max-ai-calls} allowance, exactly as the review path reserves one for
 * its summary, and is only spent when there is genuinely more than one part.
 */
@ApplicationScoped
public class PrDescriptionGenerator extends AbstractPrSuggestionGenerator {

  private static final String COMMAND = "/describe";

  /** One call of the allowance is held back for the synthesis that composes the partials. */
  private static final int SYNTHESIS_CALLS = 1;

  static final String HEADER = "## 🤖 ThrillhouseBot — suggested title & description\n\n";

  /**
   * Posted when the plan covered nothing: every file overflowed even a single-file batch, which
   * only happens with a per-call input budget too small for any clip of any file. Staying silent
   * would make a misconfigured budget look like a bot that ignored the command, and the files it
   * could not read would go unnamed — the very failure mode batching replaced.
   */
  static final String NOT_COVERED =
      "🤖 ThrillhouseBot could not fit any part of this PR into the per-call input-token budget,"
          + " so it has nothing to describe. Raise the input-token budget and re-run `/describe`.";

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
      RepoSettingsResolver repoSettingsResolver,
      DiffBudgetPlanner budgetPlanner,
      ActiveModelSettings activeModel,
      ThrillhouseConfig config,
      PrDescribeAssistant describeAssistant) {
    super(
        prClient,
        diffFormatter,
        instructionsResolver,
        repoSettingsResolver,
        budgetPlanner,
        activeModel,
        config);
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
    // Resolved once and threaded downstream, so nothing later re-derives the in-scope file list.
    var reviewable =
        respectPerRepoIgnores(
            new CommandTarget(owner, repo, defaultBranch, installationId),
            COMMAND,
            inputs.reviewableFiles());
    var plan =
        planBatches(
            reviewable,
            inputs,
            PrDescribeAssistantPrompts.systemPrompt(),
            PrSuggestionPrompts.userPrompt(),
            SYNTHESIS_CALLS);
    if (plan.batches().isEmpty()) {
      Log.debugf("No file of %s/%s #%d fit a /describe batch", owner, repo, prNumber);
      // Nothing fitted, but the files that did not are known: name them rather than go quiet. A
      // plan that covered nothing and omitted nothing means no file was in scope at all (every one
      // ignored), which is genuinely nothing to say.
      return plan.truncated() ? NOT_COVERED + disclosure(plan) : null;
    }
    var drafted = describeEachBatch(inputs, plan);
    if (drafted.partials().isEmpty()) {
      return null;
    }
    String suggestion = reduce(inputs, drafted.partials());
    if (suggestion == null) {
      return null;
    }
    return HEADER
        + suggestion
        + batchFailureNote(
            drafted.failedBatches(), COMMAND, "the files in them are not described here.")
        + FOOTER
        + disclosure(plan);
  }

  /** The per-batch partial descriptions that came back, plus how many batch calls failed. */
  private record Drafted(List<String> partials, int failedBatches) {}

  /**
   * The map step: one description per batch. A batch whose call fails is skipped rather than
   * failing the run — the other batches still describe most of the PR — and the count is disclosed.
   */
  private Drafted describeEachBatch(Inputs inputs, DiffBudgetPlanner.BudgetPlan plan) {
    var partials = new ArrayList<String>(plan.batches().size());
    int failed = 0;
    for (var batch : plan.batches()) {
      String partial =
          callAssistant(
              COMMAND,
              () ->
                  describeAssistant.describe(
                      PromptTemplateEscaper.fence(batch.text()),
                      PromptTemplateEscaper.escape(inputs.title()),
                      PromptTemplateEscaper.escape(inputs.body()),
                      PromptTemplateEscaper.escape(inputs.instructions())));
      if (partial == null) {
        failed++;
      } else {
        partials.add(partial);
      }
    }
    return new Drafted(List.copyOf(partials), failed);
  }

  /**
   * The reduce step: one coherent description of the whole PR. A single partial <em>is</em> that
   * description, so the reserved call is not spent; two or more are composed by the synthesis call
   * rather than concatenated, because concatenated partials repeat the overview once per part and
   * read as several pull requests.
   *
   * <p>The synthesis input is bounded by construction — at most {@code max-ai-calls - 1} partial
   * descriptions, each a PR description rather than a diff — so it stays far below the per-call
   * budget the batches were sized against.
   */
  private String reduce(Inputs inputs, List<String> partials) {
    if (partials.size() == 1) {
      return partials.get(0);
    }
    return callAssistant(
        COMMAND,
        () ->
            describeAssistant.synthesize(
                PromptTemplateEscaper.fence(joinPartials(partials)),
                PromptTemplateEscaper.escape(inputs.title()),
                PromptTemplateEscaper.escape(inputs.body()),
                PromptTemplateEscaper.escape(inputs.instructions())));
  }

  /**
   * The partials as one fenced block, each under a numbered heading so the model can tell them
   * apart. The headings are ours, not PR content, and the whole block is fenced as untrusted data.
   */
  private static String joinPartials(List<String> partials) {
    var sb = new StringBuilder();
    for (int i = 0; i < partials.size(); i++) {
      sb.append("## Part ").append(i + 1).append(" of ").append(partials.size()).append("\n");
      sb.append(partials.get(i)).append("\n\n");
    }
    return sb.toString().stripTrailing();
  }
}
