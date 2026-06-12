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

class RiskLevelTest {

  @Test
  void fromStringShouldReturnCorrectValues() {
    assertEquals(RiskLevel.CRITICAL, RiskLevel.fromString("critical"));
    assertEquals(RiskLevel.HIGH, RiskLevel.fromString("high"));
    assertEquals(RiskLevel.MEDIUM, RiskLevel.fromString("medium"));
    assertEquals(RiskLevel.LOW, RiskLevel.fromString("low"));
  }

  @Test
  void fromStringShouldBeCaseInsensitive() {
    assertEquals(RiskLevel.CRITICAL, RiskLevel.fromString("CRITICAL"));
    assertEquals(RiskLevel.HIGH, RiskLevel.fromString("HIGH"));
    assertEquals(RiskLevel.CRITICAL, RiskLevel.fromString("Critical"));
  }

  @Test
  void fromStringShouldReturnLowForNull() {
    assertEquals(RiskLevel.LOW, RiskLevel.fromString(null));
  }

  @Test
  void fromStringShouldReturnLowForUnknown() {
    assertEquals(RiskLevel.LOW, RiskLevel.fromString("unknown"));
    assertEquals(RiskLevel.LOW, RiskLevel.fromString(""));
  }

  @Test
  void toEmojiShouldReturnCorrectEmoji() {
    assertEquals("🔴", RiskLevel.CRITICAL.toEmoji());
    assertEquals("🟠", RiskLevel.HIGH.toEmoji());
    assertEquals("🟡", RiskLevel.MEDIUM.toEmoji());
    assertEquals("🔵", RiskLevel.LOW.toEmoji());
  }

  @Test
  void compareToShouldOrderBySeverityDescending() {
    // Enum ordinal: CRITICAL(0) < HIGH(1) < MEDIUM(2) < LOW(3)
    assertTrue(RiskLevel.CRITICAL.compareTo(RiskLevel.HIGH) < 0);
    assertTrue(RiskLevel.HIGH.compareTo(RiskLevel.MEDIUM) < 0);
    assertTrue(RiskLevel.MEDIUM.compareTo(RiskLevel.LOW) < 0);
    assertTrue(RiskLevel.CRITICAL.compareTo(RiskLevel.LOW) < 0);
    assertEquals(0, RiskLevel.HIGH.compareTo(RiskLevel.HIGH));
  }
}
