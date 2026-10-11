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

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The checks a review's CI-failure section records as failed on the head commit, and which of the
 * review's findings cite one of them (#952).
 *
 * <p>The review call is given that section and the verifier is not, so a finding tied to a failing
 * test reached the verifier as a claim that a test fails, which its prompt treats as unsettled
 * arithmetic: two HIGH off-by-ones the failing test confirmed were posted as low-confidence items.
 * A finding that cites a failing check therefore carries a note naming it, through {@link
 * ContextEvidenceResolver}, and {@link FindingVerificationService#floorEstablishedConfidence} keeps
 * it off confidence "low".
 *
 * <p>The section is read back from its rendered text, as {@code CiFailureContextResolver} writes
 * it, because what counts is what the reviewer was shown. A finding cites a check when its own
 * words say something failed and name that check AS a check: quoted ({@code "test"}), or beside the
 * word that makes it one ({@code test check}, {@code test (conclusion: failure)}, {@code the
 * unit-tests job}). A bare co-occurrence is not a citation: a check named {@code test} would
 * otherwise be cited by any finding that says a test would fail in CI. Naming a check the section
 * does not record cites nothing, so a finding cannot buy the floor by saying "CI fails".
 */
final class CiFailureCitations implements FindingVerificationService.FailingChecks {

  /** A review whose CI-failure section was empty or absent. */
  static final CiFailureCitations NONE = new CiFailureCitations(List.of());

  /** The heading each detailed check opens with, as {@code CiFailureContextResolver} renders it. */
  private static final String CHECK_HEADING = "### ";

  /** What follows a detailed check's name on its heading line. */
  private static final String CONCLUSION = " (conclusion: ";

  /** The line naming the failing checks the section did not detail. */
  private static final String NOT_DETAILED = "Also failing, not detailed: ";

  /** Characters of a check name repeated in a note; names are output the pull request controls. */
  static final int MAX_NAME_CHARS = 120;

  /** A finding that cites a check says it failed. */
  private static final List<String> FAILURE_WORDS =
      phrases("fail", "fails", "failed", "failing", "failure", "failures");

  /**
   * What a finding says when it mentions a failure without tying itself to it: the failure denied
   * ("does not fail") or set aside ("unrelated to the failing check"). Either one in the finding
   * withdraws the citation; under-citing only leaves the finding where it was.
   */
  private static final List<String> DISCLAIMERS =
      phrases(
          "not fail",
          "does not fail",
          "doesnt fail",
          "dont fail",
          "wont fail",
          "isnt failing",
          "arent failing",
          "never fails",
          "no failure",
          "unrelated to",
          "not related to",
          "not caused by",
          "not the cause");

  /** The words that make a name next to them the name of a check. */
  private static final List<String> CHECK_WORDS =
      List.of("check", "checks", "conclusion", "job", "workflow");

  /** The quote pairs a finding names a check between, opening mark first. */
  private static final List<String> QUOTES = List.of("\"\"", "``", "''", "\u201C\u201D");

  private static final Pattern NOT_A_WORD = Pattern.compile("[^a-z0-9]+");

  /** The apostrophes a contraction is written with, dropped so "doesn't" reads as "doesnt". */
  private static final Pattern APOSTROPHE = Pattern.compile("['\u2019]");

  /** Each failing check: its name as rendered, and as the padded words a finding is matched on. */
  private record Check(String name, String words) {}

  private final List<Check> checks;

  private CiFailureCitations(List<Check> checks) {
    this.checks = checks;
  }

  /** The failing checks the rendered section records; {@link #NONE} for a blank section. */
  static CiFailureCitations parse(String section) {
    if (section == null || section.isBlank()) {
      return NONE;
    }
    var names = new ArrayList<String>();
    for (String line : section.split("\n")) {
      int conclusion = line.lastIndexOf(CONCLUSION);
      if (line.startsWith(CHECK_HEADING) && conclusion > CHECK_HEADING.length()) {
        names.add(line.substring(CHECK_HEADING.length(), conclusion));
      } else if (line.startsWith(NOT_DETAILED)) {
        names.addAll(Arrays.asList(line.substring(NOT_DETAILED.length()).split(", ")));
      }
    }
    var checks =
        names.stream()
            .map(String::strip)
            .filter(name -> !normalized(name).isBlank())
            .map(name -> new Check(name, normalized(name)))
            .toList();
    return checks.isEmpty() ? NONE : new CiFailureCitations(checks);
  }

  @Override
  public boolean cites(ReviewResponse.Finding finding) {
    return !citedChecks(finding).isEmpty();
  }

  /**
   * The note attached to a finding that cites a failing check, or {@code null} when it cites none.
   * It states what the section records and nothing more: the check failed on this commit, which is
   * CI's own measurement, while whether the flagged line is what fails it stays the verifier's
   * call.
   */
  String noteFor(ReviewResponse.Finding finding) {
    var cited = citedChecks(finding);
    if (cited.isEmpty()) {
      return null;
    }
    var names =
        String.join(
            ", ",
            cited.stream()
                .map(name -> "\"" + CiFailureContextResolver.clip(name, MAX_NAME_CHARS) + "\"")
                .toList());
    return "This review's CI context records "
        + names
        + " as completed without passing on this commit, and the finding cites it. That outcome"
        + " is CI's own measurement, not a count anyone made: it settles that the check fails."
        + " Whether the flagged line is what makes it fail is judged on the code.";
  }

  private List<String> citedChecks(ReviewResponse.Finding finding) {
    if (checks.isEmpty()) {
      return List.of();
    }
    String raw =
        ((finding.title() == null ? "" : finding.title())
                + "\n"
                + (finding.description() == null ? "" : finding.description()))
            .toLowerCase(Locale.ROOT);
    String text = normalized(raw);
    if (!states(text, FAILURE_WORDS) || states(text, DISCLAIMERS)) {
      return List.of();
    }
    return checks.stream()
        .filter(check -> namesAsACheck(check, raw, text))
        .map(Check::name)
        .toList();
  }

  /** Whether the finding names this check quoted, or beside a word that makes it a check. */
  private static boolean namesAsACheck(Check check, String raw, String text) {
    String name = check.name().toLowerCase(Locale.ROOT);
    return QUOTES.stream().anyMatch(pair -> raw.contains(pair.charAt(0) + name + pair.charAt(1)))
        || besideACheckWord(check.words().strip(), text)
        || namesEveryWordOfACompoundName(check.words().strip(), text);
  }

  private static boolean besideACheckWord(String core, String text) {
    return CHECK_WORDS.stream()
        .anyMatch(
            word ->
                text.contains(" " + core + " " + word + " ")
                    || text.contains(" " + word + " " + core + " "));
  }

  /**
   * A name of several words ({@code lint / eslint}) said in the finding's own order with other
   * words between ("the lint and eslint checks fail"): every word of the name is in the text, and
   * its last word stands beside a check word.
   */
  private static boolean namesEveryWordOfACompoundName(String core, String text) {
    String[] words = core.split(" ");
    return words.length > 1
        && Arrays.stream(words).allMatch(word -> text.contains(" " + word + " "))
        && besideACheckWord(words[words.length - 1], text);
  }

  private static boolean states(String normalized, List<String> words) {
    return words.stream().anyMatch(normalized::contains);
  }

  private static List<String> phrases(String... words) {
    return Arrays.stream(words).map(word -> " " + word + " ").toList();
  }

  /** Lower-case words separated by single spaces and padded, so a phrase matches whole words. */
  private static String normalized(String text) {
    String contracted = APOSTROPHE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    String words = NOT_A_WORD.matcher(contracted).replaceAll(" ").strip();
    return words.isEmpty() ? "" : " " + words + " ";
  }
}
