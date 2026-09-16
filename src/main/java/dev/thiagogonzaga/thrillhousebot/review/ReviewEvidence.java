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

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;

/**
 * One review's deterministic per-finding evidence: what the finding's cited {@code path:line}
 * really holds (#650) and what the context sections the reviewer was given really say (#475).
 *
 * <p>The two resolvers are opened together because they share one thing that has to be shared — the
 * {@link EvidenceBudget}. Each attaches a small note per finding and neither is worth a cap of its
 * own; what must stay bounded is their sum, so that this material can never crowd out the diff the
 * verifier is reading. Opening them apart would leave that sum to whoever wired them up.
 *
 * @param citedLocations the round that reads the repository at the head commit
 * @param contextEvidence the round that reads this review's own context sections
 */
public record ReviewEvidence(
    CitedLocationResolver.Round citedLocations, ContextEvidenceResolver.Round contextEvidence) {

  /** Attaches nothing at all, for a caller with no repository access and no loaded context. */
  public static final ReviewEvidence NONE =
      new ReviewEvidence(CitedLocationResolver.disabled(), ContextEvidenceResolver.disabled());

  /**
   * Opens both rounds over one review, on one budget.
   *
   * @param ref the revision files are read at, normally the PR head SHA
   */
  public static ReviewEvidence forReview(
      CitedLocationResolver resolver,
      String auth,
      String owner,
      String repo,
      String ref,
      ReviewContextLoader.ReviewContext ctx) {
    var budget = new EvidenceBudget();
    return new ReviewEvidence(
        resolver.forReview(auth, owner, repo, ref, ctx.reviewableFiles(), budget),
        ContextEvidenceResolver.forReview(ctx.patchCoverage(), ctx.pathInstructions(), budget));
  }

  /**
   * Both rounds run over one call's findings, as the evidence the verifier is handed.
   *
   * <p>Call this with the findings <em>as the model raised them</em>, before {@link
   * FindingQuoteValidator} runs: that guard nulls {@code suggestion_old} on precisely the finding
   * whose quote is outside the review window, which is the finding the cited-location round exists
   * to resolve.
   */
  public FindingVerificationService.FindingEvidence forFindings(
      List<ReviewResponse.Finding> findings) {
    return new FindingVerificationService.FindingEvidence(
        citedLocations.locate(findings), contextEvidence.locate(findings));
  }
}
