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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The assertions a failing test states, read from its body in the pull request's changed files
 * (#952). A finding can tie itself to a failing test by quoting the assertion that fails instead of
 * naming the test: rust ThrillhouseBot-test#279's finding quoted {@code assert!((rms -
 * 12.5f64.sqrt()).abs() < 1e-9, "got {rms}");}, the job log printed only the test's name and "got
 * 4", and nothing tied the finding to the failure.
 *
 * <p>A test's body is found from its declaration: a new-side line of a changed file that names the
 * test, then the lines below it in the same hunk indented deeper than that line. The rule reads
 * brace and indentation languages alike, and a body its hunk cuts keeps only what the patch shows.
 * Only the body's lines that state an assertion are kept ({@link #statesAnAssertion}: an assertion
 * call opening the line, after a binding at most, never a comment), and only a quote long enough to
 * be that line's own code matches one, so a finding that quotes a setup line or a short expression
 * cites nothing through this class.
 *
 * <p>The patches are indexed by identifier once, on the first lookup, so a job log naming many
 * failing tests costs one pass over the patches and a lookup per test.
 */
final class TestAssertions {

  /** No changed file to read a test's body from. */
  static final TestAssertions NONE = new TestAssertions(List.of());

  /** Characters below which a quote is too short to be one assertion's own code. */
  static final int MIN_QUOTE_CHARS = 16;

  /** Lines read below one declaration, so a file of one long indented block stays bounded. */
  static final int MAX_BODY_LINES = 200;

  /**
   * An assertion keyword used as itself: called ({@code assert(}, {@code expect(}, {@code
   * assert!(}) or used as an object ({@code assert.equal(}, {@code should.exist(}, {@code
   * x.should.be}, {@code require.NoError(}), capitalized as .NET and Gomega write it ({@code
   * Assert.Equal(}, {@code x.Should().Be(}, {@code Expect(x)}, {@code mock.Verify(}). A name that
   * only starts with a keyword ({@code shouldRetry}, {@code assertCount}, {@code expectedFor}) is
   * not one. It may follow a qualifier.
   */
  private static final Pattern ASSERTION_CALL =
      Pattern.compile("(?:[Aa]ssert|[Ee]xpect|[Ss]hould|[Vv]erify|[Rr]efute)\\s*+[(!.]");

  /** Require checks retained in other languages; JS/TS uses require to load modules. */
  private static final Pattern REQUIRE_CALL = Pattern.compile("[Rr]equire\\s*+[(!.]");

  /** JavaScript/TypeScript files in which CommonJS require is a module loader. */
  private static final Pattern COMMON_JS_FILE = Pattern.compile("(?i)\\.(?:[cm]?[jt]s|[jt]sx)$");

  /**
   * A suffixed assertion, only as a call: {@code assertEquals(}, {@code assertThat(} (JUnit,
   * AssertJ, Hamcrest), {@code assert_eq!(} (Rust), {@code expectThrows(}, {@code
   * verifyNoMoreInteractions(} (Mockito), {@code refute_nil(} (Minitest). These four prefixes name
   * checks by convention: a project's own {@code assertValidOrder(…)} or {@code
   * verifyUserExists(…)} wraps assertions, and in a failing test it is the line the failure is
   * reported from, so it is kept. {@code should} and {@code require} have no such form here: {@code
   * shouldRetry(…)} and {@code requireNonNull(…)} are helpers and preconditions as often as checks.
   * Only the body of a test the CI log names as failing is read, and a finding cites such a line
   * only by quoting it whole ({@link #MIN_QUOTE_CHARS}), so a helper read as an assertion can only
   * tie a finding to a test that did fail.
   */
  private static final Pattern SUFFIXED_CALL =
      Pattern.compile("(?:assert|expect|verify|refute)[A-Z_][\\w$]*+\\s*+!?+\\(");

  /** pytest's and Python's {@code assert} statement opening a line. */
  private static final Pattern ASSERT_STATEMENT = Pattern.compile("assert\\s");

  /** Go's {@code t.Error}, {@code t.Errorf}, {@code t.Fatal} and {@code t.Fatalf} calls. */
  private static final Pattern GO_FAILURE = Pattern.compile("(?<![\\w.])t\\.(?:Error|Fatal)f?+\\(");

  /** What a line may open with before its assertion call: {@code let}, a binding and {@code =}. */
  private static final Pattern BINDING =
      Pattern.compile("^(?:(?:let|var|val|const)\\s++)?+(?:[\\w$]++\\s*+=(?!=)\\s*+)?+");

  /**
   * A comment line: {@code //}, {@code /*}, a block comment's {@code *}, {@code #} or {@code --}.
   */
  private static final Pattern COMMENT = Pattern.compile("(?://|/\\*|\\*|#|--)");

