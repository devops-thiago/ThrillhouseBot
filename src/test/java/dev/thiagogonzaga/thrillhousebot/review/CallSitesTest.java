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

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The name before a parenthesis, read as {@code (\w+)\s*\(} reads it. */
class CallSitesTest {

  private static String nameBefore(String text) {
    int end = CallSites.nameEnd(text, text.indexOf('('));
    return text.substring(CallSites.nameStart(text, end), end);
  }

  @Test
  void theNameIsTheWordBeforeTheParenthesisAndItsWhitespace() {
    assertEquals("parse_Row9", nameBefore("x = parse_Row9(a)"));
    assertEquals("AZaz_09", nameBefore("AZaz_09 \t\n\u000B\f\r("));
    assertEquals("", nameBefore("(a)"));
    assertEquals("", nameBefore("a.("));
    assertEquals("", nameBefore("a\u0001("));
  }

  /** Every character up to U+017F, before the parenthesis and between a name and it. */
  @Test
  void aWordAndWhitespaceAreWhatARegularExpressionTakesThemFor() {
    var word = Pattern.compile("\\w");
    var space = Pattern.compile("\\s");
    for (char c = 0; c < 0x180; c++) {
      var one = String.valueOf(c);
      assertEquals(word.matcher(one).matches() ? one : "", nameBefore(c + "("), "word " + (int) c);
      assertEquals(
          space.matcher(one).matches(),
          CallSites.nameEnd("a" + c + "(", 2) == 1,
          "whitespace " + (int) c);
    }
  }

  @Test
  void aDigitIsAnAsciiDigit() {
    assertTrue(CallSites.isDigit('0'));
    assertTrue(CallSites.isDigit('9'));
    assertFalse(CallSites.isDigit('/'));
    assertFalse(CallSites.isDigit(':'));
    assertFalse(CallSites.isDigit('٣'));
  }
}
