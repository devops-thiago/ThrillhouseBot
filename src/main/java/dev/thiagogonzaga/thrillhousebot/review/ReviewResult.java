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
    List<PreviousFindingStatus> previousStatuses) {
  public ReviewResult {
    findings = List.copyOf(findings);
    previousStatuses = List.copyOf(previousStatuses);
    // Check-run conclusion derivation relies on a non-null state; fall back to the
    // canonical risk mapping rather than NPE on an inconsistent record
    if (reviewState == null) {
      reviewState = ReviewState.fromHighestRisk(highestRisk);
    }
  }

  public boolean hasIssues() {
    return !findings.isEmpty();
  }

  public int totalFindings() {
    return findings.size();
  }

  public record PreviousFindingStatus(
      int id,
      String status, // resolved, unresolved, justified
      String note) {}
}
