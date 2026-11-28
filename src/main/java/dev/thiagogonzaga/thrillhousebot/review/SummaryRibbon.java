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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The scannable block under the summary title (#1446, #1450): the verdict, the open findings by
 * severity, a few facts about the change and the first sentence of its purpose. All of it comes
 * from what the review already holds, with no model call of its own.
 */
record SummaryRibbon(boolean enabled, boolean impactLine) {

  /** Closes the ribbon, so a section added under the title can be placed after it. */
  static final String END = "<!-- /thrillhousebot:ribbon -->";

  private static final int MAX_EXCERPT = 160;

  private static final Pattern SENTENCE_END =
      Pattern.compile("[.!?](?<!\\b(?:e\\.g|i\\.e|vs|cf)\\.)(?= )");

  /** What one summary's ribbon is built from. */
  record Facts(
      ReviewResult round,
      PrSummaryGenerator.CurrentState state,
      int descriptionGaps,
      List<PrSummaryGenerator.ChangedFile> changedFiles,
      boolean large,
      String purpose) {}

  String render(Facts facts) {
    if (!enabled) {
      return "";
    }
    var round = facts.round();
    var open = facts.state().result();
    var verdict = verdict(round, open, facts.state().carried().size());
    var sb = new StringBuilder();
    sb.append(
        round.heldOnCiOnly()
            ? CiSummarySections.wrap(CiSummarySections.VERDICT, verdict)
            : verdict + "\n");
    sb.append(scorecard(open, facts.descriptionGaps())).append('\n');
    sb.append(chips(facts)).append('\n');
    if (impactLine) {
      sb.append(impact(open)).append('\n');
    }
    sb.append('\n');
    excerpt(facts.purpose()).ifPresent(sentence -> sb.append("> ").append(sentence).append("\n\n"));
    return sb.append(END).append("\n\n").toString();
  }

  /** The first line: what the review of {@code round} posts, and why it is not an approval. */
  static String verdict(ReviewResult round, ReviewResult open, int fromEarlier) {
    var parts = new ArrayList<String>();
    parts.add("**" + postedAs(round) + "**");
    if (open.criticalCount() > 0) {
      parts.add(open.criticalCount() + " critical");
    }
    if (open.highCount() > 0) {
      parts.add(open.highCount() + " high");
    }
    if (fromEarlier > 0) {
      parts.add(fromEarlier + " open from an earlier review");
    }
    if (round.heldOnCiOnly()) {
      parts.add("CI hold");
    }
    return String.join(" · ", parts);
  }

  /** A review with findings is never posted as an approval, whatever its verdict. */
  private static String postedAs(ReviewResult round) {
    if (round.reviewState() == ReviewState.REQUEST_CHANGES) {
      return "Request changes";
    }
    return round.reviewState() == ReviewState.APPROVE && !round.hasIssues() ? "Approve" : "Comment";
  }

  private static String scorecard(ReviewResult open, int descriptionGaps) {
    var line =
        new StringBuilder("🔴 Critical ")
            .append(open.criticalCount())
            .append(" · 🟠 High ")
            .append(open.highCount())
            .append(" · 🟡 Medium ")
            .append(open.mediumCount())
            .append(" · 🔵 Low ")
            .append(open.lowCount());
    var doubleCheck = open.doubleCheckFindings().size();
    if (doubleCheck > 0) {
      line.append(" · Double-check ").append(doubleCheck);
    }
    if (descriptionGaps > 0) {
      line.append(" · Description gaps ").append(descriptionGaps);
    }
    var scan =
        open.findings().stream()
            .filter(f -> SecurityScan.raisedByScan(f.title(), f.description()))
            .count();
    if (scan > 0) {
      line.append(" · Scan findings ").append(scan);
    }
    return line.toString();
  }

  private static String chips(Facts facts) {
    var chips = new ArrayList<String>();
    var tests =
        facts.changedFiles() != null
            && facts.changedFiles().stream()
                .anyMatch(f -> !f.pureRename() && ReviewDiffFormatter.isTestFile(f.path()));
    if (tests) {
      chips.add("`Tests touched`");
    }
    chips.add(facts.round().isFirstReview() ? "`First review`" : "`Follow-up`");
    if (facts.large()) {
      chips.add("`Large PR`");
    }
    return String.join(" ", chips);
  }

  /** The highest severity still open, with how many findings carry it. */
  private static String impact(ReviewResult open) {
    var counts =
        new int[] {open.criticalCount(), open.highCount(), open.mediumCount(), open.lowCount()};
    var levels = new String[] {"critical", "high", "medium", "low"};
    for (var i = 0; i < counts.length; i++) {
      if (counts[i] > 0) {
        return "Impact: **%s** (%d %s %s open)"
            .formatted(levels[i], counts[i], levels[i], counts[i] == 1 ? "finding" : "findings");
      }
    }
    return "Impact: **none** (no open findings)";
  }

  /**
   * The first sentence of {@code purpose}, cut to {@value #MAX_EXCERPT} characters; empty when that
   * is the whole of it and fits, which "What this PR does" prints right below. A first sentence
   * longer than the limit is shown cut even when it is the whole of the purpose. The period of
   * "e.g.", "i.e.", "vs." or "cf." does not end the sentence.
   */
  private static Optional<String> excerpt(String purpose) {
    if (purpose == null || purpose.isBlank()) {
      return Optional.empty();
    }
    var text = purpose.strip().replaceAll("\\s+", " ");
    var end = SENTENCE_END.matcher(text);
    var first = end.find() ? text.substring(0, end.end()) : text;
    if (first.length() > MAX_EXCERPT) {
      var cut = first.lastIndexOf(' ', MAX_EXCERPT);
      first = first.substring(0, cut > 0 ? cut : MAX_EXCERPT) + "…";
    } else if (first.equals(text)) {
      return Optional.empty();
    }
    return Optional.of(MarkdownSafe.inline(first));
  }
}
