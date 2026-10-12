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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * mention (with a parenthetical holding only it) leaves a finding's description. A finding is never
 * dropped, and its title, file and suggestions are never touched. When the repository could not be
 * listed, or the listing was truncated, a token the earlier steps do not resolve is left as
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
   * URL), an {@code @} (a scoped package), a glob star.
   */
  private static final Pattern TOKEN =
      Pattern.compile(
          "(?<![\\w./@*~:\\\\+-])([\\w+-][\\w.+-]*+(?:/[\\w.+-]++)*+/?)(:\\d++(?:-\\d++)?+)?");

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

  private final Set<String> changed;
  private final DiffLineResolver resolver;
  private final String verbatim;
  private final Supplier<Optional<CitedLocationResolver.RepositoryPaths>> repository;

  private PathMentions(
      List<GitHubPullRequestClient.FileDiff> files,
      String known,
      Supplier<Optional<CitedLocationResolver.RepositoryPaths>> repository) {
    var paths = new HashSet<String>();
    var current = new HashSet<String>();
    var text = new StringBuilder(known == null ? "" : known);
    for (var file : files) {
      if (file == null || file.filename() == null) {
        continue;
      }
      paths.add(file.filename());
      current.add(file.filename());
      if (file.previousFilename() != null) {
        paths.add(file.previousFilename());
      }
      if (file.patch() != null) {
        text.append('\n').append(file.patch());
      }
    }
    this.changed = Set.copyOf(paths);
    this.resolver = new DiffLineResolver(Map.of(), current);
    this.verbatim = text.toString();
    this.repository = ReviewContextLoader.memoize(repository);
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
            .filter(gap -> gap != null && !gap.isBlank())
            .toList();
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
        && fileSummaries.equals(summary.fileSummaries())) {
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
        summary.walkthroughDiagram(),
        summary.addressedGaps());
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
   * goes with the code span around it, and with the parenthetical when that holds only the mention.
   * Text that would be left blank is returned as it was.
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
    for (int i = spans.size() - 1; i >= 0; i--) {
      int start = spans.get(i)[0];
      int end = spans.get(i)[1];
      if (start > 0 && end < sb.length() && sb.charAt(start - 1) == '`' && sb.charAt(end) == '`') {
        start--;
        end++;
      }
      if (start > 0 && end < sb.length() && sb.charAt(start - 1) == '(' && sb.charAt(end) == ')') {
        start--;
        end++;
      }
      while (start > 0 && sb.charAt(start - 1) == ' ') {
        start--;
      }
      sb.delete(start, end);
    }
    var tidy = sb.toString().replaceAll(" {2,}", " ").replaceAll(" ([.,;:!?])", "$1").strip();
    return tidy.isBlank() ? text : tidy;
  }

  /** A text with its mapped paths replaced, and the spans of the paths nothing resolves in it. */
  private record Rewrite(String text, List<int[]> unresolved) {}

  private Rewrite rewrite(String text) {
    var out = new StringBuilder(text.length());
    var unresolved = new ArrayList<int[]>();
    var urls = spans(URL, text);
    var fences = fences(text);
    var matcher = TOKEN.matcher(text);
    int at = 0;
    while (matcher.find()) {
      String token = stripTrailingDots(matcher.group(1));
      int start = matcher.start(1);
      int end = start + token.length();
      boolean lineSuffix = matcher.group(2) != null && token.equals(matcher.group(1));
      int fullEnd = lineSuffix ? matcher.end(2) : end;
      if (inside(urls, start)
          || inside(fences, start)
          || !pathShaped(token)
          || continuesAsSomethingElse(text, fullEnd)) {
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
    if (verbatim.contains(token) || namesChanged(token)) {
      return token;
    }
    if (token.indexOf('/') > 0 && !token.endsWith("/")) {
      String mapped = resolver.diffPathFor(token);
      if (!mapped.equals(token) && changed.contains(mapped)) {
        return mapped;
      }
    }
    var listing = repository.get();
    if (listing.isEmpty() || listing.get().truncated() || names(token, listing.get().paths())) {
      return token;
    }
    return null;
  }

  private boolean namesChanged(String token) {
    return names(token, changed);
  }

  /**
   * Whether {@code token} is one of {@code paths}, a directory holding one, or a file's name, in
   * any letter case: "readme.md" names {@code README.md}, so it is not invented.
   */
  private static boolean names(String token, Set<String> paths) {
    String wanted = token.toLowerCase(Locale.ROOT);
    if (wanted.indexOf('/') < 0) {
      return paths.stream()
          .anyMatch(path -> fileName(path).toLowerCase(Locale.ROOT).equals(wanted));
    }
    String directory = wanted.endsWith("/") ? wanted : wanted + "/";
    return paths.stream()
        .map(path -> path.toLowerCase(Locale.ROOT))
        .anyMatch(path -> path.equals(wanted) || path.startsWith(directory));
  }

  /**
   * Whether {@code token}'s shape makes it a file path rather than prose, a package or a domain.
   */
  static boolean pathShaped(String token) {
    int slash = token.indexOf('/');
    if (slash < 0) {
      int dot = token.lastIndexOf('.');
      if (dot <= 0 || !EXTENSIONS.contains(extension(token))) {
        return false;
      }
      String stem = token.substring(0, dot).toLowerCase(Locale.ROOT);
      return stem.chars().anyMatch(Character::isLetter)
          && !("js".equals(extension(token)) && TECHNOLOGY_NAMES.contains(stem));
    }
    String first = token.substring(0, slash);
    if (first.indexOf('.') > 0 && !EXTENSIONS.contains(extension(first))) {
      return false;
    }
    if (token.endsWith("/")) {
      return true;
    }
    String last = fileName(token);
    return (last.lastIndexOf('.') > 0 && EXTENSIONS.contains(extension(last)))
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
    while (end > 0 && token.charAt(end - 1) == '.') {
      end--;
    }
    return token.substring(0, end);
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
    var spans = new ArrayList<int[]>();
    int open = text.indexOf("```");
    while (open >= 0) {
      int close = text.indexOf("```", open + 3);
      int end = close < 0 ? text.length() : close + 3;
      spans.add(new int[] {open, end});
      open = close < 0 ? -1 : text.indexOf("```", end);
    }
    return spans;
  }

  private static boolean inside(List<int[]> spans, int at) {
    return spans.stream().anyMatch(span -> at >= span[0] && at < span[1]);
  }
}
