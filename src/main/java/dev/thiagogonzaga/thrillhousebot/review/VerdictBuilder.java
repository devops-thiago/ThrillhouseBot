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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@link ReviewResult} verdict from the model response and the review's gating inputs,
 * and derives the check-run conclusion/title/summary. Extracted from {@code ReviewOrchestrator}
 * (#250) as the decision core: the APPROVE-gating guards move here <em>verbatim</em> — a review
 * approves only when there are no outstanding new findings AND no unresolved previous findings
 * (#118/#130/#143 backstop), no offending CI checks (#217), and the diff was not truncated (#234).
 */
@ApplicationScoped
public class VerdictBuilder {

  private final PrSummaryGenerator summaryGenerator;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final BotIdentity botIdentity;

  @Inject
  public VerdictBuilder(
      PrSummaryGenerator summaryGenerator,
      FollowUpAnalyzer followUpAnalyzer,
      BotIdentity botIdentity) {
    this.summaryGenerator = summaryGenerator;
    this.followUpAnalyzer = followUpAnalyzer;
    this.botIdentity = botIdentity;
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
      List<ReviewResult.CiCheck> offendingCiChecks) {
    return build(ctx, aiResponse, offendingCiChecks, false);
  }

  ReviewResult build(
      ReviewContextLoader.ReviewContext ctx,
      ReviewResponse aiResponse,
      List<ReviewResult.CiCheck> offendingCiChecks,
      boolean ciUnreadable) {
    var diffStats = DiffStats.fromFiles(ctx.reviewableFiles(), ctx.omittedFiles());
    var changedFiles = toChangedFiles(ctx.reviewableFiles());
    var unresolvedPrevious =
        followUpAnalyzer.unresolvedFindings(
            ctx.previousAiResponseJson(), aiResponse.previousFindingsStatus());
    var backstopUnresolved =
        ctx.hasContext()
            ? followUpAnalyzer.unreportedUnresolvedStatuses(
                ctx.priorAiResponseJsons(),
                aiResponse.previousFindingsStatus(),
                ctx.inlineComments(),
                ctx.lineResolver(),
                botIdentity)
            : List.<ReviewResult.PreviousFindingStatus>of();
    return buildResult(
        aiResponse,
        ctx.isFirstVisibleReview(),
        diffStats,
        changedFiles,
        unresolvedPrevious,
        offendingCiChecks,
        ciUnreadable,
        backstopUnresolved);
  }

  static String conclusionForResult(ReviewResult result) {
    return result.reviewState().checkRunConclusion();
  }

  static String checkTitleForResult(ReviewResult result) {
    // Derive the ✅ from the single verdict gate, not a parallel predicate: reviewState() is APPROVE
    // only when nothing holds the review back — no findings, no unresolved previous findings, no
    // offending CI, AND the diff was not truncated (#234). Re-deriving the "all clear" condition by
    // hand here used to omit the truncation guard, so a truncated-but-clean PR showed ✅ over a
    // neutral (held) conclusion.
    if (result.reviewState() == ReviewState.APPROVE) {
      return CheckRunManager.CHECK_NAME + " ✅";
    }
    return CheckRunManager.CHECK_NAME;
  }

  static String checkSummaryForResult(ReviewResult result) {
    if (result.hasIssues()) {
      return String.format(
          "%d findings: %d critical, %d high, %d medium, %d low",
          result.totalFindings(),
          result.criticalCount(),
          result.highCount(),
          result.mediumCount(),
          result.lowCount());
    }
    // No new findings — surface CI gating first, since it also holds approval back.
    if (!result.offendingCiChecks().isEmpty()) {
      return String.format(
          "No new issues found, but %d required CI check(s) are still pending or failing.",
          result.offendingCiChecks().size());
    }
    if (result.ciUnreadable()) {
      return "No new issues found, but the CI status could not be read — holding approval until it"
          + " can be confirmed.";
    }
    var unresolved = result.unresolvedPreviousCount();
    if (unresolved == 0) {
      return PrSummaryGenerator.ZERO_ISSUES_MESSAGE;
    }
    return ReviewResult.unresolvedPreviousMessage(unresolved);
  }

  record DiffStats(int filesChanged, int additions, int deletions, int omittedFiles) {
    DiffStats(int filesChanged, int additions, int deletions) {
      this(filesChanged, additions, deletions, 0);
    }

    /** True when the line budget dropped whole files, so the model never saw part of the change. */
    boolean truncated() {
      return omittedFiles > 0;
    }

    static DiffStats fromFiles(List<GitHubPullRequestClient.FileDiff> files, int omittedFiles) {
      var additions = 0;
      var deletions = 0;
      for (var file : files) {
        additions += file.additions();
        deletions += file.deletions();
      }
      return new DiffStats(files.size(), additions, deletions, omittedFiles);
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
      List<ReviewResult.CiCheck> offendingCiChecks,
      boolean ciUnreadable,
      List<ReviewResult.PreviousFindingStatus> backstopUnresolved) {
    var tally = tallyFindings(aiResponse);

    // The review may only approve when nothing is outstanding: new findings AND previous
    // findings still unresolved (not fixed, no accepted justification) both count
    var outstanding = new ArrayList<Finding>(tally.findings());
    outstanding.addAll(unresolvedPrevious);
    ReviewState state = ReviewState.fromFindings(outstanding);
    // Merge the model's previous-findings statuses with the backstop's reconstructed unresolved
    // ones: the silently dropped findings then flow through the same gate, counts, and
    // messages as any unresolved status, so no separate path is needed. Backstop statuses reach the
    // gate but never `outstanding`, keeping the hold downgrade-only (APPROVE → COMMENT, never
    // REQUEST_CHANGES).
    var previousStatuses =
        new ArrayList<ReviewResult.PreviousFindingStatus>(
            followUpAnalyzer.toStatuses(aiResponse.previousFindingsStatus()));
    previousStatuses.addAll(backstopUnresolved);
    // Unresolved statuses that could not be mapped back to stored findings (e.g. pre-persistence
    // sessions), or that the model dropped but the backstop reconstructed, must still hold approval
    if (state == ReviewState.APPROVE && followUpAnalyzer.hasUnresolved(previousStatuses)) {
      state = ReviewState.COMMENT;
    }

    // CI holds approval back when a required check is offending OR a CI source could not be read at
    // all — an unread source means we cannot confirm CI is green, so we fail closed (#253/#5).
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
                0,
                ciUnreadable));
    if (diffStats.truncated()) {
      summaryMarkdown = ReviewResult.truncationNotice(diffStats.omittedFiles()) + summaryMarkdown;
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
        ciUnreadable);
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
    // ReviewResponse never returns null findings, so we iterate directly. The lowest risk level is
    // counted by the catch-all branch — RiskLevel has exactly four values — which avoids the
    // unreachable extra branch the compiler would otherwise generate and report as uncovered.
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

  ReviewResult buildResult(
      ReviewResponse aiResponse,
      boolean isFirstReview,
      DiffStats diffStats,
      List<Finding> unresolvedPrevious,
      List<ReviewResult.CiCheck> offendingCiChecks) {
    return buildResult(
        aiResponse,
        isFirstReview,
        diffStats,
        List.of(),
        unresolvedPrevious,
        offendingCiChecks,
        false,
        List.of());
  }

  ReviewResult buildResult(
      ReviewResponse aiResponse,
      boolean isFirstReview,
      DiffStats diffStats,
      List<Finding> unresolvedPrevious) {
    return buildResult(aiResponse, isFirstReview, diffStats, unresolvedPrevious, List.of());
  }
}
