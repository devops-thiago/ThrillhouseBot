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
import java.util.ArrayList;
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
    // False when the required-context set could not be resolved; rendered CI copy then drops
    // "required".
    boolean requiredContextsKnown,
    TruncationDetail truncation) {
  public ReviewResult {
    findings = List.copyOf(findings);
    previousStatuses = List.copyOf(previousStatuses);
    offendingCiChecks = offendingCiChecks == null ? List.of() : List.copyOf(offendingCiChecks);
    truncation = truncation == null ? TruncationDetail.EMPTY : truncation;
    // Check-run conclusion derivation relies on a non-null state.
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
        true,
        TruncationDetail.EMPTY);
  }

  /**
   * Which files the token budget left uncovered, by name: {@code omittedFileNames} were never sent
   * at all, {@code clippedFileNames} were hunk-clipped and only partially analyzed. Empty on the
   * legacy line-cap path, where only a count is known — the rendered copy then falls back to the
   * numeric clause.
   */
  @RegisterForReflection
  public record TruncationDetail(List<String> omittedFileNames, List<String> clippedFileNames) {
    public static final TruncationDetail EMPTY = new TruncationDetail(List.of(), List.of());

    public TruncationDetail {
      omittedFileNames = omittedFileNames == null ? List.of() : List.copyOf(omittedFileNames);
      clippedFileNames = clippedFileNames == null ? List.of() : List.copyOf(clippedFileNames);
    }

    public boolean isEmpty() {
      return omittedFileNames.isEmpty() && clippedFileNames.isEmpty();
    }
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

  // A backstop-held finding may have no inline thread (its line was outside the diff when raised),
  // hence the "where one exists" qualifier.
  public static String unresolvedPreviousMessage(long unresolved) {
    return String.format(
        "No new issues in this revision, but %d previous finding(s) remain unresolved — "
            + "fix them, or reply on their review thread (where one exists) with why they are"
            + " deferred.",
        unresolved);
  }

  /**
   * The shared "N file(s) were omitted …" clause, so the review banner and the on-demand-command
   * disclosure never drift on the omitted count and the reason — only the surrounding framing
   * differs between the two surfaces.
   */
  private static String omittedFilesClause(int omittedFiles) {
    return String.format(
        "%d file(s) were omitted because the diff exceeded the size budget", omittedFiles);
  }

  /**
   * Banner prepended to the summary when the diff was truncated, so a reader knows the review is
   * partial — the verdict is also held back from APPROVE in that case.
   */
  public static String truncationNotice(int omittedFiles) {
    return truncationNotice(omittedFiles, TruncationDetail.EMPTY);
  }

  /**
   * Banner variant that names the uncovered files when the token-budget plan knows them — "reported
   * by name, never silently dropped" must hold on the user-facing surfaces, not only in the model's
   * prompt. Falls back to the numeric clause when only a count is known.
   */
  public static String truncationNotice(int omittedFiles, TruncationDetail detail) {
    return String.format(
        "> ⚠️ **Large PR — partial review.** %s; the findings and verdict below cover only the"
            + " reviewed portion.%n%n",
        coverageGapClause(omittedFiles, detail));
  }

  /** The truncation banner for this result, carrying the uncovered-file names when known. */
  public String truncationNotice() {
    return truncationNotice(omittedFiles, truncation);
  }

  /**
   * "N file(s) omitted entirely (a.java, …) and M file(s) only partially analyzed (b.java, …)" — or
   * the legacy numeric clause when no names are known. Shared by the review banner and the
   * check-run summary so the two surfaces never drift.
   */
  static String coverageGapClause(int omittedFiles, TruncationDetail detail) {
    if (detail == null || detail.isEmpty()) {
      return omittedFilesClause(omittedFiles);
    }
    var parts = new ArrayList<String>(2);
    if (!detail.omittedFileNames().isEmpty()) {
      parts.add(
          String.format(
              "%d file(s) were omitted entirely (%s)",
              detail.omittedFileNames().size(), nameList(detail.omittedFileNames())));
    }
    if (!detail.clippedFileNames().isEmpty()) {
      parts.add(
          String.format(
              "%d file(s) were only partially analyzed (%s)",
              detail.clippedFileNames().size(), nameList(detail.clippedFileNames())));
    }
    return String.join(" and ", parts) + " because the diff exceeded the review budget";
  }

  /** This result's coverage-gap clause, for surfaces that already hold the record. */
  public String coverageGapClause() {
    return coverageGapClause(omittedFiles, truncation);
  }

  /**
   * Compact per-class counts for the check-run summary: "2 file(s) omitted, 1 partially analyzed" —
   * a clipped file was analyzed in part, so calling it "omitted" would misstate the coverage. Falls
   * back to the plain omitted count when no names are known (legacy line cap).
   */
  public String coverageGapBrief() {
    if (truncation.isEmpty()) {
      return String.format("%d file(s) omitted", omittedFiles);
    }
    var parts = new ArrayList<String>(2);
    if (!truncation.omittedFileNames().isEmpty()) {
      parts.add(String.format("%d file(s) omitted", truncation.omittedFileNames().size()));
    }
    if (!truncation.clippedFileNames().isEmpty()) {
      parts.add(
          String.format("%d file(s) partially analyzed", truncation.clippedFileNames().size()));
    }
    return String.join(", ", parts);
  }

  /** How many names the rendered copy lists per class before rolling the rest up as a count. */
  private static final int NAMED_FILES_LIMIT = 10;

  private static String nameList(List<String> names) {
    if (names.size() <= NAMED_FILES_LIMIT) {
      return String.join(", ", names);
    }
    return String.join(", ", names.subList(0, NAMED_FILES_LIMIT))
        + ", +"
        + (names.size() - NAMED_FILES_LIMIT)
        + " more";
  }

  /**
   * Partial-coverage disclosure appended to an on-demand command's comment ({@code /describe},
   * {@code /changelog}, {@code /add-docs}) when the diff was truncated, or an empty string when
   * nothing was omitted. Shares the omitted-file clause with {@link #truncationNotice(int)} but
   * drops that banner's review-specific "findings and verdict" framing — a suggested description,
   * changelog entry, or doc suggestion has neither — and reads correctly appended below the
   * content.
   */
  public static String truncationDisclosure(int omittedFiles) {
    return omittedFiles > 0
        ? "\n\n> ⚠️ **Large PR — partial coverage.** "
            + omittedFilesClause(omittedFiles)
            + ", so this covers only part of the diff."
        : "";
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
