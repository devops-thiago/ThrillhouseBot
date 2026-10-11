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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The names a file's added lines declare — functions, methods, classes, types — for the summary
 * call's changed-files overview (#920).
 *
 * <p>The summary call does not see the diff. With only file names, counts and findings to go on, it
 * filled the gaps from the PR description and stated the description's claims as delivered
 * behaviour: "reminder e-mails 15 minutes before start" on a pull request whose mailer sends one
 * confirmation, a diagram node for an audit log nobody wrote. The names a file declares are cheap,
 * deterministic evidence of what it does — {@code notify_organizer} and no {@code send_reminder} —
 * so each overview row carries them, and the prompt asks for a behaviour to be described as
 * implemented only when this material shows it.
 *
 * <p>Recognised declarations, on added lines only: a name after {@code def}, {@code class}, {@code
 * function}, {@code fn}, {@code fun}, {@code func} (past a Go receiver), {@code interface}, {@code
 * struct}, {@code enum}, {@code trait}, {@code object}, {@code type} or {@code record}, past any
 * modifiers; and a C-family method signature (a visibility modifier, a return type, a name and an
 * opening parenthesis). A name this misses only leaves the row with less evidence; nothing reads
 * the list as complete. A name that is not the file's own is worse, so a line inside a multi-line
 * string or comment, or in a documentation or data file, declares nothing. A row also names the
 * existing functions its added lines sit inside ({@link #changedNames}), so behaviour added to a
 * function that already existed is not mistaken for behaviour nobody wrote.
 */
public final class ChangeSymbols {

  private ChangeSymbols() {}

  /**
   * Extensions of documentation and data files, whose code-shaped lines (a {@code def} in a README
   * example, a {@code class} in prose) are not the file's own declarations.
   */
  private static final Set<String> PROSE_EXTENSIONS =
      Set.of(
          "md",
          "markdown",
          "rst",
          "adoc",
          "txt",
          "html",
          "htm",
          "json",
          "yml",
          "yaml",
          "toml",
          "xml",
          "properties",
          "ini",
          "cfg",
          "conf",
          "csv",
          "lock",
          "svg");

  /** Whether {@code path} is documentation or data, whose lines declare nothing. */
  static boolean isProse(String path) {
    if (path == null) {
      return false;
    }
    var name = path.substring(path.lastIndexOf('/') + 1);
    var dot = name.lastIndexOf('.');
    return dot >= 0 && PROSE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /** Names one row lists, at most; a longer list ends with an ellipsis. */
  static final int MAX_NAMES = 10;

  /** Words that may precede a declaration and are skipped. */
  private static final Set<String> MODIFIERS =
      Set.of(
          "export",
          "pub",
          "public",
          "private",
          "protected",
          "internal",
          "abstract",
          "final",
          "static",
          "async",
          "data",
          "sealed",
          "open",
          "override",
          "suspend",
          "default");

  /** Keywords that declare the name after them. */
  private static final Set<String> DECLARING_KEYWORDS =
      Set.of(
          "def",
          "class",
          "function",
          "fn",
          "fun",
          "func",
          "interface",
          "struct",
          "enum",
          "trait",
          "object",
          "type",
          "record");

  /** Modifiers a C-family method signature opens with. */
  private static final Set<String> VISIBILITY =
      Set.of("public", "private", "protected", "internal");

  /**
   * The names {@code patch}'s added lines declare, in order and without repeats; empty for a null
   * or blank patch. A line inside a multi-line string or comment (a docstring's usage example, a
   * text block, a heredoc) declares nothing: {@link LiteralTracker} follows them across the hunk's
   * added and context lines.
   */
  public static List<String> declaredNames(String patch) {
    if (patch == null || patch.isBlank()) {
      return List.of();
    }
    var names = new LinkedHashSet<String>();
    var literals = new LiteralTracker();
    for (var line : patch.lines().toList()) {
      var added = isAddedLine(line);
      if (line.startsWith("@@")) {
        // Hunks are disjoint: what lies between them is unknown, so each starts in code.
        literals = new LiteralTracker();
      } else if (added || line.startsWith(" ")) {
        var code = line.substring(1);
        var inCode = literals.inCode();
        literals.advance(code);
        var name = added && inCode ? declaredName(code) : "";
        if (!name.isEmpty()) {
          names.add(name);
        }
      }
    }
    return List.copyOf(names);
  }

  /**
   * The names of the existing functions and types {@code patch} adds lines inside, read from the
   * hunk headers ({@code @@ -10,6 +10,9 @@ def send(self, to):}), in order and without repeats, and
   * leaving out a name the patch itself declares. Added lines inside a function that already
   * existed declare nothing, so without these a retry added to an existing {@code send} would leave
   * its row with no evidence of where the change sits.
   */
  public static List<String> changedNames(String patch) {
    if (patch == null || patch.isBlank()) {
      return List.of();
    }
    var names = new LinkedHashSet<String>();
    var header = "";
    for (var line : patch.lines().toList()) {
      if (line.startsWith("@@")) {
        var close = line.indexOf("@@", 2);
        header = close < 0 ? "" : declaredName(line.substring(close + 2));
      } else if (!header.isEmpty() && isAddedLine(line)) {
        names.add(header);
        header = "";
      }
    }
    declaredNames(patch).forEach(names::remove);
    return List.copyOf(names);
  }

  private static boolean isAddedLine(String line) {
    return line.startsWith("+") && !line.startsWith("+++");
  }

  /** The name one added line declares, or {@code ""}. */
  static String declaredName(String line) {
    var rest = line.strip();
    var word = leadingWord(rest);
    var visible = false;
    while (MODIFIERS.contains(word)) {
      visible |= VISIBILITY.contains(word);
      rest = rest.substring(word.length()).strip();
      word = leadingWord(rest);
    }
    if (DECLARING_KEYWORDS.contains(word)) {
      rest = rest.substring(word.length()).strip();
      // A Go method's receiver sits between the keyword and the name: func (s *Store) Add(…).
      if (rest.startsWith("(")) {
        rest = rest.substring(rest.indexOf(')') + 1).strip();
      }
      return leadingWord(rest);
    }
    return visible ? methodName(rest) : "";
  }

  /** The name of a C-family method signature after its modifiers: {@code void notify(…)}. */
  private static String methodName(String signature) {
    var paren = signature.indexOf('(');
    if (paren < 0) {
      return "";
    }
    var head = signature.substring(0, paren).strip();
    if (head.indexOf(' ') < 0 || head.indexOf('=') >= 0) {
      return "";
    }
    var name = head.substring(head.lastIndexOf(' ') + 1);
    return name.chars().allMatch(c -> isWordChar((char) c)) ? name : "";
  }

  /** The identifier {@code text} opens with, or {@code ""}. */
  private static String leadingWord(String text) {
    var end = 0;
    while (end < text.length() && isWordChar(text.charAt(end))) {
      end++;
    }
    return text.substring(0, end);
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /**
   * The suffix an overview row carries for the file at {@code path} with {@code patch}: {@code " —
   * declares: a, b"} for the names its added lines declare and {@code " — changes: c"} for the
   * existing ones its added lines sit inside ({@link #changedNames}), each cut at {@value
   * #MAX_NAMES} names, or {@code ""} when there are none or the file is documentation or data
   * ({@link #isProse}).
   */
  public static String overviewSuffix(String path, String patch) {
    if (isProse(path)) {
      return "";
    }
    return namesPart(" — declares: ", declaredNames(patch))
        + namesPart(" — changes: ", changedNames(patch));
  }

  private static String namesPart(String label, List<String> names) {
    if (names.isEmpty()) {
      return "";
    }
    var shown = names.subList(0, Math.min(MAX_NAMES, names.size()));
    return label + String.join(", ", shown) + (names.size() > MAX_NAMES ? ", …" : "");
  }

  /**
   * Follows multi-line strings and comments line by line, so a code-shaped line inside one is not
   * read as a declaration: a triple-quoted string (a Python docstring, a Kotlin raw string, a Java
   * text block), a backtick string, a block comment and a shell heredoc. Single-line strings are
   * skipped so a delimiter inside one opens nothing, and a line comment ends the line. What it
   * cannot place, like an unclosed quote, it leans to reading as not code: a name it misses only
   * leaves the row with less evidence, while a name read from a string invents one.
   */
  static final class LiteralTracker {

    /**
     * A heredoc opener ({@code <<EOF}, {@code <<-'END'}, {@code <<'py'}): a quoted word of any case
     * straight after the operator, a quoted upper-case word after a space, or an unquoted
     * upper-case word straight after it. A shift ({@code x << y}) and a stream insertion of a
     * string or character ({@code out << "x"}) open nothing.
     */
    private static final Pattern HEREDOC =
        Pattern.compile(
            "<<[-~]?(?:(['\"])([A-Za-z_]\\w*)\\1|\\s+(['\"])([A-Z_][A-Z0-9_]*)\\3"
                + "|([A-Z_][A-Z0-9_]*)(?!\\w))");

    /** The groups of {@link #HEREDOC} that hold the delimiter word, one per alternative. */
    private static final int[] HEREDOC_WORD_GROUPS = {2, 4, 5};

    /** What closes the string or comment the text so far ends inside; {@code null} in code. */
    private String closer;

    /** The word that ends the heredoc being read; {@code null} outside one. */
    private String heredocEnd;

    /** The word that ends a heredoc the current line opens; it starts on the next line. */
    private String openedHeredoc;

    boolean inCode() {
      return closer == null && heredocEnd == null;
    }

    /** Moves past one line of the new file's text. */
    void advance(String text) {
      if (heredocEnd != null) {
        if (text.strip().equals(heredocEnd)) {
          heredocEnd = null;
        }
        return;
      }
      openedHeredoc = null;
      var i = 0;
      while (i < text.length()) {
        i = closer == null ? pastCode(text, i) : pastCloser(text, i);
      }
      heredocEnd = openedHeredoc;
    }

    /** The index past the {@link #closer} at or after {@code i}, or the line's end. */
    private int pastCloser(String text, int i) {
      var end = text.indexOf(closer, i);
      if (end < 0) {
        return text.length();
      }
      var next = end + closer.length();
      closer = null;
      return next;
    }

    /**
     * The index past the code at {@code i}: an opened string or comment sets {@link #closer}, a
     * single-line string is skipped, a line comment ends the line, a heredoc is noted.
     */
    private int pastCode(String text, int i) {
      var c = text.charAt(i);
      if (text.startsWith("\"\"\"", i) || text.startsWith("'''", i)) {
        closer = text.substring(i, i + 3);
        return i + 3;
      }
      if (text.startsWith("/*", i)) {
        closer = "*/";
        return i + 2;
      }
      if (text.startsWith("//", i) || c == '#') {
        return text.length();
      }
      if (c == '`') {
        closer = "`";
        return i + 1;
      }
      if (c == '"' || c == '\'') {
        return endOfQuoted(text, i);
      }
      var heredoc = HEREDOC.matcher(text).region(i, text.length());
      if (!heredoc.lookingAt()) {
        return i + 1;
      }
      for (var group : HEREDOC_WORD_GROUPS) {
        if (heredoc.group(group) != null) {
          openedHeredoc = heredoc.group(group);
        }
      }
      return heredoc.end();
    }

    /** The index past the single-line string opening at {@code start}, or the line's end. */
    private static int endOfQuoted(String text, int start) {
      var quote = text.charAt(start);
      var i = start + 1;
      while (i < text.length() && text.charAt(i) != quote) {
        i += text.charAt(i) == '\\' ? 2 : 1;
      }
      return Math.min(i + 1, text.length());
    }
  }
}
