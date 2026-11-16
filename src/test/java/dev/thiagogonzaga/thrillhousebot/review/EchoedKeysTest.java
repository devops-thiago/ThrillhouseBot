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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EchoedKeysTest {

  private static final List<String> LISTED = List.of("aaaa0001", "bbbb0002", "cccc0003");

  @Test
  void anIdAndKeyThatAgreeNameTheirEntry() {
    assertArrayEquals(
        new int[] {1, 3},
        EchoedKeys.resolve(LISTED, List.of(1, 3), List.of("aaaa0001", " CCCC0003 ")));
  }

  @Test
  void rotatedIdsAreReadByTheirKeys() {
    // #1401: ids 1..3 echoing the keys of 2, 3, 1.
    assertArrayEquals(
        new int[] {2, 3, 1},
        EchoedKeys.resolve(LISTED, List.of(1, 2, 3), List.of("bbbb0002", "cccc0003", "aaaa0001")));
  }

  @Test
  void aKeyedAnswerNeverTakesAnEntryAnAgreeingAnswerNames() {
    // The first answer's key names #2, which the second answer names by id and key.
    assertArrayEquals(
        new int[] {0, 2},
        EchoedKeys.resolve(LISTED, List.of(1, 2), List.of("bbbb0002", "bbbb0002")));
  }

  @Test
  void theFirstOfTwoKeyedAnswersForOneEntryTakesIt() {
    assertArrayEquals(
        new int[] {2, 0},
        EchoedKeys.resolve(LISTED, List.of(0, 9), List.of("bbbb0002", "bbbb0002")));
  }

  @Test
  void aMissingUnknownOrSharedKeyNamesNothing() {
    var twins = List.of("aaaa0001", "aaaa0001", "cccc0003");
    assertArrayEquals(
        new int[] {0, 0, 0},
        EchoedKeys.resolve(twins, List.of(1, 3, 3), Arrays.asList(null, "deadbeef", "aaaa0001")));
    assertArrayEquals(new int[] {0}, EchoedKeys.resolve(LISTED, List.of(1), List.of("  ")));
  }

  @Test
  void aKeyIsReadStrippedAndLowerCased() {
    assertEquals("ab12cd34", EchoedKeys.normalized(" AB12cd34 "));
    assertEquals("", EchoedKeys.normalized(null));
  }
}
