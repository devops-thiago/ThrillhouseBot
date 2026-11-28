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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Set;

/**
 * The summary comment's folded list of the findings this round closed (#1449): each one's title
 * struck through, its {@code path:line}, how it closed and the commit the round reviewed.
 *
 * <p>It names the same findings as {@link ClosedFindingNames}, so the list, the review body and the
 * follow-up delta cannot disagree. A reason is shown only when it is one of the bot's own fixed
 * sentences: the note on a status the model reported is model text and is left out. The summary is
 * rewritten every round, so the list covers the latest round only.
 *
 * <p>{@link PrSummaryGenerator} marks the place with {@link #ANCHOR} and {@link ReviewPublisher},
 * which knows the commit, replaces it.
 */
final class ClosedThisRound {

  /** Where the list goes. Model text cannot spell it: every {@code <} it carries is escaped. */
  static final String ANCHOR = "<!-- thrillhousebot:closed-this-round -->\n\n";

  private ClosedThisRound() {}

  /** Whether the round closed a finding the list can name. */
  static boolean any(ReviewResult result, List<ReviewResponse.Finding> previousFindings) {
    return !ClosedFindingNames.closed(result, previousFindings, ClosedFindingNames.RESOLVED)
            .isEmpty()
        || !ClosedFindingNames.closed(result, previousFindings, ClosedFindingNames.SUPERSEDED)
            .isEmpty();
  }

  /**
   * The folded list, resolved findings before superseded ones, ending with a blank line; {@code ""}
   * when the round closed nothing the list can name. The count in its header and the cap of {@link
   * ClosedFindingNames#NAMED_LIMIT} per status are the review body's.
   */
  static String render(
      ReviewResult result,
      List<ReviewResponse.Finding> previousFindings,
      String commitSha,
      BotIdentity botIdentity) {
    var fixedReasons =
        Set.of(
            FollowUpAnalyzer.FIX_LANDED_NOTE,
            FollowUpAnalyzer.REPLACEMENT_LANDED_NOTE,
            FollowUpAnalyzer.MODEL_RESOLVED_NOTE,
            FollowUpAnalyzer.SUPERSEDED_NOTE,
            FollowUpAnalyzer.conversationClearedNote(botIdentity),
            FollowUpAnalyzer.threadClearedNote(botIdentity),
            SecurityScan.NO_LONGER_DETECTED_NOTE);
    var rows =
        rows(
                ClosedFindingNames.closures(result, previousFindings, ClosedFindingNames.RESOLVED),
                "✓ Resolved",
                fixedReasons)
            + rows(
                ClosedFindingNames.closures(
                    result, previousFindings, ClosedFindingNames.SUPERSEDED),
                "🗂️ Superseded",
                fixedReasons);
    if (rows.isEmpty()) {
      return "";
    }
    var sb = new StringBuilder("<details>\n<summary>Closed this round: ");
    sb.append(result.resolvedPreviousCount() + result.supersededPreviousCount())
        .append("</summary>\n\n");
    if (commitSha != null && !commitSha.isBlank()) {
      sb.append("Checked at ").append(commitSha).append(":\n\n");
    }
    return sb.append(rows).append("\n</details>\n\n").toString();
  }

  private static String rows(
      List<ClosedFindingNames.Closure> closures, String outcome, Set<String> fixedReasons) {
    var sb = new StringBuilder();
    closures.stream()
        .limit(ClosedFindingNames.NAMED_LIMIT)
        .forEach(c -> sb.append(row(c.finding(), outcome, c.note(), fixedReasons)).append('\n'));
    if (closures.size() > ClosedFindingNames.NAMED_LIMIT) {
      sb.append("- …and ")
          .append(closures.size() - ClosedFindingNames.NAMED_LIMIT)
          .append(" more\n");
    }
    return sb.toString();
  }

  /** Backslashes are doubled first, so that none of the title's own can eat a closing tilde. */
  private static String row(
      ReviewResponse.Finding finding, String outcome, String note, Set<String> fixedReasons) {
    var locator = "`" + MarkdownSafe.inlineCode(finding.file() + ":" + finding.line()) + "`";
    var title =
        MarkdownSafe.inline(MarkdownSafe.oneLine(finding.title()).replace("\\", "\\\\"))
            .replace("~", "\\~");
    var named = title.isBlank() ? locator : "~~" + title + "~~ (" + locator + ")";
    var reason =
        note != null && fixedReasons.contains(note) ? ": " + MarkdownSafe.inline(note) : "";
    return "- " + named + " " + outcome + reason;
  }
}
