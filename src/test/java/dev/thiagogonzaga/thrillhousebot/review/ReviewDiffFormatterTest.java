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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ReviewDiffFormatterTest {

  @Mock private ThrillhouseConfig config;
  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  private static GitHubPullRequestClient.FileDiff file(
      String name, String status, int additions, int deletions, String patch) {
    return new GitHubPullRequestClient.FileDiff(
        name, status, additions, deletions, additions + deletions, patch);
  }

  @Test
  void shouldBuildFromConfigConstructor() {
    MockitoAnnotations.openMocks(this);
    when(reviewConfig.ignoredFiles()).thenReturn(List.of("**/*.lock"));
    when(reviewConfig.maxDiffLines()).thenReturn(5000);
    when(config.review()).thenReturn(reviewConfig);

    var formatter = new ReviewDiffFormatter(config);

    assertTrue(formatter.isIgnored("yarn.lock"));
  }

  @Nested
  class IgnoredFiles {

    @Test
    void shouldSkipPatchForPackageLockJson() {
      var formatter = new ReviewDiffFormatter(List.of("**/package-lock.json"), 5000);
      var files =
          List.of(file("frontend/package-lock.json", "modified", 100, 50, "@@ huge lockfile diff"));

      var result = formatter.buildDiffString(files);

      assertTrue(result.contains("frontend/package-lock.json (modified, +100 -50)"));
      assertTrue(
          result.contains(
              "(frontend/package-lock.json skipped: matches ignored pattern, +100 -50)"));
      assertFalse(result.contains("```diff"));
      assertFalse(result.contains("huge lockfile diff"));
    }

    @Test
    void shouldSkipLockFilesAndIncludeSourceFiles() {
      var formatter =
          new ReviewDiffFormatter(List.of("**/package-lock.json", "**/*.lock", "**/pom.xml"), 5000);
      var files =
          List.of(
              file("pom.xml", "modified", 10, 2, "@@ pom diff"),
              file("src/Main.java", "modified", 5, 1, "@@ -1 +1,2 @@\n+change"));

      var result = formatter.buildDiffString(files);

      assertTrue(result.contains("(pom.xml skipped: matches ignored pattern, +10 -2)"));
      assertTrue(result.contains("```diff"));
      assertTrue(result.contains("+change"));
      assertFalse(result.contains("pom diff"));
    }

    @Test
    void shouldApplyIgnoredPatternsInBaseComparison() {
      var formatter = new ReviewDiffFormatter(List.of("**/*.generated.*"), 5000);
      var comparison =
          new GitHubPullRequestClient.CompareResponse(
              1,
              List.of(
                  file("src/out.generated.js", "modified", 500, 0, "@@ generated"),
                  file("src/handwritten.js", "modified", 2, 1, "@@ -1 +1,2 @@\n+ok")));

      var result = formatter.buildBaseComparison(comparison, "abcdefgh", "hijklmno");

      assertTrue(
          result.contains("(src/out.generated.js skipped: matches ignored pattern, +500 -0)"));
      assertTrue(result.contains("+ok"));
      assertFalse(result.contains("@@ generated"));
    }

    @Test
    void shouldExcludeIgnoredFilesFromReviewableList() {
      var formatter = new ReviewDiffFormatter(List.of("**/package-lock.json"), 5000);
      var files =
          List.of(
              file("frontend/package-lock.json", "modified", 100, 50, "@@ lock"),
              file("src/Main.java", "modified", 5, 1, "@@ patch"));

      var reviewable = formatter.reviewableFiles(files);

      assertEquals(1, reviewable.size());
      assertEquals("src/Main.java", reviewable.get(0).filename());
    }
  }

  @Nested
  class TruncationHelpers {

    @Test
    void shouldAppendPerFileOmittedLinesWhenFooterBudgetAllows() {
      var formatter = new ReviewDiffFormatter(List.of(), 8);
      var output = new StringBuilder("header\n");
      var omitted =
          List.of(
              file("second.java", "modified", 2, 1, "patch"),
              file("third.java", "modified", 3, 0, "patch2"));

      formatter.appendTruncationFooter(output, omitted);

      var result = output.toString();
      assertTrue(result.contains("(diff truncated at 8 lines — 2 files omitted)"));
      assertTrue(result.contains("(second.java omitted: +2 -1)"));
      assertTrue(result.contains("(third.java omitted: +3 -0)"));
    }

    @Test
    void shouldSkipFooterWhenNoLineBudgetRemains() {
      var formatter = new ReviewDiffFormatter(List.of(), 2);
      var output = new StringBuilder("line-one\nline-two\n");
      var omitted = List.of(file("other.java", "modified", 1, 0, "patch"));

      formatter.appendTruncationFooter(output, omitted);

      assertFalse(output.toString().contains("files omitted"));
    }

    @Test
    void shouldStopListingOmittedFilesWhenFooterBudgetRunsOut() {
      var formatter = new ReviewDiffFormatter(List.of(), 4);
      var output = new StringBuilder("x\n");
      var omitted =
          List.of(
              file("a.java", "modified", 1, 0, "p"),
              file("b.java", "modified", 2, 0, "p"),
              file("c.java", "modified", 3, 0, "p"));

      formatter.appendTruncationFooter(output, omitted);

      var result = output.toString();
      assertTrue(result.contains("files omitted"));
      assertTrue(result.contains("(a.java omitted: +1 -0)"));
      assertFalse(result.contains("(c.java omitted: +3 -0)"));
    }

    @Test
    void shouldReturnEmptyStringWhenTruncateBudgetIsZero() {
      assertEquals("", ReviewDiffFormatter.truncateSection("a\nb\n", 0));
    }

    @Test
    void shouldReturnFullSectionWhenItFitsTruncateBudget() {
      assertEquals("a\nb\n", ReviewDiffFormatter.truncateSection("a\nb\n", 3));
    }

    @Test
    void shouldReturnPatchTruncatedPlaceholderWhenBudgetIsOneLine() {
      assertEquals("(patch truncated)\n", ReviewDiffFormatter.truncateSection("a\nb\nc\n", 1));
    }

    @Test
    void shouldTruncateFencelessSectionWithEmDashNoticeWhenBudgetAboveOne() {
      // Sections without a ```diff fence (ignored / patch-less files): keep the first maxLines-1
      // lines, then an em-dash notice. The omitted count is the real lines dropped (c, d, e = 3),
      // not the split-array length, which would over-count the trailing empty element.
      assertEquals(
          "a\nb\n(patch truncated — 3 lines omitted)\n",
          ReviewDiffFormatter.truncateSection("a\nb\nc\nd\ne\n", 3));
    }

    @Test
    void shouldCountLinesForNullEmptyAndNewlineOnlyText() {
      assertEquals(0, ReviewDiffFormatter.lineCount(null));
      assertEquals(0, ReviewDiffFormatter.lineCount(""));
      assertEquals(0, ReviewDiffFormatter.lineCount("\n"));
    }

    @Test
    void shouldCountLinesWithoutTrailingNewline() {
      assertEquals(2, ReviewDiffFormatter.lineCount("a\nb"));
    }
  }

  @Nested
  class MaxDiffLines {

    @Test
    void shouldTruncateWhenDiffExceedsLimit() {
      var formatter = new ReviewDiffFormatter(List.of(), 12);
      var files =
          List.of(
              file("a.java", "modified", 1, 1, "line1\nline2\nline3\nline4\nline5"),
              file("b.java", "modified", 1, 1, "more1\nmore2\nmore3\nmore4\nmore5"));

      var result = formatter.buildDiffString(files);

      assertTrue(result.contains("(diff truncated at 12 lines — 1 files omitted)"));
      assertFalse(result.contains("more5"));
    }

    @Test
    void shouldNotTruncateWhenWithinLimit() {
      var formatter = new ReviewDiffFormatter(List.of(), 100);
      var files = List.of(file("a.java", "modified", 1, 1, "@@ patch\n+line"));

      var result = formatter.buildDiffString(files);

      assertFalse(result.contains("diff truncated"));
      assertTrue(result.contains("+line"));
    }

    @Test
    void shouldNotApplyLineLimitWhenMaxDiffLinesIsZero() {
      var formatter = new ReviewDiffFormatter(List.of(), 0);
      var files =
          List.of(
              file("a.java", "modified", 1, 1, "line1\nline2\nline3"),
              file("b.java", "modified", 1, 1, "tail-line"));

      var result = formatter.buildDiffString(files);

      assertTrue(result.contains("tail-line"));
      assertFalse(result.contains("diff truncated"));
    }

    @Test
    void shouldPartiallyTruncateFirstFileWhenBudgetExceeded() {
      var formatter = new ReviewDiffFormatter(List.of(), 12);
      var bigPatch = "line\n".repeat(15);
      var files =
          List.of(
              file("big.java", "modified", 15, 0, bigPatch),
              file("small.java", "modified", 1, 1, "+x"));

      var result = formatter.buildDiffString(files);

      assertTrue(result.contains("patch truncated"));
      assertTrue(result.contains("(diff truncated at 12 lines — 1 files omitted)"));
      assertFalse(result.contains("+x"));
    }

    @Test
    void shouldOmitEntireTailWhenOnlyFooterBudgetRemains() {
      var formatter = new ReviewDiffFormatter(List.of(), 10);
      var files =
          List.of(
              file("first.java", "modified", 1, 0, "one\ntwo\nthree\nfour\nfive\nsix\nseven"),
              file("second.java", "modified", 2, 0, "alpha\nbeta\ngamma\ndelta"),
              file("third.java", "modified", 3, 0, "tail"));

      var result = formatter.buildDiffString(files);

      assertTrue(result.contains("(diff truncated at 10 lines — 2 files omitted)"));
      assertFalse(result.contains("```diff\nalpha"));
      assertFalse(result.contains("tail"));
    }
  }

  @Nested
  class FenceClosing {

    private static final String TWO_HUNK_SECTION =
        "### big.java (modified, +6 -0)\n"
            + "```diff\n"
            + "@@ -1,3 +1,6 @@\n"
            + " a\n"
            + "+b\n"
            + "+c\n"
            + "@@ -10,2 +13,4 @@\n"
            + " d\n"
            + "+e\n"
            + "```\n\n";

    private static long fenceCount(String text) {
      return text.lines().filter(line -> line.startsWith("```")).count();
    }

    @Test
    void shouldReCloseFenceWhenTruncatingMidHunk() {
      // Budget 7: the first hunk (4 lines) does not fit, so the cut lands mid-hunk — the fence must
      // still be closed rather than left open.
      var truncated = ReviewDiffFormatter.truncateSection(TWO_HUNK_SECTION, 7);

      assertEquals(2, fenceCount(truncated), "opening fence must be balanced by a closing fence");
      assertTrue(truncated.endsWith("```\n"), "section must end with the closing fence");
      assertTrue(truncated.contains("patch truncated"));
      assertTrue(ReviewDiffFormatter.lineCount(truncated) <= 7, "must not exceed the line budget");
    }

    @Test
    void shouldCutAtHunkBoundaryWhenWholeHunkFits() {
      // Budget 8: the first hunk fits but the second does not, so the partial second hunk is
      // dropped
      // entirely at the @@ boundary.
      var truncated = ReviewDiffFormatter.truncateSection(TWO_HUNK_SECTION, 8);

      assertTrue(truncated.contains("@@ -1,3 +1,6 @@"), "first whole hunk is kept");
      assertTrue(truncated.contains("+c"), "first hunk content is kept in full");
      assertFalse(
          truncated.contains("@@ -10,2 +13,4 @@"), "partial second hunk dropped at boundary");
      assertFalse(truncated.contains("+e"), "no content leaks from the dropped hunk");
      assertEquals(2, fenceCount(truncated), "fence must be re-closed");
      assertTrue(truncated.endsWith("```\n"));
      assertTrue(ReviewDiffFormatter.lineCount(truncated) <= 8);
    }

    @Test
    void shouldCloseFenceForPatchWithNoHunkHeaders() {
      // No @@ headers, so alignToHunkBoundary finds no boundary and falls back to a raw cut — the
      // fence must still be re-closed (a partial patch inside a closed fence beats an open one).
      var section = "### r.java (modified, +5 -0)\n```diff\n+l1\n+l2\n+l3\n+l4\n+l5\n```\n\n";
      var truncated = ReviewDiffFormatter.truncateSection(section, 6);

      assertEquals(2, fenceCount(truncated), "fence must be re-closed even with no @@ headers");
      assertTrue(truncated.endsWith("```\n"));
      assertTrue(truncated.contains("+l1"));
      assertTrue(truncated.contains("+l2"));
      assertFalse(truncated.contains("+l3"));
      assertTrue(truncated.contains("patch truncated"));
      assertEquals(6, ReviewDiffFormatter.lineCount(truncated));
    }

    @Test
    void shouldNotOpenAFenceItCannotCloseWhenBudgetTooSmall() {
      // Budget 3 cannot fit header + fence + notice + closing fence, so no fence is emitted at all.
      var truncated = ReviewDiffFormatter.truncateSection(TWO_HUNK_SECTION, 3);

      assertEquals(0, fenceCount(truncated), "must not emit an unbalanced fence");
      assertTrue(truncated.contains("patch truncated"));
      assertTrue(ReviewDiffFormatter.lineCount(truncated) <= 3);
    }

    @Test
    void shouldKeepPromptFencesBalancedWhenLastFileIsTruncated() {
      var formatter = new ReviewDiffFormatter(List.of(), 12);
      var files =
          List.of(
              file(
                  "big.java",
                  "modified",
                  6,
                  0,
                  "@@ -1,3 +1,6 @@\n a\n+b\n+c\n@@ -20,2 +24,5 @@\n d\n+e\n+f\n+g"),
              file("small.java", "modified", 1, 0, "+x"));

      var result = formatter.buildDiffString(files);

      long fences = fenceCount(result);
      assertTrue(fences >= 2, "the included file's fence must be present");
      assertEquals(0, fences % 2, "every ```diff fence in the prompt must be closed");
      assertTrue(result.contains("patch truncated"));
      assertTrue(result.contains("(diff truncated at 12 lines — 1 files omitted)"));
    }

    @Test
    void shouldCloseFenceWhenSectionHasNoClosingFence() {
      // Defensive: a fenced section missing its closing ``` (closingFence falls through to
      // end-of-input) is still truncated with a freshly appended closing fence.
      var section = "### a.java (modified, +5 -0)\n```diff\n@@ -1,5 +1,5 @@\n+a\n+b\n+c\n+d\n+e";
      var truncated = ReviewDiffFormatter.truncateSection(section, 5);

      assertEquals(2, fenceCount(truncated), "fence must be re-closed even when input had none");
      assertTrue(truncated.endsWith("```\n"));
      assertTrue(truncated.contains("patch truncated"));
      assertTrue(ReviewDiffFormatter.lineCount(truncated) <= 5);
    }

    @Test
    void shouldKeepWholePatchAndReCloseFenceWhenOnlyTrailingBlanksOverflow() {
      // Budget equals the section's real line count: the entire patch body fits and only the
      // trailing blank lines overflow, so the body is kept intact and the fence is still re-closed.
      var section = "### a.java (modified, +2 -0)\n```diff\n@@ -1,1 +1,2 @@\n+x\n```\n\n";
      var truncated = ReviewDiffFormatter.truncateSection(section, 6);

      assertTrue(truncated.contains("@@ -1,1 +1,2 @@"), "hunk header kept");
      assertTrue(truncated.contains("+x"), "patch body kept intact");
      assertEquals(2, fenceCount(truncated), "fence re-closed");
      assertTrue(truncated.endsWith("```\n"));
      assertTrue(ReviewDiffFormatter.lineCount(truncated) <= 6);
    }
  }

  @Nested
  class EmptyAndEdgeCases {

    @Test
    void shouldReturnNoChangesForNullOrEmptyFiles() {
      var formatter = new ReviewDiffFormatter(List.of(), 5000);

      assertEquals("(no changes detected)", formatter.buildDiffString(null));
      assertEquals("(no changes detected)", formatter.buildDiffString(List.of()));
      assertTrue(formatter.reviewableFiles(null).isEmpty());
      assertTrue(formatter.reviewableFiles(List.of()).isEmpty());
    }

    @Test
    void shouldSkipNullPatchFilesInBaseComparison() {
      var formatter = new ReviewDiffFormatter(List.of(), 5000);
      var comparison =
          new GitHubPullRequestClient.CompareResponse(
              2,
              List.of(
                  file("binary.png", "modified", 0, 0, null),
                  file("src/App.java", "modified", 1, 0, "@@ -1 +1,2 @@\n+ok")));

      var result = formatter.buildBaseComparison(comparison, "abcdefgh", "hijklmno");

      assertTrue(result.contains("Commits between abcdefg..hijklmn: 2"));
      assertTrue(result.contains("+ok"));
      assertFalse(result.contains("binary.png"));
    }

    @Test
    void shouldFormatFileSectionWithoutPatch() {
      var formatter = new ReviewDiffFormatter(List.of(), 5000);
      var files = List.of(file("empty.java", "added", 0, 0, null));

      var result = formatter.buildDiffString(files);

      assertTrue(result.contains("empty.java (added, +0 -0)"));
      assertFalse(result.contains("```diff"));
    }

    @Test
    void shouldReturnNoChangesForEmptyComparison() {
      var formatter = new ReviewDiffFormatter(List.of(), 5000);
      var comparison = new GitHubPullRequestClient.CompareResponse(0, List.of());

      var result = formatter.buildBaseComparison(comparison, "abcdefgh", "hijklmno");

      assertEquals("(no changes between abcdefg and hijklmn)", result);
    }

    @Test
    void shouldTreatNullIgnoredPatternsAsEmpty() {
      var formatter = new ReviewDiffFormatter(null, 5000);

      assertFalse(formatter.isIgnored("src/Main.java"));
      assertEquals("(no changes detected)", formatter.buildDiffString(List.of()));
    }

    @Test
    void shouldFormatOverviewTotals() {
      var formatter = new ReviewDiffFormatter(List.of(), 5000);
      var files =
          List.of(
              file("a.java", "modified", 10, 2, "@@ a"), file("b.java", "added", 25, 0, "@@ b"));

      var result = formatter.buildDiffString(files);

      assertTrue(result.contains("## Overview: 2 files (+35 -2)"));
    }

    @Test
    void shouldIgnoreInvalidGlobPatterns() {
      var formatter = new ReviewDiffFormatter(List.of("**/[bad", "**/*.lock"), 5000);

      assertFalse(formatter.isIgnored("src/Main.java"));
      assertTrue(formatter.isIgnored("yarn.lock"));
    }

    @Test
    void shouldSkipNullAndBlankIgnoredPatterns() {
      var formatter = new ReviewDiffFormatter(Arrays.asList(null, "  ", "**/*.lock"), 5000);

      assertTrue(formatter.isIgnored("deps.lock"));
    }
  }

  @Nested
  class GlobMatching {

    @Test
    void shouldMatchViaPrimaryGlobPattern() {
      var formatter = new ReviewDiffFormatter(List.of("src/exact/Main.java"), 5000);

      assertTrue(formatter.isIgnored("src/exact/Main.java"));
      assertFalse(formatter.isIgnored("src/other/Main.java"));
    }

    @Test
    void shouldMatchViaSuffixSubpathForNestedTargetDirectory() {
      var formatter = new ReviewDiffFormatter(List.of("**/target/**"), 5000);

      assertTrue(formatter.isIgnored("module/target/classes/Foo.class"));
      assertFalse(formatter.isIgnored("module/src/Foo.java"));
    }

    @Test
    void shouldMatchTargetDirectoryAtRepositoryRoot() {
      var formatter = new ReviewDiffFormatter(List.of("**/target/**"), 5000);

      assertTrue(formatter.isIgnored("target/generated.txt"));
    }

    @Test
    void shouldMatchSuffixPatternAgainstFileNameOnly() {
      var formatter = new ReviewDiffFormatter(List.of("**/yarn.lock"), 5000);

      assertTrue(formatter.isIgnored("frontend/yarn.lock"));
      assertTrue(formatter.isIgnored("yarn.lock"));
    }

    @Test
    void shouldMatchViaSuffixWhenPrimaryGlobDoesNotMatchRootFile() {
      var pattern = "**/standalone.lock";
      Path path = Path.of("standalone.lock");
      var primaryMatches = FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(path);
      var formatter = new ReviewDiffFormatter(List.of(pattern), 5000);

      assertFalse(primaryMatches, "test requires primary glob to miss root-level file");
      assertTrue(formatter.isIgnored("standalone.lock"));
    }

    @Test
    void shouldSkipSuffixFileNameCheckWhenPathHasNoFileName() {
      var formatter = new ReviewDiffFormatter(List.of("**/*.lock"), 5000);

      assertFalse(formatter.isIgnored("/"));
    }

    @Test
    void shouldMatchSuffixViaIntermediateSubpath() {
      var formatter = new ReviewDiffFormatter(List.of("**/generated/**"), 5000);

      assertTrue(formatter.isIgnored("build/generated/sources/Foo.java"));
    }

    @Test
    void shouldTreatNullFilenameAsNotIgnored() {
      var formatter = new ReviewDiffFormatter(List.of("**/*.lock"), 5000);

      assertFalse(formatter.isIgnored(null));
      assertFalse(formatter.isIgnored("  "));
    }
  }

  @Test
  void buildRelatedTestsShouldListOnlyTestFiles() {
    var formatter = new ReviewDiffFormatter(java.util.List.of(), 5000);
    var files =
        java.util.List.of(
            new GitHubPullRequestClient.FileDiff("src/main/java/App.java", "modified", 1, 1, 2, ""),
            new GitHubPullRequestClient.FileDiff(
                "src/test/java/AppTest.java", "added", 1, 0, 1, ""),
            new GitHubPullRequestClient.FileDiff("pkg/server_test.go", "added", 1, 0, 1, ""),
            new GitHubPullRequestClient.FileDiff("web/button.spec.ts", "added", 1, 0, 1, ""),
            new GitHubPullRequestClient.FileDiff("scripts/test_utils.py", "added", 1, 0, 1, ""));

    var related = formatter.buildRelatedTests(files);

    assertFalse(related.contains("src/main/java/App.java"));
    assertTrue(related.contains("src/test/java/AppTest.java"));
    assertTrue(related.contains("pkg/server_test.go"));
    assertTrue(related.contains("web/button.spec.ts"));
    assertTrue(related.contains("scripts/test_utils.py"));
  }

  @Test
  void buildRelatedTestsShouldReturnEmptyWhenNoTests() {
    var formatter = new ReviewDiffFormatter(java.util.List.of(), 5000);
    assertEquals("", formatter.buildRelatedTests(null));
    assertEquals("", formatter.buildRelatedTests(java.util.List.of()));
    assertEquals(
        "",
        formatter.buildRelatedTests(
            java.util.List.of(
                new GitHubPullRequestClient.FileDiff("src/App.java", "modified", 1, 1, 2, ""))));
  }

  @Test
  void isTestFileShouldRejectNullAndNonTestNames() {
    assertFalse(ReviewDiffFormatter.isTestFile(null));
    assertFalse(ReviewDiffFormatter.isTestFile("src/main/java/Contest.md"));
    assertFalse(ReviewDiffFormatter.isTestFile("Makefile"));
    assertTrue(ReviewDiffFormatter.isTestFile("__tests__/foo.js"));
    assertTrue(ReviewDiffFormatter.isTestFile("ModuleTests.java"));
    assertTrue(ReviewDiffFormatter.isTestFile("src/tests/helper.rb"));
    assertTrue(ReviewDiffFormatter.isTestFile("web/foo.test.js"));
    assertTrue(ReviewDiffFormatter.isTestFile("test_data"));
    assertTrue(ReviewDiffFormatter.isTestFile("data_test.csv"));
    assertTrue(ReviewDiffFormatter.isTestFile("native/FooTest.kt"));
  }
}
