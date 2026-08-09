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
import io.quarkus.logging.Log;
import java.util.List;
import java.util.function.Supplier;

/**
 * Shared loading <em>and batch planning</em> for the on-request "suggestion from the diff" commands
 * ({@code /describe}, {@code /changelog}, {@code /improve}). They all load the same inputs — the PR
 * diff, its current title/body, and the resolved repository instructions — before handing them to
 * their own assistant, so that fetch-and-degrade logic lives here once. Every fetch fails soft: a
 * failure degrades to empty context (or, for the diff, to {@code null} so the caller posts nothing)
 * rather than a noisy error on the PR.
 *
 * <p>Coverage is planned the way the review path has planned it since #53: {@link
 * #planBatches(List, Inputs, String, String, int)} splits the <em>file list</em> into
 * token-budgeted batches so every changed file reaches some model call, and the files that did not
 * are named in {@link #disclosure(DiffBudgetPlanner.BudgetPlan)}. That replaces {@code
 * max-diff-lines}, which used to shrink a large PR to its first N lines before the model ever saw
 * it — a second, cruder ceiling in front of the token budget, and one that dropped whole files
 * silently.
 *
 * <p>Batching is only the map step. Each command reduces its per-batch results itself, because the
 * reductions genuinely differ (a union of suggestions, a synthesized description, one merged
 * changelog entry). A command whose reduce costs an extra model call reserves it here, via the
 * {@code reservedCalls} argument, exactly as the review path reserves one for its summary.
 */
public abstract class AbstractPrSuggestionGenerator {

  /**
   * The PR context a suggestion is generated from. {@code headSha} and {@code reviewableFiles} are
   * the head commit and the ignore-filtered files behind that diff — carried here (rather than
   * re-fetched) for the commands that anchor their suggestions back onto the diff.
   *
   * <p>{@code diff} is the whole-PR render, and is now only the "is there anything to work from"
   * signal: no command sends it to a model. Each sends {@link DiffBudgetPlanner.DiffBatch#text()}
   * instead, so the render's {@code max-diff-lines} cap no longer decides what a command covers.
   */
  protected record Inputs(
      String diff,
      String title,
      String body,
      String instructions,
      String headSha,
      List<GitHubPullRequestClient.FileDiff> reviewableFiles) {
    protected Inputs {
      reviewableFiles = reviewableFiles == null ? List.of() : List.copyOf(reviewableFiles);
    }
  }

  /**
   * The repository a command is running against, as needed to resolve that repository's own
   * settings. The PR number is deliberately absent: nothing resolved from these coordinates is
   * per-PR.
   */
  protected record CommandTarget(
      String owner, String repo, String defaultBranch, long installationId) {}

  private GitHubPullRequestClient prClient;
  private ReviewDiffFormatter diffFormatter;
  private InstructionsResolver instructionsResolver;
  private RepoSettingsResolver repoSettingsResolver;
  private DiffBudgetPlanner budgetPlanner;
  private ActiveModelSettings activeModel;
  private ThrillhouseConfig config;

  /**
   * No-args constructor required so CDI can synthesize the client-proxy subclass for the
   * {@code @ApplicationScoped} concrete generators; the proxy delegates to the real bean and never
   * reads these fields. Not for direct use.
   */
  protected AbstractPrSuggestionGenerator() {}

  protected AbstractPrSuggestionGenerator(
      GitHubPullRequestClient prClient,
      ReviewDiffFormatter diffFormatter,
      InstructionsResolver instructionsResolver,
      RepoSettingsResolver repoSettingsResolver,
      DiffBudgetPlanner budgetPlanner,
      ActiveModelSettings activeModel,
      ThrillhouseConfig config) {
    this.prClient = prClient;
    this.diffFormatter = diffFormatter;
    this.instructionsResolver = instructionsResolver;
    this.repoSettingsResolver = repoSettingsResolver;
    this.budgetPlanner = budgetPlanner;
    this.activeModel = activeModel;
    this.config = config;
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
    var files = SoftLoaders.files(prClient, auth, owner, repo, prNumber, command);
    var reviewable = diffFormatter.reviewableFiles(files);
    var formatted = diffFormatter.buildDiffStringWithStats(files, reviewable);
    String diff = formatted.text();
    if (diff == null || diff.isBlank() || "(no changes detected)".equals(diff)) {
      Log.debugf("No diff for %s on %s/%s #%d — posting nothing", command, owner, repo, prNumber);
      return null;
    }
    var details = SoftLoaders.pullRequest(prClient, auth, owner, repo, prNumber, command);
    String title = details != null && details.title() != null ? details.title() : "";
    String body = details != null && details.body() != null ? details.body() : "";
    String headSha = details != null && details.head() != null ? details.head().sha() : null;
    String instructions =
        SoftLoaders.instructions(
                instructionsResolver, owner, repo, defaultBranch, installationId, command)
            .content();
    return new Inputs(diff, title, body, instructions, headSha, reviewable);
  }

