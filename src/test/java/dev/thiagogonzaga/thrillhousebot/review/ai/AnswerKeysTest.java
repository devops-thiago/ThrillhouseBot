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

import java.util.List;
import org.junit.jupiter.api.Test;

/** #1079: the titles and statuses a failed answer holds, read without a parser. */
class AnswerKeysTest {

  @Test
  void onlyKeysOfTheFindingsAndStatusesElementsCount() {
    var keys =
        AnswerKeys.of(
            """
            Here: {"findings": [
              {"title": "One", "example": {"title": "nested", "status": "x"},
               "description": "quotes {\\"title\\": \\"q\\"}",},
              {"title": "Tab\\there", "risk": "low"}],
             "previous_findings_status": [{"id": 1, "status": "resolved"}, {"id": 2, "status": 3}],
             "summary": {"title": "not a finding"}}
            """);

    assertTrue(keys.readable());
    assertEquals(List.of("One", "Tab\there"), keys.titles());
    assertEquals(2, keys.titleCount());
    assertEquals(2, keys.statuses());
  }

  @Test
  void aTopLevelArrayAndSeveralDocumentsAreReadThrough() {
    var keys =
        AnswerKeys.of(
            """
            [{"title": "A", "risk": "low"}, [{"title": "inner"}]]
            {"findings": [{"title": "B\\nC", "line": 3}]}
            """);

    assertTrue(keys.readable());
    assertEquals(List.of("A", "B\nC"), keys.titles());
  }

  @Test
  void aTitleWithANonStringValueIsNoTitle() {
    var keys = AnswerKeys.of("{\"findings\": [{\"title\": 7, \"risk\": \"low\"}]}");

    assertTrue(keys.readable());
    assertEquals(List.of(), keys.titles());
  }

  @Test
  void aScanThatLosesTheStructureSaysSo() {
    assertFalse(AnswerKeys.of("{\"findings\": [{\"title\": \"open").readable());
    assertFalse(AnswerKeys.of("{\"findings\": [{\"title\": \"x\"}]").readable());
    assertFalse(AnswerKeys.of("{\"findings\": []}]}").readable());
    assertFalse(AnswerKeys.of("{\"title\": \"x\\").readable());
  }

  @Test
  void stringsOutsideAnObjectAndSpacedColonsAreReadAsTheyStand() {
    var keys =
        AnswerKeys.of(
            "\"prose\" {\"findings\": [\"loose\", {\"title\" : \"T\", \"risk\": \"low\"}]}");

    assertTrue(keys.readable());
    assertEquals(List.of("T"), keys.titles());
    assertFalse(AnswerKeys.of("{\"findings\": [{\"title\": \"T\"}], \"tail\"").readable());
  }

  @Test
  void aFindingsArrayNestedInsideAFindingCountsForNothing() {
    // rc16 review of #1168: a nested "findings" array added a phantom title.
    var keys =
        AnswerKeys.of(
            "{\"findings\": [{\"title\": \"real\", \"example\": {\"findings\": [{\"title\":"
                + " \"phantom\"}]}}]}");

    assertTrue(keys.readable());
    assertEquals(List.of("real"), keys.titles());
  }

  @Test
  void aStatusesArrayNestedInsideAnotherObjectCountsForNothing() {
    var keys =
        AnswerKeys.of(
            "{\"findings\": [{\"title\": \"real\", \"example\": {\"previous_findings_status\":"
                + " [{\"id\": 9, \"status\": \"resolved\"}]}}],"
                + " \"summary\": {\"previous_findings_status\": [{\"status\": \"x\"}]},"
                + " \"previous_findings_status\": [{\"id\": 1, \"status\": \"resolved\"}]}");

    assertEquals(1, keys.statuses());
  }

  @Test
  void titleValuesAreReadStringByStringAtAnyDepth() {
    assertEquals(
        List.of("A", "inner", "B"),
        AnswerKeys.titleValues(
            "{\"findings\": [{\"title\": \"A\", \"description\": \"e.g. {\\\"title\\\": \\\"x\\\"}\","
                + " \"meta\": {\"title\" : \"inner\"}}, {\"title\": \"B\", \"note\": \"cut"));
    assertEquals(
        List.of("A"),
        AnswerKeys.titleValues("{\"title\": 5, \"name\": \"title\", \"title\": \"A\"}"),
        "a title that is no string, and \"title\" as a value, hold no title");
    assertEquals(
        List.of("A", "B"), AnswerKeys.titleValues("[{\"title\": \"A\"}, {\"title\": \"B\\"));
    assertEquals(List.of(), AnswerKeys.titleValues("{\"title\": "));
    assertEquals(List.of(), AnswerKeys.titleValues("{\"title\" "));
  }
}
