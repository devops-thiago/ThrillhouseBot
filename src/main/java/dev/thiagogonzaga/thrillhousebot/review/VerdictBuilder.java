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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the {@link ReviewResult} verdict from the model response and the review's gating inputs,
 * and derives the check-run conclusion/title/summary. The decision core: a review approves only
 * when there are no outstanding new findings AND no unresolved previous findings (backstop), no
 * offending CI checks, and the diff was not truncated.
 */
@ApplicationScoped
public class VerdictBuilder {

  private final PrSummaryGenerator summaryGenerator;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final BotIdentity botIdentity;
  private final BlockingStrictness blockingStrictness;

  @Inject
  public VerdictBuilder(
      PrSummaryGenerator summaryGenerator,
      FollowUpAnalyzer followUpAnalyzer,
      BotIdentity botIdentity,
      ThrillhouseConfig config) {
    this(
        summaryGenerator,
        followUpAnalyzer,
        botIdentity,
        BlockingStrictness.fromString(config.review().blockingStrictness())
            .orElse(BlockingStrictness.BALANCED));
  }

  /** Visible for tests: pins the blocking mode without booting the CDI container. */
  VerdictBuilder(
      PrSummaryGenerator summaryGenerator,
      FollowUpAnalyzer followUpAnalyzer,
      BotIdentity botIdentity,
      BlockingStrictness blockingStrictness) {
    this.summaryGenerator = summaryGenerator;
    this.followUpAnalyzer = followUpAnalyzer;
    this.botIdentity = botIdentity;
    this.blockingStrictness =
        blockingStrictness == null ? BlockingStrictness.BALANCED : blockingStrictness;
  }

  /**
   * Builds the verdict from the loaded context and the refined model response: derives the diff
   * stats and changed-file rows, the unresolved previous findings, and the deterministic backstop
   * (the bot's own prior findings the model silently dropped but that are still present in this
   * diff — gated on {@code hasContext}, spanning every prior round), then delegates to {@link
   * #buildResult}. Keeps the APPROVE-gating guards in one place.
   */
  ReviewResult build(
      ReviewContextLoader.ReviewContext ctx,
      ReviewResponse aiResponse,
      CiStatusEvaluator.CiEvaluation ciEvaluation,
      DiffBudgetPlanner.BudgetPlan plan) {
    // Budgeted: plan omitted + clipped files; legacy: line-cap count — never sum both.
    var truncation =
        plan.budgeted()
            ? new ReviewResult.TruncationDetail(plan.omittedFiles(), plan.clippedFiles())
            : ReviewResult.TruncationDetail.EMPTY;
    var omitted =
        plan.budgeted()
            ? plan.omittedFiles().size() + plan.clippedFiles().size()
            : ctx.omittedFiles();
    // GitHub PR-level totals when available; ignore-glob drops can undercount diff-derived stats.
    var diffStats =
        DiffStats.fromFiles(ctx.reviewableFiles(), omitted, truncation)
            .withAuthoritativeTotals(ctx.prTotals());
    var omittedNames = Set.copyOf(truncation.omittedFileNames());
    var changedFiles =
        toChangedFiles(
            ctx.reviewableFiles().stream()
                .filter(f -> !omittedNames.contains(f.filename()))
                .toList());
    // A model-reported "unresolved" whose targeted code left the diff (force-push) becomes
    // "superseded" before the gates run, so a vanished finding never holds APPROVE (#336).
    // Skip the DiffLineResolver when there are no statuses — first reviews and empty-status
    // paths must not pay for a patch re-parse (#135).
    var rawStatuses = aiResponse.previousFindingsStatus();
    var effectiveStatuses =
        followUpAnalyzer.supersedeVanished(
            ctx.previousAiResponseJson(),
            rawStatuses,
            rawStatuses == null || rawStatuses.isEmpty() ? null : ctx.lineResolver());
    var effectiveResponse =
        new ReviewResponse(aiResponse.findings(), effectiveStatuses, aiResponse.summary());
    var unresolvedPrevious =
        followUpAnalyzer.unresolvedFindings(ctx.previousFindingsList(), effectiveStatuses);
    // Lazily resolve the shared DiffLineResolver only when the backstop runs — first reviews and
    // other no-context paths never pay for a patch re-parse here (FindingPipeline / postReview
    // still share the same memoized supplier when they need it).
    var backstopUnresolved =
        ctx.hasContext()
            ? followUpAnalyzer.unreportedUnresolvedStatusesFromParsed(
                ctx.priorAiResponses(),
                effectiveStatuses,
                ctx.inlineComments(),
                ctx.lineResolver(),
                botIdentity)
            : List.<ReviewResult.PreviousFindingStatus>of();
    return buildResult(
        effectiveResponse,
        ctx.isFirstVisibleReview(),
        diffStats,
        changedFiles,
        unresolvedPrevious,
        ciEvaluation,
        backstopUnresolved);
  }

