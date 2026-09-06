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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Applies review-scoping rules from config: skip ignored file patterns, and optionally cap total
 * diff lines for single-call renders (on-demand commands, maintainer replies, and
 * budgeting-disabled legacy review). Token-budgeted reviews do not use the line cap — {@link
 * DiffBudgetPlanner} owns coverage by tokens per request.
 */
@ApplicationScoped
public class ReviewDiffFormatter {

  /**
   * Logger pinned to this class so the glob warning emitted from {@link IgnoreGlobs} keeps the
   * category operators already filter on: the build-time {@code Log} facade binds its category to
   * the class holding the call, which for a nested type would silently relabel the WARN to {@code
   * ReviewDiffFormatter$IgnoreGlobs}.
   */
  private static final Logger LOG = Logger.getLogger(ReviewDiffFormatter.class);

  record GlobMatcher(PathMatcher primary, PathMatcher suffix) {}

  /**
   * A compiled set of ignore globs — the single glob-matching implementation in the codebase. The
   * deployment-wide {@code thrillhousebot.review.ignored-files} list and the extra patterns a
   * repository declares for itself are both compiled and matched through here, so a repository can
   * never end up with different matching semantics than the global default.
   *
   * <p>{@link PathScopedInstructions} matches path-scoped review rules through the same type (one
   * single-pattern set per declared scope) rather than adding a second matcher, so a scope glob and
   * an ignore glob mean the same thing to a maintainer.
   *
   * <p>Patterns are gitignore-style, which both keys have always been documented as (#481). Raw
   * Java NIO globs are not: under them {@code build/} matched nothing at all, a bare {@code vendor}
   * matched only a file literally named that, and {@code *.lock} matched only a root-level one.
   * Those three idioms failed <em>open</em> — files the repository asked to exclude were sent to
   * the model anyway — and silently, since a pattern that compiles but matches nothing produced no
   * log line at any level. {@link #gitignoreForms} normalizes a declared pattern into the NIO-glob
   * forms that reproduce gitignore's reading before anything is compiled.
   */
  record IgnoreGlobs(List<GlobMatcher> matchers) {

    static final IgnoreGlobs NONE = new IgnoreGlobs(List.of());

    IgnoreGlobs {
      matchers = List.copyOf(matchers);
    }

    static IgnoreGlobs compile(List<String> patterns) {
      var compiled = compileGlobMatchers(patterns);
      return compiled.isEmpty() ? NONE : new IgnoreGlobs(compiled);
    }

    /**
     * Compiles the usable patterns, dropping blank, negated and invalid ones. Warns through {@link
     * #LOG}, pinned to the enclosing class, so the operator-facing category is unchanged by this
     * method living here.
     *
     * <p>Each declaration is judged on its own by {@link #compileDeclaration}, so one unusable
     * pattern costs only itself and never the rest of the list.
     */
    private static List<GlobMatcher> compileGlobMatchers(List<String> patterns) {
      if (patterns == null || patterns.isEmpty()) {
        return List.of();
      }
      var matchers = new ArrayList<GlobMatcher>();
      for (String raw : patterns) {
        matchers.addAll(compileDeclaration(raw));
      }
      return List.copyOf(matchers);
    }

    /**
     * The matchers one declaration contributes — empty when it contributes none, which is every way
     * a pattern can be unusable: blank, negated, naming no path, or failing to compile. Kept apart
     * from the loop above so each of those verdicts is reached by returning where it is decided,
     * rather than by four exits out of a single body.
     *
     * <p>The whole-or-nothing rule lives here too: every form is compiled into a local list before
     * any of it is handed back, so a declaration whose later form is invalid contributes nothing at
     * all instead of a partial matcher set that would exclude a different set of files than the one
     * the maintainer wrote.
     */
    private static List<GlobMatcher> compileDeclaration(String raw) {
      if (raw == null || raw.isBlank()) {
        return List.of();
      }
      var pattern = raw.trim();
      if (pattern.startsWith("!")) {
        // gitignore's re-include, which this model cannot honor: the effective set is global ∪
        // per-repo and nothing may put back a file the union excludes. Compiling it would build a
        // matcher for a file literally named "!…" — the silent nonsense #481 is about.
        LOG.warnf(
            "Ignoring negated ignore glob: re-includes are not supported, the effective ignore"
                + " set is only ever added to: %s",
            pattern);
        return List.of();
      }
      var forms = gitignoreForms(pattern);
      if (forms.isEmpty()) {
        LOG.warnf("Ignoring ignore glob that names no path: %s", pattern);
        return List.of();
      }
      try {
        var compiled = new ArrayList<GlobMatcher>(forms.size());
        for (String form : forms) {
          compiled.add(compileForm(form));
        }
        return compiled;
      } catch (InvalidPathException | PatternSyntaxException e) {
        LOG.warnf(e, "Ignoring invalid ignored-files glob pattern: %s", pattern);
        return List.of();
      }
    }

