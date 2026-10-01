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

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers a review answer whose findings arrive as a bare top-level array (#960). A model answered
 * one review call with its findings as an array followed by {@code {"findings": [],
 * "previous_findings_status": []}}; the empty object was read as the whole answer and every model
 * finding was dropped without a log line.
 */
class ReviewResponseParserFindingArrayTest {

  /** Three findings in the shape the review prompt asks for, as the #180 answer carried them. */
  private static final String FINDINGS_ARRAY =
      """
      [
        {"risk": "critical", "confidence": "high", "file": "app/src/main/kotlin/AuthService.kt",
         "line": 42, "title": "Password compared with ==",
         "description": "The stored hash is compared with == instead of a constant-time check.",
         "suggestion_old": "if (hash == stored) {",
         "suggestion_new": "if (MessageDigest.isEqual(hash, stored)) {"},
        {"risk": "high", "confidence": "medium", "file": "app/src/main/kotlin/UserRepository.kt",
         "line": 17, "title": "SQL built by string concatenation",
         "description": "The user id is concatenated into the query text.",
         "suggestion_old": "\\"SELECT * FROM users WHERE id = \\" + id",
         "suggestion_new": "\\"SELECT * FROM users WHERE id = ?\\""},
        {"risk": "low", "confidence": "high", "file": "app/build.gradle.kts", "line": 9,
         "title": "Unused dependency", "description": "kotlinx-datetime is never imported.",
         "suggestion_old": "", "suggestion_new": ""}
      ]
      """;

  private static final String EMPTY_ANSWER = "{\"findings\": [], \"previous_findings_status\": []}";

  private static final List<String> TITLES =
      List.of(
          "Password compared with ==", "SQL built by string concatenation", "Unused dependency");

  private ReviewResponseParser parser;
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
  private Logger julLogger;
  private Level originalLevel;
  private Handler capture;

  @BeforeEach
  void setUp() {
    parser = new ReviewResponseParser(new ObjectMapper());
    julLogger = Logger.getLogger(ReviewResponseParser.class.getName());
    originalLevel = julLogger.getLevel();
    julLogger.setLevel(Level.ALL);
    capture =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            logged.add(entry);
          }

          @Override
          public void flush() {
            // Nothing is buffered.
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
    julLogger.addHandler(capture);
  }

  @AfterEach
  void restoreLogging() {
    julLogger.removeHandler(capture);
    julLogger.setLevel(originalLevel);
  }

  private List<String> warnings() {
    return logged.stream()
        .filter(entry -> entry.getLevel().intValue() >= Level.WARNING.intValue())
        .map(ReviewResponseParserFindingArrayTest::render)
        .toList();
  }

  private static String render(LogRecord entry) {
    var parameters = entry.getParameters();
    return parameters == null || parameters.length == 0
        ? entry.getMessage()
        : String.format(entry.getMessage(), parameters);
  }

  private static List<String> titles(ReviewResponse response) {
    return response.findings().stream().map(ReviewResponse.Finding::title).toList();
  }

  @Test
  void readsAFindingsArrayFollowedByAnEmptyAnswerAsTheFindings() {
    // The #180 shape: the array carried every finding, the empty object after it none.
    var response = parser.parse(FINDINGS_ARRAY + "\n" + EMPTY_ANSWER);

    assertEquals(TITLES, titles(response));
    assertEquals(42, response.findings().get(0).line());
    assertEquals(
        "if (MessageDigest.isEqual(hash, stored)) {", response.findings().get(0).suggestionNew());
    assertTrue(response.previousFindingsStatus().isEmpty());
    assertTrue(
        warnings().stream().anyMatch(w -> w.contains("held 2 JSON documents")),
        warnings().toString());
    assertTrue(
        warnings().stream().noneMatch(w -> w.contains("merged response holds")),
        warnings().toString());
  }

  @Test
  void readsTheSameAnswerFencedAndAfterDeliberation() {
    var raw =
        "I checked every file. [LOW] items are listed last.\n```json\n"
            + FINDINGS_ARRAY
            + "```\n```json\n"
            + EMPTY_ANSWER
            + "\n```";

    assertEquals(TITLES, titles(parser.parse(raw)));
  }

  @Test
  void readsAFindingsArrayThatFollowsTheAnswerObject() {
    var response =
        parser.parse(
            """
            {"findings": [], "previous_findings_status": [{"id": 3, "status": "resolved",
             "note": "fixed in this push"}]}
            """
                + FINDINGS_ARRAY);

    assertEquals(TITLES, titles(response));
    assertEquals(3, response.previousFindingsStatus().get(0).id());
  }

  @Test
  void readsAFindingsArrayAfterProseThatFollowsTheAnswerObject() {
    // The scan jumps from prose to the next brace, and must land on the array around it.
    var response = parser.parse(EMPTY_ANSWER + "\nAnd the findings:\n" + FINDINGS_ARRAY);

    assertEquals(TITLES, titles(response));
  }

  @Test
  void readsAFindingsArrayOnItsOwnAsTheFindings() {
    var response = parser.parse(FINDINGS_ARRAY);

    assertEquals(TITLES, titles(response));
    assertNull(response.summary());
    assertTrue(warnings().isEmpty(), warnings().toString());
  }

  @Test
  void keepsAFindingTwoDocumentsCarryOnceAndWarnsOfTheDrop() {
    var repeated =
        """
        {"findings": [{"risk": "low", "confidence": "high", "file": "app/build.gradle.kts",
         "line": 9, "title": "Unused dependency", "description": "kotlinx-datetime is never imported.",
         "suggestion_old": "", "suggestion_new": ""}]}
        """;

    var response = parser.parse(FINDINGS_ARRAY + repeated);

    assertEquals(TITLES, titles(response));
    assertTrue(
        warnings().stream()
            .anyMatch(
                w ->
                    w.contains("carried 4 finding(s) but the merged response holds 3")
                        && w.contains("dropped 1 finding(s), 1 of them identical duplicates")),
        warnings().toString());
  }

  @Test
  void warnsWhenAFindingsArrayIsLostToAConflictingField() {
    // A findings field that is not an array keeps its place, as any conflicting field does; the
    // array's findings are lost with it, and the warning says how many.
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse("{\"findings\": 7}\n" + FINDINGS_ARRAY));

    assertTrue(
        warnings().stream().anyMatch(w -> w.contains("dropped 3 finding(s), 0 of them identical")),
        warnings().toString());
  }

  @Test
  void refusesAnArrayOfObjectsThatAreNotFindings() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parser.parse(
                    "[{\"path\": \"src/A.kt\", \"summary\": \"adds a guard\"},"
                        + " {\"path\": \"src/B.kt\", \"summary\": \"renames\"}]"));

    assertTrue(ex.getMessage().contains("not a JSON object"), ex.getMessage());
  }

  @Test
  void refusesAnEmptyArrayAndAnArrayWithOneNonFinding() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("[]"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            parser.parse(
                "[{\"risk\": \"low\", \"file\": \"a.kt\", \"title\": \"t\"}, {\"risk\": \"low\"}]"));
  }

  @Test
  void doesNotTakeANonFindingArrayAheadOfTheAnswerForFindings() {
    var response =
        parser.parse("[{\"path\": \"src/A.kt\", \"summary\": \"adds a guard\"}]\n" + EMPTY_ANSWER);

    assertTrue(response.findings().isEmpty());
  }

  @Test
  void stillReadsAnAnswerWrappedInAnArray() {
    var response = parser.parse("Here it is: [{\"findings\": " + FINDINGS_ARRAY + "}]");

    assertEquals(TITLES, titles(response));
  }

  @Test
  void mergesTheObjectsOfALaterArrayThatIsNotFindings() {
    // Before arrays were documents, the scan read such an array's objects one by one.
    var response =
        parser.parse(
            "{\"previous_findings_status\": []}\n[{\"findings\": "
                + FINDINGS_ARRAY
                + "}, \"done\"]");

    assertEquals(TITLES, titles(response));
  }

  @Test
  void readsAFindingsArrayInASummaryCallAnswer() {
    var response =
        parser.parseSummary(
            FINDINGS_ARRAY
                + """
                {"summary": {"total_findings": 3, "critical": 1, "high": 1, "medium": 0, "low": 1,
                 "overall_assessment": "Two blocking issues.", "pr_purpose": "Adds login"}}
                """);

    assertEquals(TITLES, titles(response));
    assertEquals("Adds login", response.summary().prPurpose());
  }

  @Test
  void readsAFindingsArrayAloneAsASummaryCallAnswer() {
    var response = parser.parseSummary(FINDINGS_ARRAY);

    assertEquals(TITLES, titles(response));
    assertNull(response.summary());
  }

  @Test
  void rejectsAResponseCutInsideTheDocumentAfterAFindingsArray() {
    var raw = FINDINGS_ARRAY + "{\"findings\": [{\"risk\": \"hi";

    assertEquals(0, ReviewResponseParser.findAnswer(raw, KEYS, true).start());
    var ex = assertThrows(IllegalArgumentException.class, () -> parser.parse(raw));
    assertTrue(ex.getMessage().contains("not valid review JSON"), ex.getMessage());
  }

  @Test
  void anchorsACutAnswerWrappedInAnArrayOnTheObjectInside() {
    // A cut array runs to the end of the body, so the root-keyed object inside it is the answer
    // the truncation salvage reads from, as before arrays were candidates.
    var raw = "[{\"findings\": [{\"risk\": \"high\", \"file\": \"a.kt\", \"li";

    assertEquals(1, ReviewResponseParser.findAnswer(raw, KEYS, true).start());
  }

  @Test
  void readsEachArrayOnceWhileStillProbingTheObjectsInside() {
    // The outer array is not findings, so the array nested in it is skipped; its objects are
    // still candidates.
    var raw = "[{\"a\": 1}, [{\"b\": 2}]]\n" + EMPTY_ANSWER;

    var search = ReviewResponseParser.findAnswer(raw, KEYS, true);

    assertEquals(raw.indexOf(EMPTY_ANSWER), search.start());
    assertTrue(parser.parse(raw).findings().isEmpty());

    // An array inside an object already probed is skipped with the rest of that object.
    var inObject = "{\"a\": [{\"b\": 2}]}\n" + EMPTY_ANSWER;
    assertEquals(
        inObject.indexOf(EMPTY_ANSWER),
        ReviewResponseParser.findAnswer(inObject, KEYS, true).start());
  }

  @Test
  void leavesTheSharedExtractionAnchoredOnTheObject() {
    // The truncation salvage reads a single object, so only the review parse reads arrays.
    var raw = FINDINGS_ARRAY + EMPTY_ANSWER;

    assertEquals(raw.indexOf(EMPTY_ANSWER), ReviewResponseParser.findAnswer(raw, KEYS).start());
    assertEquals(EMPTY_ANSWER, ReviewResponseParser.extractJson(raw, KEYS));
  }

  @Test
  void tellsFindingArraysApart() {
    var mapper = new ObjectMapper();

    assertTrue(ReviewResponseParser.isFindingArray(read(mapper, FINDINGS_ARRAY)));
    assertFalse(ReviewResponseParser.isFindingArray(null));
    assertFalse(ReviewResponseParser.isFindingArray(read(mapper, "{\"risk\": \"low\"}")));
    assertFalse(ReviewResponseParser.isFindingArray(read(mapper, "[]")));
    assertFalse(ReviewResponseParser.isFindingArray(read(mapper, "[\"a\", \"b\"]")));
  }

  private static com.fasterxml.jackson.databind.JsonNode read(ObjectMapper mapper, String json) {
    try {
      return mapper.readTree(json);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final List<String> KEYS = ReviewResponseParser.REVIEW_ROOT_KEYS;
}
