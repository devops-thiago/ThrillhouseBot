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
 * Bounds the runs of combining marks in a line a pull request adds, before a pattern with a word
 * boundary reads it.
 *
 * <p>To tell whether a combining mark is part of a word, {@code \b} in {@code java.util.regex}
 * steps back over the marks before it to the character they stand on. It does so again at every
 * place it is tried, so a pattern that looks for a boundary along a run of marks reads the run once
 * per mark. No pattern can be written around that; the text is bounded where it comes in instead.
 *
 * <p>Only the marks {@code \b} steps over count: the non-spacing ones of the basic plane. One
 * outside it is two characters, neither a mark on its own, and stops the walk.
 */
final class CombiningMarks {

  /**
   * The longest run kept. Written text stacks a few marks on a character; Unicode's stream-safe
   * format (UAX #15) allows 30, so no text in that format is changed.
   */
  static final int MAX_RUN = 30;

  private CombiningMarks() {}

  /**
   * {@code line} with every run of combining marks cut to its first {@value #MAX_RUN}; the line
   * itself when it has no longer run, which is nearly always. What a pattern finds beside a run is
   * the same either way: the character the marks stand on is kept, and marks still follow it.
   */
  static String bounded(String line) {
    int run = 0;
    for (int at = 0; at < line.length(); at++) {
      run = isMark(line.charAt(at)) ? run + 1 : 0;
      if (run > MAX_RUN) {
        return cut(line, at);
      }
    }
    return line;
  }

  /** {@code line} cut from {@code at}, the first mark past the longest run kept. */
  private static String cut(String line, int at) {
    var kept = new StringBuilder(line.length()).append(line, 0, at);
    int run = MAX_RUN;
    for (int next = at; next < line.length(); next++) {
      char c = line.charAt(next);
      run = isMark(c) ? run + 1 : 0;
      if (run <= MAX_RUN) {
        kept.append(c);
      }
    }
    return kept.toString();
  }

  private static boolean isMark(char c) {
    return Character.getType(c) == Character.NON_SPACING_MARK;
  }
}
