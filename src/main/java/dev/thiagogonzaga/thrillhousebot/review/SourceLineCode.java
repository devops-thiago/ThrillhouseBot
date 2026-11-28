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
 * The code of one source line, in any of the languages {@link ConfigKeyContextResolver} reads, with
 * what only names a call taken out. A comment-only line has none; a line comment cuts the rest of
 * the line and a block comment closed on the line is skipped. A string literal keeps only its
 * interpolations, which are code: {@code ${...}}, {@code #{...}} and {@code \(...)} in any literal,
 * and a bare {@code {...}} only in an {@code f} or {@code $} literal (Python, C#). A quote with no
 * closing quote on the line, such as a Rust lifetime {@code 'static}, is code too.
 *
 * <p>{@link #of} reads one line on its own. {@link #ofLines} reads consecutive lines and carries a
 * triple-quoted literal or a block comment from the line that opens it to the line that closes it,
 * so a docstring's inner lines are text. The lines may start or end inside one, as a hunk does.
 */
final class SourceLineCode {

  /**
   * A block comment's opener and closer. One left open on the line runs past its end, except {@code
   * {-}, which is also code (C's {@code {-1}}) when no {@code -}} follows.
   */
  private record BlockComment(String opener, String closer, boolean openToLineEnd) {}

  private static final List<BlockComment> BLOCK_COMMENTS =
      List.of(
          new BlockComment("/*", "*/", true),
          new BlockComment("<!--", "-->", true),
          new BlockComment("--[[", "]]", true),
          new BlockComment("{-", "-}", false));

  /** Characters of a string-literal prefix, such as Python's {@code rf} or C#'s {@code $@}. */
  private static final String LITERAL_PREFIX = "fFrRbBuU$@";

  /**
   * What a line leaves open past its end: the {@code closer} that ends it, whether it is a string
   * {@code literal} rather than a comment, and whether a bare {@code {...}} interpolates in it.
   */
  private record Open(String closer, boolean literal, boolean bareBraces) {}

  /**
   * The code of one line, what the line leaves open ({@code null} when nothing), and whether a
   * triple quote on it does not fit the part it was read in: one read as a closer with a word right
   * after it, as in {@code """Summary}, or one read as an opener right after a full stop, as in
   * {@code unset."""}.
   */
  private record Read(String code, Open open, boolean misfit) {}

  /**
   * Consecutive lines read from one starting state.
   *
   * @param codes the code of each line
   * @param end what the last line leaves open, {@code null} when nothing
   * @param openedAt the line that opened {@code end}, -1 when no line did
   * @param misfit whether a triple quote did not fit the part it was read in
   * @param firstQuote the triple quote of the first literal a line left open, {@code null} when
   *     none did
   */
  private record Reading(
      List<String> codes, Open end, int openedAt, boolean misfit, String firstQuote) {}

  private SourceLineCode() {}

  /** {@code line} without its comments and string-literal text. */
  static String of(String line) {
    return read(line).code();
  }

  /**
   * The code of each of {@code lines}, consecutive lines of one file. A triple-quoted literal or a
   * block comment opened on one line runs to the line that closes it: the lines between are text,
   * and only a literal's interpolations on them are code.
   *
   * <p>The lines may start inside a triple-quoted literal, as a hunk does, and then every quote
   * that reads as an opener is a closer and the text and the code change places. So the lines are
   * read both ways, as starting outside any literal and as starting inside one, and a quote that
   * cannot play its part rules a reading out: a closer has no word right after it, and an opener
   * does not follow a full stop. The reading left is taken, to the last line, so a docstring the
   * lines cut at either end is text. When both readings are ruled out every line is read on its
   * own, as {@link #of} reads it.
   *
   * <p>When no quote rules a reading out, the lines are taken to start outside if that leaves no
   * literal open after the last line, and are otherwise each read on their own: reading text as
   * code can only name a read that is not made, and reading code as text can miss one that is. Bare
   * quotes around code that sits between two docstrings are therefore still misread: the code
   * between them is taken for text.
   *
   * <p>A block comment left open after the last line may not be one ({@code build/*} in a shell
   * line), so the lines after the one that opens it are each read on their own. The lines before it
   * keep their reading.
   */
  static List<String> ofLines(List<String> lines) {
    var outside = reading(lines, null);
    if (outside.firstQuote() == null) {
      return withOpenCommentUnfollowed(outside, lines);
    }
    // The opening quote is not seen, so a bare brace is read as an interpolation: that can only
    // name a read that is not made.
    var inside = reading(lines, new Open(outside.firstQuote(), true, true));
    if (outside.misfit() != inside.misfit()) {
      return withOpenCommentUnfollowed(outside.misfit() ? inside : outside, lines);
    }
    var endsInLiteral = outside.end() != null && outside.end().literal();
    return outside.misfit() || endsInLiteral
        ? lines.stream().map(SourceLineCode::of).toList()
        : withOpenCommentUnfollowed(outside, lines);
  }

  /** Reads {@code lines} as starting inside {@code start}, or outside anything when null. */
  private static Reading reading(List<String> lines, Open start) {
    var codes = new ArrayList<String>(lines.size());
    var open = start;
    var openedAt = -1;
    var misfit = false;
    String firstQuote = null;
    for (var i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      var carried = open != null && !line.contains(open.closer());
      var read = open == null ? read(line) : resume(line, open);
      codes.add(read.code());
      misfit |= read.misfit();
      open = read.open();
      if (open != null && !carried) {
        openedAt = i;
        if (firstQuote == null && open.literal()) {
          firstQuote = open.closer();
        }
      }
    }
    return new Reading(codes, open, openedAt, misfit, firstQuote);
  }

  /**
   * The codes of {@code reading}, with the lines after one that leaves a block comment open each
   * read on their own.
   */
  private static List<String> withOpenCommentUnfollowed(Reading reading, List<String> lines) {
    if (reading.end() == null || reading.end().literal()) {
      return reading.codes();
    }
    var codes = new ArrayList<>(reading.codes());
    for (var i = reading.openedAt() + 1; i < lines.size(); i++) {
      codes.set(i, of(lines.get(i)));
    }
    return codes;
  }

  private static Read read(String line) {
    var first = firstNonBlank(line);
    if (first == line.length() || isCommentLine(line.substring(first))) {
      return new Read("", null, false);
    }
    var code = new StringBuilder(line.length());
    Open open = null;
    var misfit = false;
    var i = first;
    while (i < line.length()) {
      var next = next(line, first, i, code);
      if (next == line.length()) {
        open = leftOpenAt(line, i);
        misfit = open != null && open.literal() && i > 0 && line.charAt(i - 1) == '.';
      }
      i = next;
    }
    return new Read(code.toString(), open, misfit);
  }

  /**
   * Reads a line that starts inside {@code open}: up to the closer only a literal's interpolations
   * are code, and what follows the closer is read as a line of its own.
   */
  private static Read resume(String line, Open open) {
    var end = line.indexOf(open.closer());
    var textEnd = end < 0 ? line.length() : end;
    // The space stands for the character before the line, which no interpolation starts on.
    var inside =
        open.literal() ? interpolations(' ' + line, 1, textEnd + 1, open.bareBraces()) : "";
    if (end < 0) {
      return new Read(inside, open, false);
    }
    var after = end + open.closer().length();
    var rest = read(" ".repeat(after) + line.substring(after));
    var wordFollows =
        open.literal() && after < line.length() && Character.isLetterOrDigit(line.charAt(after));
    return new Read(inside + ' ' + rest.code(), rest.open(), wordFollows || rest.misfit());
  }

  /**
   * What the token at {@code i}, which runs to the line's end, leaves open: a block comment or a
   * triple-quoted literal with no closer on the line. {@code null} for any other token.
   */
  private static Open leftOpenAt(String line, int i) {
    for (var comment : BLOCK_COMMENTS) {
      if (comment.openToLineEnd()
          && line.startsWith(comment.opener(), i)
          && line.indexOf(comment.closer(), i + comment.opener().length()) < 0) {
        return new Open(comment.closer(), false, false);
      }
    }
    var delimiter = delimiterAt(line, i);
    var bareBraces = bareBracesInterpolate(line, i);
    return delimiter.length() == 3 && stringEnd(line, i, delimiter, bareBraces) < 0
        ? new Open(delimiter, true, bareBraces)
        : null;
  }

  /** Appends the token at {@code i} and returns where the next one starts. */
  private static int next(String line, int first, int i, StringBuilder code) {
    var blockEnd = blockCommentEnd(line, i);
    if (blockEnd >= 0) {
      code.append(' ');
      return blockEnd;
    }
    if (opensLineComment(line, first, i)) {
      return line.length();
    }
    return consume(line, i, code);
  }

  /**
   * Appends what starts at {@code i}, a literal's interpolations or one character of code, and
   * returns where the next token starts. A triple-quoted literal ({@code """} or {@code '''}) left
   * open runs past the line's end, so the rest of the line is its text.
   */
  private static int consume(String line, int i, StringBuilder code) {
    var delimiter = delimiterAt(line, i);
    var bareBraces = !delimiter.isEmpty() && bareBracesInterpolate(line, i);
    var end = delimiter.isEmpty() ? -1 : stringEnd(line, i, delimiter, bareBraces);
    if (end >= 0) {
      code.append(' ')
          .append(interpolations(line, i + delimiter.length(), end, bareBraces))
          .append(' ');
      return end + delimiter.length();
    }
    if (delimiter.length() == 3) {
      return line.length();
    }
    code.append(line.charAt(i));
    return i + 1;
  }

  /** The quote that opens a literal at {@code i}: three of {@code "} or {@code '}, or one quote. */
  private static String delimiterAt(String line, int i) {
    var c = line.charAt(i);
    if (!isQuote(c)) {
      return "";
    }
    var triple = String.valueOf(c).repeat(3);
    return c != '`' && line.startsWith(triple, i) ? triple : String.valueOf(c);
  }

  /** Whether the literal whose quote is at {@code quote} has an {@code f} or {@code $} prefix. */
  private static boolean bareBracesInterpolate(String line, int quote) {
    var start = quote;
    while (start > 0 && quote - start < 3 && LITERAL_PREFIX.indexOf(line.charAt(start - 1)) >= 0) {
      start--;
    }
    var prefix = line.substring(start, quote);
    return prefix.indexOf('f') >= 0 || prefix.indexOf('F') >= 0 || prefix.indexOf('$') >= 0;
  }

  private static int firstNonBlank(String line) {
    var i = 0;
    while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
      i++;
    }
    return i;
  }

  /**
   * A block comment's continuation ({@code * text}, so a pointer write such as {@code *p =
   * getenv("X")} is still code) or a {@code #} comment.
   */
  private static boolean isCommentLine(String trimmed) {
    return trimmed.startsWith("* ") || isHashComment(trimmed);
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
   * Where the block comment opening at {@code i} ends: past its closer, at the line's end when it
   * stays open, or -1 when none opens there.
   */
  private static int blockCommentEnd(String line, int i) {
    for (var comment : BLOCK_COMMENTS) {
      if (line.startsWith(comment.opener(), i)) {
        var closer = line.indexOf(comment.closer(), i + comment.opener().length());
        if (closer >= 0) {
          return closer + comment.closer().length();
        }
        if (comment.openToLineEnd()) {
          return line.length();
        }
      }
    }
    return -1;
  }

  /**
   * A line comment: {@code //} anywhere, a dash comment at the line's start or after whitespace,
   * and a {@code #} after code and whitespace ({@code #[} and {@code #{} are code, as are dashes
   * glued to a name).
   */
  private static boolean opensLineComment(String line, int first, int i) {
    if (line.startsWith("//", i)) {
      return true;
    }
    if (i > first && !Character.isWhitespace(line.charAt(i - 1))) {
      return false;
    }
    var hash =
        i > first
            && line.charAt(i) == '#'
            && !line.startsWith("#[", i)
            && !line.startsWith("#{", i);
    return hash || isDashComment(line, i);
  }

  /** A run of two or more dashes ending the line or followed by whitespace, as in {@code --- x}. */
  private static boolean isDashComment(String line, int i) {
    var end = i;
    while (end < line.length() && line.charAt(end) == '-') {
      end++;
    }
    return end - i >= 2 && (end == line.length() || Character.isWhitespace(line.charAt(end)));
  }

  private static boolean isQuote(char c) {
    return c == '"' || c == '\'' || c == '`';
  }

  /**
   * The index of the {@code delimiter} that closes the literal it opens at {@code start}, or -1
   * when the line has none. A quote inside an interpolation belongs to the interpolated code, not
   * to the literal.
   */
  private static int stringEnd(String line, int start, String delimiter, boolean bareBraces) {
    var closers = new StringBuilder();
    var i = start + delimiter.length();
    while (i < line.length()) {
      var c = line.charAt(i);
      if (c == '\\') {
        if (line.startsWith("\\(", i)) {
          closers.append(')');
        }
        i += 2;
      } else if (closers.isEmpty() && line.startsWith(delimiter, i)) {
        return i;
      } else {
        track(closers, c, opensInterpolation(bareBraces, line.charAt(i - 1)));
        i++;
      }
    }
    return -1;
  }

  /** Whether a {@code {}} after {@code previous} opens an interpolation. */
  private static boolean opensInterpolation(boolean bareBraces, char previous) {
    return bareBraces || previous == '$' || previous == '#';
  }

  /**
   * Opens or closes a bracket. Outside an interpolation only an interpolating brace opens one:
   * other brackets in a literal are text.
   */
  private static void track(StringBuilder closers, char c, boolean braceOpens) {
    var depth = closers.length();
    if (depth > 0 && c == closers.charAt(depth - 1)) {
      closers.setLength(depth - 1);
    } else if (depth > 0 ? "{([".indexOf(c) >= 0 : c == '{' && braceOpens) {
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
  private static String interpolations(String line, int from, int to, boolean bareBraces) {
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
        track(closers, c, opensInterpolation(bareBraces, line.charAt(i - 1)));
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
