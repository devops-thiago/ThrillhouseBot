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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds what a finding or a Description vs. Implementation bullet says about the linked issue to
 * the issue the review actually read (#1073). Three deterministic rules, applied only when the
 * linked-issue context was resolved for the review; with no linked issue nothing changes:
 *
 * <ul>
 *   <li><b>The issue is named.</b> With exactly one issue linked, "the linked issue" in a finding
 *       or a bullet reads "the linked issue #N". With two or more the phrase is ambiguous and
 *       stays.
 *   <li><b>A cited criterion must be in the issue.</b> A passage quoted as the linked issue's, or
 *       an acceptance criterion named by its subject ("the acceptance criterion for declined
 *       handling"), is checked against the issue text, case-, whitespace- and punctuation-blind as
 *       {@link DescriptionGapScreen} checks the PR description (#946). An explicit issue number
 *       scopes the check to that issue's body, or the named bodies for a plural attribution
 *       (#1109). An unnamed attribution reads the full linked-issue section. A citation that
 *       matches nothing is taken out of the text: its clause when the sentence makes another claim,
 *       else the sentence. A finding whose title or description is then empty is dropped, since the
 *       criterion was its only claim; so is a bullet. A {@code Linked issue #N: <criterion>} bullet
 *       is shortened by design, so its criterion is matched on content words instead.
 *   <li><b>A finding reporting an unmet criterion is a threaded finding.</b> One that cites a
 *       criterion the issue holds, other than to say the code matches it, is raised to at least
 *       medium risk and medium confidence, so it is never only a "Things to double-check" item. The
 *       verifier is not given the issue, so a criterion it could not see is established evidence
 *       here, as a failing CI check is for {@code floorEstablishedConfidence} (#926).
 * </ul>
 *
 * <p>The patterns are one per shape, each well under the regex complexity bound.
 */
final class LinkedIssueCitations {

  private LinkedIssueCitations() {}

  /** Content words a named criterion needs before it is judged. */
  static final int MIN_NAMED_WORDS = 2;

  /** Characters between a cue and the quote it introduces, at most. */
  static final int MAX_CUE_TO_QUOTE = 60;

  /** "The linked issue", singular, not already followed by an issue number. */
  private static final Pattern THE_LINKED_ISSUE =
      Pattern.compile("(?i)\\blinked\\s++issue\\b(?!\\s*+\\(?#\\d)");

  /**
   * The linked issue or issues named as a source: what a quote after it is attributed to. The
   * plural is a citation too ("the linked issues state '…'"); it is only never renamed.
   */
  private static final Pattern LINKED_ISSUE = Pattern.compile("(?i)\\blinked\\s++issues?+\\b");

  /** An acceptance criterion or an issue's criterion named: "acceptance criterion", … */
  private static final Pattern ACCEPTANCE_CRITERION =
      Pattern.compile("(?i)\\bacceptance\\s++criteri(?:on|a)\\b");

  /** …"issue criterion", "issue's criteria". */
  private static final Pattern ISSUE_CRITERION =
      Pattern.compile("(?i)\\bissue(?:['’]s)?\\s++criteri(?:on|a)\\b");

  /** An issue named by number: "issue #227", "issues #228 and #231" (the first number). */
  private static final Pattern ISSUE_NUMBER = Pattern.compile("(?i)\\bissues?+\\s*+(#\\d++)\\b");

  /** The cues that name a criterion by its noun, after which unquoted words name its subject. */
  private static final List<Pattern> CRITERION_NOUNS =
      List.of(ACCEPTANCE_CRITERION, ISSUE_CRITERION);

  /** Every cue but an issue number, which only counts for a linked issue. */
  private static final List<Pattern> CUES =
      List.of(LINKED_ISSUE, ACCEPTANCE_CRITERION, ISSUE_CRITERION);

  /**
   * A quoted passage, as {@link DescriptionGapScreen} reads one: double quotes, or single quotes
   * that are not apostrophes. Curly quotes are folded first.
   */
  private static final Pattern QUOTED =
      Pattern.compile("\"([^\"\n]++)\"|(?<![\\p{L}\\p{N}])'([^\n]+?)'(?![\\p{L}\\p{N}])");

  /** An eligible single-quote opener, including a quote closed only by a truncated region end. */
  private static final Pattern SINGLE_QUOTE_OPEN = Pattern.compile("(?<![\\p{L}\\p{N}])'");

  private static final Pattern SINGLE_QUOTE_CLOSE = Pattern.compile("'(?![\\p{L}\\p{N}])");
  private static final Pattern DOUBLE_QUOTE = Pattern.compile("\"");

  private static final Pattern LINE_BREAK = Pattern.compile("\n");

  /** What ends a sentence: a full stop, semicolon or mark before a blank or the end, or a break. */
  private static final Pattern SENTENCE_END = Pattern.compile("[.;!?](?=\\s|$)|\n");

  /**
   * What ends the clause naming a criterion's subject. A parenthesis or a colon does not: "the
   * criterion for duplicates (same meter serial and timestamp)" and "the criterion for the future
   * reason: readings …" go on naming it.
   */
  private static final Pattern CLAUSE_END =
      Pattern.compile("(?i)[,;!?\u2014\u2013]|\\.(?=\\s|$)|\\b(?:but|while|whereas|so)\\b|\n");

  /**
   * A comma, conjunction or subordinator that joins a citation to an earlier claim in its sentence:
   * "…twice per period because the linked issue's '…' was never implemented".
   */
  private static final Pattern JOINER =
      Pattern.compile(
          "(?i),\\s|\\s(?:and|or|because|since|as|although|though|while|whereas|despite)\\s");

  /** What separates clauses before a cue, for {@link #AFFIRMS}. */
  private static final Pattern CLAUSE_BREAK = Pattern.compile("[,;:\u2014\u2013]");

  /**
   * A clause around a cue saying the change agrees with the criterion, one pattern per shape: the
   * verbs in any form ("matches", "satisfying", "met"), then the phrases ("consistent with").
   */
  private static final List<Pattern> AFFIRMS =
      List.of(
          Pattern.compile("(?i)\\b(?:match|implement)(?:e?s|ed|ing)?\\b"),
          Pattern.compile("(?i)\\b(?:satisf(?:y|ies|ied|ying)|meets?|meeting|met)\\b"),
          Pattern.compile("(?i)\\b(?:consistent|agrees?|agreeing|in\\s++line)\\s++with\\b"));

  /**
   * Words of a named criterion that say it is a criterion, not what it is about, as {@link
   * SummarySurfaceDeduplicator#claim} stems them ("requires" and "require" both read "requir").
   */
  private static final Set<String> CITATION_WORDS =
      Set.of(
          "acceptanc",
          "criterion",
          "criteria",
          "link",
          "issu",
          "requir",
          "requirement",
          "say",
          "sai",
          "stat",
          "ask",
          "expect");

  /** A clause opening as a relative one about the code: "which the linked issue's '…' requires". */
  private static final Pattern RELATIVE = Pattern.compile("(?i)^\\s*+(?:which|as)\\b");

  /** What says the code goes against a criterion: violating, contradicting, breaking it… */
  private static final List<Pattern> VIOLATIONS =
      List.of(
          Pattern.compile("(?i)\\b(?:violat|contradict|break|invert|ignor|omit|fail)\\w*"),
          Pattern.compile("(?i)\\b(?:not|never|missing|opposite|instead|without|lacks?)\\b"),
          Pattern.compile("(?i)\\b(?:nothing|nowhere|unimplemented|unmet|undone)\\b"),
          Pattern.compile("(?i)n['’]t\\b"));

  /** One citation found in a text: where it runs and what the issue makes of it. */
  record Citation(int start, int end, Verdict verdict) {}

  /** Whether a citation holds against the issue, or could not be judged. */
  enum Verdict {
    MATCHED,
    UNMATCHED,
    UNJUDGED
  }

  private record Source(String normalized, Set<String> words) {
    static Source of(String text) {
      return new Source(
          DescriptionGapScreen.normalize(text), SummarySurfaceDeduplicator.claim(text).wordSet());
    }
  }

  /** A numbered attribution reads only that issue; an unnamed one reads the whole section. */
  private record Sources(Source all, Map<String, Source> numbered) {
    static Sources of(String linkedIssues, List<String> keys) {
      var numbered = new LinkedHashMap<String, Source>();
      for (var key : keys) {
        numbered.put(key, Source.of(DescriptionGapCarryover.issueSection(key, linkedIssues)));
      }
      return new Sources(Source.of(linkedIssues), numbered);
    }

    Source forKeys(Set<String> keys) {
      var normalized = new StringBuilder();
      var words = new LinkedHashSet<String>();
      for (var key : keys) {
        var source = numbered.get(key);
        if (source != null) {
          normalized.append(source.normalized()).append(' ');
          words.addAll(source.words());
        }
      }
      return new Source(normalized.toString(), words);
    }
  }

  /**
   * {@code response} with every finding held to {@code linkedIssues}, the unfenced linked-issue
   * section; returned as it is when no issue is linked or nothing changes.
   */
  static ReviewResponse apply(ReviewResponse response, String linkedIssues) {
    var keys = TicketContextResolver.linkedKeys(linkedIssues);
    if (keys.isEmpty() || response.findings().isEmpty()) {
      return response;
    }
    var sources = Sources.of(linkedIssues, keys);
    var kept = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (var finding : response.findings()) {
      var held = held(finding, keys, sources);
      changed |= held != finding;
      if (held != null) {
        kept.add(held);
      }
    }
    return changed
        ? new ReviewResponse(
            kept,
            response.previousFindingsStatus(),
            response.summary(),
            response.closedEarlierRound())
        : response;
  }

  /** {@code finding} held to the issue; {@code null} when its only claim was a criterion. */
  private static ReviewResponse.Finding held(
      ReviewResponse.Finding finding, List<String> keys, Sources sources) {
    var title = nullToEmpty(finding.title());
    var description = nullToEmpty(finding.description());
    var titleCitations = citations(title, keys, sources);
    var descriptionCitations = citations(description, keys, sources);
    var newTitle = withoutUnmatched(title, keys, sources);
    var newDescription = withoutUnmatched(description, keys, sources);
    if (emptied(title, newTitle) || emptied(description, newDescription)) {
      Log.infof(
          "Dropped finding '%s': the acceptance criterion it rests on is not in the linked issue",
          LogSafe.oneLine(finding.title()));
      return null;
    }
    var reportsCriterion =
        reportsUnmetCriterion(title, titleCitations)
            || reportsUnmetCriterion(description, descriptionCitations);
    var risk = reportsCriterion ? atLeastMedium(finding.risk()) : finding.risk();
    var confidence = reportsCriterion ? atLeastMedium(finding.confidence()) : finding.confidence();
    newTitle = named(newTitle, keys);
    newDescription = named(newDescription, keys);
    if (newTitle.equals(title)
        && newDescription.equals(description)
        && Objects.equals(risk, finding.risk())
        && Objects.equals(confidence, finding.confidence())) {
      return finding;
    }
    if (reportsCriterion) {
      Log.infof(
          "Finding '%s' reports an unmet acceptance criterion of the linked issue: %s risk, %s"
              + " confidence",
          LogSafe.oneLine(newTitle), LogSafe.oneLine(risk), LogSafe.oneLine(confidence));
    }
    return new ReviewResponse.Finding(
        risk,
        confidence,
        finding.file(),
        finding.line(),
        restored(finding.title(), newTitle),
        restored(finding.description(), newDescription),
        finding.suggestionOld(),
        finding.suggestionNew());
  }

  /** Whether a cut left {@code held} empty where {@code text} said something. */
  private static boolean emptied(String text, String held) {
    return !text.isBlank() && held.isBlank();
  }

  /** {@code held}, or {@code original} itself ({@code null} included) when nothing changed. */
  private static String restored(String original, String held) {
    return held.equals(nullToEmpty(original)) ? original : held;
  }

  /**
   * {@code gaps} held to {@code linkedIssues}: a bullet citing a criterion the issue does not hold
   * loses that citation, or is dropped when it was all the bullet said, and "the linked issue"
   * names its number when one issue is linked. As it is with no issue linked.
   */
  static List<String> screenGaps(List<String> gaps, String linkedIssues) {
    var keys = TicketContextResolver.linkedKeys(linkedIssues);
    if (keys.isEmpty() || gaps.isEmpty()) {
      return gaps;
    }
    var sources = Sources.of(linkedIssues, keys);
    var kept = new ArrayList<String>(gaps.size());
    for (var gap : gaps) {
      var held = heldGap(gap, keys, linkedIssues, sources);
      if (held != null) {
        kept.add(held);
      } else {
        Log.infof(
            "Dropped a description gap citing an acceptance criterion the linked issue does not"
                + " hold: %s",
            LogSafe.oneLine(gap));
      }
    }
    return kept.equals(gaps) ? gaps : kept;
  }

  private static String heldGap(
      String gap, List<String> keys, String linkedIssues, Sources sources) {
    var linked = DescriptionGapCarryover.linkedIssueGap(gap);
    if (linked != null) {
      return criterionHeld(linked, keys, linkedIssues) ? gap : null;
    }
    var held = withoutUnmatched(gap, keys, sources);
    return held.isBlank() ? null : named(held, keys);
  }

  /**
   * Whether the criterion of a {@code Linked issue #N: <criterion> — <evidence>} bullet is in the
   * issue it names, or in the whole linked-issue section when it names no issue the pull request
   * links (a number the model echoed, which {@link DescriptionGapCarryover#withLinkedIssueNumbers}
   * rewrites only on fresh bullets): a quoted passage in it must appear there, and otherwise at
   * least half of its content words must, since the summary call is told to shorten the criterion.
   * A criterion of too few words to judge is kept.
   */
  private static boolean criterionHeld(Matcher linked, List<String> keys, String linkedIssues) {
    var named = DescriptionGapCarryover.issuesNamed(linked.group(1));
    var sections = new StringBuilder();
    for (var key : named) {
      if (keys.contains(key)) {
        sections.append(DescriptionGapCarryover.issueSection(key, linkedIssues)).append('\n');
      }
    }
    var issue = sections.isEmpty() ? linkedIssues : sections.toString();
    var criterion = DescriptionGapCarryover.criterionOf(linked.group(2));
    var quote = QUOTED.matcher(foldQuotes(criterion));
    if (quote.find()) {
      return DescriptionGapScreen.appearsIn(quoted(quote), DescriptionGapScreen.normalize(issue));
    }
    return judgeWords(criterion, SummarySurfaceDeduplicator.claim(issue).wordSet())
        != Verdict.UNMATCHED;
  }

  /**
   * Every criterion {@code text} cites, in order: a quote after a cue naming the linked issue or a
   * criterion, or, after a criterion noun, the words naming its subject. Cues inside a citation
   * already found are part of it.
   */
  private static List<Citation> citations(String text, List<String> keys, Sources sources) {
    return new CitationScan(foldQuotes(text), keys, sources).all();
  }

  /** Quote-folded text, with a CharSequence seam for checking the amount of parser input read. */
  static List<Citation> citationsInFoldedText(CharSequence text, String linkedIssues) {
    var keys = TicketContextResolver.linkedKeys(linkedIssues);
    return new CitationScan(text, keys, Sources.of(linkedIssues, keys)).all();
  }

  /**
   * One left-to-right pass. A missing cue or quote is searched for once, not again for every
   * intervening citation; sentence and source boundaries advance with the citations too. A new pass
   * after a cut deliberately reads the changed text, since its attribution may have changed.
   */
  private static final class CitationScan {
    private final CharSequence text;
    private final Sources sources;
    private final List<CueSearch> cues = new ArrayList<>();
    private final QuotePositions quotes;
    private final Search sentences;
    private final Search precedingSentences;
    private final Search numbers;
    private int previousSentenceEnd;
    private int previousNumberStart = -1;

    CitationScan(CharSequence text, List<String> keys, Sources sources) {
      this.text = text;
      this.sources = sources;
      for (var pattern : CUES) {
        cues.add(new CueSearch(pattern, CRITERION_NOUNS.contains(pattern), keys));
      }
      cues.add(new CueSearch(ISSUE_NUMBER, false, keys));
      quotes = new QuotePositions(text);
      sentences = new Search(SENTENCE_END, text);
      precedingSentences = new Search(SENTENCE_END, text);
      numbers = new Search(ISSUE_NUMBER, text);
    }

    List<Citation> all() {
      var found = new ArrayList<Citation>();
      var from = 0;
      while (true) {
        var cue = nextCue(from);
        if (cue == null) {
          return found;
        }
        var citation = citationAt(cue);
        found.add(citation);
        from = Math.max(citation.end(), cue.end());
      }
    }

    Citation firstUnmatched() {
      var from = 0;
      while (true) {
        var cue = nextCue(from);
        if (cue == null) {
          return null;
        }
        var citation = citationAt(cue);
        if (citation.verdict() == Verdict.UNMATCHED) {
          return citation;
        }
        from = Math.max(citation.end(), cue.end());
      }
    }

    private Cue nextCue(int from) {
      Cue earliest = null;
      for (var search : cues) {
        var cue = search.atOrAfter(from);
        if (cue != null && (earliest == null || cue.start() < earliest.start())) {
          earliest = cue;
        }
      }
      return earliest;
    }

    private final class CueSearch {
      private final Search search;
      private final boolean noun;
      private final List<String> keys;
      private final boolean numbered;

      CueSearch(Pattern pattern, boolean noun, List<String> keys) {
        search = new Search(pattern, text);
        this.noun = noun;
        this.keys = keys;
        numbered = pattern == ISSUE_NUMBER;
      }

      Cue atOrAfter(int from) {
        var found = search.atOrAfter(from);
        // Only an issue the pull request links is a citation of it.
        while (found && numbered && !keys.contains(search.matcher.group(1))) {
          found = search.advance();
        }
        return found ? new Cue(search.matcher.start(), search.matcher.end(), noun) : null;
      }
    }

    private Citation citationAt(Cue cue) {
      var start = cue.start();
      var quote = quotes.atOrAfter(cue.end());
      var after = cue.end();
      var sentenceEnd = sentenceEnd(after);
      if (quote != null
          && quote.start() - after <= MAX_CUE_TO_QUOTE
          && quote.start() < sentenceEnd) {
        var verdict =
            DescriptionGapScreen.appearsIn(
                    text.subSequence(quote.start() + 1, quote.end() - 1).toString(),
                    sourceFor(cue, quote.start()).normalized())
                ? Verdict.MATCHED
                : Verdict.UNMATCHED;
        return new Citation(start, quote.end(), verdict);
      }
      if (!cue.noun()) {
        return new Citation(start, after, Verdict.UNJUDGED);
      }
      var clause = CLAUSE_END.matcher(text);
      var end = clause.find(after) ? clause.start() : text.length();
      return new Citation(
          start,
          end,
          judgeWords(text.subSequence(after, end).toString(), sourceFor(cue, end).words()));
    }

    /** The nearest numbered attribution before this citation, outside an earlier quoted passage. */
    private Source sourceFor(Cue cue, int end) {
      var from = Math.max(sentenceStart(cue.start()), quotes.endBefore(cue.start()));
      while (numbers.found && numbers.matcher.end() <= end) {
        previousNumberStart = numbers.matcher.start();
        numbers.advance();
      }
      return previousNumberStart < from
          ? sources.all()
          : sources.forKeys(
              DescriptionGapCarryover.issuesNamed(
                  text.subSequence(previousNumberStart, end).toString()));
    }

    private int sentenceStart(int at) {
      while (precedingSentences.found && precedingSentences.matcher.end() <= at) {
        previousSentenceEnd = precedingSentences.matcher.end();
        precedingSentences.advance();
      }
      var from = previousSentenceEnd;
      if (at > 0 && ".;!?".indexOf(text.charAt(at - 1)) >= 0) {
        from = at;
      }
      while (from < at && Character.isWhitespace(text.charAt(from))) {
        from++;
      }
      return from;
    }

    private int sentenceEnd(int from) {
      while (sentences.found && sentences.matcher.start() < from) {
        sentences.advance();
      }
      return sentences.found ? sentences.matcher.start() : text.length();
    }

    /**
     * Quote matching by positions, preserving QUOTED's nonempty bodies, Unicode guards and newline
     * boundary. Incomplete openers do not repeatedly search the same tail. Prefix queries activate
     * complete matches by end position; an earlier newly completed quote absorbs the matches inside
     * it, just as a fresh matcher over that longer prefix would. Each position is added/removed
     * once.
     */
    private static final class QuotePositions {
      private record Quote(int start, int end) {}

      private final CharSequence text;
      private final TreeMap<Integer, Quote> byStart = new TreeMap<>();
      private final List<Quote> byEnd;
      private final TreeMap<Integer, Quote> prefix = new TreeMap<>();
      private final TreeSet<Integer> unusedSingleOpeners;
      private final TreeSet<Integer> newlines;
      private int activated;

      QuotePositions(CharSequence text) {
        this.text = text;
        var doubles = positions(DOUBLE_QUOTE, text);
        unusedSingleOpeners = positions(SINGLE_QUOTE_OPEN, text);
        var singleClosers = positions(SINGLE_QUOTE_CLOSE, text);
        newlines = positions(LINE_BREAK, text);
        for (var start : doubles) {
          add(start, doubles.higher(start));
        }
        for (var start : unusedSingleOpeners) {
          add(start, singleClosers.ceiling(start + 2));
        }
        byEnd = new ArrayList<>(byStart.values());
        byEnd.sort(Comparator.comparingInt(Quote::end).thenComparingInt(Quote::start));
      }

      private void add(int start, Integer closing) {
        var newline = newlines.higher(start);
        if (closing != null && closing > start + 1 && (newline == null || closing < newline)) {
          byStart.put(start, new Quote(start, closing + 1));
        }
      }

      Quote atOrAfter(int from) {
        var entry = byStart.ceilingEntry(from);
        return entry == null ? null : entry.getValue();
      }

      int endBefore(int at) {
        while (activated < byEnd.size() && byEnd.get(activated).end() <= at) {
          var quote = byEnd.get(activated++);
          var enclosing = prefix.floorEntry(quote.start());
          if (enclosing == null || enclosing.getValue().end() <= quote.start()) {
            prefix.tailMap(quote.start()).clear();
            prefix.put(quote.start(), quote);
            unusedSingleOpeners.subSet(quote.start(), true, quote.end(), false).clear();
          }
        }
        // A single quote at the prefix's end has no following letter in that region. This closure
        // is local: extending the prefix can make it an apostrophe again. An unconsumed opener may
        // precede a completed inner quote, which this temporary outer quote would absorb.
        if (at > 0 && text.charAt(at - 1) == '\'') {
          var newline = newlines.lower(at);
          var opener = unusedSingleOpeners.ceiling(newline == null ? 0 : newline + 1);
          if (opener != null && opener + 1 < at - 1) {
            return at;
          }
        }
        return prefix.isEmpty() ? 0 : prefix.lastEntry().getValue().end();
      }

      private static TreeSet<Integer> positions(Pattern pattern, CharSequence text) {
        var found = new TreeSet<Integer>();
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
          found.add(matcher.start());
        }
        return found;
      }
    }

    /** A cue naming an issue, or a criterion noun followed by words naming its subject. */
    private record Cue(int start, int end, boolean noun) {}

    /** A monotonically searched pattern, including a cached exhausted suffix. */
    private static final class Search {
      private final Matcher matcher;
      private boolean found;

      Search(Pattern pattern, CharSequence text) {
        matcher = pattern.matcher(text);
        found = matcher.find();
      }

      boolean atOrAfter(int from) {
        if (found && matcher.start() < from) {
          found = matcher.find(from);
        }
        return found;
      }

      boolean advance() {
        found = matcher.find();
        return found;
      }
    }
  }

  /** Whether the content words naming a criterion are, at least half of them, the issue's. */
  private static Verdict judgeWords(String words, Set<String> issueWords) {
    var content =
        SummarySurfaceDeduplicator.claim(words).wordSet().stream()
            .filter(word -> !CITATION_WORDS.contains(word))
            .toList();
    if (content.size() < MIN_NAMED_WORDS) {
      return Verdict.UNJUDGED;
    }
    var shared = content.stream().filter(issueWords::contains).count();
    return shared * 2 >= content.size() ? Verdict.MATCHED : Verdict.UNMATCHED;
  }

  /**
   * {@code text} without its unmatched citations: the clause from the joiner before a citation to
   * its end when the sentence says something before it, otherwise the whole sentence. One cut at a
   * time, each on the citations of the text as the previous cut left it: a sentence cut moves or
   * removes the citations before it, so offsets taken on the original text would not hold. Every
   * cut shortens the text, so the loop ends.
   */
  private static String withoutUnmatched(String text, List<String> keys, Sources sources) {
    var out = text;
    var unmatched = firstUnmatched(out, keys, sources);
    while (unmatched != null) {
      out = cut(out, unmatched);
      unmatched = firstUnmatched(out, keys, sources);
    }
    return out;
  }

  private static Citation firstUnmatched(String text, List<String> keys, Sources sources) {
    return new CitationScan(foldQuotes(text), keys, sources).firstUnmatched();
  }

  private static String cut(String text, Citation citation) {
    var sentenceStart = sentenceStart(text, citation.start());
    var joiner = lastJoiner(text, sentenceStart, citation.start());
    if (joiner >= 0) {
      // The citation's clause goes with it: "…'refunds are instant' criterion was never met".
      var clause = CLAUSE_END.matcher(text);
      var end = clause.find(citation.end()) ? clause.start() : text.length();
      return text.substring(0, joiner) + text.substring(end);
    }
    var end = sentenceEnd(text, citation.end());
    var next = end < text.length() ? end + 1 : end;
    return (text.substring(0, sentenceStart) + text.substring(next).stripLeading()).strip();
  }

  /** Where the last joiner between {@code from} and {@code to} starts; -1 without one. */
  private static int lastJoiner(String text, int from, int to) {
    var region = text.substring(from, to);
    // A joiner inside a quote is part of the quote, not of the sentence.
    var lastQuote = QUOTED.matcher(foldQuotes(region));
    var after = 0;
    while (lastQuote.find()) {
      after = lastQuote.end();
    }
    var joiner = JOINER.matcher(region);
    var at = -1;
    while (joiner.find(after)) {
      at = joiner.start();
      after = joiner.end();
    }
    return at < 0 ? -1 : from + at;
  }

  /** Where the sentence holding {@code at} starts. */
  private static int sentenceStart(String text, int at) {
    var ends = SENTENCE_END.matcher(text.substring(0, at));
    var start = 0;
    while (ends.find()) {
      start = ends.end();
    }
    // The blanks after the previous sentence's mark stay with it.
    while (start < at && Character.isWhitespace(text.charAt(start))) {
      start++;
    }
    return start;
  }

  /** Where the sentence running at {@code from} ends: its mark, or the text's end. */
  private static int sentenceEnd(String text, int from) {
    var end = SENTENCE_END.matcher(text);
    return end.find(from) ? end.start() : text.length();
  }

  /**
   * Whether {@code text} reports an unmet criterion: it cites one the issue holds, other than to
   * say the code meets it. The citation's clause is read on both sides of it, so "which matches the
   * linked issue's '…'" and "which the linked issue's '…' requires" are both the code meeting the
   * criterion, while "which violates the linked issue's '…'" is not.
   */
  private static boolean reportsUnmetCriterion(String text, List<Citation> citations) {
    for (var citation : citations) {
      if (citation.verdict() == Verdict.MATCHED && !affirmed(text, citation)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the clause holding {@code citation} says the code meets the criterion: an affirming
   * verb in any form, or a relative clause about the code, and no word saying the code goes against
   * it ("does not match", "fails to meet").
   */
  private static boolean affirmed(String text, Citation citation) {
    var sentenceLead = text.substring(sentenceStart(text, citation.start()), citation.start());
    var breaks = CLAUSE_BREAK.matcher(sentenceLead);
    var clauseStart = 0;
    while (breaks.find()) {
      clauseStart = breaks.end();
    }
    var lead = sentenceLead.substring(clauseStart);
    var rest = text.substring(citation.end(), sentenceEnd(text, citation.end()));
    var tailEnd = CLAUSE_BREAK.matcher(rest);
    var tail = tailEnd.find() ? rest.substring(0, tailEnd.start()) : rest;
    var relative = RELATIVE.matcher(lead).find();
    // A relative clause hangs off the clause before it, so a negation there is about the same code:
    // "the code does not expire listings, which the linked issue's '…' requires".
    var judged = (relative ? sentenceLead : lead) + " " + tail;
    return (relative || anyFinds(AFFIRMS, judged)) && !anyFinds(VIOLATIONS, judged);
  }

  private static boolean anyFinds(List<Pattern> patterns, String text) {
    return patterns.stream().anyMatch(pattern -> pattern.matcher(text).find());
  }

  /** {@code text} with "the linked issue" naming its number, when exactly one issue is linked. */
  static String named(String text, List<String> keys) {
    if (keys.size() != 1) {
      return text;
    }
    return THE_LINKED_ISSUE
        .matcher(text)
        .replaceAll("$0 " + Matcher.quoteReplacement(keys.getFirst()));
  }

  /** {@code level} raised to medium when it is low; as it is otherwise, {@code null} included. */
  private static String atLeastMedium(String level) {
    return "low".equalsIgnoreCase(nullToEmpty(level).strip()) ? "medium" : level;
  }

  private static String quoted(Matcher quote) {
    return quote.group(quote.group(1) != null ? 1 : 2);
  }

  private static String foldQuotes(String text) {
    return text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'');
  }

  private static String nullToEmpty(String text) {
    return text == null ? "" : text;
  }
}
