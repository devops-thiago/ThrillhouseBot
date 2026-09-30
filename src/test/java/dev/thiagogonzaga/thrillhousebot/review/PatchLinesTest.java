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

import java.util.List;
import org.junit.jupiter.api.Test;

/** #60: the new-side view of a patch the security scan reads. */
class PatchLinesTest {

  @Test
  void numbersNewSideLinesAndSkipsRemovedOnes() {
    var patch =
        PatchLines.parse(
            "@@ -3,3 +3,3 @@ class A\n a\n-b\n+c\n d\n@@ -20 +20,2 @@\n+e\n f\n\\ No newline at end of file\n");
    assertEquals(
        List.of(
            new PatchLines.Line(3, false, "a", 0),
            new PatchLines.Line(4, true, "c", 0),
            new PatchLines.Line(5, false, "d", 0),
            new PatchLines.Line(20, true, "e", 1),
            new PatchLines.Line(21, false, "f", 1)),
        patch.lines());
    assertEquals(1, patch.lastHunk());
    assertTrue(patch.lastHunkReachesEndOfFile());
  }

  @Test
  void threeTrailingContextLinesMeanTheFileGoesOn() {
    var patch = PatchLines.parse("@@ -1,4 +1,5 @@\n+x\n a\n b\n c\n");
    assertFalse(patch.lastHunkReachesEndOfFile());
    var removalLast = PatchLines.parse("@@ -1,4 +1,3 @@\n a\n b\n c\n-d\n");
    assertTrue(removalLast.lastHunkReachesEndOfFile());
    var removalOnlyHunk = PatchLines.parse("@@ -1,1 +1,1 @@\n-a\n+b\n@@ -9,1 +9,0 @@\n-z\n");
    assertEquals(1, removalOnlyHunk.lastHunk());
  }

  @Test
  void anEmptyPatchHasNoLines() {
    for (var patch : new String[] {null, "", "  ", "Binary files differ"}) {
      var parsed = PatchLines.parse(patch);
      assertTrue(parsed.lines().isEmpty());
      assertEquals(-1, parsed.lastHunk());
      assertFalse(parsed.lastHunkReachesEndOfFile());
    }
  }
}
