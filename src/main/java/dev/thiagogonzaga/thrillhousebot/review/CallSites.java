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

/**
 * Where the name before an argument list stands: the word that ends at a {@code (}, with any
 * whitespace between the two. Read backwards from the parenthesis, so each character of a line is
 * looked at once however many names and parentheses the line holds.
 *
 * <p>A word is a run of ASCII letters, digits and underscores and whitespace is the six ASCII
 * whitespace characters, as {@code \w} and {@code \s} are in a regular expression.
 */
final class CallSites {

  private CallSites() {}

  /** Where the word before {@code paren} ends: {@code paren} itself, or the whitespace before. */
  static int nameEnd(String text, int paren) {
    int end = paren;
    while (end > 0 && isSpace(text.charAt(end - 1))) {
      end--;
    }
    return end;
  }

  /** Where the word that ends at {@code end} starts; {@code end} when no word ends there. */
  static int nameStart(String text, int end) {
    int start = end;
    while (start > 0 && isWord(text.charAt(start - 1))) {
      start--;
    }
    return start;
  }

  static boolean isWord(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || isDigit(c) || c == '_';
  }

  static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  static boolean isSpace(char c) {
    return c == ' ' || (c >= '\t' && c <= '\r');
  }
}
