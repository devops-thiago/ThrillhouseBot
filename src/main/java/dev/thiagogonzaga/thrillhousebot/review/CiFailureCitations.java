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
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 *
 * <p>A finding also cites a check through a failing test its job log names (#1075): the section
 * lists the runner's line for each one ("✖ returns exactly `slots` applications (1.5ms)", "[ERROR]
 * WaitlistServiceTest.topCandidatesReturnsAtMostLimit:67 expected: &lt;2&gt;"), and a finding that
 * names the test without the check is tied to the same measured failure. Only a name distinctive
 * enough to be that test counts: a phrase of several words or a compound identifier, never one
 * plain word. The same reading decides the summary's sentence on each failing check ({@link
 * #overviewSentences}), so that sentence and the floor cannot disagree about which finding explains
 * a failure.
 */
final class CiFailureCitations implements FindingVerificationService.FailingChecks {

  /** A review whose CI-failure section was empty or absent. */
  static final CiFailureCitations NONE = new CiFailureCitations(List.of());

  /** The heading each detailed check opens with, as {@code CiFailureContextResolver} renders it. */
  private static final String CHECK_HEADING = CiFailureContextResolver.CHECK_HEADING;

  /** What follows a detailed check's name on its heading line. */
  private static final String CONCLUSION = CiFailureContextResolver.CONCLUSION;

  /** The line naming the failing checks the section did not detail. */
  private static final String NOT_DETAILED = CiFailureContextResolver.NOT_DETAILED;

  /** The prefix of each check named under {@link #NOT_DETAILED}, and of each failing test. */
  private static final String NAMED_ONLY = CiFailureContextResolver.NAMED_ONLY;

  /** The line a detailed check's failing tests are listed under. */
  private static final String FAILING_TESTS = CiFailureContextResolver.FAILING_TESTS;

  /** Characters of a check name repeated in a note; names are output the pull request controls. */
  static final int MAX_NAME_CHARS = 120;

  /** Characters of a test name's words below which the name is too common to cite a test by. */
  static final int MIN_TEST_NAME_CHARS = 8;

  /** Failing tests named per check in the summary's sentence; the rest are counted. */
  static final int MAX_TESTS_NAMED = 2;

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
  private static final List<String> QUOTES = List.of("\"\"", "``", "''", "“”");

  private static final Pattern NOT_A_WORD = Pattern.compile("[^a-z0-9]+");

  /** The apostrophes a contraction is written with, dropped so "doesn't" reads as "doesnt". */
  private static final Pattern APOSTROPHE = Pattern.compile("['’]");

  /**
   * What a runner prints before a failed test's name: a status mark or word, a counter, a logger's
   * level. Anchored and possessive, so no line the pull request's code prints can make it
   * backtrack.
   */
  private static final Pattern TEST_LINE_LEAD =
      Pattern.compile(
          "^(?:\\[ERROR]\\s++|[\u2716\u2715\u00D7\u25CF] |--- FAIL: |not ok \\d++(?: - )?+"
              + "|(?:FAILED|ERROR|Failed) |(?:FAIL|ERROR): |==> X |- |test |\\d++/\\d++ "
              + "|Test \\[\\d++/\\d++] |\\d++ - )");

  /** What a runner prints after a failed test's name: a duration, a status, an assertion. */
  private static final List<String> TEST_LINE_TAILS =
      List.of(" (", " [", "...", " <<<", " -- ", " - ", " FAILED", " *** ", "  ");

  /** The separators between a test's suites and its own name: Jest, Gradle, pytest and cargo. */
  private static final Pattern TEST_PATH_SEPARATOR = Pattern.compile(" \u203A | > |::");

  /** A {@code file:line} or {@code Class.method:line} position ending a test's token. */
  private static final Pattern LINE_POSITION = Pattern.compile(":\\d");

  /** A failing test the job log names: its name as read, and as the padded words matched on. */
  private record FailingTest(String name, String words) {}

  /**
   * Each failing check: its name as rendered, as the padded words a finding is matched on, and the
   * failing tests its job log names.
   */
  private record Check(String name, String words, List<FailingTest> tests) {}

  /** What a finding cites: a check by its name ({@code test} null), or by one of its tests. */
  private record Citation(Check check, FailingTest test) {}

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
    var testLines = new ArrayList<List<String>>();
    var namedOnly = false;
    var underTests = false;
    for (String line : section.split("\n")) {
      int conclusion = line.lastIndexOf(CONCLUSION);
      if (line.startsWith(CHECK_HEADING) && conclusion > CHECK_HEADING.length()) {
        names.add(line.substring(CHECK_HEADING.length(), conclusion));
        testLines.add(new ArrayList<>());
        namedOnly = false;
        underTests = false;
      } else if (line.equals(NOT_DETAILED)) {
        namedOnly = true;
        underTests = false;
      } else if (line.equals(FAILING_TESTS) && !testLines.isEmpty()) {
        underTests = true;
        namedOnly = false;
      } else if (namedOnly && line.startsWith(NAMED_ONLY)) {
        names.add(line.substring(NAMED_ONLY.length()));
        testLines.add(List.of());
      } else if (underTests && line.startsWith(NAMED_ONLY)) {
        testLines.getLast().add(line.substring(NAMED_ONLY.length()));
      } else {
        namedOnly = false;
        underTests = false;
      }
    }
    var checks = new ArrayList<Check>();
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i).strip();
      if (!normalized(name).isBlank()) {
        checks.add(new Check(name, normalized(name), failingTests(testLines.get(i))));
      }
    }
    return checks.isEmpty() ? NONE : new CiFailureCitations(List.copyOf(checks));
  }

  /** The distinctive test names among one check's failing-test lines, each once. */
  private static List<FailingTest> failingTests(List<String> lines) {
    var tests = new ArrayList<FailingTest>();
    var seen = new HashSet<String>();
    for (String line : lines) {
      String name = testName(line);
      if (name != null && distinctive(name) && seen.add(normalized(name))) {
        tests.add(new FailingTest(name, normalized(name)));
      }
    }
    return List.copyOf(tests);
  }

  /**
   * The test a runner's failed-test line names, without the runner's decoration around it, or
   * {@code null} for a line that names a file or a tally rather than a test. A suite path keeps
   * only the test's own name ("Pool › returns exactly `slots`" is "returns exactly `slots`"), and a
   * qualified method only the method ({@code WaitlistServiceTest.topCandidatesReturnsAtMostLimit}
   * is {@code topCandidatesReturnsAtMostLimit}), which is how a finding names it.
   */
  static String testName(String line) {
    String s = line.strip();
    int comment = s.indexOf(" # ");
    if (s.startsWith("rspec ") && comment > 0) {
      return s.substring(comment + 3).strip();
    }
    // Jest's and Vitest's "FAIL src/app.test.ts" names a file; Surefire's tally names no test.
    if (s.startsWith("FAIL ") || s.contains("Tests run:")) {
      return null;
    }
    s = TEST_LINE_LEAD.matcher(s).replaceFirst("");
    for (String tail : TEST_LINE_TAILS) {
      int at = s.indexOf(tail);
      if (at > 0) {
        s = s.substring(0, at);
      }
    }
    var segments = TEST_PATH_SEPARATOR.split(s);
    s = segments[segments.length - 1].strip();
    return ownName(s);
  }

  /**
   * A test's own name from its last path segment: a qualified token loses its position, its
   * arguments, its parameters and its qualifier; a name of several words loses Zig's {@code test.}
   * prefix.
   */
  private static String ownName(String segment) {
    String s = segment;
    var position = LINE_POSITION.matcher(s);
    if (position.find() && !hasSpaceBefore(s, position.start())) {
      s = s.substring(0, position.start());
    }
    int arguments = s.indexOf('(');
    if (arguments > 0 && !hasSpaceBefore(s, arguments)) {
      s = s.substring(0, arguments);
    }
    if (s.indexOf(' ') < 0) {
      int parameters = s.indexOf('[');
      s = parameters > 0 ? s.substring(0, parameters) : s;
      s = s.substring(s.lastIndexOf('.') + 1);
    } else {
      int zig = s.indexOf("test.");
      if (zig >= 0 && !hasSpaceBefore(s, zig)) {
        s = s.substring(zig + "test.".length());
      }
    }
    s = s.strip();
    return s.isEmpty() ? null : s;
  }

  private static boolean hasSpaceBefore(String s, int at) {
    return s.lastIndexOf(' ', at) >= 0;
  }

  /**
   * Whether a test name is specific enough that a finding naming it names that test: words of
   * {@link #MIN_TEST_NAME_CHARS} characters or more, and several words or a compound identifier
   * ({@code wardIsEscaped}). One plain word ("overflow") is any finding's vocabulary.
   */
  private static boolean distinctive(String name) {
    String words = normalized(name).strip();
    if (words.length() < MIN_TEST_NAME_CHARS) {
      return false;
    }
    if (words.indexOf(' ') > 0) {
      return true;
    }
    for (int i = 1; i < name.length(); i++) {
      if (Character.isUpperCase(name.charAt(i)) && Character.isLowerCase(name.charAt(i - 1))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean cites(ReviewResponse.Finding finding) {
    return !citations(finding.title(), finding.description()).isEmpty();
  }

  /**
   * The note attached to a finding that cites a failing check, or {@code null} when it cites none.
   * It states what the section records and nothing more: the check failed on this commit, and its
   * job log names the test, which is CI's own measurement, while whether the flagged line is what
   * makes it fail stays the verifier's call.
   */
  String noteFor(ReviewResponse.Finding finding) {
    var cited = citations(finding.title(), finding.description());
    if (cited.isEmpty()) {
      return null;
    }
    var checkNames = new LinkedHashSet<String>();
    var testNames = new LinkedHashSet<String>();
    for (var citation : cited) {
      if (citation.test() == null) {
        checkNames.add(quoted(citation.check().name()));
      } else {
        testNames.add(
            quoted(citation.test().name()) + " (check " + quoted(citation.check().name()) + ")");
      }
    }
    var records = new ArrayList<String>();
    if (!checkNames.isEmpty()) {
      records.add("records " + String.join(", ", checkNames) + " as completed without passing");
    }
    if (!testNames.isEmpty()) {
      records.add(
          "names the failing test "
              + String.join(", ", testNames)
              + " in the job log of a check that completed without passing");
    }
    return "This review's CI context "
        + String.join(", and ", records)
        + " on this commit, and the finding cites it. That outcome is CI's own measurement, not a"
        + " count anyone made: it settles that the check fails. Whether the flagged line is what"
        + " makes it fail is judged on the code.";
  }

  /**
   * One sentence for every failing check the section records, in its order: the check, the failing
   * tests its job log names, and the first of {@code open} that cites the check or one of those
   * tests — or, when none does, that no open finding explains it (#1075). Empty when the section
   * recorded no check. The summary call, asked for this sentence, wrote "no finding explains it"
   * beside the finding that did; it is decided here instead, by the reading that floors that
   * finding's confidence, so the two cannot disagree.
   *
   * @param open the findings the summary lists: this round's, then the earlier rounds' still open
   */
  String overviewSentences(List<Finding> open) {
    var sentences = new ArrayList<String>();
    for (Check check : checks) {
      var sb = new StringBuilder("CI on this commit fails the check ").append(quoted(check.name()));
      var tests = check.tests();
      if (!tests.isEmpty()) {
        var named = tests.stream().limit(MAX_TESTS_NAMED).map(t -> quoted(t.name())).toList();
        sb.append(tests.size() == 1 ? " (failing test " : " (failing tests ")
            .append(String.join(", ", named));
        if (tests.size() > named.size()) {
          sb.append(" and ").append(tests.size() - named.size()).append(" more");
        }
        sb.append(')');
      }
      var explaining =
          open.stream()
              .filter(
                  finding ->
                      citations(finding.title(), finding.description()).stream()
                          .anyMatch(citation -> citation.check() == check))
              .findFirst();
      if (explaining.isPresent()) {
        sb.append(", which the finding ").append(label(explaining.get())).append(" explains.");
      } else {
        sb.append(", which no open finding explains.");
      }
      sentences.add(sb.toString());
    }
    return String.join(" ", sentences);
  }

  /** A finding as the sentence names it: its title, or where it is when it has none. */
  private static String label(Finding finding) {
    String title = finding.title();
    return title == null || title.isBlank()
        ? "at " + finding.file() + ":" + finding.line()
        : quoted(title);
  }

  /** A name the pull request or the model controls, quoted and clipped to a bounded length. */
  private static String quoted(String name) {
    return "\"" + CiFailureContextResolver.clip(name, MAX_NAME_CHARS) + "\"";
  }

  /** The failing checks a finding's own words cite, by name or by one of their failing tests. */
  private List<Citation> citations(String title, String description) {
    if (checks.isEmpty()) {
      return List.of();
    }
    String raw =
        ((title == null ? "" : title) + "\n" + (description == null ? "" : description))
            .toLowerCase(Locale.ROOT);
    String text = normalized(raw);
    if (!states(text, FAILURE_WORDS) || states(text, DISCLAIMERS)) {
      return List.of();
    }
    var cited = new ArrayList<Citation>();
    for (Check check : checks) {
      if (namesAsACheck(check, raw, text)) {
        cited.add(new Citation(check, null));
      }
      check.tests().stream()
          .filter(test -> text.contains(test.words()))
          .forEach(test -> cited.add(new Citation(check, test)));
    }
    return cited;
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
