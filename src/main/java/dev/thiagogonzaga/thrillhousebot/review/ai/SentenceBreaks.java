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

import java.util.regex.Pattern;

/**
 * Where a sentence of model-written text ends. A "!" or "?" ends one; a period ends one only when a
 * blank or the end follows it, so a decimal ("1.5") or a dotted name ({@code Foo.bar}, {@code
 * Client.java}) ends nothing, and only when it does not close a common abbreviation ("e.g.",
 * "i.e.", "etc.", "vs.", "cf.").
 */
public final class SentenceBreaks {

  /** A common abbreviation a period closes, read just before that period's end. */
  public static final Pattern ABBREVIATION =
      Pattern.compile("(?i)\\b(?:e\\.g|i\\.e|etc|vs|cf)\\.\\z");

  /** Characters at most an {@link #ABBREVIATION} spans, its period included. */
  public static final int ABBREVIATION_CHARS = 4;

  private SentenceBreaks() {}

  /** Whether the character just before {@code end} in {@code text} ends a sentence. */
  public static boolean endsSentenceAt(String text, int end) {
    if (end <= 0 || end > text.length()) {
      return false;
    }
    var mark = text.charAt(end - 1);
    if (mark == '!' || mark == '?') {
      return true;
    }
    if (mark != '.' || (end < text.length() && !Character.isWhitespace(text.charAt(end)))) {
      return false;
    }
    var lead = text.substring(Math.max(0, end - ABBREVIATION_CHARS - 1), end);
    return !ABBREVIATION.matcher(lead).find();
  }
}
