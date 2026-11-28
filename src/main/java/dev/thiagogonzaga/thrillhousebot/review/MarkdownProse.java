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
 *       than any backtick run inside it, with each content line as it was written;
 *   <li>a code span that opens and closes on one line and holds no {@code |} is kept as it was
 *       written. In a table row GitHub splits the cells at each {@code |} before it reads any span,
 *       so a span holding one is code in a paragraph and text in a row; it is written as text;
 *   <li>every other backtick becomes {@code &#96;} and every other run of three or more tildes is
 *       escaped, so nothing else can open a span or a fence.
 * </ul>
 *
 * <p>Line endings are the one thing code does not keep: a carriage return, alone or before a line
 * feed, ends a line ({@link #block}) and is written as a line feed, in a fenced block too. A span a
 * carriage return divides does not close on its line, so its backticks are escaped.
 *
 * <p>Outside code, an {@code @} that could start a mention is followed by a zero-width space
 * ({@code @&#8203;name}, invisible when rendered), and a {@code <} that could open a tag or a
 * comment becomes {@code &lt;}. An {@code @} directly after a letter or digit, as in an email
 * address, is not a mention and stays.
 *
 * <p>An {@code @} in the path of a web address stays too, since GitHub links the address and reads
 * no mention inside a link, and a guard there would break the address. What counts as an address is
 * kept narrower than what GitHub links ({@link #url}, {@link #angleAutolink}), so that text GitHub
 * does not link is never taken for one.
 */
final class MarkdownProse {

  /** What follows a neutralized {@code @}: a zero-width space, written as an entity. */
  static final String MENTION_GUARD = "&#8203;";

  /** An {@code @} written as a character reference, which renders as one and would notify too. */
  private static final Pattern AT_ENTITY = Pattern.compile("&(?:#0*64|#[xX]0*40|commat);");

  /** What a fence's info string may carry over: a language tag, nothing else. */
  private static final Pattern LANGUAGE_TAG = Pattern.compile("[A-Za-z0-9+#._-]{1,20}");

  /** A line ending GitHub reads besides {@code \n}: a carriage return, alone or before one. */
  private static final Pattern CARRIAGE_RETURN = Pattern.compile("\r\n?");

  private MarkdownProse() {}

  /** An open fenced block: its marker, and the nesting to take off each content line. */
  private record Fence(char marker, int length, int indent, int quotes, String language) {}

  /**
   * Multi-line markdown, rewritten as the class comment describes. Every line ending is read as
   * GitHub reads it: a carriage return ends a line too, so text after one is not taken for the rest
   * of the line before it.
   */
  static String block(String text) {
    var out = new ArrayList<String>();
    var content = new ArrayList<String>();
    Fence open = null;
    for (var line : CARRIAGE_RETURN.matcher(text).replaceAll("\n").split("\n", -1)) {
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
    int at = nestingEnd(line);
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

  /**
   * The end of the longest prefix of {@code line} read as nesting: indentation and quote markers,
   * one list marker followed by a blank, then more of the first.
   */
  private static int nestingEnd(String line) {
    int i = quotesEnd(line, 0);
    return quotesEnd(line, listMarkerEnd(line, i));
  }

  private static int quotesEnd(String line, int from) {
    return runOf(line, from, " \t>");
  }

  private static int blanksEnd(String line, int from) {
    return runOf(line, from, " \t");
  }

  /** The end of the run of characters of {@code set} that starts at {@code from}. */
  private static int runOf(String line, int from, String set) {
    int i = from;
    while (i < line.length() && set.indexOf(line.charAt(i)) >= 0) {
      i++;
    }
    return i;
  }

  /** The end of a bullet or numbered marker at {@code from} and its blanks, else {@code from}. */
  private static int listMarkerEnd(String line, int from) {
    int i = digitsEnd(line, from);
    int digits = i - from;
    // A number of up to nine digits takes "." or ")"; without a number, a bullet stands there.
    var markers = digits == 0 ? "-*+" : ".)";
    if (digits > 9 || i >= line.length() || markers.indexOf(line.charAt(i)) < 0) {
      return from;
    }
    int blanks = blanksEnd(line, i + 1);
    return blanks > i + 1 ? blanks : from;
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
    return line.substring(quotesEnd(line, 0));
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
    return line(text, false);
  }

  /**
   * {@link #line(String)} for the text of one table cell, whose every {@code |} the caller escapes
   * afterwards: no cell can end inside a code span then, so a span holding one is kept.
   */
  static String cell(String text) {
    return line(text, true);
  }

  private static String line(String text, boolean pipesEscaped) {
    var sb = new StringBuilder(text.length() + 16);
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\\' && i + 1 < text.length()) {
        i = escaped(text, i, sb);
      } else if (c == '`') {
        i = backticks(text, i, sb, pipesEscaped);
      } else if (c == '~') {
        i = tildes(text, i, sb);
      } else if (c == '<') {
        i = angle(text, i, sb);
      } else {
        i = plain(text, i, sb);
      }
    }
    return sb.toString();
  }

  /** A {@code <}: an address in angle brackets kept whole, a tag opener escaped, or itself. */
  private static int angle(String text, int i, StringBuilder sb) {
    int end = angleAutolink(text, i);
    if (end > 0) {
      sb.append(text, i, end);
      return end;
    }
    sb.append(opensTag(text, i) ? "&lt;" : "<");
    return i + 1;
  }

  /**
   * Text that is neither code nor markup: an address, an {@code @} in either spelling, or itself.
   */
  private static int plain(String text, int i, StringBuilder sb) {
    char c = text.charAt(i);
    if (c == '&') {
      return ampersand(text, i, sb);
    }
    if (c == '@') {
      at(text, i, i + 1, sb);
      return i + 1;
    }
    if (c == 'h' || c == 'H') {
      int end = url(text, i, sb);
      if (end > 0) {
        return end;
      }
    }
    sb.append(c);
    return i + 1;
  }

  /**
   * Writes the web address that starts at {@code i} and returns its end, or -1 when none starts
   * there. An {@code @} right after a {@code /} of its path is written as it is.
   *
   * <p>GitHub links more than this accepts, and that is the point: whatever this takes for an
   * address, GitHub links at least as far, so the {@code @} is inside the link. It must
   *
   * <ul>
   *   <li>start a line or follow a blank or a {@code (}, which is also where a markdown link's
   *       destination starts;
   *   <li>open with {@code http://} or {@code https://} and a host of letters, digits and hyphens
   *       with at least one dot, optionally a port, then a {@code /};
   *   <li>hold only letters, digits and {@code / . % + = ? # @ : -} from there on. It ends at the
   *       first other character: a blank, a {@code )}, a quote, and also {@code _ * ~ & $}, which
   *       emphasis, strikethrough, an entity or math could split the link at, leaving what follows
   *       as text.
   * </ul>
   *
   * A {@code www.} address without a scheme is not one, and neither is the text of a link.
   */
  private static int url(String text, int i, StringBuilder sb) {
    if (i > 0 && text.charAt(i - 1) != '(' && !Character.isWhitespace(text.charAt(i - 1))) {
      return -1;
    }
    int path = pathStart(text, i);
    if (path < 0) {
      return -1;
    }
    sb.append(text, i, path);
    int end = path;
    while (end < text.length() && pathCharacter(text.charAt(end), false)) {
      char c = text.charAt(end);
      if (c == '@' && text.charAt(end - 1) != '/') {
        at(text, end, end + 1, sb);
      } else {
        sb.append(c);
      }
      end++;
    }
    return end;
  }

  /**
   * The end of {@code <http://…>} or {@code <https://…>} opening at {@code i}, or -1. Markdown
   * reads the whole of it as one link and nothing inside it as markup, so it is kept as written; it
   * is not a tag, since no tag name holds a colon. The address has a host and a path as {@link
   * #url} asks, may also hold {@code _ ~ & , ; ! $ ' ( ) *}, and must close on the same line.
   */
  private static int angleAutolink(String text, int i) {
    int end = pathStart(text, i + 1);
    if (end < 0) {
      return -1;
    }
    while (end < text.length() && pathCharacter(text.charAt(end), true)) {
      end++;
    }
    return text.startsWith(">", end) ? end + 1 : -1;
  }

  /**
   * Where the path of the address at {@code i} starts, just after the {@code /} that follows its
   * host, or -1 when no {@code http(s)://host/} is there.
   */
  private static int pathStart(String text, int i) {
    int host = schemeEnd(text, i);
    if (host < 0) {
      return -1;
    }
    int end = hostEnd(text, host);
    if (end < 0) {
      return -1;
    }
    if (text.startsWith(":", end)) {
      end = digitsEnd(text, end + 1);
    }
    return text.startsWith("/", end) ? end + 1 : -1;
  }

  /** The end of {@code http://} or {@code https://} at {@code i}, in either case, or -1. */
  private static int schemeEnd(String text, int i) {
    if (!text.regionMatches(true, i, "http", 0, 4)) {
      return -1;
    }
    int at = text.regionMatches(true, i + 4, "s", 0, 1) ? i + 5 : i + 4;
    return text.startsWith("://", at) ? at + 3 : -1;
  }

  /**
   * The end of the host at {@code from}: two or more labels of letters, digits and hyphens with a
   * dot between each, or -1. A dot with no label on either side of it is no host.
   */
  private static int hostEnd(String text, int from) {
    int labels = 0;
    int end = from;
    boolean dot;
    do {
      int start = end;
      end = labelEnd(text, start);
      if (end == start) {
        return -1;
      }
      labels++;
      dot = text.startsWith(".", end);
      end += dot ? 1 : 0;
    } while (dot);
    return labels > 1 ? end : -1;
  }

  private static int labelEnd(String text, int from) {
    int end = from;
    while (end < text.length()
        && (asciiAlphanumeric(text.charAt(end)) || text.charAt(end) == '-')) {
      end++;
    }
    return end;
  }

  private static int digitsEnd(String text, int from) {
    int end = from;
    while (end < text.length() && asciiDigit(text.charAt(end))) {
      end++;
    }
    return end;
  }

  /**
   * Whether {@code c} may stand in the path of an address: a letter, a digit, punctuation no inline
   * markdown can split a link at, or, inside angle brackets where nothing can, a little more.
   */
  private static boolean pathCharacter(char c, boolean bracketed) {
    if (asciiAlphanumeric(c)) {
      return true;
    }
    return switch (c) {
      case '/', '.', '%', '+', '=', '?', '#', '@', ':', '-' -> true;
      case '_', '~', '&', ',', ';', '!', '$', '\'', '(', ')', '*' -> bracketed;
      default -> false;
    };
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

  /**
   * A backtick run: a code span when the same run closes it on this line, else literal. A span
   * holding a {@code |} is literal as well unless the pipes are escaped later ({@link #cell}): its
   * text is then read as prose like the rest of the line, and its backticks pair with no other.
   */
  private static int backticks(String text, int i, StringBuilder sb, boolean pipesEscaped) {
    int end = runEnd(text, i);
    int length = end - i;
    int close = closingRun(text, end, length);
    if (close >= 0 && (pipesEscaped || text.substring(end, close).indexOf('|') < 0)) {
      sb.append(text, i, close + length);
      return close + length;
    }
    sb.append("&#96;".repeat(length));
    return end;
  }

  /** Where the next run of exactly {@code length} backticks starts from {@code from} on, or -1. */
  private static int closingRun(String text, int from, int length) {
    int close = text.indexOf('`', from);
    while (close >= 0) {
      int closeEnd = runEnd(text, close);
      if (closeEnd - close == length) {
        return close;
      }
      close = text.indexOf('`', closeEnd);
    }
    return -1;
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

  /**
   * {@code text} with every possible mention guarded and nothing else changed. An address is read
   * as {@link #url} reads it; one in angle brackets is not kept, since the caller escapes the
   * bracket.
   */
  static String mentions(String text) {
    var sb = new StringBuilder(text.length() + 16);
    int i = 0;
    while (i < text.length()) {
      i = plain(text, i, sb);
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

  private static boolean asciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean asciiAlphanumeric(char c) {
    return asciiLetter(c) || asciiDigit(c);
  }
}