    /**
     * One NIO glob, plus the {@code **}-prefix fallback: a {@code glob:**}{@code /x} pattern does
     * not match a root-level {@code x} (nothing is there for the {@code **} to consume), so the
     * remainder is compiled separately and matched against the file name and every sub-path by
     * {@link #matchesSuffix}. Every "at any depth" form {@link #gitignoreForms} produces carries
     * that prefix, so this is what makes an unanchored pattern reach the repository root too.
     */
    private static GlobMatcher compileForm(String form) {
      var primary = FileSystems.getDefault().getPathMatcher("glob:" + form);
      PathMatcher suffix =
          form.startsWith("**/")
              ? FileSystems.getDefault().getPathMatcher("glob:" + form.substring(3))
              : null;
      return new GlobMatcher(primary, suffix);
    }

    /**
     * The NIO glob forms one gitignore-style declaration expands to — the whole of #481's matcher
     * fix, kept in one pure function so the semantics can be read (and tested) without a file list.
     * Four rules, straight out of {@code gitignore(5)}:
     *
     * <ul>
     *   <li>a leading {@code /} anchors to the repository root and is then dropped, rather than
     *       compiling to an absolute path that matches nothing;
     *   <li>a pattern carrying a {@code /} anywhere else is likewise anchored — {@code
     *       generated/**} is <em>this</em> repository's {@code generated} tree, which is also the
     *       only reading under which the documented {@code docs/generated/**} keeps its meaning;
     *   <li>anything else matches at every depth, expressed as the {@code **}{@code /} prefix the
     *       shipped defaults already spell out by hand;
     *   <li>a trailing {@code /} means "directory", so only the tree under it is produced; without
     *       one, the pattern names a file <em>or</em> a directory, so both forms are produced.
     * </ul>
     *
     * <p>A declaration already ending in {@code **} is a tree pattern as written and is left alone,
     * which keeps every shipped default ({@code **}{@code /target/**} and its siblings) compiling
     * to exactly the one matcher it compiled to before.
     */
    static List<String> gitignoreForms(String pattern) {
      var start = 0;
      while (start < pattern.length() && pattern.charAt(start) == '/') {
        start++;
      }
      var end = pattern.length();
      while (end > start && pattern.charAt(end - 1) == '/') {
        end--;
      }
      var core = pattern.substring(start, end);
      if (core.isEmpty()) {
        return List.of();
      }
      var anchored = start > 0 || core.indexOf('/') >= 0;
      var directoryOnly = end < pattern.length();
      var prefix = anchored ? "" : "**/";
      if (core.endsWith("**")) {
        return List.of(prefix + core);
      }
      return directoryOnly
          ? List.of(prefix + core + "/**")
          : List.of(prefix + core, prefix + core + "/**");
    }

    /**
     * Global ∪ per-repo. Per-repo patterns are strictly additive: the union can only ever take more
     * files out of review scope, never put back a file the global list excludes.
     */
    IgnoreGlobs union(IgnoreGlobs other) {
      if (other.matchers.isEmpty()) {
        return this;
      }
      if (matchers.isEmpty()) {
        return other;
      }
      var merged = new ArrayList<GlobMatcher>(matchers.size() + other.matchers.size());
      merged.addAll(matchers);
      merged.addAll(other.matchers);
      return new IgnoreGlobs(merged);
    }

