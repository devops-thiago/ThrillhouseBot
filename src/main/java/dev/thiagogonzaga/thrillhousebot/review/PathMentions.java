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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Checks the file paths the model names in its prose against the files that exist (#1076).
 *
 * <p>Round 17 summaries and findings named files that do not exist: "moves the repository README to
 * docs/REACT-VIV.md" for {@code docs/REACT-V17.md} (react ThrillhouseBot-test#249), and a finding
 * citing {@code angular/src/docs/CONFIG-ANGULAR.md} for {@code angular/docs/CONFIG-ANGULAR.md}
 * (angular #251). A path-shaped token — one holding a {@code /}, or a file name with a known source
 * or documentation extension — is resolved in this order:
 *
 * <ol>
 *   <li>a file the pull request changes (its current or, for a rename, its earlier path), or a
 *       directory holding one; a bare file name by the name of one;
 *   <li>text the review was given verbatim: a patch, the PR title and description, the linked
 *       issues. A path that is missing on purpose ("the description promises docs/FOO.md, which no
 *       file adds", a {@code COPY} source the build cannot find) is quoted from there;
 *   <li>the one changed file {@link DiffLineResolver#diffPathFor} maps it onto, which replaces it;
 *   <li>a repository file at the head, from one recursive listing read only when a token reaches
 *       this step.
 * </ol>
 *
 * <p>A token none of these resolves is invented: its sentence leaves the summary text, and the
 * mention (with a parenthetical holding only it) leaves a finding's description, unless the model
 * put it in a code span: a name quoted that way is usually missing on purpose ("no {@code
 * package-lock.json} is added"), and cutting it left the sentence without it (#1095). A finding is
 * never dropped, and its title, file and suggestions are never touched. When the repository could
 * not be listed, or the listing was truncated, a token the earlier steps do not resolve is left as
 * written: it may exist.
 *
 * <p>Not paths: URLs, a route ({@code /scan}, {@code /triage/run}), a package name ({@code
 * com.example.x}), a scoped package ({@code @angular/core}), an image reference ({@code
 * node:24-alpine}, {@code ghcr.io/org/app:1.2}), a domain-led reference ({@code github.com/org}), a
 * glob ({@code src/**}{@code /*.ts}), one-slash prose ({@code and/or}, {@code CI/CD}) and
 * technology names ({@code Node.js}). Fenced code blocks are left as written.
 */
final class PathMentions {

  /** A review with nothing to check against: every mention is left as written. */
  static final PathMentions NONE = new PathMentions(List.of(), "", Optional::empty);

  /**
   * A path-shaped candidate: segments of word characters, dots, plus and minus signs joined by
   * slashes, an optional trailing slash, and an optional {@code :line} or {@code :start-end}. Never
   * right after a character that would make it the tail of something longer: a slash (a route or a
   * URL), an {@code @} (a scoped package). A star before or after it makes it part of a glob unless
   * it is markdown emphasis ({@link #partOfSomethingElse}).
   */
  private static final Pattern TOKEN =
      Pattern.compile(
          "(?<![\\w./@~:\\\\+-])([\\w+-][\\w.+-]*+(?:/[\\w.+-]++)*+/?)(:\\d++(?:-\\d++)?+)?");

  /** A URL, which holds path-shaped segments that are not repository paths. */
  private static final Pattern URL =
      Pattern.compile("(?i)\\b(?:[a-z][a-z0-9+.-]*+://|www\\.)[^\\s<>()\\[\\]`\"']++");

  /** The extensions that make a bare name a file name. */
  private static final Set<String> EXTENSIONS =
      Set.of(
          "java",
          "kt",
          "kts",
          "groovy",
          "scala",
          "sbt",
          "gradle",
          "xml",
          "properties",
          "yml",
          "yaml",
          "json",
          "toml",
          "ini",
          "cfg",
          "conf",
          "env",
          "lock",
          "mod",
          "sum",
          "js",
          "jsx",
          "mjs",
          "cjs",
          "ts",
          "tsx",
          "vue",
          "svelte",
          "css",
          "scss",
          "less",
          "html",
          "htm",
          "md",
          "mdx",
          "rst",
          "adoc",
          "txt",
          "py",
          "rb",
          "go",
          "rs",
          "cs",
          "csproj",
          "sln",
          "fs",
          "c",
          "h",
          "cc",
          "cpp",
          "hpp",
          "m",
          "swift",
          "php",
          "pl",
          "lua",
          "ex",
          "exs",
          "erl",
          "hs",
          "clj",
          "dart",
          "zig",
          "zon",
          "sql",
          "sh",
          "bash",
          "ps1",
          "bat",
          "tf",
          "hcl",
          "proto",
          "graphql",
          "cmake",
          "dockerfile");

  /** Names that end in ".js" without being files: the frameworks and runtimes called that way. */
  private static final Set<String> TECHNOLOGY_NAMES =
      Set.of(
          "node",
          "react",
          "preact",
          "angular",
          "svelte",
          "vue",
          "next",
          "nuxt",
          "express",
          "d3",
          "three",
          "chart",
          "ember",
          "backbone",
          "alpine",
          "solid",
          "p5",
          "moment",
          "handlebars",
          "knockout",
          "deno",
          "nest",
          "socket");

  /** Bare file names that carry no extension. */
  private static final Set<String> DOTLESS_FILE_NAMES =
      Set.of(
          "dockerfile",
          "containerfile",
          "makefile",
          "jenkinsfile",
          "procfile",
          "gemfile",
          "rakefile",
          "vagrantfile",
          "brewfile",
          "justfile");

  /** An inline code span on one line. */
  private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]++`");

  private static final Pattern BLANK_RUN = Pattern.compile(" {2,}");

  private static final Pattern BLANK_BEFORE_PUNCTUATION = Pattern.compile(" ([.,;:!?])");

  private final PathIndex changed;
  private final DiffLineResolver resolver;
  private final String verbatim;
  private final Supplier<Optional<PathIndex>> repository;

  /**
   * Each token's resolution, judged once per review: every summary field, gap, file summary and
   * finding description, and each sentence a cut re-reads, ask about the same tokens.
   */
  private final Map<String, Optional<String>> resolutions = new ConcurrentHashMap<>();

  /**
   * Paths indexed once, in lower case: whole, by file name, and by every directory holding one, so
   * a token is judged by lookups rather than a walk over a repository-sized listing.
   */
  private record PathIndex(Set<String> paths, Set<String> names, Set<String> directories) {

    static PathIndex of(Collection<String> paths) {
      var all = new HashSet<String>();
      var names = new HashSet<String>();
      var directories = new HashSet<String>();
      for (String path : paths) {
        String lower = path.toLowerCase(Locale.ROOT);
        all.add(lower);
        names.add(fileName(lower));
        for (int slash = lower.indexOf('/'); slash > 0; slash = lower.indexOf('/', slash + 1)) {
          directories.add(lower.substring(0, slash + 1));
        }
      }
      return new PathIndex(Set.copyOf(all), Set.copyOf(names), Set.copyOf(directories));
    }

    /**
     * Whether {@code token} is one of the paths, a directory holding one, or a file's name, in any
     * letter case: "readme.md" names {@code README.md}, so it is not invented.
     */
    boolean names(String token) {
      String wanted = token.toLowerCase(Locale.ROOT);
      if (wanted.indexOf('/') < 0) {
        return names.contains(wanted);
      }
      return paths.contains(wanted)
          || directories.contains(wanted.endsWith("/") ? wanted : wanted + "/");
    }
  }

  private PathMentions(
      List<GitHubPullRequestClient.FileDiff> files,
      String known,
      Supplier<Optional<CitedLocationResolver.RepositoryPaths>> repository) {
    var paths = new HashSet<String>();
    var current = new HashSet<String>();
    var text = new StringBuilder(known == null ? "" : known);
    for (var file : files) {
      paths.add(file.filename());
      current.add(file.filename());
      if (file.previousFilename() != null) {
        paths.add(file.previousFilename());
      }
      if (file.patch() != null) {
        text.append('\n').append(file.patch());
      }
    }
    this.changed = PathIndex.of(paths);
    this.resolver = new DiffLineResolver(Map.of(), current);
    this.verbatim = text.toString();
    // A truncated listing may lack a path that exists, so it judges nothing, like no listing.
    this.repository =
        ReviewContextLoader.memoize(
            () ->
                repository
                    .get()
                    .filter(listing -> !listing.truncated())
                    .map(listing -> PathIndex.of(listing.paths())));
  }

  /**
   * The mentions of one review.
   *
   * @param files the pull request's changed files, with their patches
   * @param known the text the review was given that may quote a path: the PR title and description
   *     and the linked issues
   * @param repository the repository's paths at the head, read on first use; empty when unknown
   */
  static PathMentions of(
      List<GitHubPullRequestClient.FileDiff> files,
      String known,
      Supplier<Optional<CitedLocationResolver.RepositoryPaths>> repository) {
    return new PathMentions(files, known, repository);
  }

  /**
   * {@code response} with each finding's description rid of the paths nothing resolves and its
   * mistyped paths mapped; the same instance when nothing changed. No finding is dropped.
   */
  ReviewResponse screen(ReviewResponse response) {
    var changedAny = false;
    var findings = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    for (var finding : response.findings()) {
      var description = withoutUnresolvedMentions(finding.description());
      if (Objects.equals(description, finding.description())) {
        findings.add(finding);
      } else {
        changedAny = true;
        findings.add(
            new ReviewResponse.Finding(
                finding.risk(),
                finding.confidence(),
                finding.file(),
                finding.line(),
                finding.title(),
                description,
                finding.suggestionOld(),
                finding.suggestionNew()));
      }
    }
    return changedAny
        ? new ReviewResponse(findings, response.previousFindingsStatus(), response.summary())
        : response;
  }

  /**
   * {@code summary} with every sentence naming a path nothing resolves cut from its assessment,
   * purpose, description gaps and file summaries, and its mistyped paths mapped; a gap left empty
   * goes. The same instance when nothing changed; {@code null} stays {@code null}.
   */
  ReviewResponse.Summary screen(ReviewResponse.Summary summary) {
    if (summary == null) {
      return null;
    }
    var assessment = withoutUnresolvedSentences(summary.overallAssessment());
    var purpose = withoutUnresolvedSentences(summary.prPurpose());
    var gaps =
        summary.descriptionGaps().stream()
            .map(this::withoutUnresolvedSentences)
            .filter(gap -> !gap.isBlank())
            .toList();
    var diagram = withoutUnresolvedPaths(summary.walkthroughDiagram());
    var fileSummaries =
        summary.fileSummaries().stream()
            .map(
                entry -> {
                  var text = withoutUnresolvedSentences(entry.summary());
                  return Objects.equals(text, entry.summary())
                      ? entry
                      : new ReviewResponse.FileSummary(entry.path(), text);
                })
            .toList();
    if (Objects.equals(assessment, summary.overallAssessment())
        && Objects.equals(purpose, summary.prPurpose())
        && gaps.equals(summary.descriptionGaps())
        && fileSummaries.equals(summary.fileSummaries())
        && Objects.equals(diagram, summary.walkthroughDiagram())) {
      return summary;
    }
    return new ReviewResponse.Summary(
        summary.totalFindings(),
        summary.critical(),
        summary.high(),
        summary.medium(),
        summary.low(),
        assessment,
        purpose,
        gaps,
        summary.suggestedLabels(),
        fileSummaries,
        diagram,
        summary.addressedGaps());
  }

  /**
   * {@code text} with its mapped paths replaced and the paths nothing resolves removed, and nothing
   * else touched: the walkthrough diagram, whose syntax a sentence cut or a tidy would break.
   */
  String withoutUnresolvedPaths(String text) {
    if (text == null || text.isBlank()) {
      return text;
    }
    var rewritten = rewrite(text);
    var sb = new StringBuilder(rewritten.text());
    var spans = rewritten.unresolved();
    for (int i = spans.size() - 1; i >= 0; i--) {
      sb.delete(spans.get(i)[0], spans.get(i)[1]);
    }
    String result = sb.toString();
    return result.equals(text) ? text : result;
  }

  /**
   * Whether a one-slash token without an extension ({@code react/src}) opens with a top-level
   * directory of a changed file, which makes it a directory rather than prose such as "and/or".
   */
  private boolean underAChangedDirectory(String token) {
    int slash = token.indexOf('/');
    return slash > 0
        && changed.directories().contains(token.substring(0, slash + 1).toLowerCase(Locale.ROOT));
  }

  /** {@code text} without its sentences naming a path nothing resolves, mapped paths replaced. */
  String withoutUnresolvedSentences(String text) {
    if (text == null || text.isBlank()) {
      return text;
    }
    var whole = rewrite(text);
    if (whole.unresolved().isEmpty()) {
      return whole.text();
    }
    if (!fences(text).isEmpty()) {
      // Sentences would split a fenced block and lose which side of a fence a path is on, so a
      // text holding one loses the mentions instead, which are read over the whole text.
      return withoutUnresolvedMentions(text);
    }
    var kept = new ArrayList<String>();
    for (String sentence : OverviewScreen.sentences(text)) {
      var rewritten = rewrite(sentence);
      if (rewritten.unresolved().isEmpty()) {
        kept.add(rewritten.text());
      }
    }
    return String.join(" ", kept);
  }

  /**
   * {@code text} without its mentions of paths nothing resolves, mapped paths replaced: a mention
   * goes with the parenthetical when that holds only the mention. A mention inside a code span
   * stays unless the span fills such a parenthetical (#1095). Text that would be left blank is
   * returned as it was.
   */
  String withoutUnresolvedMentions(String text) {
    if (text == null || text.isBlank()) {
      return text;
    }
    var rewritten = rewrite(text);
    if (rewritten.unresolved().isEmpty()) {
      return rewritten.text();
    }
    var sb = new StringBuilder(rewritten.text());
    var spans = rewritten.unresolved();
    var code = codeSpans(rewritten.text());
    for (int i = spans.size() - 1; i >= 0; i--) {
      int start = spans.get(i)[0];
      int end = spans.get(i)[1];
      if (inCodeSpan(code, start) && !spanFillsParenthetical(sb, start, end)) {
        // #1095: a name the model put in a code span is quoted on purpose ("no
        // `package-lock.json` is added"); cutting it left "no is added". Only a span that fills a
        // parenthetical goes, with the parenthetical.
        continue;
      }
      int[] cut = mentionBounds(sb, start, end);
      sb.delete(cut[0], cut[1]);
    }
    var tidy = tidyOutsideFences(sb.toString());
    return tidy.isBlank() ? text : tidy;
  }

  /** {@code text} with doubled blanks and blanks before punctuation removed outside its fences. */
  private static String tidyOutsideFences(String text) {
    var out = new StringBuilder(text.length());
    int at = 0;
    for (int[] fence : fences(text)) {
      out.append(tidy(text.substring(at, fence[0]))).append(text, fence[0], fence[1]);
      at = fence[1];
    }
    return out.append(tidy(text.substring(at))).toString().strip();
  }

  private static String tidy(String prose) {
    return BLANK_BEFORE_PUNCTUATION
        .matcher(BLANK_RUN.matcher(prose).replaceAll(" "))
        .replaceAll("$1");
  }

  /**
   * What goes with the mention at {@code start}..{@code end}: its emphasis, the code span or
   * parenthetical it fills, and the blank before it; or, when it opens a span or parenthetical that
   * goes on, the blank after it.
   */
  private static int[] mentionBounds(CharSequence s, int start, int end) {
    int from = start;
    int to = end;
    while (wraps(s, from, to, '*', '*')) {
      from--;
      to++;
    }
    if (wraps(s, from, to, '`', '`')) {
      from--;
      to++;
    }
    if (wraps(s, from, to, '(', ')')) {
      from--;
      to++;
    }
    if (from > 0 && "(`".indexOf(s.charAt(from - 1)) >= 0) {
      return new int[] {from, to < s.length() && s.charAt(to) == ' ' ? to + 1 : to};
    }
    while (from > 0 && s.charAt(from - 1) == ' ') {
      from--;
    }
    return new int[] {from, to};
  }

  /**
   * Whether the mention at {@code start}..{@code end} is the whole of a code span that is the whole
   * of a parenthetical: {@code (`docs/x.md:4`)}, which goes without leaving a gap in the sentence.
   */
  private static boolean spanFillsParenthetical(CharSequence s, int start, int end) {
    int from = start;
    int to = end;
    while (wraps(s, from, to, '*', '*')) {
      from--;
      to++;
    }
    return wraps(s, from, to, '`', '`') && wraps(s, from - 1, to + 1, '(', ')');
  }

  /** The inline code spans of {@code text}, outside its fences: one-line runs between backticks. */
  private static List<int[]> codeSpans(String text) {
    var fenced = new SpanCursor(fences(text));
    var spans = new ArrayList<int[]>();
    var matcher = INLINE_CODE.matcher(text);
    while (matcher.find()) {
      if (!fenced.covers(matcher.start())) {
        spans.add(new int[] {matcher.start(), matcher.end()});
      }
    }
    return spans;
  }

  /** Whether {@code at} is inside one of {@code spans}, which are in text order and disjoint. */
  private static boolean inCodeSpan(List<int[]> spans, int at) {
    int low = 0;
    int high = spans.size() - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      int[] span = spans.get(mid);
      if (at <= span[0]) {
        high = mid - 1;
      } else if (at >= span[1]) {
        low = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  private static boolean wraps(CharSequence s, int start, int end, char open, char close) {
    return start > 0 && end < s.length() && s.charAt(start - 1) == open && s.charAt(end) == close;
  }

  /** A text with its mapped paths replaced, and the spans of the paths nothing resolves in it. */
  private record Rewrite(String text, List<int[]> unresolved) {}

  private Rewrite rewrite(String text) {
    var out = new StringBuilder(text.length());
    var unresolved = new ArrayList<int[]>();
    var urls = new SpanCursor(spans(URL, text));
    var fences = new SpanCursor(fences(text));
    var matcher = TOKEN.matcher(text);
    int at = 0;
    while (matcher.find()) {
      String token = stripTrailingDots(matcher.group(1));
      int start = matcher.start(1);
      int end = start + token.length();
      int fullEnd = matcher.group(2) == null ? end : matcher.end(2);
      if (urls.covers(start)
          || fences.covers(start)
          || !(pathShaped(token) || underAChangedDirectory(token))
          || partOfSomethingElse(text, start, fullEnd)) {
        continue;
      }
      var resolution = resolve(token);
      out.append(text, at, start);
      if (resolution == null) {
        unresolved.add(new int[] {out.length(), out.length() + (fullEnd - start)});
        out.append(text, start, fullEnd);
      } else {
        out.append(resolution).append(text, end, fullEnd);
      }
      at = fullEnd;
    }
    out.append(text, at, text.length());
    String rewritten = out.toString();
    return new Rewrite(rewritten.equals(text) ? text : rewritten, unresolved);
  }

  /**
   * What {@code token} resolves to: itself when it names a changed or repository file or is quoted
   * from the review's own text (or cannot be judged), the changed file it is a near miss of, or
   * {@code null} when nothing resolves it.
   */
  private String resolve(String token) {
    return resolutions.computeIfAbsent(token, t -> Optional.ofNullable(judge(t))).orElse(null);
  }

  /** {@link #resolve} for a token not judged before in this review. */
  private String judge(String token) {
    if (verbatim.contains(token) || changed.names(token)) {
      return token;
    }
    if (token.contains("/") && !token.endsWith("/")) {
      String mapped = resolver.diffPathFor(token);
      // diffPathFor returns the token itself or the one changed file it is a near miss of.
      if (!mapped.equals(token)) {
        return mapped;
      }
    }
    var index = repository.get();
    return index.isEmpty() || index.get().names(token) ? token : null;
  }

  /**
   * Whether {@code token}'s shape makes it a file path rather than prose, a package or a domain.
   */
  static boolean pathShaped(String token) {
    int slash = token.indexOf('/');
    if (slash < 0) {
      if (DOTLESS_FILE_NAMES.contains(token.toLowerCase(Locale.ROOT))) {
        return true;
      }
      int dot = token.lastIndexOf('.');
      if (dot <= 0 || !EXTENSIONS.contains(extension(token))) {
        return false;
      }
      String stem = token.substring(0, dot).toLowerCase(Locale.ROOT);
      return stem.chars().anyMatch(Character::isLetter)
          && !("js".equals(extension(token)) && TECHNOLOGY_NAMES.contains(stem));
    }
    String first = token.substring(0, slash);
    if (first.substring(1).contains(".")) {
      // A first segment dotted after its first character is a domain or a version (github.com/org),
      // not a directory; .github is one.
      return false;
    }
    if (token.endsWith("/")) {
      return true;
    }
    String last = fileName(token);
    return (last.lastIndexOf('.') > 0 && EXTENSIONS.contains(extension(last)))
        || DOTLESS_FILE_NAMES.contains(last.toLowerCase(Locale.ROOT))
        || token.chars().filter(c -> c == '/').count() >= 2;
  }

  private static String extension(String name) {
    return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
  }

  private static String fileName(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String stripTrailingDots(String token) {
    int end = token.length();
    // The token opens with a word character, so the loop stops before index 0.
    while (token.charAt(end - 1) == '.') {
      end--;
    }
    return token.substring(0, end);
  }

  /**
   * Whether what surrounds a candidate makes it part of something else: a glob star before or after
   * it, unless the stars are markdown emphasis, or what {@link #continuesAsSomethingElse} reads.
   */
  private static boolean partOfSomethingElse(String text, int start, int end) {
    if (emphasized(text, start, end)) {
      return false;
    }
    return (start > 0 && text.charAt(start - 1) == '*') || continuesAsSomethingElse(text, end);
  }

  /**
   * Whether the candidate at {@code start}..{@code end} is wrapped in markdown emphasis ({@code
   * *docs/x.md*}, {@code **docs/x.md**}): the same run of stars on each side, with no word or slash
   * beyond either, which a glob ({@code src/*.ts}, {@code *config.yml}) never has.
   */
  private static boolean emphasized(String text, int start, int end) {
    int before = 0;
    while (start - before > 0 && text.charAt(start - before - 1) == '*') {
      before++;
    }
    int after = 0;
    while (end + after < text.length() && text.charAt(end + after) == '*') {
      after++;
    }
    return before > 0
        && before == after
        && !globNeighbour(text, start - before - 1)
        && !globNeighbour(text, end + after);
  }

  private static boolean globNeighbour(String text, int at) {
    if (at < 0 || at >= text.length()) {
      return false;
    }
    char c = text.charAt(at);
    return Character.isLetterOrDigit(c) || c == '/';
  }

  /**
   * Whether what follows a candidate makes it part of something else: a glob ({@code src/*}, {@code
   * src/{a,b}}) or an image tag ({@code app:latest}); a colon that ends a clause does not.
   */
  private static boolean continuesAsSomethingElse(String text, int at) {
    if (at >= text.length()) {
      return false;
    }
    char next = text.charAt(at);
    return next == '*'
        || next == '{'
        || (next == ':' && at + 1 < text.length() && !Character.isWhitespace(text.charAt(at + 1)));
  }

  private static List<int[]> spans(Pattern pattern, String text) {
    var spans = new ArrayList<int[]>();
    var matcher = pattern.matcher(text);
    while (matcher.find()) {
      spans.add(new int[] {matcher.start(), matcher.end()});
    }
    return spans;
  }

  /** The fenced code blocks of {@code text}, an unclosed one running to the end. */
  private static List<int[]> fences(String text) {
    return MarkdownFences.spans(text);
  }

  /**
   * Whether positions, asked in increasing order, fall inside one of {@code spans} (in text order,
   * not overlapping): a cursor that only moves forward, so a text's tokens are checked in one pass.
   */
  private static final class SpanCursor {
    private final List<int[]> spans;
    private int next;

    SpanCursor(List<int[]> spans) {
      this.spans = spans;
    }

    boolean covers(int at) {
      while (next < spans.size() && spans.get(next)[1] <= at) {
        next++;
      }
      return next < spans.size() && spans.get(next)[0] <= at;
    }
  }
}
