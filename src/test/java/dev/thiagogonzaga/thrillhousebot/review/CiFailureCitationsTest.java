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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link CiFailureCitations}: which findings cite a check the CI-failure section records. */
class CiFailureCitationsTest {

  /** The section round 12 carried on ThrillhouseBot-test#155, as the resolver renders it. */
  private static final String ROUND_12_SECTION =
      """
      Checks on this commit that had completed without passing when this review started: 1

      ### test (conclusion: failure)
      Title: test
      Annotations (1 of 1):
      - .github:41 [failure] Process completed with exit code 1.
      """;

  /** The round-12 angular finding (4149712189), posted at low confidence. */
  private static final ReviewResponse.Finding ANGULAR_OFF_BY_ONE =
      new ReviewResponse.Finding(
          "high",
          "low",
          "angular/src/app/shift-board.ts",
          45,
          "Capacity check off by one: full shift still accepts an extra sign-up",
          "The guard is `if (shift.signups.length > shift.capacity) {`. The in-diff test \"a full"
              + " shift rejects further sign-ups\" exercises exactly this path and the assertion"
              + " fails. This matches the CI check on this commit: \"test (conclusion: failure)\""
              + " with annotation \".github:41 [failure] Process completed with exit code 1.\"",
          null,
          null);

  private static ReviewResponse.Finding described(String description) {
    return new ReviewResponse.Finding(
        "high", "low", "src/queue.zig", 27, "Copy limit off by one", description, null, null);
  }

  @Test
  void aFindingThatCitesTheFailingCheckIsRecognizedAndNamed() {
    var citations = CiFailureCitations.parse(ROUND_12_SECTION);

    assertTrue(citations.cites(ANGULAR_OFF_BY_ONE));
    var note = citations.noteFor(ANGULAR_OFF_BY_ONE);
    assertTrue(note.contains("records \"test\" as completed without passing"), note);
    assertTrue(note.contains("judged on the code"), note);
  }

  /** The zig finding (4149695274) names the check its own way: the "test" check failed. */
  @Test
  void theCheckCanBeNamedInTheFindingsOwnWords() {
    var zig =
        described(
            "The added test \"submit accepts copies equal to the configured maximum\" fails, and"
                + " the CI for this commit already shows it: the \"test\" check (conclusion:"
                + " failure).");

    assertTrue(CiFailureCitations.parse(ROUND_12_SECTION).cites(zig));
  }

  /** A failing check the section does not record cites nothing: "CI fails" buys no floor. */
  @Test
  void aCheckTheSectionDoesNotRecordIsNotCited() {
    var lint = described("The lint check fails in CI on this line.");

    var citations = CiFailureCitations.parse(ROUND_12_SECTION);
    assertFalse(citations.cites(lint));
    assertNull(citations.noteFor(lint));
  }

  /**
   * A check named "test" is a word every test-failure claim uses, so only the check named as a
   * check — quoted, or beside "check", "conclusion" or "job" — is a citation, and only together
   * with a failure.
   */
  @Test
  void aNameAloneIsNotACitation() {
    var citations = CiFailureCitations.parse(ROUND_12_SECTION);

    assertFalse(citations.cites(described("The new test asserts the wrong capacity.")));
    assertFalse(citations.cites(described("The test fails when copies equals the maximum.")));
    assertFalse(
        citations.cites(
            described("The added e2e test would fail in CI because the fallback never runs.")));
    assertFalse(citations.cites(described("The \"test\" check passes on this commit.")));
    assertTrue(citations.cites(described("The test job fails on this line.")));
    assertTrue(citations.cites(described("CI's `test` run fails here.")));
    assertTrue(citations.cites(described("The \u201Ctest\u201D run fails here.")));
    assertFalse(
        citations.cites(described("The quoted \"testing harness\" fails to reset its clock.")));
    assertFalse(citations.cites(described("The 'tests' folder setup fails on Windows.")));
  }

