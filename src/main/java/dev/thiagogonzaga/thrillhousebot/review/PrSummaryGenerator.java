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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Generates the PR summary comment posted on the first review. */
@ApplicationScoped
public class PrSummaryGenerator {

  // Walkthrough-diagram master switch; also gates rendering when the model volunteers an
  // unrequested walkthrough_diagram.
  private final boolean diagramEnabled;

  // Opt-in "large PR, nothing inline" note; carries its own thresholds and off switch.
  private final LargePrNudge largePrNudge;

  @Inject
  public PrSummaryGenerator(ThrillhouseConfig config) {
    this(config.review().diagram().enabled(), LargePrNudge.from(config.review().largePrNudge()));
  }

  /** Visible for tests; the large-PR nudge stays off, matching its shipped default. */
  PrSummaryGenerator(boolean diagramEnabled) {
    this(diagramEnabled, LargePrNudge.DISABLED);
  }

  /** Visible for tests: pins the diagram switch and the large-PR nudge policy together. */
  PrSummaryGenerator(boolean diagramEnabled, LargePrNudge largePrNudge) {
    this.diagramEnabled = diagramEnabled;
    this.largePrNudge = largePrNudge;
  }

  /** The clean-review celebration; rendered inside the summary, never as a separate comment. */
  public static final String ZERO_ISSUES_MESSAGE =
      "Everything's coming up Thrillhouse! 🎉\n\nNo issues found in this PR.";

  /**
   * The first line of every generated summary. Doubles as the marker used to recognize a summary
   * comment the bot already posted on a PR, so a re-review never duplicates it.
   */
  public static final String SUMMARY_HEADING = "## 🤖 ThrillhouseBot PR Summary";

  /**
   * Upper bound on rows in the changed-files walkthrough. Keeps the comment within GitHub's size
   * budget on large PRs; any files beyond this are rolled up into a trailing "… and N more" note.
   *
   * <p>Read from {@link PrReviewPrompts#MAX_FILE_SUMMARIES} so the number of rows rendered and the
   * number of per-file summaries the prompt asks the model for cannot drift apart: a smaller prompt
   * cap would guarantee rows no response could ever fill (#536).
   */
  static final int MAX_FILE_ROWS = PrReviewPrompts.MAX_FILE_SUMMARIES;

  /**
   * Summary cell for a pure-rename row. A pure rename is excluded from AI review by design (its
   * content is unchanged, so there is nothing to review), which means the model never returns a
   * summary for it — rendering the bare "-" made that correct behavior read as a failed summary
   * (#536), so the row states the reason instead.
   */
  static final String PURE_RENAME_SUMMARY = "Pure rename — content unchanged";

  /**
   * Summary cell for a reviewed row the model returned no per-file note for. The bare "-" it
   * replaces was indistinguishable from a rendering bug: it stated nothing about why the cell was
   * empty, on exactly the rows a large PR leaves unsummarized (#547). Says the reason instead, for
   * the same reason {@link #PURE_RENAME_SUMMARY} does — the two cover the whole unsummarized set,
   * one row per cause.
   */
  static final String NO_MODEL_SUMMARY = "Not summarized — no model summary for this file";

  /** Heading of the Description vs. Implementation section when a mismatch exists. */
  static final String GAPS_HEADING = "### ⚠️ Description vs. Implementation";

  /**
   * Heading of the same section when the check found nothing. Carries no warning emoji: a clean
   * result is not a warning, and reusing the ⚠️ heading would make every review look flagged.
   */
  static final String NO_GAPS_HEADING = "### Description vs. Implementation";

  /**
   * Body for the state where the model reported gaps but #588 collapsed every one of them onto a
   * finding that states the same thing. The claim still reaches the reader at its most specific
   * surface, so it is not repeated here — but the check plainly ran, and saying so is what keeps a
   * collapsed section from reading exactly like a skipped one (#637).
   *
   * <p>"Reported as a finding below" is load-bearing, and the deduplicator holds it up: it
   * collapses a gap only onto a finding that posts inline, so a gap whose every match was demoted
   * into the collapsed double-check block survives as a bullet and this body is never reached for
   * it (#718). Reaching it means at least one gap collapsed onto a finding published as a finding
   * proper.
   */
  static final String GAPS_ALL_REPORTED_AS_FINDINGS =
      "Every mismatch found between the description and the change is reported as a finding below,"
          + " so it is not repeated here.";

