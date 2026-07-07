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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Splits a PR's reviewable files into token-budgeted batches so every file is covered by some model
 * call (#53), replacing the old silent line cap. Files are ordered highest-impact-first and packed
 * First-Fit-Decreasing into at most {@code maxBatches} bins, each within the per-call token budget;
 * a single file larger than one budget is hunk-clipped (via {@link ReviewDiffFormatter}) to fit,
 * and anything that still does not fit — once every bin is full — is reported {@link
 * BudgetPlan#omittedFiles() by name} rather than dropped silently.
 *
 * <p>The budget passed here is the share left for diff text after the caller subtracts the fixed
 * prompt overhead (system prompt, project stack, previous findings, instructions). Token estimates
 * come from {@link TokenCounter}; the caller applies its safety margin to the budget.
 */
@ApplicationScoped
public class DiffBudgetPlanner {

  private final ReviewDiffFormatter formatter;
  private final TokenCounter tokenCounter;
  private final ThrillhouseConfig config;

  @Inject
  public DiffBudgetPlanner(
      ReviewDiffFormatter formatter, TokenCounter tokenCounter, ThrillhouseConfig config) {
    this.formatter = formatter;
    this.tokenCounter = tokenCounter;
    this.config = config;
  }

  /** One in-budget batch: its rendered diff text, the files it covers, and the token estimate. */
  public record DiffBatch(
      String text, List<GitHubPullRequestClient.FileDiff> files, int estimatedTokens) {
    public DiffBatch {
      files = List.copyOf(files);
    }
  }

  /**
   * The batching plan: ordered in-budget batches, files that did not fit at all (by name), and
   * files that were hunk-clipped to fit their batch — covered, but only partially analyzed, so the
   * summary must not present them as fully reviewed. {@code budgeted} is false only for an explicit
   * {@code max-input-tokens=0} (legacy single uncapped batch) — consumers use it to pick between
   * the plan's omissions and the legacy line-cap count.
   */
  public record BudgetPlan(
      List<DiffBatch> batches,
      List<String> omittedFiles,
      List<String> clippedFiles,
      boolean budgeted) {
    public BudgetPlan {
      batches = List.copyOf(batches);
      omittedFiles = List.copyOf(omittedFiles);
      clippedFiles = List.copyOf(clippedFiles);
    }

    public boolean truncated() {
      // Clipped unseen hunks withhold coverage like omitted files — both hold APPROVE.
      return !omittedFiles.isEmpty() || !clippedFiles.isEmpty();
    }

    public boolean multiCall() {
      return batches.size() > 1;
    }
  }

  /**
   * Plans batches for a review from its fully assembled prompt inputs. Owns the budget math: the
   * per-call input budget is {@code max-input-tokens * token-safety-margin - output-buffer-tokens},
   * one call of the {@code max-ai-calls} cap is reserved for the final summary, and the shared
   * overhead is sized from the prompt templates plus every non-diff section the batch calls
   * actually repeat — including PR context, related tests, and trailing guidance, whose omission
   * would let "in-budget" batches overshoot the real input limit. The base comparison is counted
   * too: the budgeted single-batch call keeps it (multi-batch calls drop it, so they under-fill
   * slightly, which errs safe). An explicit {@code max-input-tokens <= 0} disables budgeting.
   */
  public BudgetPlan plan(
      List<GitHubPullRequestClient.FileDiff> reviewable, AiReviewService.PromptInputs inputs) {
    var review = config.review();
    if (review.maxInputTokens() <= 0) {
      return plan(reviewable, 0, 1);
    }
    // fence(" ") produces the two real fence lines (fence of empty content is a no-op by design),
    // counting the per-review scaffolding the pipeline wraps each batch in — small, but the safety
    // margin should absorb estimate error, not known constants.
    var sharedOverhead =
        PrReviewPrompts.SYSTEM
            + PrReviewPrompts.USER
            + PromptTemplateEscaper.fence(" ")
            + inputs.prContext()
            + inputs.baseComparison()
            + inputs.projectStack()
            + inputs.relatedTests()
            + inputs.previousFindings()
            + inputs.repoInstructions();
    return plan(
        reviewable, sharedOverhead, perCallInputBudget(), Math.max(1, review.maxAiCalls() - 1));
  }

  /**
   * The per-call input-token budget every review-path AI call must fit — {@code max-input-tokens *
   * token-safety-margin - output-buffer-tokens} — or {@link Integer#MAX_VALUE} when budgeting is
   * disabled. Shared with the pipeline so the summary call is bounded by the same ceiling as the
   * batch calls.
   */
  public int perCallInputBudget() {
    var review = config.review();
    if (review.maxInputTokens() <= 0) {
      return Integer.MAX_VALUE;
    }
    return (int) (review.maxInputTokens() * review.tokenSafetyMargin())
        - review.outputBufferTokens();
  }

  /**
   * Plans batches given the per-call input budget and the shared prompt overhead that every batch
   * call repeats. The diff budget is what remains of {@code inputBudgetTokens} after that overhead,
   * so each call's full prompt (shared + one batch's diff) stays within the model's input limit.
   * Overhead consuming the whole budget must not silently disable budgeting (the operator
   * configured a limit; the prompt is at its largest exactly then), so the diff budget is floored
   * at 1 token — most files then overflow into {@link BudgetPlan#omittedFiles()} and the partial
   * review is disclosed.
   */
  BudgetPlan plan(
      List<GitHubPullRequestClient.FileDiff> reviewable,
      String sharedPromptOverhead,
      int inputBudgetTokens,
      int maxBatches) {
    var overheadTokens = tokenCounter.estimateTokens(sharedPromptOverhead);
    var diffBudget = inputBudgetTokens - overheadTokens;
    if (diffBudget <= 0) {
      Log.warnf(
          "Shared prompt overhead (%d tokens) consumes the whole input budget (%d tokens);"
              + " batching with a minimal diff budget — most files will be omitted by name",
          overheadTokens, inputBudgetTokens);
      diffBudget = 1;
    }
    return plan(reviewable, diffBudget, maxBatches);
  }

  /**
   * Plans batches for the already-ignore-glob-filtered {@code reviewable} files. A {@code
   * diffBudgetTokens <= 0} disables budgeting — every file lands in a single batch (the legacy "no
   * cap" behaviour for an explicit {@code max-input-tokens=0}); overload above never passes it.
   */
  BudgetPlan plan(
      List<GitHubPullRequestClient.FileDiff> reviewable, int diffBudgetTokens, int maxBatches) {
    var budgeted = diffBudgetTokens > 0;
    if (reviewable.isEmpty()) {
      return new BudgetPlan(List.of(), List.of(), List.of(), budgeted);
    }

    var rendered = renderAndSize(reviewable, diffBudgetTokens);

    if (!budgeted) {
      return new BudgetPlan(List.of(toBatch(rendered.sized())), List.of(), List.of(), false);
    }
    return pack(rendered, diffBudgetTokens, Math.max(1, maxBatches));
  }

  /** A file rendered to its diff section with a token estimate (oversized files pre-clipped). */
  private record Sized(GitHubPullRequestClient.FileDiff file, String text, int tokens) {}

  /**
   * Rendered sections plus the files no clipping could fit (omitted by name, never packed) and the
   * files that were clipped to fit (covered, but only partially analyzed).
   */
  private record Rendered(List<Sized> sized, List<String> unclippable, List<String> clipped) {}

  private Rendered renderAndSize(
      List<GitHubPullRequestClient.FileDiff> reviewable, int diffBudgetTokens) {
    var ordered = new ArrayList<>(reviewable);
    ordered.sort(
        Comparator.comparingInt(
                (GitHubPullRequestClient.FileDiff f) -> f.additions() + f.deletions())
            .reversed()
            .thenComparing(GitHubPullRequestClient.FileDiff::filename));

    var reviewableNames = ReviewDiffFormatter.namesOf(reviewable);
    var rendered = new Rendered(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    for (var file : ordered) {
      var section = formatter.formatFileSection(file, reviewableNames);
      if (diffBudgetTokens <= 0) {
        // The disabled-budgeting path never reads the estimates; skip the BPE pass entirely.
        rendered.sized().add(new Sized(file, section, 0));
      } else {
        sizeWithinBudget(file, section, diffBudgetTokens, rendered);
      }
    }
    return rendered;
  }

  /**
   * Estimates (clipping if oversized) one file section into the rendered result. A clipped file is
   * recorded so the summary can disclose the partial coverage; a file no clip fits is recorded as
   * unclippable — content the model would never see must not count as reviewed, so it is reported
   * by name (holds APPROVE, disclosed) instead of packing a placeholder as coverage.
   */
  private void sizeWithinBudget(
      GitHubPullRequestClient.FileDiff file,
      String section,
      int diffBudgetTokens,
      Rendered rendered) {
    var tokens = tokenCounter.estimateTokens(section);
    if (tokens > diffBudgetTokens) {
      var clipped = clipToBudget(section, diffBudgetTokens);
      if (clipped == null) {
        rendered.unclippable().add(file.filename());
        return;
      }
      rendered.clipped().add(file.filename());
      section = clipped;
      tokens = tokenCounter.estimateTokens(clipped);
    }
    rendered.sized().add(new Sized(file, section, tokens));
  }

  private static DiffBatch toBatch(List<Sized> sized) {
    var text = new StringBuilder();
    var files = new ArrayList<GitHubPullRequestClient.FileDiff>(sized.size());
    var tokens = 0;
    for (var s : sized) {
      text.append(s.text());
      files.add(s.file());
      tokens += s.tokens();
    }
    return new DiffBatch(text.toString(), files, tokens);
  }

  private static BudgetPlan pack(Rendered rendered, int diffBudgetTokens, int maxBatches) {
    var binSections = new ArrayList<List<Sized>>();
    var binTokens = new ArrayList<Integer>();
    var omitted = new ArrayList<>(rendered.unclippable());

    for (var s : rendered.sized()) {
      int target = firstFit(binTokens, s.tokens(), diffBudgetTokens);
      if (target < 0 && binSections.size() < maxBatches) {
        binSections.add(new ArrayList<>());
        binTokens.add(0);
        target = binSections.size() - 1;
      }
      if (target < 0) {
        omitted.add(s.file().filename());
        continue;
      }
      binSections.get(target).add(s);
      binTokens.set(target, binTokens.get(target) + s.tokens());
    }

    var batches = new ArrayList<DiffBatch>(binSections.size());
    for (var bin : binSections) {
      batches.add(toBatch(bin));
    }
    // A clipped file can still overflow every bin and end up omitted; each file must land in
    // exactly one class or the disclosure would list it twice and the verdict double-count it.
    var omittedSet = new HashSet<>(omitted);
    var clipped = rendered.clipped().stream().filter(n -> !omittedSet.contains(n)).toList();
    return new BudgetPlan(batches, omitted, clipped, true);
  }

  /** Index of the first open bin with room for {@code tokens}, or -1 if none. */
  private static int firstFit(List<Integer> binTokens, int tokens, int budget) {
    for (var i = 0; i < binTokens.size(); i++) {
      if (binTokens.get(i) + tokens <= budget) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Hunk-clips a single oversized file section down to {@code budgetTokens}. Converts the token
   * budget to a line budget by ratio and re-clips from the original a few times until it fits — a
   * bounded loop, since {@link ReviewDiffFormatter#truncateSection} is monotonic in line count.
   * Returns {@code null} when even a one-line clip stays over budget (pathologically small
   * budgets): the caller must omit the file by name rather than present a stub as coverage.
   */
  private String clipToBudget(String section, int budgetTokens) {
    var target = Math.max(1, (int) (budgetTokens * 0.9));
    var lines = ReviewDiffFormatter.lineCount(section);
    var clipped = section;
    var clippedTokens = tokenCounter.estimateTokens(clipped);
    for (var i = 0; i < 8 && clippedTokens > budgetTokens; i++) {
      var ratio = Math.min(0.95, (double) target / Math.max(1, clippedTokens));
      lines = Math.max(1, (int) (lines * ratio));
      clipped = ReviewDiffFormatter.truncateSection(section, lines);
      clippedTokens = tokenCounter.estimateTokens(clipped);
    }
    if (clippedTokens > budgetTokens) {
      Log.warnf(
          "File section exceeds the %d-token budget even after clipping; omitting the file by name",
          budgetTokens);
      return null;
    }
    return clipped;
  }
}
