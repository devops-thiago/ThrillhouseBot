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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * The post-AI finding chain: validate quotes, dedupe, verify against the diff, drop already-replied
 * duplicates, backfill missing content anchors, and persist the response. Extracted from {@code
 * ReviewOrchestrator}; the ordering is preserved verbatim — quote validation runs before dedupe so
 * a merged finding cannot inherit a phantom quote while a verbatim sibling is discarded.
 */
@ApplicationScoped
public class FindingPipeline {

  private record BatchOutcome(
      int index,
      List<ReviewResponse.Finding> findings,
      List<ReviewResponse.PreviousFindingStatus> statuses) {}

  private final AiReviewService aiReviewService;
  private final FindingQuoteValidator quoteValidator;
  private final FindingDeduplicator deduplicator;
  private final FindingVerificationService findingVerificationService;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final ObjectMapper mapper;
  private final BotIdentity botIdentity;
  private final DiffBudgetPlanner budgetPlanner;
  private final TokenCounter tokenCounter;

  @Inject
  public FindingPipeline(
      AiReviewService aiReviewService,
      FindingQuoteValidator quoteValidator,
      FindingDeduplicator deduplicator,
      FindingVerificationService findingVerificationService,
      FollowUpAnalyzer followUpAnalyzer,
      ObjectMapper mapper,
      BotIdentity botIdentity,
      DiffBudgetPlanner budgetPlanner,
      TokenCounter tokenCounter) {
    this.aiReviewService = aiReviewService;
    this.quoteValidator = quoteValidator;
    this.deduplicator = deduplicator;
    this.findingVerificationService = findingVerificationService;
    this.followUpAnalyzer = followUpAnalyzer;
    this.mapper = mapper;
    this.botIdentity = botIdentity;
    this.budgetPlanner = budgetPlanner;
    this.tokenCounter = tokenCounter;
  }

  /**
   * Calls the model on the assembled prompt, then runs the raw response through the full post-AI
   * chain ({@link #refine}). The {@code lineResolver} is shared with the verdict backstop, so the
   * caller builds it once and passes it in. A budgeted plan is authoritative for what the model
   * sees: even a single batch sends the planned (possibly hunk-clipped) text, never the uncapped
   * raw diff — otherwise the budget would be bypassed in exactly the oversized-file case that
   * motivated clipping. The legacy uncapped {@code ctx.diff()} is only sent when budgeting is
   * explicitly disabled.
   */
  ReviewResponse run(
      ReviewSession session,
      AiReviewService.PromptInputs promptInputs,
      ReviewContextLoader.ReviewContext ctx,
      DiffBudgetPlanner.BudgetPlan plan,
      DiffLineResolver lineResolver) {
    if (plan.multiCall()) {
      return runMultiCall(session, promptInputs, ctx, plan, lineResolver);
    }
    if (plan.budgeted() && plan.batches().isEmpty() && !plan.omittedFiles().isEmpty()) {
      return summarizeWithoutReview(session, promptInputs, ctx, plan);
    }
    var singleInputs = promptInputs;
    var quoteSource = ctx.diff();
    DiffBudgetPlanner.DiffBatch budgetedBatch = null;
    if (plan.budgeted() && !plan.batches().isEmpty()) {
      budgetedBatch = plan.batches().get(0);
      // The base comparison stays: the planner counted it in the shared overhead.
      singleInputs =
          withDiff(
              promptInputs,
              PromptTemplateEscaper.fence(budgetedBatch.text()),
              promptInputs.baseComparison());
      quoteSource = budgetedBatch.text();
    }
    var aiResponse = aiReviewService.review(session, singleInputs);
    if (budgetedBatch != null && !aiResponse.previousFindingsStatus().isEmpty()) {
      // Same scoping as the multi-call path: the single budgeted batch may carry clipped files
      // (and, at maxBatches=1, omit others entirely), so a "resolved" claim is only trusted for a
      // prior finding whose file the call provably saw in full.
      var scoped =
          scopeStatusesToBatch(
              aiResponse.previousFindingsStatus(),
              budgetedBatch,
              plan,
              followUpAnalyzer.previousFindingFilesById(ctx.previousAiResponseJson()));
      aiResponse = new ReviewResponse(aiResponse.findings(), scoped, aiResponse.summary());
    }
    return refine(
        session,
        aiResponse,
        quoteSource,
        singleInputs,
        ctx.priorAiResponseJsons(),
        ctx.inlineComments(),
        lineResolver);
  }

