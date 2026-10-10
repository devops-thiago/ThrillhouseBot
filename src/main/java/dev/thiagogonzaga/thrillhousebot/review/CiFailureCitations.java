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
 * words name that check, a failure and the CI that reported it; naming a check the section does not
 * record cites nothing, so a finding cannot buy the floor by saying "CI fails".
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

  /** ... and that CI reported it. */
  private static final List<String> CI_WORDS =
      phrases("ci", "check", "checks", "conclusion", "workflow", "job", "pipeline");

  private static final Pattern NOT_A_WORD = Pattern.compile("[^a-z0-9]+");

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
    String text =
        normalized(
            (finding.title() == null ? "" : finding.title())
                + "\n"
                + (finding.description() == null ? "" : finding.description()));
    if (!states(text, FAILURE_WORDS) || !states(text, CI_WORDS)) {
      return List.of();
    }
    return checks.stream().filter(check -> text.contains(check.words())).map(Check::name).toList();
  }

  private static boolean states(String normalized, List<String> words) {
    return words.stream().anyMatch(normalized::contains);
  }

  private static List<String> phrases(String... words) {
    return Arrays.stream(words).map(word -> " " + word + " ").toList();
  }

  /** Lower-case words separated by single spaces and padded, so a phrase matches whole words. */
  private static String normalized(String text) {
    String words = NOT_A_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    return words.isEmpty() ? "" : " " + words + " ";
  }
}
