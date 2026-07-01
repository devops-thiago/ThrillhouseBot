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
    int omittedFiles,
    boolean ciUnreadable,
    // False in fail-closed gate-all mode: the required-context set could not be resolved, so every
    // check is gated. The rendered CI copy then drops "required", which would misdescribe checks
    // branch protection never named (#302).
    boolean requiredContextsKnown) {
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

  /** Convenience constructor for results whose CI status was fully readable (the common case). */
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
      List<CiCheck> offendingCiChecks,
      int omittedFiles) {
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
        omittedFiles,
        false);
  }

  /**
   * Convenience constructor for results built before the required-context flag existed (and tests):
   * assumes the required set was resolved, so rendered CI copy keeps the accurate "required"
   * wording. The production path ({@code VerdictBuilder}) passes the real flag through the
   * canonical constructor.
   */
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
      List<CiCheck> offendingCiChecks,
      int omittedFiles,
      boolean ciUnreadable) {
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
        omittedFiles,
        ciUnreadable,
        true);
  }

  /** How many findings the PR summary lists under "Key Findings". */
  public static final int KEY_FINDINGS_COUNT = 5;

  public boolean hasIssues() {
    return !findings.isEmpty();
  }

  /**
   * The highest-risk findings the first-review summary comment lists under "Key Findings" (top
   * {@value #KEY_FINDINGS_COUNT} by risk). Used by {@code PrSummaryGenerator} to render that
   * section.
   */
  public List<Finding> keyFindings() {
    return findings.stream()
        .sorted((a, b) -> a.risk().compareTo(b.risk()))
        .limit(KEY_FINDINGS_COUNT)
        .toList();
  }

  /** True when CI holds approval back: a required check is offending, or CI could not be read. */
  public boolean ciHoldsApproval() {
    return !offendingCiChecks.isEmpty() || ciUnreadable;
  }

  /** True when the line budget dropped whole files, so this review covers only part of the diff. */
  public boolean truncated() {
    return omittedFiles > 0;
  }

  /** How many previous findings the model (or the backstop) still reports as unresolved. */
  public long unresolvedPreviousCount() {
    return previousStatuses.stream().filter(s -> "unresolved".equalsIgnoreCase(s.status())).count();
  }

  // A backstop-held finding can be summary-only — its flagged line was outside the diff when first
  // raised, so it was never posted as an inline comment and has no thread to reply on.
  // The guidance therefore qualifies the reply path ("where one exists") instead of promising a
  // thread that may not be there; fixing the code, or a model resolved/justified verdict, still
  // clears such a finding.
  public static String unresolvedPreviousMessage(long unresolved) {
    return String.format(
        "No new issues in this revision, but %d previous finding(s) remain unresolved — "
            + "fix them, or reply on their review thread (where one exists) with why they are"
            + " deferred.",
        unresolved);
  }

  /**
   * Banner prepended to the summary when the diff was truncated, so a reader knows the review is
   * partial — the verdict is also held back from APPROVE in that case.
   */
  public static String truncationNotice(int omittedFiles) {
    return String.format(
        "> ⚠️ **Large PR — partial review.** %d file(s) were omitted because the diff exceeded the"
            + " size budget; the findings and verdict below cover only the reviewed portion.%n%n",
        omittedFiles);
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
