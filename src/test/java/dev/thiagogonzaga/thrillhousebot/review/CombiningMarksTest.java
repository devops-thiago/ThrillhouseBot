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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CombiningMarksTest {

  private static final String MARK = "́";

  private static final String LONGEST = MARK.repeat(CombiningMarks.MAX_RUN);

  @Test
  void aLineWithNoLongRunIsTheLineItself() {
    for (var line :
        new String[] {
          "", "acl = \"public-read\"", "Việt Nam: tiếng Việt", "e" + LONGEST + " e" + LONGEST
        }) {
      assertSame(line, CombiningMarks.bounded(line));
    }
  }

  @Test
  void aRunIsCutToItsFirstMarksAndTheTextAroundItIsKept() {
    assertEquals("e" + LONGEST, CombiningMarks.bounded("e" + LONGEST + MARK));
    assertEquals(
        "a" + LONGEST + " acl = b" + MARK + " c" + LONGEST + "d",
        CombiningMarks.bounded(
            "a" + LONGEST.repeat(3) + " acl = b" + MARK + " c" + LONGEST + MARK + "̀d"));
    assertEquals(LONGEST, CombiningMarks.bounded(MARK.repeat(1_000)));
  }

  @Test
  void onlyTheMarksAWordBoundarySteppedOverAreCounted() {
    // A spacing mark, an enclosing one, and a non-spacing one outside the basic plane.
    for (var other : new String[] {"ः", "⃝", "𝅧"}) {
      var line = "a" + other.repeat(100);
      assertSame(line, CombiningMarks.bounded(line));
    }
  }

  /** What bounding is for: the boundary is found in the same places, in time that does not grow. */
  @Test
  void aWordBoundaryReadsABoundedLineInOnePass() {
    var word = Pattern.compile("\\bacl\\b");
    var marks = MARK.repeat(400_000);

    for (var line : new String[] {"a" + marks + " acl", "a" + marks + "acl", "acl" + marks + "x"}) {
      var bounded = CombiningMarks.bounded(line);
      assertEquals(
          line.endsWith(" acl"),
          assertTimeoutPreemptively(Duration.ofSeconds(10), () -> word.matcher(bounded).find()),
          line.replace(marks, "…"));
    }
  }
}
