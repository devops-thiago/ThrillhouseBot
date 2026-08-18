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
package dev.thiagogonzaga.thrillhousebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers {@link LogSafe} — the collapse every untrusted value goes through on its way into a log
 * line. The characters are named and built by code point here rather than referenced from the
 * production pattern, so these tests pin the class of harm independently of the expression that
 * implements it.
 */
class LogSafeTest {

  private static String between(int codePoint) {
    return "a" + (char) codePoint + "b";
  }

  /**
   * The survivors of a bare {@code \s}, which java.util.regex reads as the ASCII six: each of these
   * is a record boundary or a screen control to some reader downstream, so each must come out as an
   * ordinary space (#731, #742). The last four are the ASCII six itself, which must not regress.
   */
  private static Stream<Arguments> charactersAReaderCouldTakeForAControl() {
    return Stream.of(
        arguments("NEL (U+0085)", between(0x0085)),
        arguments("LINE SEPARATOR (U+2028)", between(0x2028)),
        arguments("PARAGRAPH SEPARATOR (U+2029)", between(0x2029)),
        arguments("NUL", between(0x0000)),
        arguments("ESC", between(0x001B)),
        arguments("DEL", between(0x007F)),
        arguments("a C1 control (U+0090)", between(0x0090)),
        arguments("RIGHT-TO-LEFT OVERRIDE", between(0x202E)),
        arguments("ZERO WIDTH SPACE", between(0x200B)),
        arguments("ZERO WIDTH JOINER", between(0x200D)),
        arguments("the byte order mark", between(0xFEFF)),
        arguments("SOFT HYPHEN", between(0x00AD)),
        arguments("LF", between('\n')),
        arguments("CR", between('\r')),
        arguments("TAB", between('\t')),
        arguments("a plain space", between(' ')));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("charactersAReaderCouldTakeForAControl")
  void flattensEveryCharacterAReaderCouldTakeForAControl(String name, String value) {
    assertEquals("a b", LogSafe.oneLine(value), name);
  }

  @Test
  void collapsesARunOfThemIntoOneSpaceRatherThanOnePerCharacter() {
    assertEquals("a b", LogSafe.oneLine("a\r\n \t" + (char) 0x0085 + "b"));
  }

  @Test
  void trimsSuchARunAtEitherEndRatherThanLeavingAStraySpace() {
    assertEquals("boom", LogSafe.oneLine((char) 0x2028 + " boom " + (char) 0x0085));
  }

  /**
   * A space rather than a deletion: {@code admin<ZWSP>istrator} must not close up into a different
   * real word, which is half of why the invisible characters are worth collapsing at all.
   */
  @Test
  void separatesRatherThanDeletesSoTwoHalvesCannotCloseUpIntoOneWord() {
    assertEquals("admin istrator", LogSafe.oneLine("admin" + (char) 0x200B + "istrator"));
  }

  /**
   * The space separators a bare {@code \s} also leaves behind. None of these forges a boundary, so
   * they are the cheapest of the four classes to justify; they are here because two values
   * differing only by one of them render identically in a log line.
   */
  private static Stream<Arguments> spaceSeparatorsThatRenderLikeASpace() {
    return Stream.of(
        arguments("NO-BREAK SPACE (U+00A0)", between(0x00A0)),
        arguments("EN QUAD (U+2000)", between(0x2000)),
        arguments("EM SPACE (U+2003)", between(0x2003)),
        arguments("FIGURE SPACE (U+2007)", between(0x2007)),
        arguments("NARROW NO-BREAK SPACE (U+202F)", between(0x202F)),
        arguments("MEDIUM MATHEMATICAL SPACE (U+205F)", between(0x205F)),
        arguments("IDEOGRAPHIC SPACE (U+3000)", between(0x3000)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("spaceSeparatorsThatRenderLikeASpace")
  void flattensEverySpaceSeparatorThatRendersLikeAnOrdinarySpace(String name, String value) {
    assertEquals("a b", LogSafe.oneLine(value), name);
  }

  /**
   * The trim is no backstop for these: it reads {@code Character.isWhitespace}, which is false for
   * U+00A0, U+2007 and U+202F, so a value bounded by them kept them at every position until the
   * class covered {@code \p{IsZs}}.
   */
  @Test
  void trimsASpaceSeparatorTheWhitespaceTestDoesNotRecognise() {
    assertEquals("boom", LogSafe.oneLine((char) 0x00A0 + "boom" + (char) 0x202F));
  }

  @Test
  void leavesAnOrdinaryValueAlone() {
    assertEquals("src/main/java/App.java", LogSafe.oneLine("src/main/java/App.java"));
  }

  /** A caller logging an absent value should not have to guard for it. */
  @Test
  void flattensAnAbsentValueToTheEmptyString() {
    assertEquals("", LogSafe.oneLine(null));
  }
}
