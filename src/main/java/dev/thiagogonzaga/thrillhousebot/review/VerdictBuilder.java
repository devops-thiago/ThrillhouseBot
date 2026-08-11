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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link ReviewResult} verdict from the model response and the review's gating inputs,
 * and derives the check-run conclusion/title/summary. The decision core: a review approves only
 * when there are no outstanding new findings AND no unresolved previous findings (backstop), no
 * offending CI checks (when CI gating is {@link CiGatingMode#STRICT}), and the diff was not
 * truncated.
 */
@ApplicationScoped
public class VerdictBuilder {

  private final PrSummaryGenerator summaryGenerator;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final BotIdentity botIdentity;
  private final CiGatingMode ciGating;
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
        CiGatingMode.parse(config.review().ciGating()),
        BlockingStrictness.fromString(config.review().blockingStrictness())
            .orElse(BlockingStrictness.BALANCED));
  }

  /** Visible for tests; defaults to fail-closed CI gating and balanced blocking. */
  VerdictBuilder(
      PrSummaryGenerator summaryGenerator,
      FollowUpAnalyzer followUpAnalyzer,
      BotIdentity botIdentity) {
    this(
        summaryGenerator,
        followUpAnalyzer,
        botIdentity,
        CiGatingMode.STRICT,
        BlockingStrictness.BALANCED);
  }

  /** Visible for tests: pins CI gating; blocking stays {@link BlockingStrictness#BALANCED}. */
  VerdictBuilder(
      PrSummaryGenerator summaryGenerator,
      FollowUpAnalyzer followUpAnalyzer,
      BotIdentity botIdentity,
      CiGatingMode ciGating) {
    this(summaryGenerator, followUpAnalyzer, botIdentity, ciGating, BlockingStrictness.BALANCED);
  }

  /** Visible for tests: pins blocking mode; CI gating stays {@link CiGatingMode#STRICT}. */
  VerdictBuilder(
      PrSummaryGenerator summaryGenerator,
      FollowUpAnalyzer followUpAnalyzer,
      BotIdentity botIdentity,
      BlockingStrictness blockingStrictness) {
    this(summaryGenerator, followUpAnalyzer, botIdentity, CiGatingMode.STRICT, blockingStrictness);
  }

  VerdictBuilder(
      PrSummaryGenerator summaryGenerator,
      FollowUpAnalyzer followUpAnalyzer,
      BotIdentity botIdentity,
      CiGatingMode ciGating,
      BlockingStrictness blockingStrictness) {
    this.summaryGenerator = summaryGenerator;
    this.followUpAnalyzer = followUpAnalyzer;
    this.botIdentity = botIdentity;
    this.ciGating = ciGating == null ? CiGatingMode.STRICT : ciGating;
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
    // Budgeted: plan omitted + clipped files, with any file a failed batch left uncovered folded
    // into the omitted set (effective*); legacy: line-cap count — never sum both. Files skipped at
    // the token spend ceiling gate approval through the same omitted set but are pulled out into
    // their own class here, so the disclosure names the ceiling — a different cause with a
    // different fix — instead of blaming the diff budget (and never lists a file twice). Files
    // whose batch response was cut but salvaged (#500) are a further class: partially reviewed,
    // holding approval like the others, disclosed with the response cut as the reason — and a file
    // both clipped and response-cut is disclosed once, under the stronger (output-side) statement.
    var ceilingSkipped = plan.spendCeilingSkippedFiles();
    var responseCut = plan.responseCutFiles();
    var clipped = withoutNames(plan.effectiveClippedFiles(), responseCut);
    var truncation =
        plan.budgeted()
            ? new ReviewResult.TruncationDetail(
                withoutNames(plan.effectiveOmittedFiles(), ceilingSkipped),
                clipped,
                ceilingSkipped,
                responseCut,
                plan.summaryDegradation())
            : ReviewResult.TruncationDetail.EMPTY;
    var omitted =
        plan.budgeted()
            ? plan.effectiveOmittedFiles().size() + clipped.size() + responseCut.size()
            : ctx.omittedFiles();
    // GitHub PR-level totals when available; ignore-glob drops can undercount diff-derived stats.
    // Pure renames are excluded from reviewableFiles for AI budget (#386) but still belong in the
    // fallback file count / walkthrough when PR totals could not be fetched.
    var overviewFiles = overviewFiles(ctx);
    var diffStats =
        DiffStats.fromFiles(overviewFiles, omitted, truncation)
            .withAuthoritativeTotals(ctx.prTotals());
    // Rows are dropped for every never-reviewed class — planned/runtime omissions AND ceiling
    // skips (the detail keeps them separate only so the disclosure names the ceiling) — while
    // clipped and response-cut files keep theirs: those were partially reviewed.
    var omittedNames = new HashSet<>(truncation.omittedFileNames());
    omittedNames.addAll(truncation.spendCeilingSkippedFileNames());
    var changedFiles =
        toChangedFiles(
            overviewFiles.stream()
                .filter(f -> !ReviewDiffFormatter.namesContain(omittedNames, f.filename()))
                .toList());
    // A model-reported "unresolved" whose targeted code left the diff (force-push) becomes
    // "superseded" before the gates run, so a vanished finding never holds APPROVE (#336).
    // Skip the DiffLineResolver when there are no statuses — first reviews and empty-status
    // paths must not pay for a patch re-parse (#135).
    var rawStatuses = aiResponse.previousFindingsStatus();
    var currentRenameTargets = renameTargets(ctx.files());
    // Ids a newer round already closed must not be re-superseded — that phantom supersede would
    // pin hasSupersededPrevious on and re-post the summary every push (#470).
    var settledIds = FollowUpAnalyzer.settledPreviousIds(ctx.priorAiResponses());
    var effectiveStatuses =
        followUpAnalyzer.supersedeVanished(
            ctx.previousAiResponseJson(),
            rawStatuses,
            rawStatuses.isEmpty() ? null : ctx.lineResolver(),
            currentRenameTargets,
            settledIds);
    if (ctx.hasContext()) {
      effectiveStatuses =
          followUpAnalyzer.addUnreportedVanished(
              ctx.previousFindingsList(),
              effectiveStatuses,
              ctx.lineResolver(),
              currentRenameTargets,
              settledIds);
    }
    // A maintainer's decline is a claim, not ground truth: a "justified" whose stated reason the
    // reviewed code plainly contradicts goes back to "unresolved" for one more round (#169).
    effectiveStatuses =
        followUpAnalyzer.recheckDeclines(
            ctx.previousFindingsList(),
            effectiveStatuses,
            ctx.inlineComments(),
            botIdentity,
            () -> reviewedCode(ctx, plan));
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
                botIdentity,
                currentRenameTargets)
            : List.<ReviewResult.PreviousFindingStatus>of();
    return buildResult(
        effectiveResponse,
        ctx.isFirstVisibleReview(),
        diffStats,
        new SummaryInputs(
            changedFiles,
            ReviewDiffFormatter.formatPureRenameRollup(
                ReviewDiffFormatter.pureRenameFiles(ctx.files()))),
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
    var truncationSuffix = truncationSuffixFor(result);
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
    boolean approvedDespiteCi = result.reviewState() == ReviewState.APPROVE;
    String unreadableSuffix = "";
    if (result.ciUnreadable()) {
      unreadableSuffix =
          approvedDespiteCi
              ? " Note: the CI status for some checks could not be read."
              : " The CI status for some checks could not be read — holding approval until it can"
                  + " be confirmed.";
    }
    if (!result.offendingCiChecks().isEmpty()) {
      var checkLabel = result.requiredContextsKnown() ? "required CI check(s)" : "CI check(s)";
      if (approvedDespiteCi) {
        return String.format(
                "No new issues found. Note: %d %s are still pending or failing.",
                result.offendingCiChecks().size(), checkLabel)
            + unreadableSuffix
            + truncationSuffix;
      }
      return String.format(
              "No new issues found, but %d %s are still pending or failing.",
              result.offendingCiChecks().size(), checkLabel)
          + unreadableSuffix
          + truncationSuffix;
    }
    if (result.ciUnreadable()) {
      if (approvedDespiteCi) {
        return "No new issues found. Note: the CI status could not be read." + truncationSuffix;
      }
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
      return PrSummaryGenerator.ZERO_ISSUES_MESSAGE + truncationSuffix;
    }
    return ReviewResult.unresolvedPreviousMessage(unresolved) + truncationSuffix;
  }

  /**
   * The coverage suffix of the check-run summary: the partial-review brief when file coverage was
   * truncated (that brief already folds a summary cut or skip in as one of its counts), the
   * summary-only marker when just the summary response was cut — or skipped at the token spend
   * ceiling (#518) — and empty otherwise.
   */
  private static String truncationSuffixFor(ReviewResult result) {
    if (result.truncated()) {
      return " The diff was also too large to review in full ("
          + result.coverageGapBrief()
          + ") — partial review.";
    }
    return switch (result.truncation().summaryDegradation()) {
      case RESPONSE_CUT -> " The summary was shortened (response cut at the length cap).";
      case SKIPPED_AT_CEILING -> " The summary was skipped (token spend ceiling reached).";
      case NONE -> "";
    };
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
   * Reviewable files plus pure renames — the fallback set for Changes Overview counts when GitHub
   * PR totals are unavailable. Pure renames are omitted from AI input but still part of the PR.
   */
  static List<GitHubPullRequestClient.FileDiff> overviewFiles(
      ReviewContextLoader.ReviewContext ctx) {
    var pureRenames = ReviewDiffFormatter.pureRenameFiles(ctx.files());
    if (pureRenames.isEmpty()) {
      return ctx.reviewableFiles();
    }
    var merged =
        new ArrayList<GitHubPullRequestClient.FileDiff>(
            ctx.reviewableFiles().size() + pureRenames.size());
    merged.addAll(ctx.reviewableFiles());
    merged.addAll(pureRenames);
    return merged;
  }

  /**
   * The diff text the review call(s) actually saw — the only material a decline may be re-checked
   * against. With token budgeting on, {@code ctx.diff()} is empty and the planned batches are
   * authoritative for what the model received, so they are concatenated; with budgeting disabled
   * the legacy single diff is it. Resolved lazily by {@link FollowUpAnalyzer#recheckDeclines}, so a
   * round with no declined finding never pays for the concatenation.
   */
  private static String reviewedCode(
      ReviewContextLoader.ReviewContext ctx, DiffBudgetPlanner.BudgetPlan plan) {
    if (!plan.budgeted() || plan.batches().isEmpty()) {
      return ctx.diff();
    }
    var sb = new StringBuilder();
    for (var batch : plan.batches()) {
      sb.append(batch.text()).append('\n');
    }
    return sb.toString();
  }

  /** {@code names} minus {@code excluded}, preserving order — empty exclusion returns as-is. */
  private static List<String> withoutNames(List<String> names, List<String> excluded) {
    if (excluded.isEmpty()) {
      return names;
    }
    var excludedSet = Set.copyOf(excluded);
    return names.stream().filter(n -> !excludedSet.contains(n)).toList();
  }

  /**
   * Maps a prior path to its current rename target; blank means a content-identical pure rename.
   */
  static Map<String, String> renameTargets(List<GitHubPullRequestClient.FileDiff> files) {
    var targets = new HashMap<String, String>();
    for (var file : files) {
      if (file != null
          && "renamed".equalsIgnoreCase(file.status())
          && file.previousFilename() != null
          && !file.previousFilename().isBlank()) {
        targets.putIfAbsent(
            file.previousFilename(), ReviewDiffFormatter.isPureRename(file) ? "" : file.filename());
      }
    }
    return targets;
  }

  /**
   * Projects the reviewed diff onto the (path, change type, pure-rename) rows the summary
   * walkthrough renders. The pure-rename flag travels with the row so the walkthrough can say why
   * such a file carries no model summary instead of rendering a bare dash (#536).
   */
  static List<PrSummaryGenerator.ChangedFile> toChangedFiles(
      List<GitHubPullRequestClient.FileDiff> files) {
    return files.stream()
        .map(
            f ->
                new PrSummaryGenerator.ChangedFile(
                    f.filename(), f.status(), ReviewDiffFormatter.isPureRename(f)))
        .toList();
  }

  /** Inputs that only shape the summary walkthrough: the file rows and the pure-rename rollup. */
  private record SummaryInputs(
      List<PrSummaryGenerator.ChangedFile> changedFiles, String pureRenameRollup) {}

  ReviewResult buildResult(
      ReviewResponse aiResponse,
      boolean isFirstReview,
      DiffStats diffStats,
      List<PrSummaryGenerator.ChangedFile> changedFiles,
      List<Finding> unresolvedPrevious,
      CiStatusEvaluator.CiEvaluation ciEvaluation,
      List<ReviewResult.PreviousFindingStatus> backstopUnresolved) {
    return buildResult(
        aiResponse,
        isFirstReview,
        diffStats,
        new SummaryInputs(changedFiles, ""),
        unresolvedPrevious,
        ciEvaluation,
        backstopUnresolved);
  }

  private ReviewResult buildResult(
      ReviewResponse aiResponse,
      boolean isFirstReview,
      DiffStats diffStats,
      SummaryInputs summaryInputs,
      List<Finding> unresolvedPrevious,
      CiStatusEvaluator.CiEvaluation ciEvaluation,
      List<ReviewResult.PreviousFindingStatus> backstopUnresolved) {
    var changedFiles = summaryInputs.changedFiles();
    var pureRenameRollup = summaryInputs.pureRenameRollup();
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

    if (state == ReviewState.APPROVE
        && ciGating.holdsApproval()
        && (!offendingCiChecks.isEmpty() || ciUnreadable)) {
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
    if (pureRenameRollup != null && !pureRenameRollup.isBlank()) {
      summaryMarkdown =
          summaryMarkdown.replace(
              PrSummaryGenerator.SUMMARY_HEADING + "\n\n",
              PrSummaryGenerator.SUMMARY_HEADING
                  + "\n\n> **AI review scope:** "
                  + pureRenameRollup.strip()
                  + "\n\n");
    }
    if (diffStats.truncated()) {
      summaryMarkdown =
          ReviewResult.truncationNotice(diffStats.omittedFiles(), diffStats.truncation())
              + summaryMarkdown;
    } else {
      // Summary-only degradation (no file gap): the findings are complete, so the partial-review
      // banner (and the approval hold that goes with file gaps) would overstate it — a dedicated
      // banner names the cut, or the token spend ceiling (#518), without holding the verdict.
      summaryMarkdown =
          switch (diffStats.truncation().summaryDegradation()) {
            case RESPONSE_CUT -> ReviewResult.SUMMARY_CUT_NOTICE + summaryMarkdown;
            case SKIPPED_AT_CEILING -> ReviewResult.SUMMARY_SKIPPED_NOTICE + summaryMarkdown;
            case NONE -> summaryMarkdown;
          };
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
