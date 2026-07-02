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
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

  private static final String PLACEHOLDER_OVER_BUDGET =
      "(file section omitted — exceeds the per-call token budget)\n";

  private final ReviewDiffFormatter formatter;
  private final TokenCounter tokenCounter;

  @Inject
  public DiffBudgetPlanner(ReviewDiffFormatter formatter, TokenCounter tokenCounter) {
    this.formatter = formatter;
    this.tokenCounter = tokenCounter;
  }

  /** One in-budget batch: its rendered diff text, the files it covers, and the token estimate. */
  public record DiffBatch(
      String text, List<GitHubPullRequestClient.FileDiff> files, int estimatedTokens) {
    public DiffBatch {
      files = List.copyOf(files);
    }
  }

  /** The batching plan: ordered in-budget batches plus any files that did not fit, by name. */
  public record BudgetPlan(List<DiffBatch> batches, List<String> omittedFiles) {
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
   * Plans batches given the per-call input budget and the shared prompt overhead that every batch
   * call repeats (system prompt, project stack, previous findings, instructions). The diff budget
   * is what remains of {@code inputBudgetTokens} after that overhead, so each call's full prompt
   * (shared + one batch's diff) stays within the model's input limit.
   */
  BudgetPlan plan(
      List<GitHubPullRequestClient.FileDiff> files,
      String sharedPromptOverhead,
      int inputBudgetTokens,
      int maxBatches) {
    var diffBudget = inputBudgetTokens - tokenCounter.estimateTokens(sharedPromptOverhead);
    return plan(files, diffBudget, maxBatches);
  }

  /**
   * Plans batches for {@code files}. A {@code diffBudgetTokens <= 0} disables budgeting — every
   * reviewable file lands in a single batch (the legacy "no cap" behaviour).
   */
  BudgetPlan plan(
      List<GitHubPullRequestClient.FileDiff> files, int diffBudgetTokens, int maxBatches) {
    var reviewable = formatter.reviewableFiles(files);
    if (reviewable.isEmpty()) {
      return new BudgetPlan(List.of(), List.of());
    }

    var sized = renderAndSize(reviewable, diffBudgetTokens);

    if (diffBudgetTokens <= 0) {
      return new BudgetPlan(List.of(singleBatch(sized)), List.of());
    }
    return pack(sized, diffBudgetTokens, Math.max(1, maxBatches));
  }

  /** A file rendered to its diff section with a token estimate (oversized files pre-clipped). */
  private record Sized(GitHubPullRequestClient.FileDiff file, String text, int tokens) {}

  private List<Sized> renderAndSize(
      List<GitHubPullRequestClient.FileDiff> reviewable, int diffBudgetTokens) {
    // Highest-impact first so that, when bins fill, the files left out by name are the smallest.
    var ordered = new ArrayList<>(reviewable);
    ordered.sort(
        Comparator.comparingInt(
                (GitHubPullRequestClient.FileDiff f) -> f.additions() + f.deletions())
            .reversed()
            .thenComparing(GitHubPullRequestClient.FileDiff::filename));

    Set<String> reviewableNames =
        reviewable.stream()
            .map(GitHubPullRequestClient.FileDiff::filename)
            .collect(Collectors.toSet());
    var sized = new ArrayList<Sized>(ordered.size());
    for (var file : ordered) {
      var section = formatter.formatFileSection(file, reviewableNames);
      var tokens = tokenCounter.estimateTokens(section);
      if (diffBudgetTokens > 0 && tokens > diffBudgetTokens) {
        section = clipToBudget(section, diffBudgetTokens);
        tokens = tokenCounter.estimateTokens(section);
      }
      sized.add(new Sized(file, section, tokens));
    }
    return sized;
  }

  private static DiffBatch singleBatch(List<Sized> sized) {
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

  private static BudgetPlan pack(List<Sized> sized, int diffBudgetTokens, int maxBatches) {
    var binSections = new ArrayList<List<Sized>>();
    var binTokens = new ArrayList<Integer>();
    var omitted = new ArrayList<String>();

    for (var s : sized) {
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
    for (var i = 0; i < binSections.size(); i++) {
      var text = new StringBuilder();
      var files = new ArrayList<GitHubPullRequestClient.FileDiff>();
      for (var s : binSections.get(i)) {
        text.append(s.text());
        files.add(s.file());
      }
      batches.add(new DiffBatch(text.toString(), files, binTokens.get(i)));
    }
    return new BudgetPlan(batches, omitted);
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
   */
  private String clipToBudget(String section, int budgetTokens) {
    var target = Math.max(1, (int) (budgetTokens * 0.9));
    var lines = ReviewDiffFormatter.lineCount(section);
    var clipped = section;
    for (var i = 0; i < 8 && tokenCounter.estimateTokens(clipped) > budgetTokens; i++) {
      var current = Math.max(1, tokenCounter.estimateTokens(clipped));
      var ratio = Math.min(0.95, (double) target / current);
      lines = Math.max(1, (int) (lines * ratio));
      clipped = ReviewDiffFormatter.truncateSection(section, lines);
    }
    // Safety net: a pathologically small budget (a few tokens) can leave even a one-line section
    // over budget. Fall back to a fixed placeholder so a batch never exceeds its budget; the file
    // is
    // still surfaced (clipped to a notice) rather than silently bloating the call.
    if (tokenCounter.estimateTokens(clipped) > budgetTokens) {
      Log.warnf(
          "File section exceeds the %d-token budget after clipping; using a notice", budgetTokens);
      return PLACEHOLDER_OVER_BUDGET;
    }
    return clipped;
  }
}
