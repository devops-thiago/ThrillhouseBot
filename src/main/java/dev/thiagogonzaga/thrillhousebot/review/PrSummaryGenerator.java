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

  // The walkthrough-diagram master switch, read once. The render is gated on it as well as the
  // prompt request, so the documented "no diagram is requested or rendered unless enabled" holds
  // even if the model volunteers a walkthrough_diagram the prompt never asked for.
  private final boolean diagramEnabled;

  @Inject
  public PrSummaryGenerator(ThrillhouseConfig config) {
    this(config.review().diagram().enabled());
  }

  /** Visible for tests. */
  PrSummaryGenerator(boolean diagramEnabled) {
    this.diagramEnabled = diagramEnabled;
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
   */
  static final int MAX_FILE_ROWS = 20;

  /** One changed file in the walkthrough: its path and the diff's authoritative change type. */
  public record ChangedFile(String path, String changeType) {}

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

  public String generate(
      int filesChanged,
      int additions,
      int deletions,
      List<ChangedFile> changedFiles,
      ReviewResponse.Summary aiSummary,
      ReviewResult result) {
    var sb = new StringBuilder();
    sb.append(SUMMARY_HEADING).append("\n\n");

    appendPrPurpose(sb, aiSummary);
    appendDescriptionGaps(sb, aiSummary);
    appendWalkthroughDiagram(sb, aiSummary);

    sb.append("### Changes Overview\n");
    sb.append("- **Files changed:** ").append(filesChanged).append("\n");
    sb.append("- **Lines added:** ").append(signed('+', additions)).append("\n");
    sb.append("- **Lines removed:** ").append(signed('-', deletions)).append("\n\n");

    appendChangedFiles(sb, changedFiles, aiSummary);

    sb.append("### Risk Assessment\n");
    sb.append("| Risk | Count |\n");
    sb.append("|------|-------|\n");
    sb.append("| 🔴 Critical | ").append(result.criticalCount()).append(" |\n");
    sb.append("| 🟠 High | ").append(result.highCount()).append(" |\n");
    sb.append("| 🟡 Medium | ").append(result.mediumCount()).append(" |\n");
    sb.append("| 🔵 Low | ").append(result.lowCount()).append(" |\n\n");

    appendPreviousFindings(sb, result);
    appendFindingsOrCelebration(sb, result);
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
    sb.append("### Previous Findings Status\n");
    sb.append("| Status | Count |\n");
    sb.append("|--------|-------|\n");
    sb.append("| ✅ Resolved | ").append(resolved).append(" |\n");
    sb.append("| ⚠️ Still present | ").append(unresolved).append(" |\n");
    sb.append("| 💬 Justified | ").append(justified).append(" |\n\n");
  }

  private static void appendFindingsOrCelebration(StringBuilder sb, ReviewResult result) {
    if (result.hasIssues()) {
      sb.append("### Key Findings\n");
      List<Finding> topFindings =
          result.findings().stream()
              .sorted((a, b) -> a.risk().compareTo(b.risk()))
              .limit(5)
              .toList();
      for (Finding f : topFindings) {
        sb.append("- **")
            .append(f.risk().name())
            .append(":** ")
            .append(f.title())
            .append(" (`")
            .append(f.file())
            .append(":")
            .append(f.line())
            .append("`)\n");
      }
      sb.append("\n");
    } else if (hasNoUnresolvedPrevious(result)) {
      if (result.ciHoldsApproval()) {
        sb.append(
            "No new issues found in this PR, but the review cannot be approved until required CI is confirmed green.\n\n");
      } else if (result.truncated()) {
        sb.append(
            "No new issues found in the reviewed portion of this PR — but the diff was too large to review in full, so this is a partial review.\n\n");
      } else {
        sb.append(ZERO_ISSUES_MESSAGE).append("\n\n");
      }
    }
  }

  private static void appendCiChecks(StringBuilder sb, ReviewResult result) {
    if (!result.offendingCiChecks().isEmpty()) {
      sb.append("### ⚠️ Required CI Checks Status\n");
      sb.append("Some required checks are still pending or have failed:\n\n");
      sb.append("| Check | Type | Status | Detail |\n");
      sb.append("|-------|------|--------|--------|\n");
      for (var check : result.offendingCiChecks()) {
        String statusEmoji = check.isFailing() ? "❌ Failed" : "⏳ Pending";
        String detail = check.conclusion() != null ? check.conclusion() : "-";
        sb.append("| **")
            .append(escapeTableCell(check.name()))
            .append("** | ")
            .append(escapeTableCell(check.type()))
            .append(" | ")
            .append(statusEmoji)
            .append(" | ")
            .append(escapeTableCell(detail))
            .append(" |\n");
      }
      sb.append("\n");
    }
    // Unreadable CI is a distinct hold from an offending check: render it as its own note rather
    // than as a counterfeit row in the required-checks table (#253/#6).
    if (result.ciUnreadable()) {
      sb.append("### ⚠️ CI Status Unavailable\n");
      sb.append(
          "The CI status could not be read from GitHub, so approval is held until it can be"
              + " confirmed.\n\n");
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
    sb.append(aiSummary.prPurpose().strip()).append("\n\n");
  }

  private static void appendDescriptionGaps(StringBuilder sb, ReviewResponse.Summary aiSummary) {
    if (aiSummary == null) {
      return;
    }
    // List.copyOf in the Summary constructor guarantees no null elements
    List<String> gaps = aiSummary.descriptionGaps().stream().filter(g -> !g.isBlank()).toList();
    if (gaps.isEmpty()) {
      return;
    }
    sb.append("### ⚠️ Description vs. Implementation\n");
    sb.append("The PR description does not fully match the change:\n");
    for (String gap : gaps) {
      sb.append("- ").append(gap.strip()).append("\n");
    }
    sb.append("\n");
  }

  /**
   * Renders the file-by-file walkthrough as a table of (file, change type, one-line summary). The
   * change type comes from the diff (authoritative); the summary is the model's per-file note,
   * matched by path. Files without a model summary still appear, so the table always mirrors the
   * actual change set. Bounded to {@link #MAX_FILE_ROWS} rows to respect the comment-size budget.
   */
  private static void appendChangedFiles(
      StringBuilder sb, List<ChangedFile> changedFiles, ReviewResponse.Summary aiSummary) {
    if (changedFiles == null || changedFiles.isEmpty()) {
      return;
    }
    Map<String, String> summaryByPath = summariesByPath(aiSummary);

    sb.append("### Changed Files\n");
    sb.append("| File | Change | Summary |\n");
    sb.append("|------|--------|---------|\n");
    for (ChangedFile file : changedFiles.stream().limit(MAX_FILE_ROWS).toList()) {
      String summary = summaryByPath.getOrDefault(file.path(), "");
      sb.append("| `")
          .append(escapeTableCell(file.path()))
          .append("` | ")
          .append(changeTypeLabel(file.changeType()))
          .append(" | ")
          .append(summary.isBlank() ? "-" : escapeTableCell(summary.strip()))
          .append(" |\n");
    }
    int overflow = changedFiles.size() - MAX_FILE_ROWS;
    if (overflow > 0) {
      sb.append("\n_…and ").append(overflow).append(" more file(s)._\n");
    }
    sb.append("\n");
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
      default -> escapeTableCell(status);
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
    // Drop every backtick run: Mermaid source never contains one, and removing them both unwraps an
    // accidental ```mermaid ... ``` and prevents a stray ``` from closing our fence early.
    String cleaned = raw.replace("`", "").strip();
    // Unwrapping a ```mermaid fence leaves a bare "mermaid" language tag as the first line; drop it
    // so the diagram keyword underneath is what gets validated.
    if (cleaned.regionMatches(true, 0, "mermaid", 0, "mermaid".length())) {
      int firstBreak = cleaned.indexOf('\n');
      cleaned = firstBreak < 0 ? "" : cleaned.substring(firstBreak + 1).strip();
    }
    if (cleaned.isEmpty() || cleaned.length() > MAX_DIAGRAM_CHARS) {
      return null;
    }
    String firstLine = cleaned.lines().findFirst().orElse("").strip().toLowerCase(Locale.ROOT);
    boolean recognized = MERMAID_PREFIXES.stream().anyMatch(firstLine::startsWith);
    return recognized ? cleaned : null;
  }

  /** Renders a signed line count, avoiding the awkward "+0"/"-0". */
  private static String signed(char sign, int count) {
    return count == 0 ? "0" : sign + Integer.toString(count);
  }

  /**
   * Escapes a value for a single Markdown table cell: a literal {@code '|'} would break the column
   * and a line break would break the whole row, so the pipe is escaped and any run of line breaks
   * is folded into a single space (these are one-line cells).
   */
  private static String escapeTableCell(String value) {
    if (value == null) {
      return "-";
    }
    return value.replace("\\", "\\\\").replace("|", "\\|").replaceAll("[\r\n]+", " ");
  }
}
