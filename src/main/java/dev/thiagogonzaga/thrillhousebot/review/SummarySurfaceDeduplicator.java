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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Collapses one observation published on several surfaces of the same summary comment down to its
 * most specific surface. A model routinely raises the same claim as an inline finding, again as a
 * "Description vs. Implementation" bullet, and again inside a Changed Files walkthrough row; a
 * reader infers severity from that repetition, so a low-value note ends up outranking the severest
 * finding in the same comment.
 *
 * <p>Precedence is inline finding &gt; description-gap bullet &gt; walkthrough row, so:
 *
 * <ul>
 *   <li>a description gap that restates a finding (or an earlier gap) is dropped;
 *   <li>a walkthrough row keeps its <em>first</em> clause always — a row summarising a file that
 *       also carries an inline finding is normal and useful — and drops only the clauses appended
 *       after it that restate an already-published claim.
 * </ul>
 *
 * <p>Two texts state the same claim when they share a contiguous run of {@value #PHRASE_TOKENS}
 * content words, or when their content words overlap by {@value #OVERLAP_THRESHOLD} of the shorter
 * side. Polarity does not gate that test in general: it holds a pair back only when the two say the
 * same things with opposite polarity — one negates, and every content word of one side is present
 * in the other, containment in either direction — because such a pair scores as a perfect match
 * while asserting opposite things. Two texts that disagree on polarity but each name something the
 * other leaves out are still judged on their content. The overlap arm needs {@value
 * #MIN_OVERLAP_TOKENS} content words on the shorter side: below that the coefficient is noise, and
 * keeping both copies is the safe direction.
 */
final class SummarySurfaceDeduplicator {

  private SummarySurfaceDeduplicator() {}

  /** Fraction of the shorter side's content words that must be shared to call it a restatement. */
  static final double OVERLAP_THRESHOLD = 0.5;

  /** Content words the shorter side needs before the overlap coefficient means anything. */
  static final int MIN_OVERLAP_TOKENS = 5;

  /** Length of the contiguous content-word run that on its own proves a shared claim. */
  static final int PHRASE_TOKENS = 3;

  /**
   * Function words carrying no claim content. Dropping them keeps "the PR says X" from looking like
   * "the docs say Y", and keeps the phrase runs aligned across two paraphrases of one claim.
   */
  private static final Set<String> STOPWORDS =
      Set.of(
          "all", "also", "an", "and", "any", "are", "as", "at", "be", "been", "but", "by", "can",
          "could", "did", "do", "does", "each", "for", "from", "has", "have", "if", "in", "into",
          "is", "it", "its", "just", "more", "of", "on", "only", "or", "other", "per", "should",
          "so", "still", "than", "that", "the", "their", "them", "then", "there", "these", "this",
          "to", "was", "were", "when", "which", "while", "with", "would");

  /**
   * Syntactic negators. A negator flips what a sentence asserts while contributing a single token,
   * so no similarity score can separate "the value is sanitized" from "the value is not sanitized"
   * — several of these read as function words and would otherwise be dropped, leaving the two
   * tokenized identically. They are kept out of the content words and tracked as polarity instead.
   */
  private static final Set<String> NEGATIONS =
      Set.of("cannot", "neither", "never", "no", "non", "none", "nor", "not", "nothing", "without");

  /**
   * A negation contracted onto its auxiliary, with the irregular stems of "can't", "won't",
   * "shan't" and "ain't" folded in. Content words are split on non-alphanumeric runs, which would
   * tear "isn't" into "isn" and "t" — neither a negator — leaving a negated sentence reading as
   * affirmative and its opposite deletable as a duplicate. Rewriting the contraction to a bare
   * "not" before the split restores the polarity, and swallowing the irregular stems keeps "wo" and
   * "ca" from becoming content words of their own.
   */
  private static final Pattern CONTRACTED_NEGATION =
      Pattern.compile("(?:ca|wo|sha|ai)?n['\u2019]t\\b");

  /** One text reduced to what it asserts: its content words in order, and whether it negates. */
  record Claim(List<String> words, boolean negated) {}

  /** The description-gap bullets and per-path walkthrough notes left after collapsing. */
  record Surfaces(List<String> descriptionGaps, Map<String, String> fileSummaries) {}

  /**
   * Collapses {@code descriptionGaps} and {@code fileSummaries} against the findings already
   * published as their own items, and against each other in precedence order.
   */
  static Surfaces collapse(
      List<String> descriptionGaps, Map<String, String> fileSummaries, List<Finding> findings) {
    var claims = new ArrayList<Claim>(findings.size() + descriptionGaps.size());
    for (Finding finding : findings) {
      claims.add(claim(finding.title()));
    }
    var keptGaps = new ArrayList<String>(descriptionGaps.size());
    for (String gap : descriptionGaps) {
      var candidate = claim(gap);
      if (!restates(candidate, claims)) {
        keptGaps.add(gap);
        claims.add(candidate);
      }
    }
    var trimmed = new HashMap<String, String>(fileSummaries.size());
    for (var entry : fileSummaries.entrySet()) {
      trimmed.put(entry.getKey(), trimRestatedClauses(entry.getValue(), claims));
    }
    return new Surfaces(keptGaps, trimmed);
  }

  /**
   * The walkthrough note with every restating clause after the first removed. The first clause is
   * the row's file summary and is never touched, so a row always keeps a real description of the
   * file even when everything appended to it was already published elsewhere.
   */
  static String trimRestatedClauses(String summary, List<Claim> claims) {
    int firstBreak = summary.indexOf(';');
    if (firstBreak < 0) {
      return summary;
    }
    var kept = new StringBuilder(summary.substring(0, firstBreak).strip());
    var dropped = false;
    for (String clause : clauses(summary.substring(firstBreak + 1))) {
      if (restates(claim(clause), claims)) {
        dropped = true;
      } else {
        kept.append("; ").append(clause);
      }
    }
    return dropped ? kept.toString() : summary;
  }

  private static List<String> clauses(String tail) {
    return Arrays.stream(tail.split(";")).map(String::strip).filter(c -> !c.isEmpty()).toList();
  }

  /**
   * True when {@code candidate} states a claim one of {@code claims} already states. The
   * candidate's phrase and word sets are derived once and reused across every claim.
   */
  private static boolean restates(Claim candidate, List<Claim> claims) {
    Set<String> candidatePhrases = phrases(candidate.words());
    Set<String> candidateWords = new HashSet<>(candidate.words());
    for (Claim claim : claims) {
      if (contradicts(candidate, candidateWords, claim)) {
        continue;
      }
      if (sharesPhrase(candidatePhrases, claim.words())
          || overlaps(candidateWords, claim.words())) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when the two make the same statement with opposite polarity: one negates and the other
   * does not, and every content word of one side is present in the other. Containment in either
   * direction counts, so a short negated claim lying wholly inside a longer affirmative one — "user
   * input is not sanitized" against "user input is sanitized before it reaches the SQL query" — is
   * caught as well; requiring the two word sets to match exactly would let that pair through and
   * delete the shorter copy. Such a pair scores as a perfect match on every similarity arm — the
   * negator is not a content word, so it cannot move the score — while asserting the opposite of
   * each other, which is a contradiction to surface, never a duplicate to delete. Polarity is only
   * decisive here: two texts that each name something the other leaves out are still judged on
   * their content, so "the PR claims X, but the code cannot X" continues to collapse onto the
   * finding that reports X missing.
   */
  private static boolean contradicts(Claim candidate, Set<String> candidateWords, Claim claim) {
    if (candidate.negated() == claim.negated()) {
      return false;
    }
    var claimWords = new HashSet<>(claim.words());
    return candidateWords.containsAll(claimWords) || claimWords.containsAll(candidateWords);
  }

  /** True when the claim contains one of the candidate's {@value #PHRASE_TOKENS}-word runs. */
  private static boolean sharesPhrase(Set<String> candidatePhrases, List<String> claim) {
    return phrases(claim).stream().anyMatch(candidatePhrases::contains);
  }

  private static Set<String> phrases(List<String> tokens) {
    var phrases = new HashSet<String>();
    for (int i = 0; i + PHRASE_TOKENS <= tokens.size(); i++) {
      phrases.add(String.join(" ", tokens.subList(i, i + PHRASE_TOKENS)));
    }
    return phrases;
  }

  /** Overlap coefficient — shared content words over the shorter side — against the threshold. */
  private static boolean overlaps(Set<String> candidateWords, List<String> claim) {
    var claimWords = new HashSet<>(claim);
    int shorter = Math.min(candidateWords.size(), claimWords.size());
    if (shorter < MIN_OVERLAP_TOKENS) {
      return false;
    }
    claimWords.retainAll(candidateWords);
    return (double) claimWords.size() / shorter >= OVERLAP_THRESHOLD;
  }

  /**
   * What {@code text} asserts. The content words are its lowercased alphanumeric runs minus stop
   * words, negators, numbers and single characters (line numbers and list markers match
   * everything), each reduced to a crude stem so "hardcodes" and "hardcoded" are one word; the
   * negators it dropped set the polarity instead of vanishing.
   */
  static Claim claim(String text) {
    if (text == null) {
      return new Claim(List.of(), false);
    }
    var words = new ArrayList<String>();
    var negated = false;
    var expanded = CONTRACTED_NEGATION.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" not ");
    for (String word : expanded.split("[^a-z0-9]+")) {
      if (NEGATIONS.contains(word)) {
        negated = true;
      } else if (word.length() > 1
          && !STOPWORDS.contains(word)
          && !Character.isDigit(word.charAt(0))) {
        words.add(stem(word));
      }
    }
    return new Claim(words, negated);
  }

  /**
   * Strips trailing inflection letters down to a shared stem. Deliberately cruder than a real
   * stemmer: it only has to make the plural, past and third-person forms of one word collide
   * ("failure"/"failures", "hardcoded"/"hardcodes", "return"/"returned"/"returns"), and a stem
   * shorter than four characters is left alone so short words are not flattened together.
   */
  private static String stem(String word) {
    int end = word.length();
    while (end > 3 && "sed".indexOf(word.charAt(end - 1)) >= 0) {
      end--;
    }
    return word.substring(0, end);
  }
}
