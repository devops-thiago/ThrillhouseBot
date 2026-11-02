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
import dev.thiagogonzaga.thrillhousebot.review.ai.SentenceBreaks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
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
 *       of them, are the criterion's;
 *   <li>a review call's verdict that the criterion is unmet, when no call judged it met (#1200).
 *       The review call reads the diff, which the summary call does not, so this is the one source
 *       that names a criterion nothing the round wrote mentions: on round 19 the issue went unnamed
 *       on five pull requests of twelve. A criterion a call judged met gets no line from the
 *       overview or a walkthrough row either, both written without the diff, and a {@code Linked
 *       issue} gap naming it leaves the section ({@link #withoutMet}); a finding still places its
 *       line, since it quotes the changed code.
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

  /** Characters of a review call's evidence kept in the line it backs. */
  static final int MAX_EVIDENCE_CHARS = 200;

  /** The status a review call gives a criterion it judged met. */
  static final String VERDICT_MET = "met";

  /** The status a review call gives a criterion it judged unmet. */
  static final String VERDICT_UNMET = "unmet";

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
   * "lacks", "lacking", "fails", "falls short", "has yet to be implemented". One pattern per shape.
   */
  private static final List<Pattern> UNMET =
      List.of(
          Pattern.compile("(?i)\\b(?:missing|unmet|unimplemented|dropped)\\b"),
          Pattern.compile("(?i)\\b(?:opposite|contrar\\w*+|contradict\\w*+|violat\\w*+|fails?)\\b"),
          Pattern.compile("(?i)\\black(?:s|ing)?+\\b"),
          Pattern.compile("(?i)\\bfalls?\\s++short\\b"),
          Pattern.compile("(?i)\\byet\\s++to\\b"));

  /**
   * A bare negation in an overview clause: "not", "never", "no", "cannot", "without" ("n't" is read
   * as "not" first, {@link #readable}). It says the ask is not met only when it binds the criterion
   * ({@link #bindsCriterion}): "is not met", "cannot limit the board"; "implemented with no
   * regressions" negates something else, and "not only implemented" ({@link #AFFIRMING}) affirms. A
   * contrast word such as "but" or "instead" is never one. One pattern per shape.
   */
  private static final Pattern NEGATORS =
      Pattern.compile("(?i)\\b(?:not|never|no|cannot|without)\\b");

  /**
   * Words naming text rather than behaviour: an {@link #UNMET} word in a clause holding one is
   * about that text ("the '…' wording is missing from the walkthrough").
   */
  private static final Pattern META =
      Pattern.compile("(?i)\\b(?:wording|walkthrough|summary|mention|phrasing|docs?)\\b");

  /** "can't", read as "cannot" ({@link #readable}). */
  private static final Pattern CANT = Pattern.compile("(?i)\\bcan't\\b");

  /** "won't", read as "will not" ({@link #readable}). */
  private static final Pattern WONT = Pattern.compile("(?i)\\bwon't\\b");

  /** Any other contracted negation, read as "not": "isn't missing", "doesn’t fail". */
  private static final Pattern CONTRACTED = Pattern.compile("(?i)n't\\b");

  /**
   * What says a criterion's not-met word is the gap it closes, after the word: "an import that
   * fails on a duplicate: fixed".
   */
  private static final Pattern REMEDY =
      Pattern.compile("(?i)\\b(?:fixed|resolved|prevented|avoided|handled)\\b");

  /** Words after a criterion's not-met word a {@link #REMEDY} may sit within, at most. */
  static final int REMEDY_REACH = 3;

  /**
   * Where a criterion's clauses split, for reading what stands before and after one of its words: a
   * negation or a {@link #REMEDY} in another clause ("…are dropped, and the retry is fixed") is
   * about something else.
   */
  private static final Pattern CRITERION_CLAUSE =
      Pattern.compile("(?i)[;,.!?()—–]|\\b(?:and|but|or|while)\\b");

  /** A word of a criterion, for finding where its own words sit. */
  private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{N}]*+");

  /** Negations that turn a following {@link #UNMET} word into praise: "never fails". */
  private static final Set<String> PRAISING_NEGATIONS =
      Set.of("never", "no", "not", "nothing", "none", "cannot", "longer", "without");

  /** Words, at most, between a {@link #PRAISING_NEGATIONS} word and the word it negates. */
  static final int PRAISE_REACH = 2;

  /**
   * Words that only strengthen a negation and are skipped when measuring its reach, besides the
   * adverbs ending in "-ly": "not at all missing", "never really fails".
   */
  private static final Set<String> INTENSIFIERS = Set.of("at", "all", "even", "ever", "quite");

  /** What turns a negation into an affirmation: "not only", "not just", "not merely". */
  private static final Pattern AFFIRMING =
      Pattern.compile("(?i)\\s++(?:only|just|merely|simply)\\b");

  /**
   * Words that say an ask is fulfilled, in the forms an overview uses: "is not met", "does not
   * meet", "is not implemented", "not satisfied"… Matched by their whole stem ({@link
   * #FULFILMENT_STEMS}), never as the start of a longer word: "metrics" and "coverage" are not
   * "met" and "cover".
   */
  private static final List<String> FULFILMENT =
      List.of(
          "met",
          "meet",
          "meeting",
          "match",
          "matching",
          "implement",
          "implementing",
          "satisfy",
          "satisfied",
          "satisfying",
          "handle",
          "handling",
          "address",
          "addressing",
          "cover",
          "covering",
          "done",
          "support",
          "supporting",
          "enforce",
          "enforcing",
          "honor",
          "honoring",
          "honour",
          "honouring",
          "respect",
          "respecting",
          "apply",
          "applied",
          "applying",
          "follow",
          "following",
          "deliver",
          "delivering",
          "provide",
          "providing",
          "include",
          "including",
          "present",
          "fulfil",
          "fulfill",
          "fulfilling",
          "achieve",
          "achieving",
          "complete",
          "completing");

  /**
   * The stems of the {@link #FULFILMENT} words, as {@link #bindsCriterion} reads the words: the
   * stemmer drops a final "s", "e" or "d", so "addressed", "addresses" and "address" are one stem.
   */
  private static final Set<String> FULFILMENT_STEMS = Set.copyOf(stems(FULFILMENT));

  /**
   * Words between a negation and what it negates that are skipped when binding it, besides the
   * adverbs ending in "-ly": "does not yet lock", "does not always lock", and the verbs a negation
   * passes through: "doesn't think the change will match", "does not seem to lock". Whole words,
   * matched by their stem ({@link #QUALIFIER_STEMS}), so every inflection is skipped.
   */
  private static final List<String> QUALIFIERS =
      List.of(
          "yet", "even", "always", "ever", "longer", "think", "believe", "expect", "seem", "appear",
          "will");

  /** The stems of the {@link #QUALIFIERS}, as {@link #bindsCriterion} reads the words. */
  private static final Set<String> QUALIFIER_STEMS = Set.copyOf(stems(QUALIFIERS));

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

  /** The "and" an overview clause's quoted criteria are each read on their own side of. */
  private static final Pattern AND = Pattern.compile("(?i)\\band\\b");

  /** Where a walkthrough row's clauses split. */
  private static final Pattern CLAUSE_SPLIT = Pattern.compile("[;.(),]|\\s(?:and|or|but)\\s");

  /** A clause opening with a negation: "no lockout for …", "without a cancel path". */
  private static final Pattern NEGATED_CLAUSE =
      Pattern.compile("(?i)^\\s*+(?:no|not|never|without|missing|lacks?)\\s");

  /**
   * A verb negated inside a clause, contractions read first ({@link #readable}): "the board does
   * not lock …", "won't lock", "can't lock", "never locks", "cannot limit", "fails to lock", "lacks
   * a store check", "is unable to". A negated "is", "are", "was" or "were" states a condition
   * ("shifts that are not yet started"), not a missing behaviour, so it is not one. One pattern per
   * shape.
   */
  private static final List<Pattern> NEGATED_VERB =
      List.of(
          Pattern.compile("(?i)\\b(?:does|do|did|can|will|has|have)\\s++not\\b"),
          Pattern.compile("(?i)\\b(?:cannot|never|no\\s++longer)\\b"),
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
   * @param verdicts the review calls' verdicts on the criteria (#1200): one judged unmet and by no
   *     call met gets a line even when nothing else the round wrote names it
   */
  static Lines unmet(
      String linkedIssues,
      List<String> gaps,
      List<Finding> findings,
      String overview,
      Map<String, String> fileSummaries,
      List<ReviewResponse.CriterionVerdict> verdicts) {
    var keys = TicketContextResolver.linkedKeys(linkedIssues);
    if (keys.size() != 1) {
      return Lines.NONE;
    }
    var placed = new Placed(keys.getFirst(), criteria(linkedIssues), gaps);
    var judged = Judged.of(verdicts, placed.criteria());
    placeFindings(placed, findings);
    // The overview and the walkthrough are written without the diff; a call that read it and judged
    // the criterion met outweighs them. A finding does not: it quotes the changed line.
    placeOverview(placed, overview, judged.met());
    placeWalkthrough(placed, fileSummaries, judged.met());
    // Last, so a criterion another surface names keeps that line: a finding is the stronger proof.
    for (var unmet : judged.unmet().entrySet()) {
      placed.place(unmet.getKey(), unmet.getValue());
    }
    return new Lines(List.copyOf(new TreeMap<>(placed.lines()).values()), placed.pointed());
  }

  /** Places the line of each criterion an open finding citing the issue states unmet. */
  private static void placeFindings(Placed placed, List<Finding> findings) {
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
  }

  /** Places the line of each criterion an overview sentence states unmet, unless judged met. */
  private static void placeOverview(Placed placed, String overview, Set<Integer> met) {
    for (var sentence : sentences(overview)) {
      for (var at : overviewMatches(sentence, placed.criteria())) {
        placed.place(met.contains(at) ? -1 : at, " — the overview above reports it unmet");
      }
    }
  }

  /** Places the line of each criterion a walkthrough clause states unmet, unless judged met. */
  private static void placeWalkthrough(
      Placed placed, Map<String, String> fileSummaries, Set<Integer> met) {
    for (var row : new TreeMap<>(fileSummaries).entrySet()) {
      for (var clause : CLAUSE_SPLIT.split(row.getValue())) {
        var at = clauseMatch(clause, placed.criteria());
        placed.place(
            met.contains(at) ? -1 : at,
            " — `"
                + MarkdownSafe.inlineCode(row.getKey())
                + "`: "
                + MarkdownSafe.inline(clause.strip()));
      }
    }
  }

  /**
   * {@code gaps} without the {@code Linked issue} gaps whose criterion a review call of this round
   * judged met (#1200). A carried gap otherwise leaves only through the summary call, which does
   * not see the diff, so a criterion a later push implements would stay listed as unmet. A gap
   * pointing at a finding is kept: the finding is carried on its own and the gap leaves with it.
   */
  static List<String> withoutMet(
      List<String> gaps, String linkedIssues, List<ReviewResponse.CriterionVerdict> verdicts) {
    if (TicketContextResolver.linkedKeys(linkedIssues).size() != 1) {
      return gaps;
    }
    var criteria = criteria(linkedIssues);
    var met = Judged.of(verdicts, criteria).met();
    if (met.isEmpty()) {
      return gaps;
    }
    return gaps.stream()
        .filter(
            gap -> {
              var linked = DescriptionGapCarryover.linkedIssueGap(gap);
              return linked == null
                  || pointsAtFinding(gap)
                  || !met.contains(
                      sharedHalf(
                          words(DescriptionGapCarryover.criterionOf(linked.group(2))), criteria));
            })
        .toList();
  }

  /**
   * The criteria the review calls judged (#1200). A call sees only its own batch of files, so a
   * criterion one call judges met is met, whatever another judged; one judged unmet by a call and
   * met by none is unmet, with the first call's evidence. A verdict whose criterion matches none of
   * the issue's, or whose status is neither, says nothing.
   *
   * @param met the criteria some call judged met
   * @param unmet the line ending of each criterion judged unmet, by position
   */
  private record Judged(Set<Integer> met, Map<Integer, String> unmet) {

    static Judged of(List<ReviewResponse.CriterionVerdict> verdicts, List<String> criteria) {
      var met = new HashSet<Integer>();
      var unmet = new TreeMap<Integer, String>();
      for (var verdict : verdicts) {
        var at = sharedHalf(words(verdict.criterion()), criteria);
        var status = verdict.status().strip().toLowerCase(Locale.ROOT);
        if (at >= 0 && VERDICT_MET.equals(status)) {
          met.add(at);
        } else if (at >= 0 && VERDICT_UNMET.equals(status)) {
          unmet.putIfAbsent(at, evidence(verdict.note()));
        }
      }
      unmet.keySet().removeAll(met);
      return new Judged(met, unmet);
    }

    /** The line ending naming a call's evidence, or the verdict alone when it gave none. */
    private static String evidence(String evidence) {
      var line = MarkdownSafe.oneLine(evidence).strip();
      if (line.isEmpty()) {
        return " — the review of the diff found no change that meets it";
      }
      if (line.length() > MAX_EVIDENCE_CHARS) {
        line = line.substring(0, MAX_EVIDENCE_CHARS - 1).strip() + "…";
      }
      return " — the review of the diff found it unmet: " + MarkdownSafe.inline(line);
    }
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
    for (var clause : overviewClauses(sentence)) {
      var scopes = quotedScopes(clause, criteria);
      if (scopes.isEmpty()) {
        var at = sharedHalf(words(clause), criteria);
        scopes = at < 0 ? Map.of() : Map.of(at, clause);
      }
      for (var scope : scopes.entrySet()) {
        if (saysUnmet(scope.getValue(), criteria.get(scope.getKey()))) {
          found.add(scope.getKey());
        }
      }
    }
    return found;
  }

  /**
   * The criteria {@code clause} quotes a passage of, in order, each with the part of the clause
   * that speaks of it: the clause is cut at each "and" outside a quote, and a part whose last quote
   * is followed by one word at most is a subject of the next part's verb ("the 'A' card and the 'B'
   * state are missing"), so it reads on into it. A part never reads back: "the listing is missing
   * and the 'Sign-ups closed' card renders" does not report the card unmet.
   */
  private static Map<Integer, String> quotedScopes(String clause, List<String> criteria) {
    var quotes = quotes(clause);
    var parts = new ArrayList<int[]>();
    var from = 0;
    var and = AND.matcher(clause);
    while (and.find()) {
      var at = and.start();
      if (quotes.stream().noneMatch(q -> q.start() < at && at < q.end())) {
        parts.add(new int[] {from, at});
        from = and.end();
      }
    }
    parts.add(new int[] {from, clause.length()});
    var scopes = new LinkedHashMap<Integer, String>();
    var words = words(clause);
    for (var match : quotes) {
      var at = quotedCriterion(match, words, criteria);
      if (at < 0 || scopes.containsKey(at)) {
        continue;
      }
      var part = 0;
      while (parts.get(part)[1] <= match.start()) {
        part++;
      }
      var end = part;
      while (end + 1 < parts.size() && subjectOnly(clause, parts.get(end), quotes)) {
        end++;
      }
      scopes.put(at, clause.substring(parts.get(part)[0], parts.get(end)[1]));
    }
    return scopes;
  }

  /**
   * Whether one word at most follows the last quote of the {@code part} of {@code clause}, or makes
   * up the part when it quotes nothing: the part names a subject ("the 'A' card") and says nothing
   * of it.
   */
  private static boolean subjectOnly(String clause, int[] part, List<MatchResult> quotes) {
    var last =
        quotes.stream()
            .filter(q -> q.start() >= part[0] && q.end() <= part[1])
            .mapToInt(MatchResult::end)
            .max()
            .orElse(part[0]);
    return normalize(clause.substring(last, part[1])).indexOf(' ') < 0;
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
   * Whether a {@link #NEGATORS} negation turns an {@link #UNMET} word among the first {@value
   * #PRAISE_REACH} words of {@code rest}, what follows it, into praise: "never fails to honor", "no
   * longer missing", "isn't missing". A word the criterion asks for ({@link #asksFor}) is no such
   * word: "not dropped" says a criterion asking for the drop is unmet.
   */
  private static boolean praises(String rest, String criterion) {
    var words = withoutIntensifiers(tokens(rest));
    for (var i = 0; i < Math.min(PRAISE_REACH, words.size()); i++) {
      var from = String.join(" ", words.subList(i, words.size()));
      // "not dropped" is no praise when the criterion asks for the drop.
      if (UNMET.stream().anyMatch(pattern -> pattern.matcher(from).lookingAt())
          && !asksFor(words.get(i), criterion)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether one of the last {@value #PRAISE_REACH} words of {@code lead}, {@link #INTENSIFIERS}
   * aside, negates what follows: "never fails", "nothing is missing", "not at all missing", "isn't
   * missing".
   */
  private static boolean negatedBefore(String lead) {
    var words = withoutIntensifiers(tokens(lead.toLowerCase(Locale.ROOT)));
    for (var i = Math.max(0, words.size() - PRAISE_REACH); i < words.size(); i++) {
      if (PRAISING_NEGATIONS.contains(words.get(i))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code clause} holds an {@link #UNMET} word that the criterion does not ask for ({@link
   * #asksFor}) and no negation right before it turns into praise.
   */
  private static boolean holdsUnmetWord(String clause, String criterion) {
    for (var pattern : UNMET) {
      var unmet = pattern.matcher(clause);
      while (unmet.find()) {
        // "never fails", "nothing is missing" praise the change; "dropped" in "the bullets
        // dropped" restates a criterion that asks for them to be dropped.
        if (!negatedBefore(clause.substring(0, unmet.start()))
            && !asksFor(unmet.group(), criterion)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether {@code criterion} asks for what the not-met word {@code text} says: it holds a word of
   * that family that, within its own clause ({@link #CRITERION_CLAUSE}), is neither negated right
   * before it nor followed by a {@link #REMEDY} within {@value #REMEDY_REACH} words. "Tests from
   * the bullets: dropped" asks for the drop; "the parser no longer fails" and "an import that fails
   * on a duplicate: fixed" name the gap they close, so "fails" there is still the gap.
   */
  private static boolean asksFor(String text, String criterion) {
    var wanted = words(text);
    var readable = readable(criterion);
    var word = WORD.matcher(readable);
    while (word.find()) {
      if (words(word.group()).stream().anyMatch(c -> wanted.stream().anyMatch(w -> family(w, c)))
          && !negatedBefore(readable.substring(clauseStart(readable, word.start()), word.start()))
          && !remedyFollows(readable.substring(word.end(), clauseEnd(readable, word.end())))) {
        return true;
      }
    }
    return false;
  }

  /** Where the {@link #CRITERION_CLAUSE} holding position {@code at} of {@code text} starts. */
  private static int clauseStart(String text, int at) {
    var start = 0;
    var split = CRITERION_CLAUSE.matcher(text).region(0, at);
    while (split.find()) {
      start = split.end();
    }
    return start;
  }

  /** Where the {@link #CRITERION_CLAUSE} holding position {@code at} of {@code text} ends. */
  private static int clauseEnd(String text, int at) {
    var split = CRITERION_CLAUSE.matcher(text).region(at, text.length());
    return split.find() ? split.start() : text.length();
  }

  /** Whether a {@link #REMEDY} is among the first {@value #REMEDY_REACH} words of {@code tail}. */
  private static boolean remedyFollows(String tail) {
    return tokens(tail).stream().limit(REMEDY_REACH).anyMatch(w -> REMEDY.matcher(w).matches());
  }

  /**
   * The words of {@code text}, in order: the runs of letters and digits, with no empty token for
   * punctuation opening it (": fixed", "\"now\" missing"), so a fixed reach counts words only.
   */
  private static List<String> tokens(String text) {
    return Arrays.stream(NON_ALPHANUMERIC.split(text)).filter(word -> !word.isEmpty()).toList();
  }

  /** {@code words} less the {@link #INTENSIFIERS} and the adverbs ending in "-ly". */
  private static List<String> withoutIntensifiers(List<String> words) {
    return words.stream()
        .filter(word -> !INTENSIFIERS.contains(word.toLowerCase(Locale.ROOT)))
        .filter(word -> !word.toLowerCase(Locale.ROOT).endsWith("ly"))
        .toList();
  }

  /**
   * Whether {@code clause} says {@code criterion} is not met: an {@link #UNMET} word no negation
   * turns into praise and the criterion does not ask for ({@link #asksFor}), or a {@link #NEGATORS}
   * negation that binds it. A not-met word the criterion uses for the gap it closes ("A duplicate
   * email import fails: fixed.", "The parser no longer fails") is still read as the gap, so "the
   * import still fails" states it unmet; only a word the criterion asks for ("bullets: dropped") is
   * no signal. A praise word such as "matches the criteria" pardons neither: "matches the criteria
   * and the bullets are not dropped" still states it unmet.
   */
  private static boolean saysUnmet(String clause, String criterion) {
    var criterionWords = words(criterion);
    // A clause about the summary's own text ("the '…' wording is missing from the walkthrough")
    // says nothing about the change.
    if (META.matcher(clause).find()) {
      return false;
    }
    if (holdsUnmetWord(clause, criterion)) {
      return true;
    }
    var negation = NEGATORS.matcher(clause);
    while (negation.find()) {
      var rest = clause.substring(negation.end());
      // "not only implemented" affirms what follows; "never fails to honor" is the praise the
      // UNMET reading above already pardoned.
      if (!AFFIRMING.matcher(rest).lookingAt()
          && !praises(rest, criterion)
          && bindsCriterion(rest, criterionWords, NEGATION_REACH, true)) {
        return true;
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
            .filter(word -> !word.endsWith("ly") && !QUALIFIER_STEMS.contains(word))
            .toList();
    for (var i = 0; i < Math.min(reach, next.size()); i++) {
      var word = next.get(i);
      if ((fulfilment && FULFILMENT_STEMS.contains(word))
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
   * The criterion holding the passage {@code match} quotes, of two words or more, or -1. A phrase
   * several criteria hold ("the Sign up button") goes to the one the clause's other {@code words}
   * share most with; with no clear one it names none of them.
   */
  private static int quotedCriterion(MatchResult match, Set<String> words, List<String> criteria) {
    var quote = normalize(match.group(1));
    if (quote.indexOf(' ') < 0) {
      return -1;
    }
    var holding =
        criteria.stream()
            .filter(c -> (" " + normalize(c) + " ").contains(" " + quote + " "))
            .toList();
    var best = clearBest(words, holding);
    return best >= 0 ? criteria.indexOf(holding.get(best)) : -1;
  }

  /** The criterion {@code words} share half the words of, or -1. */
  private static int sharedHalf(Set<String> words, List<String> criteria) {
    var at = bestMatch(words, criteria);
    return at >= 0 && shared(words, words(criteria.get(at))) * 2 >= words(criteria.get(at)).size()
        ? at
        : -1;
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
    // The words are counted on the clause as written; its negations are read with contractions
    // spelled out, so "won't lock" negates as "will not lock" does.
    var spelled = readable(clause);
    for (var pattern : negations) {
      var negation = pattern.matcher(spelled);
      while (negation.find()) {
        if (bindsCriterion(spelled.substring(negation.end()), criterionWords, 1, false)) {
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

  /**
   * The sentences of {@code overview}, using {@link SentenceBreaks} for abbreviations and never
   * ending inside a {@link #QUOTED} passage.
   */
  private static List<String> sentences(String overview) {
    if (overview == null) {
      return List.of();
    }
    var text = readable(overview);
    var quotes = quotes(text);
    var sentences = new ArrayList<String>();
    var from = 0;
    var end = SENTENCE_END.matcher(text);
    while (end.find()) {
      var at = end.start();
      if (SentenceBreaks.endsSentenceAt(text, at)
          && quotes.stream().noneMatch(q -> q.start() < at && at < q.end())) {
        sentences.add(text.substring(from, at));
        from = end.end();
      }
    }
    sentences.add(text.substring(from));
    return sentences;
  }

  /** The {@link #QUOTED} passages of {@code text}, in the order they start. */
  private static List<MatchResult> quotes(String text) {
    return QUOTED.stream()
        .flatMap(pattern -> pattern.matcher(text).results())
        .sorted(Comparator.comparingInt(MatchResult::start))
        .toList();
  }

  /**
   * {@code text} as every reading here takes it: curly quotes folded, and each contracted negation
   * spelled out ("can't" as "cannot", "won't" as "will not", "isn't" as "is not"), so the spelled
   * and the contracted forms read alike in every path.
   */
  static String readable(String text) {
    var folded = text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'');
    var spelled = CANT.matcher(folded).replaceAll("cannot");
    spelled = WONT.matcher(spelled).replaceAll("will not");
    return CONTRACTED.matcher(spelled).replaceAll(" not");
  }

  private static String normalize(String text) {
    return NON_ALPHANUMERIC
        .matcher(readable(text).toLowerCase(Locale.ROOT))
        .replaceAll(" ")
        .strip();
  }

  /** The stems of {@code words}, as {@link SummarySurfaceDeduplicator#claim} reads them. */
  private static Set<String> stems(List<String> words) {
    var stems = new LinkedHashSet<String>();
    for (var word : words) {
      stems.addAll(words(word));
    }
    return stems;
  }
}
