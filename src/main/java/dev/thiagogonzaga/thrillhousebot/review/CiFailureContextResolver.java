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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.ArtifactZipFetcher;
import dev.thiagogonzaga.thrillhousebot.github.GitHubActionsClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCheckRunClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Builds the opt-in CI-failure review context (#59): a bounded, plain-text summary of the checks
 * that had already completed without passing on the head commit when the review started, so a
 * finding can be tied to the failing test or build step it explains.
 *
 * <p>The failing checks are not fetched here. They come from the {@link
 * CiStatusEvaluator.CiEvaluation} the CI gate reads for the same head, which carries each failing
 * check's name, conclusion and output panel ({@link CiStatusEvaluator.FailedCheck}); this class
 * only adds what that list cannot hold — one page of annotations per detailed check, the failing
 * tests named in up to {@link #MAX_LOG_FETCHES} failing GitHub Actions job logs, and, behind a
 * separate switch, the tail of those logs.
 *
 * <p>The failing tests are read from the log because that is the only place a test runner names
 * them (#922). A failed Actions job's annotations carry "Process completed with exit code 1" and
 * runner notices such as a Node.js deprecation, so across one corpus round twelve of twelve
 * follow-up reviews saw only "test ❌ Failed", and one tied its finding to the deprecation notice.
 * The whole downloaded window is scanned for the lines runners write about a failed test, because
 * the last lines of a job log are the runner's own cleanup, not the test output.
 *
 * <p>Every string here is produced by code the pull request can change: a test can print anything,
 * including text shaped like an instruction or a prompt heading. So each field is stripped of
 * control and formatting characters (ANSI colour codes, carriage returns, bidi overrides), clipped,
 * and the whole section is capped at {@code max-chars}. The caller fences the result like every
 * other untrusted slot; nothing here is ever phrased as, or allowed to become, an instruction.
 *
 * <p>Pending checks contribute one line, and only alongside a failure: a review that starts while
 * CI is still running — the usual case for an automatic review — gets no section at all rather than
 * a paragraph saying nothing is known yet. Best-effort throughout: nothing here can fail a review.
 */
@ApplicationScoped
public class CiFailureContextResolver {

  private static final String ACCEPT = "application/vnd.github+json";

  /** The app slug of a GitHub Actions check run, whose id is also its job id. */
  static final String GITHUB_ACTIONS_APP = "github-actions";

  /** Failing checks rendered in full; the rest are named on one line. */
  static final int MAX_CHECKS_DETAILED = 5;

  /** Annotations requested per detailed check — one page, one call. */
  static final int ANNOTATIONS_PAGE_SIZE = 20;

  /** Annotations rendered per check, failure-level first. */
  static final int MAX_ANNOTATIONS_PER_CHECK = 8;

  /** Job logs downloaded per review when logs are enabled. */
  static final int MAX_LOG_FETCHES = 2;

  /** Bytes kept from the end of a job log. */
  static final int LOG_TAIL_BYTES = 16 * 1024;

  /** Log lines rendered per job. */
  static final int LOG_TAIL_LINES = 30;

  /** Characters of log tail rendered per job. */
  static final int MAX_LOG_CHARS = 1_500;

  /** Failing-test lines rendered per job, read from the whole downloaded window. */
  static final int MAX_FAILING_TEST_LINES = 10;

  /** Characters of a check's output summary rendered. */
  static final int MAX_SUMMARY_CHARS = 600;

  /** Characters of any single line — a title, an annotation, a log line. */
  static final int MAX_LINE_CHARS = 300;

  /**
   * The heading a detailed check opens with, its conclusion marker, and the line naming the checks
   * not detailed. {@link CiFailureCitations} reads the rendered section back by these same three
   * strings, so the two cannot drift apart.
   */
  static final String CHECK_HEADING = "### ";

  static final String CONCLUSION = " (conclusion: ";

  static final String NOT_DETAILED = "Also failing, not detailed:";

  /** The prefix each check named under {@link #NOT_DETAILED} is listed with, one per line. */
  static final String NAMED_ONLY = "- ";

  /**
   * The line a detailed check's failing tests are listed under, each with the {@link #NAMED_ONLY}
   * prefix; {@link CiFailureCitations} reads them back by it.
   */
  static final String FAILING_TESTS = "Failing tests named in the job log:";

  /** Appended when the whole section had to be cut to {@code max-chars}. */
  static final String TRUNCATION_NOTE = "[CI context truncated to its character budget]";

  /** Conclusions whose job log is worth reading: the job ran and stopped on an error. */
  private static final Set<String> LOG_WORTHY_CONCLUSIONS = Set.of("failure", "timed_out");

  // ANSI CSI sequences (colour, cursor movement) that CI runners emit into logs and summaries.
  private static final Pattern ANSI_CSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");

  /** The status Jest and Vitest open a failed test file's line with. */
  private static final String JEST_FAILED_FILE = "FAIL ";

  /** The status Jest and Vitest open a passed test file's line with. */
  private static final String JEST_PASSED_FILE = "PASS ";

  /** The bullet Jest heads each failed test, and its other notices, with. */
  private static final String JEST_BULLET = "\u25CF ";

  /**
   * The lines test runners write about one failed test, each tried against a cleaned log line with
   * its timestamp removed. Plain string tests where they suffice, and the few expressions anchored
   * and possessive, so no line the pull request's own code prints can make one backtrack.
   */
  private static final List<Predicate<String>> FAILING_TEST_LINES =
      List.of(
          // Maven Surefire / Failsafe: the class line, and the summary's "Class.method:line" rows.
          line -> line.contains("<<< FAILURE!") || line.contains("<<< ERROR!"),
          CiFailureContextResolver::isSurefireRow,
          // Gradle "FooTest > bar() FAILED", Karma "Chrome Headless (Linux) Suite name FAILED".
          line -> line.endsWith(" FAILED") && (line.contains(" > ") || isKarmaLine(line)),
          // TAP (node --test, prove): "not ok 3 - name".
          Pattern.compile("^not ok \\d").asPredicate(),
          // node --test spec reporter, Jest and Vitest: "✖ name", "✕ name", "× name", "● A › b".
          line ->
              line.startsWith("\u2716 ")
                  || line.startsWith("\u2715 ")
                  || line.startsWith("\u00D7 "),
          line -> line.startsWith(JEST_BULLET) && line.contains(" \u203A "),
          // Jest and Vitest file lines: "FAIL src/app.test.ts", "FAIL src/__tests__/app.js".
          line -> isJestFile(line, JEST_FAILED_FILE),
          // pytest "FAILED tests/test_x.py::test_y" and "ERROR tests/test_x.py::test_y" (an
          // error in the test's setup): a Python file, not a logger's "ERROR app::db: refused".
          // unittest "FAIL: test_y (module.Case)".
          Pattern.compile("^(?:FAILED|ERROR) [^\\s:]++(?<=\\.py)::").asPredicate(),
          Pattern.compile("^(?:FAIL|ERROR): \\w++ \\((?:\\w++\\.)++\\w++\\)").asPredicate(),
          // go test "--- FAIL: TestY", cargo test "test y ... FAILED" and its doctest
          // "test src/lib.rs - add (line 5) - compile fail ... FAILED".
          line -> line.startsWith("--- FAIL: "),
          Pattern.compile("^test \\S++ \\.\\.\\. FAILED").asPredicate(),
          Pattern.compile("^test \\S++ - [^()\\s]++ \\(line \\d++\\)[^.]*+\\.\\.\\. FAILED")
              .asPredicate(),
          // dotnet test "Failed Ns.Class.Method [12 ms]" (a theory's arguments inlined in the
          // name), ScalaTest "*** FAILED ***", munit "==> X".
          CiFailureContextResolver::isDotnetFailedLine,
          line -> line.contains("*** FAILED ***"),
          line -> line.startsWith("==> X "),
          // ctest "1 - name (Failed)", zig "1/5 test.name...FAIL (Error)" (older releases: "Test
          // [1/5] test.name... FAIL (Error)"), rspec failed examples.
          line -> line.endsWith("(Failed)"),
          line -> line.contains("...FAIL (") || line.contains("... FAIL ("),
          Pattern.compile("^rspec \\./[^\\s:]++:\\d").asPredicate());

  /** A dotnet test duration as the console logger prints it: {@code [12 ms]}, {@code [< 1 ms]}. */
  private static final Pattern DOTNET_DURATION = Pattern.compile("\\[(?:< )?+\\d[^\\]]*+]");

  /**
   * File extensions a tool's {@code name.ext:line} row ends its token with, where Surefire's row
   * has a test method.
   */
  private static final Set<String> FILE_EXTENSIONS =
      Set.of(
          "java",
          "kt",
          "kts",
          "groovy",
          "scala",
          "gradle",
          "xml",
          "properties",
          "yml",
          "yaml",
          "json",
          "js",
          "jsx",
          "mjs",
          "cjs",
          "ts",
          "tsx",
          "vue",
          "css",
          "scss",
          "html",
          "md",
          "py",
          "rb",
          "go",
          "rs",
          "cs",
          "sql",
          "sh",
          "txt");

  /** The bullets Jest prints under a failed test file that head no test. */
  private static final Set<String> JEST_NOTICES = Set.of("Console", "Test suite failed to run");

  /** The browser names Karma prints before a browser's version on a test's result line. */
  private static final Set<String> KARMA_BROWSERS =
      Set.of(
          "Chrome",
          "Chrome Headless",
          "HeadlessChrome",
          "Chromium",
          "Chromium Headless",
          "Firefox",
          "Firefox Headless",
          "Safari",
          "Mobile Safari",
          "Edge",
          "Opera",
          "PhantomJS",
          "Electron");

  /** An indented {@code [ERROR]} row that opens with a token and a line number, not a column. */
  private static final Pattern SUREFIRE_ROW =
      Pattern.compile("^\\[ERROR] {2,}+([^\\s:]++):\\d++(?!:)");

  /**
   * The path GitHub Actions gives the runner's own annotations, which never name a test: the
   * workflow directory itself, or a workflow file in it.
   */
  private static final String RUNNER_ANNOTATION_PATH = ".github";

  // The ISO-8601 timestamp GitHub Actions prefixes to every log line: tokens, not information.
  private static final Pattern LOG_TIMESTAMP =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z ?");

  private final GitHubCheckRunClient checkRunClient;
  private final GitHubActionsClient actionsClient;
  private final ArtifactZipFetcher fetcher;
  private final boolean enabled;
  private final boolean includeLogs;
  private final int maxChars;

  @Inject
  public CiFailureContextResolver(
      @RestClient GitHubCheckRunClient checkRunClient,
      @RestClient GitHubActionsClient actionsClient,
      ArtifactZipFetcher fetcher,
      ThrillhouseConfig config) {
    this(
        checkRunClient,
        actionsClient,
        fetcher,
        config.review().ciContext().enabled(),
        config.review().ciContext().includeLogs(),
        config.review().ciContext().maxChars());
  }

  /** Visible for tests: the switches and the cap are passed directly. */
  CiFailureContextResolver(
      GitHubCheckRunClient checkRunClient,
      GitHubActionsClient actionsClient,
      ArtifactZipFetcher fetcher,
      boolean enabled,
      boolean includeLogs,
      int maxChars) {
    this.checkRunClient = checkRunClient;
    this.actionsClient = actionsClient;
    this.fetcher = fetcher;
    this.enabled = enabled;
    this.includeLogs = includeLogs;
    this.maxChars = maxChars;
  }

  /** Whether the CI-failure context is switched on for this deployment. */
  boolean enabled() {
    return enabled;
  }

  /**
   * The CI-failure section for the review prompt, unfenced, or {@code ""} when the feature is off
   * or no check had completed without passing when {@code evaluation} was read.
   */
  String resolve(
      String auth, String owner, String repo, CiStatusEvaluator.CiEvaluation evaluation) {
    if (!enabled || evaluation == null || evaluation.failures().failed().isEmpty()) {
      return "";
    }
    try {
      return cap(render(auth, owner, repo, evaluation.failures()), maxChars);
    } catch (RuntimeException e) {
      Log.warn("CI-failure context could not be built, continuing without it", e);
      return "";
    }
  }

  private String render(
      String auth, String owner, String repo, CiStatusEvaluator.CiFailures failures) {
    var failed = failures.failed();
    var sb = new StringBuilder();
    sb.append("Checks on this commit that had completed without passing when this review started: ")
        .append(failed.size())
        .append('\n');
    if (failures.pending() > 0) {
      sb.append(failures.pending())
          .append(" other check(s) had not finished; their outcome is unknown to this review.\n");
    }
    var logsLeft = MAX_LOG_FETCHES;
    var detailed = Math.min(failed.size(), MAX_CHECKS_DETAILED);
    for (var i = 0; i < detailed; i++) {
      var check = failed.get(i);
      sb.append('\n');
      appendCheck(sb, check);
      appendAnnotations(sb, auth, owner, repo, check);
      if (logsLeft > 0 && logWorthy(check)) {
        logsLeft--;
        appendLog(sb, auth, owner, repo, check.checkRunId());
      }
    }
    if (failed.size() > detailed) {
      // One name per line: a check name can itself hold a comma, so a joined list could not be
      // read back into the names it was built from.
      sb.append('\n').append(NOT_DETAILED).append('\n');
      failed
          .subList(detailed, failed.size())
          .forEach(c -> sb.append(NAMED_ONLY).append(oneLine(c.name())).append('\n'));
    }
    return sb.toString();
  }

  private static void appendCheck(StringBuilder sb, CiStatusEvaluator.FailedCheck check) {
    sb.append(CHECK_HEADING)
        .append(oneLine(check.name()))
        .append(CONCLUSION)
        .append(oneLine(check.conclusion() == null ? "unknown" : check.conclusion()))
        .append(")\n");
    var title = oneLine(check.title());
    if (!title.isEmpty()) {
      sb.append("Title: ").append(title).append('\n');
    }
    var summary = clip(clean(check.summary()).strip(), MAX_SUMMARY_CHARS);
    if (!summary.isEmpty()) {
      sb.append("Summary:\n").append(summary).append('\n');
    }
  }

  private void appendAnnotations(
      StringBuilder sb,
      String auth,
      String owner,
      String repo,
      CiStatusEvaluator.FailedCheck check) {
    if (check.checkRunId() <= 0 || check.annotationsCount() <= 0) {
      return;
    }
    List<GitHubCheckRunClient.Annotation> page;
    try {
      page =
          checkRunClient.listAnnotations(
              auth, ACCEPT, owner, repo, check.checkRunId(), ANNOTATIONS_PAGE_SIZE);
    } catch (RuntimeException e) {
      Log.debugf(e, "Could not read annotations of check run %d", check.checkRunId());
      return;
    }
    if (page == null || page.isEmpty()) {
      return;
    }
    // A notice or warning the runner attaches to the workflow itself (a deprecated Node.js version,
    // a cache miss) never explains a failed test, and one review tied its finding to exactly that
    // (#922), so it is left out; the runner's failure-level "Process completed with exit code N" is
    // kept, because it is true.
    var ordered =
        new ArrayList<>(page.stream().filter(annotation -> !isRunnerNotice(annotation)).toList());
    if (ordered.isEmpty()) {
      return;
    }
    // Failure-level annotations first: a warning on the same page rarely explains the failure.
    ordered.sort(Comparator.comparingInt(CiFailureContextResolver::levelRank));
    var shown = Math.min(ordered.size(), MAX_ANNOTATIONS_PER_CHECK);
    sb.append("Annotations (")
        .append(shown)
        .append(" of ")
        .append(check.annotationsCount())
        .append("):\n");
    for (var annotation : ordered.subList(0, shown)) {
      sb.append("- ").append(formatAnnotation(annotation)).append('\n');
    }
  }

  /**
   * A warning or notice the runner attaches to the workflow itself: anything on the workflow
   * directory, and on a workflow file a notice or a deprecation warning. Any other warning on a
   * workflow file is about that file's own content (an unpinned action, a bad expression) and is
   * kept.
   */
  private static boolean isRunnerNotice(GitHubCheckRunClient.Annotation annotation) {
    var path = annotation.path();
    if (levelRank(annotation) == 0 || path == null) {
      return false;
    }
    if (path.equals(RUNNER_ANNOTATION_PATH)) {
      return true;
    }
    return path.startsWith(RUNNER_ANNOTATION_PATH + "/")
        && ("notice".equalsIgnoreCase(annotation.annotationLevel())
            || oneLine(annotation.message()).toLowerCase(Locale.ROOT).contains("deprecated"));
  }

  /**
   * Karma's "Chrome Headless 120.0.0.0 (Linux x86_64) Suite name FAILED": a browser Karma names,
   * then its version number, right before the opening parenthesis. A build step whose name merely
   * holds a parenthesis ("Upload coverage (codecov) FAILED"), even after a version ("node 20.11.0
   * (alpine) build FAILED"), names no browser there.
   */
  private static boolean isKarmaLine(String line) {
    var open = line.indexOf(" (");
    if (open <= 0) {
      return false;
    }
    var prefix = line.substring(0, open);
    var space = prefix.lastIndexOf(' ');
    if (space < 0 || !KARMA_BROWSERS.contains(prefix.substring(0, space))) {
      return false;
    }
    var version = prefix.substring(space + 1);
    return !version.isEmpty() && Character.isDigit(version.charAt(0)) && version.contains(".");
  }

  /**
   * dotnet's "Failed Ns.Class.Method [12 ms]": the name runs to the duration in brackets that ends
   * the line, so a theory's display name with its arguments ("Math.Add(x: 0, y: 1)") is one test.
   */
  private static boolean isDotnetFailedLine(String line) {
    var duration = line.lastIndexOf(" [");
    return line.startsWith("Failed ")
        && duration > "Failed".length()
        && DOTNET_DURATION.matcher(line.substring(duration + 1)).matches()
        && isQualifiedTestName(line.substring("Failed ".length(), duration));
  }

  /**
   * Whether {@code name} opens with a namespace-qualified method, as every dotnet test name does
   * ({@code Ns.Class.Method}, then a theory's arguments): a step's "Failed 3 checks" or "Failed
   * deploy" is not one.
   */
  private static boolean isQualifiedTestName(String name) {
    var arguments = name.indexOf('(');
    var method = arguments < 0 ? name : name.substring(0, arguments);
    return method.indexOf(' ') < 0 && method.contains(".");
  }

  /**
   * Surefire's summary row {@code [ERROR] BedTest.wardIsEscaped:42 expected …}: an indented {@code
   * Class.method} token and the test's line number. A tool's output that Maven re-emits indented is
   * not a test: a path ({@code src/app.ts:12}), a file name ({@code App.java:12}), a {@code
   * file:line:column} position, or a bare {@code 12:5} position.
   */
  private static boolean isSurefireRow(String line) {
    var row = SUREFIRE_ROW.matcher(line);
    if (!row.find()) {
      return false;
    }
    var test = row.group(1);
    var method = test.substring(test.lastIndexOf('.') + 1);
    return test.contains(".")
        && test.indexOf('/') < 0
        && test.indexOf('\\') < 0
        && !FILE_EXTENSIONS.contains(method.toLowerCase(Locale.ROOT));
  }

  private static int levelRank(GitHubCheckRunClient.Annotation annotation) {
    return "failure".equalsIgnoreCase(annotation.annotationLevel()) ? 0 : 1;
  }

  private static String formatAnnotation(GitHubCheckRunClient.Annotation annotation) {
    var location = oneLine(annotation.path());
    if (annotation.startLine() != null) {
      location = location + ":" + annotation.startLine();
    }
    var level = oneLine(annotation.annotationLevel());
    var title = oneLine(annotation.title());
    var message = oneLine(annotation.message());
    var sb = new StringBuilder(location);
    if (!level.isEmpty()) {
      sb.append(" [").append(level).append(']');
    }
    if (!title.isEmpty()) {
      sb.append(' ').append(title).append(':');
    }
    return clip(sb.append(' ').append(message).toString().strip(), MAX_LINE_CHARS);
  }

  private static boolean logWorthy(CiStatusEvaluator.FailedCheck check) {
    return check.checkRunId() > 0
        && GITHUB_ACTIONS_APP.equals(check.appSlug())
        && check.conclusion() != null
        && LOG_WORTHY_CONCLUSIONS.contains(check.conclusion().toLowerCase(Locale.ROOT));
  }

  /**
   * The failing tests one job's log names, and with {@code include-logs} on its tail. The log is
   * downloaded once for both; a log that cannot be read adds nothing.
   */
  private void appendLog(StringBuilder sb, String auth, String owner, String repo, long jobId) {
    var bytes = logBytes(auth, owner, repo, jobId);
    var failingTests = failingTestLines(bytes);
    if (!failingTests.isEmpty()) {
      sb.append(FAILING_TESTS).append('\n');
      failingTests.forEach(line -> sb.append(NAMED_ONLY).append(line).append('\n'));
    }
    var tail = includeLogs ? renderLogTail(bytes) : "";
    if (!tail.isEmpty()) {
      sb.append("Job log tail:\n").append(tail).append('\n');
    }
  }

  /** The end of one job's log, or no bytes when it could not be read. */
  private byte[] logBytes(String auth, String owner, String repo, long jobId) {
    try (var response = actionsClient.downloadJobLogs(auth, ACCEPT, owner, repo, jobId)) {
      var location = response == null ? null : response.getLocation();
      if (location == null) {
        Log.debugf("Job log download for %d returned no redirect location", jobId);
        return new byte[0];
      }
      return fetcher.fetchTail(location, LOG_TAIL_BYTES);
    } catch (RuntimeException e) {
      Log.debugf(e, "Could not read the log of job %d", jobId);
      return new byte[0];
    }
  }

  /**
   * The lines of a log tail that name a failed test, in log order, each once, at most {@link
   * #MAX_FAILING_TEST_LINES}: the whole window is scanned, timestamps and control characters
   * removed, and, as in {@link #renderLogTail}, a first line cut mid-way by a full window is
   * dropped.
   */
  static List<String> failingTestLines(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return List.of();
    }
    var lines = clean(new String(bytes, StandardCharsets.UTF_8)).split("\n", -1);
    var start = bytes.length >= LOG_TAIL_BYTES ? 1 : 0;
    var found = new LinkedHashSet<String>();
    // Jest heads a test declared outside any describe block "● name", with no " › " to tell it
    // from its other bullets, so it is read only under a failed test file's line.
    var underFailedJestFile = false;
    for (var i = start; i < lines.length && found.size() < MAX_FAILING_TEST_LINES; i++) {
      // Matched before it is clipped: several shapes are read at the line's end ("... FAILED").
      var line = LOG_TIMESTAMP.matcher(lines[i]).replaceFirst("").strip();
      if (isJestFile(line, JEST_FAILED_FILE)) {
        underFailedJestFile = true;
      } else if (isJestFile(line, JEST_PASSED_FILE)) {
        underFailedJestFile = false;
      }
      if (namesAFailedTest(line) || (underFailedJestFile && isTopLevelJestTest(line))) {
        found.add(clip(line, MAX_LINE_CHARS));
      }
    }
    return List.copyOf(found);
  }

  /** Whether {@code line} is Jest's or Vitest's {@code status} line for a test file. */
  private static boolean isJestFile(String line, String status) {
    return line.startsWith(status)
        && (line.contains(".test.") || line.contains(".spec.") || line.contains("__tests__/"));
  }

  /** Jest's "● name" for a test outside any describe block, not one of its other bullets. */
  private static boolean isTopLevelJestTest(String line) {
    return line.startsWith(JEST_BULLET)
        && !JEST_NOTICES.contains(line.substring(JEST_BULLET.length()));
  }

  private static boolean namesAFailedTest(String line) {
    return !isExpectedTapOutcome(line)
        && FAILING_TEST_LINES.stream().anyMatch(shape -> shape.test(line));
  }

  /** TAP TODO/SKIP directives mark expected outcomes, not a test failure that explains the job. */
  static boolean isExpectedTapOutcome(String line) {
    return EXPECTED_TAP_OUTCOME.matcher(line).find();
  }

  private static final Pattern EXPECTED_TAP_OUTCOME =
      Pattern.compile("(?i)^not ok \\d++\\b[^\\n]*?\\h#\\h*+(?:TODO|SKIP)\\b");

  /**
   * The last {@link #LOG_TAIL_LINES} non-blank lines of a log tail, timestamps and control
   * characters removed, within {@link #MAX_LOG_CHARS}. When the bytes fill the whole tail window
   * the first line is a fragment cut mid-line, so it is dropped.
   */
  static String renderLogTail(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return "";
    }
    var lines = clean(new String(bytes, StandardCharsets.UTF_8)).split("\n", -1);
    var start = bytes.length >= LOG_TAIL_BYTES ? 1 : 0;
    var kept = new ArrayDeque<String>();
    var chars = 0;
    var full = false;
    for (var i = lines.length - 1; i >= start && kept.size() < LOG_TAIL_LINES && !full; i--) {
      var line = clip(LOG_TIMESTAMP.matcher(lines[i]).replaceFirst("").strip(), MAX_LINE_CHARS);
      if (!line.isEmpty()) {
        full = chars + line.length() + 1 > MAX_LOG_CHARS;
        if (!full) {
          kept.addFirst(line);
          chars += line.length() + 1;
        }
      }
    }
    return String.join("\n", kept);
  }

  /**
   * {@code text} with every character that could restyle, reorder or hide what the model reads
   * removed: ANSI escape sequences, carriage returns (a {@code \r} lets a log line overwrite itself
   * in a terminal, hiding what precedes it), tabs as spaces, and every other control or format
   * character — the latter covers the Unicode bidi overrides that can make text read differently
   * from its byte order. Newlines are kept.
   */
  static String clean(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    var normalized =
        ANSI_CSI.matcher(text).replaceAll("").replace("\r\n", "\n").replace('\r', '\n');
    var sb = new StringBuilder(normalized.length());
    normalized
        .codePoints()
        .forEach(
            cp -> {
              if (cp == '\n') {
                sb.append('\n');
              } else if (cp == '\t') {
                sb.append(' ');
              } else if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT) {
                sb.appendCodePoint(cp);
              }
            });
    return sb.toString();
  }

  /** {@link #clean} folded onto one line and clipped to {@link #MAX_LINE_CHARS}. */
  static String oneLine(String text) {
    return clip(clean(text).replace('\n', ' ').strip(), MAX_LINE_CHARS);
  }

  /** {@code text} cut to at most {@code max} characters, never inside a surrogate pair. */
  static String clip(String text, int max) {
    if (text.length() <= max) {
      return text;
    }
    var end = max - 1;
    if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end) + "…";
  }

  /**
   * {@code section} within {@code maxChars}, cut at a line boundary with {@link #TRUNCATION_NOTE}
   * appended. The header lines come first, so a cut only ever drops check detail.
   */
  static String cap(String section, int maxChars) {
    return cap(section, maxChars, TRUNCATION_NOTE);
  }

  /**
   * {@code section} within {@code maxChars}, cut at a line boundary with {@code note} appended —
   * the cap every untrusted context section shares (#59, #58).
   */
  static String cap(String section, int maxChars, String note) {
    if (section.length() <= maxChars) {
      return section;
    }
    var room = Math.max(0, maxChars - note.length() - 1);
    var cut = section.lastIndexOf('\n', room);
    var kept = cut > 0 ? section.substring(0, cut) : clip(section, room);
    return kept + "\n" + note;
  }
}
