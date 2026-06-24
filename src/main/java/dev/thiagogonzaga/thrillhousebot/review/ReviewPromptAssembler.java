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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns a loaded {@link ReviewContextLoader.ReviewContext} into the {@link
 * AiReviewService.PromptInputs} the model is called with — fencing the diff, escaping the prose
 * slots, and assembling the trailing guidance (labels + diagram request + repository instructions)
 * into the single {@code repoInstructions} slot. Extracted from {@code ReviewOrchestrator} (#250)
 * as the pure prompt-shaping transform.
 */
@ApplicationScoped
public class ReviewPromptAssembler {

  // The command-specific guidance line(s) for the review path's repository-instructions section
  // (PromptSections.instructionsSection renders the shared header, source attribution, and escape).
  private static final String INSTRUCTIONS_GUIDANCE =
      """
      The repository maintainers have provided these additional review guidelines.
      These take precedence over default rules where they conflict.
      """;

  private final ThrillhouseConfig config;
  private final PrLabeler labeler;
  private final ReviewDiffFormatter diffFormatter;

  @Inject
  public ReviewPromptAssembler(
      ThrillhouseConfig config, PrLabeler labeler, ReviewDiffFormatter diffFormatter) {
    this.config = config;
    this.labeler = labeler;
    this.diffFormatter = diffFormatter;
  }

  AiReviewService.PromptInputs assemble(
      ReviewContextLoader.ReviewContext ctx, ReviewOrchestrator.ReviewRequest req) {
    // The diff carries the code under review, so it is enclosed in a per-review random fence and
    // passed byte-exact (no marker rewriting that would corrupt marker-handling code). The
    // smaller prose slots keep the lightweight marker neutralization as defense-in-depth.
    String fencedDiff = PromptTemplateEscaper.fence(ctx.diff());
    String escapedStack = PromptTemplateEscaper.escape(ctx.projectStack());
    // The label guidance and the repo-instructions file share the prompt's trailing
    // {{repoInstructions}} slot; the label section is escaped (it carries repo label names),
    // the instructions section escapes its own maintainer content.
    String labelGuidance = PrLabeler.buildLabelGuidance(ctx.repoLabels(), labeler.allowNewLabels());
    // The diagram request is fixed guidance (no repo content), so it needs no escaping; its
    // presence is what gates the model's walkthrough_diagram field.
    String diagramGuidance =
        config.review().diagram().enabled() ? PrReviewPrompts.DIAGRAM_REQUEST : "";
    String trailingGuidance =
        combineSections(
            combineSections(
                labelGuidance.isBlank() ? "" : PromptTemplateEscaper.escape(labelGuidance),
                diagramGuidance),
            PromptSections.instructionsSection(ctx.instructions(), INSTRUCTIONS_GUIDANCE));
    return new AiReviewService.PromptInputs(
        fencedDiff,
        PromptTemplateEscaper.escape(PromptSections.prContext(req.prTitle(), req.prDescription())),
        PromptTemplateEscaper.escape(ctx.baseComparison()),
        escapedStack,
        PromptTemplateEscaper.escape(diffFormatter.buildRelatedTests(ctx.reviewableFiles())),
        PromptTemplateEscaper.escape(ctx.previousFindings()),
        trailingGuidance);
  }

  /** Joins two optional prompt sections with a blank line, dropping any that are blank. */
  static String combineSections(String first, String second) {
    if (first.isBlank()) {
      return second;
    }
    if (second.isBlank()) {
      return first;
    }
    return first + "\n\n" + second;
  }
}
