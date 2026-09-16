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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * #872: what the walkthrough recovery says when it recovers nothing. A production review dropped
 * all four {@code file_summaries} entries and logged only the counts, and the session row keeps the
 * response after normalization — so the shape that arrived was gone and the blank Changed Files
 * table could not be explained.
 */
class FileSummariesDropDiagnosticsTest {

  private final ReviewResponseParser parser = new ReviewResponseParser(new ObjectMapper());

  private static String ch(int codePoint) {
    return String.valueOf((char) codePoint);
  }

  /** Every line the parser logged while it read {@code raw}, rendered as a handler would see it. */
  private List<String> linesFrom(String raw) {
    var records = new CopyOnWriteArrayList<LogRecord>();
    var handler =
        new Handler() {
          @Override
          public void publish(LogRecord logRecord) {
            records.add(logRecord);
          }

          @Override
          public void flush() {
            // Nothing buffered.
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
    var logger =
        org.jboss.logmanager.LogContext.getLogContext()
            .getLogger(ReviewResponseParser.class.getName());
    var previousLevel = logger.getLevel();
    logger.addHandler(handler);
    logger.setLevel(Level.ALL);
    try {
      parser.parse(raw);
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
    }
    return records.stream()
        .map(
            r ->
                r.getParameters() != null && r.getParameters().length > 0
                    ? String.format(r.getMessage(), r.getParameters())
                    : r.getMessage())
        .toList();
  }

  /** The one line the recovery logged, or a failure naming what it logged instead. */
  private String onlyLineFrom(String raw) {
    var lines = linesFrom(raw);
    assertEquals(1, lines.size(), lines.toString());
    return lines.get(0);
  }

  private static String summaryOf(String fileSummaries) {
    return "{\"findings\": [], \"summary\": {\"total_findings\": 0, \"pr_purpose\": \"p\","
        + " \"file_summaries\": "
        + fileSummaries
        + "}}";
  }

  @Test
  void anAllDroppedRecoveryNamesTheNodeTypeAndTheFirstEntrysFieldNames() {
    // The production shape's stand-in: a key PATH_KEYS knows beside one SUMMARY_KEYS does not, so
    // every entry is dropped and the counts alone say nothing about why.
    var line =
        onlyLineFrom(
            summaryOf(
                """
                [{"file_path": "src/A.java", "change_summary": "adds a guard"},
                 {"file_path": "src/B.java", "change_summary": "new cache"}]"""));

    assertTrue(line.contains("2 entries"), line);
    assertTrue(line.contains("arrived as ARRAY"), line);
    assertTrue(line.contains("OBJECT with field(s) [file_path, change_summary]"), line);
  }

  @Test
  void aMapFormAScalarAndASingleEntryAreEachDescribed() {
    var mapForm = onlyLineFrom(summaryOf("{\"src/A.java\": 7}"));
    assertTrue(mapForm.contains("arrived as OBJECT"), mapForm);
    assertTrue(mapForm.contains("NUMBER with no fields"), mapForm);

    var scalar = onlyLineFrom(summaryOf("\"nothing worth calling out\""));
    assertTrue(scalar.contains("arrived as STRING"), scalar);
    assertTrue(scalar.contains("STRING with no fields"), scalar);

    var singleEntry = onlyLineFrom(summaryOf("{\"summary\": \"no path anywhere\"}"));
    assertTrue(singleEntry.contains("arrived as OBJECT"), singleEntry);
    assertTrue(singleEntry.contains("OBJECT with field(s) [summary]"), singleEntry);
  }

  @Test
  void theFieldNamesAreSanitizedAndBoundedInCountAndLength() {
    var longName = "x".repeat(60);
    // The U+000A stays raw rather than escaped: extractJson escapes control characters inside
    // string literals before the document is read, so this is the path a model's own stray
    // newline takes, and the name reaches the recovery as one<LF><RLO>two either way.
    var forged = "one" + ch(0x0A) + ch(0x202E) + "two";
    var entry = new StringBuilder("{\"" + longName + "\": 1, \"" + forged + "\": 1");
    for (var i = 0; i < 9; i++) {
      entry.append(", \"k").append(i).append("\": 1");
    }
    var line = onlyLineFrom(summaryOf("[" + entry.append("}]")));

    assertTrue(line.contains("x".repeat(40) + "…"), line);
    assertFalse(line.contains("x".repeat(41)), line);
    // Collapsed, not deleted: the line separator and the bidi override cannot forge a record.
    assertTrue(line.contains("one two"), line);
    assertFalse(line.contains(ch(0x0A)) || line.contains(ch(0x202E)), line);
    // Eight names at most, and the rest counted rather than listed.
    assertTrue(line.contains("k5"), line);
    assertFalse(line.contains("k6"), line);
    assertTrue(line.contains("and 3 more"), line);
  }

  @Test
  void aPartlyRecoveredResponseKeepsTheCountLineAndDescribesNoShape() {
    // One entry in, one out. Describing the miss here would log the shape on every review a single
    // stray entry appears in, so the line stays the count it always was.
    var line =
        onlyLineFrom(
            summaryOf(
                """
                [{"file": "src/A.java", "description": "adds a guard"},
                 {"file_path": "src/B.java", "change_summary": "new cache"}]"""));

    assertTrue(line.contains("recovered 1 entry and dropped 1"), line);
    assertFalse(line.contains("arrived as"), line);
  }

  @Test
  void aConformingResponseLogsNothing() {
    assertEquals(
        List.of(),
        linesFrom(summaryOf("[{\"path\": \"src/A.java\", \"summary\": \"adds a guard\"}]")));
  }
}
