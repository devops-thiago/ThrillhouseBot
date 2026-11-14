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
 * context line whose trailing spaces were not copied). A copy that kept the number but dropped the
 * marker ({@code 843 code}) is confirmed when its code equals that numbered line's code (#1357).
 *
 * <p>{@code suggestion_old} is stripped only when every non-blank line is confirmed in one file
 * section: a numbered line equal to that line of the numbered diff, or an unnumbered removed line
 * or "no newline" marker of the same section. The numbers come off, the removed lines and markers
 * are dropped, so a block copied across a change keeps only its new-side code, and blank lines stay
 * as they are. Anything else, such as {@code 1 + 2} written as code, is left as the model wrote it.
 *
 * <p>{@code suggestion_new} is new code, which no diff line can confirm. Its numbers come off only
 * when the same finding's {@code suggestion_old} was a confirmed copy, and only on lines whose
 * number falls inside that copy's span, with or without a marker after it. Its lines starting with
 * {@code -} or {@code \} are always kept, since in new code they can be content such as a list
 * item.
 */
final class CopiedLineNumbers {

  private static final String FENCE_OPEN = "```diff";
  private static final String FENCE = "```";
  private static final String SECTION_HEADER = "### ";

  /**
   * A numbered line: the number alone, or the number, a space, then the added or context marker.
   */
  private static final Pattern NUMBERED = Pattern.compile("(\\d{1,9})(?: ?| [+ ](.*))");

  /**
   * A numbered line copied without its marker: the number, one space, then the code as the line
   * holds it, indentation included (#1357). A context line that kept its marker reads the same way
   * with one more leading space, so a copy is only taken in this shape when its code equals that
   * numbered line's code.
   */
  private static final Pattern UNMARKED = Pattern.compile("(\\d{1,9}) (.+)");

  /**
   * Each section's numbered lines by number, the code each of them holds, and its unnumbered
   * removed lines and markers.
   */
  private record Section(
      Map<Integer, String> numbered, Map<Integer, String> code, Set<String> unnumbered) {}

  /**
   * The inclusive range of numbers a confirmed {@code suggestion_old} copied, and whether it copied
   * them without their markers.
   */
  private record Span(int first, int last, boolean unmarked) {
    boolean holds(int number) {
      return number >= first && number <= last;
    }
  }

  /** One quote line confirmed as a copy of numbered line {@code number}, holding {@code code}. */
  private record Copy(int number, String code, boolean unmarked) {}

  /** A confirmed {@code suggestion_old}: its span and its text without numbers or markers. */
  private record Copied(Span span, String old) {}

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
                  DiffIndex.sectionFilename(line),
                  k -> new Section(new HashMap<>(), new HashMap<>(), new HashSet<>()));
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
      section.numbered().put(numberOf(number), trimmed);
      section.code().put(numberOf(number), codeOf(number));
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
      var copied = copied(section, lines);
      if (copied != null) {
        return new ReviewResponse.Finding(
            finding.risk(),
            finding.confidence(),
            finding.file(),
            finding.line(),
            finding.title(),
            finding.description(),
            copied.old(),
            strippedNew(finding.suggestionNew(), copied.span()));
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
   * The quote as copied from {@code section}, or {@code null} unless every non-blank line is
   * confirmed there and at least one is numbered. Its text keeps the code of each numbered line and
   * the blank lines, and drops the removed lines and markers.
   */
  private static Copied copied(Section section, String[] lines) {
    int first = Integer.MAX_VALUE;
    int last = Integer.MIN_VALUE;
    boolean unmarked = false;
    var kept = new ArrayList<String>(lines.length);
    for (var line : lines) {
      var trimmed = line.stripTrailing();
      if (trimmed.isEmpty()) {
        kept.add(line);
        continue;
      }
      var copy = copy(section, trimmed);
      if (copy != null) {
        first = Math.min(first, copy.number());
        last = Math.max(last, copy.number());
        unmarked |= copy.unmarked();
        kept.add(copy.code());
      } else if (!section.unnumbered().contains(trimmed)) {
        return null;
      }
    }
    return first <= last
        ? new Copied(new Span(first, last, unmarked), String.join("\n", kept))
        : null;
  }

  /**
   * {@code line} as a copy of one of the section's numbered lines, with or without its marker, or
   * {@code null} when it copies none.
   */
  private static Copy copy(Section section, String line) {
    var number = NUMBERED.matcher(line);
    if (number.matches() && line.equals(section.numbered().get(numberOf(number)))) {
      return new Copy(numberOf(number), codeOf(number), false);
    }
    var unmarked = UNMARKED.matcher(line);
    if (unmarked.matches() && unmarked.group(2).equals(section.code().get(numberOf(unmarked)))) {
      return new Copy(numberOf(unmarked), unmarked.group(2), true);
    }
    return null;
  }

  private static int numberOf(Matcher number) {
    return Integer.parseInt(number.group(1));
  }

  /**
   * New code without the numbers it copied from inside {@code span}; everything else is kept. A
   * line written {@code N +code} kept its added marker and loses it with the number. Otherwise
   * {@code N code} is a context marker plus code when the old code was copied with its markers, and
   * indented code when any of its lines dropped them, as the confirmed old quote shows.
   */
  private static String strippedNew(String quote, Span span) {
    if (quote == null) {
      return null;
    }
    var lines = quote.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      var number = newCodeLine(lines[i].stripTrailing(), span);
      if (number.matches() && span.holds(numberOf(number))) {
        lines[i] = codeOf(number);
      }
    }
    return String.join("\n", lines);
  }

  /** {@code line} read in the shape {@link #strippedNew} reads it in. */
  private static Matcher newCodeLine(String line, Span span) {
    var marked = NUMBERED.matcher(line);
    var added = marked.matches() && line.startsWith(marked.group(1) + " +");
    return added || !span.unmarked() ? marked : UNMARKED.matcher(line);
  }

  /** The code after a copied number and marker; empty for a bare number. */
  private static String codeOf(Matcher number) {
    var code = number.group(2);
    return code == null ? "" : code;
  }
}
