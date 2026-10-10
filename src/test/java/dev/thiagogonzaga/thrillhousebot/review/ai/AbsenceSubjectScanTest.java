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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The pronoun-subject absence recognizer of {@link FindingVerificationService} (#1030): the
 * backward subject reading accepts exactly what the end-anchored subject pattern found in each
 * prefix, every supported sentence form keeps its reading, and the work grows linearly with the
 * number of verb tokens instead of re-searching every growing prefix.
 */
class AbsenceSubjectScanTest {

  /** The subject pattern the backward reading replaces, as it was searched over each prefix. */
  private static final Pattern PREFIX_SUBJECT =
      Pattern.compile("\\b(nothing|nobody)\\s+(\\w+[\\s-]+){0,3}\\z", Pattern.CASE_INSENSITIVE);

  @Test
  void readsBackwardsExactlyWhatThePrefixSearchFound() {
    String[] parts = {
      "nothing",
      "Nobody",
      "NOTHING",
      "nothingness",
      "xnobody",
      "the",
      "a",
      "pre",
      "_9",
      " ",
      "  ",
      "\t",
      "\n",
      "-",
      " - ",
      ".",
      ",",
      ";",
      "é",
      "\u000B"
    };
    var random = new Random(1030);
    for (var n = 0; n < 50_000; n++) {
      var text = new StringBuilder();
      for (var k = random.nextInt(12); k > 0; k--) {
        text.append(parts[random.nextInt(parts.length)]);
      }
      var s = text.toString();
      var end = random.nextInt(s.length() + 1);
      assertEquals(
          PREFIX_SUBJECT.matcher(s).region(0, end).find(),
          FindingVerificationService.negatingSubjectEndsAt(s, end),
          () -> "\"" + s + "\" up to " + end);
    }
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Nothing sanitizes the value before innerHTML receives it. | true
          nobody escaped it before render | true
          Nothing in this handler sanitizes the value. | true
          Nothing in this request handler sanitizes the value. | false
          Nothing pre-render sanitizes the value. | true
          NOBODY ESCAPES IT | true
          Nothing can sanitize the value before innerHTML receives it. | true
          Nothing can ever sanitize the value. | true
          Nothing can not sanitize the value. | false
          Nothing escapes validation before the value reaches innerHTML. | false
          Nothing escapes heavy validation before the value reaches innerHTML. | false
          Nothing escapes the sanitizer before the value reaches innerHTML. | false
          Nothing escapes the value before it reaches innerHTML. | true
          Nothing can escape validation before the value reaches innerHTML. | false
          Nothing escapes validation and nothing sanitizes the value before innerHTML. | true
          Nothing escapes, sanitizes, or validates the value. | true
          Nothing escapes; the sanitizer runs on render. | false
          nothingness sanitizes the value | false
          nothing- sanitizes the value | false
          The value is sanitized before render. | false
          """)
  void everySupportedSentenceFormKeepsItsReading(String sentence, boolean absence) {
    assertEquals(absence, FindingVerificationService.claimsAbsenceAsSubject(sentence), sentence);
  }

  @Test
  void theWorkGrowsLinearlyWithTheVerbTokens() {
    for (var unit : new String[] {"sanitizes ", "the x y escaping ", "can   -  sanitize "}) {
      var small = charReads(unit.repeat(1_000));
      var large = charReads(unit.repeat(4_000));
      // Four times the tokens: about four times the reads, where a scan of every growing prefix
      // reads about sixteen times as many.
      assertTrue(
          large <= 5 * small,
          () -> "\"" + unit + "\": " + small + " reads for 1000 tokens, " + large + " for 4000");
    }
  }

  /** How many characters the recognizer reads to decide {@code text}. */
  private static long charReads(String text) {
    var counting = new CountingText(text);
    FindingVerificationService.claimsAbsenceAsSubject(counting);
    return counting.reads;
  }

  /** A {@link CharSequence} that counts its {@link #charAt} calls. */
  private static final class CountingText implements CharSequence {
    private final String text;
    private long reads;

    CountingText(String text) {
      this.text = text;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      reads++;
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return text.subSequence(start, end);
    }

    @Override
    public String toString() {
      return text;
    }
  }
}
