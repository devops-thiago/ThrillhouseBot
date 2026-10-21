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
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The linked-issue criteria the PR description shows are met by code the base branch already holds
 * (#1148). The summary call sees only the diff, so a criterion an earlier pull request met looks
 * unmet: no changed file touches it. On #1141 two #952 criteria shipped in 0.7.1 were listed under
 * Description vs. Implementation on every round, though the description named the method and the
 * tests that meet each one, and a linked-issue gap is released only by code in the diff.
 *
 * <p>A {@code Linked issue #N: <criterion>} line is moved to "met by existing code" when all of
 * these hold, and is kept otherwise:
 *
 * <ul>
 *   <li>a passage of the description (a paragraph or a list item of the author's own prose: never
 *       inside a fenced code block, a blockquote or an HTML comment) states the criterion: it
 *       shares at least {@value LinkedIssueCriteria#MIN_SHARED} of the criterion's content words
 *       and half of them, the most of any passage;
 *   <li>that passage, or the paragraph that introduces its list, says the criterion is already met
 *       ({@link #MET}: "already", "shipped", "landed", "existing code", "is met"), with no negation
 *       earlier in that clause ({@link #NEGATION});
 *   <li>the passage cites code, as a {@code path:line} or as a {@code Type.member} code span, such
 *       as a test's {@code SecurityScanTest.eachIacRule…}, and every one of its citations is on the
 *       base: the file is in the repository and not added or renamed by this pull request, the
 *       cited line is in the file and not one the pull request adds, and the member is named on a
 *       line of its type's file that the pull request does not add.
 * </ul>
 *
 * <p>Anything this class cannot read counts against the release: no repository listing, a path not
 * in a truncated listing or matching several files, a changed file whose patch GitHub omitted, or a
 * file that cannot be read or is past the read cap. Each cited file is read once. A criterion line
 * that points at an open finding is never released, since that finding says the changed code does
 * not meet it.
 */
final class BaseCodeCitations {

  /**
   * What says a criterion is already met, rather than met by this change. One pattern per shape; a
   * match with a {@link #NEGATION} earlier in its clause does not count.
   */
  private static final List<Pattern> MET =
      List.of(
          Pattern.compile("(?i)\\b(?:already|shipped|landed)\\b"),
          Pattern.compile("(?i)\\bexisting\\s++code\\b"),
          Pattern.compile("(?i)\\b(?:is|are|was|were)\\s++met\\b"),
          Pattern.compile("(?i)\\bmet\\s++(?:by|in|on|before)\\b"),
          Pattern.compile("(?i)\\bbefore\\s++this\\s++(?:pr|pull\\s++request|change)\\b"));

  /**
   * A negation in the clause before a {@link #MET} match: "not", "never", "no", "nor", "cannot" or
   * a contraction's "n't", wherever it stands in that clause ("is not, as far as I can tell,
   * already met").
   */
  private static final Pattern NEGATION =
      Pattern.compile("(?i)\\b(?:not|never|no|nor|cannot)\\b|n['’]t\\b");

  /** What ends a clause: sentence punctuation followed by a blank, or a line break. */
  private static final Pattern CLAUSE_END = Pattern.compile("[.;:!?](?=\\s)|\\R");

  /** Opens an HTML comment, which GitHub does not render. */
  private static final String COMMENT_OPEN = "<!--";

  /** Closes an HTML comment; an unclosed one runs to the end. */
  private static final String COMMENT_CLOSE = "-->";

  /**
   * A run of the characters a {@code path:line} citation is written with; {@link #pathLine} reads
   * the citation out of it.
   */
  private static final Pattern PATH_TOKEN = Pattern.compile("[\\w./:-]++");

  /** Digits of a cited line number, at most. */
  private static final int MAX_LINE_DIGITS = 9;

  /** A {@code path:line} citation read from a token. */
  record PathLine(String path, int line) {}

  /** Characters a file extension may have, at most. */
  private static final int MAX_EXTENSION_CHARS = 10;

  /** A code span holding only {@code Type.member} or {@code Type.member()}. */
  private static final Pattern SYMBOL_SPAN =
      Pattern.compile("`([A-Z][A-Za-z0-9_$]*+)\\.([A-Za-z_$][A-Za-z0-9_$]*+)(?:\\(\\))?`");

  /** A list item's marker: "- ", "* ", "+ ", "1. " or "1) ". */
  private static final Pattern LIST_ITEM = Pattern.compile("^\\s*+(?:[-*+]|\\d++[.)])\\s++");

  /** Statuses of a changed file that did not exist on the base under its name. */
  private static final Set<String> NEW_ON_HEAD = Set.of("added", "renamed", "copied");

  /** Reads nothing and releases nothing. */
  static final BaseCodeCitations NONE = new BaseCodeCitations("", List.of(), null, null);

  /** A criterion the description shows met, and the code it cites. */
  record Met(String issues, String criterion, List<String> citations) {}

  /** {@code gaps} split into those still listed and those met by existing code, each in order. */
  record Split(List<String> unmet, List<Met> met) {}

  /** A paragraph or list item of the description, with the paragraph introducing its list. */
  private record Passage(String text, String lead) {}

  private final List<Passage> passages;
  private final Map<String, GitHubPullRequestClient.FileDiff> changed;
  private final Supplier<Optional<CitedLocationResolver.RepositoryPaths>> paths;
  private final Function<String, Optional<List<String>>> lines;

  private BaseCodeCitations(
      String description,
      List<GitHubPullRequestClient.FileDiff> files,
      Supplier<Optional<CitedLocationResolver.RepositoryPaths>> paths,
      Function<String, Optional<List<String>>> lines) {
    this.passages = passages(description);
    this.changed = new HashMap<>();
    files.forEach(file -> changed.putIfAbsent(file.filename(), file));
    this.paths = paths;
    var read = new HashMap<String, Optional<List<String>>>();
    this.lines = path -> read.computeIfAbsent(path, lines);
  }

  /**
   * Citations read from {@code description}, the PR body alone (never the linked issue's text,
   * which states the criteria and may cite code of its own), against every file the pull request
   * changes and a repository read at its head.
   */
  static BaseCodeCitations of(
      String description,
      List<GitHubPullRequestClient.FileDiff> files,
      CitedLocationResolver.Round repository) {
    return of(description, files, repository::repositoryPaths, repository::citedFileLines);
  }

  /** {@link #of(String, List, CitedLocationResolver.Round)} over the two reads it needs. */
  static BaseCodeCitations of(
      String description,
      List<GitHubPullRequestClient.FileDiff> files,
      Supplier<Optional<CitedLocationResolver.RepositoryPaths>> paths,
      Function<String, Optional<List<String>>> lines) {
    if (description == null || description.isBlank()) {
      return NONE;
    }
    return new BaseCodeCitations(
        description, Objects.requireNonNullElse(files, List.of()), paths, lines);
  }

  /** {@code gaps} with the linked-issue criteria the description shows met moved out. */
  Split split(List<String> gaps) {
    if (passages.isEmpty() || gaps.isEmpty()) {
      return new Split(gaps, List.of());
    }
    var unmet = new ArrayList<String>(gaps.size());
    var met = new ArrayList<Met>();
    for (var gap : gaps) {
      var found = metOnBase(gap);
      if (found == null) {
        unmet.add(gap);
      } else {
        Log.infof(
            "Listed a linked-issue criterion as met by existing code the description cites: %s",
            LogSafe.oneLine(gap));
        met.add(found);
      }
    }
    return met.isEmpty() ? new Split(gaps, List.of()) : new Split(List.copyOf(unmet), met);
  }

  private Met metOnBase(String gap) {
    var linked = DescriptionGapCarryover.linkedIssueGap(gap);
    if (linked == null || LinkedIssueCriteria.pointsAtFinding(gap)) {
      return null;
    }
    var criterion = DescriptionGapCarryover.criterionOf(linked.group(2)).strip();
    var passage = statingPassage(criterion);
    if (passage == null || !(claimsMet(passage.text()) || claimsMet(passage.lead()))) {
      return null;
    }
    var citations = new LinkedHashSet<String>();
    var tokens = PATH_TOKEN.matcher(passage.text());
    while (tokens.find()) {
      var cited = pathLine(tokens.group());
      if (cited != null) {
        if (!lineOnBase(cited.path(), cited.line())) {
          return null;
        }
        citations.add(cited.path() + ":" + cited.line());
      }
    }
    var symbols = SYMBOL_SPAN.matcher(passage.text());
    while (symbols.find()) {
      if (!memberOnBase(symbols.group(1), symbols.group(2))) {
        return null;
      }
      citations.add(symbols.group(1) + "." + symbols.group(2));
    }
    return citations.isEmpty()
        ? null
        : new Met(linked.group(1).strip(), criterion, List.copyOf(citations));
  }

  /** The passage sharing the most of the criterion's words, when it shares enough of them. */
  private Passage statingPassage(String criterion) {
    var words = SummarySurfaceDeduplicator.claim(criterion).wordSet();
    Passage best = null;
    var most = 0;
    for (var passage : passages) {
      var shared =
          LinkedIssueCriteria.shared(
              words, SummarySurfaceDeduplicator.claim(passage.text()).wordSet());
      if (shared > most) {
        best = passage;
        most = shared;
      }
    }
    return most >= LinkedIssueCriteria.MIN_SHARED && 2 * most >= words.size() ? best : null;
  }

  private static boolean claimsMet(String text) {
    for (var pattern : MET) {
      var met = pattern.matcher(text);
      while (met.find()) {
        if (!NEGATION.matcher(clauseBefore(text, met.start())).find()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * The {@code path:line} a token holds: a file path ({@link #namesAFile}), a colon, and a line of
   * at most {@value #MAX_LINE_DIGITS} digits, which may be followed by a {@code :column}. {@code
   * null} for anything else, such as a URL or a time of day.
   */
  static PathLine pathLine(String token) {
    var colon = token.indexOf(':');
    if (colon <= 0 || !namesAFile(token.substring(0, colon))) {
      return null;
    }
    var end = colon + 1;
    while (end < token.length() && Character.isDigit(token.charAt(end))) {
      end++;
    }
    var digits = end - colon - 1;
    var closed = end == token.length() || token.charAt(end) == ':' || token.charAt(end) == '.';
    if (digits == 0 || digits > MAX_LINE_DIGITS || !closed) {
      return null;
    }
    return new PathLine(
        token.substring(0, colon), Integer.parseInt(token.substring(colon + 1, end)));
  }

  /** The text of the clause {@code at} stands in, from the clause's start up to {@code at}. */
  private static String clauseBefore(String text, int at) {
    var start = 0;
    var ends = CLAUSE_END.matcher(text).region(0, at);
    while (ends.find()) {
      start = ends.end();
    }
    return text.substring(start, at);
  }

  /** Whether a path token names a file: its last segment has a name and an extension. */
  static boolean namesAFile(String token) {
    var name = fileName(token);
    var dot = name.lastIndexOf('.');
    var extension = name.substring(dot + 1);
    return dot > 0
        && !extension.isEmpty()
        && extension.length() <= MAX_EXTENSION_CHARS
        && extension.chars().allMatch(Character::isLetterOrDigit);
  }

  /**
   * Whether the cited line of the cited file is base code: the file is, and the line is in it and
   * not added (#1177).
   */
  private boolean lineOnBase(String cited, int line) {
    var path = resolve(cited, name -> name.equals(fileName(cited)));
    return path.isPresent()
        && line > 0
        && addedLines(path.get()).map(a -> !a.contains(line)).orElse(false)
        && lines.apply(path.get()).map(file -> line <= file.size()).orElse(false);
  }

  /** Whether {@code member} is named on a base line of the one file {@code type} is named after. */
  private boolean memberOnBase(String type, String member) {
    var path = resolve(type, name -> stem(name).equals(type));
    if (path.isEmpty()) {
      return false;
    }
    var added = addedLines(path.get());
    var content = lines.apply(path.get());
    if (added.isEmpty() || content.isEmpty()) {
      return false;
    }
    var file = content.get();
    for (int i = 0; i < file.size(); i++) {
      if (!added.get().contains(i + 1) && namesIdentifier(file.get(i), member)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The one repository path {@code cited} names: itself when it is a listed path holding a
   * directory, else the one listed file whose name {@code matches} and whose path ends with what
   * was cited. Empty when the listing is unread, holds no such file or several, or is truncated and
   * holds none.
   */
  private Optional<String> resolve(String cited, Predicate<String> matches) {
    var listing = paths.get();
    if (listing.isEmpty()) {
      return Optional.empty();
    }
    var all = listing.get().paths();
    if (cited.indexOf('/') >= 0 && all.contains(cited)) {
      return Optional.of(cited);
    }
    var found =
        all.stream()
            .filter(p -> matches.test(fileName(p)))
            .filter(p -> cited.indexOf('/') < 0 || p.endsWith("/" + cited))
            .limit(2)
            .toList();
    return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
  }

  /**
   * The lines this pull request adds to {@code path}: none for a file it does not change. Empty
   * when the file is not on the base under that name, or is changed with no patch to tell which
   * lines are new.
   */
  private Optional<NavigableSet<Integer>> addedLines(String path) {
    var file = changed.get(path);
    if (file == null) {
      return Optional.of(new TreeSet<>());
    }
    if (NEW_ON_HEAD.contains(Objects.requireNonNullElse(file.status(), ""))
        || file.patch() == null
        || file.patch().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(PatchCoverageResolver.addedLines(file.patch()));
  }

  private static boolean namesIdentifier(String line, String name) {
    for (int at = line.indexOf(name); at >= 0; at = line.indexOf(name, at + 1)) {
      if (!identifierChar(line, at - 1) && !identifierChar(line, at + name.length())) {
        return true;
      }
    }
    return false;
  }

  private static boolean identifierChar(String text, int at) {
    if (at < 0 || at >= text.length()) {
      return false;
    }
    char c = text.charAt(at);
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  private static String fileName(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String stem(String name) {
    var dot = name.indexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  /**
   * The description's paragraphs and list items, each list item with the last paragraph before it
   * that is not one, which is what introduces a list ("ACs 1-4 shipped in 0.7.1: …"). A heading
   * ends a passage and the list it introduced.
   */
  private static List<Passage> passages(String description) {
    var reader = new PassageReader();
    for (var line : authorProse(description).split("\\R", -1)) {
      reader.read(line);
    }
    reader.flush();
    return List.copyOf(reader.passages);
  }

  /**
   * {@code description} with what is not the author's own prose blanked, line breaks kept: fenced
   * code blocks ({@link MarkdownFences}) and HTML comments. A claim quoted from elsewhere, or left
   * in a comment the page does not show, is not the description's.
   */
  private static String authorProse(String description) {
    var prose = new StringBuilder(description);
    for (var span : MarkdownFences.spans(description)) {
      blank(prose, span[0], span[1]);
    }
    var open = description.indexOf(COMMENT_OPEN);
    while (open >= 0) {
      var close = description.indexOf(COMMENT_CLOSE, open + COMMENT_OPEN.length());
      var end = close < 0 ? description.length() : close + COMMENT_CLOSE.length();
      blank(prose, open, end);
      open = description.indexOf(COMMENT_OPEN, end);
    }
    return prose.toString();
  }

  private static void blank(StringBuilder text, int start, int end) {
    for (int i = start; i < end; i++) {
      if (text.charAt(i) != '\n') {
        text.setCharAt(i, ' ');
      }
    }
  }

  /** Reads the description line by line into {@link Passage}s. */
  private static final class PassageReader {
    private final List<Passage> passages = new ArrayList<>();
    private String lead = "";
    private StringBuilder open;
    private boolean openIsItem;

    void read(String line) {
      var heading = line.startsWith("#");
      // A blockquote is someone else's text: it ends a passage and is none.
      var quote = line.strip().startsWith(">");
      var item = !heading && !quote && LIST_ITEM.matcher(line).find();
      if (line.isBlank() || heading || quote || item) {
        flush();
        lead = heading ? "" : lead;
        open = item ? new StringBuilder(line.strip()) : null;
        openIsItem = item;
      } else if (open == null) {
        open = new StringBuilder(line.strip());
        openIsItem = false;
      } else {
        open.append(' ').append(line.strip());
      }
    }

    void flush() {
      if (open == null) {
        return;
      }
      var text = open.toString();
      passages.add(new Passage(text, openIsItem ? lead : ""));
      lead = openIsItem ? lead : text;
      open = null;
    }
  }
}
