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
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * The post-AI finding chain: validate quotes, dedupe, verify against the diff, drop already-replied
 * duplicates, backfill missing content anchors, and persist the response. Extracted from {@code
 * ReviewOrchestrator}; the ordering is preserved verbatim — quote validation runs before dedupe so
 * a merged finding cannot inherit a phantom quote while a verbatim sibling is discarded.
 */
@ApplicationScoped
public class FindingPipeline {

  private final AiReviewService aiReviewService;
  private final FindingQuoteValidator quoteValidator;
  private final FindingDeduplicator deduplicator;
  private final FindingVerificationService findingVerificationService;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final ObjectMapper mapper;
  private final BotIdentity botIdentity;

  @Inject
  public FindingPipeline(
      AiReviewService aiReviewService,
      FindingQuoteValidator quoteValidator,
      FindingDeduplicator deduplicator,
      FindingVerificationService findingVerificationService,
      FollowUpAnalyzer followUpAnalyzer,
      ObjectMapper mapper,
      BotIdentity botIdentity) {
    this.aiReviewService = aiReviewService;
    this.quoteValidator = quoteValidator;
    this.deduplicator = deduplicator;
    this.findingVerificationService = findingVerificationService;
    this.followUpAnalyzer = followUpAnalyzer;
    this.mapper = mapper;
    this.botIdentity = botIdentity;
  }

  /**
   * Calls the model on the assembled prompt, then runs the raw response through the full post-AI
   * chain ({@link #refine}). The {@code lineResolver} is shared with the verdict backstop, so the
   * caller builds it once and passes it in. A budgeted plan is authoritative for what the model
   * sees: even a single batch sends the planned (possibly hunk-clipped) text, never the uncapped
   * raw diff — otherwise the budget would be bypassed in exactly the oversized-file case that
   * motivated clipping (#53). The legacy uncapped {@code ctx.diff()} is only sent when budgeting is
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
    var singleInputs = promptInputs;
    var quoteSource = ctx.diff();
    if (plan.budgeted() && !plan.batches().isEmpty()) {
      var batch = plan.batches().get(0);
      // The base comparison stays: the planner counted it in the shared overhead.
      singleInputs =
          withDiff(
              promptInputs,
              PromptTemplateEscaper.fence(batch.text()),
              promptInputs.baseComparison());
      quoteSource = batch.text();
    }
    var aiResponse = aiReviewService.review(session, singleInputs);
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
   * Map-reduce review for a large PR (#53): review each token-budgeted batch in its own blocking
   * call, quote-validating and verifying each batch's findings against that batch's own in-budget
   * text (the combined diff would exceed the very budget the batches exist to respect), union the
   * results through the finishing chain, then a single summary call rolls them up into the PR-level
   * summary. Previous-findings statuses are aggregated from the batch calls — they saw the diff;
   * the code-blind summary call must not decide what was resolved. The passed {@code promptInputs}
   * is reused as the shared-context template — only the diff slot changes per batch, and the base
   * comparison is dropped (it is a near-duplicate of the diff that would otherwise be re-sent in
   * every call).
   */
  private ReviewResponse runMultiCall(
      ReviewSession session,
      AiReviewService.PromptInputs promptInputs,
      ReviewContextLoader.ReviewContext ctx,
      DiffBudgetPlanner.BudgetPlan plan,
      DiffLineResolver lineResolver) {
    var batches = plan.batches();
    var allFindings = new ArrayList<ReviewResponse.Finding>();
    var batchStatuses = new ArrayList<List<ReviewResponse.PreviousFindingStatus>>();
    for (var i = 0; i < batches.size(); i++) {
      var batch = batches.get(i);
      var batchInputs = withDiff(promptInputs, PromptTemplateEscaper.fence(batch.text()), "");
      var batchResponse = aiReviewService.reviewBatch(session, batchInputs, i + 1, batches.size());
      var validated = quoteValidator.validate(batchResponse, batch.text());
      var verified =
          findingVerificationService.verify(
              validated,
              batchInputs.diff(),
              promptInputs.projectStack(),
              promptInputs.previousFindings());
      allFindings.addAll(verified.findings());
      batchStatuses.add(batchResponse.previousFindingsStatus());
    }

    // Finishing chain over the union — quote validation and verification already ran per batch.
    var aggregated = new ReviewResponse(allFindings, mergeBatchStatuses(batchStatuses), null);
    var refined = deduplicator.dedupe(aggregated);
    refined =
        followUpAnalyzer.dropRepliedDuplicates(
            refined, ctx.priorAiResponseJsons(), ctx.inlineComments(), botIdentity);
    refined = populateMissingAnchors(refined, lineResolver);

    var summaryInputs =
        new AiReviewService.SummaryInputs(
            promptInputs.prContext(),
            PromptTemplateEscaper.escape(findingsJson(refined.findings())),
            PromptTemplateEscaper.escape(changedFilesOverview(ctx, plan.omittedFiles())),
            promptInputs.previousFindings(),
            promptInputs.repoInstructions());
    var summaryResponse = aiReviewService.summarize(session, summaryInputs);

    var merged =
        new ReviewResponse(
            refined.findings(), refined.previousFindingsStatus(), summaryResponse.summary());
    persistAiResponse(session, merged);
    return merged;
  }

  /**
   * Merges the per-batch previous-findings statuses into one verdict per prior finding id. A
   * "resolved"/"justified" claim outranks "unresolved": only the batch whose slice contains the
   * finding's file has the evidence to close it, while every other batch reports the finding
   * unresolved simply because its fix is out of that slice.
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
   */
  private static String changedFilesOverview(
      ReviewContextLoader.ReviewContext ctx, List<String> omittedByName) {
    var sb = new StringBuilder();
    var omitted = Set.copyOf(omittedByName);
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
          .append(")\n");
    }
    for (var name : omittedByName) {
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
    // Quote validation runs before dedupe so a merged finding can never inherit a phantom
    // quote from one duplicate while a verbatim sibling gets discarded
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
