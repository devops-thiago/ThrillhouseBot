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
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Generates the PR summary comment posted on the first review. */
@ApplicationScoped
public class PrSummaryGenerator {

  /** The clean-review celebration; rendered inside the summary, never as a separate comment. */
  public static final String ZERO_ISSUES_MESSAGE =
      "Everything's coming up Thrillhouse! 🎉\n\nNo issues found in this PR.";

  public String generate(
      int filesChanged,
      int additions,
      int deletions,
      ReviewResponse.Summary aiSummary,
      ReviewResult result) {
    var sb = new StringBuilder();
    sb.append("## 🤖 ThrillhouseBot PR Summary\n\n");

    appendPrPurpose(sb, aiSummary);
    appendDescriptionGaps(sb, aiSummary);

    sb.append("### Changes Overview\n");
    sb.append("- **Files changed:** ").append(filesChanged).append("\n");
    sb.append("- **Lines added:** ").append(signed('+', additions)).append("\n");
    sb.append("- **Lines removed:** ").append(signed('-', deletions)).append("\n\n");

    sb.append("### Risk Assessment\n");
    sb.append("| Risk | Count |\n");
    sb.append("|------|-------|\n");
    sb.append("| 🔴 Critical | ").append(result.criticalCount()).append(" |\n");
    sb.append("| 🟠 High | ").append(result.highCount()).append(" |\n");
    sb.append("| 🟡 Medium | ").append(result.mediumCount()).append(" |\n");
    sb.append("| 🔵 Low | ").append(result.lowCount()).append(" |\n\n");

    if (result.previousStatuses() != null && !result.previousStatuses().isEmpty()) {
      sb.append("### Previous Findings Status\n");
      sb.append("| Status | Count |\n");
      sb.append("|--------|-------|\n");
      var resolved =
          result.previousStatuses().stream().filter(s -> "resolved".equals(s.status())).count();
      var unresolved =
          result.previousStatuses().stream().filter(s -> "unresolved".equals(s.status())).count();
      var justified =
          result.previousStatuses().stream().filter(s -> "justified".equals(s.status())).count();
      sb.append("| ✅ Resolved | ").append(resolved).append(" |\n");
      sb.append("| ⚠️ Still present | ").append(unresolved).append(" |\n");
      sb.append("| 💬 Justified | ").append(justified).append(" |\n\n");
    }

    if (result.hasIssues()) {
      sb.append("### Key Findings\n");
      List<Finding> topFindings =
          result.findings().stream()
              .sorted((a, b) -> a.risk().compareTo(b.risk()))
              .limit(5)
              .toList();
      for (Finding f : topFindings) {
        sb.append("- **")
            .append(f.risk().name())
            .append(":** ")
            .append(f.title())
            .append(" (`")
            .append(f.file())
            .append(":")
            .append(f.line())
            .append("`)\n");
      }
      sb.append("\n");
    } else if (hasNoUnresolvedPrevious(result)) {
      sb.append(ZERO_ISSUES_MESSAGE).append("\n\n");
    }

    sb.append("---\n");
    sb.append("*Automated review by ThrillhouseBot. Reply with `/review` to re-run.*\n");

    return sb.toString();
  }

  /** A clean review celebrates only when nothing from earlier rounds is still unresolved. */
  private static boolean hasNoUnresolvedPrevious(ReviewResult result) {
    // The record constructor guarantees a non-null status list
    return result.previousStatuses().stream()
        .noneMatch(s -> "unresolved".equalsIgnoreCase(s.status()));
  }

  private static void appendPrPurpose(StringBuilder sb, ReviewResponse.Summary aiSummary) {
    if (aiSummary == null || aiSummary.prPurpose() == null || aiSummary.prPurpose().isBlank()) {
      return;
    }
    sb.append("### What this PR does\n");
    sb.append(aiSummary.prPurpose().strip()).append("\n\n");
  }

  private static void appendDescriptionGaps(StringBuilder sb, ReviewResponse.Summary aiSummary) {
    if (aiSummary == null) {
      return;
    }
    // List.copyOf in the Summary constructor guarantees no null elements
    List<String> gaps = aiSummary.descriptionGaps().stream().filter(g -> !g.isBlank()).toList();
    if (gaps.isEmpty()) {
      return;
    }
    sb.append("### ⚠️ Description vs. Implementation\n");
    sb.append("The PR description does not fully match the change:\n");
    for (String gap : gaps) {
      sb.append("- ").append(gap.strip()).append("\n");
    }
    sb.append("\n");
  }

  /** Renders a signed line count, avoiding the awkward "+0"/"-0". */
  private static String signed(char sign, int count) {
    return count == 0 ? "0" : sign + Integer.toString(count);
  }
}
