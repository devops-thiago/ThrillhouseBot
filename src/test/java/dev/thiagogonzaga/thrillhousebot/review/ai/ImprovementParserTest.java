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

class ImprovementParserTest {

  private ImprovementParser parser;

  @BeforeEach
  void setUp() {
    parser = new ImprovementParser(new ObjectMapper());
  }

  @Test
  void parsesImprovementsWithSnakeCaseFields() {
    var response =
        parser.parse(
            """
            {"improvements":[{"file":"Foo.java","line":12,"title":"Close the stream",
            "category":"error-handling","rationale":"Leaks a handle.",
            "suggestion_old":"var in = open();","suggestion_new":"try (var in = open()) {"}]}
            """);

    assertEquals(1, response.improvements().size());
    var improvement = response.improvements().get(0);
    assertEquals("Foo.java", improvement.file());
    assertEquals(12, improvement.line());
    assertEquals("Close the stream", improvement.title());
    assertEquals("error-handling", improvement.category());
    assertEquals("Leaks a handle.", improvement.rationale());
    assertEquals("var in = open();", improvement.suggestionOld());
    assertEquals("try (var in = open()) {", improvement.suggestionNew());
    assertTrue(improvement.isPostable());
  }

  @Test
  void stripsMarkdownFencesAndLeadingProseAroundJson() {
    var response =
        parser.parse(
            """
            Here are the improvements:
            ```json
            {"improvements":[]}
            ```
            """);

    assertTrue(response.improvements().isEmpty());
  }

  @Test
  void rejectsANullResponse() {
    var e = assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
    assertEquals("Model returned an empty response", e.getMessage());
  }

  @Test
  void rejectsABlankResponse() {
    var e = assertThrows(IllegalArgumentException.class, () -> parser.parse("   \n  "));
    assertEquals("Model returned an empty response", e.getMessage());
  }

  @Test
  void rejectsAResponseThatIsNotJson() {
    var e = assertThrows(IllegalArgumentException.class, () -> parser.parse("I cannot do that."));
    assertEquals("Model response is not valid improve JSON", e.getMessage());
  }

  @Test
  void rejectsTruncatedJson() {
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse("{\"improvements\":[{\"file\":\"Foo.java\""));
    assertEquals("Model response is not valid improve JSON", e.getMessage());
  }
}