  /**
   * Map-reduce review for a large PR: review each token-budgeted batch in parallel (blocking AI
   * calls on virtual threads), quote-validating and verifying each batch's findings against that
   * batch's own in-budget text (the combined diff would exceed the very budget the batches exist to
   * respect), union the results through the finishing chain, then a single summary call rolls them
   * up into the PR-level summary. A batch that fails after {@link AiReviewService}'s internal
   * retries is retried once more synchronously after the parallel pass — other batches keep their
   * results instead of being discarded. Previous-findings statuses are aggregated from the batch
   * calls — they saw the diff; the code-blind summary call must not decide what was resolved. The
   * passed {@code promptInputs} is reused as the shared-context template — only the diff slot
   * changes per batch, and the base comparison is dropped (it is a near-duplicate of the diff that
   * would otherwise be re-sent in every call).
   */
  private ReviewResponse runMultiCall(
      ReviewSession session,
      AiReviewService.PromptInputs promptInputs,
      ReviewContextLoader.ReviewContext ctx,
      DiffBudgetPlanner.BudgetPlan plan,
      DiffLineResolver lineResolver) {
    var batches = plan.batches();
    // The id space of previous_findings_status entries maps 1-based onto the prior response's
    // findings; a batch may only close a prior finding whose file its own diff slice contained.
    var previousFilesById = followUpAnalyzer.previousFindingFilesById(ctx.previousAiResponseJson());

    var outcomesByIndex = new BatchOutcome[batches.size()];
    var failedIndices = new ArrayList<Integer>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          IntStream.range(0, batches.size())
              .mapToObj(
                  i ->
                      CompletableFuture.supplyAsync(
                          () ->
                              processBatch(
                                  i, batches, session, promptInputs, plan, previousFilesById),
                          executor))
              .toList();
      for (int i = 0; i < futures.size(); i++) {
        try {
          outcomesByIndex[i] = futures.get(i).join();
        } catch (CompletionException e) {
          failedIndices.add(i);
          var cause = e.getCause() != null ? e.getCause() : e;
          Log.warnf(
              cause,
              "Batch %d/%d failed in the parallel pass; will retry after the other batches finish",
              i + 1,
              batches.size());
        }
      }
    }

    for (int index : failedIndices) {
      try {
        outcomesByIndex[index] =
            processBatch(index, batches, session, promptInputs, plan, previousFilesById);
        Log.infof("Batch %d/%d succeeded on retry", index + 1, batches.size());
      } catch (RuntimeException e) {
        throw new IllegalStateException("Parallel batch review failed", e);
      }
    }

    var outcomes =
        IntStream.range(0, batches.size())
            .mapToObj(i -> outcomesByIndex[i])
            .sorted(Comparator.comparingInt(BatchOutcome::index))
            .toList();

    var allFindings = new ArrayList<ReviewResponse.Finding>();
    var batchStatuses = new ArrayList<List<ReviewResponse.PreviousFindingStatus>>();
    for (var outcome : outcomes) {
      allFindings.addAll(outcome.findings());
      batchStatuses.add(outcome.statuses());
    }

    var aggregated = new ReviewResponse(allFindings, mergeBatchStatuses(batchStatuses), null);
    var refined = deduplicator.dedupe(aggregated);
    refined =
        followUpAnalyzer.dropRepliedDuplicates(
            refined, ctx.priorAiResponseJsons(), ctx.inlineComments(), botIdentity);
    refined = populateMissingAnchors(refined, lineResolver);

