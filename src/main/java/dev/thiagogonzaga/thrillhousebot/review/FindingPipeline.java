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
import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiContextWindowExceededException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponseTruncatedException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.review.ai.Throwables;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenSpendCeilingExceededException;
import dev.thiagogonzaga.thrillhousebot.review.ai.TruncatedResponseSalvager;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.jboss.logging.Logger;

/**
 * The post-AI finding chain: validate quotes, dedupe, verify against the diff, drop already-replied
 * duplicates, backfill missing content anchors, and persist the response. Extracted from {@code
 * ReviewOrchestrator}; the ordering is preserved verbatim — quote validation runs before dedupe so
 * a merged finding cannot inherit a phantom quote while a verbatim sibling is discarded.
 */
@ApplicationScoped
public class FindingPipeline {

  /**
   * Logger pinned to this class so the empty-batch warning emitted from {@link BatchPrompts} keeps
   * the category operators already filter on: the build-time {@code Log} facade binds its category
   * to the class holding the call, which for a nested type would silently relabel the WARN to
   * {@code FindingPipeline$BatchPrompts}.
   */
  private static final Logger LOG = Logger.getLogger(FindingPipeline.class);

  /** Directory rows listed in the scope header before the remainder is rolled up by count. */
  private static final int MAX_SCOPE_DIRECTORIES = 10;

  /** Withheld paths named in the review call's notice before the rest is rolled up by count. */
  private static final int MAX_WITHHELD_PATHS = 20;

  private record BatchOutcome(
      int index,
      List<ReviewResponse.Finding> findings,
      List<ReviewResponse.PreviousFindingStatus> statuses) {}

  /**
   * Everything one multi-call review's batch lane needs beyond the index of the batch being worked
   * on: the planned batches, the session the calls are billed to, the shared prompt template, the
   * plan the coverage disclosures are recorded on, and the previous-finding file index the statuses
   * are scoped against. One value because these five are fixed for the whole lane and travel
   * together through every step of it — the parallel pass, the join, the sequential retry and the
   * truncation salvage — so the step a batch is in is the only thing its signature has to say.
   */
  private record BatchRun(
      List<DiffBudgetPlanner.DiffBatch> batches,
      ReviewSession session,
      BatchPrompts prompts,
      DiffBudgetPlanner.BudgetPlan plan,
      Map<Integer, String> previousFilesById) {}

  /** The rendered file-section header {@link ReviewDiffFormatter#formatFileSection} emits. */
  private static final String SECTION_HEADER_PREFIX = "### ";

  /**
   * The shared prompt template for a review call plus the PR-level {@linkplain
   * #withheldMaterialNotice withheld-material notice}, so every call this class assembles material
   * for carries the same disclosure and the prefixing lives in one place. The notice rides inside
   * the diff slot's untrusted fence — the paths in it come from the pull request — while the rule
   * that acts on it is in the trusted system prompt.
   *
   * <p>The trailing guidance is extended per batch with the {@linkplain
   * #heuristicFailureModesFor(DiffBudgetPlanner.DiffBatch) heuristic failure-mode dimension}, whose
   * trigger is the diff — so it can only be decided once the batch's own text is known.
   */
  private record BatchPrompts(AiReviewService.PromptInputs template, String withheldNotice) {

    AiReviewService.PromptInputs forBatch(
        DiffBudgetPlanner.DiffBatch batch, String baseComparison) {
      return withDiff(
          template,
          PromptTemplateEscaper.fence(withheldNotice + batch.text()),
          baseComparison,
          ReviewPromptAssembler.combineSections(
              template.repoInstructions(), heuristicFailureModesFor(batch)));
    }

    /**
     * The heuristic failure-mode review dimension (#123 / #420) for one batch, appended to that
     * batch's trailing guidance.
     *
     * <p>{@link ReviewPromptAssembler} decides this section from {@code ctx.diff()}, which {@link
     * ReviewContextLoader} leaves empty whenever token budgeting is on — the shipped default — so
     * the whole dimension was silently absent from every default-configuration review (#486 P3). It
     * is decided here instead because this is the first point that holds the material the call
     * actually receives: the plan's batch text. Only the batches whose own slice introduces a
     * decision rule pay for it, which is stricter than the whole-diff gate it replaces. The two
     * cannot both emit the section: this runs only on a budgeted plan, and the assembler's gate
     * only has material when budgeting is off — the loader keys the empty {@code ctx.diff()} on the
     * setting the planner keys {@code budgeted} on.
     *
     * <p>Sizing: the section is a single fixed constant, and the planner sized the shared overhead
     * before it existed. That mirrors the {@linkplain #withheldMaterialNotice withheld-material
     * notice} this class already adds after planning, and it is what the token safety margin (10%
     * of the input cap by default, ~4800 tokens against this section's ~700) is held back for.
     *
     * <p>Warns through {@link #LOG}, pinned to the enclosing class, so the operator-facing category
     * is unchanged by this method living in {@link BatchPrompts}.
     */
    private static String heuristicFailureModesFor(DiffBudgetPlanner.DiffBatch batch) {
      var scanned = heuristicScanSource(batch.text());
      if (scanned.isBlank()) {
        // Loud on purpose: an empty input is exactly what made this dimension die unnoticed, and a
        // detector fed nothing reports "no heuristic code" in the same voice as a detector that
        // read
        // the diff and found none.
        LOG.warnf(
            "Review batch covering %d file(s) carries no diff text, so the heuristic failure-mode"
                + " review dimension has no material to evaluate and is omitted from that call —"
                + " this is a planning defect, not a pull request that introduces no heuristic code",
            batch.files().size());
        return "";
      }
      return ReviewPromptAssembler.heuristicFailureModesSection(scanned);
    }

    /**
     * Copies the shared prompt context, swapping the diff, base-comparison and trailing-guidance
     * slots.
     */
    private static AiReviewService.PromptInputs withDiff(
        AiReviewService.PromptInputs base,
        String diff,
        String baseComparison,
        String repoInstructions) {
      return new AiReviewService.PromptInputs(
          diff,
          base.prContext(),
          baseComparison,
          base.projectStack(),
          base.relatedTests(),
          base.previousFindings(),
          repoInstructions);
    }
  }