  static String conclusionForResult(ReviewResult result) {
    return result.reviewState().checkRunConclusion();
  }

  static String checkTitleForResult(ReviewResult result) {
    if (result.reviewState() == ReviewState.APPROVE) {
      return CheckRunManager.CHECK_NAME + " ✅";
    }
    return CheckRunManager.CHECK_NAME;
  }

  static String checkSummaryForResult(ReviewResult result) {
    var truncationSuffix =
        result.truncated()
            ? " The diff was also too large to review in full ("
                + result.coverageGapBrief()
                + ") — partial review."
            : "";
    if (result.hasIssues()) {
      return String.format(
              "%d findings: %d critical, %d high, %d medium, %d low",
              result.totalFindings(),
              result.criticalCount(),
              result.highCount(),
              result.mediumCount(),
              result.lowCount())
          + truncationSuffix;
    }
    var unreadableSuffix =
        result.ciUnreadable()
            ? " The CI status for some checks could not be read — holding approval until it can be"
                + " confirmed."
            : "";
    if (!result.offendingCiChecks().isEmpty()) {
      var checkLabel = result.requiredContextsKnown() ? "required CI check(s)" : "CI check(s)";
      return String.format(
              "No new issues found, but %d %s are still pending or failing.",
              result.offendingCiChecks().size(), checkLabel)
          + unreadableSuffix
          + truncationSuffix;
    }
    if (result.ciUnreadable()) {
      return "No new issues found, but the CI status could not be read — holding approval until it"
          + " can be confirmed."
          + truncationSuffix;
    }
    if (result.truncated()) {
      return "No new issues found, but the diff was too large to review in full ("
          + result.coverageGapBrief()
          + ") — this is a partial review, so approval is held.";
    }
    var unresolved = result.unresolvedPreviousCount();
    if (unresolved == 0) {
      return PrSummaryGenerator.ZERO_ISSUES_MESSAGE;
    }
    return ReviewResult.unresolvedPreviousMessage(unresolved);
  }

  record DiffStats(
      int filesChanged,
      int additions,
      int deletions,
      int omittedFiles,
      ReviewResult.TruncationDetail truncation) {
    DiffStats {
      truncation = truncation == null ? ReviewResult.TruncationDetail.EMPTY : truncation;
    }

    DiffStats(int filesChanged, int additions, int deletions) {
      this(filesChanged, additions, deletions, 0);
    }

    DiffStats(int filesChanged, int additions, int deletions, int omittedFiles) {
      this(filesChanged, additions, deletions, omittedFiles, ReviewResult.TruncationDetail.EMPTY);
    }

    /** True when the line budget dropped whole files, so the model never saw part of the change. */
    boolean truncated() {
      return omittedFiles > 0;
    }

    /**
     * Replaces the file/line counts with GitHub's authoritative PR-level totals, keeping the
     * reviewed diff's omitted-file count. Returns {@code this} unchanged when {@code totals} is
     * {@code null} (totals couldn't be fetched), so the summary falls back to the diff-derived
     * counts. Only the overview counts change; {@link #truncated()} and {@link #omittedFiles()} —
     * which gate approval and drive the truncation disclosure — still reflect the reviewed diff.
     */
    DiffStats withAuthoritativeTotals(ReviewContextLoader.PrTotals totals) {
      if (totals == null) {
        return this;
      }
      return new DiffStats(
          totals.filesChanged(), totals.additions(), totals.deletions(), omittedFiles, truncation);
    }

    static DiffStats fromFiles(List<GitHubPullRequestClient.FileDiff> files, int omittedFiles) {
      return fromFiles(files, omittedFiles, ReviewResult.TruncationDetail.EMPTY);
    }

    static DiffStats fromFiles(
        List<GitHubPullRequestClient.FileDiff> files,
        int omittedFiles,
        ReviewResult.TruncationDetail truncation) {
      var additions = 0;
      var deletions = 0;
      for (var file : files) {
        additions += file.additions();
        deletions += file.deletions();
      }
      return new DiffStats(files.size(), additions, deletions, omittedFiles, truncation);
    }
  }

  /**
   * Projects the reviewed diff onto the (path, change type) rows the summary walkthrough renders.
   */
  static List<PrSummaryGenerator.ChangedFile> toChangedFiles(
      List<GitHubPullRequestClient.FileDiff> files) {
    return files.stream()
        .map(f -> new PrSummaryGenerator.ChangedFile(f.filename(), f.status()))
        .toList();
  }

