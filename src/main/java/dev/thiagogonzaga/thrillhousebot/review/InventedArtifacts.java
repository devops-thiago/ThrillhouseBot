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
   * A fenced block, closed by the marker that opened it; an unclosed one runs to the end of the
   * text.
   */
  private static final Pattern FENCED = Pattern.compile("(?s)(```|~~~)(?:(?!\\1).)*+(?:\\1|\\z)");

  /** A blank line between paragraphs, with the blanks around it. */
  private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\n[ \t]*+\n\\s*+");

  /** A file GitHub sends without a patch because it is binary: images, fonts, archives. */
  private static final Pattern BINARY =
      Pattern.compile("(?i)\\.(?:png|jpe?g|gif|ico|webp|pdf|woff2?|ttf|jar|zip|gz)$");

  /** A negation in an artifact's clause: "does not add", "never ships", "without". */
  private static final Pattern NEGATION =
      Pattern.compile("(?i)\\b(?:not|never|without|no\\s++longer)\\b|n['’]t\\b");

  /** A sentence about taking something out: "removes", "deleted", "drops", "retires". */
  private static final Pattern REMOVAL =
      Pattern.compile("(?i)\\b(?:remov|delet|drop|retir)\\w*+(?!-)");

  /** A verb of addition, which takes the artifact after it away from an earlier removal verb. */
  private static final Pattern ADDITION =
      Pattern.compile("(?i)\\b(?:add|introduc|creat|implement|ship|build|wire)\\w*+");

  /** A contrast that ends a negation's reach: "does not touch billing but adds …". */
  private static final Pattern CONTRAST =
      Pattern.compile("(?i)\\b(?:but|however|instead|while|whereas|although|though)\\b");

  /** Words at most between a negation and the addition verb it negates: "does not yet add". */
  static final int NEGATED_REACH = 2;

  /** Where an artifact's clause starts, for {@link #REMOVAL}: punctuation or a dash. */
  private static final Pattern CLAUSE_END = Pattern.compile("[,;:.!?—–]");

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
    var fences = FENCED.matcher(text);
    var from = 0;
    while (fences.find()) {
      out.append(withoutInventedProse(text.substring(from, fences.start()), change))
          .append(fences.group());
      from = fences.end();
    }
    out.append(withoutInventedProse(text.substring(from), change));
    var result = out.toString();
    return result.equals(text) ? text : result;
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
   * the old one" is still judged. Camel humps are split first, so "a SettingsScreen" reads as "a
   * Settings Screen".
   */
  static boolean invents(String sentence, Change change) {
    // A qualifier in a code span ("a `settings` page") is read as its words.
    var text = CAMEL_HUMP.matcher(sentence.replace("`", "")).replaceAll(" ");
    var introduced = INTRODUCED.matcher(text);
    while (introduced.find()) {
      var lead = text.substring(0, introduced.start());
      // "does not add a settings page" says there is none, which is no invention.
      if (removedInClause(lead) || negatedInClause(lead)) {
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
    return last >= 0 && !ADDITION.matcher(clause.substring(last)).find();
  }

  /**
   * Whether a {@link #NEGATION} governs the artifact {@code lead} leads to: the negation is in the
   * artifact's clause and either no {@link #ADDITION} verb follows it ("ships without a …"), or the
   * first one follows within {@value #NEGATED_REACH} words with no contrast between ("does not add
   * a …", "doesn't yet ship a … or a …"). "does not touch billing but adds a …" and "does not touch
   * billing and adds a …" are judged.
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
    var rest = clause.substring(last);
    var addition = ADDITION.matcher(rest);
    if (!addition.find()) {
      return true;
    }
    return !CONTRAST.matcher(rest).find()
        && words(rest.substring(0, addition.start())).size() <= NEGATED_REACH;
  }

  /** The clause {@code lead} ends in: what follows its last punctuation or dash. */
  private static String clauseOf(String lead) {
    var breaks = CLAUSE_END.matcher(lead);
    var start = 0;
    while (breaks.find()) {
      start = breaks.end();
    }
    return lead.substring(start);
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