    boolean matches(String filename) {
      if (filename == null || filename.isBlank() || matchers.isEmpty()) {
        return false;
      }
      Path path = Path.of(filename.replace('\\', '/'));
      for (GlobMatcher matcher : matchers) {
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
  }

  /** A formatted diff plus the number of files the line budget dropped (0 when nothing omitted). */
  record FormattedDiff(String text, int omittedFiles) {
    boolean truncated() {
      return omittedFiles > 0;
    }
  }

  private final IgnoreGlobs globalGlobs;
  private final int maxDiffLines;

  @Inject
  public ReviewDiffFormatter(ThrillhouseConfig config) {
    this(config.review().ignoredFiles(), config.review().maxDiffLines());
  }

  /** Visible for tests. */
  ReviewDiffFormatter(List<String> ignoredPatterns, int maxDiffLines) {
    this.globalGlobs = IgnoreGlobs.compile(ignoredPatterns);
    this.maxDiffLines = maxDiffLines;
  }

  /**
   * The ignore set for one operation: the global {@code review.ignored-files} list unioned with the
   * extra globs the repository declared for itself. Compile it once per review and hand the result
   * to {@link #reviewableFiles(List, IgnoreGlobs)} so the globs stay walked a single time.
   *
   * <p>An unparseable or empty per-repo list degrades to the global set — a repository can never
   * shrink or replace the deployment default, and a bad pattern in its list is dropped by {@link
   * IgnoreGlobs#compileGlobMatchers} rather than failing the review.
   */
  IgnoreGlobs ignoreGlobs(List<String> perRepoPatterns) {
    if (perRepoPatterns == null || perRepoPatterns.isEmpty()) {
      return globalGlobs;
    }
    return globalGlobs.union(IgnoreGlobs.compile(perRepoPatterns));
  }

  boolean isIgnored(String filename) {
    return globalGlobs.matches(filename);
  }

  /**
   * The patterns in {@code declared} that match none of {@code filenames}, in declaration order —
   * the maintainer-facing half of #481. A glob that excludes nothing is not an error the parser can
   * see: it compiles, it is applied, and it silently does nothing, which is exactly how a mistyped
   * or wrongly-shaped declaration used to survive release after release.
   *
   * <p>Only a repository's own declarations are worth reporting: the deployment default list is
   * written for every repository at once, so most of it legitimately matches nothing in any one
   * pull request. Each pattern is compiled on its own (the shared set short-circuits on the first
   * match and could not attribute a hit), which is why this runs once per review, off the declared
   * list only, and never per finding.
   */
  static List<String> unmatchedPatterns(List<String> declared, List<String> filenames) {
    if (declared == null || declared.isEmpty() || filenames == null || filenames.isEmpty()) {
      return List.of();
    }
    var unmatched = new ArrayList<String>();
    for (String raw : declared) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      // An uncompilable pattern compiles to an empty set, which matches nothing — it belongs in
      // this list too, since its own warning never leaves the log.
      var globs = IgnoreGlobs.compile(List.of(raw));
      if (filenames.stream().noneMatch(globs::matches)) {
        unmatched.add(raw.trim());
      }
    }
    return List.copyOf(unmatched);
  }

  /**
   * One-line disclosure for the summary's review-scope note, or {@code ""} when every declared glob
   * matched something. Names the globs rather than only counting them: the count says something is
   * wrong, the names say which line to fix. Capped like the pure-rename rollup so a long list
   * cannot dominate the summary, and each glob is neutralized — it is repository-authored text
   * being spliced into a rendered comment.
   */
  static String formatUnmatchedIgnoreGlobs(List<String> unmatched) {
    if (unmatched == null || unmatched.isEmpty()) {
      return "";
    }
    final int sampleCap = 5;
    var samples =
        unmatched.stream()
            .limit(sampleCap)
            .map(glob -> "`" + MarkdownSafe.inlineCode(glob) + "`")
            .toList();
    var sb =
        new StringBuilder()
            .append(unmatched.size())
            .append(unmatched.size() == 1 ? " ignore glob" : " ignore globs")
            .append(" declared in this repository's ThrillhouseBot config matched no file in this")
            .append(" pull request (")
            .append(String.join(", ", samples));
    var more = unmatched.size() - samples.size();
    if (more > 0) {
      sb.append(", and ").append(more).append(" more");
    }
    return sb.append(')').toString();
  }

  /**
   * Whether a set of file names holds this file's name, tolerating a null name the way {@link
   * IgnoreGlobs#matches(String)} does — {@code filename()} on a Jackson-deserialized {@link
   * GitHubPullRequestClient.FileDiff} is not validated at construction (its {@code patch} and
   * {@code previousFilename} siblings are legitimately null), and the name sets these lookups run
   * against are immutable, whose {@code contains(null)} throws instead of returning false. A file
   * with no name is simply not in the set.
   */
  static boolean namesContain(Set<String> names, String filename) {
    return filename != null && names.contains(filename);
  }