  /** An identifier a line holds, the key the patches are indexed by. */
  private static final Pattern IDENTIFIER = Pattern.compile("\\w++");

  /**
   * A quote in a finding's text: one backtick or single-quote span on one line. A finding quotes
   * code in single quotes too: scala ThrillhouseBot-test#297's wrote the failing assertion as
   * 'assert(…)' (#1194). A span between two apostrophes of prose is never part of an assertion.
   */
  private static final Pattern QUOTE = Pattern.compile("`([^`\\n]++)`|'([^'\\n]++)'");

  /** A function declared or called on a line: its name, then its argument list opening. */
  private static final Pattern FUNCTION_NAME = Pattern.compile("([A-Za-z_]\\w*+)\\s*+\\(");

  private static final Pattern WHITESPACE = Pattern.compile("\\s++");

  private record TestPatch(PatchLines lines, boolean commonJs) {}

  private final List<TestPatch> patches;

  /**
   * Where each identifier stands: the patch and the line holding it, built on first use in one pass
   * over the patches, so each failing test is then looked up rather than searched for.
   */
  private Map<String, List<int[]>> byIdentifier;

  /** Lines the index read, for the test that pins one pass. */
  private int linesRead;

  private TestAssertions(List<TestPatch> patches) {
    this.patches = patches;
  }

  /** The new-side lines of every changed file with a patch. */
  static TestAssertions of(List<GitHubPullRequestClient.FileDiff> files) {
    if (files == null || files.isEmpty()) {
      return NONE;
    }
    var patches = new ArrayList<TestPatch>();
    for (var file : files) {
      var lines = PatchLines.parse(file.patch());
      if (!lines.lines().isEmpty()) {
        patches.add(
            new TestPatch(
                lines, COMMON_JS_FILE.matcher(Objects.toString(file.filename(), "")).find()));
      }
    }
    return patches.isEmpty() ? NONE : new TestAssertions(List.copyOf(patches));
  }

  /**
   * The assertion lines of every body the changed files declare {@code testName} with, normalized
   * as {@link #normalized} does, each once. Empty when no changed line declares it.
   */
  synchronized List<String> assertionsOf(String testName) {
    var key = lookupKey(testName);
    if (key.isEmpty()) {
      return List.of();
    }
    // An identifier is found as a whole token by the index; any other name must be in the line.
    var single = key.equals(testName);
    var assertions = new LinkedHashSet<String>();
    for (var at : index().getOrDefault(key, List.of())) {
      var patch = patches.get(at[0]);
      var lines = patch.lines().lines();
      if (single || lines.get(at[1]).text().contains(testName)) {
        collectBody(lines, at[1], assertions, patch.commonJs());
      }
    }
    return List.copyOf(assertions);
  }

  /**
   * The test functions that print {@code label}, for a harness whose check macro names each check
   * with a string the job log then prints: C ThrillhouseBot-test#302 logged "FAIL
   * rider_limit_rejects_third_code" from {@code CHECK("rider_limit_rejects_third_code", …)} inside
   * {@code test_rider_limit}, and the finding named the function (#1194). Each changed line holding
   * the label in double quotes is read up to the nearest line of its hunk indented less, a lone
   * brace skipped; that line's first function name is kept when it holds "test".
   */
  synchronized List<String> functionsLabelling(String label) {
    var key = lookupKey(label);
    if (key.isEmpty()) {
      return List.of();
    }
    var quoted = "\"" + label + "\"";
    var functions = new LinkedHashSet<String>();
    for (var at : index().getOrDefault(key, List.of())) {
      var lines = patches.get(at[0]).lines().lines();
      if (lines.get(at[1]).text().contains(quoted)) {
        enclosingTestFunction(lines, at[1]).ifPresent(functions::add);
      }
    }
    return List.copyOf(functions);
  }

  /**
   * The test function the line at {@code start} is in, read as {@link #functionsLabelling} does.
   */
  private static Optional<String> enclosingTestFunction(List<PatchLines.Line> lines, int start) {
    var line = lines.get(start);
    int indent = indentOf(line.text());
    for (int i = start - 1; i >= 0 && i >= start - MAX_BODY_LINES; i--) {
      var above = lines.get(i);
      if (above.hunk() != line.hunk()) {
        break;
      }
      var text = above.text();
      if (!text.isBlank() && !text.strip().equals("{") && indentOf(text) < indent) {
        var name = FUNCTION_NAME.matcher(text);
        return name.find() && name.group(1).toLowerCase(Locale.ROOT).contains("test")
            ? Optional.of(name.group(1))
            : Optional.empty();
      }
    }
    return Optional.empty();
  }

