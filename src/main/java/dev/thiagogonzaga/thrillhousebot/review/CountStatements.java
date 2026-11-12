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
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Removes the sentences of the overview's prose that state a count of findings or a size of the
 * change (#1323).
 *
 * <p>The summary call sees only this round's findings, not the open total the summary renders, so
 * on #1315 round 4 its purpose paragraph said "One medium finding remains" over a risk table of 17.
 * Sizes have the same problem: the Changes Overview prints the file and line totals from the diff,
 * and a figure the call copied or rounded can only disagree with them. Every number the summary
 * posts is computed (the risk table, the Changes Overview, {@link CiFailureOverview}), so a
 * sentence of the call's that states one is cut, and the rest of the paragraph is kept.
 *
 * <p>A sentence states a count when a number, in digits or in words, comes at most three words
 * before "findings", or before a "finding" that ends its phrase ("One medium finding remains", "17
 * findings were raised"; "one finding stage" names a part of the code). It states a size when it
 * holds a signed figure ("+555"), a compound such as "555-line", or a number followed by lines,
 * files, directories, folders or modules in a sentence that frames it as the change's size: after a
 * verb such as "touches", "adds" or "across", inside an opening parenthesis, or before "changed",
 * "added" or "across". "Caps the table at 50 files" and "Java 17" are not sizes.
 */
final class CountStatements {

  private CountStatements() {}

  /**
   * A word, or a number standing on its own with an optional sign. A grouped number ("1,204") reads
   * as its digit groups, the last of which still sits before the unit it counts. A number joined to
   * a letter, a digit, a dot, an underscore, a "#" or a "+" is part of a name, a reference or a
   * version ("#1315", "P1", "v0.9.2", "C++17"), not a count.
   */
  private static final Pattern TOKEN =
      Pattern.compile(
          "(?<![#+.\\w])[+−]?\\d++(?!\\.?\\w)|\\p{L}++", Pattern.UNICODE_CHARACTER_CLASS);

  /** Punctuation that ends a phrase, so a count does not reach across it: "a new one, findings". */
  private static final Pattern PHRASE_BREAK = Pattern.compile("[,;:()–—]|\\s-\\s");

  /** The words a count of findings ends on. */
  private static final String FINDING = "finding";

  private static final String FINDINGS = "findings";

  /** How many words a count may come before the finding word: "17 open high-severity findings". */
  private static final int COUNT_REACH = 3;

  /**
   * Words after a singular "finding" that end its phrase, so the count is of findings ("One medium
   * finding remains") rather than of something a finding names ("one finding stage").
   */
  private static final Set<String> SINGULAR_ENDERS =
      Set.of(
          "is",
          "was",
          "remains",
          "remained",
          "stays",
          "stayed",
          "left",
          "open",
          "still",
          "from",
          "in",
          "on",
          "at",
          "for",
          "of",
          "to",
          "with",
          "by",
          "about",
          "that",
          "which",
          "and",
          "or",
          "but",
          "needs",
          "requires");

  /** The units a size is counted in. */
  private static final Set<String> SIZE_UNITS =
      Set.of(
          "line",
          "lines",
          "file",
          "files",
          "directory",
          "directories",
          "folder",
          "folders",
          "module",
          "modules");

  /** The past participles of editing: "40 changed lines", "555 lines added", "files touched". */
  private static final Set<String> EDITED =
      Set.of("changed", "modified", "added", "removed", "deleted", "touched");

  private static final String ACROSS = "across";

  /** A word that may sit between a number and its unit: "two new files", "40 changed lines". */
  private static final Set<String> UNIT_MODIFIERS = union(EDITED, Set.of("new", "source", "test"));

  /** Words that hedge a number and may sit between it and the verb framing it: "about 600". */
  private static final Set<String> APPROXIMATIONS =
      Set.of("about", "roughly", "around", "nearly", "approximately", "almost", "some", "over");

  /** Words before a number that frame it as the size of the change: "touches 6 files". */
  private static final Set<String> SIZE_CUES_BEFORE =
      union(
          EDITED,
          Set.of(
              ACROSS,
              "spanning",
              "spans",
              "span",
              "touches",
              "touching",
              "changes",
              "changing",
              "modifies",
              "adds",
              "adding",
              "removes",
              "removing",
              "deletes",
              "deleting"));

  /** Words after a unit that frame the number as the size of the change: "555 lines across". */
  private static final Set<String> SIZE_CUES_AFTER = union(EDITED, Set.of(ACROSS));

  /** The words of both sets, as one unmodifiable set. */
  private static Set<String> union(Set<String> first, Set<String> second) {
    return Stream.concat(first.stream(), second.stream()).collect(Collectors.toUnmodifiableSet());
  }

  /** One word of a sentence and where it sits. */
  private record Token(String word, int start, int end) {}

  /**
   * {@code summary} with the sentences stating a count or a size cut from its purpose paragraph and
   * its overall assessment; the same instance when there was none, and {@code null} for {@code
   * null}.
   */
  static ReviewResponse.Summary screen(ReviewResponse.Summary summary) {
    if (summary == null) {
      return null;
    }
    var purpose = withoutCounts(summary.prPurpose());
    var assessment = withoutCounts(summary.overallAssessment());
    if (Objects.equals(purpose, summary.prPurpose())
        && Objects.equals(assessment, summary.overallAssessment())) {
      return summary;
    }
    Log.info("Cut the summary sentences that state a finding count or a change size");
    return new ReviewResponse.Summary(
        summary.totalFindings(),
        summary.critical(),
        summary.high(),
        summary.medium(),
        summary.low(),
        assessment,
        purpose,
        summary.descriptionGaps(),
        summary.suggestedLabels(),
        summary.fileSummaries(),
        summary.walkthroughDiagram(),
        summary.addressedGaps());
  }

  /**
   * {@code text} without its sentences that state a count of findings or a size of the change;
   * {@code text} itself when it has none. The computed sentence on a failing CI check ({@link
   * CiFailureOverview}) is never cut.
   */
  static String withoutCounts(String text) {
    if (text == null || text.isBlank()) {
      return text;
    }
    var sentences = OverviewScreen.sentences(text);
    var kept =
        sentences.stream()
            .filter(s -> s.startsWith(CiFailureCitations.SENTENCE_OPENING) || !statesCount(s))
            .toList();
    return kept.size() == sentences.size() ? text : String.join(" ", kept);
  }

  /** Whether {@code sentence} states a count of findings or a size of the change. */
  static boolean statesCount(String sentence) {
    var tokens = tokens(sentence);
    for (var i = 0; i < tokens.size(); i++) {
      var word = tokens.get(i).word();
      if (signedFigure(word)) {
        return true;
      }
      if (isNumber(word)
          && (countsFindings(sentence, tokens, i) || statesSize(sentence, tokens, i))) {
        return true;
      }
    }
    return false;
  }

  private static List<Token> tokens(String sentence) {
    var tokens = new ArrayList<Token>();
    var matcher = TOKEN.matcher(sentence);
    while (matcher.find()) {
      tokens.add(
          new Token(matcher.group().toLowerCase(Locale.ROOT), matcher.start(), matcher.end()));
    }
    return tokens;
  }

  /**
   * Whether {@code word} is a number with a sign, as a diff's "+555" is. A sign joined to a name
   * ("C++17") never starts a token ({@link #TOKEN}).
   */
  private static boolean signedFigure(String word) {
    var sign = word.charAt(0);
    return sign == '+' || sign == '−';
  }

  /** Whether {@code word} is a number in digits or in words. */
  private static boolean isNumber(String word) {
    return OverviewScreen.isCount(word);
  }

  /**
   * Whether the number at {@code at} counts findings: "findings" comes within {@link #COUNT_REACH}
   * words after it in the same phrase ({@link #joined}), or a "finding" that ends its phrase
   * ({@link #SINGULAR_ENDERS}) does.
   */
  private static boolean countsFindings(String sentence, List<Token> tokens, int at) {
    var last = Math.min(tokens.size() - 1, at + COUNT_REACH);
    for (var j = at + 1; j <= last && joined(sentence, tokens.get(at), tokens.get(j)); j++) {
      var word = tokens.get(j).word();
      if (word.equals(FINDINGS)) {
        return true;
      }
      if (word.equals(FINDING)) {
        return j + 1 == tokens.size() || SINGULAR_ENDERS.contains(tokens.get(j + 1).word());
      }
    }
    return false;
  }

  /** Whether no {@link #PHRASE_BREAK} stands between {@code from} and {@code to}. */
  private static boolean joined(String sentence, Token from, Token to) {
    return !PHRASE_BREAK.matcher(sentence.substring(from.end(), to.start())).find();
  }

  /**
   * Whether the number at {@code at} is a size of the change: a unit ({@link #SIZE_UNITS}) follows
   * it, after at most one {@link #UNIT_MODIFIERS} word, and the number is joined to it by a hyphen
   * ("555-line") or framed as a size ({@link #framedAsSize}).
   */
  private static boolean statesSize(String sentence, List<Token> tokens, int at) {
    var unit = at + 1;
    if (unit < tokens.size() && UNIT_MODIFIERS.contains(tokens.get(unit).word())) {
      unit++;
    }
    if (unit >= tokens.size()
        || !SIZE_UNITS.contains(tokens.get(unit).word())
        || !joined(sentence, tokens.get(at), tokens.get(unit))) {
      return false;
    }
    var hyphenated =
        unit == at + 1
            && sentence.substring(tokens.get(at).end(), tokens.get(unit).start()).equals("-");
    return hyphenated || framedAsSize(sentence, tokens, at, unit);
  }

  /**
   * Whether the number at {@code at}, counted in the unit at {@code unit}, is framed as the
   * change's size: a {@link #SIZE_CUES_BEFORE} word comes before it (past hedges such as "about"
   * and the other words of a spelled number), an opening parenthesis does, or a {@link
   * #SIZE_CUES_AFTER} word follows the unit.
   */
  private static boolean framedAsSize(String sentence, List<Token> tokens, int at, int unit) {
    if (unit + 1 < tokens.size() && SIZE_CUES_AFTER.contains(tokens.get(unit + 1).word())) {
      return true;
    }
    var first = at;
    while (first > 0 && isNumber(tokens.get(first - 1).word())) {
      first--;
    }
    var before = sentence.substring(0, tokens.get(first).start()).stripTrailing();
    if (before.endsWith("(")) {
      return true;
    }
    var cue = first - 1;
    while (cue >= 0 && APPROXIMATIONS.contains(tokens.get(cue).word())) {
      cue--;
    }
    return cue >= 0 && SIZE_CUES_BEFORE.contains(tokens.get(cue).word());
  }
}