  /** A finding that denies the failure, or sets it aside, does not cite the check. */
  @Test
  void aDeniedOrDisclaimedFailureIsNotACitation() {
    var citations = CiFailureCitations.parse(ROUND_12_SECTION);

    assertFalse(
        citations.cites(
            described(
                "The test check does not fail on this commit; the failure comes from the guard.")));
    assertFalse(citations.cites(described("This is unrelated to the failing test check.")));
    assertFalse(citations.cites(described("The test check doesn't fail on this commit.")));
    assertFalse(citations.cites(described("The test check won\u2019t fail for this line.")));
  }

  @Test
  void checksTheSectionDidNotDetailAreRecordedToo() {
    var section =
        ROUND_12_SECTION
            + "\nAlso failing, not detailed:\n- lint / eslint\n- build (windows-latest)\n"
            + "- deploy, staging\n";

    var citations = CiFailureCitations.parse(section);

    assertTrue(citations.cites(described("The eslint step of the lint / eslint check failed.")));
    assertTrue(citations.cites(described("The build (windows-latest) check fails in CI.")));
    // A name holding a comma stays one check; no phantom "staging" check is recorded.
    assertTrue(citations.cites(described("The deploy, staging job fails in CI.")));
    assertFalse(citations.cites(described("The staging job fails in CI.")));
    assertTrue(citations.cites(described("The lint and eslint checks fail in CI on this line.")));
    assertFalse(citations.cites(described("Lint and eslint both fail on this line.")));
  }

  /** The named-only list ends at the first line that is not one of its entries. */
  @Test
  void theNamedOnlyListEndsAtItsFirstOtherLine() {
    var citations =
        CiFailureCitations.parse("Also failing, not detailed:\n- lint\n\n- a later dash line\n");

    assertTrue(citations.cites(described("The lint check fails in CI.")));
    assertFalse(citations.cites(described("The a later dash line check fails in CI.")));
  }

  /**
   * The section is read back by the strings {@link CiFailureContextResolver} renders it with: a
   * section that resolver actually built records every check, detailed or only named.
   */
  @Test
  void aSectionTheResolverRendersIsReadBack() {
    var failed = new java.util.ArrayList<CiStatusEvaluator.FailedCheck>();
    for (var name : java.util.List.of("unit", "lint", "e2e", "docs", "build", "native image")) {
      failed.add(new CiStatusEvaluator.FailedCheck(name, "failure", 0L, null, null, null, 0));
    }
    var section =
        new CiFailureContextResolver(null, null, null, true, false, 20_000)
            .resolve(
                "a",
                "o",
                "r",
                new CiStatusEvaluator.CiEvaluation(
                    java.util.List.of(), false, true, new CiStatusEvaluator.CiFailures(failed, 0)));

    var citations = CiFailureCitations.parse(section);

    assertTrue(citations.cites(described("The e2e check fails in CI.")), section);
    assertTrue(citations.cites(described("The native image job fails in CI.")), section);
  }

  @Test
  void aBlankOrNamelessSectionRecordsNothing() {
    assertSame(CiFailureCitations.NONE, CiFailureCitations.parse(null));
    assertSame(CiFailureCitations.NONE, CiFailureCitations.parse("  "));
    assertSame(CiFailureCitations.NONE, CiFailureCitations.parse("### ··· (conclusion: failure)"));
    assertSame(CiFailureCitations.NONE, CiFailureCitations.parse("### (conclusion: failure)"));
    assertFalse(CiFailureCitations.NONE.cites(ANGULAR_OFF_BY_ONE));
    assertNull(CiFailureCitations.NONE.noteFor(ANGULAR_OFF_BY_ONE));
  }

  @Test
  void aFindingWithNoTextCitesNothing() {
    var bare = new ReviewResponse.Finding("high", "low", "a.ts", 1, null, null, null, null);

    assertFalse(CiFailureCitations.parse(ROUND_12_SECTION).cites(bare));
  }

  /** Check names are output the pull request controls, so a note repeats a bounded one. */
  @Test
  void aLongCheckNameIsClippedInTheNote() {
    String name = "x".repeat(400);
    var citations = CiFailureCitations.parse("### " + name + " (conclusion: failure)\n");

    var note = citations.noteFor(described("The CI check " + name + " fails."));

    assertEquals(
        CiFailureCitations.MAX_NAME_CHARS,
        note.substring(note.indexOf('"') + 1, note.indexOf('"', note.indexOf('"') + 1)).length());
  }

