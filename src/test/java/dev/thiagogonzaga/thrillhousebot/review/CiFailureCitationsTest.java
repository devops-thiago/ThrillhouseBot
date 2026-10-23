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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.nio.charset.StandardCharsets;
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
        "CI on this commit fails the check \"ci\".", citations.overviewSentences(List.of()));
  }

  /**
   * A test named as one identifier is cited by that identifier, not by its words in prose: a
   * finding that says "validates the input" names no pytest test_validates_the_input.
   */
  @Test
  void anIdentifierTestIsCitedOnlyAsTheIdentifier() {
    var citations =
        CiFailureCitations.parse(
            """
            ### test (conclusion: failure)
            Failing tests named in the job log:
            - FAILED tests/test_config.py::test_validates_the_input - AssertionError: x
            """);

    assertFalse(
        citations.cites(
            described(
                "The shared helper validates the input before parsing; the new path then fails on"
                    + " blank rows.")));
    assertFalse(citations.cites(described("my_test_validates_the_input_v2 fails on blank rows.")));
    assertTrue(citations.cites(described("`test_validates_the_input` fails on blank rows.")));
    assertFalse(citations.cites(described("xtest_validates_the_input fails on blank rows.")));
    assertFalse(citations.cites(described("test_validates_the_input2 fails on blank rows.")));
    assertTrue(citations.cites(described("Blank rows fail: test_validates_the_input")));
    var titled =
        new ReviewResponse.Finding(
            "high", "low", "a.py", 3, "test_validates_the_input fails", null, null, null);
    assertTrue(citations.cites(titled));
  }

  /** Failing-test lines before any check heading belong to no check and are not read. */
  @Test
  void failingTestsOutsideACheckAreNotRead() {
    var citations =
        CiFailureCitations.parse(
            "Failing tests named in the job log:\n- --- FAIL: TestParseConfig (0.00s)\n");

    assertSame(CiFailureCitations.NONE, citations);
  }

  @Test
  void expectedTapOutcomesCannotCiteOrExplainAFailedCheck() {
    var citations =
        CiFailureCitations.parse(
            """
        ### node-test (conclusion: failure)
        Failing tests named in the job log:
        - not ok 1 - pending implementation # TODO later
        - not ok 2 - skipped on this platform # SKIP Windows only
        """);
    assertFalse(citations.cites(described("pending implementation fails on this commit")));
    assertFalse(citations.cites(described("skipped on this platform fails on this commit")));
    assertNull(CiFailureCitations.testName("not ok 1 - pending implementation # TODO later"));
    assertNull(
        CiFailureCitations.testName("not ok 2 - skipped on this platform # SKIP Windows only"));
    assertFalse(citations.overviewSentences(List.of()).contains("failing test"));
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
          \u2716 keeps # comments (1ms) | keeps # comments
          \u2716 renders empty (when no rows are eligible) (1.5ms) | renders empty (when no rows are eligible)
          \u2716 returns [missing] rows (5 ms) | returns [missing] rows
          \u2716 handles empty case - weekend schedule (2ms) | handles empty case - weekend schedule
          FAIL: test_parse_config (tests.test_config.ConfigTest) | test_parse_config
          \u2716 debounce(0) keeps rows busy after idle (3ms) | debounce(0) keeps rows busy after idle
          UserTest > rejects(blank FAILED | rejects(blank
          Failed Calc.Tests.MathTest.Nested(f(1)) [1 ms] | Nested
          1 - queue_rejects_overflow (Failed) | queue_rejects_overflow
          2/4 Test #3: booking_test ....***Failed  0.01 sec | booking_test
          1/4 Test #1: queue_test ....***Exception: SegFault  0.02 sec | queue_test
          --- FAIL: TestParseConfig/empty_input (0.00s) | TestParseConfig
          ERROR tests/test_store.py::test_search_escapes_event_name - fixture 'db' not found | test_search_escapes_event_name
          ERROR: test_hold_expiry (tests.test_holds.HoldTest.test_hold_expiry) | test_hold_expiry
          ==> X port.EventRepositorySuite.findByContainer  0.012s | findByContainer
          \u2716 waits 30s | waits 30s
          Test [1/5] queue.test.submit accepts copies... FAIL (TestUnexpectedResult) | submit accepts copies
          Failed RoomBooking.Tests.MathTests.Addition(x: 0, y: 1) [< 1 ms] | Addition
          \u2716 returns 404 -- empty payload (3ms) | returns 404 -- empty payload
          \u2716 waits for retry... then fails | waits for retry... then fails
          \u2716 expects 1 > 0 for an empty cart | expects 1 > 0 for an empty cart
          - rejects a blank name *** FAILED *** | rejects a blank name
          Chrome Headless 120.0 (Linux) Suite rejects blank names FAILED | Chrome Headless 120.0 (Linux) Suite rejects blank names
          [ERROR] testRejectsBlank(com.x.FooTest)  Time elapsed: 0.01 s  <<< FAILURE! | testRejectsBlank
          [ERROR] cowork.invoicing.InvoiceServiceTest.a single-day booking is billed as one day -- Time elapsed: 0.050 s <<< FAILURE! | a single-day booking is billed as one day
          [ERROR] cowork.InvoiceTest.single-day v1.2 booking -- Time elapsed: 0.05 s <<< FAILURE! | single-day v1.2 booking
          [ERROR] cowork.InvoiceTest.v1.2 is inclusive -- Time elapsed: 0.05 s <<< FAILURE! | v1.2 is inclusive
          [ERROR] cowork.FooTest$Nested.a.b is parsed -- Time elapsed: 0.05 s <<< FAILURE! | a.b is parsed
          [ERROR] cowork.fooTest.a single day -- Time elapsed: 0.05 s <<< FAILURE! | cowork.fooTest.a single day
          \u00D7 capacity > marks a shift full when signups reach capacity 6ms | capacity > marks a shift full when signups reach capacity
          \u00D7 recall rules > schedules one reminder per attempt, starting when the lead window opens 8ms | recall rules > schedules one reminder per attempt, starting when the lead window opens
          \u00D7 retries the upload 0.01s | retries the upload
          \u2716 accepts a team of exactly maxTeamSize members (0.320076ms) | accepts a team of exactly maxTeamSize members
          error: 'tests.test.compact counts every reading in the window' failed: expected 3, found 2 | compact counts every reading in the window
          FAIL test_find_free_uses_last_locker | test_find_free_uses_last_locker
          """)
  void eachRunnersLineYieldsTheTestsOwnName(String line, String name) {
    assertEquals(name, CiFailureCitations.testName(line));
  }

  /** A line naming a file or a tally, or nothing once its decoration is cut, names no test. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "FAIL src/app.test.ts",
        "rspec ./spec/booking_spec.rb:12",
        "[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE!",
        "[ERROR]   Suite.:12 expected",
        "\u2716 failing tests:",
        "FAIL example.com/locker 0.012s"
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
            + " the check \"unit\" (failing test \"topCandidatesReturnsAtMostLimit\").",
        sentences);
    assertEquals(
        sentences,
        citations.overviewSentences(List.of(Finding.fromAiResponse(NODE_SHORTLIST), unrelated)),
        "the first citing finding is named wherever it stands");
  }

  /**
   * With no open finding citing a check, its sentence states the failure and claims nothing about
   * the findings: one may describe the failing assertion without naming its test (#1091).
   */
  @Test
  void theOverviewSentenceStatesAnUncitedFailureAlone() {
    var citations = CiFailureCitations.parse(ROUND_12_SECTION);

    assertEquals(
        "CI on this commit fails the check \"test\".", citations.overviewSentences(List.of()));
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
            + " \"TestRejectsBlank\" and 1 more).",
        citations.overviewSentences(List.of()));
  }

  /**
   * The section the resolver renders for one failing "test" check over the given job-log lines: the
   * failing-test lines are those {@link CiFailureContextResolver#failingTestLines} reads from them,
   * so a test sees exactly what reached the round-18 summaries.
   */
  private static CiFailureCitations fromJobLog(String log) {
    return CiFailureCitations.parse(sectionFromJobLog(log));
  }

  private static String sectionFromJobLog(String log) {
    var section = new StringBuilder("### test (conclusion: failure)\n");
    var lines = CiFailureContextResolver.failingTestLines(log.getBytes(StandardCharsets.UTF_8));
    if (!lines.isEmpty()) {
      section.append("Failing tests named in the job log:\n");
      lines.forEach(line -> section.append("- ").append(line).append('\n'));
    }
    return section.toString();
  }

  /**
   * csharp ThrillhouseBot-test#270 (finding 4253711864): the finding names the failing test but, as
   * the review prompt asks of a prediction, says "CI will confirm" instead of that it fails. It
   * explains the check for the summary's sentence; the confidence floor still wants the failure
   * stated (#1091).
   */
  @Test
  void aFindingNamingTheFailingTestExplainsItWithoutSayingItFails() {
    var citations =
        fromJobLog(
            """
            [xUnit.net 00:00:00.25]     MuseumTickets.Tests.TimedEntryAllocatorTests.TryHold_CanTakeEveryRemainingTicketInASlot [FAIL]
            Failed MuseumTickets.Tests.TimedEntryAllocatorTests.TryHold_CanTakeEveryRemainingTicketInASlot [1 ms]
            Error Message:
            Assert.NotNull() Failure: Value is null
            """);
    var finding =
        new ReviewResponse.Finding(
            "high",
            "high",
            "csharp/MuseumTickets/TimedEntryAllocator.cs",
            39,
            "Hold rejected when the request exactly fills the remaining slot capacity",
            "The guard is `if (remaining > request.Quantity)`, so a request whose Quantity equals"
                + " the tickets left (e.g. 40 requested of 40 remaining in a fresh slot, equal to"
                + " AllocatorConfig.DefaultSlotCapacity) takes the else branch and `return null;` —"
                + " the slot cannot take its last party even though there is room. The docstring"
                + " above it says the method returns null only \"when the slot cannot take them\","
                + " and an in-diff test, TryHold_CanTakeEveryRemainingTicketInASlot, calls TryHold"
                + " with `capacity` tickets on a fresh slot and asserts NotNull — CI will confirm"
                + " the outcome; the trace shows every exact-fit request getting null and the"
                + " caller answering 409.",
            null,
            null);

    assertEquals(
        "CI on this commit fails the check \"test\" (failing test"
            + " \"TryHold_CanTakeEveryRemainingTicketInASlot\"), which the finding \"Hold rejected"
            + " when the request exactly fills the remaining slot capacity\" explains.",
        citations.overviewSentences(List.of(Finding.fromAiResponse(finding))));
    assertFalse(citations.cites(finding), "the floor still wants the failure stated");
  }

  /**
   * kotlin ThrillhouseBot-test#276 (finding 4253722678): Surefire prints a Kotlin test's name of
   * several words after its class, and the class stayed in the name, so the finding that names the
   * test and says it "fails on this commit" did not match it (#1091).
   */
  @Test
  void aKotlinTestIsNamedWithoutItsClass() {
    var citations =
        fromJobLog(
            """
            [ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.149 s <<< FAILURE! -- in cowork.invoicing.InvoiceServiceTest
            [ERROR] cowork.invoicing.InvoiceServiceTest.a single-day booking is billed as one day -- Time elapsed: 0.050 s <<< FAILURE!
            org.opentest4j.AssertionFailedError: expected: <1> but was: <0>
            at cowork.invoicing.InvoiceServiceTest.a single-day booking is billed as one day(InvoiceServiceTest.kt:46)
            [ERROR] Failures:
            [ERROR]   InvoiceServiceTest.a single-day booking is billed as one day:46 expected: <1> but was: <0>
            [ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
            """);
    var finding =
        new ReviewResponse.Finding(
            "high",
            "medium",
            "kotlin/src/main/kotlin/cowork/invoicing/InvoiceService.kt",
            78,
            "billableDays is off by one: inclusive date range returns one day too few",
            "Booking dates are documented as inclusive in Models.kt: \"Both [startDate] and"
                + " [endDate] are inclusive: a booking for a single day has startDate == endDate.\""
                + " billableDays returns ChronoUnit.DAYS.between(start, end), which excludes the end"
                + " date. For the test's single-day booking LocalDate.of(2026, 3, 10) to"
                + " LocalDate.of(2026, 3, 10), DAYS.between returns 0, not 1, so the CI test 'a"
                + " single-day booking is billed as one day' (assertEquals(1, days)) fails on this"
                + " commit.",
            null,
            null);

    assertTrue(citations.cites(finding));
    assertEquals(
        "CI on this commit fails the check \"test\" (failing test \"a single-day booking is billed"
            + " as one day\"), which the finding \"billableDays is off by one: inclusive date range"
            + " returns one day too few\" explains.",
        citations.overviewSentences(List.of(Finding.fromAiResponse(finding))));
  }

  /** rust ThrillhouseBot-test#279's job log: the failing test's name, and "got 4". */
  private static final String RUST_279_LOG =
      """
      test rms_uses_exactly_the_last_window_values ... FAILED
      test thresholds_are_sorted ... ok
      ---- rms_uses_exactly_the_last_window_values stdout ----
      thread 'rms_uses_exactly_the_last_window_values' (2483) panicked at tests/ingest_test.rs:34:5:
      got 4
      test result: FAILED. 8 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
      """;

  /** Lines 18-43 of rust ThrillhouseBot-test#279's {@code rust/tests/ingest_test.rs}. */
  private static final String RUST_279_TESTS =
      """
      #[test]
      fn parses_batch_lines() {
          let batch = parse_batch("acc-1,1000,12.5\\n\\nacc-1,1001,-3\\n").unwrap();
          assert_eq!(batch, vec![reading("acc-1", 1000, 12.5), reading("acc-1", 1001, -3.0)]);
          assert!(parse_batch("acc-1,1000").is_err());
      }

      #[test]
      fn dedup_drops_retransmitted_samples() {
          let unique = dedup(vec![reading("a", 1, 1.0), reading("a", 1, 1.0), reading("b", 1, 2.0)]);
          assert_eq!(unique.len(), 2);
      }

      #[test]
      fn rms_uses_exactly_the_last_window_values() {
          let rms = rms_of_last(&[0.0, 0.0, 3.0, 4.0], 2);
          assert!((rms - 12.5f64.sqrt()).abs() < 1e-9, "got {rms}");
      }

      #[test]
      fn zone_counts_crossed_thresholds() {
          let thresholds = [4.5, 7.1, 11.2];
          assert_eq!(zone(1.0, &thresholds), 0);
          assert_eq!(zone(7.1, &thresholds), 2);
          assert_eq!(zone(30.0, &thresholds), 3);
      }
      """;

  /** The round-18 rust finding (4253724050), posted HIGH at low confidence. */
  private static final ReviewResponse.Finding RUST_279_FINDING =
      new ReviewResponse.Finding(
          "high",
          "low",
          "rust/src/ingest.rs",
          45,
          "rms_of_last window arithmetic is off by one and drops a sample",
          "Worked from the values quoted verbatim in the test: for `[0.0, 0.0, 3.0, 4.0]` with"
              + " `window = 2`, `4 - 2 = 2`, `+ 1 = 3`, so the tail is `[4.0]` and the RMS would"
              + " be 4.0, while the assertion `assert!((rms - 12.5f64.sqrt()).abs() < 1e-9, \"got"
              + " {rms}\");` expects roughly 3.5355 — the committed `cargo test` run will confirm"
              + " whether this assertion fails as written.",
          null,
          null);

  /** {@code content} as the patch of a file the pull request adds, its first line at {@code at}. */
  private static GitHubPullRequestClient.FileDiff addedAt(String path, int at, String content) {
    var lines = content.split("\n", -1);
    int count = content.endsWith("\n") ? lines.length - 1 : lines.length;
    var patch = new StringBuilder("@@ -0,0 +" + at + "," + count + " @@\n");
    for (int i = 0; i < count; i++) {
      patch.append('+').append(lines[i]).append('\n');
    }
    return new GitHubPullRequestClient.FileDiff(path, "added", count, 0, count, patch.toString());
  }

  @Test
  void aQuotedCommonJsImportDoesNotCiteTheFailingTest() {
    var citations =
        CiFailureCitations.parse(
            sectionFromJobLog("not ok 1 - loads the client"),
            List.of(
                addedAt(
                    "client.test.cjs",
                    1,
                    """
              test("loads the client", () => {
                const api = require('../api');
                expect(api.ready()).toBe(true);
              });
            """)));
    var imported = described("The setup line `const api = require('../api');` fails.");

    assertFalse(citations.cites(imported));
    assertNull(citations.noteFor(imported));
    assertEquals(
        "low",
        FindingVerificationService.floorCiCitedConfidence(
                new ReviewResponse(List.of(imported), List.of(), null), citations)
            .findings()
            .getFirst()
            .confidence());
    assertFalse(
        citations
            .overviewSentences(List.of(Finding.fromAiResponse(imported)))
            .contains("which the finding"));
    assertTrue(citations.cites(described("`expect(api.ready()).toBe(true);` fails.")));
  }

  private static CiFailureCitations rust279(List<GitHubPullRequestClient.FileDiff> files) {
    return CiFailureCitations.parse(sectionFromJobLog(RUST_279_LOG), files);
  }

  /**
   * Without the test's body the rust finding, which quotes the test's assertion and not its name,
   * is tied to nothing: the job log does not print the assertion ("got 4"), so the sentence states
   * the failure and says nothing of the findings (#1091).
   */
  @Test
  void aQuotedAssertionCitesNothingWithoutTheTestsBody() {
    var citations = fromJobLog(RUST_279_LOG);

    assertEquals(
        "CI on this commit fails the check \"test\" (failing test"
            + " \"rms_uses_exactly_the_last_window_values\").",
        citations.overviewSentences(List.of(Finding.fromAiResponse(RUST_279_FINDING))));
    assertFalse(citations.cites(RUST_279_FINDING));
    assertFalse(
        rust279(List.of(addedAt("rust/src/ingest.rs", 1, "pub fn rms_of_last() {}\n")))
            .cites(RUST_279_FINDING),
        "a test file outside the diff gives no body");
  }

  /**
   * #952, round 18: with the test file in the diff, the rust finding cites the failing test whose
   * assertion it quotes. It gets the verifier's note, the CI-cited floor lifts it from low to
   * medium confidence at its HIGH risk, and the summary's sentence names it.
   */
  @Test
  void theRound18RustFindingCitesTheTestWhoseAssertionItQuotes() {
    var citations = rust279(List.of(addedAt("rust/tests/ingest_test.rs", 18, RUST_279_TESTS)));

    assertTrue(citations.cites(RUST_279_FINDING));
    var note = citations.noteFor(RUST_279_FINDING);
    assertTrue(
        note.contains(
            "names the failing test \"rms_uses_exactly_the_last_window_values\" (check \"test\"),"
                + " whose assertion the finding quotes, in the job log"),
        note);
    var floored =
        FindingVerificationService.floorCiCitedConfidence(
                new ReviewResponse(List.of(RUST_279_FINDING), List.of(), null), citations)
            .findings()
            .getFirst();
    assertEquals("high", floored.risk());
    assertEquals("medium", floored.confidence());
    assertTrue(
        citations
            .overviewSentences(List.of(Finding.fromAiResponse(RUST_279_FINDING)))
            .endsWith(
                ", which the finding \"rms_of_last window arithmetic is off by one and drops a"
                    + " sample\" explains."));
  }

  /**
   * Only an assertion of the failing test's own body cites it: one of a passing test, a setup line
   * of the failing one, a quote too short to be the assertion's own code, a disclaimed failure, and
   * an assertion whose declaration the patch does not show all cite nothing.
   */
  @Test
  void onlyAQuotedAssertionOfTheFailingTestsOwnBodyCitesIt() {
    var citations = rust279(List.of(addedAt("rust/tests/ingest_test.rs", 18, RUST_279_TESTS)));

    assertFalse(citations.cites(described("`assert_eq!(zone(7.1, &thresholds), 2);` fails.")));
    assertFalse(
        citations.cites(described("`let rms = rms_of_last(&[0.0, 0.0, 3.0, 4.0], 2);` fails.")));
    assertFalse(citations.cites(described("`(rms - 12.5f64` fails.")));
    assertFalse(
        citations.cites(
            described(
                "`assert!((rms - 12.5f64.sqrt()).abs() < 1e-9` fails, unrelated to this change.")));
    assertTrue(
        citations.cites(described("`assert!((rms - 12.5f64.sqrt()).abs() < 1e-9` fails.")),
        "part of the assertion line is enough");

    var bodyOnly =
        rust279(
            List.of(
                addedAt(
                    "rust/tests/ingest_test.rs",
                    33,
                    """
                        let rms = rms_of_last(&[0.0, 0.0, 3.0, 4.0], 2);
                        assert!((rms - 12.5f64.sqrt()).abs() < 1e-9, "got {rms}");
                    """)));
    assertFalse(bodyOnly.cites(RUST_279_FINDING), "no declaration, no body");
  }

  /**
   * react ThrillhouseBot-test#273 and angular #275: Vitest prints the suite path and the duration
   * around the test's name, and the duration stayed in it, so no finding could name it. Neither
   * round-18 finding names its test (the angular one, 4253711454, quotes the spec's assertions), so
   * the sentence states the failure; a finding naming the test without its suite explains it
   * (#1091).
   */
  @Test
  void aVitestTestIsNamedWithoutItsDurationAndCitedWithoutItsSuite() {
    var react =
        fromJobLog(
            """
            ❯ src/__tests__/board.test.ts (8 tests | 1 failed) 12ms
            × capacity > marks a shift full when signups reach capacity 6ms
            → expected false to be true // Object.is equality
            FAIL  src/__tests__/board.test.ts > capacity > marks a shift full when signups reach capacity
            """);
    var angular =
        fromJobLog(
            """
            ❯ src/app/recall-board.spec.ts (10 tests | 1 failed) 15ms
            × recall rules > schedules one reminder per attempt, starting when the lead window opens 8ms
            FAIL  src/app/recall-board.spec.ts > recall rules > schedules one reminder per attempt, starting when the lead window opens
            """);
    var angularFinding =
        new Finding(
            RiskLevel.MEDIUM,
            "angular/src/app/recall-rules.ts",
            35,
            "reminderSchedule starts at i=1, so it emits one fewer reminder and never fires on the"
                + " window-open day",
            "The spec at angular/src/app/recall-board.spec.ts asserts"
                + " `expect(schedule).toHaveLength(3);` and `expect(schedule[0]).toEqual(addDays(due,"
                + " -21));`, which the loop as written cannot satisfy (it produces length 2 and a"
                + " first element at due-14) — that test run and the CI run on this commit will show"
                + " the mismatch directly.",
            null,
            null);
    var naming =
        new Finding(
            RiskLevel.HIGH,
            "react/src/lib/schedule.ts",
            15,
            "isFull uses a strict greater-than comparison",
            "The test \"marks a shift full when signups reach capacity\" expects true here.",
            null,
            null);

    assertEquals(
        "CI on this commit fails the check \"test\" (failing test \"recall rules > schedules one"
            + " reminder per attempt, starting when the lead window opens\").",
        angular.overviewSentences(List.of(angularFinding)));
    assertEquals(
        "CI on this commit fails the check \"test\" (failing test \"capacity > marks a shift full"
            + " when signups reach capacity\"), which the finding \"isFull uses a strict"
            + " greater-than comparison\" explains.",
        react.overviewSentences(List.of(naming)));
  }

  /**
   * A Vitest test whose own name holds " > " is cited by the whole name after its suite, and a tail
   * too short to name a test cites nothing.
   */
  @Test
  void aVitestNameHoldingAComparisonIsCitedWhole() {
    var citations = fromJobLog("× timers > skips the run when count > limit 6ms\n");
    var naming =
        new Finding(
            RiskLevel.HIGH,
            "src/timers.ts",
            9,
            "Guard inverted",
            "The test 'skips the run when count > limit' runs the job anyway.",
            null,
            null);
    var tailOnly =
        new Finding(RiskLevel.HIGH, "src/a.ts", 2, "Bound", "The limit is wrong.", null, null);

    assertTrue(citations.overviewSentences(List.of(naming)).contains("explains"));
    assertFalse(citations.overviewSentences(List.of(tailOnly)).contains("explains"));
    assertEquals(
        "CI on this commit fails the check \"test\" (failing test \"timers > skips the run when"
            + " count > limit\").",
        citations.overviewSentences(List.of()));
  }

  /**
   * node ThrillhouseBot-test#278: node:test heads its list of failed tests "✖ failing tests:",
   * which was named as a second failing test (#1091).
   */
  @Test
  void nodeTestsListHeaderIsNoTest() {
    var citations =
        fromJobLog(
            """
            ▶ TeamService.register
            ✖ accepts a team of exactly maxTeamSize members (0.320076ms)
            ✖ TeamService.register (4.06223ms)
            ℹ fail 1

            ✖ failing tests:

            test at test/teams.test.ts:64:3
            ✖ accepts a team of exactly maxTeamSize members (0.320076ms)
            """);

    assertEquals(
        "CI on this commit fails the check \"test\" (failing test \"accepts a team of exactly"
            + " maxTeamSize members\").",
        citations.overviewSentences(List.of()));
  }

  /**
   * zig ThrillhouseBot-test#274 (finding 4253722582) and c #277 (finding 4253705906): zig build's
   * "error: '…' failed:" and a harness's "FAIL test_name" name the failing test, which each finding
   * names too (#1091).
   */
  @Test
  void zigBuildAndBareFailLinesNameTheirTests() {
    var zig =
        fromJobLog(
            """
            +- run test 5/6 passed, 1 failed
            error: 'tests.test.compact counts every reading in the window' failed: expected 3, found 2
            return error.TestExpectedEqual;
            error: while executing test 'tests.test.compact reports window minimum in celsius', the following test command failed:
            Build Summary: 1/3 steps succeeded; 1 failed; 5/6 tests passed; 1 failed
            """);
    var c =
        fromJobLog(
            """
            ./build/test_dispenser
            tests/test_dispenser.c:53: check failed: idx == 2
            PASS test_find_free_returns_first_slot
            FAIL test_find_free_uses_last_locker
            PASS test_pin_is_six_digits
            1 failure(s)
            """);
    var zigFinding =
        new Finding(
            RiskLevel.HIGH,
            "zig/src/compactor.zig",
            68,
            "compact() drops the last reading of every batch, and the new unit test fails on it",
            "Trace: for the input in `zig/src/tests.zig` test \"compact counts every reading in the"
                + " window\" (ts 0, 60, 120, window 300), the loop runs for i=0 and i=1 only.",
            null,
            null);
    var cFinding =
        new Finding(
            RiskLevel.HIGH,
            "c/src/locker.c",
            18,
            "locker_find_free never examines the last locker in the bank",
            "The unit test added in this PR traces straight into the bug:"
                + " test_find_free_uses_last_locker builds a 3-slot bank, marks slots 0 and 1"
                + " occupied, and expects `locker_find_free(&bank)` to return 2 — but the loop bound"
                + " `i < bank->count - 1` (i.e. i < 2) never reaches index 2, the function returns"
                + " -1, and the `CHECK(idx == 2)` assertion fails; CI will confirm this.",
            null,
            null);

    assertEquals(
        "CI on this commit fails the check \"test\" (failing test \"compact counts every reading in"
            + " the window\"), which the finding \"compact() drops the last reading of every batch,"
            + " and the new unit test fails on it\" explains.",
        zig.overviewSentences(List.of(zigFinding)));
    assertEquals(
        "CI on this commit fails the check \"test\" (failing test"
            + " \"test_find_free_uses_last_locker\"), which the finding \"locker_find_free never"
            + " examines the last locker in the bank\" explains.",
        c.overviewSentences(List.of(cFinding)));
  }

  /**
   * A finding that names the failing test but sets the failure aside does not explain it, and a
   * check named without a failure is no citation for the sentence either.
   */
  @Test
  void aDisclaimedTestOrABareCheckNameExplainsNothing() {
    var citations = CiFailureCitations.parse(ROUND_17_SECTION);
    var disclaimed =
        new Finding(
            RiskLevel.HIGH,
            "src/a.ts",
            3,
            "Unrelated",
            "topCandidatesReturnsAtMostLimit is unrelated to this line.",
            null,
            null);
    var checkOnly =
        new Finding(
            RiskLevel.HIGH, "src/a.ts", 4, "Check named", "The unit check runs here.", null, null);
    var passing =
        new Finding(
            RiskLevel.HIGH,
            "src/a.ts",
            5,
            "Guard is fine",
            "The in-diff test topCandidatesReturnsAtMostLimit passes as written; the guard is fine.",
            null,
            null);
    var fixedThenPassing =
        new Finding(
            RiskLevel.HIGH,
            "src/a.ts",
            6,
            "Limit off by one",
            "topCandidatesReturnsAtMostLimit fails on this line; with the fix it passes.",
            null,
            null);

    var sentences = citations.overviewSentences(List.of(disclaimed, checkOnly, passing));
    assertTrue(
        citations
            .overviewSentences(List.of(passing, fixedThenPassing))
            .contains("which the finding \"Limit off by one\" explains"),
        "a stated failure still explains it beside a pass after the fix");

    assertFalse(sentences.contains("explains"), sentences);
  }

  /**
   * #952, round 18: python ThrillhouseBot-test#268's round-1 finding was posted at low confidence
   * because `test` failed after round 1 read CI. Read against the section the CI re-read gives
   * (#1092), its own words cite the failing test, so the CI-cited floor lifts it.
   */
  @Test
  void theRound18PythonFindingCitesTheTestThatFailedAfterReviewStart() {
    var citations =
        CiFailureCitations.parse(
            """
            ### test (conclusion: failure)
            Failing tests named in the job log:
            - FAIL: test_first_applicant_is_position_one (tests.test_waitlist.WaitlistTest)
            """);
    var finding =
        new ReviewResponse.Finding(
            "medium",
            "low",
            "python/garden_waitlist/waitlist.py",
            57,
            "position_of returns a 0-based index, so the first applicant is told they are number 0",
            "position_of returns the enumerate index from `for index, entry in"
                + " enumerate(self.store.all_entries()):`, which is 0 for the first entry. join"
                + " returns this value directly and /signup renders it as `\"You are number"
                + " {position} on the waitlist.\"`, so the first resident to sign up is told they"
                + " are number 0. The test test_first_applicant_is_position_one asserts"
                + " `waitlist.join(\"Ana\", ...) == 1` and `== 2` for the second applicant, i.e."
                + " 1-based places in line; with the code as written the CI unittest run is"
                + " expected to fail — the test run will show whether Ana's expected 1 or the"
                + " returned 0 is right. If 1-based is intended, use enumerate(..., start=1).",
            null,
            null);

    assertTrue(citations.cites(finding));
  }

  // ---- #1194, round 19: a wrong tie (node, react) and a missing one (c, scala) ----

  /** node ThrillhouseBot-test#299's job log: node --test crashed before any test ran. */
  private static final String NODE_299_LOG =
      """
      # Error: Cannot find module '/home/runner/work/ThrillhouseBot-test/ThrillhouseBot-test/node/test'
      not ok 1 - test
        ---
        failureType: 'testCodeFailure'
        error: 'test failed'
      # fail 1
      """;

  /** node ThrillhouseBot-test#299's Dockerfile finding (4336441186). */
  private static final ReviewResponse.Finding NODE_299_FINDING =
      new ReviewResponse.Finding(
          "high",
          "medium",
          "node/Dockerfile",
          6,
          "COPY dist and ENTRYPOINT dist/server.js reference nothing the build produces",
          "The COPY dist ./dist step requires a dist/ directory in the build context, and the"
              + " ENTRYPOINT [\"node\", \"dist/server.js\"] requires dist/server.js inside the image —"
              + " but nothing in or around this PR produces it. The node directory is added"
              + " wholesale by this PR; node/package.json defines only a test script (\"test\":"
              + " \"node --test test/\"), has no build script and no dependencies, and the"
              + " implementation files are src/*.js, with no server.js among them. The CI workflow"
              + " (.github/workflows/node-ci.yml) runs only npm test. As written, docker build fails"
              + " at COPY (no dist in the context), and even if dist existed the entrypoint targets"
              + " a file no stage builds.",
          null,
          null);

  /**
   * #1194: node's runner crashed before any test ran, so the log names no test, and the Dockerfile
   * finding was credited with the failing {@code test} check by a package.json script key quoted in
   * one sentence and "docker build fails" in another. Neither ties a failure to the check.
   */
  @Test
  void theRound19NodeDockerfileFindingDoesNotExplainTheCrashedTestRun() {
    var citations = fromJobLog(NODE_299_LOG);

    assertEquals(
        "CI on this commit fails the check \"test\".",
        citations.overviewSentences(List.of(Finding.fromAiResponse(NODE_299_FINDING))));
    assertFalse(citations.cites(NODE_299_FINDING));
  }

  /** react ThrillhouseBot-test#300's workflow finding (4336451178). */
  private static final ReviewResponse.Finding REACT_300_FINDING =
      new ReviewResponse.Finding(
          "high",
          "high",
          ".github/workflows/react-ci.yml",
          21,
          "CI copies dist/report.html, but no step builds dist or generates the report",
          "The test job runs only: 'actions/checkout@v4', 'actions/setup-node@v4', and '- run:"
              + " npm test' (line 19). It then does '- name: Upload bundle report' / 'run: cp"
              + " dist/report.html report.html' (line 21). Nothing runs 'npm run build', so does"
              + " not exist at that point, and even after a build nothing in the reviewed code"
              + " generates a report.html inside dist — vite build does not emit one by default and"
              + " no report plugin is configured. The cp fails deterministically on every run ('cp:"
              + " cannot stat 'dist/report.html': No such file or directory') and the job goes red"
              + " on every pull request touching react/**.",
          null,
          null);

  /**
   * #1194: react's runner crashed the same way, and the upload step the finding is about was
   * skipped. "The test job" and "the cp fails" are in different sentences about different steps.
   */
  @Test
  void theRound19ReactWorkflowFindingDoesNotExplainTheCrashedTestRun() {
    var citations =
        fromJobLog(
            """
            Error: Cannot find module '/home/runner/work/ThrillhouseBot-test/ThrillhouseBot-test/react/test'
            ✖ test (24.80723ms)
            ℹ fail 1
            ✖ failing tests:
            ✖ test (24.80723ms)
            """);

    assertEquals(
        "CI on this commit fails the check \"test\".",
        citations.overviewSentences(List.of(Finding.fromAiResponse(REACT_300_FINDING))));
    assertFalse(citations.cites(REACT_300_FINDING));
  }

  /**
   * A check named in the same sentence that says it fails still cites it, and a key quoted in that
   * sentence does not stop a second, quoted mention from naming the check.
   */
  @Test
  void aCheckNamedInTheSentenceThatSaysItFailsIsCited() {
    var citations = fromJobLog("not ok 1 - test\n");
    var finding =
        new ReviewResponse.Finding(
            "high",
            "high",
            "package.json",
            3,
            "Test script points at a missing directory",
            "The script key \"test\": \"node --test test/\" names a directory, so the \"test\""
                + " check fails before any test runs.",
            null,
            null);

    assertTrue(citations.cites(finding));
    assertFalse(
        citations.cites(
            new ReviewResponse.Finding(
                "high",
                "high",
                "package.json",
                3,
                "Test script points at a missing directory",
                "The script key \"test\": \"node --test test/\" names a directory. The build"
                    + " fails.",
                null,
                null)),
        "the key and the failure are in different sentences");
  }

  /** c ThrillhouseBot-test#302's harness output: its CHECK macro prints the label it was given. */
  private static final String C_302_LOG =
      """
      PASS issue_notifies
      FAIL rider_limit_rejects_third_code
      PASS no_dock_available
      """;

  /** Lines 59-71 of c ThrillhouseBot-test#302's {@code c/tests/test_dispenser.c}. */
  private static final String C_302_TESTS =
      """
      static void test_rider_limit(void)
      {
          static config_t cfg;
          static dispenser_t d;
          int rc;

          config_load(&cfg);
          setup(&d, &cfg);
          dispenser_issue(&d, "rider1", "+15550101", "harbor", 100, stub_notify, NULL);
          dispenser_issue(&d, "rider1", "+15550101", "harbor", 101, stub_notify, NULL);
          rc = dispenser_issue(&d, "rider1", "+15550101", "harbor", 102, stub_notify, NULL);
          CHECK("rider_limit_rejects_third_code", rc == DISP_LIMIT);
      }
      """;

  /** c ThrillhouseBot-test#302's off-by-one finding (4336472686). */
  private static final ReviewResponse.Finding C_302_FINDING =
      new ReviewResponse.Finding(
          "medium",
          "high",
          "c/src/dock.c",
          64,
          "Off-by-one lets a rider hold one more code than the configured limit",
          "The cap check is `if (count_active(d, rider, now) > d->cfg->max_codes_per_rider) return"
              + " DISP_LIMIT;`. With the default max_codes_per_rider of 2, a rider holding 2 active"
              + " codes gets 2 > 2 = false and is issued a third. The documented contract says the"
              + " limit is the number a rider may hold, and the PR's own test test_rider_limit"
              + " encodes exactly that intent: it issues two codes at now=100 and 101, then asserts"
              + " the third issuance at now=102 returns DISP_LIMIT. With the current comparison the"
              + " third call instead proceeds to find a free dock and returns DISP_OK, so that test"
              + " run will show this in CI. The comparison should be >=, matching what both the"
              + " documentation and the test expect.",
          null,
          null);

  /**
   * #1194: the c log prints the CHECK label, and the off-by-one finding names the test function
   * that check is in. With the test file in the diff the label leads to that function, and the
   * finding explains the failure; without it the sentence states the failure alone.
   */
  @Test
  void theRound19CFindingNamingTheTestFunctionExplainsItsFailingCheck() {
    var section = sectionFromJobLog(C_302_LOG);
    var withTests =
        CiFailureCitations.parse(
            section, List.of(addedAt("c/tests/test_dispenser.c", 59, C_302_TESTS)));

    assertEquals(
        "CI on this commit fails the check \"test\" (failing test"
            + " \"rider_limit_rejects_third_code\"), which the finding \"Off-by-one lets a rider"
            + " hold one more code than the configured limit\" explains.",
        withTests.overviewSentences(List.of(Finding.fromAiResponse(C_302_FINDING))));
    assertEquals(
        "CI on this commit fails the check \"test\" (failing test"
            + " \"rider_limit_rejects_third_code\").",
        CiFailureCitations.parse(section)
            .overviewSentences(List.of(Finding.fromAiResponse(C_302_FINDING))));
  }

  /** scala ThrillhouseBot-test#297's sbt output, its level and colours as the job log printed. */
  private static final String SCALA_297_LOG =
      """
      [info] ColdchainSpec:
      [info] - rolling average covers exactly the last n readings *** FAILED ***
      [info]   7.0 did not equal 6.0 (ColdchainSpec.scala:16)
      [info] - rolling average of nothing is zero
      [info] *** 1 TEST FAILED ***
      """;

  /** Lines 14-21 of scala ThrillhouseBot-test#297's {@code ColdchainSpec.scala}. */
  private static final String SCALA_297_TESTS =
      """
        test("rolling average covers exactly the last n readings") {
          val rs = List(2.0, 4.0, 6.0, 8.0).zipWithIndex.map { case (c, i) => Reading("S1", i.toLong, c) }
          assert(ExcursionDetector.rollingAverage(rs, 3) == 6.0)
        }

        test("rolling average of nothing is zero") {
          assert(ExcursionDetector.rollingAverage(Nil, 3) == 0.0)
        }
      """;

  /** scala ThrillhouseBot-test#297's rollingAverage finding (4336444594). */
  private static final ReviewResponse.Finding SCALA_297_FINDING =
      new ReviewResponse.Finding(
          "medium",
          "medium",
          "scala/src/main/scala/coldchain/ExcursionDetector.scala",
          9,
          "rollingAverage averages the last n-1 readings while its docstring promises the last n",
          "The scaladoc says '/** Average of the most recent `n` readings. */' but the body is"
              + " 'val recent = readings.takeRight(n - 1)'. For the input the sibling test supplies"
              + " — readings (2.0, 4.0, 6.0, 8.0) with n = 3 — this takes the last two elements"
              + " (6.0, 8.0) and returns 7.0, while the test asserts"
              + " 'assert(ExcursionDetector.rollingAverage(rs, 3) == 6.0)', the average of the last"
              + " three; the CI run will confirm whether that test fails as written.",
          null,
          null);

  /**
   * #1194: sbt opens each line with its level, which left the failing test's name read as "[info]";
   * and the scala finding quotes the test's assertion in single quotes. Both are read now, so the
   * finding explains the failing test.
   */
  @Test
  void theRound19ScalaFindingQuotingTheAssertionExplainsTheFailingTest() {
    var citations =
        CiFailureCitations.parse(
            sectionFromJobLog(SCALA_297_LOG),
            List.of(
                addedAt(
                    "scala/src/test/scala/coldchain/ColdchainSpec.scala", 14, SCALA_297_TESTS)));

    assertEquals(
        "CI on this commit fails the check \"test\" (failing test \"rolling average covers"
            + " exactly the last n readings\"), which the finding \"rollingAverage averages the"
            + " last n-1 readings while its docstring promises the last n\" explains.",
        citations.overviewSentences(List.of(Finding.fromAiResponse(SCALA_297_FINDING))));
    assertTrue(citations.cites(SCALA_297_FINDING));
  }

  @Test
  void sbtsLevelIsNotPartOfTheTestName() {
    assertEquals(
        "rolling average covers exactly the last n readings",
        CiFailureCitations.testName(
            "[info] - rolling average covers exactly the last n readings *** FAILED ***"));
    assertEquals(
        "rejects a negative reading",
        CiFailureCitations.testName("[error] - rejects a negative reading *** FAILED ***"));
  }
}
