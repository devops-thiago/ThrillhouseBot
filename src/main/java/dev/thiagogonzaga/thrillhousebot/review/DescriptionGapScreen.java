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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Drops a Description vs. Implementation bullet that does not hold up against the text it claims to
 * describe (#946, #964). Two checks, both deterministic:
 *
 * <ul>
 *   <li><b>A quoted claim must be in the description.</b> A bullet that attributes a quoted passage
 *       to the PR title or description ("the PR description claims '…'") is dropped when that
 *       passage is not there. The model once misquoted "{@code docs/RUST-V10.md}" as "{@code
 *       docs/RUST-VEV10.md}" and then reported its own misquote as a gap, two rounds running. Both
 *       sides are compared case-, whitespace- and punctuation-blind, an editorial bracket such as
 *       "[t]he" reads as the letters it holds, and a bracketed or "…" omission splits the quote
 *       into parts that must appear in order. Only a quote of at least {@value #MIN_QUOTE_WORDS}
 *       words that follows a claim cue is judged: a quoted path or value the bullet gives as what
 *       the change does is not the description's claim, and a single quoted word is usually an
 *       identifier the model inflected.
 *   <li><b>A bullet must cite the description or a linked issue.</b> A gap is a mismatch between
 *       what the author or the linked issue says and what the change does; one that names neither —
 *       a configuration document's example disagreeing with the code, say — is a finding about the
 *       change, not a gap in its description, and reads as one only by sitting under the heading.
 * </ul>
 *
 * <p>The screen runs on the summary call's own gaps and on the gaps carried from the previous round
 * alike ({@link DescriptionGapCarryover.Carry#apply}), so a misquote carried over is dropped too.
 */
final class DescriptionGapScreen {

  private DescriptionGapScreen() {}

  /** Words a quoted passage needs, after normalising, before it is judged. */
  static final int MIN_QUOTE_WORDS = 2;

  /** Characters before a quote searched for the cue that attributes it to the description. */
  private static final int CUE_WINDOW = 120;

  /**
   * A quoted passage: in double quotes, or in single quotes whose opening one does not follow a
   * letter and whose closing one is not followed by one, so the apostrophes of "PR's" and "doesn't"
   * neither open nor close it. Curly quotes are folded to these before matching.
   */
  private static final Pattern QUOTED =
      Pattern.compile("\"([^\"\n]++)\"" + "|(?<![\\p{L}\\p{N}])'([^\n]+?)'(?![\\p{L}\\p{N}])");

  /**
   * What attributes the quote that follows it to the pull request's own text: the description, its
   * title or body, the author, or the PR stating or claiming something.
   */
  private static final Pattern CLAIM_CUE =
      Pattern.compile(
          "(?i)\\b(?:description|title|body|author|(?:pr|pull request)(?:['’]s)?\\s++"
              + "(?:states?|claims?|says|promises?|mentions?|reads?))\\b");

  /**
   * What a gap bullet cites when it names the description or a linked issue. A bullet may elide its
   * subject under the section's intro line ("Claims retries…"), so a leading claim verb counts.
   */
  private static final Pattern CITATION =
      Pattern.compile(
          "(?i)\\b(?:description|pr body|pr title|title|author|linked issues?|issue\\s*+#\\d+"
              + "|acceptance criteri\\w*|claim\\w*|promis\\w*|stated|mention\\w*|narrower)\\b"
              + "|^\\s*+(?:states|says)\\b");

  /** An editorial bracket holding one or two letters, as in "[t]he". */
  private static final Pattern EDITORIAL_LETTERS = Pattern.compile("\\[(\\p{L}{1,2})]");

  /** An omission inside a quote: a bracketed insertion, or an ellipsis. */
  private static final Pattern OMISSION = Pattern.compile("\\[[^]]*+]|\\.{3}|…");

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

  /**
   * {@code gaps} less every bullet that misquotes {@code prText} or cites neither the description
   * nor a linked issue. With {@code prText} blank nothing is judged as a misquote, since there is
   * no text to judge against; the citation check still applies.
   *
   * @param prText the PR title and description as the summary call saw them, unfenced
   * @param linkedIssues the unfenced linked-issue section, {@code ""} when none; a quote taken from
   *     an issue is a citation, not a misquote
   */
  static List<String> screen(List<String> gaps, String prText, String linkedIssues) {
    var source = normalize(prText == null ? "" : prText);
    var withIssues = normalize((prText == null ? "" : prText) + "\n" + linkedIssues);
    var kept = new ArrayList<String>(gaps.size());
    for (var gap : gaps) {
      if (cites(gap) && (source.isEmpty() || !misquotes(gap, withIssues))) {
        kept.add(gap);
      }
    }
    return kept.size() == gaps.size() ? gaps : List.copyOf(kept);
  }

  /** Whether {@code gap} names the description, its title, the author or a linked issue. */
  static boolean cites(String gap) {
    return DescriptionGapCarryover.linkedIssueGap(gap) != null || CITATION.matcher(gap).find();
  }

  /**
   * Whether {@code gap} attributes to the description a quoted passage that {@code
   * normalizedSource} (already {@linkplain #normalize normalised}) does not contain. Each quote is
   * weighed on the text since the previous quote ended, so a quote giving what the change does
   * after a quoted claim is not taken for a second claim.
   */
  static boolean misquotes(String gap, String normalizedSource) {
    var text = foldQuotes(gap);
    var quotes = QUOTED.matcher(text);
    var since = 0;
    while (quotes.find()) {
      var lead = text.substring(Math.max(since, quotes.start() - CUE_WINDOW), quotes.start());
      since = quotes.end();
      var quote = quotes.group(1) != null ? quotes.group(1) : quotes.group(2);
      if (CLAIM_CUE.matcher(lead).find() && judged(quote) && !appearsIn(quote, normalizedSource)) {
        return true;
      }
    }
    return false;
  }

  /** Whether a quote has enough words to be judged. */
  private static boolean judged(String quote) {
    var words = normalize(joinEditorialLetters(quote));
    return !words.isEmpty() && words.split(" ").length >= MIN_QUOTE_WORDS;
  }

  /**
   * Whether every part of {@code quote} between omissions appears in {@code normalizedSource}, in
   * order, on word boundaries.
   */
  static boolean appearsIn(String quote, String normalizedSource) {
    var haystack = " " + normalizedSource + " ";
    var from = 0;
    for (var part : OMISSION.split(joinEditorialLetters(quote))) {
      var needle = normalize(part);
      if (needle.isEmpty()) {
        continue;
      }
      var at = haystack.indexOf(" " + needle + " ", from);
      if (at < 0) {
        return false;
      }
      from = at + needle.length() + 1;
    }
    return true;
  }

  private static String joinEditorialLetters(String quote) {
    return EDITORIAL_LETTERS.matcher(quote).replaceAll("$1");
  }

  private static String foldQuotes(String text) {
    return text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'');
  }

  /** Lowercase letters and digits, every other run collapsed to one space. */
  static String normalize(String text) {
    return NON_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }
}
