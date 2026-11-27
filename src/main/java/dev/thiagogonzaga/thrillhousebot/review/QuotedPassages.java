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

import java.util.ArrayList;
import java.util.List;

/**
 * The quoted passages of a text whose curly quotes are folded into straight ones: a passage of one
 * character or more between double quotes, or between single quotes that are not apostrophes. A
 * single quote opens a passage when no letter or number stands right before it, and closes one when
 * none stands right after it, so the passage may hold apostrophes. No passage runs over a line
 * break, and none starts inside another.
 *
 * <p>The text is read once: a single quote that nothing closes on its line settles that for every
 * quote after it on that line, which is then not read again.
 */
final class QuotedPassages {

  private QuotedPassages() {}

  /** One passage: where its opening quote stands, and where the text goes on after its closing. */
  record Passage(int start, int end) {

    /** What {@code text} holds between this passage's quotes. */
    String body(String text) {
      return text.substring(start + 1, end - 1);
    }
  }

  /** The passages of {@code text}, in order. */
  static List<Passage> in(String text) {
    var passages = new ArrayList<Passage>();
    // No single quote before this index is closed on its line.
    int unclosedBefore = 0;
    int at = 0;
    while (at < text.length()) {
      int end = -1;
      char c = text.charAt(at);
      if (c == '"') {
        end = doubleQuoteEnd(text, at);
      } else if (c == '\'' && at >= unclosedBefore && !letterOrNumberBefore(text, at)) {
        end = singleQuoteEnd(text, at);
        if (end < 0) {
          unclosedBefore = -end;
          end = -1;
        }
      }
      if (end < 0) {
        at++;
      } else {
        passages.add(new Passage(at, end));
        at = end;
      }
    }
    return passages;
  }

  /** {@code text} with each of its passages, quotes included, replaced by one blank. */
  static String blanked(String text) {
    var out = new StringBuilder(text.length());
    int from = 0;
    for (var passage : in(text)) {
      out.append(text, from, passage.start()).append(' ');
      from = passage.end();
    }
    return out.append(text, from, text.length()).toString();
  }

  /** Where the passage the double quote at {@code open} opens ends; -1 when it opens none. */
  private static int doubleQuoteEnd(String text, int open) {
    int at = open + 1;
    while (at < text.length() && text.charAt(at) != '"' && text.charAt(at) != '\n') {
      at++;
    }
    return at > open + 1 && at < text.length() && text.charAt(at) == '"' ? at + 1 : -1;
  }

  /**
   * Where the passage the single quote at {@code open} opens ends. When nothing closes it, the
   * index its line was read to, negated: no quote before that index is closed either.
   */
  private static int singleQuoteEnd(String text, int open) {
    int at = open + 1;
    while (at < text.length() && text.charAt(at) != '\n') {
      if (at > open + 1 && text.charAt(at) == '\'' && !letterOrNumberAt(text, at + 1)) {
        return at + 1;
      }
      at++;
    }
    return -at;
  }

  private static boolean letterOrNumberBefore(String text, int at) {
    return at > 0 && letterOrNumber(text.charAt(at - 1));
  }

  private static boolean letterOrNumberAt(String text, int at) {
    return at < text.length() && letterOrNumber(text.codePointAt(at));
  }

  /** Whether {@code codePoint} is a letter or a number of any script. */
  private static boolean letterOrNumber(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isLetter(codePoint)
        || type == Character.DECIMAL_DIGIT_NUMBER
        || type == Character.LETTER_NUMBER
        || type == Character.OTHER_NUMBER;
  }
}
