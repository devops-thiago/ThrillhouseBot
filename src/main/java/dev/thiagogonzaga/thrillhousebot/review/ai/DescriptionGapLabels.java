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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes the summary prompt's carried-gap labels from description gaps (#970). The summary call
 * sees the gaps an earlier round listed as "G1: …", "G2: …" (#923) so it can name the resolved ones
 * in {@code addressed_gaps}; a model that echoes a label into {@code description_gaps} would put
 * "G1:" — or a bullet that is only "G1" — in front of a reviewer who never saw the prompt.
 *
 * <p>Only a leading label in the prompt's shapes is removed: "G1:", "G1 — …", "[G1]", "(G1)", or a
 * gap that is nothing but a label, which is dropped. A bare "G1" followed by more words is left
 * alone, because that is also how a gap about the G1 garbage collector starts, and a gap that
 * starts with an ordinary word ("Gateway retries …") never matches. {@code addressed_gaps} is not
 * touched: there the labels are the answer.
 *
 * <p>{@link #stripIssued} goes further, and only where the labels are known to be in play: when a
 * round put carried gaps labelled G1…Gk to the call, a label {@code G<n>} with {@code n ≤ k} is a
 * reference to one of them wherever it sits (#974), so a gap that uses it as its subject ("G2
 * stands partially unaddressed: …") or cites it mid-sentence ("as in G2", "(see G1)") is rewritten
 * too.
 */
public final class DescriptionGapLabels {

  private DescriptionGapLabels() {}

  /**
   * The leading label shapes, each kept simple on its own: "[G1]" and "(G1)", either followed by an
   * optional colon or dash; and a bare "G1" followed by a colon, a dash that ends the gap or is
   * followed by a space, or nothing at all.
   */
  private static final List<Pattern> LEADING_LABELS =
      List.of(
          Pattern.compile("^\\s*\\[\\s*G\\d{1,3}\\s*]\\s*[:\\-–—]?"),
          Pattern.compile("^\\s*\\(\\s*G\\d{1,3}\\s*\\)\\s*[:\\-–—]?"),
          Pattern.compile("^\\s*G\\d{1,3}\\s*(?::|[-–—](?=\\s|$)|$)"));

  /**
   * {@code gaps} in order, each without its leading label; a gap that was only a label is dropped.
   * Every other gap, a blank one included, is kept as it is.
   */
  public static List<String> stripAll(List<String> gaps) {
    var stripped = new ArrayList<String>(gaps.size());
    for (var gap : gaps) {
      var end = labelEnd(gap);
      if (end < 0) {
        stripped.add(gap);
        continue;
      }
      var text = gap.substring(end).strip();
      if (!text.isEmpty()) {
        stripped.add(text);
      }
    }
    return stripped;
  }

  /** Where the leading label of {@code gap} ends, or -1 when it has none. */
  private static int labelEnd(String gap) {
    for (var shape : LEADING_LABELS) {
      var label = shape.matcher(gap);
      if (label.find()) {
        return label.end();
      }
    }
    return -1;
  }

  /** One label, not part of a longer word ("G1-specific", "XG1", "G12a"). */
  private static final String LABEL = "(?<![\\w-])G\\d{1,3}";

  /** One label or several joined ("G1 and G2", "G1, G3", "G1/G2"). */
  private static final String LABELS =
      LABEL + "(?:\\s*(?:,|/|&|and|or)\\s*G\\d{1,3})*(?!\\w)(?!-\\w)";

  /**
   * Not followed by an all-caps word: "G1 GC", "G1 JVM" name the garbage collector, not a gap, even
   * in a round that issued G1.
   */
  private static final String NOT_A_NAME = "(?!\\s+[A-Z]{2,}\\b)";

  private static final Pattern LABEL_NUMBER = Pattern.compile("G(\\d{1,3})");

  /** A parenthetical that only points at labels: "(G2)", "(see G1)", "(as in G1 and G2)". */
  private static final Pattern PARENTHETICAL =
      Pattern.compile(
          "\\s*\\(\\s*(?:(?:see|cf\\.?|as in|like|per|same as)\\s+)?(" + LABELS + ")\\s*\\)");

  /**
   * A trailing reference phrase that ends its clause: ", as in G2.", "same as G1;", "see G3". The
   * phrase only points the reader at the label, so the sentence stands without it.
   */
  private static final Pattern REFERENCE_PHRASE =
      Pattern.compile(
          "(?:,\\s*|\\s+)\\b(?:as\\s+(?:in|with|per|for|noted\\s+in|listed\\s+in)|same\\s+as"
              + "|like|see|cf\\.|per)\\s+("
              + LABELS
              + ")(?=\\s*(?:[.,;:)]|$))");

  /**
   * A gap led by a label and a short status clause about it, then a colon or a spaced dash: "G2
   * stands partially unaddressed: X", "**G1 still open** — X". Group 2 is the label(s), group 3 the
   * clause, group 4 the gap itself.
   */
  private static final Pattern LEADING_STATUS =
      Pattern.compile(
          "(?s)^\\s*([*_]{0,2})("
              + LABELS
              + ")"
              + NOT_A_NAME
              + "([^:—–*_\\n.]{0,80}?)\\1\\s*(?::|\\s[-—–]\\s)\\s*(\\S.*)$");

  /** A gap that opens with a label as its subject; group 2 is the word after it. */
  private static final Pattern LEADING_SUBJECT =
      Pattern.compile("^\\s*(" + LABELS + ")" + NOT_A_NAME + "\\s+(\\p{L}+)");

  /** A label anywhere else. */
  private static final Pattern BARE = Pattern.compile("(" + LABELS + ")" + NOT_A_NAME);

  /** The most words a leading status clause has, so a whole sentence is never taken for one. */
  private static final int MAX_STATUS_WORDS = 6;

  /** Words that make a leading label the subject of a sentence about that gap. */
  private static final Set<String> SUBJECT_VERBS =
      Set.of(
          "is",
          "are",
          "was",
          "were",
          "remains",
          "remain",
          "stays",
          "stay",
          "stands",
          "stand",
          "still",
          "persists",
          "persist",
          "continues",
          "continue",
          "has",
          "have",
          "also",
          "only",
          "lacks",
          "lack",
          "needs",
          "need",
          "covers",
          "cover",
          "applies",
          "apply");

  private static final String REFERENT = "a previously listed gap";

  private static final String REFERENTS = "previously listed gaps";

  private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");

  private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([,.;:)])");

  private static final Pattern DOUBLED_COMMA = Pattern.compile(",\\s*([,.;:])");

  private static final Pattern LEADING_PUNCTUATION = Pattern.compile("^[\\s,;:]+");

  /**
   * {@code gaps} with every reference to a label the round issued removed or rewritten (#974);
   * {@code issued} is how many carried gaps the call saw, labelled G1…G{@code issued}. With none
   * issued the gaps are returned as they are, so "G1 GC is…" in a round that carried nothing is
   * never touched; a label past {@code issued} is not one of this round's and is left as well.
   *
   * <p>The rewrites, in order: a parenthetical that only cites labels is dropped; a reference
   * phrase that closes its clause (", as in G2") is dropped; a leading label with a short status
   * clause before a colon or dash ("G2 stands partially unaddressed: X") leaves only the gap
   * itself, "X", since the clause describes the label's status, which the carried gap listed next
   * to it already shows; a leading label that is a sentence's subject ("G2 remains open because …")
   * becomes "A previously listed gap"; any other label becomes "a previously listed gap" in place.
   * A gap left with nothing but punctuation is dropped.
   */
  public static List<String> stripIssued(List<String> gaps, int issued) {
    if (issued <= 0) {
      return gaps;
    }
    var stripped = new ArrayList<String>(gaps.size());
    for (var gap : gaps) {
      var text = withoutIssuedLabels(gap, issued);
      if (text == null) {
        stripped.add(gap);
      } else if (!text.isEmpty()) {
        stripped.add(text);
      }
    }
    return stripped;
  }

  /** {@code gap} rewritten without its issued labels; {@code null} when it cites none. */
  private static String withoutIssuedLabels(String gap, int issued) {
    var text = replace(PARENTHETICAL, gap, issued, m -> "");
    text = replace(REFERENCE_PHRASE, text, issued, m -> "");
    var status = LEADING_STATUS.matcher(text);
    if (status.matches()
        && allIssued(status.group(2), issued)
        && words(status.group(3)) <= MAX_STATUS_WORDS) {
      text = status.group(4);
    }
    var subject = LEADING_SUBJECT.matcher(text);
    if (subject.lookingAt() && allIssued(subject.group(1), issued)) {
      var verb = SUBJECT_VERBS.contains(subject.group(2).toLowerCase(Locale.ROOT));
      var lead = verb ? referent(subject.group(1)) + " " : "";
      text = lead + text.substring(subject.start(2));
    }
    text = replace(BARE, text, issued, m -> referent(m.group(1)));
    if (text.equals(gap)) {
      return null;
    }
    return capitalized(tidy(text));
  }

  /** {@code text} with each match whose labels were all issued replaced as {@code with} says. */
  private static String replace(
      Pattern pattern, String text, int issued, Function<MatchResult, String> with) {
    return pattern
        .matcher(text)
        .replaceAll(
            m ->
                Matcher.quoteReplacement(
                    allIssued(m.group(1), issued) ? with.apply(m) : m.group()));
  }

  /** Whether every label in {@code labels} is one of G1…G{@code issued}. */
  private static boolean allIssued(String labels, int issued) {
    var numbers = LABEL_NUMBER.matcher(labels);
    while (numbers.find()) {
      var n = Integer.parseInt(numbers.group(1));
      if (n < 1 || n > issued) {
        return false;
      }
    }
    return true;
  }

  /** The words standing in for {@code labels}: one gap or several. */
  private static String referent(String labels) {
    var numbers = LABEL_NUMBER.matcher(labels);
    var count = 0;
    while (numbers.find()) {
      count++;
    }
    return count > 1 ? REFERENTS : REFERENT;
  }

  private static int words(String clause) {
    var trimmed = clause.strip();
    return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
  }

  private static String tidy(String text) {
    var tidied = SPACES.matcher(text).replaceAll(" ");
    tidied = SPACE_BEFORE_PUNCTUATION.matcher(tidied).replaceAll("$1");
    tidied = DOUBLED_COMMA.matcher(tidied).replaceAll("$1");
    tidied = LEADING_PUNCTUATION.matcher(tidied).replaceAll("");
    tidied = tidied.strip();
    return tidied.matches("[\\p{P}\\s]*") ? "" : tidied;
  }

  private static String capitalized(String text) {
    return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }
}
