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
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
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
 * ReviewOrchestrator} (#250); the ordering is preserved verbatim — quote validation runs before
 * dedupe so a merged finding cannot inherit a phantom quote while a verbatim sibling is discarded.
 */
@ApplicationScoped
public class FindingPipeline {

  private final FindingQuoteValidator quoteValidator;
  private final FindingDeduplicator deduplicator;
  private final FindingVerificationService findingVerificationService;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final ObjectMapper mapper;
  private final BotIdentity botIdentity;

  @Inject
  public FindingPipeline(
      FindingQuoteValidator quoteValidator,
      FindingDeduplicator deduplicator,
      FindingVerificationService findingVerificationService,
      FollowUpAnalyzer followUpAnalyzer,
      ObjectMapper mapper,
      ThrillhouseConfig config) {
    this.quoteValidator = quoteValidator;
    this.deduplicator = deduplicator;
    this.findingVerificationService = findingVerificationService;
    this.followUpAnalyzer = followUpAnalyzer;
    this.mapper = mapper;
    this.botIdentity = BotIdentity.from(config.github().botLogins());
  }

  /**
   * Runs the raw model response through the full post-AI chain and persists it. The {@code
   * lineResolver} is shared with the caller's verdict backstop, so it is passed in rather than
   * built here.
   */
  ReviewResponse refine(
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
