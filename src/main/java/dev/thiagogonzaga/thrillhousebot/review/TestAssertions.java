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
   * An assertion call: an assert, expect, should, require, verify or refute call in any library's
   * spelling ({@code assert_eq!(}, {@code assertEquals(}, {@code expect(}, {@code assert.equal(}).
   * The keyword is the whole name or is followed by a capital or an underscore, so a setup call
   * such as {@code expectedFor(name)} is not one. It may follow a qualifier.
   */
  private static final Pattern ASSERTION_CALL =
      Pattern.compile(
          "(?:assert|expect|should|require|verify|refute)(?:[A-Z_][\\w$]*+)?+\\s*+[(!.]");

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

  /** A quote in a finding's text: one backtick span on one line. */
  private static final Pattern QUOTE = Pattern.compile("`([^`\\n]++)`");

  private static final Pattern WHITESPACE = Pattern.compile("\\s++");

  private final List<PatchLines> patches;

  /**
   * Where each identifier stands: the patch and the line holding it, built on first use in one pass
   * over the patches, so each failing test is then looked up rather than searched for.
   */
  private Map<String, List<int[]>> byIdentifier;

  /** Lines the index read, for the test that pins one pass. */
  private int linesRead;

  private TestAssertions(List<PatchLines> patches) {
    this.patches = patches;
  }

  /** The new-side lines of every changed file with a patch. */
  static TestAssertions of(List<GitHubPullRequestClient.FileDiff> files) {
    if (files == null || files.isEmpty()) {
      return NONE;
    }
    var patches = new ArrayList<PatchLines>();
    for (var file : files) {
      var lines = PatchLines.parse(file.patch());
      if (!lines.lines().isEmpty()) {
        patches.add(lines);
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
      var lines = patches.get(at[0]).lines();
      if (single || lines.get(at[1]).text().contains(testName)) {
        collectBody(lines, at[1], assertions);
      }
    }
    return List.copyOf(assertions);
  }

  /** How many patch lines the index has read. */
  synchronized int linesRead() {
    return linesRead;
  }

  private Map<String, List<int[]>> index() {
    if (byIdentifier == null) {
      byIdentifier = new HashMap<>();
      for (int p = 0; p < patches.size(); p++) {
        var lines = patches.get(p).lines();
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
   * lines of its hunk that are blank or indented deeper than it.
   */
  private static void collectBody(
      List<PatchLines.Line> lines, int start, LinkedHashSet<String> out) {
    var declaration = lines.get(start);
    int indent = indentOf(declaration.text());
    addIfAssertion(declaration.text(), out);
    for (int i = start + 1; i < lines.size() && i <= start + MAX_BODY_LINES; i++) {
      var line = lines.get(i);
      if (line.hunk() != declaration.hunk()
          || (!line.text().isBlank() && indentOf(line.text()) <= indent)) {
        return;
      }
      addIfAssertion(line.text(), out);
    }
  }

  private static void addIfAssertion(String line, LinkedHashSet<String> out) {
    if (statesAnAssertion(line)) {
      out.add(normalized(line));
    }
  }

  /**
   * Whether {@code line} is an assertion: an assertion call opening it, after a binding at most, an
   * {@code assert} statement, or a Go failure call. A comment line never is.
   */
  static boolean statesAnAssertion(String line) {
    var code = line.strip();
    if (COMMENT.matcher(code).lookingAt()) {
      return false;
    }
    var call =
        code.substring(BINDING.matcher(code).results().findFirst().map(m -> m.end()).orElse(0));
    return opensWithAssertionCall(call)
        || ASSERT_STATEMENT.matcher(code).lookingAt()
        || GO_FAILURE.matcher(code).find();
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
  }

  /** Whether one segment of the dotted name {@code code} opens with starts an assertion call. */
  private static boolean opensWithAssertionCall(String code) {
    int end = 0;
    while (end < code.length() && isNameChar(code.charAt(end))) {
      end++;
    }
    var call = ASSERTION_CALL.matcher(code);
    for (int at = 0; at <= end; at = code.indexOf('.', at) + 1) {
      if (call.region(at, code.length()).lookingAt()) {
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
      String quote = normalized(matcher.group(1));
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
