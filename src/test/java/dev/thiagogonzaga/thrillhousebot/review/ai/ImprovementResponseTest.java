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

import dev.thiagogonzaga.thrillhousebot.review.ai.ImprovementResponse.Improvement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ImprovementResponseTest {

  private static Improvement improvement(
      String file, int line, String suggestionOld, String suggestionNew) {
    return new Improvement(file, line, "title", "readability", "why", suggestionOld, suggestionNew);
  }

  @Test
  void treatsANullImprovementListAsEmpty() {
    assertTrue(new ImprovementResponse(null).improvements().isEmpty());
  }

  @Test
  void dropsNullEntriesSoOneBadElementDoesNotFailTheWholeCommand() {
    var response =
        new ImprovementResponse(
            Arrays.asList(improvement("Foo.java", 1, "old", "new"), null, null));

    assertEquals(1, response.improvements().size());
    assertEquals("Foo.java", response.improvements().get(0).file());
  }

  @Test
  void keepsAnAlreadyEmptyList() {
    assertTrue(new ImprovementResponse(List.of()).improvements().isEmpty());
  }

  @Test
  void aFullyPopulatedImprovementIsPostable() {
    assertTrue(improvement("Foo.java", 1, "old", "new").isPostable());
  }

  static Stream<Arguments> unpostable() {
    return Stream.of(
        Arguments.of("null file", improvement(null, 1, "old", "new")),
        Arguments.of("blank file", improvement("  ", 1, "old", "new")),
        Arguments.of("zero line", improvement("Foo.java", 0, "old", "new")),
        Arguments.of("negative line", improvement("Foo.java", -3, "old", "new")),
        Arguments.of("null suggestion_old", improvement("Foo.java", 1, null, "new")),
        Arguments.of("blank suggestion_old", improvement("Foo.java", 1, " \n ", "new")),
        Arguments.of("null suggestion_new", improvement("Foo.java", 1, "old", null)),
        Arguments.of("blank suggestion_new", improvement("Foo.java", 1, "old", "   ")));
  }

  @ParameterizedTest(name = "{0} is not postable")
  @MethodSource("unpostable")
  void anImprovementMissingRequiredDataIsNotPostable(String label, Improvement improvement) {
    assertFalse(improvement.isPostable(), label);
  }
}