  ReviewResult buildResult(
      ReviewResponse aiResponse,
      boolean isFirstReview,
      DiffStats diffStats,
      List<PrSummaryGenerator.ChangedFile> changedFiles,
      List<Finding> unresolvedPrevious,
      CiStatusEvaluator.CiEvaluation ciEvaluation,
      List<ReviewResult.PreviousFindingStatus> backstopUnresolved) {
    var offendingCiChecks = ciEvaluation.offendingChecks();
    var ciUnreadable = ciEvaluation.unreadable();
    var requiredContextsKnown = ciEvaluation.requiredContextsKnown();
    var tally = tallyFindings(aiResponse);

    var outstanding = new ArrayList<Finding>(tally.findings());
    outstanding.addAll(unresolvedPrevious);
    ReviewState state = ReviewState.fromFindings(outstanding, blockingStrictness);
    // Backstop statuses reach the gate but never `outstanding`, keeping the hold downgrade-only
    // (APPROVE → COMMENT, never REQUEST_CHANGES). Keep the toStatuses list as-is on the common
    // no-backstop path — no ArrayList wrap/copy when there is nothing to append.
    var previousStatuses =
        mergePreviousStatuses(
            followUpAnalyzer.toStatuses(aiResponse.previousFindingsStatus()), backstopUnresolved);
    if (state == ReviewState.APPROVE && followUpAnalyzer.hasUnresolved(previousStatuses)) {
      state = ReviewState.COMMENT;
    }

    if (state == ReviewState.APPROVE && (!offendingCiChecks.isEmpty() || ciUnreadable)) {
      state = ReviewState.COMMENT;
    }

    if (state == ReviewState.APPROVE && diffStats.truncated()) {
      state = ReviewState.COMMENT;
    }

    var summaryMarkdown =
        summaryGenerator.generate(
            diffStats.filesChanged(),
            diffStats.additions(),
            diffStats.deletions(),
            changedFiles,
            aiResponse.summary(),
            new ReviewResult(
                tally.findings(),
                tally.critical(),
                tally.high(),
                tally.medium(),
                tally.low(),
                tally.highest(),
                state,
                isFirstReview,
                "",
                previousStatuses,
                offendingCiChecks,
                diffStats.omittedFiles(),
                ciUnreadable,
                requiredContextsKnown,
                diffStats.truncation()));
    if (diffStats.truncated()) {
      summaryMarkdown =
          ReviewResult.truncationNotice(diffStats.omittedFiles(), diffStats.truncation())
              + summaryMarkdown;
    }

    return new ReviewResult(
        tally.findings(),
        tally.critical(),
        tally.high(),
        tally.medium(),
        tally.low(),
        tally.highest(),
        state,
        isFirstReview,
        summaryMarkdown,
        previousStatuses,
        offendingCiChecks,
        diffStats.omittedFiles(),
        ciUnreadable,
        requiredContextsKnown,
        diffStats.truncation());
  }

  /**
   * Merges model-reported previous-finding statuses with the deterministic backstop. Returns {@code
   * modelStatuses} unchanged when the backstop is empty so the common path does not allocate a
   * fresh {@link ArrayList}.
   */
  static List<ReviewResult.PreviousFindingStatus> mergePreviousStatuses(
      List<ReviewResult.PreviousFindingStatus> modelStatuses,
      List<ReviewResult.PreviousFindingStatus> backstopUnresolved) {
    if (backstopUnresolved == null || backstopUnresolved.isEmpty()) {
      return modelStatuses;
    }
    var merged = new ArrayList<>(modelStatuses);
    merged.addAll(backstopUnresolved);
    return merged;
  }

  /** Findings parsed from the model response, with per-severity counts and the highest risk. */
  private record FindingTally(
      List<Finding> findings, int critical, int high, int medium, int low, RiskLevel highest) {}

  private static FindingTally tallyFindings(ReviewResponse aiResponse) {
    var findings = new ArrayList<Finding>();
    var critical = 0;
    var high = 0;
    var medium = 0;
    var low = 0;
    RiskLevel highest = null;
    // RiskLevel has exactly four values; the catch-all counts LOW and avoids an unreachable
    // extra branch that coverage would report as unhit.
    for (var ai : aiResponse.findings()) {
      Finding f = Finding.fromAiResponse(ai);
      findings.add(f);
      switch (f.risk()) {
        case CRITICAL -> critical++;
        case HIGH -> high++;
        case MEDIUM -> medium++;
        default -> low++;
      }
      if (highest == null || f.risk().compareTo(highest) < 0) {
        highest = f.risk();
      }
    }
    return new FindingTally(findings, critical, high, medium, low, highest);
  }
}
