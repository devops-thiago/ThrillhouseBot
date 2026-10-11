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
 * the list as complete.
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
   * or blank patch.
   */
  public static List<String> declaredNames(String patch) {
    if (patch == null || patch.isBlank()) {
      return List.of();
    }
    var names = new LinkedHashSet<String>();
    patch
        .lines()
        .filter(line -> line.startsWith("+") && !line.startsWith("+++"))
        .map(line -> declaredName(line.substring(1)))
        .filter(name -> !name.isEmpty())
        .forEach(names::add);
    return List.copyOf(names);
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
   * declares: a, b, c"}, cut at {@value #MAX_NAMES} names, or {@code ""} when it declares none or
   * is documentation or data ({@link #isProse}).
   */
  public static String overviewSuffix(String path, String patch) {
    if (isProse(path)) {
      return "";
    }
    var names = declaredNames(patch);
    if (names.isEmpty()) {
      return "";
    }
    var shown = names.subList(0, Math.min(MAX_NAMES, names.size()));
    return " — declares: " + String.join(", ", shown) + (names.size() > MAX_NAMES ? ", …" : "");
  }
}
