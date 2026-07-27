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
import org.junit.jupiter.api.Test;

class FixResponseParserTest {

  private final FixResponseParser parser = new FixResponseParser(new ObjectMapper());

  @Test
  void parsesReplaceAndCreateEdits() {
    var response =
        parser.parse(
            """
            {
              "summary": "Close the connection on the early-return path",
              "edits": [
                {"file": "src/Foo.java", "operation": "replace",
                 "search": "return null;", "replace": "conn.close();\\nreturn null;"},
                {"file": "src/FooTest.java", "operation": "create",
                 "search": "", "replace": "class FooTest {}"}
              ],
              "notes": "run the Foo tests"
            }
            """);

    assertEquals("Close the connection on the early-return path", response.summary());
    assertEquals(2, response.edits().size());
    var replace = response.edits().get(0);
    assertFalse(replace.isCreate());
    assertTrue(replace.isApplicable());
    assertEquals("src/Foo.java", replace.file());
    var create = response.edits().get(1);
    assertTrue(create.isCreate());
    assertTrue(create.isApplicable());
    assertEquals("run the Foo tests", response.notes());
  }

  @Test
  void parsesJsonWrappedInMarkdownFences() {
    var response =
        parser.parse(
            """
            Here is the fix:
            ```json
            {"summary": "s", "edits": [], "notes": "cannot fix"}
            ```
            """);
    assertTrue(response.edits().isEmpty());
    assertEquals("cannot fix", response.notes());
  }

  @Test
  void dropsNullEditEntries() {
    var response = parser.parse("{\"summary\": \"s\", \"edits\": [null], \"notes\": \"\"}");
    assertTrue(response.edits().isEmpty());
  }

  @Test
  void treatsMissingEditsAsEmpty() {
    var response = parser.parse("{\"summary\": \"s\", \"notes\": \"\"}");
    assertTrue(response.edits().isEmpty());
  }

  @Test
  void rejectsEmptyAndNonJsonResponses() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("   "));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("I could not fix this."));
  }

  @Test
  void flagsIncompleteEditsAsNotApplicable() {
    var response =
        parser.parse(
            """
            {"summary": "s", "edits": [
              {"file": "", "operation": "replace", "search": "a", "replace": "b"},
              {"file": "F.java", "operation": "replace", "search": "", "replace": "b"},
              {"file": "F.java", "operation": "delete", "search": "a", "replace": "b"},
              {"file": "New.java", "operation": "create", "search": "", "replace": ""}
            ], "notes": ""}
            """);
    assertTrue(response.edits().stream().noneMatch(FixResponse.FileEdit::isApplicable));
  }
}
