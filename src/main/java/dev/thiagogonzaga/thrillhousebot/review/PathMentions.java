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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 *   <li>the one changed file whose path ends with it, which replaces it: {@code docs/CONFIG-C.md}
 *       for {@code c/docs/CONFIG-C.md}, even where a patch quotes the shorter form (#1094);
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
 * dropped, and its title and file are never touched. When the repository could not be listed, or
 * the listing was truncated, a token the earlier steps do not resolve is left as written: it may
 * exist.
 *
 * <p>Two more checks on findings (#1094). A code span in a finding's description that quotes a line
 * of the finding's file with an absolute path the line does not hold ({@code COPY --from=build
 * /src/zig-out/bin/app /usr/local/bin/ap}) gets the path the line holds, when exactly one fits. A
 * suggestion that introduces a repository file nothing resolves, or a glob over an absolute
 * directory ({@code /app/lib/*}) that no patch, the PR text or the replaced lines name, is dropped;
 * the finding stays.
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

  /**
   * An absolute path of two segments or more ({@code /usr/local/bin/app}), never the tail of a
   * longer token; a route-like single segment is not one.
   */
  private static final Pattern ABSOLUTE_PATH =
      Pattern.compile("(?<![\\w.~/\\\\+-])/[\\w.+-]++(?:/[\\w.+-]++)++");

  /** A glob over an absolute directory ({@code /app/lib/*}): the directory, without its slash. */
  private static final Pattern ABSOLUTE_GLOB =
      Pattern.compile("(?<![\\w.~/\\\\+-])(/[\\w.+-]++(?:/[\\w.+-]++)*+)/\\*");

  /** A Dockerfile instruction that copies into the image. */
  private static final Pattern COPY_INSTRUCTION =
      Pattern.compile("\\s*+(?:COPY|ADD)\\s", Pattern.CASE_INSENSITIVE);

  /** What may follow a line's last argument: the close of an exec-form array, and blanks. */
  private static final Pattern LINE_END = Pattern.compile("[\"\\]\\s]*+");

  /**
   * Where a shell command starts on a line: at its start or after a separator or a pipe, past a
   * Dockerfile {@code RUN}.
   */
  private static final Pattern COMMAND_START =
      Pattern.compile("(?:^|&&|\\|\\||[;|(])\\s*+(?:RUN\\s++)?", Pattern.CASE_INSENSITIVE);

  /** A shell command whose last argument is a path it writes, with its options and arguments. */
  private static final Pattern WRITING_COMMAND =
      Pattern.compile("\\s*+(?:sudo\\s++)?(?:cp|mv|install|ln|touch|mkdir|tee)\\s");

  /** What may follow a command's last argument: the next command, a continuation, or the end. */
  private static final Pattern COMMAND_END =
      Pattern.compile("[\"']?\\s*+(?:&&|\\|\\||[;|)\\\\]|$)");

  /** A line that ends with a continuation backslash, read at the end of the text before it. */
  private static final Pattern CONTINUED = Pattern.compile("\\\\[ \\t]*+$");

  /** A continuation backslash and the line break after it. */
  private static final Pattern CONTINUATION = Pattern.compile("\\\\[ \\t]*+\\n");

  /** A redirection right before a token: {@code > out.log}, {@code >>conf/app.conf}. */
  private static final Pattern REDIRECTED = Pattern.compile(">\\s*+[\"']?$");

  /** An inline code span on one line. */
  private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\n]++)`");

  private static final Pattern BLANK_RUN = Pattern.compile(" {2,}");

  private static final Pattern BLANK_BEFORE_PUNCTUATION = Pattern.compile(" ([.,;:!?])");

  private final PathIndex changed;
  private final Set<String> currentFiles;

  /** Each changed file's patch lines, without their diff markers, for the quote check. */
  private final Map<String, List<String>> patchLines;

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
    var lines = new HashMap<String, List<String>>();
    for (var file : files) {
      paths.add(file.filename());
      current.add(file.filename());
      if (file.previousFilename() != null) {
        paths.add(file.previousFilename());
      }
      if (file.patch() != null) {
        text.append('\n').append(file.patch());
        var code = codeLines(file.patch());
        lines.put(file.filename(), code);
        if (file.previousFilename() != null) {
          // A finding may name a renamed file by its earlier path, which diffPathFor can leave.
          lines.putIfAbsent(file.previousFilename(), code);
        }
      }
    }
    this.changed = PathIndex.of(paths);
    this.currentFiles = Set.copyOf(current);
    this.patchLines = Map.copyOf(lines);
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
      var description =
          withQuotedPathsCorrected(
              withoutUnresolvedMentions(finding.description()), finding.file());
      var suggestionNew = finding.suggestionNew();
      var unresolved = unresolvedInSuggestion(finding);
      if (unresolved != null) {
        Log.infof(
            "Finding '%s' (%s:%d) suggests %s, which nothing resolves; dropping its suggestion and"
                + " keeping the finding",
            LogSafe.oneLine(finding.title()),
            LogSafe.oneLine(finding.file()),
            finding.line(),
            LogSafe.oneLine(unresolved));
        suggestionNew = null;
      }
      if (Objects.equals(description, finding.description())
          && Objects.equals(suggestionNew, finding.suggestionNew())) {
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
                suggestionNew));
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
      int[] cut = mentionBounds(sb, spans.get(i)[0], spans.get(i)[1]);
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
    if (changed.names(token)) {
      return token;
    }
    String completed = changedFileEndingWith(token);
    if (completed != null) {
      return completed;
    }
    if (verbatim.contains(token)) {
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
   * The one changed file whose path ends with {@code token} after a slash, or {@code null} for a
   * bare name, a directory, or none or several: {@code c/docs/CONFIG-C.md} for {@code
   * docs/CONFIG-C.md} (#1094).
   */
  private String changedFileEndingWith(String token) {
    if (token.indexOf('/') < 0 || token.endsWith("/")) {
      return null;
    }
    String tail = "/" + token;
    String match = null;
    for (String file : currentFiles) {
      if (file.endsWith(tail)) {
        if (match != null) {
          return null;
        }
        match = file;
      }
    }
    return match;
  }

  /**
   * {@code description} with each code span that quotes a line of {@code file}'s patch, but with an
   * absolute path the line does not hold, given the path the line holds (#1094). A span is a quote
   * when the text around the path in it surrounds exactly one other absolute path in the patch's
   * lines; anything else is left as written.
   */
  String withQuotedPathsCorrected(String description, String file) {
    if (description == null || description.indexOf('`') < 0) {
      return description;
    }
    var lines = patchLines.get(resolver.diffPathFor(file));
    if (lines == null) {
      return description;
    }
    var fenced = new SpanCursor(fences(description));
    var out = new StringBuilder(description.length());
    var spans = CODE_SPAN.matcher(description);
    int at = 0;
    while (spans.find()) {
      if (!fenced.covers(spans.start())) {
        out.append(description, at, spans.start(1)).append(correctedQuote(spans.group(1), lines));
        at = spans.end(1);
      }
    }
    out.append(description, at, description.length());
    String result = out.toString();
    return result.equals(description) ? description : result;
  }

  /** {@code quote} with an absolute path the patch lines do not hold replaced, where one fits. */
  private static String correctedQuote(String quote, List<String> lines) {
    if (lines.stream().anyMatch(line -> line.contains(quote))) {
      return quote;
    }
    var paths = ABSOLUTE_PATH.matcher(quote);
    while (paths.find()) {
      String path = paths.group();
      String before = quote.substring(0, paths.start());
      String after = quote.substring(paths.end());
      if ((before.isBlank() && after.isBlank())
          || lines.stream().anyMatch(line -> line.contains(path))) {
        continue;
      }
      var fits = pathsBetween(before, after, lines);
      if (fits.size() == 1) {
        return before + fits.getFirst() + after;
      }
    }
    return quote;
  }

  /** The absolute paths of {@code lines} that {@code before} and {@code after} surround. */
  private static List<String> pathsBetween(String before, String after, List<String> lines) {
    var fits = new LinkedHashSet<String>();
    for (String line : lines) {
      var candidates = ABSOLUTE_PATH.matcher(line);
      while (candidates.find()) {
        if (line.contains(before + candidates.group() + after)) {
          fits.add(candidates.group());
        }
      }
    }
    return List.copyOf(fits);
  }

  /**
   * The first path {@code finding}'s suggestion introduces that nothing resolves, or {@code null}
   * (#1094): a file named in the new lines and not in the replaced ones that no step of {@link
   * #resolve} settles, or a glob over an absolute directory that no patch, PR text or the replaced
   * lines name.
   */
  private String unresolvedInSuggestion(ReviewResponse.Finding finding) {
    String suggested = finding.suggestionNew();
    if (suggested == null || suggested.isBlank()) {
      return null;
    }
    String replaced = finding.suggestionOld() == null ? "" : finding.suggestionOld();
    var globs = ABSOLUTE_GLOB.matcher(suggested);
    while (globs.find()) {
      var directory =
          Pattern.compile("(?<![\\w.~/\\\\+-])" + Pattern.quote(globs.group(1)) + "(?![\\w.+-])");
      if (!directory.matcher(replaced).find()
          && !directory.matcher(verbatim).find()
          && !createsDirectory(suggested, directory)) {
        return globs.group();
      }
    }
    var urls = new SpanCursor(spans(URL, suggested));
    var tokens = TOKEN.matcher(suggested);
    while (tokens.find()) {
      String token = stripTrailingDots(tokens.group(1));
      int start = tokens.start(1);
      if (!urls.covers(start)
          && namesAFile(token)
          && !replaced.contains(token)
          && !partOfSomethingElse(suggested, start, start + token.length())
          && !createdBySuggestion(suggested, start, start + token.length())
          && resolve(token) == null) {
        return token;
      }
    }
    return null;
  }

  /**
   * Whether the token at {@code start}..{@code end} is a path the suggestion creates rather than
   * one it relies on: the destination of a Dockerfile {@code COPY} or {@code ADD} (the last
   * argument on a line those open), the last argument of a shell command that writes it ({@code
   * cp}, {@code mv}, {@code install}, {@code ln}, {@code touch}, {@code mkdir}, {@code tee}), or
   * the target of a {@code >} redirection.
   */
  private static boolean createdBySuggestion(String text, int start, int end) {
    int lineStart = text.lastIndexOf('\n', start) + 1;
    // A Dockerfile or shell line that ends with a backslash continues on the next one, so the
    // instruction a token belongs to may open lines above it.
    while (lineStart > 1 && CONTINUED.matcher(text.substring(0, lineStart - 1)).find()) {
      lineStart = text.lastIndexOf('\n', lineStart - 2) + 1;
    }
    int lineEnd = text.indexOf('\n', end);
    if (lineEnd < 0) {
      lineEnd = text.length();
    }
    String before = CONTINUATION.matcher(text.substring(lineStart, start)).replaceAll(" ");
    String line = before + text.substring(start, lineEnd);
    String after = text.substring(end, lineEnd);
    if (COPY_INSTRUCTION.matcher(line).lookingAt() && LINE_END.matcher(after).matches()) {
      return true;
    }
    if (REDIRECTED.matcher(before).find()) {
      return true;
    }
    var command = COMMAND_START.matcher(before);
    int from = 0;
    while (command.find()) {
      from = command.end();
    }
    return WRITING_COMMAND.matcher(before.substring(from)).lookingAt()
        && COMMAND_END.matcher(after).lookingAt();
  }

  /**
   * Whether {@code suggested} makes the directory {@code directory} matches: a mention of it, with
   * or without a closing slash, that {@link #createdBySuggestion} reads as written ({@code RUN
   * mkdir -p /app/lib}, {@code COPY lib/ /app/lib/}). A glob over it or a command reading it does
   * not count.
   */
  private static boolean createsDirectory(String suggested, Pattern directory) {
    var mentions = directory.matcher(suggested);
    while (mentions.find()) {
      int end = mentions.end();
      boolean slash = end < suggested.length() && suggested.charAt(end) == '/';
      if (createdBySuggestion(suggested, mentions.start(), slash ? end + 1 : end)) {
        return true;
      }
    }
    return false;
  }

  /** Whether a path-shaped token ends in a file name: an extension or a dotless file name. */
  private static boolean namesAFile(String token) {
    if (!pathShaped(token) || token.endsWith("/")) {
      return false;
    }
    String last = fileName(token);
    return (last.lastIndexOf('.') > 0 && EXTENSIONS.contains(extension(last)))
        || DOTLESS_FILE_NAMES.contains(last.toLowerCase(Locale.ROOT));
  }

  /** A patch's code lines: hunk headers and "no newline" notes left out, diff markers removed. */
  private static List<String> codeLines(String patch) {
    return patch
        .lines()
        .filter(line -> !line.isEmpty() && !line.startsWith("@@") && !line.startsWith("\\"))
        .map(line -> line.substring(1))
        .toList();
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
