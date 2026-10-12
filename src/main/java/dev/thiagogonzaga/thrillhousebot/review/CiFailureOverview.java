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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The summary overview's sentence on each failing CI check, computed rather than written by the
 * summary call (#1075).
 *
 * <p>The call was asked to say, for each failing check, which finding explains it or that none
 * does, and it wrote "CI on this commit fails the check 'test' (failing test 'returns exactly
 * `slots` applications…'), which no finding among the three verified above explains" over a summary
 * whose Key Findings listed the finding that names that test. The call sees only this round's
 * findings, and an explaining finding still open from an earlier round is not among them. So the
 * overview's sentences of that kind are removed, and one sentence per failing check is added from
 * {@link CiFailureCitations#overviewSentences}, over every finding the summary lists. It runs when
 * the summary is rendered, so a carried overview loses a stale sentence too.
 */
final class CiFailureOverview {

  /**
   * A sentence tying a CI failure to the findings: it speaks of a finding explaining something, and
   * of CI, a check or a failing test. A sentence on what the change does names neither pair.
   */
  private static final Pattern EXPLAINS =
      Pattern.compile("\\bexplain(?:s|ed|ing)?\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern FINDING =
      Pattern.compile("\\bfindings?\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern CI_FAILURE =
      Pattern.compile("\\b(?:CI|checks?|failing tests?)\\b", Pattern.CASE_INSENSITIVE);

  private CiFailureOverview() {}

  /**
   * {@code summary} with the call's own sentences on whether a finding explains a CI failure
   * removed from its assessment and purpose, and the computed sentences appended to the purpose
   * when it has one. The same instance when nothing changed; {@code null} stays {@code null}.
   *
   * @param open the findings the summary lists: this round's, then the earlier rounds' still open
   */
  static ReviewResponse.Summary apply(
      ReviewResponse.Summary summary, CiFailureCitations citations, List<Finding> open) {
    if (summary == null) {
      return null;
    }
    var assessment = withoutCiVerdicts(summary.overallAssessment());
    var purpose = withoutCiVerdicts(summary.prPurpose());
    var sentences = citations.overviewSentences(open);
    if (purpose != null && !purpose.isBlank() && !sentences.isEmpty()) {
      purpose = purpose.strip() + " " + sentences;
    }
    if (Objects.equals(assessment, summary.overallAssessment())
        && Objects.equals(purpose, summary.prPurpose())) {
      return summary;
    }
    return new ReviewResponse.Summary(
        summary.totalFindings(),
        summary.critical(),
        summary.high(),
        summary.medium(),
        summary.low(),
        assessment,
        purpose,
        summary.descriptionGaps(),
        summary.suggestedLabels(),
        summary.fileSummaries(),
        summary.walkthroughDiagram(),
        summary.addressedGaps());
  }

  /** {@code text} without its sentences on whether a finding explains a CI failure. */
  static String withoutCiVerdicts(String text) {
    if (text == null || !EXPLAINS.matcher(text).find()) {
      return text;
    }
    var sentences = OverviewScreen.sentences(text);
    var kept = sentences.stream().filter(s -> !isCiVerdict(s)).toList();
    return kept.size() == sentences.size() ? text : String.join(" ", kept);
  }

  private static boolean isCiVerdict(String sentence) {
    return EXPLAINS.matcher(sentence).find()
        && FINDING.matcher(sentence).find()
        && CI_FAILURE.matcher(sentence).find();
  }
}
