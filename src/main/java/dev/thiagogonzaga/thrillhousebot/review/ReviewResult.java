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
    TruncationDetail truncation,
    // How many outstanding findings were severe enough to block but were denied eligibility by
    // their confidence alone, on a review that did not end up requesting changes — see
    // #confidenceHoldNotice(int). Zero whenever the hedge was not what decided the verdict.
    int blockingWithheldByConfidence) {
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

  /**
   * Convenience constructor for results built before the confidence-hold count existed (and tests):
   * nothing was withheld from blocking by confidence, so no such disclosure is rendered. The
   * production path ({@code VerdictBuilder}) passes the real count through the canonical
   * constructor.
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
      boolean ciUnreadable,
      boolean requiredContextsKnown,
      TruncationDetail truncation) {
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
        requiredContextsKnown,
        truncation,
        0);
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
   * Which files the review left uncovered, by name and by reason: {@code omittedFileNames} were
   * never sent because the diff exceeded the token budget, {@code clippedFileNames} were
   * hunk-clipped and only partially analyzed, {@code spendCeilingSkippedFileNames} had their review
   * call skipped because the review's token spend ceiling ({@code REVIEW_MAX_TOKENS_PER_REVIEW})
   * was reached — a different reason with a different fix, so the rendered copy names it separately
   * — and {@code responseCutFileNames} were only partially reviewed because the model's response
   * was cut at its length cap and the findings up to the cut were kept (#500). {@code
   * callFailedFileNames} were sent but their review call failed all its retries (#655) — a
   * different reason again, so the rendered copy must not blame the diff budget for them. {@code
   * summaryDegradation} marks the same degradations on the summary call: the findings are complete,
   * but the prose summary either was salvaged from a length-cap-cut response or replaced by the
   * counts-only fallback ({@link SummaryDegradation#RESPONSE_CUT}), or the call was skipped (or
   * refused mid-call) because the review's token spend ceiling ({@code
   * REVIEW_MAX_TOKENS_PER_REVIEW}) was reached ({@link SummaryDegradation#SKIPPED_AT_CEILING},
   * #518). {@code verification} marks how much of the finding set the second-pass verification
   * audit covered (#623): like the summary degradation it is not a file-coverage gap — the verifier
   * fails open by design, so every finding posts either way — but a finding set the audit never (or
   * only partially) screened must say so instead of reading exactly like a verified one. All empty
   * on the legacy line-cap path, where only a count is known — the rendered copy then falls back to
   * the numeric clause.
   */
  @RegisterForReflection
  public record TruncationDetail(
      List<String> omittedFileNames,
      List<String> clippedFileNames,
      List<String> spendCeilingSkippedFileNames,
      List<String> responseCutFileNames,
      List<String> callFailedFileNames,
      SummaryDegradation summaryDegradation,
      VerificationCoverage verification) {
    public static final TruncationDetail EMPTY =
        new TruncationDetail(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            SummaryDegradation.NONE,
            VerificationCoverage.EMPTY);

    /**
     * Convenience constructor for details built before the verification-coverage class existed (and
     * tests): every attempted verification completed, so no such clause is rendered. The production
     * path ({@code VerdictBuilder}) passes the real coverage through the canonical constructor.
     */
    public TruncationDetail(
        List<String> omittedFileNames,
        List<String> clippedFileNames,
        List<String> spendCeilingSkippedFileNames,
        List<String> responseCutFileNames,
        List<String> callFailedFileNames,
        SummaryDegradation summaryDegradation) {
      this(
          omittedFileNames,
          clippedFileNames,
          spendCeilingSkippedFileNames,
          responseCutFileNames,
          callFailedFileNames,
          summaryDegradation,
          VerificationCoverage.EMPTY);
    }

    /**
     * Convenience constructor for details built before the call-failed class existed (and tests):
     * no review call failed, so no such clause is rendered. The production path ({@code
     * VerdictBuilder}) passes the real list through the canonical constructor.
     */
    public TruncationDetail(
        List<String> omittedFileNames,
        List<String> clippedFileNames,
        List<String> spendCeilingSkippedFileNames,
        List<String> responseCutFileNames,
        SummaryDegradation summaryDegradation) {
      this(
          omittedFileNames,
          clippedFileNames,
          spendCeilingSkippedFileNames,
          responseCutFileNames,
          List.of(),
          summaryDegradation);
    }

    public TruncationDetail {
      omittedFileNames = omittedFileNames == null ? List.of() : List.copyOf(omittedFileNames);
      clippedFileNames = clippedFileNames == null ? List.of() : List.copyOf(clippedFileNames);
      spendCeilingSkippedFileNames =
          spendCeilingSkippedFileNames == null
              ? List.of()
              : List.copyOf(spendCeilingSkippedFileNames);
      responseCutFileNames =
          responseCutFileNames == null ? List.of() : List.copyOf(responseCutFileNames);
      callFailedFileNames =
          callFailedFileNames == null ? List.of() : List.copyOf(callFailedFileNames);
      summaryDegradation =
          summaryDegradation == null ? SummaryDegradation.NONE : summaryDegradation;
      verification = verification == null ? VerificationCoverage.EMPTY : verification;
    }

    public boolean isEmpty() {
      return !hasFileGaps()
          && summaryDegradation == SummaryDegradation.NONE
          && !verification.disclosed();
    }

    /**
     * Whether any per-file coverage gap exists — a name in any of the five file classes. False for
     * a detail whose only content is a summary degradation: the findings then cover the whole diff,
     * so surfaces whose framing is per-file partial coverage (the on-demand disclosure, the delta
     * comment) treat such a detail as empty (#516) while the summary-aware surfaces (banner,
     * coverage clause, check-run brief) still disclose the degradation.
     */
    public boolean hasFileGaps() {
      return !omittedFileNames.isEmpty()
          || !clippedFileNames.isEmpty()
          || !spendCeilingSkippedFileNames.isEmpty()
          || !responseCutFileNames.isEmpty()
          || !callFailedFileNames.isEmpty();
    }
  }

  /** How many findings the PR summary lists under "Key Findings". */
  public static final int KEY_FINDINGS_COUNT = 5;

  public boolean hasIssues() {
    return !findings.isEmpty();
  }

  /**
   * The highest-risk findings the first-review summary comment lists under "Key Findings" (top
   * {@value #KEY_FINDINGS_COUNT} by risk among findings that post inline). Used by {@code
   * PrSummaryGenerator} to render that section. Low-confidence medium/low findings are listed under
   * "Things to double-check" instead ({@link #doubleCheckFindings()}).
   */
  public List<Finding> keyFindings() {
    return findings.stream()
        .filter(Finding::postsInline)
        .sorted((a, b) -> a.risk().compareTo(b.risk()))
        .limit(KEY_FINDINGS_COUNT)
        .toList();
  }

  /**
   * Findings withheld from inline threads because confidence is low and risk is below high — shown
   * in the PR summary's collapsed "Things to double-check" section instead.
   */
  public List<Finding> doubleCheckFindings() {
    return findings.stream().filter(f -> !f.postsInline()).toList();
  }

  /** True when CI holds approval back: a required check is offending, or CI could not be read. */
  public boolean ciHoldsApproval() {
    // Reflects the verdict after mode-aware gating: APPROVE means CI did not hold (strict), or the
    // operator chose warn/off. Callers that only need "are there CI issues to surface" should check
    // offendingCiChecks / ciUnreadable instead.
    return reviewState != ReviewState.APPROVE && (!offendingCiChecks.isEmpty() || ciUnreadable);
  }

  /** True when the line budget dropped whole files, so this review covers only part of the diff. */
  public boolean truncated() {
    return omittedFiles > 0;
  }

  /**
   * True when a prior finding was superseded this round — its targeted code left the diff (e.g. a
   * force-push removed it), so the finding was auto-closed instead of holding APPROVE. Triggers a
   * summary re-post on follow-up reviews, since the earlier summary may describe removed code.
   */
  public boolean hasSupersededPrevious() {
    return previousStatuses.stream().anyMatch(s -> "superseded".equalsIgnoreCase(s.status()));
  }

  /** How many previous findings the model (or the backstop) still reports as unresolved. */
  public long unresolvedPreviousCount() {
    return previousStatuses.stream().filter(s -> "unresolved".equalsIgnoreCase(s.status())).count();
  }

  /**
   * How many previous findings this round closed as fixed. Strictly the {@code resolved} status:
   * {@code justified} is a maintainer's decline, not a fix, and {@code superseded} is an auto-close
   * because the targeted code left the diff — counting either as "resolved" would overstate what
   * the round actually fixed.
   */
  public long resolvedPreviousCount() {
    return previousStatuses.stream().filter(s -> "resolved".equalsIgnoreCase(s.status())).count();
  }

  /**
   * The "previous findings are still open" status line, which must state every way a maintainer can
   * close one. A finding raised below the inline-posting bar has no review thread at all, so
   * pointing only at threads left it uncloseable by any action (#548); the message now names the PR
   * conversation directive that reaches it, instead of admitting the gap with a parenthetical.
   */
  public static String unresolvedPreviousMessage(long unresolved) {
    return UNRESOLVED_PREVIOUS_PREFIX + unresolved + UNRESOLVED_PREVIOUS_SUFFIX;
  }

  private static final String UNRESOLVED_PREVIOUS_PREFIX = "No new issues in this revision, but ";

  private static final String UNRESOLVED_PREVIOUS_SUFFIX =
      " previous finding(s) remain unresolved — fix them, or reply on their review thread with why"
          + " they are deferred. A finding listed only under \"Things to double-check\" has no"
          + " thread: clear it by commenting `@thrillhousebot resolved path/to/File.java:42 —"
          + " <the finding's title>` on this PR.";

  /**
   * The same sentence as the previous release emitted it, before #548 appended the threadless-clear
   * instructions. Review bodies are read back from the pull request, so this recognizer outlives
   * the producer that wrote them: on every round after an upgrade the stored bodies still end this
   * way, and a recognizer pinned to the current wording alone hands the bot its own status sentence
   * back as an issue "flagged in the previous review" — #455's exact failure mode — until a fresh
   * round overwrites it. Kept as a second verbatim suffix rather than a loosened prefix match, so
   * it accepts exactly one more producer and nothing else.
   */
  private static final String UNRESOLVED_PREVIOUS_SUFFIX_LEGACY =
      " previous finding(s) remain unresolved — fix them, or reply on their review thread (where"
          + " one exists) with why they are deferred.";

  /**
   * Whether {@code text} is {@link #unresolvedPreviousMessage(long)} for some count — the bot's own
   * sentence about previous findings, which carries no finding of its own. Recognizing it is what
   * lets the previous-findings context reject a review body the bot wrote itself instead of
   * offering it to the model as an issue "flagged in the previous review" (#455).
   *
   * <p>Both ends are required, because everything this accepts is discarded: a body that only opens
   * with the same words is a human review carrying a real finding. Absent text matches nothing. The
   * tail may be the current wording or {@link #UNRESOLVED_PREVIOUS_SUFFIX_LEGACY}, since the bodies
   * this reads were written by whichever release was deployed when the round ran.
   */
  public static boolean isUnresolvedPreviousMessage(String text) {
    if (text == null) {
      return false;
    }
    var stripped = text.strip();
    return stripped.startsWith(UNRESOLVED_PREVIOUS_PREFIX)
        && (stripped.endsWith(UNRESOLVED_PREVIOUS_SUFFIX)
            || stripped.endsWith(UNRESOLVED_PREVIOUS_SUFFIX_LEGACY));
  }

  /**
   * Lead-in of the no-new-findings review body posted when CI checks hold approval back. Shared
   * with {@link ReviewPublisher} so the recognizer above cannot drift from the producer.
   */
  static final String NO_ISSUES_CI_PENDING_LEAD_IN =
      "ThrillhouseBot found no issues in this PR, but some checks are still pending or failed:";

  /** Lead-in of the no-new-findings review body posted when the CI status could not be read. */
  static final String NO_ISSUES_CI_UNREADABLE_LEAD_IN =
      "ThrillhouseBot found no issues in this PR, but the CI status could not be read, so approval"
          + " is held until it can be confirmed.";

  /** Lead-in of {@link #truncationNotice(int, TruncationDetail)}'s partial-review banner. */
  static final String TRUNCATION_NOTICE_LEAD_IN = "> ⚠️ **Large PR — partial review.**";

  /**
   * Banner prepended to the summary when only the summary response was cut at the model's length
   * cap — no file-coverage gap exists, so the partial-review banner (whose framing says the
   * findings cover only part of the diff) would overstate the damage: here the findings are
   * complete and only the prose summary was salvaged or degraded. When a file-coverage gap exists
   * too, {@link #coverageGapClause(int, TruncationDetail)} folds the summary cut in as one more
   * clause instead and this banner is not used.
   */
  static final String SUMMARY_CUT_NOTICE =
      """
      > ⚠️ **Summary shortened.** The model's summary response was cut at its length cap\
       (max-output-tokens / REVIEW_CONCISE_MAX_OUTPUT_TOKENS) — the findings themselves are\
       complete.

      """;

  /**
   * The ceiling sibling of {@link #SUMMARY_CUT_NOTICE} (#518): the summary call was skipped because
   * the review's token spend ceiling was reached, with every batch already reviewed — the findings
   * are complete, so the partial-review banner would overstate the damage here too, and the knob to
   * raise is named instead. When a file-coverage gap exists as well, {@link #coverageGapClause(int,
   * TruncationDetail)} folds the skip in as one more clause and this banner is not used.
   */
  static final String SUMMARY_SKIPPED_NOTICE =
      """
      > ⚠️ **Summary skipped.** The review's token spend ceiling\
       (REVIEW_MAX_TOKENS_PER_REVIEW) was reached before the summary could be generated — the\
       findings themselves are complete.

      """;

  /**
   * True when at least one outstanding finding was severe enough to block but its confidence, not
   * its risk, is why this review is not requesting changes.
   */
  public boolean confidenceHeldTheVerdict() {
    return blockingWithheldByConfidence > 0;
  }

  /**
   * Lead-in of {@link #confidenceHoldNotice(int)}. Shared with the surfaces that render or
   * recognize the banner so the two cannot drift, exactly like {@link #TRUNCATION_NOTICE_LEAD_IN}.
   */
  static final String CONFIDENCE_HOLD_LEAD_IN = "> ⚠️ **Severity did not decide this verdict.**";

  /**
   * The shared "N finding(s) … hedged" clause behind both confidence-hold surfaces, so the summary
   * banner and the check-run summary never drift on the count or on what the hedge means.
   */
  private static String confidenceHoldClause(int withheld) {
    return String.format(
        "%d finding(s) were severe enough to block on their own, but the review hedged its"
            + " confidence in them",
        withheld);
  }

  /**
   * Banner prepended to the summary when confidence, not severity, is why the review did not
   * request changes (#645).
   *
   * <p>Under the default {@code balanced} mode a critical/high finding blocks only when the review
   * is highly confident in it, and the review prompt mandates a hedge whenever a mitigation is not
   * visible in the diff — so the very class the severity floor exists to protect (an injection sink
   * with no visible sanitizer) is hedged by construction and quietly loses the floor's consequence.
   * Round 6 of the dogfood corpus measured that on a stored-XSS sink and a verified build-breaking
   * defect, both non-blocking, with nothing in the review saying why.
   *
   * <p>The gate itself is deliberately left alone: an operator who wants severity alone to decide
   * already has {@code REVIEW_BLOCKING_STRICTNESS=strict}, and widening the default would collapse
   * {@code balanced} into it, leaving no mode that means "only demonstrated findings block". What
   * was wrong was that the choice was invisible, so this states it and names the knob.
   */
  static String confidenceHoldNotice(int withheld) {
    return String.format(
        CONFIDENCE_HOLD_LEAD_IN
            + " %s, so this review comments instead of requesting changes. A hedge means the"
            + " finding could not be confirmed from the diff alone — not that it was disproven —"
            + " so read those findings before merging. Set `REVIEW_BLOCKING_STRICTNESS=strict` to"
            + " let severity alone decide.%n%n",
        confidenceHoldClause(withheld));
  }

  /** The one-sentence check-run form of {@link #confidenceHoldNotice(int)}. */
  static String confidenceHoldBrief(int withheld) {
    return "Not blocking: " + confidenceHoldClause(withheld) + ".";
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
        TRUNCATION_NOTICE_LEAD_IN
            + " %s; the findings and verdict below cover only the reviewed portion.%n%n",
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
    // The spend-ceiling class carries its own reason: these files fit the diff budget fine — the
    // review ran out of tokens to pay for their calls — so the budget wording would misdirect the
    // operator, and the knob to raise is named instead (#499).
    var clauses = new ArrayList<String>(2);
    if (!parts.isEmpty()) {
      clauses.add(String.join(" and ", parts) + " because the diff exceeded the review budget");
    }
    if (!detail.spendCeilingSkippedFileNames().isEmpty()) {
      clauses.add(
          String.format(
              "%d file(s) were not reviewed because the review's token spend ceiling"
                  + " (REVIEW_MAX_TOKENS_PER_REVIEW) was reached (%s)",
              detail.spendCeilingSkippedFileNames().size(),
              nameList(detail.spendCeilingSkippedFileNames())));
    }
    // Runtime call failure carries its own reason too (#655): these files fit the diff budget and
    // were sent — the review call for them failed all its retries — so the budget wording would
    // misdirect the operator toward a knob that cannot help, and the summary overview already
    // says the call did not complete; the two surfaces must agree.
    if (!detail.callFailedFileNames().isEmpty()) {
      clauses.add(
          String.format(
              "%d file(s) were not reviewed because the review call for them did not complete"
                  + " (%s)",
              detail.callFailedFileNames().size(), nameList(detail.callFailedFileNames())));
    }
    // The response-cut class is partial in a third way: the files were sent and reviewed, but the
    // model's answer was cut at its length cap — the findings produced before the cut were kept,
    // so "not reviewed" would understate the coverage and silence the honest caveat.
    if (!detail.responseCutFileNames().isEmpty()) {
      clauses.add(
          String.format(
              "%d file(s) were only partially reviewed because the model's response was cut at"
                  + " its length cap (max-output-tokens) — findings up to the cut were kept (%s)",
              detail.responseCutFileNames().size(), nameList(detail.responseCutFileNames())));
    }
    // A detail carrying only a summary degradation still owes the reader the legacy omitted-file
    // count (#659): nothing below the fallback reads the int, so the count vanished whenever the
    // summary also degraded — the coverage disclosure this class exists to guarantee.
    if (!detail.hasFileGaps() && omittedFiles > 0) {
      clauses.add(omittedFilesClause(omittedFiles));
    }
    // A summary degradation affects prose, not findings: the findings are complete, but the
    // summary call either had its response cut at the length cap and was salvaged (or replaced by
    // the counts-only fallback), or was skipped outright at the token spend ceiling (#518) — the
    // two flavors of the same degradation, disclosed with the same shape so neither lane stays
    // log-only.
    switch (detail.summaryDegradation()) {
      case RESPONSE_CUT ->
          clauses.add(
              "the summary was shortened because the model's response was cut at its length cap"
                  + " (max-output-tokens / REVIEW_CONCISE_MAX_OUTPUT_TOKENS) — the findings"
                  + " themselves are complete");
      case SKIPPED_AT_CEILING ->
          clauses.add(
              "the summary was skipped because the review's token spend ceiling"
                  + " (REVIEW_MAX_TOKENS_PER_REVIEW) was reached — the findings themselves are"
                  + " complete");
      case NONE -> {
        // Nothing to disclose.
      }
    }
    // Verification coverage affects trust, not coverage (#623): the findings post either way —
    // the verifier fails open by design — but a finding set no second stage screened must not
    // read exactly like a verified one, so the gap is stated here alongside the other
    // degradations instead of staying log-only.
    if (detail.verification().disclosed()) {
      clauses.add(verificationClause(detail.verification()));
    }
    return String.join(", and ", clauses);
  }

  /**
   * The shared "verification covered X of Y finding(s)" clause behind every verification-coverage
   * surface (#623), so the review banner, the check-run summary and the delta comment never drift
   * on the counts or on what an unverified finding means. Only rendered for a {@linkplain
   * VerificationCoverage#disclosed() disclosed} coverage; the cause is stated generically — an
   * empty verifier response, a response cut mid-JSON and a call skipped at the token spend ceiling
   * all leave the same unverified state, and the logs carry the specific reason.
   */
  private static String verificationClause(VerificationCoverage verification) {
    if (verification.outcome() == VerificationCoverage.Outcome.PARTIAL) {
      return String.format(
          "the second-pass finding verification only covered %d of the %d finding(s) because its"
              + " response did not complete — the remaining %d post unverified, as the reviewer"
              + " raised them",
          verification.verified(), verification.candidates(), verification.unverified());
    }
    return String.format(
        "the %d finding(s) were NOT verified by the second-pass audit because its call did not"
            + " complete — they post as the reviewer raised them",
        verification.candidates());
  }

  /**
   * Banner prepended to the summary when verification did not cover the whole finding set and no
   * file-coverage gap exists to fold the clause into (#623). Like {@link #SUMMARY_CUT_NOTICE}, it
   * does not hold the verdict: the verifier fails open by design, so the disclosure changes what
   * the review says, never what it decides.
   */
  static String verificationNotice(VerificationCoverage verification) {
    return String.format(
        "> ⚠️ **Findings not fully verified.** %s.%n%n",
        capitalize(verificationClause(verification)));
  }

  /** The one-sentence check-run form of {@link #verificationNotice(VerificationCoverage)}. */
  public static String verificationBrief(VerificationCoverage verification) {
    if (verification.outcome() == VerificationCoverage.Outcome.PARTIAL) {
      return String.format(
          "Verification covered %d of %d finding(s); the rest posted unverified.",
          verification.verified(), verification.candidates());
    }
    return String.format(
        "The %d finding(s) were not verified (the verification call did not complete).",
        verification.candidates());
  }

  /** Sentence-cases a clause; every caller passes a non-empty format-built string. */
  private static String capitalize(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
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
    var parts = new ArrayList<String>(3);
    if (!truncation.omittedFileNames().isEmpty()) {
      parts.add(String.format("%d file(s) omitted", truncation.omittedFileNames().size()));
    }
    if (!truncation.clippedFileNames().isEmpty()) {
      parts.add(
          String.format("%d file(s) partially analyzed", truncation.clippedFileNames().size()));
    }
    if (!truncation.spendCeilingSkippedFileNames().isEmpty()) {
      parts.add(
          String.format(
              "%d file(s) skipped at the token spend ceiling",
              truncation.spendCeilingSkippedFileNames().size()));
    }
    if (!truncation.responseCutFileNames().isEmpty()) {
      parts.add(
          String.format(
              "%d file(s) partially reviewed (response cut at the length cap)",
              truncation.responseCutFileNames().size()));
    }
    if (!truncation.callFailedFileNames().isEmpty()) {
      parts.add(
          String.format(
              "%d file(s) not reviewed (review call did not complete)",
              truncation.callFailedFileNames().size()));
    }
    switch (truncation.summaryDegradation()) {
      case RESPONSE_CUT -> parts.add("summary shortened (response cut at the length cap)");
      case SKIPPED_AT_CEILING -> parts.add("summary skipped (token spend ceiling reached)");
      case NONE -> {
        // Nothing to disclose.
      }
    }
    switch (truncation.verification().outcome()) {
      case NONE ->
          parts.add(
              String.format(
                  "%d finding(s) unverified (verification call did not complete)",
                  truncation.verification().candidates()));
      case PARTIAL ->
          parts.add(
              String.format(
                  "verification covered %d of %d finding(s)",
                  truncation.verification().verified(), truncation.verification().candidates()));
      case FULL -> {
        // Nothing to disclose.
      }
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
   * {@code /changelog}, {@code /add-docs}, {@code /generate-tests}) when the diff was truncated, or
   * an empty string when nothing was omitted. Shares the omitted-file clause with {@link
   * #truncationNotice(int)} but drops that banner's review-specific "findings and verdict" framing
   * — a suggested description, changelog entry, doc suggestion, or proposed test has neither — and
   * reads correctly appended below the content.
   */
  public static String truncationDisclosure(int omittedFiles) {
    return truncationDisclosure(omittedFiles, TruncationDetail.EMPTY);
  }

  /**
   * Disclosure variant that names the uncovered files when a token-budget plan knows them, and
   * separates files dropped entirely from files only partially analyzed. Mirrors {@link
   * #truncationNotice(int, TruncationDetail)} so an on-demand command that batches under the token
   * budget upholds the same "reported by name, never silently dropped" contract as the review
   * banner; falls back to the numeric clause when only a count is known.
   *
   * <p>A detail carrying only a summary degradation (no file names) is treated as empty here: this
   * disclosure's framing — "Large PR — partial coverage … covers only part of the diff" — is about
   * per-file gaps, and with the findings complete it would be self-contradictory (#516). The
   * summary-aware surfaces (review banner, check-run suffix) disclose the flag instead.
   */
  public static String truncationDisclosure(int omittedFiles, TruncationDetail detail) {
    if (omittedFiles <= 0 && (detail == null || !detail.hasFileGaps())) {
      return "";
    }
    return "\n\n> ⚠️ **Large PR — partial coverage.** "
        + coverageGapClause(omittedFiles, detail)
        + ", so this covers only part of the diff.";
  }

  public int totalFindings() {
    return findings.size();
  }

  @RegisterForReflection
  public record PreviousFindingStatus(
      int id,
      String status, // resolved, unresolved, justified, superseded
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