  /**
   * Pure rename: GitHub reports {@code status=renamed} with no content hunks. These burn AI budget
   * without anything to review — exclude them from model input (see #386). Rename+edit (non-empty
   * patch and/or non-zero add/del) is still reviewable.
   */
  static boolean isPureRename(GitHubPullRequestClient.FileDiff file) {
    if (file == null || file.status() == null) {
      return false;
    }
    if (!"renamed".equalsIgnoreCase(file.status())) {
      return false;
    }
    if (file.additions() + file.deletions() != 0) {
      return false;
    }
    return file.patch() == null || file.patch().isBlank();
  }

  /** Pure renames in {@code files}, preserving order. */
  static List<GitHubPullRequestClient.FileDiff> pureRenameFiles(
      List<GitHubPullRequestClient.FileDiff> files) {
    if (files == null || files.isEmpty()) {
      return List.of();
    }
    return files.stream().filter(ReviewDiffFormatter::isPureRename).toList();
  }

  /**
   * One-line disclosure for the summary / diff overview. Caps the path sample so bulk package moves
   * do not dominate the prompt.
   */
  static String formatPureRenameRollup(List<GitHubPullRequestClient.FileDiff> pureRenames) {
    if (pureRenames == null || pureRenames.isEmpty()) {
      return "";
    }
    final int sampleCap = 5;
    var samples = new ArrayList<String>(Math.min(sampleCap, pureRenames.size()));
    for (var i = 0; i < pureRenames.size() && samples.size() < sampleCap; i++) {
      var file = pureRenames.get(i);
      var prev = file.previousFilename();
      if (prev != null && !prev.isBlank()) {
        samples.add(prev + " → " + file.filename());
      } else {
        samples.add(file.filename());
      }
    }
    var sb = new StringBuilder();
    sb.append(pureRenames.size())
        .append(pureRenames.size() == 1 ? " pure rename" : " pure renames")
        .append(" omitted from AI review (")
        .append(String.join(", ", samples));
    var more = pureRenames.size() - samples.size();
    if (more > 0) {
      sb.append(", and ").append(more).append(" more");
    }
    sb.append(")\n");
    return sb.toString();
  }

  /**
   * Files that are included in AI review scope (non-ignored, non–pure-rename), using the global
   * ignore list only.
   */
  List<GitHubPullRequestClient.FileDiff> reviewableFiles(
      List<GitHubPullRequestClient.FileDiff> files) {
    return reviewableFiles(files, globalGlobs);
  }

  /**
   * Same, but scoped by an explicit ignore set — normally {@link #ignoreGlobs(List)} applied to the
   * patterns the repository declared, so its own globs are honored on top of the global list.
   */
  List<GitHubPullRequestClient.FileDiff> reviewableFiles(
      List<GitHubPullRequestClient.FileDiff> files, IgnoreGlobs globs) {
    if (files == null || files.isEmpty()) {
      return List.of();
    }
    var effective = globs == null ? globalGlobs : globs;
    return files.stream()
        .filter(f -> !effective.matches(f.filename()))
        .filter(f -> !isPureRename(f))
        .toList();
  }

  /**
   * Reviewable files plus pure renames — for prompt context that should still see moved paths
   * (related-tests list, summary walkthrough counts) without putting empty rename hunks in the
   * model diff.
   */
  static List<GitHubPullRequestClient.FileDiff> withPureRenames(
      List<GitHubPullRequestClient.FileDiff> reviewable,
      List<GitHubPullRequestClient.FileDiff> allFiles) {
    var renames = pureRenameFiles(allFiles);
    if (renames.isEmpty()) {
      return reviewable;
    }
    var merged =
        new ArrayList<GitHubPullRequestClient.FileDiff>(reviewable.size() + renames.size());
    merged.addAll(reviewable);
    merged.addAll(renames);
    return merged;
  }

  /**
   * The non-blank patch text of each reviewable file, keyed by filename — the map a {@link
   * DiffLineResolver} parses to map AI-reported line numbers back onto the diff. Ignored files are
   * dropped, so the resolver never anchors a comment to a file outside review scope.
   */
  Map<String, String> patchesByFile(List<GitHubPullRequestClient.FileDiff> files) {
    return patchesByReviewableFiles(reviewableFiles(files));
  }

