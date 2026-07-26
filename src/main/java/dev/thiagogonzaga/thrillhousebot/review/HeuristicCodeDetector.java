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

import java.util.ArrayDeque;
import java.util.regex.Pattern;

/**
 * Detects that a diff introduces parsing / regex / validation / heuristic code, so the review
 * prompt can switch those hunks into failure-mode characterization instead of happy-path reading
 * (issue #123). Such code is a function whose decision boundary must be probed with inputs the diff
 * does not contain by definition, so re-reading the added lines cannot reveal a silent miss.
 *
 * <p>Detection is textual and deliberately narrow: only added lines count, test files are ignored
 * (a regex there is usually a fixture, not new production logic), and every signal names
 * construction or char-level scanning rather than mere use. Ubiquitous calls — {@code substring},
 * {@code indexOf}, {@code trim}, {@code matches}, {@code parseInt} — are excluded on purpose: they
 * appear in most diffs, and a trigger that fires on most diffs spends prompt budget without adding
 * an angle. A missed trigger costs one unprompted review; an over-eager one taxes every review.
 */
final class HeuristicCodeDetector {

  /** Explicit regex construction, across the languages the bot reviews. */
  private static final Pattern REGEX_CONSTRUCTION =
      Pattern.compile(
          "Pattern\\s*\\.\\s*compile\\s*\\(|re\\s*\\.\\s*compile\\s*\\("
              + "|new\\s+RegExp\\s*\\(|regexp\\s*\\.\\s*MustCompile\\s*\\(|\\bRegex\\s*\\(");

  /** JavaScript/TypeScript regex literal in a value-producing position, not division or a URL. */
  private static final Pattern JS_REGEX_LITERAL =
      Pattern.compile(
          "(?:=|\\breturn\\b|\\(|,|:)\\s*/(?:\\\\.|[^/\\\\\\r\\n])++/[dgimsuvy]*"
              + "(?=\\s*(?:[,;).]|$))");

  /** Source paths where slash-delimited regex literals are language syntax. */
  private static final Pattern JS_REGEX_PATH = Pattern.compile("(?i)\\.(?:[cm]?js|jsx|tsx?)$");

  /** Char-level scanning: a parser written by hand, where an index slips silently. */
  private static final Pattern CHAR_SCANNING = Pattern.compile("\\.charAt\\(|\\.codePointAt\\(");

  /** Unicode / whitespace normalization, where an NBSP or zero-width char is the classic miss. */
  private static final Pattern NORMALIZATION =
      Pattern.compile("(?i)\\bNormalizer\\b|isWhitespace|\\bNBSP\\b|zero.?width");

  /**
   * A <em>declared</em> parse/validate/tokenize-style member — the decision boundary lives in its
   * body. Three constraints keep ordinary calls out: a declaration keyword on the same line, no
   * {@code =} before the name (a field initializer is an assignment, not a declaration), and no
   * {@code .} or word character immediately before it (so the receiver call {@code
   * Integer.parseInt(...)} cannot pose as a declared {@code parse} member).
   */
  private static final Pattern HEURISTIC_DECLARATION =
      Pattern.compile(
          "(?i)\\b(?:private|public|protected|internal|static|final|fun|def|func|function)\\b"
              + "[^(=\\r\\n]*(?<![.\\w])"
              + "(?:parse|validate|isValid|tokeni[sz]e|segment|normali[sz]e|canonicali[sz]e"
              + "|sanitiz|lex|scan|split)\\w*\\s*\\(");

  /** Package-private Java-style method declaration, where no visibility keyword is present. */
  private static final Pattern PACKAGE_PRIVATE_HEURISTIC_DECLARATION =
      Pattern.compile(
          "(?i)^\\+\\s*(?!(?:return|if|for|while|switch|throw|new)\\b)"
              + "(?:@[\\w.]+\\s+)?(?:[\\w$<>?,.\\[\\]]+\\s+)+"
              + "(?:parse|validate|isValid|tokeni[sz]e|segment|normali[sz]e|canonicali[sz]e"
              + "|sanitiz|lex|scan|split)\\w*\\s*\\(");

  /** JavaScript/TypeScript parser or validator assigned to an arrow/function expression. */
  private static final Pattern ASSIGNED_HEURISTIC_DECLARATION =
      Pattern.compile(
          "(?i)\\b(?:const|let|var)\\s+"
              + "(?:parse|validate|isValid|tokeni[sz]e|segment|normali[sz]e|canonicali[sz]e"
              + "|sanitiz|lex|scan|split)\\w*\\s*=\\s*"
              + "(?:function\\s*\\(|(?:async\\s*)?"
              + "(?:\\((?=[^\\r\\n]*\\)\\s*=>)|[\\w$]+\\s*=>))");

  /**
   * An import/package line mentions a type without using it, so it is never itself heuristic code —
   * {@code import java.text.Normalizer;} must not stand in for normalization logic.
   */
  private static final Pattern DECLARATION_ONLY_LINE =
      Pattern.compile("^\\+\\s*(?:import|from|package|using|#include)\\b");

