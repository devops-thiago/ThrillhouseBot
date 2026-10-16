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

import java.util.Arrays;
import java.util.LinkedHashSet;
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
 *       words that a claim cue attributes is judged, the cue before the quote or, as in "'…' is
 *       what the description promises", right after it (#1064): a quoted path or value the bullet
 *       gives as what the change does is not the description's claim, and a single quoted word is
 *       usually an identifier the model inflected. A single token that runs words together, camel
 *       case such as "retryForever" or a run such as "retriesforever" that starts or ends with a
 *       word of the PR text, is judged too, against the PR text with its separators removed.
 *   <li><b>A bullet must cite the description or a linked issue.</b> A gap is a mismatch between
 *       what the author or the linked issue says and what the change does; one that names neither —
 *       a configuration document's example disagreeing with the code, say — is a finding about the
 *       change, not a gap in its description, and reads as one only by sitting under the heading.
 *   <li><b>An omission must be one.</b> A bullet saying the description leaves out files or
 *       components it then names ("the description … not … the python-ci workflow, Dockerfile,
 *       config docs") is dropped when most of what it names is in the PR text (#1074).
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
   * Letters, at least, of both the PR-text word a run-together token starts or ends with and the
   * rest of it, so a suffix ("bookings" from "booking") or a short word ("the" in "theory") does
   * not make an ordinary word read as two run together.
   */
  static final int MIN_RUN_PART = 4;

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

  /**
   * The verbs that attribute what follows them to their subject, present or past: states, stated,
   * claims, claimed, says, said…
   */
  private static final String SAYING_VERBS =
      "(?:(?:state|promise)[sd]?|(?:claim|mention)(?:s|ed)?|reads?|says|said)";

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
   * The pull request's own text named in a clause, with or without a verb: what an omission gap
   * says lacks a passage ("the description never mentions '…'", "the PR says nothing about '…'").
   */
  private static final List<Pattern> PR_TEXT_NAMED =
      List.of(DESCRIPTION, PR_TITLE_OR_BODY, PR_SAYS);

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

  /**
   * An attribution after a quote, opening its clause: "'…' is what the description promises". The
   * claim cue itself is read from the rest of the clause ({@link #CLAIM_CUES}).
   */
  private static final Pattern WHAT_FOLLOWS_QUOTE =
      Pattern.compile("(?i)^\\s*+(?:is|are|was|were)\\s++what\\b");

  /** A lower-case letter followed by an upper-case one: a camel-case word boundary. */
  private static final Pattern CAMEL_HUMP = Pattern.compile("\\p{Ll}\\p{Lu}");

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

  /** A possessive ending belongs to its word, not between a compound identifier's parts. */
  private static final Pattern POSSESSIVE = Pattern.compile("(?i)(?<=[\\p{L}\\p{N}])['’]s\\b");

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

  /** Full stops ending a name, which end its sentence rather than the name. */
  private static final Pattern TRAILING_DOTS = Pattern.compile("\\.++$");

  /**
   * A file or component an omission bullet names, one pattern or more per shape: a path of two
   * slashes or more (one slash is as often "and/or" or "on/off"), a directory ending in a slash (a
   * sentence's full stop may follow it), a file name with an extension, a CI job name
   * ("python-ci"), or a file conventionally named without one ("Dockerfile", "README", written as
   * the file is). A backticked span alone is not one (it is as often a setting or an identifier),
   * nor is a word such as "license" that prose uses for other things.
   */
  private static final List<Pattern> NAMED_FILES =
      List.of(
          Pattern.compile("(?<![\\w/.-])[\\w.-]*+(?:/[\\w.-]++){2,}+"),
          Pattern.compile("\\b[\\w.-]++/(?![\\w-]|\\.\\w)"),
          Pattern.compile("\\b[\\w-]++\\.(?:md|ya?ml|json|toml|xml|txt|sh|env|cfg|ini|lock)\\b"),
          Pattern.compile("\\b[\\w-]++\\.(?:java|kts?|scala|py|go|rs|zig|c|h|rb|php|cs)\\b"),
          Pattern.compile("\\b[\\w-]++\\.(?:[jt]sx?|mjs|vue|html|css|sql|gradle|properties)\\b"),
          Pattern.compile("(?i)\\b[\\w]++-ci\\b"),
          Pattern.compile("\\b(?:Dockerfile|Containerfile|Makefile|Jenkinsfile|README)\\b"));

  /**
   * The description as a clause's subject. Followed by a claim verb ({@link #claimsFirst}) it
   * reports a misclaim, not an omission: "the description says the Dockerfile does not pin its base
   * image". "Says nothing about" and "states no …" are omissions still.
   */
  private static final Pattern DESCRIPTION_SUBJECT =
      Pattern.compile("(?i)\\b(?:description|pr\\s++body)(?:['’]s)?\\s++");

  /** The claim verbs after {@link #DESCRIPTION_SUBJECT}, a word between allowed, one per shape. */
  private static final List<Pattern> CLAIM_VERB_NEXT =
      List.of(
          Pattern.compile("(?i)^(?:\\w++\\s++)?(?:says|said|reads|asserts)\\b"),
          Pattern.compile("(?i)^(?:\\w++\\s++)?(?:states?|stated|claim(?:s|ed)?|promise[sd]?)\\b"));

  /** A claim verb whose object is nothing: "says nothing about", "states no …". */
  private static final Pattern CLAIMS_NOTHING =
      Pattern.compile("(?i)^(?:\\w++\\s++)?\\w++\\s++(?:nothing|no)\\b");

  /**
   * The description said to leave something out, in one clause: "the description never mentions",
   * "the description's summary covers X but not". A comma ends the clause, so "what the description
   * says, and it does not" is not an omission.
   */
  private static final List<Pattern> DESCRIPTION_OMITS =
      List.of(
          Pattern.compile(
              "(?i)\\b(?:description|pr\\s++body)\\b[^.;,\n]{0,80}?"
                  + "\\b(?:not|never|omit\\w*|lacks?|nothing|without|doesn['’]t)\\b"),
          Pattern.compile(
              "(?i)\\b(?:description|pr\\s++body)\\b[^.;,\n]{0,80}?"
                  + "\\b(?:fails?\\s++to|leaves?\\s++out|is\\s++missing|misses|skips)\\b"));

  /** What an omission bullet says the description lacks besides: "beyond", "also adds". */
  private static final List<Pattern> OMISSION_CUES =
      List.of(
          Pattern.compile("(?i)\\b(?:beyond|besides)\\b"),
          Pattern.compile("(?i)\\balso\\s++adds\\b"));

  /** Content words a PR-text sentence needs before a finding can paraphrase it. */
  static final int MIN_PARAPHRASED_WORDS = 5;

  /** The share of a PR-text sentence's content words a paraphrase repeats, at least. */
  static final double PARAPHRASE_SHARE = 0.75;

  /** Where the PR text splits into clauses: sentence marks, semicolons, colons, list items. */
  private static final Pattern SENTENCE_BREAK = Pattern.compile("[.!?;:](?=\\s)|\n");

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
            .filter(gap -> !falseOmission(gap, text))
            .toList();
    return kept.size() == gaps.size() ? gaps : kept;
  }

  /**
   * Whether {@code gap} says the description leaves out files or components that, most of them,
   * {@code prText} names after all (#1074: python #244 listed "the python-ci workflow, Dockerfile,
   * config docs" as missing from a description naming all three). Only an omission bullet is
   * judged: one saying in a clause that the description does not hold something, or naming it with
   * "beyond"/"also adds", and naming at least one file; a bullet reporting what the description
   * says ("the description states the Dockerfile does not…") is a misclaim and is never judged
   * here. A name counts as in the text when the text holds its last path segment as a whole word,
   * ignoring case.
   */
  static boolean falseOmission(String gap, String prText) {
    if (prText.isBlank()
        || !((anyFinds(DESCRIPTION_OMITS, gap) && !claimsFirst(gap))
            || (anyFinds(PR_TEXT_NAMED, gap) && anyFinds(OMISSION_CUES, gap)))) {
      return false;
    }
    var names = new LinkedHashSet<String>();
    for (var pattern : NAMED_FILES) {
      var m = pattern.matcher(gap);
      while (m.find()) {
        // A sentence's full stop is not part of the path it ends on.
        names.add(TRAILING_DOTS.matcher(m.group()).replaceAll("").toLowerCase(Locale.ROOT));
      }
    }
    // One file matched by two shapes ("README" in "README.md") is one name.
    var distinct =
        names.stream()
            .filter(name -> names.stream().noneMatch(other -> isPartOf(name, other)))
            .toList();
    if (distinct.isEmpty()) {
      return false;
    }
    var text = prText.toLowerCase(Locale.ROOT);
    var named = distinct.stream().filter(name -> holdsToken(text, lastSegment(name))).count();
    return named * 2 > distinct.size();
  }

  /**
   * Whether {@code text} holds {@code token} as a whole word: "build" is not in "rebuilds", nor
   * "rate" in "accurate".
   */
  private static boolean holdsToken(String text, String token) {
    return Pattern.compile("(?<![\\w-])" + Pattern.quote(token) + "(?![\\w-])")
        .matcher(text)
        .find();
  }

  /** Whether {@code gap} reports what the description claims ({@link #DESCRIPTION_SUBJECT}). */
  private static boolean claimsFirst(String gap) {
    var subject = DESCRIPTION_SUBJECT.matcher(gap);
    while (subject.find()) {
      var next = gap.substring(subject.end());
      if (anyFinds(CLAIM_VERB_NEXT, next) && !CLAIMS_NOTHING.matcher(next).find()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPartOf(String name, String other) {
    return !other.equals(name) && other.contains(name);
  }

  private static String lastSegment(String name) {
    var trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    return trimmed.substring(trimmed.lastIndexOf('/') + 1);
  }

  /**
   * Whether {@code findingText} quotes or paraphrases {@code claimText}, the PR text and linked
   * issue (#1074): a quoted passage {@link #misquotes} would judge appears in it, or the finding
   * repeats at least {@value #PARAPHRASE_SHARE} of the content words of one of its sentences of
   * {@value #MIN_PARAPHRASED_WORDS} or more.
   */
  static boolean echoes(String findingText, String claimText) {
    var source = normalize(claimText);
    var quotes = QUOTED.matcher(foldQuotes(findingText));
    while (quotes.find()) {
      var quote = quotes.group(quotes.group(1) != null ? 1 : 2);
      if (judged(quote, source) && present(quote, source)) {
        return true;
      }
    }
    var words = SummarySurfaceDeduplicator.claim(findingText).wordSet();
    return Arrays.stream(SENTENCE_BREAK.split(claimText))
        .map(sentence -> SummarySurfaceDeduplicator.claim(sentence).wordSet())
        .filter(sentence -> sentence.size() >= MIN_PARAPHRASED_WORDS)
        .anyMatch(
            sentence ->
                sentence.stream().filter(words::contains).count()
                    >= PARAPHRASE_SHARE * sentence.size());
  }

  /**
   * Whether {@code gap} says in its own words that it is not a mismatch: "(disclosed, not
   * contradicted)", "which matches the diff — no gap beyond the noted scope". Such a bullet listed
   * under the section's "does not fully match" intro contradicts itself. Quoted passages are the PR
   * text's words, not the bullet's. A {@code Linked issue #N:} gap reports an unmet criterion of
   * the issue and is never one, even when it adds that "the change matches the description".
   */
  static boolean concedesNoGap(String gap) {
    if (DescriptionGapCarryover.linkedIssueGap(gap) != null) {
      return false;
    }
    var own = QUOTED.matcher(foldQuotes(gap)).replaceAll(" ");
    return anyFinds(CONCESSIONS, own);
  }

  /**
   * A bullet's own concession that there is no mismatch, one pattern per shape: "no gap", "not
   * contradicted", "matches the description", "disclosed".
   */
  private static final List<Pattern> CONCESSIONS =
      List.of(
          Pattern.compile("(?i)\\bno\\s++(?:gap|mismatch|contradiction|discrepancy)\\b"),
          Pattern.compile("(?i)\\bnot\\s++(?:contradicted|a\\s++(?:gap|mismatch))\\b"),
          Pattern.compile(
              "(?i)\\b(?:matches|matched|consistent\\s++with)\\s++the\\s++(?:description|diff)\\b"),
          Pattern.compile("(?i)\\bdisclosed\\b"));

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
      if ((attributedBefore(lead) || attributedAfter(text.substring(quotes.end())))
          && judged(quote, normalizedSource)
          && !present(quote, normalizedSource)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code gap} says the description lacks a quoted passage that {@code normalizedSource}
   * now holds: "the description never mentions '…'" over a description that does mention it. On a
   * head the previous round reviewed this releases a carried omission gap once the author has
   * edited the description to cover it (#1064).
   */
  static boolean omissionCovered(String gap, String normalizedSource) {
    var text = foldQuotes(gap);
    var quotes = QUOTED.matcher(text);
    var since = 0;
    while (quotes.find()) {
      var lead = clauseOf(text.substring(since, quotes.start()));
      since = quotes.end();
      var quote = quotes.group(quotes.group(1) != null ? 1 : 2);
      if (anyFinds(PR_TEXT_NAMED, lead)
          && negated(lead)
          && judged(quote, normalizedSource)
          && present(quote, normalizedSource)) {
        return true;
      }
    }
    return false;
  }

  /** Whether the clause before a quote attributes it to the pull request's text. */
  private static boolean attributedBefore(String lead) {
    return anyFinds(CLAIM_CUES, lead) && !negated(lead);
  }

  /**
   * Whether the clause after a quote attributes it to the pull request's text: "'…' is what the
   * description promises". A negation there keeps the quote on the code's side ("'…' is what the
   * code does, not what the description says" ends its clause at the comma anyway).
   */
  private static boolean attributedAfter(String rest) {
    var end = CLAUSE_BREAK.matcher(rest);
    var tail = end.find() ? rest.substring(0, end.start()) : rest;
    return WHAT_FOLLOWS_QUOTE.matcher(tail).find()
        && anyFinds(CLAIM_CUES, tail)
        && !anyFinds(NEGATED_CUES, tail);
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

  /**
   * Whether a quote can be judged: it has enough words, or it is one token that runs words together
   * ({@link #runTogether}).
   */
  private static boolean judged(String quote, String normalizedSource) {
    var joined = joinEditorialLetters(quote);
    var words = normalize(joined);
    return words.split(" ").length >= MIN_QUOTE_WORDS
        || CAMEL_HUMP.matcher(joined).find()
        || runTogether(words, normalizedSource);
  }

  /**
   * Whether the single token {@code word} starts or ends with a word of {@code normalizedSource},
   * both that word and what is left at least {@value #MIN_RUN_PART} letters: "retriesforever"
   * against a description reading "retries".
   */
  static boolean runTogether(String word, String normalizedSource) {
    if (word.length() < 2 * MIN_RUN_PART) {
      return false;
    }
    return Arrays.stream(normalizedSource.split(" "))
        .filter(part -> part.length() >= MIN_RUN_PART)
        .filter(part -> word.length() - part.length() >= MIN_RUN_PART)
        .anyMatch(part -> word.startsWith(part) || word.endsWith(part));
  }

  /**
   * Whether a judged quote is in {@code normalizedSource}: on word boundaries ({@link #appearsIn}),
   * or, for a quote of one token, with the separators removed from both sides, so "retryForever"
   * matches a description reading "retry forever" or {@code retry_forever}.
   */
  private static boolean present(String quote, String normalizedSource) {
    var words = normalize(joinEditorialLetters(quote));
    var runTogetherSource = normalizedSource.replace(" ", "");
    if (words.indexOf(' ') < 0) {
      return runTogetherSource.contains(words);
    }
    // A quote that spaces out what the PR text runs together ('retry forever' for retryForever)
    // is the same words, so it is kept.
    return appearsIn(quote, normalizedSource) || runTogetherSource.contains(words.replace(" ", ""));
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

  /** Lowercase letters and digits; possessive endings fold into the word they belong to. */
  static String normalize(String text) {
    return NON_ALPHANUMERIC
        .matcher(POSSESSIVE.matcher(text).replaceAll("").toLowerCase(Locale.ROOT))
        .replaceAll(" ")
        .strip();
  }
}
