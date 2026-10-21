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
}
