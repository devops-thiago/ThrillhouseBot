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
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import java.util.Comparator;
import java.util.List;

/**
 * Names the previous findings a review body counts as still open (#1436), one row per entry of
 * {@link ReviewResult#openPreviousFindings()}, the set {@link
 * ReviewResult#unresolvedPreviousCount()} counts.
 *
 * <p>A row is the {@code path:line} and title {@link ClosedFindingNames} prints, which is what an
 * {@code @thrillhousebot resolved} comment names a finding by, followed by a link to the finding's
 * review thread or a mark saying why there is none. Unlike the closed list there is no cap on the
 * number of names: only GitHub's body length limit cuts the list, and the cut says how many rows it
 * left out. The most severe findings come first, so a cut drops the least severe.
 */
final class OpenFindingNames {

  /**
   * Opening words of the section. {@code FollowUpAnalyzer.withoutClosedFindingNames} removes the
   * section by them, so a later round never reads these rows back as new findings (#455).
   */
  static final String REVIEW_BODY_LEAD_IN = "ThrillhouseBot still lists ";

  static final String DOUBLE_CHECK_MARK = " _(double-check item, no thread)_";

  static final String NO_THREAD_MARK = " _(no review thread found)_";

  static final String NEW_THREAD_MARK = " _(thread posted with this review)_";

  /** Room kept for the line saying how many rows were left out, and for the end marker. */
  private static final int REMAINDER_RESERVE = 100 + ReadBackMarkers.PREVIOUS_END.length();

  private OpenFindingNames() {}

  /**
   * What the rows are linked from.
   *
   * @param inlineComments the pull request's inline comments as the round loaded them
   * @param postedNow the findings this review opened a thread for, which {@code inlineComments}
   *     predates
   * @param pullRequestUrl the pull request's page, which a thread anchor is appended to
   */
  record Threads(
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<Finding> postedNow,
      BotIdentity botIdentity,
      String pullRequestUrl) {}

  /**
   * The section listing {@code open}, at most {@code maxLength} characters when that leaves room
   * for its first line, or an empty string when nothing is open.
   */
  static String section(List<Finding> open, Threads threads, int maxLength) {
    if (open.isEmpty()) {
      return "";
    }
    var leadIn =
        ReadBackMarkers.PREVIOUS_START
            + "\n"
            + REVIEW_BODY_LEAD_IN
            + open.size()
            + " previous finding(s) as open:\n\n";
    var rows =
        open.stream()
            .sorted(Comparator.comparing(Finding::risk))
            .map(finding -> row(finding, threads))
            .toList();
    var all = leadIn + String.join("", rows) + ReadBackMarkers.PREVIOUS_END;
    if (all.length() <= maxLength) {
      return all;
    }
    var sb = new StringBuilder(leadIn);
    var listed = 0;
    while (sb.length() + rows.get(listed).length() + REMAINDER_RESERVE <= maxLength) {
      sb.append(rows.get(listed));
      listed++;
    }
    return sb.append("- …and ")
        .append(rows.size() - listed)
        .append(" more that do not fit within GitHub's comment length limit\n")
        .append(ReadBackMarkers.PREVIOUS_END)
        .toString();
  }

  private static String row(Finding finding, Threads threads) {
    return "- "
        + ClosedFindingNames.describe(finding.file(), finding.line(), finding.title())
        + threadReference(finding, threads)
        + "\n";
  }

  private static String threadReference(Finding finding, Threads threads) {
    var root =
        FollowUpAnalyzer.openFindingThread(
            finding, threads.inlineComments(), threads.botIdentity());
    if (root.isPresent()) {
      return " ([thread](" + threads.pullRequestUrl() + "#discussion_r" + root.get() + "))";
    }
    if (threads.postedNow().stream()
        .anyMatch(posted -> PrSummaryGenerator.reRaises(posted, finding))) {
      return NEW_THREAD_MARK;
    }
    return finding.postsInline() ? NO_THREAD_MARK : DOUBLE_CHECK_MARK;
  }
}