  /** Body for the state where the check ran over the description and found no mismatch. */
  static final String NO_GAPS_FOUND =
      "No mismatch found between the PR description and the change.";

  /**
   * One changed file in the walkthrough: its path, the diff's authoritative change type, and
   * whether it is a pure rename — renamed with no content change, hence never sent to the model and
   * never carrying a model summary.
   */
  public record ChangedFile(String path, String changeType, boolean pureRename) {

    /** A row whose pure-rename status is not distinguished; treated as ordinary content change. */
    public ChangedFile(String path, String changeType) {
      this(path, changeType, false);
    }
  }

  /**
   * Upper bound on the rendered Mermaid source. A diagram larger than this is dropped rather than
   * posted: it is likely runaway or truncated output, and GitHub silently fails to render oversized
   * Mermaid blocks anyway. Comfortably fits the ~12-node diagrams the prompt asks for.
   */
  static final int MAX_DIAGRAM_CHARS = 4000;

  /**
   * Mermaid diagram-type keywords the first source line may start with. The model is asked for a
   * flowchart or sequence diagram; the wider set is accepted so a valid diagram is never dropped on
   * a technicality, while arbitrary prose (which would render as a broken block) still is.
   */
  private static final List<String> MERMAID_PREFIXES =
      List.of(
          "graph",
          "flowchart",
          "sequencediagram",
          "classdiagram",
          "statediagram",
          "erdiagram",
          "journey",
          "gantt");

  /**
   * Renders the summary comment. {@code filesChanged}/{@code additions}/{@code deletions} are
   * GitHub's authoritative PR-level totals (or the diff-derived fallback), so the "Changes
   * Overview" reflects the whole PR even when files are dropped by the ignore-glob. {@code
   * changedFiles} is the reviewable (non-ignored) file list rendered as the walkthrough table; its
   * rollup "…and N more" note is derived from {@code filesChanged} so it, too, matches the
   * authoritative total.
   */
  public String generate(
      int filesChanged,
      int additions,
      int deletions,
      List<ChangedFile> changedFiles,
      ReviewResponse.Summary aiSummary,
      ReviewResult result) {
    var sb = new StringBuilder();
    sb.append(SUMMARY_HEADING).append("\n\n");

    // One observation must be published once. The findings are the most specific surface, so a
    // description-gap bullet — or a walkthrough clause appended after the row's file summary —
    // that only restates one of them is collapsed away before anything is rendered (#588).
    var reportedGaps = descriptionGaps(aiSummary);
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            reportedGaps, summariesByPath(aiSummary), result.findings());

    appendPrPurpose(sb, aiSummary);
    appendDescriptionGaps(sb, aiSummary, reportedGaps.size(), surfaces.descriptionGaps());
    appendWalkthroughDiagram(sb, aiSummary);

    sb.append("### Changes Overview\n");
    sb.append("- **Files changed:** ").append(filesChanged).append("\n");
    sb.append("- **Lines added:** ").append(signed('+', additions)).append("\n");
    sb.append("- **Lines removed:** ").append(signed('-', deletions)).append("\n\n");

    appendChangedFiles(sb, filesChanged, changedFiles, surfaces.fileSummaries());

    sb.append("### Risk Assessment\n");
    sb.append("| Risk | Count |\n");
    sb.append("|------|-------|\n");
    sb.append("| 🔴 Critical | ").append(result.criticalCount()).append(" |\n");
    sb.append("| 🟠 High | ").append(result.highCount()).append(" |\n");
    sb.append("| 🟡 Medium | ").append(result.mediumCount()).append(" |\n");
    sb.append("| 🔵 Low | ").append(result.lowCount()).append(" |\n\n");