  private final AiReviewService aiReviewService;
  private final FindingQuoteValidator quoteValidator;
  private final FrameworkFalsePositiveFilter frameworkFilter;
  private final FindingDeduplicator deduplicator;
  private final FindingVerificationService findingVerificationService;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final ObjectMapper mapper;
  private final BotIdentity botIdentity;
  private final DiffBudgetPlanner budgetPlanner;
  private final TokenCounter tokenCounter;
  private final ReviewTokenLedger tokenLedger;
  private final TruncatedResponseSalvager salvager;

  @Inject
  public FindingPipeline(
      AiReviewService aiReviewService,
      FindingQuoteValidator quoteValidator,
      FrameworkFalsePositiveFilter frameworkFilter,
      FindingDeduplicator deduplicator,
      FindingVerificationService findingVerificationService,
      FollowUpAnalyzer followUpAnalyzer,
      ObjectMapper mapper,
      BotIdentity botIdentity,
      DiffBudgetPlanner budgetPlanner,
      TokenCounter tokenCounter,
      ReviewTokenLedger tokenLedger,
      TruncatedResponseSalvager salvager) {
    this.aiReviewService = aiReviewService;
    this.quoteValidator = quoteValidator;
    this.frameworkFilter = frameworkFilter;
    this.deduplicator = deduplicator;
    this.findingVerificationService = findingVerificationService;
    this.followUpAnalyzer = followUpAnalyzer;
    this.mapper = mapper;
    this.botIdentity = botIdentity;
    this.budgetPlanner = budgetPlanner;
    this.tokenCounter = tokenCounter;
    this.tokenLedger = tokenLedger;
    this.salvager = salvager;
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
    // Open/clear the spend ledger around everything that can make an AI call, so a review's
    // entry exists exactly while its provider callbacks may land and never outlives the review.
    tokenLedger.open(ledgerSessionId(session));
    try {
      return runWithLedger(session, promptInputs, ctx, plan, lineResolver);
    } finally {
      tokenLedger.clear(ledgerSessionId(session));
    }
  }