  /**
   * The section round 17 carried on ThrillhouseBot-test#254 (node) and #245 (java): each check's
   * failing tests as its job log names them (#1075).
   */
  private static final String ROUND_17_SECTION =
      """
      Checks on this commit that had completed without passing when this review started: 2

      ### test (conclusion: failure)
      Annotations (1 of 1):
      - .github:23 [failure] Process completed with exit code 1.
      Failing tests named in the job log:
      - \u2716 returns exactly `slots` applications when enough are eligible (1.549323ms)

      ### unit (conclusion: failure)
      Failing tests named in the job log:
      - [ERROR] com.clinic.waitlist.WaitlistServiceTest.topCandidatesReturnsAtMostLimit -- Time \
      elapsed: 0.015 s <<< FAILURE!
      - [ERROR]   WaitlistServiceTest.topCandidatesReturnsAtMostLimit:67 expected: <2> but was: <3>
      Job log tail:
      - some list line the tail happens to print
      """;

  /** The #254 finding (4248942723) as it would read naming only the test, not the check. */
  private static final ReviewResponse.Finding NODE_SHORTLIST =
      new ReviewResponse.Finding(
          "high",
          "low",
          "node/src/scoring.ts",
          36,
          "Shortlist returns slots-1 items, violating the 25-slot contract",
          "'return ranked.slice(0, slots - 1);' drops one item. The in-diff test 'returns exactly"
              + " `slots` applications when enough are eligible' asserts this directly and fails"
              + " on this commit.",
          null,
          null);

  /** A finding that names a failing test the job log lists, and not its check, cites it. */
  @Test
  void aFindingThatNamesAFailingTestCitesItsCheck() {
    var citations = CiFailureCitations.parse(ROUND_17_SECTION);

    assertTrue(citations.cites(NODE_SHORTLIST));
    var note = citations.noteFor(NODE_SHORTLIST);
    assertTrue(
        note.contains(
            "names the failing test \"returns exactly `slots` applications when enough are"
                + " eligible\" (check \"test\")"),
        note);
    assertFalse(note.contains("records"), note);
    assertTrue(note.contains("judged on the code"), note);
  }

  /** The java runner's qualified method, as a finding names it, cites the check it failed in. */
  @Test
  void aQualifiedTestIsCitedByItsMethodName() {
    var citations = CiFailureCitations.parse(ROUND_17_SECTION);
    var java =
        described(
            "The inclusive bound returns limit+1 entries; topCandidatesReturnsAtMostLimit fails"
                + " with expected 2 but was 3.");

    assertTrue(citations.cites(java));
    var note = citations.noteFor(java);
    assertTrue(note.contains("\"topCandidatesReturnsAtMostLimit\" (check \"unit\")"), note);
  }

  /** Naming the check and its test cites both, in one note. */
  @Test
  void aFindingNamingTheCheckAndTheTestCitesBoth() {
    var both =
        described(
            "The CI check 'test' fails on it: the test 'returns exactly `slots` applications when"
                + " enough are eligible' fails.");

    var note = CiFailureCitations.parse(ROUND_17_SECTION).noteFor(both);

    assertTrue(note.contains("records \"test\" as completed without passing, and names"), note);
  }

  /**
   * A test name is no citation without a failure, or with the failure set aside, and a line the
   * section lists outside a check's failing tests names no test.
   */
  @Test
  void aTestNameAloneOrDisclaimedIsNotACitation() {
    var citations = CiFailureCitations.parse(ROUND_17_SECTION);

    assertFalse(
        citations.cites(
            described("The test returns exactly `slots` applications when enough are eligible.")));
    assertFalse(
        citations.cites(
            described(
                "topCandidatesReturnsAtMostLimit fails, but that is unrelated to this line.")));
    assertFalse(
        citations.cites(described("The some list line the tail happens to print fails here.")));
  }

