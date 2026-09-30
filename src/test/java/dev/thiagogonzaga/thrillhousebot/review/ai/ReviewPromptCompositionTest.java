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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * How the review and verifier system prompts are assembled from their blocks (#665): the monolith
 * is exactly the blocks, a routed prompt carries exactly the blocks its dimensions call for, the
 * core is a byte-stable prefix of every routed prompt, and a smaller set never yields a larger
 * prompt. The pins on what each block says live in {@link PrReviewPromptsContentTest} and {@link
 * FindingVerifierPromptsContentTest}.
 */
class ReviewPromptCompositionTest {

  private static final List<ReviewDimension> ROUTED =
      List.of(ReviewDimension.values()).stream().filter(d -> !d.alwaysOn()).toList();

  /** Every subset of the routed dimensions, each with the always-on ones. */
  private static List<Set<ReviewDimension>> everyRouting() {
    var sets = new ArrayList<Set<ReviewDimension>>();
    for (int mask = 0; mask < 1 << ROUTED.size(); mask++) {
      var set = EnumSet.noneOf(ReviewDimension.class);
      for (int bit = 0; bit < ROUTED.size(); bit++) {
        if ((mask & (1 << bit)) != 0) {
          set.add(ROUTED.get(bit));
        }
      }
      sets.add(set);
    }
    return sets;
  }

  private static final String CORE_PREFIX =
      PrReviewPrompts.CORE_IDENTITY
          + PrReviewPrompts.CORE_FINDING_FIELDS_AND_SEVERITY
          + PrReviewPrompts.CORE_SELF_CHECK
          + "Review dimensions:\n"
          + PrReviewPrompts.DIMENSION_FUNCTIONAL_CORRECTNESS
          + PrReviewPrompts.DIMENSION_SECURITY
          + PrReviewPrompts.DIMENSION_REGRESSIONS;

  @Test
  void theMonolithHoldsEveryDimensionOnceInNumberOrder() {
    var at = -1;
    for (var dimension : ReviewDimension.values()) {
      var block = PrReviewPrompts.dimensionBlock(dimension);
      assertTrue(
          block.startsWith(dimension.number() + ". "),
          "block " + dimension + " must open with its number");
      var index = PrReviewPrompts.SYSTEM.indexOf(block);
      assertTrue(index > at, dimension + " out of order or missing");
      assertEquals(index, PrReviewPrompts.SYSTEM.lastIndexOf(block), dimension + " twice");
      at = index;
    }
    assertTrue(PrReviewPrompts.SYSTEM.contains(PrReviewPrompts.ANCHORED_INFRASTRUCTURE_CLASSES));
    assertFalse(PrReviewPrompts.SYSTEM.contains(PrReviewPrompts.ROUTED_OUT_NOTE));
  }

  @Test
  void aRoutedPromptCarriesExactlyTheBlocksItsDimensionsCallFor() {
    for (var dimensions : everyRouting()) {
      var prompt = PrReviewPrompts.reviewSystemPrompt(dimensions);
      for (var dimension : ReviewDimension.values()) {
        assertEquals(
            dimension.alwaysOn() || dimensions.contains(dimension),
            prompt.contains(PrReviewPrompts.dimensionBlock(dimension)),
            dimension + " in " + dimensions);
      }
      assertEquals(
          dimensions.contains(ReviewDimension.CONFIG_IAC),
          prompt.contains(PrReviewPrompts.ANCHORED_INFRASTRUCTURE_CLASSES),
          "the anchored classes travel with config/IaC: " + dimensions);
      assertEquals(
          !dimensions.containsAll(ROUTED),
          prompt.contains(PrReviewPrompts.ROUTED_OUT_NOTE),
          "the note appears exactly when something was left out: " + dimensions);
      assertTrue(prompt.endsWith(PrReviewPrompts.FINDINGS_RESPONSE_CONTRACT), "" + dimensions);
    }
  }

  @Test
  void theAlwaysOnDimensionsCannotBeRoutedOut() {
    var prompt = PrReviewPrompts.reviewSystemPrompt(Set.of());
    assertTrue(prompt.contains(PrReviewPrompts.DIMENSION_FUNCTIONAL_CORRECTNESS));
    assertTrue(prompt.contains(PrReviewPrompts.DIMENSION_SECURITY));
    assertTrue(prompt.contains(PrReviewPrompts.DIMENSION_REGRESSIONS));
  }

  @Test
  void theCoreIsAByteStablePrefixOfEveryRoutedPrompt() {
    // What a provider's prefix cache matches across the batches of a review: every routed prompt
    // must open with the whole core, byte for byte, whatever the batch's files.
    for (var dimensions : everyRouting()) {
      assertTrue(
          PrReviewPrompts.reviewSystemPrompt(dimensions).startsWith(CORE_PREFIX), "" + dimensions);
      assertEquals(
          PrReviewPrompts.reviewSystemPrompt(dimensions),
          PrReviewPrompts.reviewSystemPrompt(EnumSet.copyOf(dimensions)),
          "the same set must render the same prompt");
    }
    var verifierPrefix =
        FindingVerifierPrompts.CORE_HEAD
            + FindingVerifierPrompts.CORE_HEURISTIC_LIMITATION
            + FindingVerifierPrompts.CORE_INJECTION_SINK
            + FindingVerifierPrompts.CORE_TAIL;
    for (var dimensions : everyRouting()) {
      assertTrue(
          FindingVerifierPrompts.verifierSystemPrompt(dimensions).startsWith(verifierPrefix),
          "" + dimensions);
    }
  }

  @Test
  void aSmallerSetNeverRendersALargerPrompt() {
    // The planner sizes every batch from the pull request's prompt; a batch routes a subset of the
    // pull request's dimensions, so this is what keeps a batch from overshooting its budget.
    var tokens = new TokenCounter();
    for (var superset : everyRouting()) {
      var large = tokens.estimateTokens(PrReviewPrompts.reviewSystemPrompt(superset));
      for (var dimension : superset) {
        var subset = EnumSet.copyOf(superset);
        subset.remove(dimension);
        assertTrue(
            tokens.estimateTokens(PrReviewPrompts.reviewSystemPrompt(subset)) <= large,
            subset + " vs " + superset);
      }
    }
  }

  @Test
  void theVerifierMonolithIsItsBlocksAndARoutedVerifierCarriesItsCarveOuts() {
    assertEquals(
        FindingVerifierPrompts.CORE_HEAD
            + FindingVerifierPrompts.CARVE_OUT_MOCK_FIDELITY
            + FindingVerifierPrompts.CORE_HEURISTIC_LIMITATION
            + FindingVerifierPrompts.CARVE_OUT_CONFIG_KEY_DOCUMENTATION
            + FindingVerifierPrompts.CORE_INJECTION_SINK
            + FindingVerifierPrompts.CARVE_OUT_ARTIFACT_REFERENCE
            + FindingVerifierPrompts.CARVE_OUT_PRODUCER_CONSUMER
            + FindingVerifierPrompts.CORE_TAIL
            + FindingVerifierPrompts.RESPONSE_SCHEMA,
        FindingVerifierPrompts.SYSTEM);
    for (var dimensions : everyRouting()) {
      var prompt = FindingVerifierPrompts.verifierSystemPrompt(dimensions);
      assertEquals(
          dimensions.contains(ReviewDimension.MOCK_FIDELITY),
          prompt.contains(FindingVerifierPrompts.CARVE_OUT_MOCK_FIDELITY));
      assertEquals(
          dimensions.contains(ReviewDimension.CONFIG_KEY_DOCUMENTATION),
          prompt.contains(FindingVerifierPrompts.CARVE_OUT_CONFIG_KEY_DOCUMENTATION));
      assertEquals(
          dimensions.contains(ReviewDimension.CONFIG_IAC),
          prompt.contains(FindingVerifierPrompts.CARVE_OUT_ARTIFACT_REFERENCE));
      assertEquals(
          dimensions.contains(ReviewDimension.PRODUCER_CONSUMER),
          prompt.contains(FindingVerifierPrompts.CARVE_OUT_PRODUCER_CONSUMER));
      assertTrue(prompt.endsWith(FindingVerifierPrompts.RESPONSE_SCHEMA));
    }
  }

  @Test
  void routingOutBlocksShrinksThePrompt() {
    // The measured saving the issue asks for, pinned loosely: a documentation-only call drops five
    // blocks and must come in well under the monolith.
    var tokens = new TokenCounter();
    var full = tokens.estimateTokens(PrReviewPrompts.SYSTEM);
    var docsOnly =
        tokens.estimateTokens(
            PrReviewPrompts.reviewSystemPrompt(
                Set.of(
                    ReviewDimension.COMMENT_CONTRADICTS_CODE,
                    ReviewDimension.CONFIG_KEY_DOCUMENTATION)));
    assertTrue(docsOnly < full * 0.8, docsOnly + " of " + full);
    assertTrue(
        Math.abs(
                tokens.estimateTokens(PrReviewPrompts.reviewSystemPrompt(ReviewDimension.ALL))
                    - full)
            < 5,
        "every dimension routed must cost what the monolith does");
  }
}
