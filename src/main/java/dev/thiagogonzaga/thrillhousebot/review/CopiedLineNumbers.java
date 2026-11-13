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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes the line numbers a finding's quotes copied from the numbered diff the review call read
 * (#1320, #1340).
 *
 * <p>Each quote line is checked against the diff itself, not guessed from its shape or from the
 * line the finding cites. The index is built from {@link DiffLineNumbers#annotate} of the very text
 * the call was given, so a number is confirmed only when the quote line is exactly the numbered
 * line the model saw ({@code 843 +code}, {@code 843 code}, or a bare {@code 843} for an empty
 * context line whose trailing spaces were not copied).
 *
 * <p>{@code suggestion_old} is stripped only when every non-blank line is confirmed in one file
 * section: a numbered line equal to that line of the numbered diff, or an unnumbered removed line
 * or "no newline" marker of the same section. The numbers come off, the removed lines and markers
 * are dropped, so a block copied across a change keeps only its new-side code, and blank lines stay
 * as they are. Anything else, such as {@code 1 + 2} written as code, is left as the model wrote it.
 *
 * <p>{@code suggestion_new} is new code, which no diff line can confirm. Its numbers come off only
 * when the same finding's {@code suggestion_old} was a confirmed copy, and only on lines whose
 * number falls inside that copy's span. Its lines starting with {@code -} or {@code \} are always
 * kept, since in new code they can be content such as a list item.
 */
final class CopiedLineNumbers {

  private static final String FENCE_OPEN = "```diff";
  private static final String FENCE = "```";
  private static final String SECTION_HEADER = "### ";

  /**
   * A numbered line: the number alone, or the number, a space, then the added or context marker.
   */
  private static final Pattern NUMBERED = Pattern.compile("(\\d{1,9})(?: ?| [+ ](.*))");

  /** Each section's numbered lines by number and its unnumbered removed lines and markers. */
  private record Section(Map<Integer, String> numbered, Set<String> unnumbered) {}

  /** The inclusive range of numbers a confirmed {@code suggestion_old} copied. */
  private record Span(int first, int last) {
    boolean holds(int number) {
      return number >= first && number <= last;
    }
  }

  private final Map<String, Section> sections;

  private CopiedLineNumbers(Map<String, Section> sections) {
    this.sections = sections;
  }

  /** The index of {@code diff} as the review call read it, numbered by {@link DiffLineNumbers}. */
  static CopiedLineNumbers of(String diff) {
    var sections = new HashMap<String, Section>();
    var annotated = DiffLineNumbers.annotate(diff);
    if (annotated == null) {
      return new CopiedLineNumbers(sections);
    }
    Section current = null;
    boolean inBlock = false;
    for (var line : annotated.split("\n", -1)) {
      if (!inBlock) {
        if (line.startsWith(SECTION_HEADER)) {
          current =
              sections.computeIfAbsent(
                  FindingQuoteValidator.sectionFilename(line),
                  k -> new Section(new HashMap<>(), new HashSet<>()));
        }
        inBlock = current != null && line.equals(FENCE_OPEN);
      } else if (line.equals(FENCE)) {
        inBlock = false;
      } else {
        index(current, line);
      }
    }
    return new CopiedLineNumbers(sections);
  }

  /**
   * Records one line of a numbered block. Inside a block only the numbered lines start with a
   * digit: every patch line starts with its marker or {@code @@}.
   */
  private static void index(Section section, String line) {
    var trimmed = line.stripTrailing();
    var number = NUMBERED.matcher(trimmed);
    if (number.matches()) {
      section.numbered().put(Integer.parseInt(number.group(1)), trimmed);
    } else if (isUnnumbered(trimmed)) {
      section.unnumbered().add(trimmed);
    }
  }

  /** A removed line or a "no newline" marker, which the numbered diff leaves without a number. */
  private static boolean isUnnumbered(String line) {
    return line.startsWith("-") || line.startsWith("\\");
  }

  /**
   * The finding with copied numbers removed from its quotes, or the same finding when none were.
   */
  ReviewResponse.Finding strip(ReviewResponse.Finding finding) {
    var old = finding.suggestionOld();
    if (old == null || old.isBlank()) {
      return finding;
    }
    var lines = old.split("\n", -1);
    for (var section : sectionsFor(finding.file())) {
      var span = copiedSpan(section, lines);
      if (span != null) {
        return new ReviewResponse.Finding(
            finding.risk(),
            finding.confidence(),
            finding.file(),
            finding.line(),
            finding.title(),
            finding.description(),
            strippedOld(lines),
            strippedNew(finding.suggestionNew(), span));
      }
    }
    return finding;
  }

  /**
   * The sections a finding's path names: the exact path, else every section whose path ends with it
   * or that it ends with, the same tolerance the quote validator gives a short path.
   */
  private List<Section> sectionsFor(String file) {
    if (file == null || file.isBlank()) {
      return List.of();
    }
    var exact = sections.get(file);
    if (exact != null) {
      return List.of(exact);
    }
    var matches = new ArrayList<Section>();
    sections.forEach(
        (path, section) -> {
          if (path.endsWith("/" + file) || file.endsWith("/" + path)) {
            matches.add(section);
          }
        });
    return matches;
  }

  /**
   * The span of numbers the quote copied from {@code section}, or {@code null} unless every
   * non-blank line is confirmed there and at least one is numbered.
   */
  private static Span copiedSpan(Section section, String[] lines) {
    int first = Integer.MAX_VALUE;
    int last = Integer.MIN_VALUE;
    for (var line : lines) {
      var trimmed = line.stripTrailing();
      if (trimmed.isEmpty()) {
        continue;
      }
      var number = NUMBERED.matcher(trimmed);
      if (number.matches() && trimmed.equals(section.numbered().get(numberOf(number)))) {
        first = Math.min(first, numberOf(number));
        last = Math.max(last, numberOf(number));
      } else if (!section.unnumbered().contains(trimmed)) {
        return null;
      }
    }
    return first <= last ? new Span(first, last) : null;
  }

  private static int numberOf(Matcher number) {
    return Integer.parseInt(number.group(1));
  }

  /**
   * A confirmed copy without its numbers, removed lines and markers. Every non-blank line of a
   * confirmed copy is numbered or one of those, so only blank lines are kept as they are.
   */
  private static String strippedOld(String[] lines) {
    var kept = new ArrayList<String>(lines.length);
    for (var line : lines) {
      var number = NUMBERED.matcher(line.stripTrailing());
      if (number.matches()) {
        kept.add(codeOf(number));
      } else if (line.isBlank()) {
        kept.add(line);
      }
    }
    return String.join("\n", kept);
  }

  /** New code without the numbers it copied from inside {@code span}; everything else is kept. */
  private static String strippedNew(String quote, Span span) {
    if (quote == null) {
      return null;
    }
    var lines = quote.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      var number = NUMBERED.matcher(lines[i].stripTrailing());
      if (number.matches() && span.holds(numberOf(number))) {
        lines[i] = codeOf(number);
      }
    }
    return String.join("\n", lines);
  }

  /** The code after a copied number and marker; empty for a bare number. */
  private static String codeOf(Matcher number) {
    var code = number.group(2);
    return code == null ? "" : code;
  }
}
