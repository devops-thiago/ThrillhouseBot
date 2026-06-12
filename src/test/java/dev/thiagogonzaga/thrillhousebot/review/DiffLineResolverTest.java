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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class DiffLineResolverTest {

  private static final String PATCH =
      """
      @@ -10,3 +10,4 @@
       def unchanged():
      -    old_line()
      +    new_line()
      +    added_line()
      """;

  @Test
  void shouldAcceptExactLineInDiff() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertEquals(OptionalInt.of(11), resolver.resolveRightSideLine("main.py", 11));
  }

  @Test
  void shouldSnapToNearestCommentableLine() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertEquals(OptionalInt.of(12), resolver.resolveRightSideLine("main.py", 15));
    assertEquals(OptionalInt.of(10), resolver.resolveRightSideLine("main.py", 5));
  }

  @Test
  void shouldReturnEmptyWhenFileNotInDiff() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertTrue(resolver.resolveRightSideLine("other.py", 10).isEmpty());
  }

  @Test
  void shouldParseRightSideLinesFromPatch() {
    var lines = DiffLineResolver.parseRightSideLines(PATCH);

    assertEquals(3, lines.size());
    assertTrue(lines.contains(10));
    assertTrue(lines.contains(11));
    assertTrue(lines.contains(12));
  }

  @Test
  void shouldReturnEmptyForInvalidInputs() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertTrue(resolver.resolveRightSideLine(null, 10).isEmpty());
    assertTrue(resolver.resolveRightSideLine("main.py", 0).isEmpty());
    assertTrue(resolver.resolveRightSideLine("main.py", -1).isEmpty());
  }

  @Test
  void shouldReturnEmptyForBlankPatch() {
    var resolver = new DiffLineResolver(Map.of("main.py", ""));

    assertTrue(resolver.resolveRightSideLine("main.py", 10).isEmpty());
    assertTrue(DiffLineResolver.parseRightSideLines(null).isEmpty());
    assertTrue(DiffLineResolver.parseRightSideLines("   ").isEmpty());
  }

  @Test
  void shouldPreferCeilingWhenItIsCloserThanFloor() {
    var patch =
        """
        @@ -1,1 +10,1 @@
        +ten
        @@ -1,1 +20,1 @@
        +twenty
        """;
    var resolver = new DiffLineResolver(Map.of("main.py", patch));

    assertEquals(OptionalInt.of(20), resolver.resolveRightSideLine("main.py", 17));
  }

  @Test
  void shouldPreferCloserLineWhenSnapping() {
    var resolver = new DiffLineResolver(Map.of("main.py", PATCH));

    assertEquals(OptionalInt.of(11), resolver.resolveRightSideLine("main.py", 11));
    assertEquals(OptionalInt.of(12), resolver.resolveRightSideLine("main.py", 13));
    assertEquals(OptionalInt.of(10), resolver.resolveRightSideLine("main.py", 9));
  }

  @Test
  void shouldUseCeilingWhenOnlyAboveRequestedLineExists() {
    var patch =
        """
        @@ -1,1 +20,1 @@
        -old
        +new
        """;
    var resolver = new DiffLineResolver(Map.of("main.py", patch));

    assertEquals(OptionalInt.of(20), resolver.resolveRightSideLine("main.py", 5));
  }

  @Test
  void shouldUseFloorWhenOnlyBelowRequestedLineExists() {
    var patch =
        """
        @@ -1,1 +5,1 @@
        -old
        +new
        """;
    var resolver = new DiffLineResolver(Map.of("main.py", patch));

    assertEquals(OptionalInt.of(5), resolver.resolveRightSideLine("main.py", 99));
  }

  @Test
  void shouldIgnoreDeletionOnlyLines() {
    var patch =
        """
        @@ -10,2 +10,0 @@
        -removed_one
        -removed_two
        """;
    var lines = DiffLineResolver.parseRightSideLines(patch);

    assertTrue(lines.isEmpty());
  }

  @Test
  void shouldTrackContextLinesInPatch() {
    var patch =
        """
        @@ -1,2 +1,2 @@
         context
        -old
        +new
        """;
    var lines = DiffLineResolver.parseRightSideLines(patch);

    assertEquals(2, lines.size());
    assertTrue(lines.contains(1));
    assertTrue(lines.contains(2));
  }
}
