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
          "Pattern\\.compile\\(|re\\.compile\\(|new\\s+RegExp\\(|regexp\\.MustCompile\\(|\\bRegex\\(");

  /** Char-level scanning: a parser written by hand, where an index slips silently. */
  private static final Pattern CHAR_SCANNING = Pattern.compile("\\.charAt\\(|\\.codePointAt\\(");

  /** Unicode / whitespace normalization, where an NBSP or zero-width char is the classic miss. */
  private static final Pattern NORMALIZATION =
      Pattern.compile("(?i)\\bNormalizer\\b|isWhitespace|\\bNBSP\\b|zero.?width");

  /**
   * A <em>declared</em> parse/validate/tokenize-style member — the decision boundary lives in its
   * body. Requires a declaration keyword on the same line so a call like {@code parseInt(...)} does
   * not trigger.
   */
  private static final Pattern HEURISTIC_DECLARATION =
      Pattern.compile(
          "(?i)\\b(?:private|public|protected|internal|static|final|fun|def|func)\\b[^(\\r\\n]*"
              + "\\b(?:parse|validate|isValid|tokeni[sz]e|segment|normali[sz]e|canonicali[sz]e"
              + "|sanitiz|lex|scan|split)\\w*\\s*\\(");

  /** A tolerance/window constant — the #55 three-line ambiguity window is the motivating shape. */
  private static final Pattern THRESHOLD_CONSTANT =
      Pattern.compile(
          "(?i)\\b(?:static\\s+final|const|val|let|var)\\b[^=\\r\\n]*"
              + "\\b\\w*(?:WINDOW|THRESHOLD|TOLERANCE|RADIUS|FUZZ|_LINES|LINES_)\\w*\\b[^=\\r\\n]*=");

  /** Diff header naming the file the following hunks belong to. */
  private static final Pattern DIFF_FILE_HEADER = Pattern.compile("^\\+\\+\\+ b/(.+)$");

  private static final Pattern TEST_PATH =
      Pattern.compile(
          "(?i)(?:^|/)(?:test|tests|spec|__tests__)/|[._-](?:test|spec)\\.|Test\\.java$");

  private HeuristicCodeDetector() {}

  /**
   * Whether any added line outside a test file introduces heuristic code. Scans the unified diff in
   * one pass over text the review already holds.
   */
  static boolean introducesHeuristicCode(String diff) {
    if (diff == null || diff.isBlank()) {
      return false;
    }
    var inTestFile = false;
    for (String line : diff.split("\n", -1)) {
      // Every "+++ " line is a header, including the "+++ /dev/null" of a deletion — consuming them
      // all here keeps the flag from carrying over to the next file and out of the content check.
      if (line.startsWith("+++ ")) {
        var header = DIFF_FILE_HEADER.matcher(line);
        inTestFile = header.matches() && TEST_PATH.matcher(header.group(1)).find();
        continue;
      }
      if (inTestFile || line.length() < 2 || line.charAt(0) != '+') {
        continue;
      }
      if (isHeuristicLine(line)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isHeuristicLine(String addedLine) {
    return REGEX_CONSTRUCTION.matcher(addedLine).find()
        || CHAR_SCANNING.matcher(addedLine).find()
        || NORMALIZATION.matcher(addedLine).find()
        || HEURISTIC_DECLARATION.matcher(addedLine).find()
        || THRESHOLD_CONSTANT.matcher(addedLine).find();
  }
}
