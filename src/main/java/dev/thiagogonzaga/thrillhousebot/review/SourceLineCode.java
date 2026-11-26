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

import java.util.List;

/**
 * The code of one source line, in any of the languages {@link ConfigKeyContextResolver} reads, with
 * what only names a call taken out: a comment-only line has none, a trailing comment is cut, and a
 * string literal keeps only its interpolations ({@code ${...}}, {@code #{...}}, {@code {...}},
 * {@code \(...)}), which are code. A quote with no closing quote on the line, such as a Rust
 * lifetime {@code 'static}, is code too.
 */
final class SourceLineCode {

  /**
   * Comment openers at a line's start. A {@code *} counts only as a block comment's continuation
   * ({@code * text}), so a pointer write such as {@code *p = getenv("X")} is still code.
   */
  private static final List<String> COMMENT_OPENERS = List.of("//", "/*", "--", "<!--", "* ");

  private SourceLineCode() {}

  /** {@code line} without its comments and string-literal text. */
  static String of(String line) {
    var first = firstNonBlank(line);
    if (first == line.length() || isCommentLine(line.substring(first))) {
      return "";
    }
    var code = new StringBuilder(line.length());
    var i = first;
    while (i < line.length() && !(i > first && opensTrailingComment(line, i))) {
      var end = isQuote(line.charAt(i)) ? stringEnd(line, i) : -1;
      if (end < 0) {
        code.append(line.charAt(i));
        i++;
      } else {
        code.append(' ').append(interpolations(line, i + 1, end)).append(' ');
        i = end + 1;
      }
    }
    return code.toString();
  }

  private static int firstNonBlank(String line) {
    var i = 0;
    while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean isCommentLine(String trimmed) {
    return COMMENT_OPENERS.stream().anyMatch(trimmed::startsWith) || isHashComment(trimmed);
  }

  /**
   * A {@code #} comment (Python, Ruby, shell, YAML), but not a C preprocessor line such as {@code
   * #define X getenv("X")} or a Rust attribute {@code #[...]}, which are code.
   */
  private static boolean isHashComment(String trimmed) {
    return trimmed.startsWith("#")
        && !trimmed.startsWith("#[")
        && !trimmed.matches("#\\s*(define|if|ifdef|ifndef|elif|else|endif|include|undef)\\b.*");
  }

  /**
   * A comment after code: {@code //}, {@code /*} or {@code <!--} anywhere, and a {@code #} or
   * {@code --} that follows whitespace ({@code #[} and {@code #{} are code, as is {@code --} glued
   * to a name).
   */
  private static boolean opensTrailingComment(String line, int i) {
    if (line.startsWith("//", i) || line.startsWith("/*", i) || line.startsWith("<!--", i)) {
      return true;
    }
    if (!Character.isWhitespace(line.charAt(i - 1))) {
      return false;
    }
    var hash = line.charAt(i) == '#' && !line.startsWith("#[", i) && !line.startsWith("#{", i);
    var dashes =
        line.startsWith("--", i)
            && (i + 2 == line.length() || Character.isWhitespace(line.charAt(i + 2)));
    return hash || dashes;
  }

  private static boolean isQuote(char c) {
    return c == '"' || c == '\'' || c == '`';
  }

  /**
   * The index of the quote that closes the literal opened at {@code start}, or -1 when the line has
   * none. A quote inside an interpolation belongs to the interpolated code, not to the literal.
   */
  private static int stringEnd(String line, int start) {
    var quote = line.charAt(start);
    var closers = new StringBuilder();
    var i = start + 1;
    while (i < line.length()) {
      var c = line.charAt(i);
      if (c == '\\') {
        if (line.startsWith("\\(", i)) {
          closers.append(')');
        }
        i += 2;
      } else if (closers.isEmpty() && c == quote) {
        return i;
      } else {
        track(closers, c);
        i++;
      }
    }
    return -1;
  }

  /**
   * Opens or closes a bracket. Outside an interpolation only a brace opens one: other brackets in a
   * literal are text.
   */
  private static void track(StringBuilder closers, char c) {
    var depth = closers.length();
    if (depth > 0 && c == closers.charAt(depth - 1)) {
      closers.setLength(depth - 1);
    } else if (c == '{' || (depth > 0 && (c == '(' || c == '['))) {
      closers.append(closerOf(c));
    }
  }

  private static char closerOf(char opener) {
    return switch (opener) {
      case '{' -> '}';
      case '(' -> ')';
      default -> ']';
    };
  }

  /** The code inside the literal {@code line[from, to)}: its interpolations, space-separated. */
  private static String interpolations(String line, int from, int to) {
    var code = new StringBuilder();
    var closers = new StringBuilder();
    var i = from;
    while (i < to) {
      var c = line.charAt(i);
      var opensSwift = c == '\\' && line.startsWith("\\(", i);
      if (opensSwift) {
        closers.append(')');
        i++;
      } else if (c == '\\') {
        i++;
      } else {
        var wasInside = !closers.isEmpty();
        track(closers, c);
        if (wasInside && !closers.isEmpty()) {
          code.append(c);
        } else if (wasInside) {
          code.append(' ');
        }
      }
      i++;
    }
    return code.toString();
  }
}
