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
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 *       {@link DescriptionGapScreen} checks the PR description (#946). A citation that matches
 *       nothing is taken out of the text: its clause when the sentence makes another claim, else
 *       the sentence. A finding whose title or description is then empty is dropped, since the
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

  /** The linked issue named as a source: what a quote after it is attributed to. */
  private static final Pattern LINKED_ISSUE = Pattern.compile("(?i)\\blinked\\s++issue\\b(?!s)");

  /** An acceptance criterion or an issue's criterion named: "acceptance criterion", … */
  private static final Pattern ACCEPTANCE_CRITERION =
      Pattern.compile("(?i)\\bacceptance\\s++criteri(?:on|a)\\b");

  /** …"issue criterion", "issue's criteria". */
  private static final Pattern ISSUE_CRITERION =
      Pattern.compile("(?i)\\bissue(?:['’]s)?\\s++criteri(?:on|a)\\b");

  /** An issue named by number: "issue #227". */
  private static final Pattern ISSUE_NUMBER = Pattern.compile("(?i)\\bissue\\s*+(#\\d++)\\b");

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

  /** What ends a sentence: a full stop, semicolon or mark before a blank or the end, or a break. */
  private static final Pattern SENTENCE_END = Pattern.compile("[.;!?](?=\\s|$)|\n");

  /** What ends the clause naming a criterion's subject. */
  private static final Pattern CLAUSE_END =
      Pattern.compile("(?i)[,;:!?\u2014\u2013(]|\\.(?=\\s|$)|\\b(?:but|while|whereas|so)\\b|\n");

  /** A conjunction or comma that joins a citation to an earlier claim in its sentence. */
  private static final Pattern JOINER = Pattern.compile("(?i),\\s|\\s(?:and|or)\\s");

  /** What separates clauses before a cue, for {@link #AFFIRMS}. */
  private static final Pattern CLAUSE_BREAK = Pattern.compile("[,;:\u2014\u2013]");

  /** The clause before a cue saying the change agrees with the criterion. */
  private static final Pattern AFFIRMS =
      Pattern.compile(
          "(?i)\\b(?:match(?:es|ing)?|consistent\\s++with|agrees?\\s++with|in\\s++line\\s++with"
              + "|satisf(?:y|ies)|meets|implements)\\b");

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
          Pattern.compile("(?i)n['’]t\\b"));

  /** One citation found in a text: where it runs and what the issue makes of it. */
  record Citation(int start, int end, Verdict verdict) {}

  /** Whether a citation holds against the issue, or could not be judged. */
  enum Verdict {
    MATCHED,
    UNMATCHED,
    UNJUDGED
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
    var source = DescriptionGapScreen.normalize(linkedIssues);
    var issueWords = SummarySurfaceDeduplicator.claim(linkedIssues).wordSet();
    var kept = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (var finding : response.findings()) {
      var held = held(finding, keys, source, issueWords);
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
      ReviewResponse.Finding finding, List<String> keys, String source, Set<String> issueWords) {
    var title = nullToEmpty(finding.title());
    var description = nullToEmpty(finding.description());
    var titleCitations = citations(title, keys, source, issueWords);
    var descriptionCitations = citations(description, keys, source, issueWords);
    var newTitle = withoutUnmatched(title, keys, source, issueWords);
    var newDescription = withoutUnmatched(description, keys, source, issueWords);
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
    var source = DescriptionGapScreen.normalize(linkedIssues);
    var issueWords = SummarySurfaceDeduplicator.claim(linkedIssues).wordSet();
    var kept = new ArrayList<String>(gaps.size());
    for (var gap : gaps) {
      var held = heldGap(gap, keys, linkedIssues, source, issueWords);
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
      String gap, List<String> keys, String linkedIssues, String source, Set<String> issueWords) {
    var linked = DescriptionGapCarryover.linkedIssueGap(gap);
    if (linked != null) {
      return criterionHeld(linked, keys, linkedIssues) ? gap : null;
    }
    var held = withoutUnmatched(gap, keys, source, issueWords);
    return held.isBlank() ? null : named(held, keys);
  }

  /**
   * Whether the criterion of a {@code Linked issue #N: <criterion> — <evidence>} bullet is in the
   * issue it names: a quoted passage in it must appear there, and otherwise at least half of its
   * content words must, since the summary call is told to shorten the criterion. A criterion of too
   * few words to judge is kept.
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
  static List<Citation> citations(
      String text, List<String> keys, String source, Set<String> issueWords) {
    var folded = foldQuotes(text);
    var found = new ArrayList<Citation>();
    var from = 0;
    while (true) {
      var cue = nextCue(folded, from, keys);
      if (cue == null) {
        return found;
      }
      var citation = citationAt(folded, cue, source, issueWords);
      found.add(citation);
      from = Math.max(citation.end(), cue.end());
    }
  }

  /**
   * A cue naming the linked issue or a criterion: where it runs, and whether it is a criterion noun
   * (after which unquoted words name the criterion's subject).
   */
  private record Cue(int start, int end, boolean noun) {}

  /** The earliest cue at or after {@code from}; {@code null} when none. */
  private static Cue nextCue(String text, int from, List<String> keys) {
    var cues = new ArrayList<Cue>();
    for (var pattern : CUES) {
      var m = pattern.matcher(text);
      if (m.find(from)) {
        cues.add(new Cue(m.start(), m.end(), CRITERION_NOUNS.contains(pattern)));
      }
    }
    // Only an issue the pull request links is a citation of it.
    var number = ISSUE_NUMBER.matcher(text);
    var found = number.find(from);
    while (found && !keys.contains(number.group(1))) {
      found = number.find();
    }
    if (found) {
      cues.add(new Cue(number.start(), number.end(), false));
    }
    return cues.stream().min(Comparator.comparingInt(Cue::start)).orElse(null);
  }

  private static Citation citationAt(String text, Cue cue, String source, Set<String> issueWords) {
    var start = cue.start();
    var quote = QUOTED.matcher(text);
    var after = cue.end();
    var sentenceEnd = sentenceEnd(text, after);
    if (quote.find(after)
        && quote.start() - after <= MAX_CUE_TO_QUOTE
        && quote.start() < sentenceEnd) {
      var verdict =
          DescriptionGapScreen.appearsIn(quoted(quote), source)
              ? Verdict.MATCHED
              : Verdict.UNMATCHED;
      return new Citation(start, quote.end(), verdict);
    }
    if (!cue.noun()) {
      return new Citation(start, after, Verdict.UNJUDGED);
    }
    var clause = CLAUSE_END.matcher(text);
    var end = clause.find(after) ? clause.start() : text.length();
    return new Citation(start, end, judgeWords(text.substring(after, end), issueWords));
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
  static String withoutUnmatched(
      String text, List<String> keys, String source, Set<String> issueWords) {
    var out = text;
    var unmatched = firstUnmatched(out, keys, source, issueWords);
    while (unmatched != null) {
      out = cut(out, unmatched);
      unmatched = firstUnmatched(out, keys, source, issueWords);
    }
    return out;
  }

  private static Citation firstUnmatched(
      String text, List<String> keys, String source, Set<String> issueWords) {
    return citations(text, keys, source, issueWords).stream()
        .filter(citation -> citation.verdict() == Verdict.UNMATCHED)
        .findFirst()
        .orElse(null);
  }

  private static String cut(String text, Citation citation) {
    var sentenceStart = sentenceStart(text, citation.start());
    var joiner = lastJoiner(text, sentenceStart, citation.start());
    if (joiner >= 0) {
      return text.substring(0, joiner) + text.substring(citation.end());
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

  /** Whether the clause holding {@code citation} says the code meets the criterion. */
  private static boolean affirmed(String text, Citation citation) {
    var lead = text.substring(sentenceStart(text, citation.start()), citation.start());
    var breaks = CLAUSE_BREAK.matcher(lead);
    var clauseStart = 0;
    while (breaks.find()) {
      clauseStart = breaks.end();
    }
    lead = lead.substring(clauseStart);
    var rest = text.substring(citation.end(), sentenceEnd(text, citation.end()));
    var tailEnd = CLAUSE_BREAK.matcher(rest);
    var clause = lead + " " + (tailEnd.find() ? rest.substring(0, tailEnd.start()) : rest);
    if (AFFIRMS.matcher(clause).find()) {
      return true;
    }
    return RELATIVE.matcher(lead).find() && !anyFinds(VIOLATIONS, clause);
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