  /**
   * Re-filters the loaded file list through the repository's own ignore patterns (#449) on top of
   * the deployment-wide ones {@link #loadInputs} already applied. Per-repo patterns are strictly
   * additive, so narrowing the already-global-filtered list yields exactly the effective set
   * without re-fetching the diff. Fails soft to the global set, like the review path.
   *
   * <p>This matters more now than it did before batching: while a pass was capped at {@code
   * max-diff-lines}, a repo-ignored file beyond the cap was excluded by accident. Now that every
   * file is in scope it has to be excluded on purpose. <strong>The returned list is authoritative
   * for everything downstream</strong> — batches <em>and</em> any line map built for anchoring. A
   * command that plans batches from the filtered list but resolves lines from the unfiltered one
   * can still land a committable suggestion on a file the repository asked the bot to leave alone.
   */
  protected List<GitHubPullRequestClient.FileDiff> respectPerRepoIgnores(
      CommandTarget target,
      String command,
      List<GitHubPullRequestClient.FileDiff> globallyFiltered) {
    var repoSettings =
        SoftLoaders.repoSettings(
            repoSettingsResolver,
            target.owner(),
            target.repo(),
            target.defaultBranch(),
            target.installationId(),
            command);
    if (repoSettings.ignoredFiles().isEmpty()) {
      return globallyFiltered;
    }
    return diffFormatter.reviewableFiles(
        globallyFiltered, diffFormatter.ignoreGlobs(repoSettings.ignoredFiles()));
  }

  /**
   * Plans a command's pass as token-budgeted batches over the reviewable files, the way the review
   * path does since #53 — rather than one call over a line-capped diff string, which silently drops
   * whole files from commands whose entire value is covering the change set.
   *
   * <p>{@code systemPrompt} and {@code userPrompt} must be the calling command's <em>own</em>
   * prompt constants: they size the fixed overhead every batch call repeats, and sizing a batch
   * against another command's prompts would let an "in-budget" batch overshoot the real input
   * limit. {@code reservedCalls} is how many of the {@code max-ai-calls} allowance the command's
   * reduce step needs (0 when it reduces locally, 1 when it makes a synthesis call).
   *
   * <p>An explicit {@code max-input-tokens <= 0} disables budgeting and yields a single uncapped
   * batch.
   */
  protected DiffBudgetPlanner.BudgetPlan planBatches(
      List<GitHubPullRequestClient.FileDiff> reviewable,
      Inputs inputs,
      String systemPrompt,
      String userPrompt,
      int reservedCalls) {
    return planBatches(reviewable, inputs, systemPrompt, userPrompt, "", reservedCalls);
  }

  /**
   * As {@link #planBatches(List, Inputs, String, String, int)}, for a command that repeats a
   * non-diff section the shared overhead does not know about — {@code extraPerCallSections} is that
   * section, already escaped exactly as the command sends it.
   *
   * <p>This exists because {@link #sharedPromptOverhead} can only account for the inputs every
   * command carries. {@code /generate-tests} also sends the resolved project stack on every call,
   * and a project stack is dependency manifests: kilobytes, not a rounding error. Leaving it out of
   * the estimate is precisely the "in-budget batch overshoots the real input limit" failure the
   * overhead exists to prevent, so a command with its own extra section must declare it here rather
   * than rely on the five-argument form.
   */
  protected DiffBudgetPlanner.BudgetPlan planBatches(
      List<GitHubPullRequestClient.FileDiff> reviewable,
      Inputs inputs,
      String systemPrompt,
      String userPrompt,
      String extraPerCallSections,
      int reservedCalls) {
    if (activeModel.maxInputTokens() <= 0) {
      return budgetPlanner.plan(reviewable, 0, 1);
    }
    return budgetPlanner.plan(
        reviewable,
        sharedPromptOverhead(systemPrompt, userPrompt, inputs) + extraPerCallSections,
        budgetPlanner.perCallInputBudget(),
        maxBatches(reservedCalls));
  }