  private ReviewResponse runWithLedger(
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
          new BatchPrompts(promptInputs, withheldMaterialNotice(ctx, plan))
              .forBatch(budgetedBatch, promptInputs.baseComparison());
      quoteSource = budgetedBatch.text();
    }
    ReviewResponse aiResponse;
    try {
      aiResponse = aiReviewService.review(session, singleInputs);
    } catch (AiResponseTruncatedException e) {
      // #580: the call was made and billed, and its body is well-formed up to the cut. Losing the
      // whole review to it would discard exactly the paid work the multi-call lane already keeps.
      aiResponse = salvageTruncatedSingleCall(session, e, budgetedBatch, plan);
    }
    if (budgetedBatch != null && !aiResponse.previousFindingsStatus().isEmpty()) {
      // Same scoping as the multi-call path: the single budgeted batch may carry clipped files
      // (and, at maxBatches=1, omit others entirely), so a "resolved" claim is only trusted for a
      // prior finding whose file the call provably saw in full.
      var scoped =
          scopeStatusesToBatch(
              aiResponse.previousFindingsStatus(),
              budgetedBatch,
              plan,
              followUpAnalyzer.previousFindingFilesById(ctx.previousFindingsList()));
      aiResponse = new ReviewResponse(aiResponse.findings(), scoped, aiResponse.summary());
    }
    return refine(session, aiResponse, quoteSource, singleInputs, ctx, lineResolver, plan);
  }

  /**
   * The disclose step for a single-call review the model cut at its length cap (#580): the same
   * salvage the multi-call lane's {@link #salvageTruncatedBatch} runs, applied to the lane that had
   * none. The complete leading findings and statuses are recovered from the buffered partial body
   * and returned as an ordinary response, so the caller's status scoping and {@link #refine} chain
   * treat them exactly like parsed ones — a salvaged finding faces every check a parsed finding
   * does. The summary object rides along when it closed before the cut (it is last in the response
   * shape, so usually it did not); {@code null} then leaves the renderer's counts-only shape, the
   * same degradation the multi-call summary seam produces. No {@link SummaryDegradation} is
   * recorded for it, though: that class's copy states the findings are complete, which is exactly
   * what a salvaged review's are not — the honest disclosure here is the response-cut file class
   * below, which says the findings up to the cut were kept.
   *
   * <p>A salvaged review covers the diff only up to the cut, so the files it was given are recorded
   * as {@linkplain DiffBudgetPlanner.BudgetPlan#recordResponseCutFiles response-cut} — the existing
   * partially-reviewed class, which holds APPROVE and makes the posted review say why. This makes
   * no AI call of its own and never re-enters the retry lane: the truncation was already refused a
   * retry (#495) because an identical call would cut identically.
   *
   * <p>When nothing salvages — the cut landed before the first element closed — the truncation is
   * rethrown, keeping today's behaviour. Unlike a batch, whose siblings still carry the review,
   * this lane's failed call is the whole review: there would be no finding, no status and no
   * summary to post, so failing loudly (the orchestrator's notice names the cap and the knob to
   * raise) beats posting an empty review whose only content is a disclosure.
   */
  private ReviewResponse salvageTruncatedSingleCall(
      ReviewSession session,
      AiResponseTruncatedException truncation,
      DiffBudgetPlanner.DiffBatch budgetedBatch,
      DiffBudgetPlanner.BudgetPlan plan) {
    var salvaged = salvager.salvage(truncation.partialBody());
    if (!salvaged.hasFindingsOrStatuses()) {
      Log.warnf(
          "The review call for session %d hit the model's response-length cap and nothing could be"
              + " salvaged from the partial response; failing the review rather than posting one"
              + " with neither findings nor coverage",
          ledgerSessionId(session));
      throw truncation;
    }
    discloseSingleCallResponseCut(session, budgetedBatch, plan, salvaged);
    return new ReviewResponse(
        salvaged.findings(), salvaged.previousFindingsStatus(), salvaged.summary());
  }

  /**
   * Records what a salvaged single call could not cover. A budgeted plan's single batch is the
   * natural unit — it is exactly the material the call was given — so its files are disclosed as
   * partially reviewed. With no batch to name (budgeting disabled by {@code max-input-tokens <= 0},
   * or a plan that produced none) there is no per-file unit to record and {@link VerdictBuilder}
   * ignores the plan's file classes on that path anyway, so the cut is stated here in the log
   * instead of being disclosed silently as nothing at all.
   */
  private void discloseSingleCallResponseCut(
      ReviewSession session,
      DiffBudgetPlanner.DiffBatch budgetedBatch,
      DiffBudgetPlanner.BudgetPlan plan,
      TruncatedResponseSalvager.Salvaged salvaged) {
    if (budgetedBatch == null) {
      Log.warnf(
          "The review call for session %d hit the model's response-length cap; not retrying —"
              + " salvaged %d complete finding(s) and %d status(es) from the partial response."
              + " The plan carries no batch (budgeting disabled, or no reviewable file), so the"
              + " covered files cannot be named and the posted review cannot mark the diff beyond"
              + " the cut as unreviewed; enable budgeting (REVIEW_MAX_INPUT_TOKENS) for that"
              + " disclosure",
          ledgerSessionId(session),
          salvaged.findings().size(),
          salvaged.previousFindingsStatus().size());
      return;
    }
    Log.warnf(
        "The review call for session %d hit the model's response-length cap; not retrying —"
            + " salvaged %d complete finding(s) and %d status(es) from the partial response and"
            + " disclosing its %d file(s) as partially reviewed",
        ledgerSessionId(session),
        salvaged.findings().size(),
        salvaged.previousFindingsStatus().size(),
        budgetedBatch.files().size());
    plan.recordResponseCutFiles(filenamesOf(budgetedBatch.files()));
  }

  /**
   * Map-reduce review for a large PR: review each token-budgeted batch in parallel (blocking AI
   * calls on virtual threads), quote-validating and verifying each batch's findings against that
   * batch's own in-budget text (the combined diff would exceed the very budget the batches exist to
   * respect), union the results through the finishing chain, then a single summary call rolls them
   * up into the PR-level summary. A batch that fails after {@link AiReviewService}'s internal
   * retries is retried once more synchronously after the parallel pass; if it still fails it is
   * soft-failed — its files are recorded as uncovered on the shared plan (so the verdict holds
   * APPROVE and the summary discloses the gap) and every batch that succeeded keeps its findings,
   * rather than the whole review being discarded. Previous-findings statuses are aggregated from
   * the batch calls — they saw the diff; the code-blind summary call must not decide what was
   * resolved. The passed {@code promptInputs} is reused as the shared-context template — only the
   * diff slot changes per batch, and the base comparison is dropped (it is a near-duplicate of the
   * diff that would otherwise be re-sent in every call).
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
    var previousFilesById = followUpAnalyzer.previousFindingFilesById(ctx.previousFindingsList());

    var run =
        new BatchRun(
            batches,
            session,
            new BatchPrompts(promptInputs, withheldMaterialNotice(ctx, plan)),
            plan,
            previousFilesById);
    var outcomesByIndex = new BatchOutcome[batches.size()];
    var failedIndices = new ArrayList<Integer>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          IntStream.range(0, batches.size())
              .mapToObj(i -> CompletableFuture.supplyAsync(() -> processBatch(i, run), executor))
              .toList();
      joinBatchOutcomes(futures, run, outcomesByIndex, failedIndices);
    }

    for (int index : failedIndices) {
      if (tokenLedger.ceilingReached(ledgerSessionId(session))) {
        // The sequential retry is a fresh billed call; once the ceiling is reached it is not made.
        Log.warnf(
            "Batch %d/%d retry skipped at the review's token spend ceiling (%d tokens spent,"
                + " ceiling %d — REVIEW_MAX_TOKENS_PER_REVIEW); disclosing its files as not"
                + " reviewed",
            index + 1,
            batches.size(),
            tokenLedger.tokensSpent(ledgerSessionId(session)),
            tokenLedger.ceiling());
        plan.recordSpendCeilingSkippedFiles(filenamesOf(batches.get(index).files()));
        continue;
      }
      retryBatch(index, run, outcomesByIndex);
    }

    var outcomes =
        IntStream.range(0, batches.size())
            .mapToObj(i -> outcomesByIndex[i])
            .filter(Objects::nonNull)
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
    // #726: a novel lower-confidence hypothesis on an anchor the maintainer has already
    // dispositioned twice is a re-roll of the dice, not a finding to disposition again.
    refined =
        FollowUpAnalyzer.withoutPreviouslyLitigated(
            refined,
            ctx.priorAiResponses(),
            ctx.inlineComments(),
            ctx.conversationComments(),
            botIdentity);
    refined = populateMissingAnchors(refined, lineResolver);

    if (tokenLedger.ceilingReached(ledgerSessionId(session))) {
      return countsOnlySummary(session, refined, plan, "skipping the summary call");
    }
    var overview = clampOverview(changedFilesOverview(ctx, plan), promptInputs);
    var summaryInputs =
        new AiReviewService.SummaryInputs(
            promptInputs.prContext(),
            PromptTemplateEscaper.escape(
                budgetedFindingsJson(refined.findings(), promptInputs, overview)),
            PromptTemplateEscaper.escape(overview),
            promptInputs.previousFindings(),
            promptInputs.repoInstructions());
    ReviewResponse summaryResponse;
    try {
      summaryResponse = aiReviewService.summarize(session, summaryInputs);
    } catch (TokenSpendCeilingExceededException _) {
      // The gate above passed but a late usage callback (e.g. a timed-out batch attempt's
      // response) crossed the ceiling first, or a summary retry was refused mid-loop.
      return countsOnlySummary(session, refined, plan, "summary call refused");
    } catch (AiResponseTruncatedException e) {
      // #500 scope A: every batch call succeeded and was billed. Losing the whole review to a
      // truncated summary would discard exactly the paid work #495 preserved on the batch lane —
      // salvage the summary object if it closed before the cut, else degrade to the same
      // counts-only shape as the ceiling-tripped path above. Never re-enters the retry lane.
      return salvagedOrCountsOnlySummary(session, refined, plan, e);
    }

    var merged =
        new ReviewResponse(
            refined.findings(), refined.previousFindingsStatus(), summaryResponse.summary());
    persistAiResponse(session, merged);
    return merged;
  }

  /**
   * The summary degradation for a review whose token spend ceiling tripped after the batch calls:
   * the findings are already paid for, so they are kept and persisted with a {@code null} model
   * summary — the renderer's counts-only shape, the same one a summary call that returns no summary
   * object produces — rather than spending past the ceiling or discarding the review. Recorded on
   * the plan so the posted review names the ceiling (#518), like the truncation flavor of the same
   * lane ({@link #salvagedOrCountsOnlySummary}) — a log-only warning would let a bare counts-only
   * review post with no stated reason when no batch was also skipped.
   */
  private ReviewResponse countsOnlySummary(
      ReviewSession session,
      ReviewResponse refined,
      DiffBudgetPlanner.BudgetPlan plan,
      String what) {
    plan.recordSummaryDegradation(SummaryDegradation.SKIPPED_AT_CEILING);
    Log.warnf(
        "Review session %d reached its token spend ceiling before the summary call (%d tokens"
            + " spent, ceiling %d — REVIEW_MAX_TOKENS_PER_REVIEW); %s and keeping the %d paid"
            + " findings with a counts-only summary",
        ledgerSessionId(session),
        tokenLedger.tokensSpent(ledgerSessionId(session)),
        tokenLedger.ceiling(),
        what,
        refined.findings().size());
    return persistWithSummary(session, refined, null);
  }

  /**
   * The summary degradation for a summary response the model cut at its length cap (#500 scope A):
   * if the summary object closed before the cut it is salvaged and used as-is; otherwise the review
   * keeps its paid findings with the {@link #countsOnlySummary counts-only} shape. Either way the
   * truncation is disclosed in the log with the caps that apply — the summary call runs on the
   * concise model, so both knobs are named — and recorded on the plan so the posted review carries
   * the same disclosure instead of leaving it log-only, exactly like the sibling spend-ceiling
   * degradation of this lane records its own flavor in {@link #countsOnlySummary} (#518). Statuses
   * are never taken from the salvage: the code-blind summary call must not decide what was resolved
   * (same rule as the parsed path).
   */
  private ReviewResponse salvagedOrCountsOnlySummary(
      ReviewSession session,
      ReviewResponse refined,
      DiffBudgetPlanner.BudgetPlan plan,
      AiResponseTruncatedException truncation) {
    plan.recordSummaryDegradation(SummaryDegradation.RESPONSE_CUT);
    var salvagedSummary = salvager.salvage(truncation.partialBody()).summary();
    if (salvagedSummary != null) {
      Log.warnf(
          "Summary response for session %d was cut at the model's response-length cap"
              + " (max-output-tokens / REVIEW_CONCISE_MAX_OUTPUT_TOKENS); the summary object"
              + " closed before the cut and was salvaged — keeping the %d paid findings",
          ledgerSessionId(session), refined.findings().size());
      return persistWithSummary(session, refined, salvagedSummary);
    }
    Log.warnf(
        "Summary response for session %d was cut at the model's response-length cap"
            + " (max-output-tokens / REVIEW_CONCISE_MAX_OUTPUT_TOKENS) and no complete summary"
            + " object could be salvaged; keeping the %d paid findings with a counts-only summary",
        ledgerSessionId(session), refined.findings().size());
    return persistWithSummary(session, refined, null);
  }

  /** Persists and returns the refined findings/statuses under the given (possibly null) summary. */
  private ReviewResponse persistWithSummary(
      ReviewSession session, ReviewResponse refined, ReviewResponse.Summary summary) {
    var merged = new ReviewResponse(refined.findings(), refined.previousFindingsStatus(), summary);
    persistAiResponse(session, merged);
    return merged;
  }

  /**
   * The ledger key for a session. Production sessions are persisted (and so have an id) before the
   * pipeline runs; the sentinel only exists so an unpersisted session (unit tests) does not NPE on
   * unboxing.
   */
  private static long ledgerSessionId(ReviewSession session) {
    return ReviewTokenLedger.keyFor(session);
  }

  /**
   * One batch's sequential retry: a fresh billed attempt whose failure classifications mirror the
   * parallel pass — a mid-retry ceiling refusal or a truncation goes to disclosure (the truncation
   * salvaging first), anything else soft-fails the batch as not reviewed.
   */
  private void retryBatch(int index, BatchRun run, BatchOutcome[] outcomesByIndex) {
    var batches = run.batches();
    var plan = run.plan();
    try {
      outcomesByIndex[index] = processBatch(index, run);
      Log.infof("Batch %d/%d succeeded on retry", index + 1, batches.size());
    } catch (TokenSpendCeilingExceededException e) {
      // The gate above passed but a concurrent late callback (e.g. a timed-out attempt's usage)
      // pushed the ledger over before the attempt started, or a mid-retry attempt was refused.
      Log.warnf(
          "Batch %d/%d retry refused at the review's token spend ceiling; disclosing its files"
              + " as not reviewed. %s",
          index + 1, batches.size(), e.getMessage());
      plan.recordSpendCeilingSkippedFiles(filenamesOf(batches.get(index).files()));
    } catch (RuntimeException e) {
      var truncation = AiResponseTruncatedException.findIn(e);
      if (truncation.isPresent()) {
        // The parallel attempt failed transiently and the retry hit the length cap: same
        // salvage-or-disclose step as a parallel-pass truncation, and no further retry (#495).
        outcomesByIndex[index] = salvageTruncatedBatch(index, run, truncation.get());
        return;
      }
      // Soft-fail like the on-request generators (DocGenerationService / PrImprovementService):
      // one batch that never succeeds must not discard the batches that did. Keep their
      // findings, record this batch's files as uncovered on the shared plan so the verdict holds
      // APPROVE and the summary discloses the gap, and let the review proceed (outcome stays
      // null and is skipped below).
      Log.warnf(
          e,
          "Batch %d/%d failed after its retry; keeping the successful batches and disclosing its"
              + " files as not reviewed rather than failing the whole review",
          index + 1,
          batches.size());
      plan.recordUncoveredFiles(filenamesOf(batches.get(index).files()));
    }
  }

  /**
   * Joins the parallel batch futures, classifying each failure: a truncation is disclosed at once
   * (its retry would cut identically), a ceiling refusal is disclosed with the ceiling as the
   * reason, and anything else queues for the sequential retry pass. A genuine ceiling refusal
   * always follows billed spend — the ledger opens fresh per review and only refuses once spent
   * reaches a positive ceiling — so an all-refused review still falls through to the disclosure and
   * counts-only summary paths; there is no reachable zero-call state to special-case.
   */
  private void joinBatchOutcomes(
      List<CompletableFuture<BatchOutcome>> futures,
      BatchRun run,
      BatchOutcome[] outcomesByIndex,
      List<Integer> failedIndices) {
    var batches = run.batches();
    var plan = run.plan();
    var session = run.session();
    for (int i = 0; i < futures.size(); i++) {
      try {
        outcomesByIndex[i] = futures.get(i).join();
      } catch (CompletionException e) {
        var truncation = AiResponseTruncatedException.findIn(e);
        if (truncation.isPresent()) {
          // The batch's own retry below would re-send the identical prompt against the identical
          // cap (#495), so the failure goes straight to the disclose step — which now salvages
          // the complete leading findings out of the buffered partial body first (#500).
          outcomesByIndex[i] = salvageTruncatedBatch(i, run, truncation.get());
        } else if (AiContextWindowExceededException.findIn(e).isPresent()) {
          // Deterministic like a truncation: the provider rejected the batch's request for
          // exceeding the model's context window, and the sequential retry below would re-send
          // the identical prompt against the identical window (#622). Straight to disclosure.
          Log.warnf(
              "Batch %d/%d was rejected for exceeding the model's context window; not retrying"
                  + " (an identical request would be rejected identically) and disclosing its"
                  + " files as not reviewed",
              i + 1, batches.size());
          plan.recordUncoveredFiles(filenamesOf(batches.get(i).files()));
        } else if (isSpendCeilingBlocked(e)) {
          // Deterministic like a truncation: the ledger is monotonic within a review, so a retry
          // would be refused identically. Degrade like the budgeter — disclose, with the ceiling
          // as the reason — instead of paying nothing and losing the batches that succeeded.
          Log.warnf(
              "Batch %d/%d skipped at the review's token spend ceiling (%d tokens spent, ceiling"
                  + " %d — REVIEW_MAX_TOKENS_PER_REVIEW); disclosing its files as not reviewed",
              i + 1,
              batches.size(),
              tokenLedger.tokensSpent(ledgerSessionId(session)),
              tokenLedger.ceiling());
          plan.recordSpendCeilingSkippedFiles(filenamesOf(batches.get(i).files()));
        } else {
          failedIndices.add(i);
          Log.warnf(
              e,
              "Batch %d/%d failed in the parallel pass; will retry after the other batches finish",
              i + 1,
              batches.size());
        }
      }
    }
  }

  private BatchOutcome processBatch(int index, BatchRun run) {
    var batches = run.batches();
    var batch = batches.get(index);
    var batchInputs = run.prompts().forBatch(batch, "");
    var batchResponse =
        aiReviewService.reviewBatch(run.session(), batchInputs, index + 1, batches.size());
    return refineBatchOutcome(index, batch, batchInputs, batchResponse, run);
  }

  /**
   * Runs one batch's raw response through the per-batch chain — quote validation and framework
   * filtering against the batch's own in-budget text, verification, and status scoping. Shared by
   * the parsed path and the salvage path, so salvaged findings face exactly the checks a normally
   * parsed batch's findings do. The session keys the spend ledger for the verification call, which
   * is metered and ceiling-gated inside {@link FindingVerificationService}.
   */
  private BatchOutcome refineBatchOutcome(
      int index,
      DiffBudgetPlanner.DiffBatch batch,
      AiReviewService.PromptInputs batchInputs,
      ReviewResponse batchResponse,
      BatchRun run) {
    var validated = quoteValidator.validate(batchResponse, batch.text());
    validated = frameworkFilter.filter(validated, batch.text());
    // #736: the verification call is the one review-path call that does no budget arithmetic of
    // its own, so the section the author alone sizes is bounded here before it is sent.
    var verified =
        findingVerificationService.verify(
            ledgerSessionId(run.session()),
            validated,
            PrContextBudget.bound(
                batchInputs.prContext(), budgetPlanner.perCallInputBudget(), tokenCounter),
            batchInputs.diff(),
            batchInputs.projectStack(),
            batchInputs.previousFindings(),
            run.plan()::recordVerificationCoverage);
    return new BatchOutcome(
        index,
        verified.findings(),
        scopeStatusesToBatch(
            batchResponse.previousFindingsStatus(), batch, run.plan(), run.previousFilesById()));
  }

  /**
   * The disclose step for a batch whose response the model cut at its length cap (#500): salvages
   * the complete leading findings/statuses out of the buffered partial body and, when anything came
   * back, runs them through the normal per-batch chain and discloses the batch's files as
   * <em>partially reviewed</em> — the response was cut and the findings up to the cut were kept.
   * When nothing salvages (the cut landed before the first element closed, or the lane carried no
   * body), it falls back to the pre-#500 behaviour: the files are disclosed as not reviewed.
   * Replaces disclosure only — the truncation was already refused a retry (#495), and salvage makes
   * no AI call of its own; the verification call it funnels salvaged findings into is metered and
   * ceiling-gated like any other, so #509's ceiling accounting is untouched.
   */
  private BatchOutcome salvageTruncatedBatch(
      int index, BatchRun run, AiResponseTruncatedException truncation) {
    var batches = run.batches();
    var plan = run.plan();
    var batch = batches.get(index);
    var salvaged = salvager.salvage(truncation.partialBody());
    if (!salvaged.hasFindingsOrStatuses()) {
      Log.warnf(
          "Batch %d/%d hit the model's response-length cap and nothing could be salvaged from the"
              + " partial response; not retrying and disclosing its files as not reviewed",
          index + 1, batches.size());
      plan.recordUncoveredFiles(filenamesOf(batch.files()));
      return null;
    }
    Log.warnf(
        "Batch %d/%d hit the model's response-length cap; not retrying — salvaged %d complete"
            + " finding(s) and %d status(es) from the partial response and disclosing its files as"
            + " partially reviewed",
        index + 1,
        batches.size(),
        salvaged.findings().size(),
        salvaged.previousFindingsStatus().size());
    plan.recordResponseCutFiles(filenamesOf(batch.files()));
    var batchInputs = run.prompts().forBatch(batch, "");
    var partialResponse =
        new ReviewResponse(salvaged.findings(), salvaged.previousFindingsStatus(), null);
    return refineBatchOutcome(index, batch, batchInputs, partialResponse, run);
  }

  private static List<String> filenamesOf(List<GitHubPullRequestClient.FileDiff> files) {
    return files.stream().map(GitHubPullRequestClient.FileDiff::filename).toList();
  }

  /**
   * Whether a batch failure was the spend ceiling refusing the call before it was made. The refusal
   * arrives wrapped in the parallel pass's {@link CompletionException}, so this shares the bounded
   * cause walk in {@link Throwables#findCause} with {@link AiResponseTruncatedException#findIn}.
   */
  private static boolean isSpendCeilingBlocked(Throwable failure) {
    return Throwables.findCause(failure, TokenSpendCeilingExceededException.class).isPresent();
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
    ReviewResponse summaryResponse;
    try {
      summaryResponse = aiReviewService.summarize(session, summaryInputs);
    } catch (TokenSpendCeilingExceededException _) {
      // Same degradation as the multi-call summarize seam: a summary retry refused mid-loop at
      // the spend ceiling must not fail the review — this lane's only AI call is the summary, so
      // the review must still post with its omission disclosures, and the skip is recorded on the
      // plan so the posted review names the ceiling.
      return countsOnlySummary(
          session, new ReviewResponse(List.of(), List.of(), null), plan, "summary call refused");
    } catch (AiResponseTruncatedException e) {
      // Same degradation as the multi-call summary seam (#500 scope A): this path has no findings
      // by construction, but the review must still post with its omission disclosures rather than
      // fail on a deterministic truncation.
      return salvagedOrCountsOnlySummary(
          session, new ReviewResponse(List.of(), List.of(), null), plan, e);
    }
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
   *
   * <p>The clamp keeps a prefix, so what it drops is the tail — a row the walkthrough renders could
   * in principle be one the summary prompt never saw, leaving it ungrounded (#547). Measured
   * against the largest PR on record (devops-thiago/ThrillhouseBot#532, 170 changed files) at the
   * shipped defaults, that gap does not open: the per-call budget is {@code 48000 * 0.9 - 8192 =
   * 35008} tokens and the summary prompt's fixed sections cost ~1208, so the overview's half share
   * is ~16900 — while all 170 rows cost 5188, under a third of it. Nothing is clamped, and the
   * walkthrough renders at most {@link PrReviewPrompts#MAX_FILE_SUMMARIES} rows, so every rendered
   * row is grounded with a wide margin. Pinned by {@code
   * FindingPipelineTest#aPr532SizedOverviewKeepsFarMoreRowsThanTheWalkthroughRenders}.
   *
   * <p>Only the per-file rows are clampable. The {@linkplain ChangedFilesOverview#header() header
   * block} — the pure-rename rollup and the PR-scope totals — is rendered first precisely so
   * clamping can only take the tail, so a budget too small to hold it withholds the overview
   * outright rather than emitting a rollup note in the header's place (#486 P4). That also keeps
   * the note's count honest: everything it can drop is a file.
   */
  private String clampOverview(
      ChangedFilesOverview overview, AiReviewService.PromptInputs promptInputs) {
    var budget = budgetPlanner.perCallInputBudget();
    if (budget == Integer.MAX_VALUE) {
      return overview.text();
    }
    var inherited =
        PrReviewPrompts.SUMMARY_SYSTEM
            + PrReviewPrompts.SUMMARY_USER
            + promptInputs.prContext()
            + promptInputs.previousFindings()
            + promptInputs.repoInstructions();
    var overviewBudget = (budget - tokenCounter.estimateTokens(inherited)) / 2;
    var rows = overview.fileRows();
    // Rollup-note tokens are reserved up front so post-truncation append cannot exceed the share.
    var noteReserve = tokenCounter.estimateTokens(overviewRollupNote(rows.length));
    var headerTokens = tokenCounter.estimateTokens(overview.header());
    if (overviewBudget - noteReserve < headerTokens) {
      return "(changed-files overview withheld — summary input budget exhausted)\n";
    }
    var sb = new StringBuilder(overview.header());
    var used = headerTokens;
    var listed = 0;
    while (listed < rows.length) {
      var rowTokens = tokenCounter.estimateTokens(rows[listed] + "\n");
      if (used + rowTokens > overviewBudget - noteReserve) {
        break;
      }
      sb.append(rows[listed]).append('\n');
      used += rowTokens;
      listed++;
    }
    if (listed == rows.length) {
      return overview.text();
    }
    sb.append(overviewRollupNote(rows.length - listed));
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

  /**
   * The review call's disclosure of what this pull request changes that its diff deliberately does
   * NOT show: pure renames (excluded by #386), paths the ignore list took out of review scope, and
   * files the token budget could not fit. Without it the model reads absence from its material as
   * absence from the pull request and reports finished work as missing — the walkthrough lists the
   * renamed file while {@code description_gaps} claims the rename the PR body describes is not in
   * the diff (#569).
   *
   * <p>The budgeted path is where this bites, and it is why the disclosure has to be rebuilt here:
   * a batch's text is file sections only, so the rollup {@link
   * ReviewDiffFormatter#buildDiffStringWithStats} puts in the legacy raw diff's header never
   * reaches the call — and budgeting is on by default, so in practice no review call ever saw it.
   * The wording deliberately matches that rollup's ("omitted from AI review"), so the system
   * prompt's rule recognizes either form.
   *
   * <p>Returns empty when nothing was withheld, leaving an ordinary PR's prompt byte-identical.
   * Uses {@code ctx.files()} minus {@code ctx.reviewableFiles()}, which is exactly the material the
   * review call cannot see, so a path withheld for any reason is named rather than only the classes
   * enumerated here.
   */
  private static String withheldMaterialNotice(
      ReviewContextLoader.ReviewContext ctx, DiffBudgetPlanner.BudgetPlan plan) {
    var reviewable = ReviewDiffFormatter.namesOf(ctx.reviewableFiles());
    var rows = new ArrayList<String>();
    for (var file : ctx.files()) {
      if (reviewable.contains(file.filename())) {
        continue;
      }
      rows.add(
          ReviewDiffFormatter.isPureRename(file)
              ? renamePath(file) + " (pure rename — no content change)"
              : file.filename() + " (excluded from review scope by the ignore list)");
    }
    // Planned omissions are reviewable files that did not fit any batch: withheld for a third
    // reason, and just as invisible to this call as the two above.
    for (var name : plan.omittedFiles()) {
      rows.add(name + " (exceeded the review call budget)");
    }
    if (rows.isEmpty()) {
      return "";
    }
    var sb =
        new StringBuilder(
            """
            ## Changed files omitted from AI review
            Each path below IS changed by this pull request. Its content was withheld from \
            the diff below, so nothing about it can appear in the material you were given.
            """);
    for (var row : rows.subList(0, Math.min(rows.size(), MAX_WITHHELD_PATHS))) {
      sb.append("- ").append(row).append('\n');
    }
    if (rows.size() > MAX_WITHHELD_PATHS) {
      sb.append("- (+")
          .append(rows.size() - MAX_WITHHELD_PATHS)
          .append(" more changed files omitted from AI review)\n");
    }
    return sb.append('\n').toString();
  }

  /** "old → new" for a rename that carries its previous path, else the current path alone. */
  private static String renamePath(GitHubPullRequestClient.FileDiff file) {
    var previous = file.previousFilename();
    return previous == null || previous.isBlank()
        ? file.filename()
        : previous + " → " + file.filename();
  }

  /**
   * The batch text with each rendered {@code ### path (status, +a -d)} header rewritten to the
   * unified-diff {@code +++ b/path} form {@link HeuristicCodeDetector} scopes files by. Without it
   * the detector sees one unattributed run of added lines: its test-file exclusion stops applying
   * (a fixture regex is not new production logic) and its JavaScript regex-literal signal, which is
   * only enabled for JS/TS paths, never fires. Everything else is passed through verbatim, so the
   * detector reads exactly the added lines the model was given — clipping included.
   */
  static String heuristicScanSource(String batchText) {
    if (batchText == null || batchText.isBlank()) {
      return "";
    }
    var lines = batchText.split("\n", -1);
    var sb = new StringBuilder(batchText.length());
    for (var i = 0; i < lines.length; i++) {
      if (i > 0) {
        sb.append('\n');
      }
      var line = lines[i];
      sb.append(line.startsWith(SECTION_HEADER_PREFIX) ? "+++ b/" + sectionFilename(line) : line);
    }
    return sb.toString();
  }

  /**
   * The path out of a rendered section header, dropping the {@code " (status, +a -d)"} suffix when
   * one is present — the same parse {@link FindingQuoteValidator} does on the same headers.
   */
  private static String sectionFilename(String headerLine) {
    var name = headerLine.substring(SECTION_HEADER_PREFIX.length());
    var suffix = name.lastIndexOf(" (");
    return (suffix > 0 ? name.substring(0, suffix) : name).strip();
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
   * The summary call's changed-files overview, split at the seam {@link #clampOverview} clamps on:
   * the {@code header} block that must survive any clamp (pure-rename rollup, PR-scope totals) and
   * the newline-separated per-file {@code rows} that may be rolled up by count from the tail.
   */
  private record ChangedFilesOverview(String header, String rows) {

    String text() {
      return header + rows;
    }

    /** The per-file rows as lines, empty when there are none — never a single blank row. */
    String[] fileRows() {
      return rows.isEmpty() ? new String[0] : rows.split("\n");
    }
  }

  /**
   * Every changed file by name + change counts, so the summary covers files with no findings too.
   * Hunk-clipped files are marked partially analyzed — presenting them with bare change counts
   * would tell the summary they were fully covered when most of the patch was never sent.
   */
  private static ChangedFilesOverview changedFilesOverview(
      ReviewContextLoader.ReviewContext ctx, DiffBudgetPlanner.BudgetPlan plan) {
    var header = new StringBuilder();
    // Pure-rename rollup first so clampOverview (keeps a prefix) never drops the disclosure on
    // large multi-call reviews (#386).
    var pureRenames = ReviewDiffFormatter.pureRenameFiles(ctx.files());
    if (!pureRenames.isEmpty()) {
      header.append(ReviewDiffFormatter.formatPureRenameRollup(pureRenames));
    }
    // Scope totals next, ahead of the per-file rows, for the same reason: clamping drops the tail.
    header.append(changeScopeSummary(ctx));
    var sb = new StringBuilder();
    var omitted = Set.copyOf(plan.omittedFiles());
    var uncovered = Set.copyOf(plan.runtimeUncoveredFiles());
    // effectiveClippedFiles drops any clipped file a failed batch left wholly uncovered, so it is
    // disclosed once, as uncovered, not also marked "partially analyzed".
    var clipped = Set.copyOf(plan.effectiveClippedFiles());
    // Files whose batch response was cut but salvaged (#500): kept in the walkthrough with an
    // honest caveat — checked before the clipped marker so a file in both classes is disclosed
    // once, under the stronger (output-side) statement.
    var responseCut = Set.copyOf(plan.responseCutFiles());
    for (var file : ctx.reviewableFiles()) {
      if (ReviewDiffFormatter.namesContain(omitted, file.filename())
          || ReviewDiffFormatter.namesContain(uncovered, file.filename())) {
        continue;
      }
      sb.append(file.filename())
          .append(" (")
          .append(file.status())
          .append(", +")
          .append(file.additions())
          .append(" -")
          .append(file.deletions())
          .append(fileCoverageMarker(file.filename(), responseCut, clipped))
          .append(")\n");
    }
    for (var name : plan.omittedFiles()) {
      sb.append(name).append(" (omitted — exceeded the review call budget; not analyzed)\n");
    }
    // A file skipped at the spend ceiling is recorded as runtime-uncovered too (it holds approval
    // like any uncovered file), but its cause is a deliberate stop, not a call that failed. The
    // posted disclosure already names the ceiling (ReviewResult.coverageGapClause); labelling it a
    // transient failure here would leave the two surfaces disagreeing about the same files, and
    // this is the one the summary model reads.
    var ceilingSkipped = Set.copyOf(plan.spendCeilingSkippedFiles());
    for (var name : plan.runtimeUncoveredFiles()) {
      sb.append(name)
          .append(
              ReviewDiffFormatter.namesContain(ceilingSkipped, name)
                  ? " (not reviewed — skipped at the review's token spend ceiling"
                      + " (REVIEW_MAX_TOKENS_PER_REVIEW))\n"
                  : " (not reviewed — the review call for it did not complete; treated as"
                      + " uncovered)\n");
    }
    return new ChangedFilesOverview(header.toString(), sb.toString());
  }

  /** The per-file coverage caveat for the summary overview, or empty for full coverage. */
  private static String fileCoverageMarker(
      String filename, Set<String> responseCut, Set<String> clipped) {
    if (ReviewDiffFormatter.namesContain(responseCut, filename)) {
      return " — partially reviewed: the model's response was cut at its length cap; findings up"
          + " to the cut were kept";
    }
    if (ReviewDiffFormatter.namesContain(clipped, filename)) {
      return " — partially analyzed: clipped to fit the review call budget";
    }
    return "";
  }

  /**
   * PR-level scope header for the summary call: the authoritative file/line totals (GitHub's, the
   * same numbers the rendered Changes Overview reports — #298) plus how the change is spread across
   * directories. The summary call never sees the diff, so without these the only cue for how big
   * the change is is a file list the input budget may have clamped — which is how a multi-file
   * decompose got described as one extracted class (#335). Rendered as data, ahead of the per-file
   * rows, so clamping can only take the tail.
   */
  private static String changeScopeSummary(ReviewContextLoader.ReviewContext ctx) {
    var files = VerdictBuilder.overviewFiles(ctx);
    var additions = 0;
    var deletions = 0;
    for (var file : files) {
      additions += file.additions();
      deletions += file.deletions();
    }
    var totals = ctx.prTotals();
    // A non-positive file count means the totals carry nothing usable: fall back to the
    // diff-derived counts rather than announcing a zero-file PR over a non-empty file list.
    var authoritative = totals != null && totals.filesChanged() > 0;
    var filesChanged = authoritative ? totals.filesChanged() : files.size();
    if (authoritative) {
      additions = totals.additions();
      deletions = totals.deletions();
    }
    if (filesChanged <= 0) {
      return "";
    }
    var sb = new StringBuilder();
    sb.append("PR scope (whole pull request): ")
        .append(filesChanged)
        .append(filesChanged == 1 ? " file changed, +" : " files changed, +")
        .append(additions)
        .append(" -")
        .append(deletions)
        .append("\n");
    appendDirectoryBreakdown(sb, files);
    return sb.toString();
  }

  /**
   * "directory: N files (+a -d)" rows, most files first, so a change spread over several packages
   * cannot read as a single-file edit. Bounded so a wide PR cannot crowd out the file list.
   */
  private static void appendDirectoryBreakdown(
      StringBuilder sb, List<GitHubPullRequestClient.FileDiff> files) {
    if (files.isEmpty()) {
      return;
    }
    var byDirectory = new LinkedHashMap<String, int[]>();
    for (var file : files) {
      var stats = byDirectory.computeIfAbsent(directoryOf(file.filename()), key -> new int[3]);
      stats[0]++;
      stats[1] += file.additions();
      stats[2] += file.deletions();
    }
    var rows = new ArrayList<>(byDirectory.entrySet());
    rows.sort(
        Comparator.<Map.Entry<String, int[]>>comparingInt(e -> -e.getValue()[0])
            .thenComparing(Map.Entry::getKey));
    sb.append("Directories touched: ").append(byDirectory.size()).append("\n");
    for (var row : rows.subList(0, Math.min(rows.size(), MAX_SCOPE_DIRECTORIES))) {
      sb.append("- ")
          .append(row.getKey())
          .append(": ")
          .append(row.getValue()[0])
          .append(row.getValue()[0] == 1 ? " file (+" : " files (+")
          .append(row.getValue()[1])
          .append(" -")
          .append(row.getValue()[2])
          .append(")\n");
    }
    if (rows.size() > MAX_SCOPE_DIRECTORIES) {
      sb.append("- (+").append(rows.size() - MAX_SCOPE_DIRECTORIES).append(" more directories)\n");
    }
  }

  /**
   * The file's parent directory, or a stable label for a path carrying no directory component —
   * which also covers a blank one, and a null one. The directory breakdown runs ahead of the
   * per-file rows, so this is the first place an unnamed file is dereferenced; a file with no path
   * carries no directory component either, so it lands in the same bucket rather than failing the
   * review.
   */
  private static String directoryOf(String path) {
    var slash = path == null ? -1 : path.lastIndexOf('/');
    return slash <= 0 ? "(repository root)" : path.substring(0, slash);
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
      ReviewContextLoader.ReviewContext ctx,
      DiffLineResolver lineResolver,
      DiffBudgetPlanner.BudgetPlan plan) {
    aiResponse = quoteValidator.validate(aiResponse, diff);
    aiResponse = frameworkFilter.filter(aiResponse, diff);
    aiResponse = deduplicator.dedupe(aiResponse);
    // #736: the verification call is the one review-path call that does no budget arithmetic of
    // its own, so the section the author alone sizes is bounded here before it is sent.
    aiResponse =
        findingVerificationService.verify(
            ledgerSessionId(session),
            aiResponse,
            PrContextBudget.bound(
                promptInputs.prContext(), budgetPlanner.perCallInputBudget(), tokenCounter),
            promptInputs.diff(),
            promptInputs.projectStack(),
            promptInputs.previousFindings(),
            plan::recordVerificationCoverage);
    aiResponse =
        followUpAnalyzer.dropRepliedDuplicates(
            aiResponse, ctx.priorAiResponseJsons(), ctx.inlineComments(), botIdentity);
    // #726: a novel lower-confidence hypothesis on an anchor the maintainer has already
    // dispositioned twice is a re-roll of the dice, not a finding to disposition again.
    aiResponse =
        FollowUpAnalyzer.withoutPreviouslyLitigated(
            aiResponse,
            ctx.priorAiResponses(),
            ctx.inlineComments(),
            ctx.conversationComments(),
            botIdentity);
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
              "Populating missing content anchor for finding '%s' (%s:%d)",
              LogSafe.oneLine(finding.title()), LogSafe.oneLine(finding.file()), finding.line());
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
