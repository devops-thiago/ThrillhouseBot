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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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
 *   <li>a sentence of the overview that cites the issue, says it is not met ({@link #UNMET}) and
 *       quotes a passage of the criterion, or shares half its content words;
 *   <li>a negated clause of a walkthrough row ("no lockout for imminent shifts") whose content
 *       words, at least {@value #MIN_SHARED} and more than half of them, are the criterion's.
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

  /** The linked issue or its criteria named in a text. One pattern per shape. */
  private static final List<Pattern> CITES_ISSUE =
      List.of(
          Pattern.compile("(?i)\\blinked\\s++issues?+\\b"),
          Pattern.compile("(?i)\\bacceptance\\s++criteri\\w*+"),
          Pattern.compile("(?i)\\bissue\\s*+#\\d++\\b"),
          Pattern.compile("(?i)\\bthe\\s++issue(?:['’]s)?+\\s++(?:requirement|criteri)\\w*+"),
          Pattern.compile("(?i)\\bthe\\s++issue['’]s\\b"));

  /** An overview sentence saying the issue's ask is not met. */
  private static final Pattern UNMET =
      Pattern.compile(
          "(?i)\\b(?:not|never|no|opposite|contrary|contradicts?|violates?|unmet|missing|instead"
              + "|however|but|without|lacks?|fails?)\\b");

  /** Where an overview sentence ends. */
  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s++");

  /** Where a walkthrough row's clauses split. */
  private static final Pattern CLAUSE_SPLIT =
      Pattern.compile("[;.()]|,\\s++|\\s++(?:and|or|but)\\s++");

  /** A clause opening with a negation: "no lockout for …", "without a cancel path". */
  private static final Pattern NEGATED_CLAUSE =
      Pattern.compile("(?i)^\\s*+(?:no|not|never|without|missing|lacks?)\\s");

  /** A quoted passage, in double or single quotes (curly quotes folded). */
  private static final Pattern QUOTED =
      Pattern.compile("\"([^\"\n]++)\"|(?<![\\p{L}\\p{N}])'([^'\n]++)'(?![\\p{L}\\p{N}])");

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
    var key = keys.getFirst();
    var criteria = criteria(linkedIssues);
    var lines = new LinkedHashMap<Integer, String>();
    var pointed = new LinkedHashSet<Finding>();
    for (var finding : findings) {
      var text = findingText(finding);
      if (!citesIssue(text)) {
        continue;
      }
      var at = bestMatch(words(text), criteria);
      if (at >= 0 && !lines.containsKey(at) && !named(criteria.get(at), gaps)) {
        lines.put(
            at,
            line(key, criteria.get(at))
                + FINDING_SEPARATOR
                + PrSummaryGenerator.findingBullet(finding)
                    .substring(PrSummaryGenerator.GAP_FINDING_PREFIX.length()));
        pointed.add(finding);
      }
    }
    for (var sentence : sentences(overview)) {
      var at = overviewMatch(sentence, criteria);
      if (at >= 0 && !lines.containsKey(at) && !named(criteria.get(at), gaps)) {
        lines.put(at, line(key, criteria.get(at)) + " — the overview above reports it unmet");
      }
    }
    for (var row : new TreeMap<>(fileSummaries).entrySet()) {
      for (var clause : CLAUSE_SPLIT.split(row.getValue())) {
        var at = clauseMatch(clause, criteria);
        if (at >= 0 && !lines.containsKey(at) && !named(criteria.get(at), gaps)) {
          lines.put(
              at,
              line(key, criteria.get(at))
                  + " — `"
                  + MarkdownSafe.inlineCode(row.getKey())
                  + "`: "
                  + clause.strip());
        }
      }
    }
    var ordered = new TreeMap<>(lines);
    return new Lines(List.copyOf(ordered.values()), pointed);
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
        criteria.add(line.substring(2).strip());
      } else if (listing) {
        listing = false;
      }
    }
    return criteria;
  }

  private static String line(String key, String criterion) {
    return "Linked issue " + key + ": " + MarkdownSafe.oneLine(criterion);
  }

  /** Whether a {@code Linked issue} gap of {@code gaps} names {@code criterion}. */
  private static boolean named(String criterion, List<String> gaps) {
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

  /** The criterion sharing the most words with {@code words}, at least {@value #MIN_SHARED}. */
  private static int bestMatch(Set<String> words, List<String> criteria) {
    var best = -1;
    var bestShared = MIN_SHARED - 1;
    for (var i = 0; i < criteria.size(); i++) {
      var shared = shared(words, words(criteria.get(i)));
      if (shared > bestShared) {
        best = i;
        bestShared = shared;
      }
    }
    return best;
  }

  /**
   * The criterion an overview sentence states unmet: the sentence cites the issue and says it is
   * not met, then quotes a passage of the criterion or shares half its words.
   */
  private static int overviewMatch(String sentence, List<String> criteria) {
    if (!citesIssue(sentence) || !UNMET.matcher(sentence).find()) {
      return -1;
    }
    var quotes = QUOTED.matcher(foldQuotes(sentence));
    while (quotes.find()) {
      var quote = normalize(quotes.group(quotes.group(1) != null ? 1 : 2));
      for (var i = 0; quote.indexOf(' ') > 0 && i < criteria.size(); i++) {
        if ((" " + normalize(criteria.get(i)) + " ").contains(" " + quote + " ")) {
          return i;
        }
      }
    }
    var words = words(sentence);
    var at = bestMatch(words, criteria);
    return at >= 0 && shared(words, words(criteria.get(at))) * 2 >= words(criteria.get(at)).size()
        ? at
        : -1;
  }

  /**
   * The criterion a negated walkthrough clause states unmet: at least {@value #MIN_SHARED} of the
   * clause's words, and more than half of them, are the criterion's.
   */
  private static int clauseMatch(String clause, List<String> criteria) {
    if (!NEGATED_CLAUSE.matcher(clause).find()) {
      return -1;
    }
    var words = words(clause);
    var at = bestMatch(words, criteria);
    return at >= 0 && shared(words, words(criteria.get(at))) * 2 > words.size() ? at : -1;
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

  private static String foldQuotes(String text) {
    return text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'');
  }

  private static String normalize(String text) {
    return NON_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }
}
