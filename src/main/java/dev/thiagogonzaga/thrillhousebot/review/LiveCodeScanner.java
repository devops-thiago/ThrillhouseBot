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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Separates the code a revision runs from the text it merely quotes, one right-side diff line at a
 * time, so {@link RebuttalContradiction} can match dispatch constructs against live code only.
 *
 * <p>Quoted runs — string literals, character literals, comments — are blanked to spaces rather
 * than dropped: every offset after them is unchanged, so the caller's evidence quote still shows
 * the real shape of the line. A line comment is the one exception; it is cut, because nothing after
 * it is code. Literal delimiters are kept (a blanked string still reads as a string in the quote);
 * comment delimiters are not, because a comment is not part of the statement around it and leaving
 * {@code /*} in place would put a non-whitespace token inside an argument list.
 *
 * <h2>Why a state machine rather than more tells</h2>
 *
 * <p>This replaces a character walk that toggled quote state on any single {@code "}, {@code '} or
 * {@code `} and special-cased everything else. Each delimiter shape the walk did not model was a
 * defect, in one direction or the other, and every fix added a tell that interacted with the tells
 * already there (#647, #780, #651). The shapes left over — Java text blocks, C++ raw strings, block
 * comments, template-literal interpolations — are not more tells; they are more <em>states</em>, so
 * the honest fix is to name the states once and let a table of delimiters drive them.
 *
 * <p>The machine is deliberately one machine for every language in the corpus rather than a lexer
 * per language: the delimiter table is the union of the shapes the reviewed diffs actually carry.
 * That answers every shape whose delimiters are unambiguous across the corpus, and it cannot answer
 * the two that are genuinely ambiguous without knowing the language — Python's {@code //} floor
 * division, which reads as a comment start, and a Rust lifetime that pairs with a later apostrophe.
 * Both are left as they were, both fail by blanking rather than by matching, and both are recorded
 * in {@code RebuttalContradiction.rightSideCode} as the residue a language hint would close.
 *
 * <h2>Which way it errs</h2>
 *
 * <p>Every ambiguous decision here resolves toward <em>quoted</em>. Blanking live code costs a
 * contradiction the caller would otherwise have found, and the decline then stands — which is this
 * class's default outcome anyway. Matching quoted text costs an argument with a maintainer who was
 * right, which is the outcome the whole re-check exists to avoid. So an opener whose closer never
 * arrives blanks the rest of its hunk, and an unrecognised URL scheme reads as a comment.
 *
 * <p>The one place that resolves the other way is a template literal's {@code ${…}} interpolation,
 * which is live code inside a quoted run. Blanking it erased real dispatches (#651); reading it as
 * code costs an over-fire only for a Go raw string that quotes {@code ${…}} text <em>and</em> names
 * a dispatch construct inside those braces, which the corpus does not produce.
 *
 * <h2>How far state is carried</h2>
 *
 * <p>Only the delimiters that are designed to span lines carry across one — text blocks, raw
 * strings, template literals, block comments — and only from one diff body line to the next. The
 * caller resets the machine at every line that is not a hunk body line, so a stray delimiter cannot
 * silence anything past the hunk that holds it: the scan text is a whole multi-file patch rejoined,
 * where an omitted context region can easily hold the closer that never comes.
 */
final class LiveCodeScanner {

  /** A block comment opens here and runs, across lines, to {@link #BLOCK_COMMENT_CLOSE}. */
  private static final String BLOCK_COMMENT_OPEN = "/*";

  private static final String BLOCK_COMMENT_CLOSE = "*/";

  /**
   * How far past {@code R"} a C++ raw-string delimiter may run before the opener is read as an
   * ordinary quote instead. Real delimiters are a word or empty; the bound keeps a stray {@code R"}
   * from claiming the rest of the line.
   */
  private static final int RAW_DELIMITER_LIMIT = 16;

  /**
   * Openers with a fixed closing token, longest first so {@code """} is not read as {@code ""}
   * followed by a third quote. {@code spansLines} marks the ones a language lets cross a line
   * break; the rest must close on the line that opens them or they were not literals at all.
   *
   * <p>The escape column says where a backslash escapes the next character: inside {@code "} and
   * {@code '} literals, never inside a backtick one, which is a Go raw string and holds the
   * backslash literally. Honouring it there stepped over the closing backtick of a Windows path
   * ({@code `C:\`}) and let the real comment after it pose as live code (#647).
   */
  private static final List<Delimiter> DELIMITERS =
      List.of(
          // Java text block, Kotlin raw string, Python triple-quoted string.
          new Delimiter("\"\"\"", "\"\"\"", true, false, true),
          new Delimiter("'''", "'''", true, false, true),
          // Go raw string and JavaScript template literal share a delimiter; only the latter
          // interpolates, and reading ${…} as code is the direction that keeps real dispatches.
          new Delimiter("`", "`", false, true, true),
          new Delimiter("\"", "\"", true, false, false),
          new Delimiter("'", "'", true, false, false));

  /**
   * Schemes whose {@code ://} is a URL rather than a label followed by a comment. A closed list is
   * the only thing that separates {@code https://host} from {@code default://note}: both are
   * letters, a colon and two slashes, and the character before the colon — all the previous
   * carve-out looked at — is a letter in both. An unlisted scheme therefore reads as a comment and
   * the rest of the line is cut, which is the blanking direction and keeps the decline.
   */
  private static final Set<String> URL_SCHEMES =
      Set.of(
          "http",
          "https",
          "ftp",
          "ftps",
          "file",
          "ws",
          "wss",
          "git",
          "ssh",
          "svn",
          "jdbc",
          "s3",
          "gs",
          "redis",
          "mongodb",
          "postgres",
          "postgresql",
          "mysql",
          "amqp",
          "kafka",
          "ldap",
          "ldaps",
          "smb",
          "nfs",
          "docker",
          "oci",
          "hdfs",
          "classpath");

  /**
   * The regions still open at the end of the last line, innermost first. Empty means live code —
   * the state every line starts in once {@link #reset()} has run.
   */
  private final Deque<Region> open = new ArrayDeque<>();

  /** Forgets every open region, so the next line is scanned as live code from its first column. */
  void reset() {
    open.clear();
  }

  /**
   * {@code line} with its quoted runs blanked and any line comment cut, continuing whatever region
   * the previous line left open.
   */
  String scanLine(String line) {
    var out = new StringBuilder(line.length());
    var i = 0;
    while (i < line.length()) {
      var region = open.peek();
      if (region != null && !region.live) {
        i = blank(line, i, out, region);
        continue;
      }
      var next = scanLive(line, i, out, region);
      if (next < 0) {
        // A line comment: nothing after it is code, on this line or any other.
        return out.toString();
      }
      i = next;
    }
    return out.toString();
  }

  /**
   * One step inside a quoted region: its closer ends it, a backslash covers the character after it
   * where the region escapes, {@code ${} opens live code where the region interpolates, and
   * anything else is erased.
   */
  private int blank(String line, int i, StringBuilder out, Region region) {
    if (region.escapes && line.charAt(i) == '\\' && i + 1 < line.length()) {
      out.append("  ");
      return i + 2;
    }
    if (region.interpolates && line.startsWith("${", i)) {
      open.push(Region.interpolation());
      out.append("${");
      return i + 2;
    }
    if (line.startsWith(region.closer, i)) {
      open.pop();
      out.append(region.hideDelimiters ? " ".repeat(region.closer.length()) : region.closer);
      return i + region.closer.length();
    }
    out.append(' ');
    return i + 1;
  }

  /**
   * One step in live code — top level when {@code region} is null, otherwise inside a template
   * literal's interpolation. Returns the next index, or {@code -1} when a line comment starts here
   * and the rest of the line must be cut.
   *
   * <p>Comments and URLs are recognised at top level only. Inside an interpolation a {@code //} is
   * division or the tail of a URL far more often than a comment — a comment there would swallow the
   * closing brace — and cutting the line would strand the enclosing literal's closer, leaving it
   * open for the rest of the hunk.
   */
  private int scanLive(String line, int i, StringBuilder out, Region region) {
    var c = line.charAt(i);
    if (region != null) {
      if (c == '{') {
        region.braceDepth++;
      } else if (c == '}') {
        if (region.braceDepth == 0) {
          open.pop();
          out.append(c);
          return i + 1;
        }
        region.braceDepth--;
      }
    } else if (line.startsWith(BLOCK_COMMENT_OPEN, i)) {
      open.push(Region.blockComment());
      out.append("  ");
      return i + BLOCK_COMMENT_OPEN.length();
    } else if (isDoubleSlash(line, i)) {
      var url = urlTokenEnd(line, i);
      if (url < 0) {
        return -1;
      }
      out.append(line, i, url);
      return url;
    }
    var opened = openLiteral(line, i, out);
    if (opened > 0) {
      return opened;
    }
    out.append(c);
    return i + 1;
  }

  /**
   * Opens the literal starting at {@code i} and returns the index its body begins at, or {@code -1}
   * when nothing opens here.
   *
   * <p>A delimiter that cannot span lines and finds no closer on the line was not a delimiter: a
   * Rust lifetime, an apostrophe in prose, a stray quote in a log message. It is left as ordinary
   * text and the scan resumes one character later, so a later closed literal on the same line is
   * still stepped over whole (#647).
   */
  private int openLiteral(String line, int i, StringBuilder out) {
    var rawBody = rawStringBodyStart(line, i);
    if (rawBody > 0) {
      open.push(Region.quoted(")" + line.substring(i + 2, rawBody - 1) + "\"", false, false));
      out.append(line, i, rawBody);
      return rawBody;
    }
    for (var delimiter : DELIMITERS) {
      if (!line.startsWith(delimiter.open(), i)) {
        continue;
      }
      var body = i + delimiter.open().length();
      if (delimiter.spansLines() && closesAStatement(line, body)) {
        return -1;
      }
      if (!delimiter.spansLines()) {
        var close = closerIndex(line, body, delimiter.close());
        if (close < 0 || isMisreadApostrophePair(line, delimiter.open().charAt(0), i, close)) {
          return -1;
        }
      }
      open.push(Region.quoted(delimiter.close(), delimiter.escapes(), delimiter.interpolates()));
      out.append(delimiter.open());
      return body;
    }
    return -1;
  }

  /**
   * Whether the delimiter just scanned is a closer whose opener sits above this hunk, rather than
   * an opener. It is the one shape that tells them apart when a language spells both the same way:
   * a text block's opening delimiter must be the last thing on its line (JLS 3.10.6) and a template
   * literal's opener is followed by its body, so a delimiter with a statement terminator after it —
   * {@code """;}, {@code """,}, {@code """)}, {@code """.formatted(x)} — ends a literal that began
   * before the first line the diff shows.
   *
   * <p>Without this, a patch whose hunk starts inside a text block read that closer as an opener
   * and inverted every literal below it, blanking live code all the way to the next delimiter.
   * Measured over 80 commits of this repository's own history (32535 right-side Java lines): the
   * opener reading blanked 57 statement-shaped lines, among them live methods of {@code
   * ReviewResult}, because the hunks that touch this repository's code so often start under a text
   * block constant. The closer reading leaves 23, every one of them the body of a text block that
   * quotes a diff or a JSON payload — which is exactly the text that must be blanked.
   */
  private static boolean closesAStatement(String line, int after) {
    var i = after;
    while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
      i++;
    }
    return i < line.length() && ";,).+".indexOf(line.charAt(i)) >= 0;
  }

  /**
   * Index just past a C++ raw string's {@code R"delim(} opener at {@code at}, or {@code -1} when
   * this is not one. The delimiter is the text between the quote and the paren, and the literal
   * ends at {@code )delim"} — which is what lets its body carry an unescaped quote, the shape that
   * closed the old single-quote toggle early and truncated the line at the {@code //} after it.
   *
   * <p>An encoding prefix ({@code u8R"}, {@code LR"}) still opens one; an {@code R} that is merely
   * the last character of an identifier does not, and neither does a delimiter carrying a character
   * C++ does not allow in one. Both fall back to reading the quote as an ordinary string opener,
   * which is what the scan did before raw strings were modelled.
   */
  private static int rawStringBodyStart(String line, int at) {
    if (line.charAt(at) != 'R' || !line.startsWith("\"", at + 1) || !isPrefixBoundary(line, at)) {
      return -1;
    }
    var end = at + 2;
    var limit = Math.min(line.length(), end + RAW_DELIMITER_LIMIT);
    while (end < limit) {
      var c = line.charAt(end);
      if (c == '(') {
        return end + 1;
      }
      if (Character.isWhitespace(c) || "\")\\".indexOf(c) >= 0) {
        return -1;
      }
      end++;
    }
    return -1;
  }

  /** Whether the {@code R} at {@code at}, with any encoding prefix, starts a fresh token. */
  private static boolean isPrefixBoundary(String line, int at) {
    var start = at;
    while (start > 0 && at - start < 2 && "uUL8".indexOf(line.charAt(start - 1)) >= 0) {
      start--;
    }
    return start == 0 || !Character.isJavaIdentifierPart(line.charAt(start - 1));
  }

  /**
   * Index of {@code closer} at or after {@code from}, or {@code -1} when the line ends first. A
   * backslash covers the character after it, which is the rule for every delimiter that reaches
   * here: only the ones that must close on their own line do, and those are the {@code "} and
   * {@code '} literals. The delimiters that hold a backslash literally — a Go raw string, a C++ raw
   * string — span lines, so they are never looked ahead for and {@link #blank} steps over them
   * instead.
   */
  private static int closerIndex(String line, int from, String closer) {
    var i = from;
    while (i < line.length()) {
      if (line.charAt(i) == '\\') {
        i += 2;
      } else if (line.startsWith(closer, i)) {
        return i;
      } else {
        i++;
      }
    }
    return -1;
  }

  /**
   * Whether a {@code '}-quoted span is really two unrelated apostrophes with live code between
   * them, rather than a literal. A lone apostrophe in non-string context — a Rust lifetime ({@code
   * &'a ctx}, {@code foo::<'a>}) — followed later on the line by a real char literal ({@code '\n'})
   * or another lifetime pairs with that later apostrophe, and blanking the span would erase any
   * dispatch between them. Three tells mark a span as misread, each shaped so genuine quoted prose
   * (which may hold {@code .submit(} text — the very thing blanking exists to erase) keeps
   * blanking: the opener sits directly after {@code &} or {@code <}, which is Rust lifetime syntax
   * and never a string opener; the span holds a {@code ;}, which is statement shape, not prose; or
   * the pairing closer itself opens a char-literal-shaped span, meaning the "closer" was really the
   * next literal's opener.
   *
   * <p>This is the one classification the state machine cannot make structurally: whether {@code
   * 'a} opens a literal is a fact about the language, not about the delimiter. The tells are kept
   * as they were (#780) rather than grown, and the residue — two lifetimes with live code and no
   * semicolon between them — blanks that code, which keeps the decline. Double-quote and backtick
   * openers have no lifetime-style bare use, so the guard applies to {@code '} alone.
   */
  private static boolean isMisreadApostrophePair(String line, char quote, int open, int close) {
    if (quote != '\'') {
      return false;
    }
    // Lifetime position: an apostrophe directly after & or < ( &'a ctx, foo::<'a> ) is Rust
    // syntax, never a string opener, whatever its span holds.
    if (open > 0 && (line.charAt(open - 1) == '&' || line.charAt(open - 1) == '<')) {
      return true;
    }
    // Statement separator: a ; inside the span is code shape, not quoted prose.
    if (line.substring(open + 1, close).indexOf(';') >= 0) {
      return true;
    }
    // The pairing closer itself opens a char-literal-shaped span — '\n', ',', or a longer escape
    // like a Rust unicode char (backslash-u{1F600}): the "closer" was really the next literal's
    // opener, and everything between the two apostrophes is live code. Char-shaped means at most
    // two characters — one character or a simple escape — or an escape sequence: backslash-led
    // and short enough for the longest real spelling, Rust's backslash-u{10FFFF} at 9 characters.
    // Ten leaves a margin without reading a backslash-led prose span as a char.
    var closerAsOpener = closerIndex(line, close + 1, "'");
    if (closerAsOpener < 0) {
      return false;
    }
    var charSpan = line.substring(close + 1, closerAsOpener);
    return charSpan.length() <= 2 || (charSpan.length() <= 10 && charSpan.charAt(0) == '\\');
  }

  /**
   * Index just past the URL token whose {@code //} sits at {@code slashes}, or {@code -1} when the
   * slashes do not belong to a URL and start a comment instead.
   *
   * <p>The whole token is consumed, not just the scheme's slash pair: a path may hold a doubled
   * slash of its own ({@code http://host//v1}), and stopping at the scheme left the scan to read
   * that pair as a comment start and drop the rest of the line. The token ends at whitespace or at
   * a character that ends a URL in running code — a quote, a bracket, a statement separator — so
   * the code after it is still scanned.
   *
   * <p>The scheme is read back over letters and digits alone, so a compound scheme is recognised by
   * its last component ({@code git+ssh://host} matches on {@code ssh}). That errs toward reading a
   * URL, which keeps the line; requiring the whole {@code git+ssh} to be listed would cut it at the
   * slashes instead.
   */
  private static int urlTokenEnd(String line, int slashes) {
    if (slashes == 0 || line.charAt(slashes - 1) != ':') {
      return -1;
    }
    var schemeStart = slashes - 1;
    while (schemeStart > 0 && Character.isLetterOrDigit(line.charAt(schemeStart - 1))) {
      schemeStart--;
    }
    var scheme = line.substring(schemeStart, slashes - 1).toLowerCase(Locale.ROOT);
    if (!URL_SCHEMES.contains(scheme)) {
      return -1;
    }
    var end = slashes + 2;
    while (end < line.length() && !endsUrlToken(line.charAt(end))) {
      end++;
    }
    return end;
  }

  private static boolean endsUrlToken(char c) {
    return Character.isWhitespace(c) || "\"'`<>()[]{}\\,;".indexOf(c) >= 0;
  }

  /** Whether a doubled slash sits at {@code at}. */
  private static boolean isDoubleSlash(String line, int at) {
    return line.charAt(at) == '/' && at + 1 < line.length() && line.charAt(at + 1) == '/';
  }

  /**
   * An opener the scanner knows, its closing token, whether a backslash escapes inside it, whether
   * a {@code ${…}} inside it is live code, and whether it may stay open at the end of a line.
   */
  private record Delimiter(
      String open, String close, boolean escapes, boolean interpolates, boolean spansLines) {}

  /**
   * A region the scan is currently inside: a quoted run being blanked, or the live code of a
   * template literal's interpolation. Mutable because an interpolation counts the braces nested
   * inside it, which is what tells {@code ${fn({k: v})}} apart from the brace that closes it.
   */
  private static final class Region {

    private final String closer;
    private final boolean escapes;
    private final boolean interpolates;
    private final boolean live;
    private final boolean hideDelimiters;
    private int braceDepth;

    private Region(
        String closer,
        boolean escapes,
        boolean interpolates,
        boolean live,
        boolean hideDelimiters) {
      this.closer = closer;
      this.escapes = escapes;
      this.interpolates = interpolates;
      this.live = live;
      this.hideDelimiters = hideDelimiters;
    }

    static Region quoted(String closer, boolean escapes, boolean interpolates) {
      return new Region(closer, escapes, interpolates, false, false);
    }

    /** A comment is not part of the statement around it, so its delimiters are erased too. */
    static Region blockComment() {
      return new Region(BLOCK_COMMENT_CLOSE, false, false, false, true);
    }

    static Region interpolation() {
      return new Region("}", false, false, true, false);
    }
  }
}