  /** One plain word, or a short name, is any finding's vocabulary: it cites no test. */
  @Test
  void aCommonTestNameCitesNothing() {
    var citations =
        CiFailureCitations.parse(
            """
            ### ci (conclusion: failure)
            Failing tests named in the job log:
            - --- FAIL: overflow (0.00s)
            - \u2716 parses (1ms)
            - FAILED t.py::test_y
            """);

    assertFalse(citations.cites(described("The overflow fails when the queue is full.")));
    assertFalse(citations.cites(described("It parses and fails on the empty case.")));
    assertFalse(citations.cites(described("test_y fails on the empty case.")));
    assertEquals(
        "CI on this commit fails the check \"ci\", which no open finding explains.",
        citations.overviewSentences(List.of()));
  }

  /** Failing-test lines before any check heading belong to no check and are not read. */
  @Test
  void failingTestsOutsideACheckAreNotRead() {
    var citations =
        CiFailureCitations.parse(
            "Failing tests named in the job log:\n- --- FAIL: TestParseConfig (0.00s)\n");

    assertSame(CiFailureCitations.NONE, citations);
  }

  /**
   * Only a detailed check's job log names failing tests: a failing-test list after a check that is
   * only named is read as no check's, and the parse does not fail on it.
   */
  @Test
  void failingTestsAfterACheckNamedOnlyAreNotRead() {
    var citations =
        CiFailureCitations.parse(
            """
            ### unit (conclusion: failure)
            Also failing, not detailed:
            - lint
            Failing tests named in the job log:
            - --- FAIL: TestParseConfig (0.00s)
            """);

    assertTrue(citations.cites(described("The lint check fails in CI.")));
    assertFalse(citations.cites(described("TestParseConfig fails on this line.")));
  }

  /**
   * Each runner's line yields the name a finding would write. Decoration is cut only where it
   * decorates a token: a position, an argument list or Zig's prefix after a word of the name is
   * part of the name.
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          \u2716 returns exactly `slots` applications when enough are eligible (1.5ms) | returns exactly `slots` applications when enough are eligible
          [ERROR]   WaitlistServiceTest.topCandidatesReturnsAtMostLimit:67 expected: <2> | topCandidatesReturnsAtMostLimit
          [ERROR] com.x.WaitlistServiceTest.topCandidatesReturnsAtMostLimit -- Time elapsed: 0.01 s <<< FAILURE! | topCandidatesReturnsAtMostLimit
          \u25CF BookingService \u203A rejects an overlapping booking | rejects an overlapping booking
          FAILED tests/test_config.py::test_parse_config[empty] - AssertionError: x | test_parse_config
          --- FAIL: TestParseConfig (0.00s) | TestParseConfig
          test config::tests::parses_empty_input ... FAILED | parses_empty_input
          Failed Calc.Tests.MathTest.AddsTwoNumbers(x: 0) [12 ms] | AddsTwoNumbers
          UserTest > rejectsBlankName() FAILED | rejectsBlankName
          1/5 barcode.test.isValid accepts a barcode whose last payload digit is non-zero...FAIL (TestUnexpectedResult) | isValid accepts a barcode whose last payload digit is non-zero
          rspec ./spec/booking_spec.rb:12 # Booking rejects an overlap | Booking rejects an overlap
          not ok 3 - back-to-back bookings conflict | back-to-back bookings conflict
          \u2716 keeps the 3:1 ratio for big carts | keeps the 3:1 ratio for big carts
          \u2716 returns list(empty) unchanged | returns list(empty) unchanged
          \u2716 checks that test.name stays | checks that test.name stays
          rspec ./spec/booking_spec.rb:12 | rspec ./spec/booking_spec.rb:12
          \u2716 keeps # comments (1ms) | keeps # comments
          \u2716 renders empty (when no rows are eligible) (1.5ms) | renders empty (when no rows are eligible)
          \u2716 returns [missing] rows (5 ms) | returns [missing] rows
          \u2716 handles empty case - weekend schedule (2ms) | handles empty case - weekend schedule
          FAIL: test_parse_config (tests.test_config.ConfigTest) | test_parse_config
          """)
  void eachRunnersLineYieldsTheTestsOwnName(String line, String name) {
    assertEquals(name, CiFailureCitations.testName(line));
  }

  /** A line naming a file or a tally, or nothing once its decoration is cut, names no test. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "FAIL src/app.test.ts",
        "[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE!",
        "[ERROR]   Suite.:12 expected"
      })
  void aLineThatNamesNoTestYieldsNone(String line) {
    assertNull(CiFailureCitations.testName(line));
  }

  /**
   * A file line or an all-capital word lists no test a finding can cite, and a finding without a
   * title is named by where it is.
   */
  @Test
  void aSectionsFileLinesAndCapitalWordsNameNoTest() {
    var citations =
        CiFailureCitations.parse(
            """
            ### test (conclusion: failure)
            Failing tests named in the job log:
            - FAIL src/app.test.ts
            - --- FAIL: ABCDEFGHIJ (0.00s)
            """);
    var untitled =
        new Finding(
            RiskLevel.HIGH,
            "src/app.ts",
            7,
            null,
            "The test check fails on this line.",
            null,
            null);

    assertFalse(citations.cites(described("ABCDEFGHIJ fails on this line.")));
    assertEquals(
        "CI on this commit fails the check \"test\", which the finding at src/app.ts:7 explains.",
        citations.overviewSentences(List.of(untitled)));
    var blankTitle =
        new Finding(
            RiskLevel.HIGH, "src/app.ts", 9, " ", "The test check fails on this line.", null, null);
    assertEquals(
        "CI on this commit fails the check \"test\", which the finding at src/app.ts:9 explains.",
        citations.overviewSentences(List.of(blankTitle)));
  }