    appendPreviousFindings(sb, result);
    appendFindingsOrCelebration(sb, result);
    appendDoubleCheckFindings(sb, result);
    // After the findings sections so the note can refer back to them, and so a reader meets the
    // celebration (or the double-check list) before the caveat about how much to trust it.
    largePrNudge.render(filesChanged, additions, deletions, result).ifPresent(sb::append);
    appendCiChecks(sb, result);

    sb.append("---\n");
    sb.append("*Automated review by ThrillhouseBot. Reply with `/review` to re-run.*\n");

    return sb.toString();
  }

  private static void appendPreviousFindings(StringBuilder sb, ReviewResult result) {
    // The record constructor guarantees a non-null status list.
    if (result.previousStatuses().isEmpty()) {
      return;
    }
    var resolved =
        result.previousStatuses().stream()
            .filter(s -> "resolved".equalsIgnoreCase(s.status()))
            .count();
    var unresolved =
        result.previousStatuses().stream()
            .filter(s -> "unresolved".equalsIgnoreCase(s.status()))
            .count();
    var justified =
        result.previousStatuses().stream()
            .filter(s -> "justified".equalsIgnoreCase(s.status()))
            .count();
    var superseded =
        result.previousStatuses().stream()
            .filter(s -> "superseded".equalsIgnoreCase(s.status()))
            .count();
    sb.append("### Previous Findings Status\n");
    sb.append("| Status | Count |\n");
    sb.append("|--------|-------|\n");
    sb.append("| ✅ Resolved | ").append(resolved).append(" |\n");
    sb.append("| ⚠️ Still present | ").append(unresolved).append(" |\n");
    sb.append("| 💬 Justified | ").append(justified).append(" |\n");
    if (superseded > 0) {
      sb.append("| 🗂️ Superseded (targeted code left the diff) | ")
          .append(superseded)
          .append(" |\n");
    }
    sb.append("\n");
  }

  private static void appendFindingsOrCelebration(StringBuilder sb, ReviewResult result) {
    if (result.hasIssues()) {
      var keyFindings = result.keyFindings();
      if (!keyFindings.isEmpty()) {
        sb.append("### Key Findings\n");
        for (Finding f : keyFindings) {
          // Model-supplied title/path: flatten and neutralize break-outs (a newline, a "```", or a
          // "</details>" in the title must not escape the bullet or the summary's <details>
          // blocks).
          sb.append("- **")
              .append(f.risk().name())
              .append(":** ")
              .append(MarkdownSafe.inline(f.title()))
              .append(" (`")
              .append(MarkdownSafe.inlineCode(f.file()))
              .append(":")
              .append(f.line())
              .append("`)\n");
        }
        sb.append("\n");
      }
    } else if (hasNoUnresolvedPrevious(result)) {
      if (result.ciHoldsApproval()) {
        appendCiHold(sb, result);
      } else if (result.truncated()) {
        sb.append(
            "No new issues found in the reviewed portion of this PR — but the diff was too large to review in full, so this is a partial review.\n\n");
      } else {
        sb.append(ZERO_ISSUES_MESSAGE).append("\n\n");
      }
    }
  }

  /**
   * Collapsed section for low-confidence medium/low findings that were withheld from inline
   * threads. Keeps the signal visible and clearly non-blocking without opening a review thread that
   * maintainers must triage and resolve.
   *
   * <p>A bullet that says what an inline finding already says is cross-referenced to it. This list
   * never passed through the deduplicator — {@code collapse} weighs the gap bullets and walkthrough
   * rows against the findings and stops there — so one defect reported both inline and here read as
   * two independent defects, which is exactly the repetition-reads-as-severity failure the
   * deduplicator exists to prevent (#639). The bullet is annotated instead of dropped because it is
   * the finding's only rendered surface: it is the one place the {@code path:line} a maintainer
   * needs to clear it from the PR conversation is printed (#548).
   */
  private static void appendDoubleCheckFindings(StringBuilder sb, ReviewResult result) {
    var findings = result.doubleCheckFindings();
    if (findings.isEmpty()) {
      return;
    }
    var inline = result.findings().stream().filter(Finding::postsInline).toList();
    sb.append("### Things to double-check\n");
    sb.append("<details>\n");
    sb.append("<summary>")
        .append(findings.size())
        .append(" lower-confidence finding")
        .append(findings.size() == 1 ? "" : "s")
        .append("</summary>\n\n");
    for (Finding f : findings) {
      // By construction these findings are confidence LOW, so the disclaimer is always present.
      // Model-supplied title/path is flattened and its break-outs neutralized so an embedded
      // newline, "```", or "</details>" cannot escape this bullet or the enclosing <details>.
      sb.append("- **")
          .append(f.risk().name())
          .append(":** ")
          .append(MarkdownSafe.inline(f.title()))
          .append(" (`")
          .append(MarkdownSafe.inlineCode(f.file()))
          .append(":")
          .append(f.line())
          .append("`) ")
          .append(SuggestionFormatter.confidenceDisclaimer(Confidence.LOW));
      SummarySurfaceDeduplicator.restatedBy(f, inline)
          .ifPresent(published -> sb.append(" ").append(sameIssueNote(published)));
      sb.append("\n");
    }
    sb.append("\n</details>\n\n");
  }

  /** Opening of the cross-reference appended to a double-check bullet that restates a finding. */
  static final String SAME_ISSUE_PREFIX = "_Same issue as the inline finding on ";

  /**
   * The cross-reference naming the inline finding a double-check bullet restates, by the locator
   * that finding's own row and review thread carry, so a reader can see the two are one defect.
   */
  private static String sameIssueNote(Finding published) {
    return SAME_ISSUE_PREFIX
        + "`"
        + MarkdownSafe.inlineCode(published.file())
        + ":"
        + published.line()
        + "`._";
  }

  /**
   * Renders the celebration-replacement line when CI holds approval (optionally alongside a
   * truncated diff). The two CI holds read differently: an offending check is pending/failing, so
   * it is phrased as CI "not confirmed green" — with neutral "CI"/"required CI" wording per whether
   * the required set was resolved. An unreadable source is NOT a not-green result — it could not be
   * read — so it gets "could not be read" wording, matching {@code
   * VerdictBuilder.checkSummaryForResult} and the "CI Status Unavailable" section rather than
   * misreporting an unread status as failing. Reached only via {@link
   * ReviewResult#ciHoldsApproval}, so no offending check implies the hold is an unreadable source.
   */
  private static void appendCiHold(StringBuilder sb, ReviewResult result) {
    boolean offending = !result.offendingCiChecks().isEmpty();
    boolean truncated = result.truncated();
    String ciPhrase = result.requiredContextsKnown() ? "required CI" : "CI";
    if (offending && truncated) {
      sb.append(
              "No new issues found in the reviewed portion of this PR, but it cannot be approved: ")
          .append(ciPhrase)
          .append(
              " is not confirmed green, and the diff was too large to review in full (a partial review).\n\n");
    } else if (offending) {
      sb.append("No new issues found in this PR, but the review cannot be approved until ")
          .append(ciPhrase)
          .append(" is confirmed green.\n\n");
    } else if (truncated) {
      sb.append(
          "No new issues found in the reviewed portion of this PR, but it cannot be approved: the CI status could not be read, and the diff was too large to review in full (a partial review).\n\n");
    } else {
      sb.append(
          "No new issues found in this PR, but the CI status could not be read, so the review cannot be approved until it can be confirmed.\n\n");
    }
  }

  private static void appendCiChecks(StringBuilder sb, ReviewResult result) {
    appendOffendingCiChecks(sb, result);
    appendUnreadableCiStatus(sb, result);
  }

  private static void appendOffendingCiChecks(StringBuilder sb, ReviewResult result) {
    if (result.offendingCiChecks().isEmpty()) {
      return;
    }
    if (result.requiredContextsKnown()) {
      sb.append("### ⚠️ Required CI Checks Status\n");
      sb.append("Some required checks are still pending or have failed:\n\n");
    } else {
      sb.append("### ⚠️ CI Checks Status\n");
      sb.append("Some checks are still pending or have failed:\n\n");
    }
    sb.append("| Check | Type | Status | Detail |\n");
    sb.append("|-------|------|--------|--------|\n");
    for (var check : result.offendingCiChecks()) {
      String statusEmoji = check.isFailing() ? "❌ Failed" : "⏳ Pending";
      String detail = check.conclusion() != null ? check.conclusion() : "-";
      sb.append("| **")
          .append(MarkdownSafe.tableCell(check.name()))
          .append("** | ")
          .append(MarkdownSafe.tableCell(check.type()))
          .append(" | ")
          .append(statusEmoji)
          .append(" | ")
          .append(MarkdownSafe.tableCell(detail))
          .append(" |\n");
    }
    sb.append("\n");
  }

  private static void appendUnreadableCiStatus(StringBuilder sb, ReviewResult result) {
    if (!result.ciUnreadable()) {
      return;
    }
    sb.append("### ⚠️ CI Status Unavailable\n");
    if (result.reviewState() == ReviewState.APPROVE) {
      sb.append(
          """
          The CI status could not be read from GitHub. Approval was still posted because CI \
          gating is not strict — verify CI separately if needed.

          """);
    } else {
      sb.append(
          """
          The CI status could not be read from GitHub, so approval is held until it can be \
          confirmed.

          """);
    }
  }

  /** A clean review celebrates only when nothing from earlier rounds is still unresolved. */
  private static boolean hasNoUnresolvedPrevious(ReviewResult result) {
    // The record constructor guarantees a non-null status list
    return result.previousStatuses().stream()
        .noneMatch(s -> "unresolved".equalsIgnoreCase(s.status()));
  }

  private static void appendPrPurpose(StringBuilder sb, ReviewResponse.Summary aiSummary) {
    if (aiSummary == null || aiSummary.prPurpose() == null || aiSummary.prPurpose().isBlank()) {
      return;
    }
    sb.append("### What this PR does\n");
    sb.append(MarkdownSafe.inline(aiSummary.prPurpose())).append("\n\n");
  }

  /** The model's non-blank description gaps; empty when there is no summary to read them from. */
  private static List<String> descriptionGaps(ReviewResponse.Summary aiSummary) {
    if (aiSummary == null) {
      return List.of();
    }
    // List.copyOf in the Summary constructor guarantees no null elements
    return aiSummary.descriptionGaps().stream().filter(g -> !g.isBlank()).toList();
  }

  /**
   * Renders the Description vs. Implementation section in whichever of its three states the check
   * actually reached, so a reader can tell a matching description from a check that never ran
   * (#637). Silence is reserved for the one case that earns it — {@code aiSummary} is {@code null},
   * meaning no summary came back at all, which the summary-degradation banners already disclose.
   *
   * <p>The states are: gaps survived, so they are listed; the model reported gaps but every one of
   * them restated an inline finding and #588 collapsed them all away, which used to delete the
   * whole section along with them (the sharpest measured case — the review found the contradicted
   * bug and still said nothing about the description); and the model reported none, which is now
   * stated rather than implied by an absent heading.
   *
   * <p>A PR with an empty body reaches the last state too, since the model reports no gaps for a
   * description it never received and the renderer cannot tell the two apart from the response
   * alone. The line is vacuous there rather than wrong, and that is the safe direction: the failure
   * this fixes is a reader who cannot tell "checked, matched" from "never checked".
   */
  private static void appendDescriptionGaps(
      StringBuilder sb, ReviewResponse.Summary aiSummary, int reported, List<String> gaps) {
    if (aiSummary == null) {
      return;
    }
    if (!gaps.isEmpty()) {
      sb.append(GAPS_HEADING).append("\n");
      sb.append("The PR description does not fully match the change:\n");
      for (String gap : gaps) {
        sb.append("- ").append(gap.strip()).append("\n");
      }
      sb.append("\n");
      return;
    }
    if (reported > 0) {
      sb.append(GAPS_HEADING).append("\n").append(GAPS_ALL_REPORTED_AS_FINDINGS).append("\n\n");
      return;
    }
    sb.append(NO_GAPS_HEADING).append("\n").append(NO_GAPS_FOUND).append("\n\n");
  }

  /**
   * Renders the file-by-file walkthrough as a table of (file, change type, one-line summary). The
   * change type comes from the diff (authoritative); the summary is the model's per-file note,
   * matched by path. Files without a model summary still appear, so the table always mirrors the
   * reviewable change set. Bounded to {@link #MAX_FILE_ROWS} rows to respect the comment-size
   * budget.
   *
   * <p>The trailing "…and N more file(s)" rollup is measured against {@code totalFilesChanged}
   * (GitHub's authoritative total), not the reviewable-row count, so it also accounts for files the
   * ignore-glob dropped from the table — matching the "Changes Overview" total above it.
   */
  private static void appendChangedFiles(
      StringBuilder sb,
      int totalFilesChanged,
      List<ChangedFile> changedFiles,
      Map<String, String> summaryByPath) {
    if (changedFiles == null || changedFiles.isEmpty()) {
      return;
    }
    sb.append("### Changed Files\n");
    sb.append("| File | Change | Summary |\n");
    sb.append("|------|--------|---------|\n");
    for (ChangedFile file : changedFiles.stream().limit(MAX_FILE_ROWS).toList()) {
      String summary = summaryByPath.getOrDefault(file.path(), "");
      sb.append("| `")
          .append(MarkdownSafe.tableCell(file.path()))
          .append("` | ")
          .append(changeTypeLabel(file.changeType()))
          .append(" | ")
          .append(summaryCell(file, summary))
          .append(" |\n");
    }
    int rowsShown = Math.min(changedFiles.size(), MAX_FILE_ROWS);
    int overflow = totalFilesChanged - rowsShown;
    if (overflow > 0) {
      sb.append("\n_…and ").append(overflow).append(" more file(s)._\n");
    }
    sb.append("\n");
  }

  /**
   * The walkthrough's Summary cell. The model's note wins whenever it supplied one; otherwise the
   * row states why it has none — a pure rename was never sent to the model, and any other row
   * simply got no note back. No cell is ever left as a bare dash, which said nothing about which of
   * the two it was (#547).
   */
  private static String summaryCell(ChangedFile file, String summary) {
    if (!summary.isBlank()) {
      return MarkdownSafe.tableCell(summary.strip());
    }
    return file.pureRename() ? PURE_RENAME_SUMMARY : NO_MODEL_SUMMARY;
  }

  /** Indexes the model's per-file notes by path; a duplicate path keeps the first note. */
  private static Map<String, String> summariesByPath(ReviewResponse.Summary aiSummary) {
    if (aiSummary == null) {
      return Map.of();
    }
    // List.copyOf in the Summary constructor guarantees no null elements.
    return aiSummary.fileSummaries().stream()
        .filter(fs -> fs.path() != null && !fs.path().isBlank() && fs.summary() != null)
        .collect(
            Collectors.toMap(
                fs -> fs.path().strip(),
                ReviewResponse.FileSummary::summary,
                (first, dup) -> first));
  }

  /** Maps a GitHub file status to a display label, falling back to the raw value when unknown. */
  private static String changeTypeLabel(String status) {
    if (status == null || status.isBlank()) {
      return "Changed";
    }
    return switch (status.toLowerCase(Locale.ROOT)) {
      case "added" -> "Added";
      case "removed", "deleted" -> "Removed";
      case "renamed" -> "Renamed";
      case "copied" -> "Copied";
      case "changed", "modified" -> "Modified";
      default -> MarkdownSafe.tableCell(status);
    };
  }

  /**
   * Renders the optional Mermaid control-flow diagram as a collapsible block. The model supplies
   * raw Mermaid source (no fences); this validates it is a real diagram, neutralizes any stray code
   * fences so it cannot break out of the block, and drops anything oversized or unrecognized.
   */
  private void appendWalkthroughDiagram(StringBuilder sb, ReviewResponse.Summary aiSummary) {
    if (!diagramEnabled || aiSummary == null) {
      return;
    }
    String diagram = sanitizeDiagram(aiSummary.walkthroughDiagram());
    if (diagram == null) {
      return;
    }
    sb.append("### Control-Flow Diagram\n");
    sb.append("<details>\n");
    sb.append("<summary>🔀 Show diagram</summary>\n\n");
    sb.append("```mermaid\n");
    sb.append(diagram).append("\n");
    sb.append("```\n\n");
    sb.append("</details>\n\n");
  }

  /**
   * Returns render-ready Mermaid source, or {@code null} when there is nothing safe to render.
   * Strips any backtick fences the model may have wrapped around the source (which would otherwise
   * let it escape the ```mermaid block), then accepts the result only if it opens with a known
   * Mermaid diagram keyword and stays within the size bound.
   */
  private static String sanitizeDiagram(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    // Mermaid source never contains a backtick; dropping them all unwraps an accidental
    // ```mermaid fence and keeps a stray ``` from closing our fence early.
    String cleaned = raw.replace("`", "").strip();
    // An unwrapped fence leaves a bare "mermaid" language tag as the first line; drop it.
    if (cleaned.regionMatches(true, 0, "mermaid", 0, "mermaid".length())) {
      int firstBreak = cleaned.indexOf('\n');
      cleaned = firstBreak < 0 ? "" : cleaned.substring(firstBreak + 1).strip();
    }
    if (cleaned.isEmpty() || cleaned.length() > MAX_DIAGRAM_CHARS) {
      return null;
    }
    String firstLine = cleaned.lines().findFirst().orElse("").strip().toLowerCase(Locale.ROOT);
    boolean recognized = MERMAID_PREFIXES.stream().anyMatch(firstLine::startsWith);
    if (!recognized) {
      return null;
    }
    if (firstLine.startsWith("sequencediagram") && hasBracketLabeledParticipant(cleaned)) {
      return null;
    }
    return cleaned;
  }

  /**
   * True when any {@code participant}/{@code actor} declaration in the source carries a flowchart
   * bracket label — {@code participant O["Orchestrator"]} rather than the valid {@code participant
   * O as Orchestrator}. The stray {@code [} or &#123; is the flowchart-quoting rule leaking into a
   * sequence diagram, which GitHub cannot render.
   */
  private static boolean hasBracketLabeledParticipant(String diagram) {
    return diagram
        .lines()
        .map(line -> line.strip().toLowerCase(Locale.ROOT))
        .filter(line -> line.startsWith("participant ") || line.startsWith("actor "))
        .anyMatch(PrSummaryGenerator::hasBracketInAliasPosition);
  }

  /**
   * True when the alias of a participant/actor declaration carries a bracket — the leaked flowchart
   * shape {@code participant O["Orchestrator"]}, which never uses the {@code as} form. A valid
   * {@code participant O as Display} may legitimately carry brackets in its display name (e.g.
   * {@code as [User]}), so only the alias — the text before {@code " as "}, or the whole line when
   * there is no {@code " as "} — is inspected, to avoid dropping a renderable diagram.
   */
  private static boolean hasBracketInAliasPosition(String participantLine) {
    int asIndex = participantLine.indexOf(" as ");
    String alias = asIndex >= 0 ? participantLine.substring(0, asIndex) : participantLine;
    return alias.indexOf('[') >= 0 || alias.indexOf('{') >= 0;
  }

  /** Renders a signed line count, avoiding the awkward "+0"/"-0". */
  private static String signed(char sign, int count) {
    return count == 0 ? "0" : sign + Integer.toString(count);
  }
}
