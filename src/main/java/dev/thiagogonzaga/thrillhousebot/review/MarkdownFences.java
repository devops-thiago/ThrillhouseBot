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

/** Shared bounds for code blocks that text screens must preserve byte for byte. */
final class MarkdownFences {
  private MarkdownFences() {}

  /** The fence at {@code at} ends at a run of its own marker at least as long as the opener. */
  static int endAt(String text, int at) {
    String marker;
    if (text.startsWith("```", at)) {
      marker = "```";
    } else if (text.startsWith("~~~", at)) {
      marker = "~~~";
    } else {
      return -1;
    }
    char c = marker.charAt(0);
    int from = runEnd(text, at, c);
    int length = from - at;
    int depth = lineDepth(text, at, true);
    if (c == '`') {
      int newline = text.indexOf('\n', from);
      int tick = text.indexOf('`', from);
      // A backtick in the opening line's info text makes this an inline span, not a block.
      if (tick >= 0 && (newline < 0 || tick < newline)) {
        depth = -1;
      }
    }
    int close = text.indexOf(marker, from);
    while (close >= 0) {
      int end = runEnd(text, close, c);
      if (end - close >= length && (depth < 0 || closingLine(text, close, end, depth))) {
        return end;
      }
      close = text.indexOf(marker, end);
    }
    return text.length();
  }

  /** A block closer stays at its opener's quote depth and has only blanks after its marker. */
  private static boolean closingLine(String text, int at, int end, int depth) {
    if (lineDepth(text, at, false) != depth) {
      return false;
    }
    for (int i = end; i < text.length() && text.charAt(i) != '\n'; i++) {
      char c = text.charAt(i);
      if (c != ' ' && c != '\t' && c != '\r') {
        return false;
      }
    }
    return true;
  }

  /** Indentation and quote markers may precede a block; an opener can also follow a list marker. */
  private static int lineDepth(String text, int at, boolean allowList) {
    int start = prefixStart(text, at, allowList);
    if (start < 0) {
      return -1;
    }
    int depth = 0;
    int i = start;
    while (i < at) {
      char c = text.charAt(i);
      if (blank(c)) {
        i++;
      } else if (c == '>') {
        depth++;
        i++;
      } else {
        int end = listMarkerEnd(text, i, at);
        if (end < 0) {
          return -1;
        }
        i = end;
      }
    }
    return depth;
  }

  /** Stop at ordinary prose immediately, rather than rescanning a long line for each span. */
  private static int prefixStart(String text, int at, boolean allowList) {
    int start = at;
    while (start > 0 && text.charAt(start - 1) != '\n') {
      char c = text.charAt(start - 1);
      if (!blank(c) && c != '>' && !(allowList && listCharacter(c))) {
        return -1;
      }
      start--;
    }
    return start;
  }

  private static boolean listCharacter(char c) {
    return Character.isDigit(c) || "-*+.)".indexOf(c) >= 0;
  }

  /** The end of an unordered or numbered marker, only when a blank follows it. */
  private static int listMarkerEnd(String text, int at, int limit) {
    int end = at + 1;
    if (Character.isDigit(text.charAt(at))) {
      while (end < limit && Character.isDigit(text.charAt(end))) {
        end++;
      }
      if (end == limit || ".)".indexOf(text.charAt(end)) < 0) {
        return -1;
      }
      end++;
    } else if ("-*+".indexOf(text.charAt(at)) < 0) {
      return -1;
    }
    return end < limit && blank(text.charAt(end)) ? end : -1;
  }

  private static boolean blank(char c) {
    return c == ' ' || c == '\t';
  }

  private static int runEnd(String text, int at, char marker) {
    int end = at;
    while (end < text.length() && text.charAt(end) == marker) {
      end++;
    }
    return end;
  }

  /** Ordered non-overlapping spans; an unclosed block runs to the end of the text. */
  static List<int[]> spans(String text) {
    var spans = new ArrayList<int[]>();
    int i = 0;
    while (i < text.length()) {
      int end = endAt(text, i);
      if (end >= 0) {
        spans.add(new int[] {i, end});
        i = end;
      } else {
        i++;
      }
    }
    return spans;
  }
}
