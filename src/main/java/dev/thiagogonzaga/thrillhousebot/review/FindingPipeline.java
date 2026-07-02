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
import java.util.List;

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
   * caller builds it once and passes it in.
   */
  ReviewResponse run(
      ReviewSession session,
      AiReviewService.PromptInputs promptInputs,
      ReviewContextLoader.ReviewContext ctx,
      DiffLineResolver lineResolver) {
    if (ctx.multiCall()) {
      return runMultiCall(session, promptInputs, ctx, lineResolver);
    }
    var aiResponse = aiReviewService.review(session, promptInputs);
    return refine(
        session,
        aiResponse,
        ctx.diff(),
        promptInputs,
        ctx.priorAiResponseJsons(),
        ctx.inlineComments(),
        lineResolver);
  }

  /**
   * Map-reduce review for a large PR (#53): review each token-budgeted batch in its own blocking
   * call, union the findings, run the same dedupe + verifier chain over that union, then a single
   * summary call rolls them up into the PR-level summary and reconciles previous-findings status.
   * The passed {@code promptInputs} is reused as the shared-context template — only the diff slot
   * changes per batch, and the base comparison is dropped (it is a near-duplicate of the diff that
   * would otherwise be re-sent in every call).
   */
  private ReviewResponse runMultiCall(
      ReviewSession session,
      AiReviewService.PromptInputs promptInputs,
      ReviewContextLoader.ReviewContext ctx,
      DiffLineResolver lineResolver) {
    var batches = ctx.diffBatches();
    var allFindings = new ArrayList<ReviewResponse.Finding>();
    var combinedDiff = new StringBuilder();
    for (var i = 0; i < batches.size(); i++) {
      var batch = batches.get(i);
      var batchInputs = withDiff(promptInputs, PromptTemplateEscaper.fence(batch.text()));
      var batchResponse = aiReviewService.reviewBatch(session, batchInputs, i + 1, batches.size());
      allFindings.addAll(batchResponse.findings());
      combinedDiff.append(batch.text());
    }

    var combinedRaw = combinedDiff.toString();
    var aggregated = new ReviewResponse(allFindings, List.of(), null);
    var refined =
        refine(
            session,
            aggregated,
            combinedRaw,
            withDiff(promptInputs, PromptTemplateEscaper.fence(combinedRaw)),
            ctx.priorAiResponseJsons(),
            ctx.inlineComments(),
            lineResolver);

    var summaryInputs =
        new AiReviewService.SummaryInputs(
            promptInputs.prContext(),
            PromptTemplateEscaper.escape(findingsJson(refined.findings())),
            PromptTemplateEscaper.escape(changedFilesOverview(ctx)),
            promptInputs.previousFindings(),
            promptInputs.repoInstructions());
    var summaryResponse = aiReviewService.summarize(session, summaryInputs);

    var merged =
        new ReviewResponse(
            refined.findings(),
            summaryResponse.previousFindingsStatus(),
            summaryResponse.summary());
    persistAiResponse(session, merged);
    return merged;
  }

  /** Copies the shared prompt context, swapping the diff slot and dropping the base comparison. */
  private static AiReviewService.PromptInputs withDiff(
      AiReviewService.PromptInputs base, String diff) {
    return new AiReviewService.PromptInputs(
        diff,
        base.prContext(),
        "",
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
  private static String changedFilesOverview(ReviewContextLoader.ReviewContext ctx) {
    var sb = new StringBuilder();
    for (var file : ctx.reviewableFiles()) {
      sb.append(file.filename())
          .append(" (")
          .append(file.status())
          .append(", +")
          .append(file.additions())
          .append(" -")
          .append(file.deletions())
          .append(")\n");
    }
    for (var omitted : ctx.omittedByName()) {
      sb.append(omitted).append(" (omitted — exceeded the review call budget; not analyzed)\n");
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
