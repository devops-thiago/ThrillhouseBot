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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.PatchIndex.Kind;
import dev.thiagogonzaga.thrillhousebot.review.PatchIndex.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #1321: the one patch parser, with both sides of every line. */
class PatchIndexTest {

  @Test
  void numbersBothSidesOfEveryLine() {
    var patch = PatchIndex.parse("@@ -3,3 +5,3 @@ class A\n a\n-b\n+c\n d\n");

    assertEquals(
        List.of(
            new Line(0, Kind.CONTEXT, 3, 5, "a", 1),
            new Line(0, Kind.DELETED, 4, 6, "b", 2),
            new Line(0, Kind.ADDED, 5, 6, "c", 3),
            new Line(0, Kind.CONTEXT, 5, 7, "d", 4)),
        patch.lines());
    var deleted = patch.lines().get(1);
    assertTrue(deleted.deleted() && deleted.onLeft() && !deleted.onRight());
    assertEquals('-', deleted.marker());
    var added = patch.lines().get(2);
    assertTrue(added.added() && added.onRight() && !added.onLeft());
    assertEquals(' ', patch.lines().get(0).marker());
  }

  @Test
  void codeThatReadsLikeAFileHeaderInsideAHunkIsALine() {
    var patch = PatchIndex.parse("@@ -1,2 +1,2 @@\n---x;\n+++x;\n");

    assertEquals(List.of("--x;", "++x;"), patch.lines().stream().map(Line::text).toList());
  }

  @Test
  void aRawGitDiffsFileHeadersEndTheHunkAndAreNotLines() {
    var patch =
        PatchIndex.parse(
            """
            @@ -1,1 +1,1 @@
            -a
            +b
            diff --git a/B b/B
            index 1..2
            --- a/B
            +++ b/B
            @@ -4,1 +4,1 @@
            -c
            +d
            """);

    assertEquals(List.of("a", "b", "c", "d"), patch.lines().stream().map(Line::text).toList());
    assertEquals(1, patch.lastHunk());
  }

  @Test
  void anUnreadableHeaderStartsNoHunk() {
    var builder = new PatchIndex.Builder();
    for (var raw : List.of("@@ not a header @@", " run();", "-old();", "+++ b/A", "--- a/A", "")) {
      builder.accept(raw);
    }

    assertEquals(List.of(), builder.build().lines());
    assertEquals(-1, builder.build().lastHunk());
    assertEquals(
        List.of(" run();", "-old();"),
        builder.looseLines().stream().map(line -> line.marker() + line.text()).toList());
  }

  @Test
  void endingAHunkLeavesTheRestOutOfIt() {
    var builder = new PatchIndex.Builder();
    builder.accept("@@ -1,2 +1,2 @@");
    builder.accept("+in();");
    builder.endHunk();
    builder.accept("+out();");

    assertEquals(List.of("in();"), builder.build().lines().stream().map(Line::text).toList());
  }

  @Test
  void hunksGroupLinesAndKeepARemovalOnlyHunk() {
    var patch = PatchIndex.parse("@@ -1,1 +1,1 @@\n-a\n+b\n@@ -9,1 +8,0 @@\n-z\n");

    assertEquals(2, patch.hunks().size());
    assertEquals(1, patch.hunks().get(1).size());
    assertEquals(1, patch.rightSide().size());
    assertTrue(patch.lastHunkReachesEndOfFile());
  }

  @Test
  void threeTrailingContextLinesMeanTheFileGoesOn() {
    assertFalse(PatchIndex.parse("@@ -1,3 +1,4 @@\n+x\n a\n b\n c\n").lastHunkReachesEndOfFile());
    assertFalse(PatchIndex.parse((String) null).lastHunkReachesEndOfFile());
    assertEquals(List.of(), PatchIndex.parse(" ").lines());
  }
}
