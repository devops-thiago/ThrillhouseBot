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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * Only the body's lines that state an assertion are kept, and only a quote long enough to be that
 * line's own code matches one, so a finding that quotes a setup line or a short expression cites
 * nothing through this class.
 */
final class TestAssertions {

  /** No changed file to read a test's body from. */
  static final TestAssertions NONE = new TestAssertions(List.of());

  /** Characters below which a quote is too short to be one assertion's own code. */
  static final int MIN_QUOTE_CHARS = 16;

  /** Lines read below one declaration, so a file of one long indented block stays bounded. */
  static final int MAX_BODY_LINES = 200;

  /**
   * A line that states an assertion: an assert, expect, should, require, verify or refute call (of
   * any library's spelling, {@code assert_eq!}, {@code assertThat}, {@code expect(…)}), or Go's
   * {@code t.Error} and {@code t.Fatal}.
   */
  private static final Pattern ASSERTION =
      Pattern.compile(
          "(?<![A-Za-z0-9_])(?:assert|expect|should|require|verify|refute)"
              + "|(?<![A-Za-z0-9_])t\\.(?:Error|Fatal)",
          Pattern.CASE_INSENSITIVE);

  /** A quote in a finding's text: one backtick span on one line. */
  private static final Pattern QUOTE = Pattern.compile("`([^`\\n]++)`");

  private static final Pattern WHITESPACE = Pattern.compile("\\s++");

  private final List<PatchLines> patches;

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
  List<String> assertionsOf(String testName) {
    var assertions = new LinkedHashSet<String>();
    for (var patch : patches) {
      var lines = patch.lines();
      for (int i = 0; i < lines.size(); i++) {
        if (declares(lines.get(i).text(), testName)) {
          collectBody(lines, i, assertions);
        }
      }
    }
    return List.copyOf(assertions);
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
    if (ASSERTION.matcher(line).find()) {
      out.add(normalized(line));
    }
  }

  /**
   * Whether {@code line} names the test: a name of several words as written, an identifier as a
   * whole identifier, so {@code parses_batch} is not declared by {@code parses_batch_lines}.
   */
  private static boolean declares(String line, String name) {
    if (name.indexOf(' ') >= 0) {
      return line.contains(name);
    }
    for (int at = line.indexOf(name); at >= 0; at = line.indexOf(name, at + 1)) {
      if (!identifierChar(line, at - 1) && !identifierChar(line, at + name.length())) {
        return true;
      }
    }
    return false;
  }

  private static boolean identifierChar(String text, int at) {
    if (at < 0 || at >= text.length()) {
      return false;
    }
    char c = text.charAt(at);
    return Character.isLetterOrDigit(c) || c == '_';
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
