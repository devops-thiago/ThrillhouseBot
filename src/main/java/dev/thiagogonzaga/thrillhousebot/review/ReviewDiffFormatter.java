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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.PatternSyntaxException;

/**
 * Applies review-scoping rules from config: skip ignored file patterns and cap total diff lines
 * sent to the AI prompt.
 */
@ApplicationScoped
public class ReviewDiffFormatter {

  private record GlobMatcher(PathMatcher primary, PathMatcher suffix) {}

  private final List<GlobMatcher> globMatchers;
  private final int maxDiffLines;

  @Inject
  public ReviewDiffFormatter(ThrillhouseConfig config) {
    this(config.review().ignoredFiles(), config.review().maxDiffLines());
  }

  /** Visible for tests. */
  ReviewDiffFormatter(List<String> ignoredPatterns, int maxDiffLines) {
    this.globMatchers = compileGlobMatchers(ignoredPatterns);
    this.maxDiffLines = maxDiffLines;
  }

  private static List<GlobMatcher> compileGlobMatchers(List<String> patterns) {
    if (patterns == null || patterns.isEmpty()) {
      return List.of();
    }
    var matchers = new ArrayList<GlobMatcher>();
    for (String raw : patterns) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      var pattern = raw.trim();
      try {
        var primary = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        PathMatcher suffix =
            pattern.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3))
                : null;
        matchers.add(new GlobMatcher(primary, suffix));
      } catch (InvalidPathException | PatternSyntaxException e) {
        Log.warnf(e, "Ignoring invalid review.ignored-files pattern: %s", pattern);
      }
    }
    return List.copyOf(matchers);
  }

  boolean isIgnored(String filename) {
    if (filename == null || filename.isBlank() || globMatchers.isEmpty()) {
      return false;
    }
    Path path = Path.of(filename.replace('\\', '/'));
    for (GlobMatcher matcher : globMatchers) {
      if (matcher.primary().matches(path) || matchesSuffix(matcher.suffix(), path)) {
        return true;
      }
    }
    return false;
  }

  /** Matches `**`-prefixed patterns against the file name and every sub-path of the file. */
  private static boolean matchesSuffix(PathMatcher suffix, Path path) {
    if (suffix == null) {
      return false;
    }
    var fileName = path.getFileName();
    if (fileName != null && suffix.matches(fileName)) {
      return true;
    }
    for (var i = 0; i < path.getNameCount(); i++) {
      if (suffix.matches(path.subpath(i, path.getNameCount()))) {
        return true;
      }
    }
    return false;
  }

  /** Files that are included in AI review scope (non-ignored). */
  List<GitHubPullRequestClient.FileDiff> reviewableFiles(
      List<GitHubPullRequestClient.FileDiff> files) {
    if (files == null || files.isEmpty()) {
      return List.of();
    }
    return files.stream().filter(f -> !isIgnored(f.filename())).toList();
  }

  String buildDiffString(List<GitHubPullRequestClient.FileDiff> files) {
    if (files == null || files.isEmpty()) {
      return "(no changes detected)";
    }

    var totalAdditions = 0;
    var totalDeletions = 0;
    for (var file : files) {
      totalAdditions += file.additions();
      totalDeletions += file.deletions();
    }

    var header =
        String.format(
            "## Overview: %d files (+%d -%d)%n%n", files.size(), totalAdditions, totalDeletions);
    return formatWithLineBudget(header, files);
  }

  /**
   * Newline-separated list of test files in the PR, surfaced to the model as evidence of intended
   * behavior — a claim that changed code is broken must reconcile with tests that exercise it.
   */
  String buildRelatedTests(List<GitHubPullRequestClient.FileDiff> files) {
    if (files == null || files.isEmpty()) {
      return "";
    }
    List<String> tests =
        files.stream()
            .map(GitHubPullRequestClient.FileDiff::filename)
            .filter(ReviewDiffFormatter::isTestFile)
            .toList();
    return String.join("\n", tests);
  }

  static boolean isTestFile(String filename) {
    if (filename == null) {
      return false;
    }
    var lower = filename.toLowerCase(Locale.ROOT);
    var name = filename.substring(filename.lastIndexOf('/') + 1);
    var dot = name.lastIndexOf('.');
    String baseName = dot > 0 ? name.substring(0, dot) : name;
    var lowerName = name.toLowerCase(Locale.ROOT);
    return lower.contains("/test/")
        || lower.contains("/tests/")
        || lower.contains("__tests__/")
        || lowerName.startsWith("test_")
        || lowerName.contains(".test.")
        || lowerName.contains(".spec.")
        || baseName.toLowerCase(Locale.ROOT).endsWith("_test")
        // Case-sensitive on purpose: FooTest.java is a test, Contest.java is not
        || baseName.endsWith("Test")
        || baseName.endsWith("Tests");
  }

  String buildBaseComparison(
      GitHubPullRequestClient.CompareResponse comparison, String base, String head) {
    if (comparison.files().isEmpty()) {
      return "(no changes between " + base.substring(0, 7) + " and " + head.substring(0, 7) + ")";
    }

    var reviewable = comparison.files().stream().filter(f -> f.patch() != null).toList();
    var header =
        new StringBuilder("## Changes between base and head\n")
            .append(
                String.format(
                    "Commits between %s..%s: %d%n%n",
                    base.substring(0, 7), head.substring(0, 7), comparison.totalCommits()))
            .toString();
    return formatWithLineBudget(header, reviewable);
  }

  private String formatWithLineBudget(String header, List<GitHubPullRequestClient.FileDiff> files) {
    if (maxDiffLines <= 0) {
      var sb = new StringBuilder(header);
      for (var file : files) {
        sb.append(formatFileSection(file));
      }
      return sb.toString();
    }

    var output = new StringBuilder(header);
    var usedLines = lineCount(header);
    var omitted = new ArrayList<GitHubPullRequestClient.FileDiff>();

    for (var i = 0; i < files.size(); i++) {
      var file = files.get(i);
      var section = formatFileSection(file);
      var sectionLines = lineCount(section);

      if (usedLines + sectionLines <= maxDiffLines) {
        output.append(section);
        usedLines += sectionLines;
      } else {
        var remaining = maxDiffLines - usedLines;
        var tail = files.subList(i, files.size());
        int footerLines = tail.size() > 1 ? 2 : 1;
        if (remaining <= footerLines) {
          omitted.addAll(tail);
        } else {
          omitted.addAll(files.subList(i + 1, files.size()));
          var sectionBudget = Math.max(1, remaining - 1);
          String truncated = truncateSection(section, sectionBudget);
          output.append(truncated);
          usedLines += lineCount(truncated);
        }
        break;
      }
    }

    if (!omitted.isEmpty()) {
      appendTruncationFooter(output, omitted, usedLines);
      Log.warnf(
          "Diff truncated to %d lines (max: %d, %d files omitted)",
          maxDiffLines, maxDiffLines, omitted.size());
    }

    return output.toString();
  }

  void appendTruncationFooter(
      StringBuilder output, List<GitHubPullRequestClient.FileDiff> omitted) {
    appendTruncationFooter(output, omitted, lineCount(output.toString()));
  }

  void appendTruncationFooter(
      StringBuilder output, List<GitHubPullRequestClient.FileDiff> omitted, int usedLines) {
    if (maxDiffLines - usedLines < 1) {
      return;
    }

    String summary =
        String.format(
            "(diff truncated at %d lines — %d files omitted)", maxDiffLines, omitted.size());
    var summaryBlock = "\n" + summary + "\n";
    output.append(summaryBlock);
    usedLines += lineCount(summaryBlock);

    for (GitHubPullRequestClient.FileDiff file : omitted) {
      if (maxDiffLines - usedLines < 1) {
        break;
      }
      String line =
          String.format(
              "(%s omitted: +%d -%d)", file.filename(), file.additions(), file.deletions());
      var lineBlock = line + "\n";
      output.append(lineBlock);
      usedLines += lineCount(lineBlock);
    }
  }

  private String formatFileSection(GitHubPullRequestClient.FileDiff file) {
    var sb =
        new StringBuilder("### ")
            .append(file.filename())
            .append(" (")
            .append(file.status())
            .append(", +")
            .append(file.additions())
            .append(" -")
            .append(file.deletions())
            .append(")\n");
    if (isIgnored(file.filename())) {
      sb.append(
              String.format(
                  "(%s skipped: matches ignored pattern, +%d -%d)",
                  file.filename(), file.additions(), file.deletions()))
          .append("\n\n");
      return sb.toString();
    }
    if (file.patch() != null) {
      sb.append("```diff\n").append(file.patch()).append("\n```\n\n");
    }
    return sb.toString();
  }

  static String truncateSection(String section, int maxLines) {
    if (maxLines <= 0) {
      return "";
    }
    var lines = section.split("\n", -1);
    if (lines.length <= maxLines) {
      return section;
    }
    if (maxLines == 1) {
      return "(patch truncated)\n";
    }

    int fenceStart = diffFenceStart(lines);
    return fenceStart < 0
        ? truncatePlain(section, lines, maxLines)
        : truncateFenced(lines, fenceStart, maxLines);
  }

  /** Index of the opening ```` ```diff ```` fence, or -1 when the section has no fenced patch. */
  private static int diffFenceStart(String[] lines) {
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].startsWith("```")) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Raw line-count truncation for sections without a fenced patch (skipped or patch-less files).
   * The omitted count is measured with {@link #lineCount} so it ignores the trailing empty elements
   * {@code split("\n", -1)} produces for a section's {@code \n}-terminated tail.
   */
  private static String truncatePlain(String section, String[] lines, int maxLines) {
    var sb = new StringBuilder();
    var contentLines = maxLines - 1;
    for (var i = 0; i < contentLines; i++) {
      sb.append(lines[i]).append('\n');
    }
    sb.append("(patch truncated — ")
        .append(lineCount(section) - contentLines)
        .append(" lines omitted)\n");
    return sb.toString();
  }

  /**
   * Truncates a section whose patch is wrapped in a ```` ```diff ```` fence. Prefers to cut at a
   * hunk boundary so no partial hunk is shown, and always re-closes the fence so the surrounding
   * prompt never carries an open code block. The result stays within {@code maxLines}.
   *
   * <p>Assumes the section was produced by {@link #formatFileSection}: a single {@code
   * \n}-delimited ```` ```diff ```` block whose body is a GitHub unified-diff patch (lines prefixed
   * with a space, {@code +}, {@code -}, or {@code @@}), so the only bare {@code ```} line is the
   * closing fence.
   */
  private static String truncateFenced(String[] lines, int fenceStart, int maxLines) {
    int patchStart = fenceStart + 1;
    int fenceEnd = closingFence(lines, patchStart);
    int patchCount = Math.max(0, fenceEnd - patchStart);

    // Reserve lines for the prefix (header + opening fence), the notice, and the closing fence.
    int patchBudget = maxLines - patchStart - 2;
    if (patchBudget < 1) {
      return truncateWithoutFence(lines, fenceStart, patchCount, maxLines);
    }

    int keep =
        alignToHunkBoundary(lines, patchStart, patchCount, Math.min(patchBudget, patchCount));

    var sb = new StringBuilder();
    for (var i = 0; i < patchStart; i++) {
      sb.append(lines[i]).append('\n');
    }
    for (var i = 0; i < keep; i++) {
      sb.append(lines[patchStart + i]).append('\n');
    }
    sb.append("(patch truncated — ").append(patchCount - keep).append(" lines omitted)\n");
    sb.append("```\n");
    return sb.toString();
  }

  /**
   * Index of the closing ```` ``` ```` fence at or after {@code from}, or end-of-array if absent.
   */
  private static int closingFence(String[] lines, int from) {
    for (var i = from; i < lines.length; i++) {
      if (lines[i].equals("```")) {
        return i;
      }
    }
    return lines.length;
  }

  /**
   * Pulls the cut point back to the start of the hunk it lands in so a partial trailing hunk is
   * dropped rather than shown half-formed. Falls back to the raw cut when even the first hunk
   * overflows the budget — a partial hunk inside a closed fence still beats an empty one.
   */
  private static int alignToHunkBoundary(String[] lines, int patchStart, int patchCount, int keep) {
    if (keep >= patchCount) {
      return keep;
    }
    int aligned = keep;
    while (aligned > 0 && !lines[patchStart + aligned].startsWith("@@")) {
      aligned--;
    }
    return aligned > 0 ? aligned : keep;
  }

  /** Degrades a fenced section to header + notice (no fence) when the budget is too small. */
  private static String truncateWithoutFence(
      String[] lines, int fenceStart, int patchCount, int maxLines) {
    var sb = new StringBuilder();
    int headerLines = Math.min(fenceStart, maxLines - 1);
    for (var i = 0; i < headerLines; i++) {
      sb.append(lines[i]).append('\n');
    }
    sb.append("(patch truncated — ").append(patchCount).append(" lines omitted)\n");
    return sb.toString();
  }

  static int lineCount(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    String normalized = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    if (normalized.isEmpty()) {
      return 0;
    }
    return (int) normalized.chars().filter(ch -> ch == '\n').count() + 1;
  }
}
