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
   * The batching plan: ordered in-budget batches plus any files that did not fit, by name. {@code
   * budgeted} is false only for an explicit {@code max-input-tokens=0} (legacy single uncapped
   * batch) — consumers use it to pick between the plan's omissions and the legacy line-cap count.
   */
  public record BudgetPlan(List<DiffBatch> batches, List<String> omittedFiles, boolean budgeted) {
    public BudgetPlan {
      batches = List.copyOf(batches);
      omittedFiles = List.copyOf(omittedFiles);
    }

    public boolean truncated() {
      return !omittedFiles.isEmpty();
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
    var inputBudget =
        (int) (review.maxInputTokens() * review.tokenSafetyMargin()) - review.outputBufferTokens();
    var sharedOverhead =
        PrReviewPrompts.SYSTEM
            + PrReviewPrompts.USER
            + inputs.prContext()
            + inputs.baseComparison()
            + inputs.projectStack()
            + inputs.relatedTests()
            + inputs.previousFindings()
            + inputs.repoInstructions();
    return plan(reviewable, sharedOverhead, inputBudget, Math.max(1, review.maxAiCalls() - 1));
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
      return new BudgetPlan(List.of(), List.of(), budgeted);
    }

    var rendered = renderAndSize(reviewable, diffBudgetTokens);

    if (!budgeted) {
      return new BudgetPlan(List.of(toBatch(rendered.sized())), List.of(), false);
    }
    return pack(rendered, diffBudgetTokens, Math.max(1, maxBatches));
  }

  /** A file rendered to its diff section with a token estimate (oversized files pre-clipped). */
  private record Sized(GitHubPullRequestClient.FileDiff file, String text, int tokens) {}

  /** Rendered sections plus the files no clipping could fit — omitted by name, never packed. */
  private record Rendered(List<Sized> sized, List<String> unclippable) {}

  private Rendered renderAndSize(
      List<GitHubPullRequestClient.FileDiff> reviewable, int diffBudgetTokens) {
    // Highest-impact first so that, when bins fill, the files left out by name are the smallest.
    var ordered = new ArrayList<>(reviewable);
    ordered.sort(
        Comparator.comparingInt(
                (GitHubPullRequestClient.FileDiff f) -> f.additions() + f.deletions())
            .reversed()
            .thenComparing(GitHubPullRequestClient.FileDiff::filename));

    var reviewableNames = ReviewDiffFormatter.namesOf(reviewable);
    var sized = new ArrayList<Sized>(ordered.size());
    var unclippable = new ArrayList<String>();
    for (var file : ordered) {
      var section = formatter.formatFileSection(file, reviewableNames);
      if (diffBudgetTokens <= 0) {
        // The disabled-budgeting path never reads the estimates; skip the BPE pass entirely.
        sized.add(new Sized(file, section, 0));
      } else {
        sizeWithinBudget(file, section, diffBudgetTokens, sized, unclippable);
      }
    }
    return new Rendered(sized, unclippable);
  }

  /**
   * Estimates (clipping if oversized) one file section into {@code sized}, or records it in {@code
   * unclippable} when no clip fits: content the model would never see must not count as reviewed —
   * the file is reported by name (holds APPROVE, disclosed) instead of packing a placeholder as
   * coverage.
   */
  private void sizeWithinBudget(
      GitHubPullRequestClient.FileDiff file,
      String section,
      int diffBudgetTokens,
      List<Sized> sized,
      List<String> unclippable) {
    var tokens = tokenCounter.estimateTokens(section);
    if (tokens > diffBudgetTokens) {
      var clipped = clipToBudget(section, diffBudgetTokens);
      if (clipped == null) {
        unclippable.add(file.filename());
        return;
      }
      section = clipped;
      tokens = tokenCounter.estimateTokens(clipped);
    }
    sized.add(new Sized(file, section, tokens));
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
    return new BudgetPlan(batches, omitted, true);
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
