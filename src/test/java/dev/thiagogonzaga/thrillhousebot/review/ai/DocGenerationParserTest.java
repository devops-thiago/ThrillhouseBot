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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocGenerationParserTest {

  private DocGenerationParser parser;

  @BeforeEach
  void setUp() {
    parser = new DocGenerationParser(new ObjectMapper());
  }

  @Test
  void parsesSuggestionsWithSnakeCaseFields() {
    var response =
        parser.parse(
            """
            {"docs":[{"file":"Foo.java","line":12,"symbol":"bar",
            "suggestion_old":"void bar() {","suggestion_new":"/** d */\\nvoid bar() {"}]}
            """);

    assertEquals(1, response.docs().size());
    var doc = response.docs().get(0);
    assertEquals("Foo.java", doc.file());
    assertEquals(12, doc.line());
    assertEquals("void bar() {", doc.suggestionOld());
    assertEquals("/** d */\nvoid bar() {", doc.suggestionNew());
    assertTrue(doc.isPostable());
  }

  @Test
  void stripsMarkdownFencesAroundJson() {
    var response =
        parser.parse(
            """
            Here you go:
            ```json
            {"docs":[]}
            ```
            """);

    assertTrue(response.docs().isEmpty());
  }

  @Test
  void dropsNullArrayElements() {
    var response = parser.parse("{\"docs\":[null]}");

    assertTrue(response.docs().isEmpty());
  }

  @Test
  void throwsOnNullEmptyOrInvalidJson() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
    assertThrows(IllegalArgumentException.class, () -> parser.parse(""));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("not json at all"));
  }
}
