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
 *
 * <p>The cues are lists of small patterns, one per shape, rather than one alternation each: the
 * rule reads shape by shape, and each pattern stays well under the regex complexity bound.
 */
final class DescriptionGapScreen {

  private DescriptionGapScreen() {}

  /** Words a quoted passage needs, after normalising, before it is judged. */
  static final int MIN_QUOTE_WORDS = 2;

  /**
   * A quoted passage: in double quotes, or in single quotes whose opening one does not follow a
   * letter and whose closing one is not followed by one, so the apostrophes of "PR's" and "doesn't"
   * neither open nor close it. Curly quotes are folded to these before matching.
   */
  private static final Pattern QUOTED =
      Pattern.compile("\"([^\"\n]++)\"|(?<![\\p{L}\\p{N}])'([^\n]+?)'(?![\\p{L}\\p{N}])");

  /**
   * The description named as such: a citation of the pull request's own text, though not by itself
   * what attributes a quote to it, since "the description column renders ('…')" quotes the change.
   */
  private static final Pattern DESCRIPTION = Pattern.compile("(?i)\\bdescription\\b");

  /**
   * The author named: a citation like {@link #DESCRIPTION}, and no attribution by itself either.
   */
  private static final Pattern AUTHOR = Pattern.compile("(?i)\\bauthor\\b");

  /** The verbs that attribute what follows them to their subject: states, claims, says, said… */
  private static final String SAYING_VERBS = "(?:(?:state|claim|promise|mention|read)s?|says|said)";

  /**
   * The description stating or claiming something: "the description states", "description says".
   */
  private static final Pattern DESCRIPTION_SAYS =
      Pattern.compile("(?i)\\bdescription\\s++" + SAYING_VERBS + "\\b");

  /** The pull request's title or body, named as the PR's: a bare "title" may be a page's. */
  private static final Pattern PR_TITLE_OR_BODY =
      Pattern.compile("(?i)\\b(?:pr|pull request)(?:['’]s)?\\s++(?:title|body)\\b");

  /** The PR or its author stating or claiming something: "the PR states", "the author says". */
  private static final Pattern PR_SAYS =
      Pattern.compile("(?i)\\b(?:pr|pull request|author)(?:['’]s)?\\s++" + SAYING_VERBS + "\\b");

  /** A claim verb opening the text, its subject elided under the section's intro line. */
  private static final Pattern LEADING_CLAIM_VERB =
      Pattern.compile("(?i)^\\s*+(?:(?:claim|state|promise)s?|says)\\b");

  /**
   * What attributes the quote that follows it to the pull request's own text: the description, the
   * PR or its author stating or claiming something, or the PR's title or body named. A bare
   * "description" or "author" does not, so "the description never mentions '…'" and "the author's
   * code caps caching at '…'" are not read as the description's claims.
   */
  private static final List<Pattern> CLAIM_CUES =
      List.of(DESCRIPTION_SAYS, PR_TITLE_OR_BODY, PR_SAYS, LEADING_CLAIM_VERB);

  /**
   * A clause saying the description does not hold something: the quote after it is what the change
   * has and the description lacks, so it is not the description's claim. Negation words, then the
   * verbs and the two-word verbs that say something is left out.
   */
  private static final List<Pattern> NEGATED_CUES =
      List.of(
          Pattern.compile("(?i)\\b(?:not|never|no|nothing|without|missing|omitted)\\b"),
          Pattern.compile("(?i)\\b(?:omits?|lacks?|doesn['’]t)\\b"),
          Pattern.compile("(?i)\\b(?:fails?\\s++to|leaves?\\s++out)\\b"));

  /**
   * An attribution verb ending the text before a quote, with "that", a colon or a comma allowed
   * after it: "the description says: '…'", "the PR claims that '…'", "the description states, '…'".
   */
  private static final Pattern VERB_BEFORE_QUOTE =
      Pattern.compile(
          "(?i)\\b(?:state|claim|say|promise|mention|read)s?(?:\\s++that)?\\s*+[:,]?\\s*+$");

  /** A linked issue, or what it asks for. */
  private static final Pattern LINKED_ISSUE =
      Pattern.compile("(?i)\\b(?:linked issues?|issue\\s*+#\\d+|acceptance criteri\\w*)\\b");

  /**
   * What a gap bullet cites when it names the description or a linked issue. A bullet may elide its
   * subject under the section's intro line ("Claims retries…", "Not mentioned: …"), so a claim
   * word, a leading claim verb and a negated mention count. A bare "mentions" does not: "the
   * configuration doc mentions …" cites the change's own documents.
   */
  private static final List<Pattern> CITATIONS =
      List.of(
          DESCRIPTION,
          AUTHOR,
          PR_TITLE_OR_BODY,
          PR_SAYS,
          LINKED_ISSUE,
          Pattern.compile("(?i)\\b(?:claim\\w*|promis\\w*|stated|narrower)\\b"),
          Pattern.compile(
              "(?i)\\b(?:un|(?:not|never|nothing|doesn['’]t|without)\\s++)mention\\w*\\b"),
          Pattern.compile("(?i)^\\s*+(?:states|says)\\b"));