  /**
   * Same as {@link #patchesByFile} but for a list already filtered to reviewable files, so the
   * ignore-glob filter is not walked a second time when the caller has already computed it.
   */
  Map<String, String> patchesByReviewableFiles(
      List<GitHubPullRequestClient.FileDiff> reviewableFiles) {
    var patches = new HashMap<String, String>();
    for (var file : reviewableFiles) {
      if (file.patch() != null && !file.patch().isBlank()) {
        patches.put(file.filename(), file.patch());
      }
    }
    return patches;
  }

  String buildDiffString(List<GitHubPullRequestClient.FileDiff> files) {
    return buildDiffStringWithStats(files).text();
  }

  /** Like {@link #buildDiffString} but also reports how many files the line budget omitted. */
  FormattedDiff buildDiffStringWithStats(List<GitHubPullRequestClient.FileDiff> files) {
    return buildDiffStringWithStats(files, reviewableFiles(files));
  }

  /**
   * Like {@link #buildDiffStringWithStats(List)} but reuses an already-computed reviewable-file
   * list instead of re-running the ignore-glob filter — the caller (the context loader) computes it
   * once for the line resolver and the diff render alike, so the glob is walked a single time per
   * review.
   */
  FormattedDiff buildDiffStringWithStats(
      List<GitHubPullRequestClient.FileDiff> files,
      List<GitHubPullRequestClient.FileDiff> reviewableFiles) {
    if (files == null || files.isEmpty()) {
      return new FormattedDiff("(no changes detected)", 0);
    }

    var totalAdditions = 0;
    var totalDeletions = 0;
    for (var file : files) {
      totalAdditions += file.additions();
      totalDeletions += file.deletions();
    }

    var pureRenames = pureRenameFiles(files);
    var header =
        new StringBuilder(
            String.format(
                "## Overview: %d files (+%d -%d)%n%n",
                files.size(), totalAdditions, totalDeletions));
    if (!pureRenames.isEmpty()) {
      header.append(formatPureRenameRollup(pureRenames)).append('\n');
    }
    // Pure renames are disclosed in the rollup only — do not emit empty ### headers into the
    // model input (or count them toward the line-budget truncation / APPROVE hold).
    var sectionFiles = files.stream().filter(f -> !isPureRename(f)).toList();
    return formatWithLineBudget(header.toString(), sectionFiles, namesOf(reviewableFiles));
  }

  static Set<String> namesOf(List<GitHubPullRequestClient.FileDiff> files) {
    return files.stream()
        .map(GitHubPullRequestClient.FileDiff::filename)
        .collect(Collectors.toSet());
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
    return buildBaseComparisonWithStats(comparison, base, head).text();
  }

  /** Like {@link #buildBaseComparison} but also reports how many files the line budget omitted. */
  FormattedDiff buildBaseComparisonWithStats(
      GitHubPullRequestClient.CompareResponse comparison, String base, String head) {
    return buildBaseComparisonWithStats(comparison, base, head, true);
  }

  /**
   * Base comparison render. When {@code applyLineBudget} is false (token-budgeted review path),
   * every file with a patch is included — {@link DiffBudgetPlanner} counts the result in shared
   * overhead and decides what still fits the per-call token budget.
   */
  FormattedDiff buildBaseComparisonWithStats(
      GitHubPullRequestClient.CompareResponse comparison,
      String base,
      String head,
      boolean applyLineBudget) {
    return buildBaseComparisonWithStats(comparison, base, head, applyLineBudget, globalGlobs);
  }

  /**
   * Same, but scoped by an explicit ignore set so the base comparison hides exactly what the PR
   * diff hides for this repository (global ∪ per-repo).
   */
  FormattedDiff buildBaseComparisonWithStats(
      GitHubPullRequestClient.CompareResponse comparison,
      String base,
      String head,
      boolean applyLineBudget,
      IgnoreGlobs globs) {
    if (comparison.files().isEmpty()) {
      return new FormattedDiff(
          "(no changes between " + base.substring(0, 7) + " and " + head.substring(0, 7) + ")", 0);
    }

    var withPatch = comparison.files().stream().filter(f -> f.patch() != null).toList();
    var header =
        new StringBuilder("## Changes between base and head\n")
            .append(
                String.format(
                    "Commits between %s..%s: %d%n%n",
                    base.substring(0, 7), head.substring(0, 7), comparison.totalCommits()))
            .toString();
    return formatWithLineBudget(
        header,
        withPatch,
        namesOf(reviewableFiles(withPatch, globs)),
        applyLineBudget ? maxDiffLines : 0);
  }

