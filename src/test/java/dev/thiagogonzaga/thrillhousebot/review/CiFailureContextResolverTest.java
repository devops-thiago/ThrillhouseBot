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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.ArtifactZipFetcher;
import dev.thiagogonzaga.thrillhousebot.github.GitHubActionsClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCheckRunClient;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link CiFailureContextResolver} — the opt-in CI-failure review context (#59). */
class CiFailureContextResolverTest {

  private static final URI LOG_URI = URI.create("https://blob.example/log");

  private GitHubCheckRunClient checkRunClient;
  private GitHubActionsClient actionsClient;
  private ArtifactZipFetcher fetcher;

  @BeforeEach
  void setUp() {
    checkRunClient = mock(GitHubCheckRunClient.class);
    actionsClient = mock(GitHubActionsClient.class);
    fetcher = mock(ArtifactZipFetcher.class);
  }

  private CiFailureContextResolver resolver(boolean enabled, boolean logs, int maxChars) {
    return new CiFailureContextResolver(
        checkRunClient, actionsClient, fetcher, enabled, logs, maxChars);
  }

  private static CiStatusEvaluator.FailedCheck actionsFailure(
      long id, String name, int annotations) {
    return new CiStatusEvaluator.FailedCheck(
        name, "failure", id, "github-actions", name + " failed", "Summary of " + name, annotations);
  }

  private static CiStatusEvaluator.CiEvaluation evaluation(
      int pending, CiStatusEvaluator.FailedCheck... failed) {
    return new CiStatusEvaluator.CiEvaluation(
        List.of(), false, true, new CiStatusEvaluator.CiFailures(List.of(failed), pending));
  }

  private static GitHubCheckRunClient.Annotation annotation(
      String path, int line, String level, String message) {
    return new GitHubCheckRunClient.Annotation(path, line, level, "T", message);
  }

  @Nested
  class WhenThereIsNothingToSay {

    @Test
    void aDisabledDeploymentBuildsNoSectionAndMakesNoCall() {
      var section =
          resolver(false, true, 4000)
              .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "b", 2)));

      assertEquals("", section);
      assertFalse(resolver(false, false, 4000).enabled());
      verifyNoInteractions(checkRunClient, actionsClient, fetcher);
    }

    @Test
    void noFailingCheckBuildsNoSection() {
      assertEquals("", resolver(true, true, 4000).resolve("a", "o", "r", evaluation(0)));
      assertEquals("", resolver(true, true, 4000).resolve("a", "o", "r", null));
      assertTrue(resolver(true, false, 4000).enabled());
    }

    @Test
    void pendingChecksAloneBuildNoSection() {
      // The usual automatic review starts while CI is still running: that is not worth a section.
      assertEquals("", resolver(true, true, 4000).resolve("a", "o", "r", evaluation(4)));
      verifyNoInteractions(checkRunClient, actionsClient, fetcher);
    }
  }

  @Nested
  class Rendering {

    @Test
    void rendersEachFailureWithItsOutputAndAFailureFirstPageOfAnnotations() {
      when(checkRunClient.listAnnotations("a", "application/vnd.github+json", "o", "r", 11L, 20))
          .thenReturn(
              List.of(
                  annotation("README.md", 1, "warning", "style nit"),
                  annotation("src/FooTest.java", 42, "failure", "expected <1> but was <2>")));

      var section =
          resolver(true, false, 4000)
              .resolve("a", "o", "r", evaluation(2, actionsFailure(11L, "test", 5)));

      assertTrue(
          section.startsWith(
              "Checks on this commit that had completed without passing when this review started:"
                  + " 1\n2 other check(s) had not finished"),
          section);
      assertTrue(section.contains("### test (conclusion: failure)\n"), section);
      assertTrue(section.contains("Title: test failed\n"), section);
      assertTrue(section.contains("Summary:\nSummary of test\n"), section);
      assertTrue(section.contains("Annotations (2 of 5):\n"), section);
      var failure = section.indexOf("- src/FooTest.java:42 [failure] T: expected <1> but was <2>");
      var warning = section.indexOf("- README.md:1 [warning] T: style nit");
      assertTrue(failure > 0 && warning > failure, "failure-level annotations come first");
    }

    @Test
    void readsNoAnnotationsForAStatusOrARunThatReportedNone() {
      var status =
          new CiStatusEvaluator.FailedCheck(
              "codecov/patch", "error", 0L, null, null, "85% (-2%)", 0);
      var quiet = actionsFailure(12L, "build", 0);

      var section =
          resolver(true, false, 4000).resolve("a", "o", "r", evaluation(0, status, quiet));

      assertTrue(section.contains("### codecov/patch (conclusion: error)\nSummary:\n85% (-2%)"));
      assertFalse(section.contains("Annotations"));
      verifyNoInteractions(checkRunClient);
    }

    @Test
    void anAnnotationWithoutLineLevelOrTitleStillRendersItsMessage() {
      when(checkRunClient.listAnnotations(any(), any(), any(), any(), anyLong(), anyInt()))
          .thenReturn(
              List.of(new GitHubCheckRunClient.Annotation("a.py", null, null, null, "boom")));

      var section =
          resolver(true, false, 4000)
              .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "t", 1)));

      assertTrue(section.contains("- a.py boom\n"), section);
    }

    @Test
    void capsAnnotationsPerCheck() {
      var many = new ArrayList<GitHubCheckRunClient.Annotation>();
      for (var i = 0; i < 20; i++) {
        many.add(annotation("f.java", i + 1, "failure", "m" + i));
      }
      when(checkRunClient.listAnnotations(any(), any(), any(), any(), anyLong(), anyInt()))
          .thenReturn(many);

      var section =
          resolver(true, false, 20_000)
              .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "t", 40)));

      assertTrue(section.contains("Annotations (8 of 40):"), section);
      assertEquals(
          CiFailureContextResolver.MAX_ANNOTATIONS_PER_CHECK,
          section.lines().filter(l -> l.startsWith("- f.java")).count());
    }

    @Test
    void anUnreadableOrEmptyAnnotationPageIsSkipped() {
      when(checkRunClient.listAnnotations(any(), any(), any(), any(), eq(1L), anyInt()))
          .thenThrow(new RuntimeException("403"));
      when(checkRunClient.listAnnotations(any(), any(), any(), any(), eq(2L), anyInt()))
          .thenReturn(List.of());
      when(checkRunClient.listAnnotations(any(), any(), any(), any(), eq(3L), anyInt()))
          .thenReturn(null);

      var section =
          resolver(true, false, 4000)
              .resolve(
                  "a",
                  "o",
                  "r",
                  evaluation(
                      0,
                      actionsFailure(1, "x", 1),
                      actionsFailure(2, "y", 1),
                      actionsFailure(3, "z", 1)));

      assertTrue(
          section.contains("### x") && section.contains("### y") && section.contains("### z"));
      assertFalse(section.contains("Annotations"));
    }

    @Test
    void detailsOnlyTheFirstChecksAndNamesTheRest() {
      var failed =
          IntStream.rangeClosed(1, 7)
              .mapToObj(i -> actionsFailure(i, "job" + i, 0))
              .toArray(CiStatusEvaluator.FailedCheck[]::new);

      var section = resolver(true, false, 20_000).resolve("a", "o", "r", evaluation(0, failed));

      assertTrue(section.contains("### job5 "), section);
      assertFalse(section.contains("### job6"), section);
      assertTrue(section.contains("\nAlso failing, not detailed: job6, job7\n"), section);
    }

    @Test
    void aMissingConclusionRendersAsUnknown() {
      var check = new CiStatusEvaluator.FailedCheck("x", null, 0L, null, null, null, 0);

      var section = resolver(true, false, 4000).resolve("a", "o", "r", evaluation(0, check));

      assertTrue(section.contains("### x (conclusion: unknown)\n"), section);
      assertFalse(section.contains("Title:") || section.contains("Summary:"), section);
    }

    @Test
    void aMalformedAnnotationPageDegradesToNoSectionRatherThanFailingTheReview() {
      var page = new ArrayList<GitHubCheckRunClient.Annotation>();
      page.add(annotation("a", 1, "failure", "m"));
      page.add(null);
      when(checkRunClient.listAnnotations(any(), any(), any(), any(), anyLong(), anyInt()))
          .thenReturn(page);

      assertEquals(
          "",
          resolver(true, false, 4000)
              .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "t", 2))));
    }
  }

  @Nested
  class UntrustedText {

    @Test
    void stripsAnsiCarriageReturnsBidiOverridesAndOtherControlCharacters() {
      var check =
          new CiStatusEvaluator.FailedCheck(
              "evil\nname\u202E",
              "failure",
              0L,
              null,
              "\u001B[31mred\u001B[0m title\u0007",
              "line one\r\nhidden\rshown\tafter tab\u200B\u0000",
              0);

      var section = resolver(true, false, 4000).resolve("a", "o", "r", evaluation(0, check));

      assertTrue(section.contains("### evil name (conclusion: failure)"), section);
      assertTrue(section.contains("Title: red title\n"), section);
      assertTrue(section.contains("Summary:\nline one\nhidden\nshown after tab\n"), section);
      assertFalse(section.chars().anyMatch(c -> c == 0x1B || c == '\r' || c == 0x202E || c == 0));
    }

    @Test
    void cleanKeepsOrdinaryTextAndHandlesNull() {
      assertEquals("", CiFailureContextResolver.clean(null));
      assertEquals("", CiFailureContextResolver.clean(""));
      assertEquals("café ✓ 😀\nok", CiFailureContextResolver.clean("café ✓ 😀\nok"));
    }

    @Test
    void longFieldsAreClippedWithoutSplittingASurrogatePair() {
      var summary = "s".repeat(2_000);
      var check =
          new CiStatusEvaluator.FailedCheck("t".repeat(500), "failure", 0L, null, null, summary, 0);

      var section = resolver(true, false, 20_000).resolve("a", "o", "r", evaluation(0, check));

      assertTrue(section.contains("t".repeat(299) + "…"), section);
      assertFalse(section.contains("t".repeat(300)));
      assertTrue(section.contains("s".repeat(599) + "…"));
      assertEquals("a…", CiFailureContextResolver.clip("a😀b", 3));
      assertEquals("abc", CiFailureContextResolver.clip("abc", 3));
      assertEquals("…", CiFailureContextResolver.clip("abc", 1));
    }
  }

  @Nested
  class SizeCap {

    @Test
    void theWholeSectionIsCutAtALineBoundaryWithinTheCapKeepingTheHeader() {
      var failed =
          IntStream.rangeClosed(1, 5)
              .mapToObj(
                  i ->
                      new CiStatusEvaluator.FailedCheck(
                          "job" + i, "failure", 0L, null, "t", "x".repeat(550), 0))
              .toArray(CiStatusEvaluator.FailedCheck[]::new);

      var section = resolver(true, false, 1_000).resolve("a", "o", "r", evaluation(3, failed));

      assertTrue(section.length() <= 1_000, "length " + section.length());
      assertTrue(section.endsWith("\n" + CiFailureContextResolver.TRUNCATION_NOTE), section);
      assertTrue(section.startsWith("Checks on this commit"), section);
      assertTrue(section.contains("3 other check(s) had not finished"), section);
    }

    @Test
    void aSectionWithinTheCapIsUntouched() {
      assertEquals("abc", CiFailureContextResolver.cap("abc", 3));
    }

    @Test
    void aSectionWithNoLineBreakInRangeIsClipped() {
      var capped = CiFailureContextResolver.cap("y".repeat(200), 100);

      assertTrue(capped.length() <= 100, capped);
      assertTrue(capped.endsWith("…\n" + CiFailureContextResolver.TRUNCATION_NOTE), capped);
    }
  }

  @Nested
  class JobLogs {

    private Response redirect() {
      return Response.status(302).location(LOG_URI).build();
    }

    /**
     * #922: the failing test is named only in the log, so the log is read whether or not the raw
     * tail is shown; with include-logs off only the lines that name a failed test reach the prompt.
     */
    @Test
    void withTheTailSwitchOffOnlyTheFailingTestLinesAreShown() {
      when(actionsClient.downloadJobLogs("a", "application/vnd.github+json", "o", "r", 1L))
          .thenReturn(redirect());
      when(fetcher.fetchTail(LOG_URI, CiFailureContextResolver.LOG_TAIL_BYTES))
          .thenReturn(
              """
                  2026-09-29T10:00:00.1234567Z > node --test
                  2026-09-29T10:00:00.2000000Z ✔ overlapping bookings conflict (0.4ms)
                  2026-09-29T10:00:00.3000000Z ✖ back-to-back bookings do not conflict (0.9ms)
                  2026-09-29T10:00:00.4000000Z ##[error]Process completed with exit code 1.
                  2026-09-29T10:00:01.0000000Z Post job cleanup.
                  """
                  .getBytes(StandardCharsets.UTF_8));

      var section =
          resolver(true, false, 4000)
              .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "test", 0)));

      assertTrue(
          section.contains(
              "Failing tests named in the job log:\n"
                  + "- \u2716 back-to-back bookings do not conflict (0.9ms)\n"),
          section);
      assertFalse(section.contains("Job log tail"), section);
      assertFalse(section.contains("Post job cleanup"), section);
    }

    @Test
    void aLogNamingNoFailedTestAddsNothingWithTheTailSwitchOff() {
      when(actionsClient.downloadJobLogs(any(), any(), any(), any(), anyLong()))
          .thenAnswer(ignored -> redirect());
      when(fetcher.fetchTail(any(), anyInt()))
          .thenReturn("npm ERR! missing script: build\n".getBytes(StandardCharsets.UTF_8));

      var section =
          resolver(true, false, 4000)
              .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "build", 0)));

      assertFalse(section.contains("Failing tests named"), section);
      assertFalse(section.contains("npm ERR!"), section);
    }

    @Test
    void appendsTheTailOfAFailingActionsJobLogWithoutTimestamps() {
      when(actionsClient.downloadJobLogs("a", "application/vnd.github+json", "o", "r", 1L))
          .thenReturn(redirect());
      when(fetcher.fetchTail(LOG_URI, CiFailureContextResolver.LOG_TAIL_BYTES))
          .thenReturn(
              """
                  2026-09-29T10:00:00.1234567Z Running tests

                  2026-09-29T10:00:01.0000000Z \u001B[31mFAILED\u001B[0m FooTest
                  """
                  .getBytes(StandardCharsets.UTF_8));

      var section =
          resolver(true, true, 4000)
              .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "t", 0)));

      assertTrue(section.contains("Job log tail:\nRunning tests\nFAILED FooTest\n"), section);
    }

    @Test
    void theFailingTestsPrecedeTheTailWhenBothAreShown() {
      when(actionsClient.downloadJobLogs(any(), any(), any(), any(), anyLong()))
          .thenAnswer(ignored -> redirect());
      when(fetcher.fetchTail(any(), anyInt()))
          .thenReturn("--- FAIL: TestOverlap (0.00s)\nFAIL\n".getBytes(StandardCharsets.UTF_8));

      var section =
          resolver(true, true, 4000)
              .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "go", 0)));

      assertTrue(
          section.indexOf("Failing tests named in the job log:\n- --- FAIL: TestOverlap")
              < section.indexOf("Job log tail:"),
          section);
    }

    @Test
    void readsAtMostTwoLogsAndOnlyForActionsJobsThatFailedOrTimedOut() {
      when(actionsClient.downloadJobLogs(any(), any(), any(), any(), anyLong()))
          .thenAnswer(ignored -> redirect());
      when(fetcher.fetchTail(any(), anyInt())).thenReturn("boom".getBytes(StandardCharsets.UTF_8));
      var cancelled =
          new CiStatusEvaluator.FailedCheck("c", "cancelled", 1L, "github-actions", null, null, 0);
      var external =
          new CiStatusEvaluator.FailedCheck("e", "failure", 2L, "circleci", null, null, 0);
      var timedOut =
          new CiStatusEvaluator.FailedCheck("t", "TIMED_OUT", 3L, "github-actions", null, null, 0);

      var section =
          resolver(true, true, 20_000)
              .resolve(
                  "a",
                  "o",
                  "r",
                  evaluation(
                      0,
                      cancelled,
                      external,
                      timedOut,
                      actionsFailure(4L, "f", 0),
                      actionsFailure(5L, "g", 0)));

      verify(actionsClient).downloadJobLogs(any(), any(), any(), any(), eq(3L));
      verify(actionsClient).downloadJobLogs(any(), any(), any(), any(), eq(4L));
      verify(actionsClient, times(2)).downloadJobLogs(any(), any(), any(), any(), anyLong());
      assertEquals(2, section.split("Job log tail:\nboom", -1).length - 1, section);
    }

    @Test
    void aStatusOrAConclusionlessRunHasNoLogToRead() {
      var status = new CiStatusEvaluator.FailedCheck("s", "failure", 0L, null, null, null, 0);
      var noConclusion =
          new CiStatusEvaluator.FailedCheck("n", null, 6L, "github-actions", null, null, 0);

      resolver(true, true, 4000).resolve("a", "o", "r", evaluation(0, status, noConclusion));

      verifyNoInteractions(actionsClient, fetcher);
    }

    @Test
    void anEmptyLogAddsNothing() {
      when(actionsClient.downloadJobLogs(any(), any(), any(), any(), anyLong()))
          .thenAnswer(ignored -> redirect());
      when(fetcher.fetchTail(any(), anyInt())).thenReturn(new byte[0]);

      var section =
          resolver(true, true, 4000)
              .resolve(
                  "a",
                  "o",
                  "r",
                  evaluation(0, actionsFailure(1, "x", 0), actionsFailure(2, "y", 0)));

      assertFalse(section.contains("Job log tail"), section);
      verify(actionsClient, times(2)).downloadJobLogs(any(), any(), any(), any(), anyLong());
    }

    @Test
    void aLogThatCannotBeReadIsSkipped() {
      when(actionsClient.downloadJobLogs(any(), any(), any(), any(), eq(1L)))
          .thenReturn(Response.status(404).build());
      when(actionsClient.downloadJobLogs(any(), any(), any(), any(), eq(2L)))
          .thenThrow(new RuntimeException("410"));

      var section =
          resolver(true, true, 4000)
              .resolve(
                  "a",
                  "o",
                  "r",
                  evaluation(0, actionsFailure(1, "x", 0), actionsFailure(2, "y", 0)));

      assertTrue(section.contains("### x") && section.contains("### y"), section);
      assertFalse(section.contains("Job log tail"));
      verifyNoInteractions(fetcher);
    }

    @Test
    void aNullDownloadResponseIsSkipped() {
      when(actionsClient.downloadJobLogs(any(), any(), any(), any(), anyLong())).thenReturn(null);

      var section =
          resolver(true, true, 4000)
              .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "x", 0)));

      assertFalse(section.contains("Job log tail"));
    }
  }

  @Nested
  class LogTail {

    @Test
    void anEmptyLogRendersNothing() {
      assertEquals("", CiFailureContextResolver.renderLogTail(new byte[0]));
    }

    @Test
    void aFullWindowDropsItsFirstLineAsAFragment() {
      var bytes = new byte[CiFailureContextResolver.LOG_TAIL_BYTES];
      Arrays.fill(bytes, (byte) 'x');
      var tail = "\nlast line".getBytes(StandardCharsets.UTF_8);
      System.arraycopy(tail, 0, bytes, bytes.length - tail.length, tail.length);

      assertEquals("last line", CiFailureContextResolver.renderLogTail(bytes));
    }

    @Test
    void keepsTheLastLinesWithinTheLineAndCharacterCaps() {
      var sb = new StringBuilder();
      for (var i = 0; i < 100; i++) {
        sb.append("line ").append(i).append('\n');
      }

      var rendered =
          CiFailureContextResolver.renderLogTail(sb.toString().getBytes(StandardCharsets.UTF_8));

      var lines = rendered.split("\n");
      assertEquals(CiFailureContextResolver.LOG_TAIL_LINES, lines.length);
      assertEquals("line 99", lines[lines.length - 1]);

      var wide = new StringBuilder();
      for (var i = 0; i < 20; i++) {
        wide.append(String.valueOf((char) ('a' + i)).repeat(200)).append('\n');
      }
      var capped =
          CiFailureContextResolver.renderLogTail(wide.toString().getBytes(StandardCharsets.UTF_8));
      assertTrue(capped.length() <= CiFailureContextResolver.MAX_LOG_CHARS, "" + capped.length());
      assertTrue(capped.endsWith("t".repeat(200)));
    }
  }

  @Test
  void theConfigConstructorReadsTheCiContextSwitches() {
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    var ciContext = mock(ThrillhouseConfig.CiContextConfig.class);
    when(config.review()).thenReturn(review);
    when(review.ciContext()).thenReturn(ciContext);
    when(ciContext.enabled()).thenReturn(true);
    when(ciContext.includeLogs()).thenReturn(false);
    when(ciContext.maxChars()).thenReturn(4000);

    var fromConfig = new CiFailureContextResolver(checkRunClient, actionsClient, fetcher, config);

    assertTrue(fromConfig.enabled());
  }

  /** #922: the lines each test runner writes about a failed test, one fixture per runner. */
  @Nested
  class FailingTestLines {

    private List<String> lines(String log) {
      return CiFailureContextResolver.failingTestLines(log.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! - in com.acme.BedTest",
          "[ERROR]   BedRepositoryTest.wardIsEscaped:42 expected: <1> but was: <0>",
          "BookingTest > backToBackBookingsDoNotConflict() FAILED",
          "Chrome Headless 120.0 (Linux x86_64) ShiftBoard a full shift rejects further sign-ups FAILED",
          "not ok 3 - back-to-back bookings do not conflict",
          "\u2716 back-to-back bookings do not conflict (0.9ms)",
          "\u2715 rejects a full shift (4 ms)",
          "\u00D7 rejects a full shift 3ms",
          "\u25CF ShiftBoard \u203A a full shift rejects further sign-ups",
          "FAIL src/app/shift-board.spec.ts",
          "FAIL src/app/bookings.test.js",
          "[ERROR] Tests run: 2, Errors: 1 <<< ERROR! - in com.acme.BedTest",
          "FAILED tests/test_store.py::test_search_escapes_event_name - AssertionError",
          "FAIL: test_hold_expiry (tests.test_holds.HoldTest)",
          "--- FAIL: TestTransactionsForVehicle (0.00s)",
          "test queue::tests::accepts_max_copies ... FAILED",
          "Failed RoomBooking.Tests.BookingTests.BackToBack [12 ms]",
          "- a full berth rejects arrivals *** FAILED ***",
          "==> X port.EventRepositorySuite.findByContainer  0.012s",
          "1 - queue_test (Failed)",
          "1/5 queue.test.submit accepts copies equal to the maximum...FAIL (TooManyCopies)",
          "rspec ./spec/booking_spec.rb:12 # Booking rejects overlaps"
        })
    void eachRunnersFailedTestLineIsNamed(String line) {
      assertEquals(List.of(line), lines("Running tests\n" + line + "\nPost job cleanup.\n"));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "##[error]Process completed with exit code 1.",
          "BUILD FAILED in 12s",
          "npm ERR! Test failed.  See above for more details.",
          "\u2714 overlapping bookings conflict (0.4ms)",
          "ok 2 - overlapping bookings conflict",
          "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.6.0:test",
          "FAIL",
          "FAIL build step",
          "\u25CF Running coverage on untested files"
        })
    void otherFailureWordsAreNotATest(String line) {
      assertEquals(List.of(), lines(line + "\n"));
    }

    @Test
    void theWholeWindowIsScannedEachLineOnceAndCapped() {
      var log = new StringBuilder("--- FAIL: TestFirst (0.00s)\n");
      for (var i = 0; i < 200; i++) {
        log.append("cleanup line ").append(i).append('\n');
      }
      log.append("--- FAIL: TestFirst (0.00s)\n");
      for (var i = 0; i < 20; i++) {
        log.append("--- FAIL: TestMore").append(i).append(" (0.00s)\n");
      }

      var found = lines(log.toString());

      assertEquals("--- FAIL: TestFirst (0.00s)", found.get(0));
      assertEquals(CiFailureContextResolver.MAX_FAILING_TEST_LINES, found.size());
      assertEquals(1, found.stream().filter(l -> l.contains("TestFirst")).count());
    }

    @Test
    void aFullWindowDropsItsFirstLineAsAFragment() {
      var bytes = new byte[CiFailureContextResolver.LOG_TAIL_BYTES];
      Arrays.fill(bytes, (byte) 'x');
      var head = "--- FAIL: TestCut (0.00s)".getBytes(StandardCharsets.UTF_8);
      System.arraycopy(head, 0, bytes, 0, head.length);
      var tail = "\n--- FAIL: TestKept (0.00s)".getBytes(StandardCharsets.UTF_8);
      System.arraycopy(tail, 0, bytes, bytes.length - tail.length, tail.length);

      assertEquals(
          List.of("--- FAIL: TestKept (0.00s)"), CiFailureContextResolver.failingTestLines(bytes));
    }

    @Test
    void noBytesNameNoTest() {
      assertEquals(List.of(), CiFailureContextResolver.failingTestLines(new byte[0]));
      assertEquals(List.of(), CiFailureContextResolver.failingTestLines(null));
      assertEquals("", CiFailureContextResolver.renderLogTail(null));
    }
  }

  /**
   * #922: a notice the runner attaches to the workflow (a deprecated Node.js version) never names a
   * test, and one review tied its finding to it, so it is left out; the failure-level exit-code
   * line is kept.
   */
  @Test
  void theRunnersOwnNoticesAreLeftOutOfTheAnnotations() {
    when(checkRunClient.listAnnotations(any(), any(), any(), any(), anyLong(), anyInt()))
        .thenReturn(
            List.of(
                annotation(
                    ".github",
                    26,
                    "warning",
                    "Node.js 20 actions are deprecated. Please update the following actions."),
                annotation(".github", 26, "failure", "Process completed with exit code 1."),
                annotation("src/app.ts", 3, "warning", "Unused variable 'x'")));

    var section =
        resolver(true, false, 4000)
            .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "test", 3)));

    assertTrue(section.contains("Process completed with exit code 1."), section);
    assertTrue(section.contains("Unused variable"), section);
    assertFalse(section.contains("Node.js 20"), section);
  }

  @Test
  void aPageOfOnlyRunnerNoticesAddsNoAnnotationsBlock() {
    when(checkRunClient.listAnnotations(any(), any(), any(), any(), anyLong(), anyInt()))
        .thenReturn(List.of(annotation(".github", 1, "notice", "Cache not found for key")));

    var section =
        resolver(true, false, 4000)
            .resolve("a", "o", "r", evaluation(0, actionsFailure(1, "test", 1)));

    assertFalse(section.contains("Annotations"), section);
  }
}