  /** How many patch lines the index has read. */
  synchronized int linesRead() {
    return linesRead;
  }

  private Map<String, List<int[]>> index() {
    if (byIdentifier == null) {
      byIdentifier = new HashMap<>();
      for (int p = 0; p < patches.size(); p++) {
        var lines = patches.get(p).lines().lines();
        for (int i = 0; i < lines.size(); i++) {
          linesRead++;
          var seen = new HashSet<String>();
          var identifiers = IDENTIFIER.matcher(lines.get(i).text());
          while (identifiers.find()) {
            if (seen.add(identifiers.group())) {
              byIdentifier
                  .computeIfAbsent(identifiers.group(), k -> new ArrayList<>())
                  .add(new int[] {p, i});
            }
          }
        }
      }
    }
    return byIdentifier;
  }

  /**
   * The identifier a declaration of {@code testName} must hold: the name itself, or the longest
   * identifier of a name of several words. Empty when the name holds none.
   */
  private static String lookupKey(String testName) {
    var key = "";
    var identifiers = IDENTIFIER.matcher(testName);
    while (identifiers.find()) {
      if (identifiers.group().length() > key.length()) {
        key = identifiers.group();
      }
    }
    return key;
  }

  /**
   * Adds the assertion lines of the body declared at {@code start}: that line, then the following
   * lines of its hunk that are blank or indented deeper than it. A brace alone on the line right
   * after the declaration (Allman style) opens the body rather than ending it.
   */
  private static void collectBody(
      List<PatchLines.Line> lines, int start, LinkedHashSet<String> out, boolean commonJs) {
    var declaration = lines.get(start);
    int indent = indentOf(declaration.text());
    addIfAssertion(declaration.text(), out, commonJs);
    for (int i = start + 1; i < lines.size() && i <= start + MAX_BODY_LINES; i++) {
      var line = lines.get(i);
      var opensBody = i == start + 1 && line.text().strip().equals("{");
      if (line.hunk() != declaration.hunk()
          || (!line.text().isBlank() && !opensBody && indentOf(line.text()) <= indent)) {
        return;
      }
      addIfAssertion(line.text(), out, commonJs);
    }
  }

  private static void addIfAssertion(String line, LinkedHashSet<String> out, boolean commonJs) {
    if (statesAnAssertion(line, commonJs)) {
      out.add(normalized(line));
    }
  }

  /**
   * Whether {@code line} is an assertion: an assertion call opening it, after a binding at most, an
   * {@code assert} statement, or a Go failure call. A comment line never is.
   */
  private static boolean statesAnAssertion(String line, boolean commonJs) {
    var code = line.strip();
    if (COMMENT.matcher(code).lookingAt()) {
      return false;
    }
    var call =
        code.substring(BINDING.matcher(code).results().findFirst().map(m -> m.end()).orElse(0));
    return opensWithAssertionCall(call, commonJs)
        || ASSERT_STATEMENT.matcher(code).lookingAt()
        || GO_FAILURE.matcher(code).find();
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
  }

  /** Whether one segment of the dotted name {@code code} opens with starts an assertion call. */
  private static boolean opensWithAssertionCall(String code, boolean commonJs) {
    int end = 0;
    while (end < code.length() && isNameChar(code.charAt(end))) {
      end++;
    }
    var call = ASSERTION_CALL.matcher(code);
    var suffixed = SUFFIXED_CALL.matcher(code);
    var require = REQUIRE_CALL.matcher(code);
    for (int at = 0; at <= end; at = code.indexOf('.', at) + 1) {
      if (call.region(at, code.length()).lookingAt()
          || suffixed.region(at, code.length()).lookingAt()
          || (!commonJs && require.region(at, code.length()).lookingAt())) {
        return true;
      }
      if (code.indexOf('.', at) < 0) {
        return false;
      }
    }
    return false;
  }

  private static int indentOf(String line) {
    return line.length() - line.stripLeading().length();
  }

  /**
   * The quotes in a finding's text long enough to be an assertion's own code, normalized as {@link
   * #normalized} does.
   */
  static List<String> quotesIn(String text) {
    var quotes = new ArrayList<String>();
    var matcher = QUOTE.matcher(text);
    while (matcher.find()) {
      String quote = normalized(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
      if (quote.length() >= MIN_QUOTE_CHARS) {
        quotes.add(quote);
      }
    }
    return List.copyOf(quotes);
  }

  /** Lower case, every run of whitespace one blank, stripped: a quote may rewrap or re-case. */
  static String normalized(String code) {
    return WHITESPACE.matcher(code.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }
}
