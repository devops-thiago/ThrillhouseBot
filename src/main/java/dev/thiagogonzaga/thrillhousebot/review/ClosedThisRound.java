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
import java.util.ArrayList;
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

  private static final int NAMED_LIMIT = 10;

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
   * when the round closed nothing the list can name.
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
    var rows = new ArrayList<String>();
    rows.addAll(
        rows(result, previousFindings, ClosedFindingNames.RESOLVED, "✓ Resolved", fixedReasons));
    rows.addAll(
        rows(
            result,
            previousFindings,
            ClosedFindingNames.SUPERSEDED,
            "🗂️ Superseded",
            fixedReasons));
    if (rows.isEmpty()) {
      return "";
    }
    var sb = new StringBuilder("<details>\n<summary>Closed this round: ");
    sb.append(rows.size()).append("</summary>\n\n");
    if (commitSha != null && !commitSha.isBlank()) {
      sb.append("Checked at ").append(commitSha).append(":\n\n");
    }
    rows.stream().limit(NAMED_LIMIT).forEach(row -> sb.append(row).append('\n'));
    if (rows.size() > NAMED_LIMIT) {
      sb.append("- …and ").append(rows.size() - NAMED_LIMIT).append(" more\n");
    }
    return sb.append("\n</details>\n\n").toString();
  }

  private static List<String> rows(
      ReviewResult result,
      List<ReviewResponse.Finding> previousFindings,
      String closedAs,
      String outcome,
      Set<String> fixedReasons) {
    var previous = previousFindings == null ? List.<ReviewResponse.Finding>of() : previousFindings;
    var rows = new ArrayList<String>();
    for (var status : result.previousStatuses()) {
      if (!closedAs.equalsIgnoreCase(status.status())) {
        continue;
      }
      var earlier = status.earlierRound();
      if (earlier != null) {
        if (!earlier.namedByTheRound()) {
          rows.add(row(earlier.finding(), outcome, status.note(), fixedReasons));
        }
      } else if (status.id() >= 1 && status.id() <= previous.size()) {
        rows.add(row(previous.get(status.id() - 1), outcome, status.note(), fixedReasons));
      }
    }
    return rows;
  }

  private static String row(
      ReviewResponse.Finding finding, String outcome, String note, Set<String> fixedReasons) {
    var locator = "`" + MarkdownSafe.inlineCode(finding.file() + ":" + finding.line()) + "`";
    var title = MarkdownSafe.inline(finding.title()).replace("~", "\\~");
    var named = title.isBlank() ? locator : "~~" + title + "~~ (" + locator + ")";
    var reason =
        note != null && fixedReasons.contains(note) ? ": " + MarkdownSafe.inline(note) : "";
    return "- " + named + " " + outcome + reason;
  }
}
