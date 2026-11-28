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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.thiagogonzaga.thrillhousebot.review.QuotedPassages.Passage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QuotedPassagesTest {

  /** The pattern the passages were read with before, which they are held to. */
  private static final Pattern QUOTED =
      Pattern.compile("\"([^\"\n]++)\"|(?<![\\p{L}\\p{N}])'([^\n]+?)'(?![\\p{L}\\p{N}])");

  /** A letter and a digit outside the basic plane, and the two halves such a character is of. */
  private static final String WIDE_LETTER = "\uD835\uDC00";

  private static final String WIDE_DIGIT = "\uD835\uDFCE";

  private static final String HIGH_HALF = "\uD835";

  private static final String LOW_HALF = "\uDC00";

  /**
   * What the random texts are made of: both quotes, letters and numbers of several scripts, a
   * letter and a digit outside the basic plane, whole and as the halves of a pair, a blank,
   * punctuation and a line break.
   */
  private static final String[] PIECES = {
    "'",
    "'",
    "'",
    "\"",
    "a",
    "Z",
    "é",
    "7",
    "²",
    "Ⅷ",
    WIDE_LETTER,
    WIDE_DIGIT,
    HIGH_HALF,
    LOW_HALF,
    " ",
    " ",
    ".",
    "-",
    "\n",
    "s"
  };

  private static List<Passage> matches(String text) {
    var found = new ArrayList<Passage>();
    var quoted = QUOTED.matcher(text);
    while (quoted.find()) {
      found.add(new Passage(quoted.start(), quoted.end()));
      assertEquals(
          quoted.group(quoted.group(1) != null ? 1 : 2),
          found.getLast().body(text),
          () -> "the body of a passage of: " + text);
    }
    return found;
  }

  private static void assertReadAsThePatternReads(String text) {
    assertEquals(matches(text), QuotedPassages.in(text), () -> "the passages of: " + text);
    assertEquals(
        QUOTED.matcher(text).replaceAll(" "),
        QuotedPassages.blanked(text),
        () -> "without its passages: " + text);
  }

  @Test
  void aPassageIsWhatStandsBetweenItsQuotes() {
    var text = "It says \"rows are kept\" and 'the PR's cache isn't shared', not ''.";

    var passages = QuotedPassages.in(text);

    assertEquals(
        List.of("rows are kept", "the PR's cache isn't shared"),
        passages.stream().map(passage -> passage.body(text)).toList());
    assertEquals("It says   and  , not ''.", QuotedPassages.blanked(text));
  }

  @Test
  void aQuoteNothingClosesOnItsLineOpensNoPassage() {
    assertEquals(List.of(), QuotedPassages.in("the 'cache\nis' shared, \"as\nsaid\""));
    assertEquals(List.of(), QuotedPassages.in("\"\" and 'unclosed\n\"unclosed"));
    // The first quote has no closing one, yet a double-quoted passage after it is still read, and
    // so is a single-quoted one on the next line.
    assertEquals(
        List.of(new Passage(9, 13), new Passage(14, 18)),
        QuotedPassages.in("it 'is a \"go\"\n'no' go"));
  }

  @Test
  void aSingleQuoteMayOpenThePassageItsNeighbourCloses() {
    assertEquals(List.of(new Passage(0, 3)), QuotedPassages.in("'''"));
    assertEquals(List.of(new Passage(0, 4)), QuotedPassages.in("''a'"));
  }

  /**
   * The pattern looked one character back and a whole code point ahead. A letter or digit outside
   * the basic plane is two characters, the second of which is neither on its own: before a quote it
   * does not keep the quote from opening a passage, and after one it keeps it from closing.
   */
  @Test
  void aLetterOutsideTheBasicPlaneCountsAfterAQuoteAndNotBeforeOne() {
    assertEquals(List.of(new Passage(2, 5)), QuotedPassages.in(WIDE_LETTER + "'x'"));
    assertEquals(List.of(new Passage(2, 5)), QuotedPassages.in(WIDE_DIGIT + "'x'"));
    assertEquals(List.of(), QuotedPassages.in(" 'x'" + WIDE_LETTER));
    assertEquals(List.of(), QuotedPassages.in(" 'x'" + WIDE_DIGIT));
    // The passage runs on to the next quote nothing of the kind follows.
    assertEquals(List.of(new Passage(1, 7)), QuotedPassages.in(" 'x'" + WIDE_LETTER + "' "));
    assertEquals(List.of(new Passage(0, 4)), QuotedPassages.in("'" + WIDE_LETTER + "'"));
    // Half a pair is no letter on either side, whatever stands next to it.
    assertEquals(List.of(new Passage(1, 4)), QuotedPassages.in(HIGH_HALF + "'x'"));
    assertEquals(List.of(new Passage(1, 4)), QuotedPassages.in(LOW_HALF + "'x'"));
    assertEquals(List.of(new Passage(2, 5)), QuotedPassages.in("a" + LOW_HALF + "'x'"));
    assertEquals(List.of(new Passage(1, 4)), QuotedPassages.in(" 'x'" + HIGH_HALF));
    assertEquals(List.of(new Passage(1, 4)), QuotedPassages.in(" 'x'" + LOW_HALF + "a"));
    assertEquals(List.of(new Passage(1, 4)), QuotedPassages.in(" 'x'" + HIGH_HALF + "a"));
    assertEquals(List.of(), QuotedPassages.in(" '" + LOW_HALF + "'" + WIDE_LETTER));
  }

  @ParameterizedTest
  @MethodSource("textsOfCharactersOutsideTheBasicPlane")
  void aTextOfCharactersOutsideTheBasicPlaneIsReadAsThePatternReadsIt(String text) {
    assertReadAsThePatternReads(text);
  }

  private static Stream<String> textsOfCharactersOutsideTheBasicPlane() {
    return Stream.of(
        WIDE_LETTER + "'x'",
        WIDE_DIGIT + "'x'",
        " 'x'" + WIDE_LETTER,
        " 'x'" + WIDE_DIGIT,
        " 'x'" + WIDE_LETTER + "' ",
        "'" + WIDE_LETTER + "'",
        HIGH_HALF + "'x'",
        LOW_HALF + "'x'",
        "a" + LOW_HALF + "'x'",
        " 'x'" + HIGH_HALF,
        " 'x'" + LOW_HALF,
        " 'x'" + LOW_HALF + "a",
        " 'x'" + HIGH_HALF + "a",
        " '" + LOW_HALF + "'" + WIDE_LETTER,
        "'x'" + HIGH_HALF + "'" + LOW_HALF + "'y'",
        WIDE_LETTER + "'" + WIDE_DIGIT + "'" + WIDE_LETTER + "' '");
  }

  /** Every text of up to eight characters over both quotes, a letter, a blank and a line break. */
  @Test
  void everyShortTextIsReadAsThePatternReadsIt() {
    char[] alphabet = {'\'', '"', 'a', ' ', '\n'};
    for (int length = 0; length <= 8; length++) {
      var chars = new char[length];
      int combinations = (int) Math.pow(alphabet.length, length);
      for (int code = 0; code < combinations; code++) {
        int rest = code;
        for (int at = 0; at < length; at++) {
          chars[at] = alphabet[rest % alphabet.length];
          rest /= alphabet.length;
        }
        assertReadAsThePatternReads(new String(chars));
      }
    }
  }

  @Test
  void randomTextsAreReadAsThePatternReadsThem() {
    var random = new Random(1501);
    for (int round = 0; round < 60_000; round++) {
      var text = new StringBuilder();
      int pieces = random.nextInt(30);
      for (int piece = 0; piece < pieces; piece++) {
        text.append(PIECES[random.nextInt(PIECES.length)]);
      }
      assertReadAsThePatternReads(text.toString());
    }
  }

  /**
   * 400,000 characters of quotes that open a passage and never close one, or that each hold one.
   * The pattern read the rest of the line from every opening quote: 16,000 characters took 0.8
   * seconds.
   */
  @ParameterizedTest
  @MethodSource("longTextsOfQuotes")
  void aLongTextOfQuotesIsReadOnce(String unit, int held) {
    var text = unit.repeat(400_000 / unit.length());

    var passages = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> QuotedPassages.in(text));

    assertEquals(held, passages.size());
  }

  private static Stream<Arguments> longTextsOfQuotes() {
    return Stream.of(
        Arguments.of(" 'a", 0),
        Arguments.of("'a' ", 100_000),
        Arguments.of("\"a\" ", 100_000),
        Arguments.of("'", 133_333),
        Arguments.of("\"", 0),
        Arguments.of("\"\n", 0),
        Arguments.of("'\n", 0));
  }
}