  /**
   * Line-budgeted render for single-call diffs. Token-budgeted review calls never use this path for
   * the main PR diff (the planner sections and clips per batch) or for base comparison (context
   * load skips it). Callers that pass {@code maxLines <= 0} get an unbounded render — used for
   * budgeting-disabled reviews with {@code max-diff-lines=0} and for on-demand commands.
   */
  private FormattedDiff formatWithLineBudget(
      String header, List<GitHubPullRequestClient.FileDiff> files, Set<String> reviewableNames) {
    return formatWithLineBudget(header, files, reviewableNames, maxDiffLines);
  }

  private FormattedDiff formatWithLineBudget(
      String header,
      List<GitHubPullRequestClient.FileDiff> files,
      Set<String> reviewableNames,
      int maxLines) {
    if (maxLines <= 0) {
      var sb = new StringBuilder(header);
      for (var file : files) {
        sb.append(formatFileSection(file, reviewableNames));
      }
      return new FormattedDiff(sb.toString(), 0);
    }

    var output = new StringBuilder(header);
    var usedLines = lineCount(header);
    var omitted = new ArrayList<GitHubPullRequestClient.FileDiff>();

    for (var i = 0; i < files.size(); i++) {
      var file = files.get(i);
      var section = formatFileSection(file, reviewableNames);
      var sectionLines = lineCount(section);

      if (usedLines + sectionLines <= maxLines) {
        output.append(section);
        usedLines += sectionLines;
      } else {
        var remaining = maxLines - usedLines;
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
          maxLines, maxLines, omitted.size());
    }

    return new FormattedDiff(output.toString(), omitted.size());
  }

  void appendTruncationFooter(
      StringBuilder output, List<GitHubPullRequestClient.FileDiff> omitted) {
    appendTruncationFooter(output, omitted, lineCount(output.toString()));
  }

  void appendTruncationFooter(
      StringBuilder output, List<GitHubPullRequestClient.FileDiff> omitted, int usedLines) {
    var maxLines = maxDiffLines;
    if (maxLines - usedLines < 1) {
      return;
    }

    String summary =
        String.format("(diff truncated at %d lines — %d files omitted)", maxLines, omitted.size());
    var summaryBlock = "\n" + summary + "\n";
    output.append(summaryBlock);
    usedLines += lineCount(summaryBlock);

    for (GitHubPullRequestClient.FileDiff file : omitted) {
      if (maxLines - usedLines < 1) {
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

  String formatFileSection(GitHubPullRequestClient.FileDiff file, Set<String> reviewableNames) {
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
    if (!reviewableNames.contains(file.filename())) {
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
      return clippedHeader(lines[0]);
    }

    int fenceStart = diffFenceStart(lines);
    return fenceStart < 0
        ? truncatePlain(section, lines, maxLines)
        : truncateFenced(lines, fenceStart, maxLines);
  }

  /**
   * The one-line degradation of a section (#603). The {@code ### <path>} header is structural, not
   * clippable content: dropping it leaves the prompt holding a file's slot with no file name, so
   * the model is asked to review a change it cannot identify and any path-keyed instruction has
   * nothing to bind to. The worst case must be a named file with no visible patch.
   *
   * <p>The truncation notice folds into the header's own parenthetical instead of trailing it, so
   * the line still reads as {@code ### <path> (…)} for consumers that scope by file (see {@code
   * FindingQuoteValidator.indexDiff}, which cuts the path at the last {@code " ("}). A first line
   * that is not a section header is left as the bare notice.
   */
  private static String clippedHeader(String firstLine) {
    if (!firstLine.startsWith("### ")) {
      return "(patch truncated)\n";
    }
    return firstLine.endsWith(")")
        ? firstLine.substring(0, firstLine.length() - 1) + ", patch truncated)\n"
        : firstLine + " (patch truncated)\n";
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
    sb.append(patchTruncatedNotice(lineCount(section) - contentLines));
    return sb.toString();
  }

  /** The shared "(patch truncated — N lines omitted)" notice appended by every truncation path. */
  private static String patchTruncatedNotice(int omittedLines) {
    return "(patch truncated — " + omittedLines + " lines omitted)\n";
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
    sb.append(patchTruncatedNotice(patchCount - keep));
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
    sb.append(patchTruncatedNotice(patchCount));
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
