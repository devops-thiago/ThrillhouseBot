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
          "rest",
          "web");

  private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

  private static final Pattern CAMEL_HUMP = Pattern.compile("(?<=\\p{Ll})(?=\\p{Lu})");

  /** A sentence about taking something out: "removes", "deleted", "drops", "retires". */
  private static final Pattern REMOVAL = Pattern.compile("(?i)\\b(?:remov|delet|drop|retir)\\w*+");

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
    if (text == null || text.isBlank() || text.contains("```") || text.contains("~~~")) {
      return text;
    }
    var sentences = OverviewScreen.sentences(text);
    var kept = sentences.stream().filter(sentence -> !invents(sentence, change)).toList();
    return kept.size() == sentences.size() ? text : String.join(" ", kept);
  }

  /**
   * Whether {@code sentence} introduces an artifact {@code change} does not back. A sentence about
   * taking something out ("removes a settings page") is not judged: what backs it is a deleted
   * file, which never backs an addition. Camel humps are split first, so "a SettingsScreen" reads
   * as "a Settings Screen".
   */
  static boolean invents(String sentence, Change change) {
    if (REMOVAL.matcher(sentence).find()) {
      return false;
    }
    var introduced = INTRODUCED.matcher(CAMEL_HUMP.matcher(sentence).replaceAll(" "));
    while (introduced.find()) {
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
   * What the change holds, as words: those of each changed path, and those of each added line.
   *
   * @param pathWords the words of each changed path, camel humps split
   * @param addedLines the words of each added line, camel humps split, in order
   */
  record Change(List<List<String>> pathWords, List<List<String>> addedLines) {

    static Change of(List<GitHubPullRequestClient.FileDiff> files) {
      var paths = new ArrayList<List<String>>();
      var added = new ArrayList<List<String>>();
      for (var file : files) {
        // A deleted file is not something the change adds.
        if (!"removed".equals(file.status())) {
          paths.add(words(file.filename()));
        }
        var patch = Objects.requireNonNullElse(file.patch(), "");
        for (var line : patch.split("\n", -1)) {
          if (line.startsWith("+")) {
            added.add(words(line.substring(1)));
          }
        }
      }
      return new Change(paths, added);
    }

    /** Whether the change backs an artifact of {@code kind} that {@code qualifier} names. */
    boolean backs(List<String> qualifier, String kind) {
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