  /** A tolerance/window constant — the #55 three-line ambiguity window is the motivating shape. */
  private static final Pattern THRESHOLD_CONSTANT =
      Pattern.compile(
          "(?i)\\b(?:static\\s+final|const|val|let|var)\\b[^=\\r\\n]*"
              + "\\b\\w*(?:WINDOW|THRESHOLD|TOLERANCE|RADIUS|FUZZ|_LINES|LINES_)\\w*\\b[^=\\r\\n]*=");

  /** Diff header naming the file the following hunks belong to. */
  private static final Pattern DIFF_FILE_HEADER = Pattern.compile("^\\+\\+\\+ b/(.+)$");

  /**
   * Test-path recognition, split across three patterns rather than one alternation: an anchor
   * sitting in one branch of a top-level alternation leaves its scope implicit (java:S5850), and
   * each of these carries at most one anchor and no competing branch.
   */
  private static final Pattern TEST_DIRECTORY_SEGMENT =
      Pattern.compile("(?i)(?:^|/)(?:test|tests|spec|__tests__)/");

  /** A {@code .test.} / {@code _spec.} style marker in the file name. */
  private static final Pattern TEST_FILENAME_MARKER = Pattern.compile("(?i)[._-](?:test|spec)\\.");

  /** The Java convention, which only counts at the end of the path. */
  private static final Pattern JAVA_TEST_SUFFIX = Pattern.compile("Test\\.java$");

  private HeuristicCodeDetector() {}

  private static boolean isTestPath(String path) {
    return TEST_DIRECTORY_SEGMENT.matcher(path).find()
        || TEST_FILENAME_MARKER.matcher(path).find()
        || JAVA_TEST_SUFFIX.matcher(path).find();
  }

  /**
   * Whether any added line outside a test file introduces heuristic code. Scans the unified diff in
   * one pass over text the review already holds.
   */
  static boolean introducesHeuristicCode(String diff) {
    if (diff == null || diff.isBlank()) {
      return false;
    }
    var inTestFile = false;
    var inJavaScriptFile = false;
    var addedLines = new ArrayDeque<String>(4);
    for (String line : diff.split("\n", -1)) {
      // Every "+++ " line is a header, including the "+++ /dev/null" of a deletion — consuming them
      // all here keeps file state and the multiline window from carrying into the next file.
      if (line.startsWith("+++ ")) {
        var header = DIFF_FILE_HEADER.matcher(line);
        var hasSourcePath = header.matches();
        var path = hasSourcePath ? header.group(1) : "";
        inTestFile = hasSourcePath && isTestPath(path);
        inJavaScriptFile = hasSourcePath && JS_REGEX_PATH.matcher(path).find();
        addedLines.clear();
        continue;
      }
      // Only contiguous additions can form one declaration or construction. Context, removals,
      // hunk headers, and ignored test-file content all terminate the bounded window.
      if (inTestFile || line.isEmpty() || line.charAt(0) != '+') {
        addedLines.clear();
        continue;
      }

      addedLines.addLast(line);
      if (addedLines.size() > 4) {
        addedLines.removeFirst();
      }
      if (isHeuristicLine(line, inJavaScriptFile)
          || isHeuristicWindow(addedLines, inJavaScriptFile)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isHeuristicLine(String addedLine, boolean inJavaScriptFile) {
    if (DECLARATION_ONLY_LINE.matcher(addedLine).find()) {
      return false;
    }
    return REGEX_CONSTRUCTION.matcher(addedLine).find()
        || CHAR_SCANNING.matcher(addedLine).find()
        || NORMALIZATION.matcher(addedLine).find()
        || HEURISTIC_DECLARATION.matcher(addedLine).find()
        || PACKAGE_PRIVATE_HEURISTIC_DECLARATION.matcher(addedLine).find()
        || (inJavaScriptFile
            && (JS_REGEX_LITERAL.matcher(addedLine).find()
                || ASSIGNED_HEURISTIC_DECLARATION.matcher(addedLine).find()))
        || THRESHOLD_CONSTANT.matcher(addedLine).find();
  }

  private static boolean isHeuristicWindow(
      ArrayDeque<String> addedLines, boolean inJavaScriptFile) {
    if (addedLines.size() < 2) {
      return false;
    }
    String[] lines = addedLines.toArray(String[]::new);
    // Check every suffix ending at the newest line. This recognizes a declaration that starts
    // after an unrelated addition without letting text older than four lines influence the match.
    for (int start = 0; start < lines.length - 1; start++) {
      var joined = new StringBuilder(lines[start]);
      for (int index = start + 1; index < lines.length; index++) {
        joined.append(' ').append(lines[index], 1, lines[index].length());
      }
      if (hasMultilineHeuristicSignal(joined, inJavaScriptFile)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasMultilineHeuristicSignal(
      CharSequence addedLines, boolean inJavaScriptFile) {
    return REGEX_CONSTRUCTION.matcher(addedLines).find()
        || HEURISTIC_DECLARATION.matcher(addedLines).find()
        || PACKAGE_PRIVATE_HEURISTIC_DECLARATION.matcher(addedLines).find()
        || (inJavaScriptFile && ASSIGNED_HEURISTIC_DECLARATION.matcher(addedLines).find());
  }
}
