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
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rewrites model-written markdown so that what GitHub renders from it cannot notify anyone, cannot
 * carry raw HTML, and cannot offer a committable suggestion, while its ordinary markdown and its
 * code still read the same. {@link MarkdownSafe} is the entry point.
 *
 * <p>Whether a character sits in code or in prose decides how it is treated, and a screen that
 * guesses that differently from GitHub leaves a way through: text it took for code is posted
 * untouched and rendered as prose. So the text is not only screened but rewritten into a form with
 * one reading:
 *
 * <ul>
 *   <li>a fenced block, wherever it was nested, is emitted as a top-level backtick fence longer
 *       than any backtick run inside it, with its content byte for byte;
 *   <li>a code span that opens and closes on one line is kept byte for byte;
 *   <li>every other backtick becomes {@code &#96;} and every other run of three or more tildes is
 *       escaped, so nothing else can open a span or a fence.
 * </ul>
 *
 * <p>Outside code, an {@code @} that could start a mention is followed by a zero-width space
 * ({@code @&#8203;name}, invisible when rendered), and a {@code <} that could open a tag or a
 * comment becomes {@code &lt;}. An {@code @} directly after a letter or digit, as in an email
 * address, is not a mention and stays.
 */
final class MarkdownProse {

  /** What follows a neutralized {@code @}: a zero-width space, written as an entity. */
  static final String MENTION_GUARD = "&#8203;";

  /** An {@code @} written as a character reference, which renders as one and would notify too. */
  private static final Pattern AT_ENTITY = Pattern.compile("&(?:#0*64|#[xX]0*40|commat);");

  /** What a fence's info string may carry over: a language tag, nothing else. */
  private static final Pattern LANGUAGE_TAG = Pattern.compile("[A-Za-z0-9+#._-]{1,20}");

  /** The longest line prefix read as nesting (indentation, quote and list markers). */
  private static final Pattern NESTING =
      Pattern.compile("^[ \\t]*(?:>[ \\t]*)*(?:(?:[-*+]|\\d{1,9}[.)])[ \\t]+)?(?:>[ \\t]*)*");

  private MarkdownProse() {}

  /** An open fenced block: its marker, and the nesting to take off each content line. */
  private record Fence(char marker, int length, int indent, int quotes, String language) {}

  /** Multi-line markdown, rewritten as the class comment describes. */
  static String block(String text) {
    var out = new ArrayList<String>();
    var content = new ArrayList<String>();
    Fence open = null;
    for (var line : text.split("\n", -1)) {
      if (open == null) {
        open = opened(line, out);
        if (open == null) {
          out.add(line(line));
        }
      } else if (closes(line, open)) {
        emit(out, content, open);
        open = null;
      } else {
        content.add(dedented(line, open));
      }
    }
    if (open != null) {
      // An unclosed block would swallow whatever the bot appends after this text.
      emit(out, content, open);
    }
    return String.join("\n", out);
  }

  /**
   * The fence {@code line} opens, or {@code null}. Text before the fence on the same line, a list
   * marker for instance, is emitted as a line of its own.
   */
  private static Fence opened(String line, List<String> out) {
    var nesting = NESTING.matcher(line);
    int at = nesting.find() ? nesting.end() : 0;
    if (at >= line.length() || (line.charAt(at) != '`' && line.charAt(at) != '~')) {
      return null;
    }
    char marker = line.charAt(at);
    int end = runEnd(line, at);
    var info = line.substring(end).strip();
    // A backtick fence cannot carry a backtick in its info string: that line is a code span.
    if (end - at < 3 || (marker == '`' && info.indexOf('`') >= 0)) {
      return null;
    }
    var prefix = line.substring(0, at);
    // A list marker the fence shared its line with keeps a line of its own; bare indentation and
    // quote markers have nothing left to hold.
    if (!prefix.replace(">", "").isBlank()) {
      out.add(line(prefix.stripTrailing()));
    }
    // Content lines sit at least as deep as the fence did: under its indentation, its quote
    // markers, and the width of a list marker it shared a line with.
    int quotes = (int) prefix.chars().filter(c -> c == '>').count();
    return new Fence(marker, end - at, at - quotes, quotes, language(info));
  }

  /**
   * The language tag of an info string, or none. {@code suggestion} is not a language: on a review
   * comment it makes the block a change a reader can commit with one click, which only the bot's
   * own suggestion block may be.
   */
  private static String language(String info) {
    var first = info.split("\\s", 2)[0];
    if (!LANGUAGE_TAG.matcher(first).matches()
        || first.toLowerCase(Locale.ROOT).startsWith("suggestion")) {
      return "";
    }
    return first;
  }

  private static boolean closes(String line, Fence open) {
    var rest = stripNesting(line);
    int end = runEnd(rest, 0);
    return !rest.isEmpty()
        && rest.charAt(0) == open.marker()
        && end >= open.length()
        && rest.substring(end).isBlank();
  }

  private static String stripNesting(String line) {
    int i = 0;
    while (i < line.length() && " \t>".indexOf(line.charAt(i)) >= 0) {
      i++;
    }
    return line.substring(i);
  }

  /** A content line without the quote markers and indentation its fence was opened under. */
  private static String dedented(String line, Fence open) {
    int i = 0;
    int indent = 0;
    int quotes = 0;
    while (i < line.length()) {
      char c = line.charAt(i);
      if ((c == ' ' || c == '\t') && indent < open.indent()) {
        indent++;
      } else if (c == '>' && quotes < open.quotes()) {
        quotes++;
      } else {
        break;
      }
      i++;
    }
    return line.substring(i);
  }

  private static void emit(List<String> out, List<String> content, Fence open) {
    var body = String.join("\n", content);
    var fence = MarkdownSafe.fenceFor(body);
    out.add(fence + open.language());
    out.addAll(content);
    out.add(fence);
    content.clear();
  }

  /** One line of prose: its one-line code spans kept, everything else neutralized. */
  static String line(String text) {
    var sb = new StringBuilder(text.length() + 16);
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\\' && i + 1 < text.length()) {
        i = escaped(text, i, sb);
      } else if (c == '`') {
        i = backticks(text, i, sb);
      } else if (c == '~') {
        i = tildes(text, i, sb);
      } else if (c == '<') {
        sb.append(opensTag(text, i) ? "&lt;" : "<");
        i++;
      } else if (c == '&') {
        i = ampersand(text, i, sb);
      } else if (c == '@') {
        at(text, i, i + 1, sb);
        i++;
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  /**
   * A backslash escape. An escaped backtick or {@code <} is written as its entity, so the result
   * does not depend on the backslash surviving a later escape pass; an escaped backslash is kept
   * whole, so the character after it is read on its own.
   */
  private static int escaped(String text, int i, StringBuilder sb) {
    char next = text.charAt(i + 1);
    if (next == '`') {
      sb.append("&#96;");
      return i + 2;
    }
    if (next == '<') {
      sb.append("&lt;");
      return i + 2;
    }
    if (next == '\\') {
      sb.append("\\\\");
      return i + 2;
    }
    sb.append('\\');
    return i + 1;
  }

  /** A backtick run: a code span when the same run closes it on this line, else literal. */
  private static int backticks(String text, int i, StringBuilder sb) {
    int end = runEnd(text, i);
    int length = end - i;
    int close = text.indexOf('`', end);
    while (close >= 0) {
      int closeEnd = runEnd(text, close);
      if (closeEnd - close == length) {
        sb.append(text, i, closeEnd);
        return closeEnd;
      }
      close = text.indexOf('`', closeEnd);
    }
    sb.append("&#96;".repeat(length));
    return end;
  }

  /** A run of three or more tildes could open a fence; shorter runs are strikethrough. */
  private static int tildes(String text, int i, StringBuilder sb) {
    int end = runEnd(text, i);
    sb.append((end - i >= 3 ? "\\~" : "~").repeat(end - i));
    return end;
  }

  /** Whether the {@code <} at {@code i} could open a tag, a comment or a declaration. */
  private static boolean opensTag(String text, int i) {
    if (i + 1 >= text.length()) {
      return false;
    }
    char next = text.charAt(i + 1);
    return asciiLetter(next) || next == '/' || next == '!' || next == '?';
  }

  /** An {@code @} written as a character reference is read as the {@code @} it renders as. */
  private static int ampersand(String text, int i, StringBuilder sb) {
    var entity = AT_ENTITY.matcher(text).region(i, text.length());
    if (entity.lookingAt()) {
      at(text, i, entity.end(), sb);
      return entity.end();
    }
    sb.append('&');
    return i + 1;
  }

  /**
   * Writes the {@code @} that starts at {@code i} and ends before {@code after}, guarded unless a
   * letter or digit comes directly before it (an email address, which GitHub does not read as a
   * mention) or the guard is already there.
   */
  private static void at(String text, int i, int after, StringBuilder sb) {
    sb.append('@');
    boolean afterWord = i > 0 && asciiAlphanumeric(text.charAt(i - 1));
    if (!afterWord && !text.startsWith(MENTION_GUARD, after)) {
      sb.append(MENTION_GUARD);
    }
  }

  /** {@code text} with every possible mention guarded and nothing else changed. */
  static String mentions(String text) {
    var sb = new StringBuilder(text.length() + 16);
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '&') {
        i = ampersand(text, i, sb);
      } else {
        if (c == '@') {
          at(text, i, i + 1, sb);
        } else {
          sb.append(c);
        }
        i++;
      }
    }
    return sb.toString();
  }

  private static int runEnd(String text, int at) {
    if (at >= text.length()) {
      return at;
    }
    char marker = text.charAt(at);
    int end = at;
    while (end < text.length() && text.charAt(end) == marker) {
      end++;
    }
    return end;
  }

  private static boolean asciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean asciiAlphanumeric(char c) {
    return asciiLetter(c) || (c >= '0' && c <= '9');
  }
}
