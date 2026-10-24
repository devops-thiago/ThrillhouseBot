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

import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * Gives a multi-line suggestion back the indentation of the lines it replaces (#1197).
 *
 * <p>The model sometimes writes {@code suggestion_old} and {@code suggestion_new} with the same
 * indentation loss, and GitHub's "Commit suggestion" then commits misformatted code:
 *
 * <ul>
 *   <li>the first line at column 0 while the following lines keep their indent (java
 *       ThrillhouseBot-test#293, six of seven blocks);
 *   <li>one space per level where the file indents with tabs (go ThrillhouseBot-test#292).
 * </ul>
 *
 * <p>The quote is the evidence. Its non-blank lines matched the replaced lines once stripped, so
 * they pair up one to one, and the suggestion is rewritten only when the pairs show one of these
 * two losses exactly. A quote copied verbatim pairs with equal indentation, so a suggestion whose
 * own indentation differs on purpose (a Python dedent) is left as written.
 */
final class SuggestionIndent {

  private SuggestionIndent() {}

  /**
   * {@code suggestion} with the indentation of {@code replaced}, the non-blank lines it is posted
   * over, or {@code suggestion} itself when the quote shows no loss or the suggestion does not
   * carry it the same way. A single-line suggestion is never changed. {@code quote} is not null.
   */
  static String restore(String quote, String suggestion, List<String> replaced) {
    if (suggestion == null || suggestion.lines().count() < 2) {
      return suggestion;
    }
    List<String> quoted = quote.lines().filter(line -> !line.isBlank()).toList();
    if (quoted.size() < 2 || quoted.size() != replaced.size()) {
      return suggestion;
    }
    if (onlyTheFirstLineLostIndent(quoted, replaced)) {
      return keepsTheQuotedDepth(suggestion, quoted)
          ? withFirstLineIndent(suggestion, indent(quoted.getFirst()), indent(replaced.getFirst()))
          : suggestion;
    }
    OptionalInt width = spacesPerTab(quoted, replaced);
    return width.isPresent() ? withTabs(suggestion, width.getAsInt()) : suggestion;
  }

  /** Whether the quote's first line is indented less than the replaced one and the rest match. */
  private static boolean onlyTheFirstLineLostIndent(List<String> quoted, List<String> replaced) {
    String lost = indent(quoted.getFirst());
    String kept = indent(replaced.getFirst());
    if (lost.length() >= kept.length() || !kept.startsWith(lost)) {
      return false;
    }
    for (int i = 1; i < quoted.size(); i++) {
      if (!indent(quoted.get(i)).equals(indent(replaced.get(i)))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether no later line of {@code suggestion} sits shallower than every later quoted line. A
   * suggestion that lost only its first line's indent keeps the rest at the replaced depth; one
   * whose later lines are dedented too, such as a Python helper moved to module level, changes the
   * indentation on purpose and is not rewritten.
   */
  private static boolean keepsTheQuotedDepth(String suggestion, List<String> quoted) {
    int quotedDepth = laterLinesDepth(quoted.stream().skip(1));
    int suggestedDepth =
        laterLinesDepth(suggestion.lines().skip(1).filter(line -> !line.isBlank()));
    return suggestedDepth >= quotedDepth;
  }

  /**
   * The least indent among {@code lines}, all non-blank, or {@code MAX_VALUE} when there are none.
   */
  private static int laterLinesDepth(Stream<String> lines) {
    return lines.mapToInt(line -> indent(line).length()).min().orElse(Integer.MAX_VALUE);
  }

  /**
   * {@code suggestion} with its first line indented as {@code kept}, when that line carries the
   * quote's indent {@code lost}; otherwise unchanged, since the suggestion was not written with the
   * quote's loss.
   */
  private static String withFirstLineIndent(String suggestion, String lost, String kept) {
    int end = suggestion.indexOf('\n');
    String first = suggestion.substring(0, end);
    if (first.isBlank() || !indent(first).equals(lost)) {
      return suggestion;
    }
    return kept + first.substring(lost.length()) + suggestion.substring(end);
  }

  /**
   * The number of spaces the quote wrote for each tab of the replaced lines, when every replaced
   * line is indented with tabs only, every quoted line with spaces only, and one width holds for
   * every pair; otherwise empty.
   */
  private static OptionalInt spacesPerTab(List<String> quoted, List<String> replaced) {
    int width = 0;
    for (int i = 0; i < quoted.size(); i++) {
      String spaces = indent(quoted.get(i));
      String tabs = indent(replaced.get(i));
      if (!consistsOf(spaces, ' ') || !consistsOf(tabs, '\t')) {
        return OptionalInt.empty();
      }
      if (tabs.isEmpty() != spaces.isEmpty()) {
        // Indented on one side only: no number of spaces per tab maps one onto the other.
        return OptionalInt.empty();
      }
      if (tabs.isEmpty()) {
        continue;
      }
      if (spaces.length() % tabs.length() != 0
          || (width != 0 && spaces.length() / tabs.length() != width)) {
        return OptionalInt.empty();
      }
      width = spaces.length() / tabs.length();
    }
    return width == 0 ? OptionalInt.empty() : OptionalInt.of(width);
  }

  /**
   * {@code suggestion} with each line's leading spaces turned into one tab per {@code width}, or
   * unchanged when a line's indent is not spaces only or not a whole number of tabs.
   */
  private static String withTabs(String suggestion, int width) {
    String[] lines = suggestion.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.isBlank()) {
        continue;
      }
      String spaces = indent(line);
      if (!consistsOf(spaces, ' ') || spaces.length() % width != 0) {
        return suggestion;
      }
      lines[i] = "\t".repeat(spaces.length() / width) + line.substring(spaces.length());
    }
    return String.join("\n", lines);
  }

  private static boolean consistsOf(String text, char c) {
    return text.chars().allMatch(ch -> ch == c);
  }

  /**
   * The run of spaces and tabs {@code line} starts with. {@code line} is not blank, so a character
   * other than a space or a tab ends the run.
   */
  private static String indent(String line) {
    int end = 0;
    while (line.charAt(end) == ' ' || line.charAt(end) == '\t') {
      end++;
    }
    return line.substring(0, end);
  }
}
