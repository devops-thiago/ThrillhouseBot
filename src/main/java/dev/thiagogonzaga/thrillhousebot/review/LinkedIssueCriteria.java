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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * The "Linked issue #N: &lt;criterion&gt;" lines the Description vs. Implementation section always
 * carries for a pull request linked to exactly one issue (#1093). After #1073 the findings held to
 * the issue were right, but the section still left an unmet criterion out: on ThrillhouseBot-test
 * #269 the criterion became a finding and the section only named the finding; on #268 the overview
 * called a criterion "the issue's requirement" and the section never named it; on #273 a
 * walkthrough row said "no lockout for imminent shifts" and the lockout criterion surfaced nowhere.
 *
 * <p>A criterion is read as unmet when one of these states it, and gets a line unless a {@code
 * Linked issue} gap already names it:
 *
 * <ul>
 *   <li>an open finding that cites the issue ({@link #CITES_ISSUE}) and shares at least {@value
 *       #MIN_SHARED} content words with the criterion, the most of any criterion. The line points
 *       at the finding and takes the place of the finding's own bullet; it is never carried as a
 *       gap ({@link #pointsAtFinding}), since the finding is carried on its own;
 *   <li>a sentence of the overview that cites the issue, with a clause that says it is not met
 *       ({@link #UNMET}) and quotes a passage of the criterion or shares half its content words;
 *   <li>a negated clause of a walkthrough row ("no lockout for imminent shifts", "the board does
 *       not lock shifts …") whose content words, at least {@value #MIN_SHARED} and more than half
 *       of them, are the criterion's.
 * </ul>
 *
 * <p>Words match when equal after {@link SummarySurfaceDeduplicator#claim}'s crude stem, or when
 * one of at least {@value #MIN_FAMILY_CHARS} letters starts the other ("lock" and "lockout"). With
 * two linked issues or more nothing is added: which issue a sentence means is then a guess.
 */
final class LinkedIssueCriteria {

  private LinkedIssueCriteria() {}

  /** Content words a text must share with a criterion before it is read as stating it. */
  static final int MIN_SHARED = 2;

  /** Letters a word needs before another word it starts is read as its family. */
  static final int MIN_FAMILY_CHARS = 4;

  /** What joins a criterion to the finding that reports it, in the line naming both. */
  static final String FINDING_SEPARATOR = " — reported as a finding: ";

  /** The line opening the criteria list of one issue in the linked-issue section. */
  private static final String CRITERIA_HEADING = "Acceptance criteria (from the issue):";

  /** The section's line counting the criteria it left out. */
  private static final Pattern MORE_NOT_SHOWN = Pattern.compile("^- \\(\\d++ more not shown\\)$");

  /** The task-list mark the section keeps before a criterion: "[ ] " or "[x] ". */
  private static final Pattern TASK_MARK = Pattern.compile("^\\[[ xX]]\\s*+");

  /** The linked issue or its criteria named in a text. One pattern per shape. */
  private static final List<Pattern> CITES_ISSUE =
      List.of(
          Pattern.compile("(?i)\\blinked\\s++issues?+\\b"),
          Pattern.compile("(?i)\\bacceptance\\s++criteri\\w*+"),
          Pattern.compile("(?i)\\bissue\\s*+#\\d++\\b"),
          Pattern.compile("(?i)\\bthe\\s++issue(?:['’]s)?+\\s++(?:requirement|criteri)\\w*+"),
          Pattern.compile("(?i)\\bthe\\s++issue['’]s\\b"));

  /**
   * What says, wherever it sits in an overview clause, that the issue's ask is not met: "missing",
   * "unmet", "unimplemented", "dropped", "opposite", "contrary", "contradicts", "violates",
   * "lacks", "fails", "falls short". One pattern per shape.
   */
  private static final List<Pattern> UNMET =
      List.of(
          Pattern.compile("(?i)\\b(?:missing|unmet|unimplemented|dropped)\\b"),
          Pattern.compile(
              "(?i)\\b(?:opposite|contrar\\w*+|contradict\\w*+|violat\\w*+|lacks?|fails?)\\b"),
          Pattern.compile("(?i)\\bfalls?\\s++short\\b"));

  /**
   * A bare negation in an overview clause: "not", "never", "no", "cannot", "n't", "without". It
   * says the ask is not met only when it binds the criterion ({@link #bindsCriterion}): "is not
   * met", "cannot limit the board"; "implemented with no regressions" negates something else, and
   * "not only implemented" ({@link #AFFIRMING}) affirms. A contrast word such as "but" or "instead"
   * is never one. One pattern per shape.
   */
  private static final List<Pattern> NEGATORS =
      List.of(
          Pattern.compile("(?i)\\b(?:not|never|no|cannot|without)\\b"),
          Pattern.compile("(?i)n['’]t\\b"));

  /**
   * Words naming text rather than behaviour: an {@link #UNMET} word in a clause holding one is
   * about that text ("the '…' wording is missing from the walkthrough").
   */
  private static final Pattern META =
      Pattern.compile("(?i)\\b(?:wording|walkthrough|summary|mention|phrasing|docs?)\\b");

  /** Negations that turn a following {@link #UNMET} word into praise: "never fails". */
  private static final Set<String> PRAISING_NEGATIONS =
      Set.of("never", "no", "not", "nothing", "none", "cannot", "longer", "without");

  /** Words, at most, between a {@link #PRAISING_NEGATIONS} word and the word it negates. */
  static final int PRAISE_REACH = 2;

  /** What turns a negation into an affirmation: "not only", "not just", "not merely". */
  private static final Pattern AFFIRMING =
      Pattern.compile("(?i)\\s++(?:only|just|merely|simply)\\b");

  /**
   * Word starts that say an ask is fulfilled: "met", "implemented", "satisfied", "honored",
   * "respected", "applied", "followed", "delivered", "provided", "included", "present"…
   */
  private static final List<String> FULFILMENT =
      List.of(
          "met",
          "implement",
          "satisf",
          "handl",
          "address",
          "cover",
          "done",
          "support",
          "enforc",
          "honor",
          "honour",
          "respect",
          "appli",
          "follow",
          "deliver",
          "provid",
          "includ",
          "present",
          "fulfil",
          "achiev",
          "complet");

  /**
   * Words between a negation and what it negates that are skipped when binding it, besides the
   * adverbs ending in "-ly": "does not yet lock", "does not even lock".
   */
  private static final Set<String> QUALIFIERS = Set.of("yet", "even", "always", "ever", "longer");

  /** Content words after an overview negation it may bind, at most. */
  static final int NEGATION_REACH = 3;

  /** Where an overview sentence ends. */
  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s++");

  /**
   * Where an overview sentence's clauses split: the not-met word, the quote and the criterion's
   * words must share one clause, so a contrast about something else does not reach the quote.
   */
  private static final Pattern OVERVIEW_CLAUSE =
      Pattern.compile("(?i)[,;—–]|\\b(?:but|however|while|whereas|although|though)\\b");

  /** Where a walkthrough row's clauses split. */
  private static final Pattern CLAUSE_SPLIT = Pattern.compile("[;.(),]|\\s(?:and|or|but)\\s");

  /** A clause opening with a negation: "no lockout for …", "without a cancel path". */
  private static final Pattern NEGATED_CLAUSE =
      Pattern.compile("(?i)^\\s*+(?:no|not|never|without|missing|lacks?)\\s");

  /**
   * A verb negated inside a clause: "the board does not lock …", "won't lock", "can't lock", "never
   * locks", "cannot limit", "fails to lock", "lacks a store check", "is unable to". A negated "is",
   * "are", "was" or "were" states a condition ("shifts that are not yet started"), not a missing
   * behaviour, so it is not one. One pattern per shape.
   */
  private static final List<Pattern> NEGATED_VERB =
      List.of(
          Pattern.compile("(?i)\\b(?:does|do|did|can|will|has|have)(?:\\s++not|n['’]t)\\b"),
          Pattern.compile("(?i)\\b(?:won['’]t|can['’]t|cannot|never|no\\s++longer)\\b"),
          Pattern.compile("(?i)\\b(?:lacks?|fails?\\s++to|refus\\w*+\\s++to|unable\\s++to)\\b"));

  /**
   * A quoted passage, in double or single quotes (curly quotes folded), one pattern per quote. A
   * single-quoted passage ends at the first quote no letter or digit follows, so an apostrophe
   * inside it stays: 'the council's yearly export'.
   */
  private static final List<Pattern> QUOTED =
      List.of(
          Pattern.compile("\"([^\"\n]++)\""),
          Pattern.compile("(?<![\\p{L}\\p{N}])'([^\n]+?)'(?![\\p{L}\\p{N}])"));

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

  /**
   * The criterion lines to render, and the findings whose own bullet one of them replaces.
   *
   * @param lines the lines, in criteria order
   * @param pointed the findings a line points at
   */
  record Lines(List<String> lines, Set<Finding> pointed) {

    static final Lines NONE = new Lines(List.of(), Set.of());

    Lines {
      lines = List.copyOf(lines);
      pointed = Set.copyOf(pointed);
    }
  }

  /**
   * The lines for the criteria of the one issue {@code linkedIssues} links that {@code findings},
   * {@code overview} or {@code fileSummaries} state unmet and no gap of {@code gaps} names.
   *
   * @param linkedIssues the unfenced linked-issue section, {@code ""} when none
   * @param gaps the section's gaps as they will be rendered
   * @param findings the pull request's open findings
   * @param overview the summary's "What this PR does" text, {@code null} when none
   * @param fileSummaries the walkthrough notes by path
   */
  static Lines unmet(
      String linkedIssues,
      List<String> gaps,
      List<Finding> findings,
      String overview,
      Map<String, String> fileSummaries) {
    var keys = TicketContextResolver.linkedKeys(linkedIssues);
    if (keys.size() != 1) {
      return Lines.NONE;
    }
    var placed = new Placed(keys.getFirst(), criteria(linkedIssues), gaps);
    for (var finding : findings) {
      var text = findingText(finding);
      if (citesIssue(text)
          && placed.place(
              bestMatch(words(text), placed.criteria()),
              FINDING_SEPARATOR
                  + PrSummaryGenerator.findingBullet(finding)
                      .substring(PrSummaryGenerator.GAP_FINDING_PREFIX.length()))) {
        placed.pointed().add(finding);
      }
    }
    for (var sentence : sentences(overview)) {
      for (var at : overviewMatches(sentence, placed.criteria())) {
        placed.place(at, " — the overview above reports it unmet");
      }
    }
    for (var row : new TreeMap<>(fileSummaries).entrySet()) {
      for (var clause : CLAUSE_SPLIT.split(row.getValue())) {
        placed.place(
            clauseMatch(clause, placed.criteria()),
            " — `"
                + MarkdownSafe.inlineCode(row.getKey())
                + "`: "
                + MarkdownSafe.inline(clause.strip()));
      }
    }
    return new Lines(List.copyOf(new TreeMap<>(placed.lines()).values()), placed.pointed());
  }

  /**
   * The lines placed so far, by criterion position, and the findings they point at.
   *
   * @param key the linked issue's key
   * @param criteria its criteria
   * @param gaps the section's gaps, a criterion one names gets no line
   * @param lines the line of each criterion placed
   * @param pointed the findings a line points at
   */
  private record Placed(
      String key,
      List<String> criteria,
      List<String> gaps,
      Map<Integer, String> lines,
      Set<Finding> pointed) {

    Placed(String key, List<String> criteria, List<String> gaps) {
      this(key, criteria, gaps, new LinkedHashMap<>(), new LinkedHashSet<>());
    }

    /**
     * Places criterion {@code at}'s line, ending in {@code evidence}, when it still wants one: it
     * was found, none is placed yet, and no {@code Linked issue} gap names it. Whether it did.
     */
    boolean place(int at, String evidence) {
      if (at < 0 || lines.containsKey(at) || named(criteria.get(at))) {
        return false;
      }
      lines.put(
          at, "Linked issue " + key + ": " + MarkdownSafe.oneLine(criteria.get(at)) + evidence);
      return true;
    }

    /** Whether a {@code Linked issue} gap of {@link #gaps} names {@code criterion}. */
    private boolean named(String criterion) {
      var wanted = words(criterion);
      for (var gap : gaps) {
        var linked = DescriptionGapCarryover.linkedIssueGap(gap);
        if (linked != null) {
          var stated = words(DescriptionGapCarryover.criterionOf(linked.group(2)));
          var shared = shared(stated, wanted);
          if (shared * 2 >= Math.min(stated.size(), wanted.size()) && shared > 0) {
            return true;
          }
        }
      }
      return false;
    }
  }

  /**
   * Whether a rendered gap is a criterion line pointing at a finding: the finding is carried on its
   * own and keeps the line while it is open, so the carry-over does not read it as a gap.
   */
  static boolean pointsAtFinding(String gap) {
    return DescriptionGapCarryover.linkedIssueGap(gap) != null && gap.contains(FINDING_SEPARATOR);
  }

  /** The criteria the one issue's part of {@code linkedIssues} lists, in order. */
  static List<String> criteria(String linkedIssues) {
    var criteria = new ArrayList<String>();
    var listing = false;
    for (var raw : linkedIssues.split("\n", -1)) {
      var line = raw.strip();
      if (line.equals(CRITERIA_HEADING)) {
        listing = true;
      } else if (listing && line.startsWith("- ") && !MORE_NOT_SHOWN.matcher(line).matches()) {
        // The section keeps the issue's task-list mark ("[ ] "); the criterion is what follows.
        criteria.add(TASK_MARK.matcher(line.substring(2).strip()).replaceFirst(""));
      } else if (listing) {
        listing = false;
      }
    }
    return criteria;
  }

  /** The criterion sharing the most words with {@code words}, at least {@value #MIN_SHARED}. */
  private static int bestMatch(Set<String> words, List<String> criteria) {
    var best = clearBest(words, criteria);
    return best >= 0 && shared(words, words(criteria.get(best))) >= MIN_SHARED ? best : -1;
  }

  /**
   * The criteria an overview sentence states unmet, in the order it names them: the sentence cites
   * the issue, and a clause of it says the criterion is not met ({@link #UNMET}, {@link #NEGATORS})
   * and quotes a passage of it or shares half its words.
   */
  private static List<Integer> overviewMatches(String sentence, List<String> criteria) {
    if (!citesIssue(sentence)) {
      return List.of();
    }
    var found = new ArrayList<Integer>();
    for (var clause : overviewClauses(foldQuotes(sentence))) {
      for (var at : stated(clause, criteria)) {
        if (saysUnmet(clause, words(criteria.get(at)))) {
          found.add(at);
        }
      }
    }
    return found;
  }

  /**
   * The clauses of {@code sentence} split at each {@link #OVERVIEW_CLAUSE} separator outside a
   * {@link #QUOTED} passage, so a quote holding a comma or a dash stays whole in one clause.
   */
  private static List<String> overviewClauses(String sentence) {
    var quotes = quotes(sentence);
    var clauses = new ArrayList<String>();
    var from = 0;
    var separator = OVERVIEW_CLAUSE.matcher(sentence);
    while (separator.find()) {
      var at = separator.start();
      if (quotes.stream().noneMatch(q -> q.start() < at && at < q.end())) {
        clauses.add(sentence.substring(from, at));
        from = separator.end();
      }
    }
    clauses.add(sentence.substring(from));
    return clauses;
  }

  /**
   * Whether {@code negation}, a {@link #PRAISING_NEGATIONS} word, turns an {@link #UNMET} word
   * among the first {@value #PRAISE_REACH} words of {@code rest} into praise: "never fails to
   * honor", "no longer missing".
   */
  private static boolean praises(String negation, String rest) {
    if (!PRAISING_NEGATIONS.contains(negation.toLowerCase(Locale.ROOT))) {
      return false;
    }
    var words = NON_ALPHANUMERIC.split(rest.strip());
    for (var i = 0; i < Math.min(PRAISE_REACH, words.length); i++) {
      var from = String.join(" ", Arrays.asList(words).subList(i, words.length));
      if (UNMET.stream().anyMatch(pattern -> pattern.matcher(from).lookingAt())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether one of the last {@value #PRAISE_REACH} words of {@code lead} negates what follows:
   * "never fails", "nothing is missing", "no longer drops".
   */
  private static boolean negatedBefore(String lead) {
    var words = NON_ALPHANUMERIC.split(lead.strip().toLowerCase(Locale.ROOT));
    for (var i = Math.max(0, words.length - PRAISE_REACH); i < words.length; i++) {
      if (PRAISING_NEGATIONS.contains(words[i])) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code clause} says the criterion of {@code criterionWords} is not met: an {@link
   * #UNMET} word no negation turns into praise, or a {@link #NEGATORS} negation that binds it.
   */
  private static boolean saysUnmet(String clause, Set<String> criterionWords) {
    // A clause about the summary's own text ("the '…' wording is missing from the walkthrough")
    // says nothing about the change.
    if (META.matcher(clause).find()) {
      return false;
    }
    for (var pattern : UNMET) {
      var unmet = pattern.matcher(clause);
      while (unmet.find()) {
        // "never fails", "nothing is missing" praise the change.
        if (!negatedBefore(clause.substring(0, unmet.start()))) {
          return true;
        }
      }
    }
    for (var pattern : NEGATORS) {
      var negation = pattern.matcher(clause);
      while (negation.find()) {
        var rest = clause.substring(negation.end());
        // "not only implemented" affirms what follows; "never fails to honor" is the praise the
        // UNMET reading above already pardoned.
        if (!AFFIRMING.matcher(rest).lookingAt()
            && !praises(negation.group(), rest)
            && bindsCriterion(rest, criterionWords, NEGATION_REACH, true)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether one of the first {@code reach} content words of {@code rest}, what follows a negation,
   * is a word of the criterion's family, or with {@code fulfilment} a {@link #FULFILMENT} word: the
   * negation is then about the criterion, as in "does not lock" or "is not met", not about
   * something else, as in "does not allow sign-ups for locked shifts". An adverb ending in "-ly"
   * and a {@link #QUALIFIERS} word ("does not actually lock", "does not yet lock") are skipped.
   */
  private static boolean bindsCriterion(
      String rest, Set<String> criterionWords, int reach, boolean fulfilment) {
    var next =
        SummarySurfaceDeduplicator.claim(rest).words().stream()
            .filter(word -> !word.endsWith("ly") && !QUALIFIERS.contains(word))
            .toList();
    for (var i = 0; i < Math.min(reach, next.size()); i++) {
      var word = next.get(i);
      if ((fulfilment && FULFILMENT.stream().anyMatch(word::startsWith))
          || criterionWords.stream().anyMatch(c -> family(word, c))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The one of {@code candidates} sharing strictly the most words with {@code words}: the only one,
   * or -1 when none is given or two share the most.
   */
  private static int clearBest(Set<String> words, List<String> candidates) {
    var best = -1;
    var bestShared = -1;
    var tied = false;
    for (var i = 0; i < candidates.size(); i++) {
      var shared = shared(words, words(candidates.get(i)));
      if (shared > bestShared) {
        best = i;
        bestShared = shared;
        tied = false;
      } else if (shared == bestShared) {
        tied = true;
      }
    }
    return tied ? -1 : best;
  }

  /**
   * The criteria {@code clause} quotes a passage of, in order; or else the one it shares half the
   * words of; or none.
   */
  private static List<Integer> stated(String clause, List<String> criteria) {
    var words = words(clause);
    var quoted = new LinkedHashSet<Integer>();
    for (var match : quotes(clause)) {
      var quote = normalize(match.group(1));
      if (quote.indexOf(' ') < 0) {
        continue;
      }
      // A phrase several criteria hold ("the Sign up button") goes to the one the clause's other
      // words share most with; with no clear one it names none of them.
      var holding =
          criteria.stream()
              .filter(c -> (" " + normalize(c) + " ").contains(" " + quote + " "))
              .toList();
      var best = clearBest(words, holding);
      if (best >= 0) {
        quoted.add(criteria.indexOf(holding.get(best)));
      }
    }
    if (!quoted.isEmpty()) {
      return List.copyOf(quoted);
    }
    var at = bestMatch(words, criteria);
    return at >= 0 && shared(words, words(criteria.get(at))) * 2 >= words(criteria.get(at)).size()
        ? List.of(at)
        : List.of();
  }

  /**
   * The criterion a negated walkthrough clause states unmet: at least {@value #MIN_SHARED} of the
   * clause's words, and more than half of them, are the criterion's, and the clause opens with a
   * negation or negates a verb whose first content word after it is the criterion's ("no lockout",
   * "doesn't lock"; not "does not allow sign-ups for locked shifts").
   */
  private static int clauseMatch(String clause, List<String> criteria) {
    var words = words(clause);
    var at = bestMatch(words, criteria);
    if (at < 0 || shared(words, words(criteria.get(at))) * 2 <= words.size()) {
      return -1;
    }
    var criterionWords = words(criteria.get(at));
    var negations = new ArrayList<Pattern>(NEGATED_VERB);
    negations.add(NEGATED_CLAUSE);
    for (var pattern : negations) {
      var negation = pattern.matcher(clause);
      while (negation.find()) {
        if (bindsCriterion(clause.substring(negation.end()), criterionWords, 1, false)) {
          return at;
        }
      }
    }
    return -1;
  }

  /** How many of {@code words} have a word of {@code other} in their family. */
  static int shared(Set<String> words, Set<String> other) {
    return (int) words.stream().filter(w -> other.stream().anyMatch(o -> family(w, o))).count();
  }

  /** Whether two stemmed words are one, or one of them starts the other. */
  static boolean family(String word, String other) {
    if (word.equals(other)) {
      return true;
    }
    var wordFirst = word.length() <= other.length();
    var shorter = wordFirst ? word : other;
    var longer = wordFirst ? other : word;
    return shorter.length() >= MIN_FAMILY_CHARS && longer.startsWith(shorter);
  }

  private static Set<String> words(String text) {
    return SummarySurfaceDeduplicator.claim(text).wordSet();
  }

  private static boolean citesIssue(String text) {
    return CITES_ISSUE.stream().anyMatch(p -> p.matcher(text).find());
  }

  private static String findingText(Finding finding) {
    return Objects.requireNonNullElse(finding.title(), "")
        + "\n"
        + Objects.requireNonNullElse(finding.description(), "");
  }

  private static List<String> sentences(String overview) {
    return overview == null ? List.of() : Arrays.asList(SENTENCE_END.split(overview));
  }

  /** The {@link #QUOTED} passages of {@code text}, in the order they start. */
  private static List<MatchResult> quotes(String text) {
    return QUOTED.stream()
        .flatMap(pattern -> pattern.matcher(text).results())
        .sorted(Comparator.comparingInt(MatchResult::start))
        .toList();
  }

  private static String foldQuotes(String text) {
    return text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'');
  }

  private static String normalize(String text) {
    return NON_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }
}
