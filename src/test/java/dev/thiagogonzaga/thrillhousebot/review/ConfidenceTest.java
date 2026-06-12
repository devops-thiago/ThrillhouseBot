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

import org.junit.jupiter.api.Test;

class ConfidenceTest {

  @Test
  void shouldParseKnownValuesCaseInsensitively() {
    assertEquals(Confidence.HIGH, Confidence.fromString("high"));
    assertEquals(Confidence.HIGH, Confidence.fromString("HIGH"));
    assertEquals(Confidence.MEDIUM, Confidence.fromString("medium"));
    assertEquals(Confidence.LOW, Confidence.fromString("Low"));
  }

  @Test
  void shouldDefaultAbsentValuesToHigh() {
    assertEquals(Confidence.HIGH, Confidence.fromString(null));
    assertEquals(Confidence.HIGH, Confidence.fromString(""));
    assertEquals(Confidence.HIGH, Confidence.fromString("   "));
  }

  @Test
  void shouldMapUnrecognizedValuesToMedium() {
    assertEquals(Confidence.MEDIUM, Confidence.fromString("uncertain"));
    assertEquals(Confidence.MEDIUM, Confidence.fromString("very-high"));
  }

  @Test
  void shouldOrderFromMostToLeastCertain() {
    assertTrue(Confidence.HIGH.ordinal() < Confidence.MEDIUM.ordinal());
    assertTrue(Confidence.MEDIUM.ordinal() < Confidence.LOW.ordinal());
  }
}
