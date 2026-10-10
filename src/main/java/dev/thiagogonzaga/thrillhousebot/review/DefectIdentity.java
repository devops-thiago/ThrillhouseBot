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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether two findings describe the same defect. One predicate answers it for every surface that
 * pairs findings (#972): a later finding replaces an earlier "Things to double-check" item that is
 * the same defect (#951, #961), the open-thread guard drops a new item restating an open finding,
 * and the summary marks a double-check bullet "Same issue as" the inline finding it pairs with.
 * When two of those surfaces judged one pair two ways, the counts held one defect twice while the
 * summary called them one.
 *
 * <p>Two findings near each other are not the same defect just because they are close. On a
 * configuration table, adjacent rows document different keys, and their descriptions use the same
 * words ("doc", "operator", "default", the config class). The overlap test {@link
 * FollowUpAnalyzer#isSameFinding} uses for re-raises read the TRACKED_PORTS separator finding on
 * row 8 as the POLL_INTERVAL unit item on row 9 and dropped the item. The opposite also happened.
 * An events-table finding and a schema item on the same line, worded differently, stayed two
 * entries. Nor do common title words make a pair: "dequeue scans the whole job list on every job"
 * is not the approval-list finding twelve lines up (#964). So two findings are the same defect only
 * when one of these holds:
 *
 * <ul>
 *   <li>in the same file, the same line and the same title: the summary's own fold ({@link
 *       PrSummaryGenerator#reRaises});
 *   <li>in the same file, a line within {@link FollowUpAnalyzer#DUPLICATE_LINE_TOLERANCE}, and
 *       similar titles or one title sharing a run of words with the other's description;
 *   <li>in the same file, the same line, and either the titles share {@value #SHARED_TITLE_WORDS}
 *       content words, or one title names an identifier the other finding's title or description
 *       names too;
 *   <li>in the same file, titles that restate each other: a shared run of words and most of the
 *       shorter title's words ({@link SummarySurfaceDeduplicator#titlesRestate});
 *   <li>in any file, both titles name the same subject (a config key, a symbol, or the class the
 *       other finding's file is named after) and share another content word or a defect class: a
 *       restatement on the paired doc or code file (#956) or on the file that calls the code
 *       (#972);
 *   <li>in any file, one finding's description cites the other's {@code file:line} and names a
 *       symbol from its title, and the titles share two content words or a defect class.
 * </ul>
 *
 * <p>Two vetoes come first, whatever the arm. Two titles that each name identifiers (a backticked
 * span, an {@code UPPER_SNAKE} or {@code snake_case} name, a {@code camelCase} name) and share none
 * are never the same defect. Nor are two titles that each name a defect class (a race, an
 * off-by-one, a hardcoded credential, a documentation gap, …) and share none: a check-then-act race
 * and an off-by-one on the same limit check are two defects however many words they share (#964). A
 * key named only in a description never pairs two findings. A false match removes an open finding
 * from the counts, while a missed one only lists a defect twice, so ambiguous pairs stay separate.
 */
final class DefectIdentity {

  private DefectIdentity() {}

  /** Content words two same-line titles must share when neither names a common identifier. */
  static final int SHARED_TITLE_WORDS = 2;

  /**
   * Identifier shapes in a finding title, in order of preference where two start at the same
   * character: a backticked span, an underscore-joined name in either case, or a {@code camelCase}
   * name. Group 1 of each is the name. Every quantifier is bounded, as in {@code
   * ConfigKeyContextResolver}, so a crafted title cannot drive the matcher into deep recursion.
   */
  private static final List<Pattern> IDENTIFIER_SHAPES =
      List.of(
          Pattern.compile("`([^`\\n]{1,120})`"),
          Pattern.compile("\\b([A-Za-z][A-Za-z0-9]{0,63}(?:_[A-Za-z0-9]{1,64}){1,16})\\b"),
          Pattern.compile("\\b([a-z][a-z0-9]{0,63}(?:[A-Z][a-z0-9]{0,63}){1,16})\\b"));

  /**
   * Symbol shapes in a title that name code but are too broad for {@link #IDENTIFIER_SHAPES}'s veto
   * (two titles naming two classes may still be one defect): a dotted member such as {@code
   * PortEvent.parse}, a call such as {@code topN()}, and a {@code PascalCase} name of two or more
   * words such as {@code ZoneQuota}. They are positive evidence only.
   */
  private static final List<Pattern> SYMBOL_SHAPES =
      List.of(
          Pattern.compile(
              "\\b([A-Za-z_][A-Za-z0-9_]{1,63}(?:\\.[A-Za-z_][A-Za-z0-9_]{1,63}){1,4})"),
          Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]{1,63})\\(\\)"),
          Pattern.compile("\\b([A-Z][a-z0-9]{1,63}(?:[A-Z][a-z0-9]{0,63}){1,16})\\b"));

  /**
   * The defect classes a title can name, each by words that name nothing else. Generic words that
   * documentation findings use as often as the class would suggest ({@code lock}, {@code token},
   * {@code page}, {@code key}) are left out: a class only vetoes or supports a pair when both
   * titles name one.
   */
  private static final Map<String, Pattern> DEFECT_CLASSES =
      Map.of(
          "concurrency",
          classPattern(
              "races?|racy|concurren\\w{0,12}|(?:un)?synchroni[sz]\\w{0,12}|thread-?safe\\w{0,6}"
                  + "|atomic\\w{0,6}|deadlocks?|mutex\\w{0,4}|check-then-act"),
          "off-by-one",
          classPattern("off[- ]by[- ]one|fencepost"),
          "injection",
          classPattern("injection|injects?|injected"),
          "credential",
          classPattern("credentials?|secrets?|passwords?|hard-?coded"),
          "documentation",
          classPattern("documentation|documented|documents?|undocumented|docs?|readme|docstrings?"),
          "complexity",
          classPattern("quadratic|complexity|o\\(n[^)]{0,8}\\)"),
          "pagination",
          classPattern("pagination|paginat\\w{0,6}|first page|next page"),
          "crash",
          classPattern("crash\\w{0,4}|panics?|panicking|malformed"),
          "unbounded",
          classPattern("unbounded|without bound|leaks?|leaking"),
          "nullness",
          classPattern("null|nil|npe|dereferenc\\w{0,4}"));

  private static Pattern classPattern(String words) {
    return Pattern.compile("(?<![a-z0-9_])(?:" + words + ")(?![a-z0-9_])");
  }

  /** Whether {@code finding} and {@code prior}, an earlier finding, describe the same defect. */
  static boolean sameDefect(ReviewResponse.Finding finding, ReviewResponse.Finding prior) {
    return sameDefect(Finding.fromAiResponse(finding), Finding.fromAiResponse(prior));
  }

  /**
   * Whether two findings describe the same defect. Symmetric: the summary pairs a bullet with an
   * inline finding in whichever order the round lists them, and replacement asks it of a later
   * finding and an earlier one, so the two must agree on every pair.
   */
  static boolean sameDefect(Finding a, Finding b) {
    if (a.file() == null || b.file() == null) {
      return false;
    }
    if (namesDifferentIdentifiers(a.title(), b.title()) || classesConflict(a.title(), b.title())) {
      return false;
    }
    return (FilePaths.same(a.file(), b.file()) && sameDefectInFile(a, b))
        || sharesSubject(a, b)
        || citesLocation(a, b)
        || citesLocation(b, a);
  }

  /** The arms that need both findings in one file. */
  private static boolean sameDefectInFile(Finding a, Finding b) {
    if (PrSummaryGenerator.reRaises(a, b)) {
      return true;
    }
    if (Math.abs(a.line() - b.line()) <= FollowUpAnalyzer.DUPLICATE_LINE_TOLERANCE
        && (FindingDeduplicator.titleSimilarity(a.title(), b.title())
                >= FindingDeduplicator.TITLE_SIMILARITY_THRESHOLD
            || SummarySurfaceDeduplicator.titleSharesPhraseWith(a.title(), b.description())
            || SummarySurfaceDeduplicator.titleSharesPhraseWith(b.title(), a.description()))) {
      return true;
    }
    if (a.line() == b.line()
        && (sharedTitleWords(a.title(), b.title()) >= SHARED_TITLE_WORDS
            || namesIdentifierOf(a.title(), b)
            || namesIdentifierOf(b.title(), a))) {
      return true;
    }
    return SummarySurfaceDeduplicator.titlesRestate(a.title(), b.title());
  }

  /**
   * Whether both titles name one subject — a key, a symbol, or the class one finding's file is
   * named after — and share a content word beyond that subject's own, or a defect class (#956,
   * #972). The subject alone is not enough: a docs finding about {@code COLDCHAIN_MAX_BATCH}'s
   * blank default and a code finding that it is never enforced name the same key and are two
   * defects.
   */
  private static boolean sharesSubject(Finding a, Finding b) {
    var shared = new HashSet<String>();
    collectNamed(subjects(a), b.title(), shared);
    collectNamed(subjects(b), a.title(), shared);
    if (shared.isEmpty()) {
      return false;
    }
    var words = new HashSet<>(SummarySurfaceDeduplicator.claim(a.title()).wordSet());
    words.retainAll(SummarySurfaceDeduplicator.claim(b.title()).wordSet());
    shared.forEach(subject -> words.removeAll(SummarySurfaceDeduplicator.claim(subject).wordSet()));
    return !words.isEmpty() || sharesClass(a.title(), b.title());
  }

  /**
   * Whether {@code citing}'s description cites {@code cited}'s location — its file name followed by
   * a line, or a line range, within {@link FollowUpAnalyzer#DUPLICATE_LINE_TOLERANCE} of it — and
   * names a symbol of {@code cited}'s title, while the two titles share {@value
   * #SHARED_TITLE_WORDS} content words or a defect class. A finding that explains its defect by
   * quoting the earlier finding's own code is restating it from the caller's side (#972).
   */
  private static boolean citesLocation(Finding citing, Finding cited) {
    var description = citing.description();
    if (description == null || cited.title() == null) {
      return false;
    }
    var name = cited.file().substring(cited.file().lastIndexOf('/') + 1);
    if (name.isBlank()
        || !citesLine(description, name, cited.line())
        || collectNamed(subjects(cited), description, new HashSet<>()).isEmpty()) {
      return false;
    }
    return sharedTitleWords(citing.title(), cited.title()) >= SHARED_TITLE_WORDS
        || sharesClass(citing.title(), cited.title());
  }

  /** Whether {@code text} cites {@code fileName}{@code :line} or a range covering {@code line}. */
  private static boolean citesLine(String text, String fileName, int line) {
    var citation =
        Pattern.compile(
                "(?<![A-Za-z0-9_])"
                    + Pattern.quote(fileName)
                    + ":(\\d{1,6})(?:\\s{0,2}[-–]\\s{0,2}(\\d{1,6}))?",
                Pattern.CASE_INSENSITIVE)
            .matcher(text);
    var tolerance = FollowUpAnalyzer.DUPLICATE_LINE_TOLERANCE;
    while (citation.find()) {
      var from = Integer.parseInt(citation.group(1));
      var to = citation.group(2) == null ? from : Integer.parseInt(citation.group(2));
      if (line >= Math.min(from, to) - tolerance && line <= Math.max(from, to) + tolerance) {
        return true;
      }
    }
    return false;
  }

  /**
   * The subjects a finding's title names, lowercased: its identifiers, its symbols, and the class
   * its own file is named after when the title names it ({@code Waitlist} for a finding on {@code
   * Waitlist.java} titled "Waitlist is never served"). Names under three characters say nothing.
   */
  static Set<String> subjects(Finding finding) {
    var title = Objects.toString(finding.title(), "");
    var names = identifiers(title);
    for (var shape : SYMBOL_SHAPES) {
      var matcher = shape.matcher(title);
      while (matcher.find()) {
        names.add(matcher.group(1).toLowerCase(Locale.ROOT));
      }
    }
    var file = Objects.toString(finding.file(), "");
    var stem = file.substring(file.lastIndexOf('/') + 1);
    var dot = stem.indexOf('.');
    stem = (dot > 0 ? stem.substring(0, dot) : stem).toLowerCase(Locale.ROOT);
    if (namesWhole(title.toLowerCase(Locale.ROOT), stem)) {
      names.add(stem);
    }
    names.removeIf(name -> name.length() < 3);
    return names;
  }

  /**
   * Adds to {@code into} each of {@code names} that {@code text} names whole; returns {@code into}.
   */
  private static Set<String> collectNamed(Set<String> names, String text, Set<String> into) {
    if (text == null) {
      return into;
    }
    var lower = text.toLowerCase(Locale.ROOT);
    for (var name : names) {
      if (namesWhole(lower, name)) {
        into.add(name);
      }
    }
    return into;
  }

  /** The defect classes {@code title} names. */
  static Set<String> defectClasses(String title) {
    var classes = new HashSet<String>();
    if (title == null) {
      return classes;
    }
    var lower = title.toLowerCase(Locale.ROOT);
    DEFECT_CLASSES.forEach(
        (name, pattern) -> {
          if (pattern.matcher(lower).find()) {
            classes.add(name);
          }
        });
    return classes;
  }

  /** Whether both titles name a defect class and they share none. */
  static boolean classesConflict(String title, String otherTitle) {
    var classes = defectClasses(title);
    var otherClasses = defectClasses(otherTitle);
    return !classes.isEmpty()
        && !otherClasses.isEmpty()
        && classes.stream().noneMatch(otherClasses::contains);
  }

  private static boolean sharesClass(String title, String otherTitle) {
    var classes = defectClasses(title);
    classes.retainAll(defectClasses(otherTitle));
    return !classes.isEmpty();
  }

  /**
   * Whether both titles name identifiers and none of them is the same name, so the two findings are
   * about different things: two config keys, two tables, two methods. One name containing the other
   * ({@code poll_interval} and {@code poll_interval_seconds}) counts as the same name.
   */
  static boolean namesDifferentIdentifiers(String title, String otherTitle) {
    var names = identifiers(title);
    var otherNames = identifiers(otherTitle);
    if (names.isEmpty() || otherNames.isEmpty()) {
      return false;
    }
    for (var name : names) {
      for (var other : otherNames) {
        if (name.contains(other) || other.contains(name)) {
          return false;
        }
      }
    }
    return true;
  }

  /** The identifiers {@code title} names, lowercased. */
  static Set<String> identifiers(String title) {
    var names = new HashSet<String>();
    if (title == null) {
      return names;
    }
    var shapes = IDENTIFIER_SHAPES.stream().map(shape -> shape.matcher(title)).toList();
    var found = new boolean[shapes.size()];
    for (var i = 0; i < shapes.size(); i++) {
      found[i] = shapes.get(i).find();
    }
    for (var next = firstFrom(shapes, found); next != null; next = firstFrom(shapes, found)) {
      var name = next.group(1);
      if (!name.isBlank()) {
        names.add(name.strip().toLowerCase(Locale.ROOT));
      }
      skipTo(shapes, found, next.end());
    }
    return names;
  }

  /**
   * The match that starts first among {@code shapes}' current ones, the earlier shape winning a
   * tie, as one pattern alternating the shapes in that order would find it; {@code null} when none
   * has a match left.
   */
  private static Matcher firstFrom(List<Matcher> shapes, boolean[] found) {
    Matcher first = null;
    for (var i = 0; i < shapes.size(); i++) {
      if (found[i] && (first == null || shapes.get(i).start() < first.start())) {
        first = shapes.get(i);
      }
    }
    return first;
  }

  /** Moves every shape whose current match starts before {@code at} on to its next one from it. */
  private static void skipTo(List<Matcher> shapes, boolean[] found, int at) {
    for (var i = 0; i < shapes.size(); i++) {
      if (found[i] && shapes.get(i).start() < at) {
        found[i] = shapes.get(i).find(at);
      }
    }
  }

  /** Whether {@code finding}'s title or description names an identifier from {@code title}. */
  private static boolean namesIdentifierOf(String title, Finding finding) {
    var text =
        (Objects.toString(finding.title(), "") + " " + Objects.toString(finding.description(), ""))
            .toLowerCase(Locale.ROOT);
    return identifiers(title).stream().anyMatch(name -> namesWhole(text, name));
  }

  /**
   * Whether {@code text} names {@code name} as a whole word: no letter, digit or underscore runs on
   * from it at a word-character edge, so {@code events} is not found inside "prevents". An edge
   * that is itself punctuation ({@code user.name()}) needs no boundary.
   */
  static boolean namesWhole(String text, String name) {
    for (var at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + 1)) {
      var end = at + name.length();
      if ((at == 0 || !continuesWord(name.charAt(0), text.charAt(at - 1)))
          && (end == text.length()
              || !continuesWord(name.charAt(name.length() - 1), text.charAt(end)))) {
        return true;
      }
    }
    return false;
  }

  private static boolean continuesWord(char edge, char neighbour) {
    return isWordChar(edge) && isWordChar(neighbour);
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private static int sharedTitleWords(String title, String otherTitle) {
    var words = new HashSet<>(SummarySurfaceDeduplicator.claim(title).wordSet());
    words.retainAll(SummarySurfaceDeduplicator.claim(otherTitle).wordSet());
    return words.size();
  }
}
