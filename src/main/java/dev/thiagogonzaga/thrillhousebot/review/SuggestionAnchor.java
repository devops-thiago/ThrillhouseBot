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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import java.util.List;

/**
 * Puts a finding on the code its {@code suggestion_old} quotes, so a suggestion replaces the lines
 * it was written for (#924).
 *
 * <p>The model's line numbers drift from its quotes: a finding about a markdown table's {@code
 * VITE_REFRESH_INTERVAL} row sat on the {@code VITE_LOAN_API_URL} row two lines up, and one about a
 * Dockerfile {@code COPY} sat on the {@code USER app} line below it. GitHub applies a suggestion to
 * the line it is posted on, so applying either deleted a row or a line nobody flagged. The quote is
 * the more reliable of the two, and the quote validator has already checked it against the diff,
 * so:
 *
 * <ul>
 *   <li>a quote found at one location ({@link DiffLineResolver#locateQuote}) that does not hold the
 *       cited line moves the finding there;
 *   <li>a quote that cannot be placed (absent from the right side, or at several locations none of
 *       which the cited line selects) keeps the finding where it is and drops {@code
 *       suggestion_new}, so no suggestion block is posted on lines it does not quote;
 *   <li>a suggestion for markdown table rows whose rows do not keep the quoted row's cell count is
 *       dropped the same way: applied, it shifts every later cell under the wrong column (#964).
 * </ul>
 *
 * <p>{@code suggestion_old} is kept in every case: it is the anchor later rounds locate the finding
 * by.
 */
final class SuggestionAnchor {

  private SuggestionAnchor() {}

  /** {@code finding} on the lines its quote occupies, or the same instance when nothing changes. */
  static ReviewResponse.Finding place(ReviewResponse.Finding finding, DiffLineResolver resolver) {
    String quote = finding.suggestionOld();
    if (quote == null || quote.isBlank()) {
      return finding;
    }
    var location = resolver.locateQuote(finding.file(), quote, finding.line());
    if (location.isEmpty()) {
      return withoutSuggestionBlock(finding, "quotes code it cannot be placed on");
    }
    var range = location.get();
    var placed = finding;
    if (finding.line() < range.startLine() || finding.line() > range.endLine()) {
      Log.infof(
          "Finding '%s' cites %s:%d but quotes lines %d-%d; moving it onto the quoted code",
          LogSafe.oneLine(finding.title()),
          LogSafe.oneLine(finding.file()),
          finding.line(),
          range.startLine(),
          range.endLine());
      placed = atLine(finding, range.startLine(), finding.suggestionNew());
    }
    return changesTableShape(quote, placed.suggestionNew())
        ? withoutSuggestionBlock(placed, "suggests table rows with a different cell count")
        : placed;
  }

  /**
   * Whether {@code suggestion} rewrites the markdown table rows {@code quote} holds into rows of a
   * different cell count, or into something that is not a table row. A quote that is not wholly
   * table rows, or a missing suggestion, is never a table-shape change. {@code quote} is not null.
   */
  static boolean changesTableShape(String quote, String suggestion) {
    List<String> quoted = nonBlankLines(quote);
    if (suggestion == null
        || suggestion.isBlank()
        || quoted.isEmpty()
        || !quoted.stream().allMatch(SuggestionAnchor::isTableRow)) {
      return false;
    }
    int cells = cellCount(quoted.getFirst());
    return nonBlankLines(suggestion).stream()
        .anyMatch(row -> !isTableRow(row) || cellCount(row) != cells);
  }

  private static boolean isTableRow(String line) {
    return line.startsWith("|");
  }

  /**
   * Cells of a markdown table row: the separator pipes after its leading pipe, plus one unless the
   * row ends on one, so a row written without a trailing pipe counts the same. As in GFM, a
   * backslash escapes the character after it, so {@code \\|} is cell text and {@code \\\\|} is an
   * escaped backslash followed by a separator.
   */
  static int cellCount(String row) {
    int separators = 0;
    boolean endsOnSeparator = false;
    int i = 1;
    while (i < row.length()) {
      char c = row.charAt(i);
      // An escape pair is cell text: step over the escaped character too.
      endsOnSeparator = c == '|';
      separators += endsOnSeparator ? 1 : 0;
      i += c == '\\' ? 2 : 1;
    }
    return endsOnSeparator ? separators : separators + 1;
  }

  private static List<String> nonBlankLines(String text) {
    return text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
  }

  private static ReviewResponse.Finding withoutSuggestionBlock(
      ReviewResponse.Finding finding, String reason) {
    if (finding.suggestionNew() == null) {
      return finding;
    }
    Log.infof(
        "Finding '%s' (%s:%d) %s; dropping its suggestion and keeping the finding",
        LogSafe.oneLine(finding.title()), LogSafe.oneLine(finding.file()), finding.line(), reason);
    return atLine(finding, finding.line(), null);
  }

  private static ReviewResponse.Finding atLine(
      ReviewResponse.Finding finding, int line, String suggestionNew) {
    return new ReviewResponse.Finding(
        finding.risk(),
        finding.confidence(),
        finding.file(),
        line,
        finding.title(),
        finding.description(),
        finding.suggestionOld(),
        suggestionNew);
  }
}
