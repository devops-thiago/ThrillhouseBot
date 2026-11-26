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

/**
 * The parts of a summary comment that describe a CI hold (#1462). A held verdict's summary wraps
 * them in hidden markers, so the CI completion that releases or keeps the hold can rewrite them
 * without the review result the summary was rendered from.
 */
final class CiSummarySections {

  static final String HOLD = "ci-hold";
  static final String CHECKS = "ci-checks";
  static final String AGENT_CI = "ci-agent";

  private CiSummarySections() {}

  /** {@code content} between the markers of section {@code name}. */
  static String wrap(String name, String content) {
    return start(name) + "\n" + content.stripTrailing() + "\n" + end(name) + "\n\n";
  }

  /**
   * {@code body} with its marked sections showing {@code ci}: the approval when it approves, else
   * what still holds it. A body without markers comes back unchanged.
   */
  static String refresh(String body, ReviewResult ci) {
    var approved = ci.reviewState() == ReviewState.APPROVE;
    var hold = approved ? approvedLine() : PrSummaryGenerator.ciHoldText(ci);
    var checks = approved ? greenChecks(ci) : PrSummaryGenerator.ciChecksText(ci);
    var summary = replace(replace(body, HOLD, hold), CHECKS, checks);
    return replace(summary, AGENT_CI, AgentNextSteps.heldCiText(ci));
  }

  private static String approvedLine() {
    return "No new issues found in this PR. CI has since gone green, so the held approval was"
        + " posted without another code review.";
  }

  private static String greenChecks(ReviewResult ci) {
    return ci.requiredContextsKnown()
        ? "### ✅ Required CI Checks Status\nAll required checks are green."
        : "### ✅ CI Checks Status\nAll checks are green.";
  }

  /**
   * Replaces what the last marker pair of {@code name} encloses. The opening marker counts only as
   * a whole line and the closing one only at the start of a line, and the last pair wins, so text
   * quoted earlier in the summary cannot stand in for it.
   */
  private static String replace(String body, String name, String content) {
    var opening = "\n" + start(name) + "\n";
    var from = body.lastIndexOf(opening);
    if (from < 0) {
      return body;
    }
    var contentFrom = from + opening.length();
    var to = body.indexOf("\n" + end(name), contentFrom);
    if (to < 0) {
      return body;
    }
    return body.substring(0, contentFrom) + content.stripTrailing() + body.substring(to);
  }

  private static String start(String name) {
    return "<!-- thrillhousebot:" + name + " -->";
  }

  private static String end(String name) {
    return "<!-- /thrillhousebot:" + name + " -->";
  }
}