  /**
   * What ends a clause before a quote: punctuation that separates clauses, a sentence end, or a
   * contrasting conjunction. A colon does not: "the description states: '…'" attributes the quote;
   * nor does a comma straight after the attribution verb ({@link #clauseOf}).
   */
  private static final Pattern CLAUSE_BREAK =
      Pattern.compile(
          "(?i)[,;!?\u2014\u2013]|\\.\\s|\\b(?:but|while|whereas|however|although|though|yet)\\b");

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
    var text = prText == null ? "" : prText;
    var source = normalize(text);
    var withIssues = normalize(text + "\n" + linkedIssues);
    var kept =
        gaps.stream()
            .filter(gap -> cites(gap) && (source.isEmpty() || !misquotes(gap, withIssues)))
            .toList();
    return kept.size() == gaps.size() ? gaps : kept;
  }

  /** Whether {@code gap} names the description, its title, the author or a linked issue. */
  static boolean cites(String gap) {
    return DescriptionGapCarryover.linkedIssueGap(gap) != null || anyFinds(CITATIONS, gap);
  }

  /**
   * Whether {@code gap} attributes to the description a quoted passage that {@code
   * normalizedSource} (already {@linkplain #normalize normalised}) does not contain. Each quote is
   * weighed on the clause before it, read from where the previous quote ended, so a quote giving
   * what the change does after a quoted claim is not taken for a second claim. The clause is read
   * whole: a long run between the cue and the quote does not lose the cue.
   */
  static boolean misquotes(String gap, String normalizedSource) {
    var text = foldQuotes(gap);
    var quotes = QUOTED.matcher(text);
    var since = 0;
    while (quotes.find()) {
      var lead = clauseOf(text.substring(since, quotes.start()));
      since = quotes.end();
      var quote = quotes.group(quotes.group(1) != null ? 1 : 2);
      if (anyFinds(CLAIM_CUES, lead)
          && !negated(lead)
          && judged(quote)
          && !appearsIn(quote, normalizedSource)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The part of {@code lead} after its last clause break, so a cue in an earlier clause ("the
   * description says X, while the code reads '…'") does not attribute the quote to the description.
   * A break that only punctuates the attribution ("the description states, '…'") is not one: the
   * verb ends the text before it, and nothing but blanks follows it.
   */
  private static String clauseOf(String lead) {
    var breaks = CLAUSE_BREAK.matcher(lead);
    var start = 0;
    while (breaks.find()) {
      if (lead.substring(breaks.end()).isBlank()
          && VERB_BEFORE_QUOTE.matcher(lead.substring(0, breaks.start())).find()) {
        break;
      }
      start = breaks.end();
    }
    return lead.substring(start);
  }

  /**
   * Whether {@code lead} says the description lacks the quote rather than makes it. A negation
   * anywhere in the clause counts ("Not mentioned in the description: '…'", "nothing in the
   * description covers '…'"), except when an attribution verb introduces the quote directly: then
   * only a negation after the cue negates the verb, so "the code doesn't do what the description
   * says: '…'" still attributes the quote to the description, while "the description never mentions
   * '…'" does not.
   */
  private static boolean negated(String lead) {
    var from = VERB_BEFORE_QUOTE.matcher(lead).find() ? lastCueEnd(lead) : 0;
    return anyFinds(NEGATED_CUES, lead.substring(from));
  }

  /** Where the last claim cue in {@code lead} ends; 0 without one. */
  private static int lastCueEnd(String lead) {
    var end = 0;
    for (var cue : CLAIM_CUES) {
      var found = cue.matcher(lead);
      while (found.find()) {
        end = Math.max(end, found.end());
      }
    }
    return end;
  }

  private static boolean anyFinds(List<Pattern> patterns, String text) {
    return patterns.stream().anyMatch(pattern -> pattern.matcher(text).find());
  }

  /** Whether a quote has enough words to be judged. */
  private static boolean judged(String quote) {
    var words = normalize(joinEditorialLetters(quote));
    return words.split(" ").length >= MIN_QUOTE_WORDS;
  }

  /**
   * Whether every part of {@code quote} between omissions appears in {@code normalizedSource}, in
   * order, on word boundaries. Each part starts after the previous one does, so a tail that
   * re-quotes the head's last words ("the cache is bounded … bounded by TTL") still appears.
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
      from = at + 1;
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
