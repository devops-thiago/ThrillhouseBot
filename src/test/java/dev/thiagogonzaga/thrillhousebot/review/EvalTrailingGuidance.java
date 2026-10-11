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

import java.util.List;

/**
 * The review call's trailing guidance for one eval case, built by the same section methods {@link
 * ReviewPromptAssembler} and {@link FindingPipeline} use, in the same order, so the live prompt
 * eval sends what a review of the same diff would (#979).
 *
 * <p>The eval used to add only the mock-fidelity and patch-coverage requests. A review also adds
 * the heuristic failure-mode request to every batch whose added lines introduce a parsing, regex or
 * matching rule ({@code FindingPipeline.BatchPrompts.heuristicFailureModesFor}), and the bug-fix
 * efficacy request when the pull request says it fixes a bug. Leaving them out measured a prompt
 * production never sends: {@code pr100-no-newline-indicator-must-find}, whose defect is exactly "a
 * line-oriented rule meets a line with no trailing newline", was judged without the section that
 * names that probe.
 */
public final class EvalTrailingGuidance {

  private EvalTrailingGuidance() {}

  /**
   * The changed test files among {@code paths}, newline-separated, as the review call lists them.
   */
  public static String relatedTests(List<String> paths) {
    return String.join("\n", paths.stream().filter(ReviewDiffFormatter::isTestFile).toList());
  }

  /**
   * The sections a review of {@code diff} would carry, joined as the assembler joins them.
   *
   * @param diff the case's diff as the pipeline renders it ({@code ### path (status, ...)} headers)
   * @param relatedTests the changed test files, newline-separated, as the assembler lists them
   * @param prDescription the pull request body
   * @param patchCoverage the rendered uncovered-lines section, or blank
   * @param learnings the stored maintainer decisions the review recalls, or blank
   */
  public static String forCase(
      String diff,
      String relatedTests,
      String prDescription,
      String patchCoverage,
      String learnings) {
    return ReviewPromptAssembler.combineSections(
        ReviewPromptAssembler.combineSections(
            ReviewPromptAssembler.combineSections(
                ReviewPromptAssembler.mockFidelitySection(relatedTests),
                ReviewPromptAssembler.bugFixEfficacySection(prDescription, "")),
            ReviewPromptAssembler.combineSections(
                ReviewPromptAssembler.heuristicFailureModesSection(
                    FindingPipeline.heuristicScanSource(diff)),
                ReviewPromptAssembler.patchCoverageSection(patchCoverage))),
        ReviewPromptAssembler.learningsSection(learnings));
  }
}