  /**
   * Everything except the diff that every batch call of one command repeats, so the planner can
   * subtract it before sizing batches. Mirrors how {@link DiffBudgetPlanner#plan(List,
   * dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService.PromptInputs)} assembles the review
   * path's overhead: both prompt templates, the per-call fence scaffolding, and each non-diff
   * section — omitting any of them would let "in-budget" batches overshoot the model's real input
   * limit.
   */
  protected String sharedPromptOverhead(String systemPrompt, String userPrompt, Inputs inputs) {
    return systemPrompt
        + userPrompt
        + PromptTemplateEscaper.fence(" ")
        + PromptTemplateEscaper.escape(inputs.title())
        + PromptTemplateEscaper.escape(inputs.body())
        + PromptTemplateEscaper.escape(inputs.instructions());
  }

  /**
   * The most batch calls one run may spend: the {@code max-ai-calls} allowance minus whatever the
   * command's reduce step reserves, so an invocation costs at most what one review costs. Always at
   * least one — a command that can make no call at all would be worse than a partial one, and the
   * uncovered files are disclosed by name either way.
   */
  private int maxBatches(int reservedCalls) {
    return Math.max(1, config.review().maxAiCalls() - Math.max(0, reservedCalls));
  }

  /**
   * The partial-coverage disclosure for a run, sourced from the token-budget plan: files that did
   * not fit any batch and files clipped to fit one. Both are named, so a large PR says which parts
   * of the change set the command did not cover rather than only how many — and coverage is now
   * bounded by {@code max-ai-calls}, never by the diff render's line cap.
   */
  protected static String disclosure(DiffBudgetPlanner.BudgetPlan plan) {
    if (!plan.truncated()) {
      return "";
    }
    return ReviewResult.truncationDisclosure(
        plan.omittedFiles().size() + plan.clippedFiles().size(),
        new ReviewResult.TruncationDetail(plan.omittedFiles(), plan.clippedFiles()));
  }

  /**
   * Discloses batches whose model call failed, so a partial pass is never presented as complete.
   * {@code missing} names what is absent as a result, in the command's own terms, and carries its
   * own sentence-ending punctuation.
   */
  protected static String batchFailureNote(int failedBatches, String command, String missing) {
    return failedBatches > 0
        ? "\n\n> ⚠️ **Partial pass.** "
            + failedBatches
            + " batch(es) of this PR could not be analyzed, so "
            + missing
            + " Re-run `"
            + command
            + "` to retry them."
        : "";
  }

  /**
   * Runs an assistant call with the shared fail-soft contract: an empty/blank reply, or any {@link
   * RuntimeException}, degrades to {@code null} (post nothing); a usable reply is returned
   * stripped. {@code command} labels the operation in logs (e.g. {@code "/describe"}). Subclasses
   * supply the actual call (and apply any command-specific post-filtering to the result).
   */
  protected String callAssistant(String command, Supplier<String> assistantCall) {
    try {
      String suggestion = assistantCall.get();
      if (suggestion == null || suggestion.isBlank()) {
        Log.debugf("%s assistant produced an empty suggestion — posting nothing", command);
        return null;
      }
      return suggestion.strip();
    } catch (RuntimeException e) {
      Log.warnf(e, "%s assistant call failed — posting nothing", command);
      return null;
    }
  }

  /** The formatter used to render the diff, so subclasses can reuse it for line resolution. */
  protected ReviewDiffFormatter diffFormatter() {
    return diffFormatter;
  }

  /** The deployment configuration, so subclasses can read their own caps. */
  protected ThrillhouseConfig config() {
    return config;
  }
}
