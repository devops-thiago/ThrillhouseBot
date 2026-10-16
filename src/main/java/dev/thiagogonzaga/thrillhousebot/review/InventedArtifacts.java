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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Cuts the overview sentences that introduce an artifact the change does not contain (#1090): on
 * ThrillhouseBot-test#250 the summary said the pull request adds "two docs files (CONFIG-ZIG.md and
 * a settings page)", and there is no settings page. "A settings page" is not path-shaped, so {@link
 * PathMentions} cannot reach it.
 *
 * <p>Only an artifact a sentence introduces is judged: an indefinite article or "new" before at
 * most two qualifier words and a kind ("a settings page", "a new /scan endpoint", "an orders
 * migration"). A changed file described by its role ("the config doc") is never judged. What backs
 * each kind:
 *
 * <ul>
 *   <li>a page or a screen: a changed file whose path holds a qualifier word ("SettingsPage.tsx",
 *       "docs/settings.md"), or an added line naming the qualifier and the kind together ("settings
 *       page", "SettingsScreen");
 *   <li>a migration: a changed path or an added line naming a migration;
 *   <li>an endpoint: an added line holding a qualifier word.
 * </ul>
 *
 * <p>A word of the qualifier matches a word of at least {@value #MIN_FAMILY_CHARS} letters it
 * starts, or that starts it ("config" and "configuration"). An artifact with no qualifier word ("a
 * page") is not judged. Only summary prose is cut; findings are never touched. This is a
 * deterministic check against the diff, not the opt-in summary verification call of #1078.
 */
final class InventedArtifacts {

  private InventedArtifacts() {}

  /** Letters a word needs before another word it starts is read as its family. */
  static final int MIN_FAMILY_CHARS = 4;

  /**
   * An introduced artifact: an article or "new", then one or two qualifier words (group 1) and the
   * kind (group 2). A word ends at a blank, a hyphen or an underscore, so "a settings-page", "a
   * settings_page" and "a self-service page" are read too. The qualifier words are matched
   * possessively one at a time, so a long run of words is not retried.
   */
  private static final Pattern INTRODUCED =
      Pattern.compile(
          "(?i)\\b(?:an?|new)\\s++((?:[\\p{L}\\p{N}/.]++[\\s_-]++){1,2}?)"
              + "(page|screen|migration|endpoint)s?\\b");

  /** Qualifier words that say nothing about what the artifact is. */
  private static final Set<String> VAGUE =
      Set.of(
          "new",
          "small",
          "single",
          "separate",
          "dedicated",
          "simple",
          "basic",
          "extra",
          "own",
          "second",
          "another",
          "other",
          "docs",
          "doc",
          "documentation",
          "api",
          "http",
          "https",
          "grpc",
          "graphql",
          "rest",
          "web");

  private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

  /**
   * A camel hump: a capital after a lower-case letter ("Settings|Page"), or the last capital of an
   * acronym before a lower-case letter ("SAML|Page", "HTML|Settings").
   */
  private static final Pattern CAMEL_HUMP =
      Pattern.compile("(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})");

  /**
   * A fence opening a line, after at most three blanks: a run of three or more backticks or tildes
   * (group 1), then the rest of the line (group 2). A block runs from such a line to the next line
   * holding only a run of the same character at least as long, or to the end of the text; a marker
   * inside a line opens nothing.
   */
  private static final Pattern FENCE_LINE = Pattern.compile("[ \t]{0,3}+(`{3,}+|~{3,}+)(.*+)");

  /** A blank line between paragraphs, with the blanks around it; LF or CRLF line ends. */
  private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\r?+\n[ \t]*+\r?+\n\\s*+");

  /** A file GitHub sends without a patch because it is binary: images, fonts, archives. */
  private static final Pattern BINARY =
      Pattern.compile("(?i)\\.(?:png|jpe?g|gif|ico|webp|pdf|woff2?|ttf|jar|zip|gz)$");

  /**
   * A negation in an artifact's clause: "does not add", "never ships", "without", "nor does it
   * add", "neither a … nor a …".
   */
  private static final Pattern NEGATION =
      Pattern.compile("(?i)\\b(?:not|cannot|never|without|nor|neither|no\\s++longer)\\b|n['’]t\\b");

  /**
   * A verb of taking something out: "removes", "deleted", "drops", "retires". A noun or adjective
   * made from one ("removal", "deletion", "dropout", "retirement", "dropdown", "removable") is not
   * a verb, and governs nothing.
   */
  private static final Pattern REMOVAL =
      Pattern.compile(
          "(?i)\\b(?:remov|delet|drop|retir)(?!(?:al|ion|out|e?ment|down|able|er)s?\\b)\\w*+(?!-)");

  /**
   * A conjunction that starts a new statement after a negated one: "ships without config and adds".
   * "Or" is not one: "does not bump v2 or add a …" negates both verbs.
   */
  private static final Pattern CONJUNCTION = Pattern.compile("(?i)\\b(?:and|then|plus)\\b");

  /**
   * An underscore that opens or closes emphasis ("a _settings page_"): one with no letter or digit
   * on one side. An underscore inside a word ("a settings_page") stays.
   */
  private static final Pattern UNDERSCORE_EMPHASIS =
      Pattern.compile("(?<![\\p{L}\\p{N}])_++|_++(?![\\p{L}\\p{N}])");

  /**
   * The forms of a verb of addition, which takes the artifact after it away from an earlier removal
   * verb. A word is one only in one of these forms and where a verb stands ({@link #inVerbPlace}),
   * so a noun or adjective sharing a stem ("the build script", "a shipment", "the creator",
   * "additional") never is.
   */
  private static final Set<String> ADDITION =
      Set.of(
          "add",
          "adds",
          "added",
          "adding",
          "introduce",
          "introduces",
          "introduced",
          "introducing",
          "create",
          "creates",
          "created",
          "creating",
          "implement",
          "implements",
          "implemented",
          "implementing",
          "ship",
          "ships",
          "shipped",
          "shipping",
          "build",
          "builds",
          "built",
          "building",
          "wire",
          "wires",
          "wired",
          "wiring");

  /** The bare forms of {@link #ADDITION}, which read as a noun as often as a verb after "and". */
  private static final Set<String> ADDITION_BARE =
      Set.of("add", "introduce", "create", "implement", "ship", "build", "wire");

  /** A word that opens a verb's object: an article, a determiner or a pronoun. */
  private static final Set<String> OBJECT_LEAD =
      Set.of(
          "a", "an", "the", "new", "its", "their", "his", "her", "our", "your", "this", "these",
          "that", "those", "some", "any", "one", "two", "three", "several", "more", "it", "them");

  /** A word a verb follows: a subject, an auxiliary, a modal, a negation or "to". */
  private static final Set<String> VERB_LEAD =
      Set.of(
          "it", "this", "that", "which", "who", "they", "we", "he", "she", "change", "to", "will",
          "would", "can", "could", "should", "may", "might", "must", "shall", "does", "do", "did",
          "doesn", "don", "didn", "t", "is", "are", "was", "were", "be", "been", "being", "has",
          "have", "had", "not", "never", "cannot", "nor", "also", "now", "yet", "still", "just",
          "only", "finally", "then");

  /** A word joining a second verb, which an inflected {@link #ADDITION} form follows as one. */
  private static final Set<String> COORDINATOR = Set.of("and", "or", "then", "plus", "but");

  /** A word, for reading where a verb stands. */
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]++");

  /**
   * A parenthetical of at most three words set off by a pair of commas or dashes ("does not,
   * however, add", "does not — in fact — add", "does not, in v2.0, add"), which does not end the
   * clause around it.
   */
  private static final Pattern PARENTHETICAL =
      Pattern.compile("([,—–])\\s*+(?:[\\p{L}\\p{N}'.]++\\s++){0,2}[\\p{L}\\p{N}'.]++\\s*+\\1");

  /** A contrast that ends a negation's reach: "does not touch billing but adds …". */
  private static final Pattern CONTRAST =
      Pattern.compile("(?i)\\b(?:but|however|instead|while|whereas|although|though)\\b");

  /** An "or" right before an addition verb, which carries the negation to it. */
  private static final Pattern OR_BEFORE = Pattern.compile("(?i)\\bor\\s*+$");

  /** Words at most between a negation and the addition verb it negates: "does not yet add". */
  static final int NEGATED_REACH = 2;

  /** What leads into examples after a clause break: "e.g.", "i.e.", "including", "such as". */
  private static final Pattern EXAMPLE_LEAD =
      Pattern.compile("(?i)\\s*+(?:e\\.g\\.|i\\.e\\.|including\\b|such\\s++as\\b)");

  /**
   * Where an artifact's clause starts, for {@link #REMOVAL}: punctuation or a dash. A comma or
   * colon between digits ("1,000", "10:30") ends nothing, nor does a period not followed by a blank
   * or the end ("v1.2") or one closing a common abbreviation ("e.g.", "i.e.", "etc.", "vs.").
   */
  private static final Pattern CLAUSE_END =
      Pattern.compile("[;!?—–]|(?<!\\d)[,:]|[,:](?!\\d)|\\.(?=\\s|\\z)");

  /** A break that may separate the items of a list rather than two clauses. */
  private static final String LIST_BREAKS = ",:—–";

  /** A form of "be" or "get" that opens a passive: "a … is added", "a … gets built". */
  private static final Set<String> PASSIVE_LEAD =
      Set.of(
          "is", "are", "was", "were", "be", "been", "being", "gets", "get", "got", "isn", "aren",
          "wasn", "weren");

  /** The past participles of {@link #ADDITION}, read after a {@link #PASSIVE_LEAD}. */
  private static final Set<String> ADDITION_PARTICIPLE =
      Set.of("added", "introduced", "created", "implemented", "shipped", "built", "wired");

  /** A word that negates a passive between its lead and its participle: "is not", "isn't". */
  private static final Set<String> PASSIVE_NEGATION = Set.of("not", "never", "t", "no");

  /** Words at most between a {@link #PASSIVE_LEAD} and its participle: "is now added". */
  private static final int PASSIVE_REACH = 2;

  /** What an artifact's own clause says after it: an addition, a denial of one, or neither. */
  private enum Passive {
    NONE,
    ADDED,
    DENIED
  }

  /** A common abbreviation a period closes, read just before that period's end. */
  private static final Pattern ABBREVIATION =
      Pattern.compile("(?i)\\b(?:e\\.g|i\\.e|etc|vs|cf)\\.\\z");

  /** Characters at most an {@link #ABBREVIATION} spans, its period included. */
  private static final int ABBREVIATION_CHARS = 4;

  /**
   * {@code summary} with every sentence of its purpose and assessment that introduces an artifact
   * {@code files} do not back cut; the same instance when nothing is cut, {@code null} stays {@code
   * null}.
   */
  static ReviewResponse.Summary screen(
      ReviewResponse.Summary summary, List<GitHubPullRequestClient.FileDiff> files) {
    if (summary == null || files.isEmpty()) {
      return summary;
    }
    var change = Change.of(files);
    var purpose = withoutInvented(summary.prPurpose(), change);
    var assessment = withoutInvented(summary.overallAssessment(), change);
    if (Objects.equals(purpose, summary.prPurpose())
        && Objects.equals(assessment, summary.overallAssessment())) {
      return summary;
    }
    return new ReviewResponse.Summary(
        summary.totalFindings(),
        summary.critical(),
        summary.high(),
        summary.medium(),
        summary.low(),
        assessment,
        purpose,
        summary.descriptionGaps(),
        summary.suggestedLabels(),
        summary.fileSummaries(),
        summary.walkthroughDiagram(),
        summary.addressedGaps());
  }

  /** {@code text} without its sentences introducing an artifact {@code change} does not back. */
  static String withoutInvented(String text, Change change) {
    if (text == null || text.isBlank()) {
      return text;
    }
    // A fenced block is kept as it is; only the prose around it is screened.
    var out = new StringBuilder();
    var fence = FENCE_LINE.matcher(text);
    var from = 0;
    var open = -1;
    String marker = null;
    var lineStart = 0;
    while (lineStart <= text.length()) {
      var lineEnd = text.indexOf('\n', lineStart);
      lineEnd = lineEnd < 0 ? text.length() : lineEnd;
      if (fence.region(lineStart, lineEnd).lookingAt()) {
        if (marker == null) {
          out.append(withoutInventedProse(text.substring(from, lineStart), change));
          open = lineStart;
          marker = fence.group(1);
        } else if (closes(fence.group(1), fence.group(2), marker)) {
          out.append(text, open, lineEnd);
          from = lineEnd;
          marker = null;
        }
      }
      lineStart = lineEnd + 1;
    }
    if (marker == null) {
      out.append(withoutInventedProse(text.substring(from), change));
    } else {
      out.append(text, open, text.length());
    }
    var result = out.toString();
    return result.equals(text) ? text : result;
  }

  /** Whether a fence line of {@code run} and {@code rest} closes a block {@code marker} opened. */
  private static boolean closes(String run, String rest, String marker) {
    return run.charAt(0) == marker.charAt(0) && run.length() >= marker.length() && rest.isBlank();
  }

  /** {@code prose}, holding no fence, without its sentences that invent an artifact. */
  private static String withoutInventedProse(String prose, Change change) {
    // Each paragraph is screened on its own, so a cut never joins two of them.
    var out = new StringBuilder();
    var breaks = PARAGRAPH_BREAK.matcher(prose);
    var from = 0;
    while (breaks.find()) {
      out.append(withoutInventedParagraph(prose.substring(from, breaks.start()), change))
          .append(breaks.group());
      from = breaks.end();
    }
    out.append(withoutInventedParagraph(prose.substring(from), change));
    return out.toString();
  }

  /** {@code paragraph} without its sentences that invent an artifact. */
  private static String withoutInventedParagraph(String paragraph, Change change) {
    var prose = paragraph;
    if (prose.isBlank()) {
      return prose;
    }
    var sentences = OverviewScreen.sentences(prose);
    var kept = sentences.stream().filter(sentence -> !invents(sentence, change)).toList();
    if (kept.size() == sentences.size()) {
      return prose;
    }
    // Keep the blanks that separated the prose from a fence around it.
    var lead = prose.substring(0, prose.length() - prose.stripLeading().length());
    var tail = prose.substring(prose.stripTrailing().length());
    return lead + String.join(" ", kept) + tail;
  }

  /**
   * Whether {@code sentence} introduces an artifact {@code change} does not back. An artifact a
   * removal verb governs, earlier in its clause ("removes a settings page"), is not judged: what
   * backs it is a deleted file, which never backs an addition; "adds a settings page and removes
   * the old one" is still judged. Nor is one a negation governs ("does not add a …"). A passive in
   * the artifact's own clause decides first: "… and a settings page is added" is judged whatever
   * came before, "a settings page is not added" never is. Camel humps are split first, so "a
   * SettingsScreen" reads as "a Settings Screen".
   *
   * <p>Scope: this reads one sentence at a time, in English, by word shapes rather than a parse. A
   * clause runs back from the artifact to the last break ({@link #CLAUSE_END}) that starts a new
   * clause; a comma, colon or dash before list items or bare coordinated verbs ("does not add: a …,
   * a …", "does not add, ship or modify a …") does not, and a parenthetical of at most three words
   * between a pair of them is skipped. A semicolon, a sentence end, "!" and "?" always end the
   * clause. It does not follow a relative clause ("a page that is added later"), a pronoun back to
   * an earlier sentence, a verb outside {@link #ADDITION} ("updates the docs, a …" continues the
   * clause), or a passive more than {@value #PASSIVE_REACH} words past its "is" or "gets".
   */
  static boolean invents(String sentence, Change change) {
    // A qualifier in a code span ("a `settings` page") or in asterisk or underscore emphasis ("a
    // **settings page**", "a _settings page_") is read as its words.
    var plain =
        UNDERSCORE_EMPHASIS.matcher(sentence.replace("`", "").replace("*", "")).replaceAll("");
    var text = CAMEL_HUMP.matcher(plain).replaceAll(" ");
    var introduced = INTRODUCED.matcher(text);
    while (introduced.find()) {
      var lead = text.substring(0, introduced.start());
      var passive = passiveAfter(text.substring(introduced.end()));
      // "does not add a settings page" says there is none, which is no invention.
      if (passive == Passive.DENIED
          || (passive == Passive.NONE && (removedInClause(lead) || negatedInClause(lead)))) {
        continue;
      }
      var qualifier =
          words(introduced.group(1)).stream().filter(word -> !VAGUE.contains(word)).toList();
      var kind = introduced.group(2).toLowerCase(Locale.ROOT);
      if (!qualifier.isEmpty() && !change.backs(qualifier, kind)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a {@link #REMOVAL} verb governs the artifact {@code lead} leads to: the verb is in the
   * artifact's clause, which punctuation or a dash ends, and no {@link #ADDITION} verb follows it
   * there. "removes a …" and "deletes the old flow and a …" are removals; "drops the old flow then
   * adds a …" and "removes cruft, a … is added" are not.
   */
  private static boolean removedInClause(String lead) {
    var clause = clauseOf(lead);
    var removal = REMOVAL.matcher(clause);
    var last = -1;
    while (removal.find()) {
      last = removal.end();
    }
    return last >= 0 && additionAt(clause, last) < 0;
  }

  /**
   * Whether a {@link #NEGATION} governs the artifact {@code lead} leads to: the negation is in the
   * artifact's clause and either no {@link #ADDITION} verb follows it ("ships without a …"), or the
   * first one follows within {@value #NEGATED_REACH} words, or right after "or", with no contrast
   * or conjunction between ("does not add a …", "doesn't yet ship a … or a …", "nor does it add a
   * …", "does not bump the v2.0 schema or add a …"). "does not touch billing but adds a …", "does
   * not touch billing and adds a …" and "ships without config and adds a …" are judged: the
   * conjunction starts a statement the negation does not reach.
   */
  private static boolean negatedInClause(String lead) {
    var clause = clauseOf(lead);
    var negation = NEGATION.matcher(clause);
    var last = -1;
    while (negation.find()) {
      last = negation.end();
    }
    if (last < 0) {
      return false;
    }
    var addition = additionAt(clause, last);
    if (addition < 0) {
      return true;
    }
    var rest = clause.substring(last);
    var between = clause.substring(last, addition);
    return !CONTRAST.matcher(rest).find()
        && !CONJUNCTION.matcher(between).find()
        && (words(between).size() <= NEGATED_REACH || OR_BEFORE.matcher(between).find());
  }

  /**
   * Where the first {@link #ADDITION} verb in {@code text} at or after {@code from} starts; -1 with
   * none. The words before {@code from} still tell where a verb stands. Callers start after a
   * removal or negation word, so the first word of the text is never read as the verb.
   */
  private static int additionAt(String text, int from) {
    var words = new ArrayList<String>();
    var starts = new ArrayList<Integer>();
    var word = WORD.matcher(text);
    while (word.find()) {
      words.add(word.group().toLowerCase(Locale.ROOT));
      starts.add(word.start());
    }
    for (var i = 1; i < words.size(); i++) {
      if (starts.get(i) >= from && ADDITION.contains(words.get(i)) && inVerbPlace(words, i)) {
        return starts.get(i);
      }
    }
    return -1;
  }

  /**
   * Whether the {@link #ADDITION} form at {@code i} stands where a verb does: before its object
   * ("adds a", "ships the"; the artifact itself follows the last word), after a subject, an
   * auxiliary, a modal, a negation or "to" ("it adds", "is building", "to build"), or inflected
   * after a coordinator ("and added tests"). "the old build script" and "nightly builds and" are
   * nouns.
   */
  private static boolean inVerbPlace(List<String> words, int i) {
    // i is at least 1: additionAt never reads the first word.
    if (i == words.size() - 1 || OBJECT_LEAD.contains(words.get(i + 1))) {
      return true;
    }
    var before = words.get(i - 1);
    return VERB_LEAD.contains(before)
        || (COORDINATOR.contains(before) && !ADDITION_BARE.contains(words.get(i)));
  }

  /**
   * The clause {@code text} ends in: what follows its last break that starts a new clause. A break
   * that leads into examples of what came before ("removes legacy pages, e.g. a …") does not, nor
   * does a comma, colon or dash whose segment, up to the next break, holds no clause of its own
   * ({@link #opensClause}): list items and bare coordinated verbs continue the clause before them.
   */
  private static String clauseOf(String text) {
    var lead = PARENTHETICAL.matcher(text).replaceAll(" ");
    var breaks = CLAUSE_END.matcher(lead);
    var ends = new ArrayList<Integer>();
    var listBreak = new ArrayList<Boolean>();
    while (breaks.find()) {
      ends.add(breaks.end());
      listBreak.add(LIST_BREAKS.indexOf(breaks.group().charAt(0)) >= 0);
    }
    var example = EXAMPLE_LEAD.matcher(lead);
    var abbreviation = ABBREVIATION.matcher(lead).useTransparentBounds(true);
    var start = 0;
    for (var i = 0; i < ends.size(); i++) {
      var end = ends.get(i);
      var segmentEnd = i + 1 < ends.size() ? ends.get(i + 1) - 1 : lead.length();
      var abbreviated = abbreviation.region(Math.max(0, end - ABBREVIATION_CHARS - 1), end).find();
      var continues =
          abbreviated
              || example.region(end, lead.length()).lookingAt()
              || (listBreak.get(i) && !opensClause(lead, end, segmentEnd));
      if (!continues) {
        start = end;
      }
    }
    return lead.substring(start);
  }

  /**
   * Whether {@code text} between {@code from} and {@code to} holds a clause of its own: a negation,
   * a removal verb, an {@link #ADDITION} verb where a verb stands, or a subject or auxiliary word
   * ({@link #VERB_LEAD}, "it", "is", "then").
   */
  private static boolean opensClause(String text, int from, int to) {
    var segment = text.substring(from, to);
    if (NEGATION.matcher(segment).find() || REMOVAL.matcher(segment).find()) {
      return true;
    }
    var addition = additionAt(text, from);
    if (addition >= 0 && addition < to) {
      return true;
    }
    var word = WORD.matcher(segment);
    while (word.find()) {
      if (VERB_LEAD.contains(word.group().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  /**
   * What the artifact's own clause, {@code trail} up to its first break, says about it: {@link
   * Passive#ADDED} for a passive addition ("is added", "gets built", "is now introduced"), {@link
   * Passive#DENIED} for a negated one ("is not added", "isn't built", "was never shipped").
   */
  private static Passive passiveAfter(String trail) {
    var breaks = CLAUSE_END.matcher(trail);
    var clause = breaks.find() ? trail.substring(0, breaks.start()) : trail;
    var words = new ArrayList<String>();
    var word = WORD.matcher(clause);
    while (word.find()) {
      words.add(word.group().toLowerCase(Locale.ROOT));
    }
    for (var i = 0; i < words.size(); i++) {
      var passive = PASSIVE_LEAD.contains(words.get(i)) ? passiveFrom(words, i) : Passive.NONE;
      if (passive != Passive.NONE) {
        return passive;
      }
    }
    return Passive.NONE;
  }

  /**
   * The passive the {@link #PASSIVE_LEAD} at {@code lead} opens, within {@value #PASSIVE_REACH}.
   */
  private static Passive passiveFrom(List<String> words, int lead) {
    var last = Math.min(words.size() - 1, lead + PASSIVE_REACH + 1);
    for (var j = lead + 1; j <= last; j++) {
      if (ADDITION_PARTICIPLE.contains(words.get(j))) {
        return words.subList(lead + 1, j).stream().anyMatch(PASSIVE_NEGATION::contains)
            ? Passive.DENIED
            : Passive.ADDED;
      }
    }
    return Passive.NONE;
  }

  /**
   * What the change holds, as words: those of each changed path, and those of each added line.
   *
   * @param pathWords the words of each changed path, camel humps split
   * @param addedLines the words of each added line, camel humps split, in order
   * @param linesComplete whether every added file's lines are known: false when GitHub sent no
   *     patch for a text file that adds lines
   */
  record Change(
      List<List<String>> pathWords, List<List<String>> addedLines, boolean linesComplete) {

    static Change of(List<GitHubPullRequestClient.FileDiff> files) {
      var paths = new ArrayList<List<String>>();
      var added = new ArrayList<List<String>>();
      var complete = true;
      for (var file : files) {
        // A deleted file is not something the change adds.
        if (!"removed".equals(file.status())) {
          paths.add(words(file.filename()));
          // GitHub sends no patch for an oversized file: its added lines are unknown. A binary
          // file has none to know.
          complete &=
              file.patch() != null
                  || file.additions() == 0
                  || BINARY.matcher(file.filename()).find();
        }
        var patch = Objects.requireNonNullElse(file.patch(), "");
        for (var line : patch.split("\n", -1)) {
          if (line.startsWith("+")) {
            added.add(words(line.substring(1)));
          }
        }
      }
      return new Change(paths, added, complete);
    }

    /**
     * Whether the change backs an artifact of {@code kind} that {@code qualifier} names. With an
     * added file whose lines are unknown, a page, screen or endpoint counts as backed; a migration
     * is still judged, since a changed path names it as often as a line does.
     */
    boolean backs(List<String> qualifier, String kind) {
      if (!linesComplete && !"migration".equals(kind)) {
        return true;
      }
      return switch (kind) {
        case "migration" ->
            Stream.concat(pathWords.stream(), addedLines.stream())
                .anyMatch(words -> holdsFamily(words, "migrat"));
        case "endpoint" ->
            addedLines.stream()
                .anyMatch(words -> qualifier.stream().anyMatch(q -> holdsFamily(words, q)));
        default ->
            pathWords.stream()
                    .anyMatch(words -> qualifier.stream().anyMatch(q -> holdsFamily(words, q)))
                || addedLines.stream()
                    .anyMatch(
                        words ->
                            holdsFamily(words, kind)
                                && qualifier.stream().anyMatch(q -> holdsFamily(words, q)));
      };
    }

    private static boolean holdsFamily(List<String> words, String wanted) {
      return words.stream().anyMatch(word -> family(word, wanted));
    }
  }

  /** Whether two lower-case words are one, or one of them starts the other. */
  static boolean family(String word, String other) {
    if (word.equals(other)) {
      return true;
    }
    var wordFirst = word.length() <= other.length();
    var shorter = wordFirst ? word : other;
    var longer = wordFirst ? other : word;
    return shorter.length() >= MIN_FAMILY_CHARS && longer.startsWith(shorter);
  }

  /** The lower-case words of {@code text}, camel humps split ("SettingsPage" is two). */
  static List<String> words(String text) {
    return Arrays.stream(WORD_SPLIT.split(CAMEL_HUMP.matcher(text).replaceAll(" ")))
        .filter(word -> !word.isEmpty())
        .map(word -> word.toLowerCase(Locale.ROOT))
        .toList();
  }
}
