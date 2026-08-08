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

class UnitTestGenerationParserTest {

  private final UnitTestGenerationParser parser = new UnitTestGenerationParser(new ObjectMapper());

  @Test
  void parsesTheProposedTestFiles() {
    var response =
        parser.parse(
            """
            {"tests":[{"path":"src/test/java/FooTest.java","language":"java",
              "covers":"the null branch","code":"class FooTest {}"}],
             "notes":"no fixtures available"}
            """);

    assertEquals(1, response.tests().size());
    var test = response.tests().getFirst();
    assertEquals("src/test/java/FooTest.java", test.path());
    assertEquals("java", test.language());
    assertEquals("the null branch", test.covers());
    assertEquals("class FooTest {}", test.code());
    assertEquals("no fixtures available", response.notes());
    assertTrue(test.isPostable());
  }

  @Test
  void unwrapsAFencedJsonReply() {
    var response =
        parser.parse(
            """
            ```json
            {"tests":[{"path":"t/FooTest.java","language":"java","covers":"c","code":"x"}],"notes":""}
            ```
            """);

    assertEquals(1, response.tests().size());
    assertEquals("t/FooTest.java", response.tests().getFirst().path());
  }

  @Test
  void normalizesMissingTestsAndNotes() {
    var response = parser.parse("{}");

    assertTrue(response.tests().isEmpty());
    assertEquals("", response.notes());
    assertTrue(response.postableTests().isEmpty());
  }

  @Test
  void dropsNullEntriesAndKeepsOnlyPostableProposals() {
    var response =
        parser.parse(
            """
            {"tests":[null,
                      {"path":" ","language":"java","covers":"c","code":"x"},
                      {"path":"t/FooTest.java","language":"java","covers":"c","code":"  "},
                      {"path":"t/BarTest.java","language":"java","covers":"c","code":"x"}]}
            """);

    assertEquals(3, response.tests().size());
    assertEquals(1, response.postableTests().size());
    assertEquals("t/BarTest.java", response.postableTests().getFirst().path());
  }

  @Test
  void rejectsAnEmptyOrUnparseableReply() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("   "));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("no json here"));
  }
}
