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
import dev.thiagogonzaga.thrillhousebot.review.ai.ChangelogAssistant;
import dev.thiagogonzaga.thrillhousebot.review.ai.ChangelogAssistantPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrSuggestionPrompts;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>Coverage is token-budgeted rather than line-capped: the change set is planned into batches
 * over the whole file list, so an entry is no longer drafted from the first {@code max-diff-lines}
 * of a large PR with the remaining files unseen. A PR that needs more than one batch produces one
 * candidate entry per batch, merged into a single entry by one further model call — reserved out of
 * the {@code max-ai-calls} allowance and only spent when there is genuinely more than one
 * candidate.
 */
@ApplicationScoped
public class ChangelogEntryGenerator extends AbstractPrSuggestionGenerator {

  private static final String COMMAND = "/changelog";

  /** One call of the allowance is held back for the merge that reduces the candidates to one. */
  private static final int MERGE_CALLS = 1;

  /** The assistant returns this sentinel when nothing in the diff warrants a CHANGELOG entry. */
  private static final String NONE = "NONE";

  /** Markdown emphasis/quote/list markers stripped from the start before matching {@code NONE}. */
  private static final String LEADING_MARKERS = "`*_>#-";

  /**
   * Markdown emphasis and trailing punctuation stripped from the end before matching {@code NONE}.
   */
  private static final String TRAILING_MARKERS = "`*_.!";

  static final String HEADER = "## 🤖 ThrillhouseBot — suggested CHANGELOG entry\n\n";

  /**
   * Posted when the plan covered nothing: every file overflowed even a single-file batch, which
   * only happens with a per-call input budget too small for any clip of any file. Staying silent
   * would make a misconfigured budget look like a bot that ignored the command, and the files it
   * could not read would go unnamed — the very failure mode batching replaced.
   */
  static final String NOT_COVERED =
      "🤖 ThrillhouseBot could not fit any part of this PR into the per-call input-token budget,"
          + " so it has nothing to draft a CHANGELOG entry from. Raise the input-token budget and"
          + " re-run `/changelog`.";

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
      RepoSettingsResolver repoSettingsResolver,
      DiffBudgetPlanner budgetPlanner,
      ActiveModelSettings activeModel,
      ThrillhouseConfig config,
      ChangelogAssistant changelogAssistant) {
    super(
        prClient,
        diffFormatter,
        instructionsResolver,
        repoSettingsResolver,
        budgetPlanner,
        activeModel,
        config);
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
            ChangelogAssistantPrompts.system(),
            PrSuggestionPrompts.user(),
            MERGE_CALLS);
    if (plan.batches().isEmpty()) {
      Log.debugf("No file of %s/%s #%d fit a /changelog batch", owner, repo, prNumber);
      // Nothing fitted, but the files that did not are known: name them rather than go quiet. A
      // plan that covered nothing and omitted nothing means no file was in scope at all (every one
      // ignored), which is genuinely nothing to say.
      return plan.truncated() ? NOT_COVERED + disclosure(plan) : null;
    }
    var drafted = draftEachBatch(inputs, prNumber, plan);
    if (drafted.candidates().isEmpty()) {
      // Either every batch declined (nothing changelog-worthy) or every batch call failed; both
      // mean there is no entry to post.
      Log.debug("No /changelog candidate entry survived the batches — posting nothing");
      return null;
    }
    String entry = reduce(inputs, prNumber, drafted.candidates());
    if (entry == null || isNoneVerdict(entry)) {
      return null;
    }
    return HEADER
        + entry
        + batchFailureNote(
            drafted.failedBatches(), COMMAND, "the files in them are not covered by this entry.")
        + FOOTER
        + disclosure(plan);
  }

  /** The per-batch candidate entries worth keeping, plus how many batch calls failed. */
  private record Drafted(List<String> candidates, int failedBatches) {}

  /**
   * The map step: one candidate entry per batch. A batch that declines with {@code NONE}
   * contributes nothing — it genuinely found nothing changelog-worthy in its files — while a batch
   * whose call fails is counted so the shortfall is disclosed rather than silently absorbed into a
   * decline.
   */
  private Drafted draftEachBatch(Inputs inputs, int prNumber, DiffBudgetPlanner.BudgetPlan plan) {
    var candidates = new ArrayList<String>(plan.batches().size());
    int failed = 0;
    for (var batch : plan.batches()) {
      String candidate =
          callAssistant(
              COMMAND,
              () ->
                  changelogAssistant.draft(
                      PromptTemplateEscaper.fence(batch.text()),
                      String.valueOf(prNumber),
                      PromptTemplateEscaper.escape(inputs.title()),
                      PromptTemplateEscaper.escape(inputs.body()),
                      PromptTemplateEscaper.escape(inputs.instructions())));
      if (candidate == null) {
        failed++;
      } else if (isNoneVerdict(candidate)) {
        Log.debug("A /changelog batch judged its files not changelog-worthy — dropping it");
      } else {
        candidates.add(candidate);
      }
    }
    return new Drafted(List.copyOf(candidates), failed);
  }

  /**
   * The reduce step: exactly one entry. A single candidate already is that entry, so the reserved
   * call is not spent.
   *
   * <p>Two or more candidates are merged by a model call rather than by concatenating their
   * sections. A deterministic merge could unify the headings and dedupe identical bullet strings,
   * but the duplicates that actually arise are not identical: two batches that saw different files
   * of the same feature describe that one user-facing change in two different sentences, and an
   * entry that lists the same change twice is exactly what a maintainer would have to rewrite by
   * hand. A changelog entry is short, so the merge is cheap; correctness is what it buys.
   */
  private String reduce(Inputs inputs, int prNumber, List<String> candidates) {
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    return callAssistant(
        COMMAND,
        () ->
            changelogAssistant.merge(
                PromptTemplateEscaper.fence(joinCandidates(candidates)),
                String.valueOf(prNumber),
                PromptTemplateEscaper.escape(inputs.title()),
                PromptTemplateEscaper.escape(inputs.body()),
                PromptTemplateEscaper.escape(inputs.instructions())));
  }

  /**
   * The candidates as one fenced block, each under a numbered heading so the model can tell them
   * apart. The headings are ours, not PR content, and the whole block is fenced as untrusted data.
   */
  private static String joinCandidates(List<String> candidates) {
    var sb = new StringBuilder();
    for (int i = 0; i < candidates.size(); i++) {
      sb.append("## Candidate ")
          .append(i + 1)
          .append(" of ")
          .append(candidates.size())
          .append("\n")
          .append(candidates.get(i))
          .append("\n\n");
    }
    return sb.toString().stripTrailing();
  }

  /**
   * Whether the assistant's whole reply is just the {@code NONE} sentinel — tolerating surrounding
   * markdown emphasis/quote markers and trailing punctuation (e.g. {@code **NONE**}, {@code
   * NONE.}), so a decorated decline is never posted as a literal changelog entry. Only the entire
   * reply collapsing to {@code NONE} counts; a real entry that merely mentions the word does not.
   */
  private static boolean isNoneVerdict(String entry) {
    int start = 0;
    int end = entry.length();
    while (start < end && isTrimmable(entry.charAt(start), LEADING_MARKERS)) {
      start++;
    }
    while (end > start && isTrimmable(entry.charAt(end - 1), TRAILING_MARKERS)) {
      end--;
    }
    return NONE.equalsIgnoreCase(entry.substring(start, end));
  }

  private static boolean isTrimmable(char c, String markers) {
    return Character.isWhitespace(c) || markers.indexOf(c) >= 0;
  }
}
