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

/** Maps review outcome to GitHub PR review event type. */
public enum ReviewState {
  APPROVE, // No issues → green check
  REQUEST_CHANGES, // Critical or High issues → blocks merge
  COMMENT; // Medium or Low only → neutral

  /**
   * Derives the review event under {@link BlockingStrictness#BALANCED}: only critical/high findings
   * the model is highly confident in block the merge. Prefer {@link #fromFindings(List,
   * BlockingStrictness)} when the operator-configured mode is available.
   */
  public static ReviewState fromFindings(List<Finding> findings) {
    return fromFindings(findings, BlockingStrictness.BALANCED);
  }

  /**
   * Derives the review event from the full finding list under the given blocking mode. Findings
   * that do not meet the mode's severity/confidence bar still surface as comments, so speculative
   * claims never fail the check run unless the operator opted into {@link
   * BlockingStrictness#STRICT}.
   */
  public static ReviewState fromFindings(List<Finding> findings, BlockingStrictness strictness) {
    if (findings == null || findings.isEmpty()) {
      return APPROVE;
    }
    var mode = strictness == null ? BlockingStrictness.BALANCED : strictness;
    return findings.stream().anyMatch(mode::isBlocking) ? REQUEST_CHANGES : COMMENT;
  }

  public static ReviewState fromHighestRisk(RiskLevel highestRisk) {
    if (highestRisk == null) return APPROVE;
    return switch (highestRisk) {
      case CRITICAL, HIGH -> REQUEST_CHANGES;
      case MEDIUM, LOW -> COMMENT;
    };
  }

  /** GitHub check-run conclusion for this review outcome. */
  public String checkRunConclusion() {
    return switch (this) {
      case APPROVE -> "success";
      case REQUEST_CHANGES -> "failure";
      case COMMENT -> "neutral";
    };
  }
}