    var overview = clampOverview(changedFilesOverview(ctx, plan), promptInputs);
    var summaryInputs =
        new AiReviewService.SummaryInputs(
            promptInputs.prContext(),
            PromptTemplateEscaper.escape(
                budgetedFindingsJson(refined.findings(), promptInputs, overview)),
            PromptTemplateEscaper.escape(overview),
            promptInputs.previousFindings(),
            promptInputs.repoInstructions());
    var summaryResponse = aiReviewService.summarize(session, summaryInputs);

    var merged =
        new ReviewResponse(
            refined.findings(), refined.previousFindingsStatus(), summaryResponse.summary());
    persistAiResponse(session, merged);
    return merged;
  }

  private BatchOutcome processBatch(
      int index,
      List<DiffBudgetPlanner.DiffBatch> batches,
      ReviewSession session,
      AiReviewService.PromptInputs promptInputs,
      DiffBudgetPlanner.BudgetPlan plan,
      Map<Integer, String> previousFilesById) {
    var batch = batches.get(index);
    var batchInputs = withDiff(promptInputs, PromptTemplateEscaper.fence(batch.text()), "");
    var batchResponse =
        aiReviewService.reviewBatch(session, batchInputs, index + 1, batches.size());
    var validated = quoteValidator.validate(batchResponse, batch.text());
    var verified =
        findingVerificationService.verify(
            validated,
            batchInputs.diff(),
            promptInputs.projectStack(),
            promptInputs.previousFindings());
    return new BatchOutcome(
        index,
        verified.findings(),
        scopeStatusesToBatch(
            batchResponse.previousFindingsStatus(), batch, plan, previousFilesById));
  }

  /**
   * Unwraps a failed parallel batch future as {@link IllegalStateException} (preserving the cause).
   * SpotBugs flags rethrowing a bare {@link RuntimeException}; callers still see {@link
   * AiReviewException} via {@link Throwable#getCause()}.
   */
  static IllegalStateException unwrapParallelFailure(CompletionException e) {
    var cause = e.getCause() != null ? e.getCause() : e;
    return new IllegalStateException("Parallel batch review failed", cause);
  }

  /**
   * Degenerate budgeted plan: every reviewable file overflowed the budget, so no review call can
   * carry any diff within it. Sending the uncapped raw diff instead would bypass the budget on
   * exactly the PR it was meant to bound — skip the review call, keep the summary call (it never
   * carries the diff) so the PR still gets its overview, and leave the previous-findings statuses
   * empty: no call saw the diff, so nothing may be marked resolved. The plan's omissions hold
   * APPROVE and disclose the partial review.
   */
  private ReviewResponse summarizeWithoutReview(
      ReviewSession session,
      AiReviewService.PromptInputs promptInputs,
      ReviewContextLoader.ReviewContext ctx,
      DiffBudgetPlanner.BudgetPlan plan) {
    Log.warnf(
        "No reviewable file fits the per-call token budget (%d omitted); skipping the review call",
        plan.omittedFiles().size());
    var summaryInputs =
        new AiReviewService.SummaryInputs(
            promptInputs.prContext(),
            "[]",
            PromptTemplateEscaper.escape(
                clampOverview(changedFilesOverview(ctx, plan), promptInputs)),
            promptInputs.previousFindings(),
            promptInputs.repoInstructions());
    var summaryResponse = aiReviewService.summarize(session, summaryInputs);
    var merged = new ReviewResponse(List.of(), List.of(), summaryResponse.summary());
    persistAiResponse(session, merged);
    return merged;
  }

  /**
   * Keeps a batch's "resolved"/"justified" claims only for prior findings whose file was provably
   * in that batch's diff slice <em>in full</em> — a batch that never saw the fix has no evidence to
   * close the finding, and its claim must not outrank an informed "unresolved". Hunk-clipped files
   * are excluded from the provably-seen set: the batch carried only their leading hunks, and the
   * fix could live in the unseen tail. A status whose prior finding cannot be mapped to a file is
   * demoted too: with no mapping, no batch can prove it saw the finding, and letting the claim
   * through would bypass the scoping entirely (e.g. when the prior context came from the
   * unstructured review-body fallback). "unresolved" always passes — it is the no-evidence default.
   */
  private static List<ReviewResponse.PreviousFindingStatus> scopeStatusesToBatch(
      List<ReviewResponse.PreviousFindingStatus> statuses,
      DiffBudgetPlanner.DiffBatch batch,
      DiffBudgetPlanner.BudgetPlan plan,
      Map<Integer, String> previousFilesById) {
    if (statuses.isEmpty()) {
      return statuses;
    }
    var batchFiles = new HashSet<String>();
    for (var file : batch.files()) {
      batchFiles.add(file.filename());
    }
    plan.clippedFiles().forEach(batchFiles::remove);
    var scoped = new ArrayList<ReviewResponse.PreviousFindingStatus>(statuses.size());
    for (var status : statuses) {
      var closing = statusRank(status.status()) > 0;
      var file = previousFilesById.get(status.id());
      if (closing && (file == null || !batchFiles.contains(file))) {
        scoped.add(
            new ReviewResponse.PreviousFindingStatus(
                status.id(), "unresolved", "finding's file not fully in this batch's diff slice"));
        continue;
      }
      scoped.add(status);
    }
    return scoped;
  }

  /**
   * Bounds the changed-files overview so the summary prompt's fixed sections can never alone exceed
   * the per-call budget — a thousands-of-files PR would otherwise blow it on file names before a
   * single finding is serialized. The overview gets at most half of what remains after the
   * templates and inherited sections (which fit every batch call by construction), leaving the
   * other half for the findings JSON; dropped rows are rolled up by count.
   */
  private String clampOverview(String overview, AiReviewService.PromptInputs promptInputs) {
    var budget = budgetPlanner.perCallInputBudget();
    if (budget == Integer.MAX_VALUE) {
      return overview;
    }
    var inherited =
        PrReviewPrompts.SUMMARY_SYSTEM
            + PrReviewPrompts.SUMMARY_USER
            + promptInputs.prContext()
            + promptInputs.previousFindings()
            + promptInputs.repoInstructions();
    var overviewBudget = (budget - tokenCounter.estimateTokens(inherited)) / 2;
    if (overviewBudget <= 0) {
      return "(changed-files overview withheld — summary input budget exhausted)\n";
    }
    var lines = overview.split("\n");
    // Rollup-note tokens are reserved up front so post-truncation append cannot exceed the share.
    var noteReserve = tokenCounter.estimateTokens(overviewRollupNote(lines.length));
    var sb = new StringBuilder();
    var used = 0;
    var listed = 0;
    while (listed < lines.length) {
      var lineTokens = tokenCounter.estimateTokens(lines[listed] + "\n");
      if (used + lineTokens > overviewBudget - noteReserve) {
        break;
      }
      sb.append(lines[listed]).append('\n');
      used += lineTokens;
      listed++;
    }
    if (listed == lines.length) {
      return overview;
    }
    sb.append(overviewRollupNote(lines.length - listed));
    return sb.toString();
  }

  private static String overviewRollupNote(int notListed) {
    return "(+"
        + notListed
        + " more changed files — overview truncated to fit the summary"
        + " budget)\n";
  }

  /**
   * Serializes the findings for the summary call within the same per-call input budget the batch
   * calls respect — the aggregated JSON of a many-batch PR can otherwise exceed the model context
   * exactly when the expensive batch work already completed. Findings are kept most-severe-first;
   * dropped ones only shrink the summary prose (the verdict's counts derive from the full list).
   */
  private String budgetedFindingsJson(
      List<ReviewResponse.Finding> findings,
      AiReviewService.PromptInputs promptInputs,
      String changedFilesOverview) {
    var budget = budgetPlanner.perCallInputBudget();
    if (budget == Integer.MAX_VALUE) {
      return withTrueTotalsIfIncomplete(findingsJson(findings), findings, findings.size());
    }
    var fixedSections =
        PrReviewPrompts.SUMMARY_SYSTEM
            + PrReviewPrompts.SUMMARY_USER
            + promptInputs.prContext()
            + changedFilesOverview
            + promptInputs.previousFindings()
            + promptInputs.repoInstructions();
    // trueTotalsNote tokens are reserved up front so post-clamp append cannot exceed the budget.
    var noteReserve =
        findings.isEmpty()
            ? 0
            : tokenCounter.estimateTokens("\n" + trueTotalsNote(findings, findings.size()));
    var available = budget - tokenCounter.estimateTokens(fixedSections) - noteReserve;
    var kept = new ArrayList<>(findings);
    kept.sort(Comparator.comparingInt(f -> -statusRankForSeverity(f.risk())));
    var json = findingsJson(kept);
    while (!kept.isEmpty() && tokenCounter.estimateTokens(json) > available) {
      kept.remove(kept.size() - 1);
      json = findingsJson(kept);
    }
    if (kept.size() < findings.size()) {
      Log.warnf(
          "Summary call input over budget: serializing %d of %d findings (most severe kept)",
          kept.size(), findings.size());
    }
    return withTrueTotalsIfIncomplete(json, findings, kept.size());
  }

  /**
   * Appends the true-totals note whenever the serialized array shows fewer findings than exist —
   * whether the clamp dropped them or serialization degraded to "[]" ({@link #findingsJson}'s
   * failure fallback). The shown count is derived from the JSON itself, so a degraded "[]" after a
   * clamp still reports every finding as not shown rather than only the clamped ones. The summary
   * model then describes the full review; the verdict and posted findings always use the full list.
   */
  private static String withTrueTotalsIfIncomplete(
      String json, List<ReviewResponse.Finding> findings, int kept) {
    var shown = json.startsWith("[]") ? 0 : kept;
    if (shown >= findings.size()) {
      return json;
    }
    return json + "\n" + trueTotalsNote(findings, findings.size() - shown);
  }

  static String trueTotalsNote(List<ReviewResponse.Finding> findings, int notShown) {
    var critical = 0;
    var high = 0;
    var medium = 0;
    var low = 0;
    for (var finding : findings) {
      switch (statusRankForSeverity(finding.risk())) {
        case 3 -> critical++;
        case 2 -> high++;
        case 1 -> medium++;
        default -> low++;
      }
    }
    return String.format(
        "(+%d more findings not shown — base the summary counts on the true totals:"
            + " %d total, %d critical, %d high, %d medium, %d low)",
        notShown, findings.size(), critical, high, medium, low);
  }

  private static int statusRankForSeverity(String risk) {
    return switch (risk == null ? "" : risk) {
      case "critical" -> 3;
      case "high" -> 2;
      case "medium" -> 1;
      default -> 0;
    };
  }

  /**
   * Merges the per-batch previous-findings statuses into one verdict per prior finding id. A
   * "resolved"/"justified" claim outranks "unresolved": only the batch whose slice contains the
   * finding's file has the evidence to close it (enforced by {@link #scopeStatusesToBatch}), while
   * every other batch reports the finding unresolved simply because its fix is out of that slice.
   */
  static List<ReviewResponse.PreviousFindingStatus> mergeBatchStatuses(
      List<List<ReviewResponse.PreviousFindingStatus>> batchStatuses) {
    var merged = new LinkedHashMap<Integer, ReviewResponse.PreviousFindingStatus>();
    for (var statuses : batchStatuses) {
      for (var status : statuses) {
        var previous = merged.get(status.id());
        if (previous == null || statusRank(status.status()) > statusRank(previous.status())) {
          merged.put(status.id(), status);
        }
      }
    }
    return List.copyOf(merged.values());
  }

  private static int statusRank(String status) {
    return switch (status == null ? "" : status) {
      case "resolved" -> 2;
      case "justified" -> 1;
      default -> 0;
    };
  }

  /** Copies the shared prompt context, swapping the diff and base-comparison slots. */
  private static AiReviewService.PromptInputs withDiff(
      AiReviewService.PromptInputs base, String diff, String baseComparison) {
    return new AiReviewService.PromptInputs(
        diff,
        base.prContext(),
        baseComparison,
        base.projectStack(),
        base.relatedTests(),
        base.previousFindings(),
        base.repoInstructions());
  }

  private String findingsJson(List<ReviewResponse.Finding> findings) {
    try {
      return mapper.writeValueAsString(findings);
    } catch (JsonProcessingException e) {
      Log.warn("Failed to serialize aggregated findings for the summary call", e);
      return "[]";
    }
  }

  /**
   * Every changed file by name + change counts, so the summary covers files with no findings too.
   * Hunk-clipped files are marked partially analyzed — presenting them with bare change counts
   * would tell the summary they were fully covered when most of the patch was never sent.
   */
  private static String changedFilesOverview(
      ReviewContextLoader.ReviewContext ctx, DiffBudgetPlanner.BudgetPlan plan) {
    var sb = new StringBuilder();
    var omitted = Set.copyOf(plan.omittedFiles());
    var clipped = Set.copyOf(plan.clippedFiles());
    for (var file : ctx.reviewableFiles()) {
      if (omitted.contains(file.filename())) {
        continue;
      }
      sb.append(file.filename())
          .append(" (")
          .append(file.status())
          .append(", +")
          .append(file.additions())
          .append(" -")
          .append(file.deletions())
          .append(
              clipped.contains(file.filename())
                  ? " — partially analyzed: clipped to fit the review call budget"
                  : "")
          .append(")\n");
    }
    for (var name : plan.omittedFiles()) {
      sb.append(name).append(" (omitted — exceeded the review call budget; not analyzed)\n");
    }
    return sb.toString();
  }

  /**
   * Runs the raw model response through the full post-AI chain and persists it. The {@code
   * lineResolver} is shared with the caller's verdict backstop, so it is passed in rather than
   * built here.
   */
  private ReviewResponse refine(
      ReviewSession session,
      ReviewResponse aiResponse,
      String diff,
      AiReviewService.PromptInputs promptInputs,
      List<String> priorAiResponseJsons,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      DiffLineResolver lineResolver) {
    aiResponse = quoteValidator.validate(aiResponse, diff);
    aiResponse = deduplicator.dedupe(aiResponse);
    aiResponse =
        findingVerificationService.verify(
            aiResponse,
            promptInputs.diff(),
            promptInputs.projectStack(),
            promptInputs.previousFindings());
    aiResponse =
        followUpAnalyzer.dropRepliedDuplicates(
            aiResponse, priorAiResponseJsons, inlineComments, botIdentity);
    aiResponse = populateMissingAnchors(aiResponse, lineResolver);
    persistAiResponse(session, aiResponse);
    return aiResponse;
  }

  void persistAiResponse(ReviewSession session, ReviewResponse aiResponse) {
    try {
      session.setAiResponseJson(mapper.writeValueAsString(aiResponse));
    } catch (JsonProcessingException e) {
      Log.warn("Failed to serialize AI response for session persistence", e);
    }
  }

  ReviewResponse populateMissingAnchors(ReviewResponse response, DiffLineResolver lineResolver) {
    if (response.findings().isEmpty()) {
      return response;
    }
    var adjusted = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (ReviewResponse.Finding finding : response.findings()) {
      if (finding.suggestionOld() == null || finding.suggestionOld().isBlank()) {
        String fallback = lineResolver.getLineText(finding.file(), finding.line());
        if (fallback != null && !fallback.isBlank()) {
          Log.infof(
              "Populating missing content anchor for finding '%s' (%s:%d) with: '%s'",
              finding.title(), finding.file(), finding.line(), fallback.strip());
          adjusted.add(
              new ReviewResponse.Finding(
                  finding.risk(),
                  finding.confidence(),
                  finding.file(),
                  finding.line(),
                  finding.title(),
                  finding.description(),
                  fallback,
                  finding.suggestionNew()));
          changed = true;
          continue;
        }
      }
      adjusted.add(finding);
    }
    return changed
        ? new ReviewResponse(adjusted, response.previousFindingsStatus(), response.summary())
        : response;
  }
}