  /**
   * The summary's sentence on a failing check names the open finding that cites it, by the check or
   * by its failing test (#1075, ThrillhouseBot-test#254).
   */
  @Test
  void theOverviewSentenceNamesTheOpenFindingThatExplainsTheFailure() {
    var citations = CiFailureCitations.parse(ROUND_17_SECTION);
    var unrelated =
        new Finding(RiskLevel.MEDIUM, "node/src/triage.ts", 18, "O(n^2) scan", "Slow.", null, null);

    var sentences =
        citations.overviewSentences(List.of(unrelated, Finding.fromAiResponse(NODE_SHORTLIST)));

    assertEquals(
        "CI on this commit fails the check \"test\" (failing test \"returns exactly `slots`"
            + " applications when enough are eligible\"), which the finding \"Shortlist returns"
            + " slots-1 items, violating the 25-slot contract\" explains. CI on this commit fails"
            + " the check \"unit\" (failing test \"topCandidatesReturnsAtMostLimit\"), which no"
            + " open finding explains.",
        sentences);
  }

  /** With no open finding citing a check, its sentence says none explains it. */
  @Test
  void theOverviewSentenceSaysNoFindingExplainsAnUncitedFailure() {
    var citations = CiFailureCitations.parse(ROUND_12_SECTION);

    assertEquals(
        "CI on this commit fails the check \"test\", which no open finding explains.",
        citations.overviewSentences(List.of()));
    assertEquals("", CiFailureCitations.NONE.overviewSentences(List.of()));
  }

  /** A check with many failing tests names the first few, each once, and counts the rest. */
  @Test
  void theOverviewSentenceCountsTheTestsItDoesNotName() {
    var citations =
        CiFailureCitations.parse(
            """
            ### test (conclusion: failure)
            Failing tests named in the job log:
            - --- FAIL: TestParseConfig (0.00s)
            - --- FAIL: TestParseConfig (0.01s)
            - --- FAIL: TestRejectsBlank (0.00s)
            - --- FAIL: TestKeepsOrder (0.00s)
            """);

    assertEquals(
        "CI on this commit fails the check \"test\" (failing tests \"TestParseConfig\","
            + " \"TestRejectsBlank\" and 1 more), which no open finding explains.",
        citations.overviewSentences(List.of()));
  }
}
