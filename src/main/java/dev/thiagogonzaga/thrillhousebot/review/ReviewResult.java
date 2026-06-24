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

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/** Aggregated result of a full review orchestration. */
@RegisterForReflection
public record ReviewResult(
    List<Finding> findings,
    int criticalCount,
    int highCount,
    int mediumCount,
    int lowCount,
    RiskLevel highestRisk,
    ReviewState reviewState,
    boolean isFirstReview,
    String summaryMarkdown,
    List<PreviousFindingStatus> previousStatuses,
    List<CiCheck> offendingCiChecks,
    int omittedFiles) {
  public ReviewResult {
    findings = List.copyOf(findings);
    previousStatuses = List.copyOf(previousStatuses);
    offendingCiChecks = offendingCiChecks == null ? List.of() : List.copyOf(offendingCiChecks);
    // Check-run conclusion derivation relies on a non-null state; fall back to the
    // canonical risk mapping rather than NPE on an inconsistent record
    if (reviewState == null) {
      reviewState = ReviewState.fromHighestRisk(highestRisk);
    }
  }

  /** Convenience constructor for results with no omitted files (a complete diff). */
  public ReviewResult(
      List<Finding> findings,
      int criticalCount,
      int highCount,
      int mediumCount,
      int lowCount,
      RiskLevel highestRisk,
      ReviewState reviewState,
      boolean isFirstReview,
      String summaryMarkdown,
      List<PreviousFindingStatus> previousStatuses,
      List<CiCheck> offendingCiChecks) {
    this(
        findings,
        criticalCount,
        highCount,
        mediumCount,
        lowCount,
        highestRisk,
        reviewState,
        isFirstReview,
        summaryMarkdown,
        previousStatuses,
        offendingCiChecks,
        0);
  }

  public ReviewResult(
      List<Finding> findings,
      int criticalCount,
      int highCount,
      int mediumCount,
      int lowCount,
      RiskLevel highestRisk,
      ReviewState reviewState,
      boolean isFirstReview,
      String summaryMarkdown,
      List<PreviousFindingStatus> previousStatuses) {
    this(
        findings,
        criticalCount,
        highCount,
        mediumCount,
        lowCount,
        highestRisk,
        reviewState,
        isFirstReview,
        summaryMarkdown,
        previousStatuses,
        List.of(),
        0);
  }

  public boolean hasIssues() {
    return !findings.isEmpty();
  }

  /** True when the line budget dropped whole files, so this review covers only part of the diff. */
  public boolean truncated() {
    return omittedFiles > 0;
  }

  public int totalFindings() {
    return findings.size();
  }

  @RegisterForReflection
  public record PreviousFindingStatus(
      int id,
      String status, // resolved, unresolved, justified
      String note) {}

  @RegisterForReflection
  public record CiCheck(String name, String type, String status, String conclusion) {
    public boolean isPending() {
      return "pending".equalsIgnoreCase(status);
    }

    public boolean isFailing() {
      return "failing".equalsIgnoreCase(status);
    }
  }
}
