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
package dev.thiagogonzaga.thrillhousebot;

import java.util.regex.Pattern;

/**
 * The single place untrusted text is flattened before it is interpolated into a log line. Every
 * value a log statement splices in that the bot did not choose itself — a GitHub error body, a
 * model-supplied finding title or path — goes through here, so the "a field forges a record" class
 * of defect is fixed in one place rather than re-litigated at each new call site (#731, #740,
 * #742).
 *
 * <p>That first sentence is a claim about every call site in the code base, and five rounds found
 * it untrue — each one fixed the sites someone had enumerated, so the next site written was outside
 * the list by default. It is now checked rather than remembered: {@code LogSafeInvariantTest} reads
 * the untrusted accessor names off the AI response records themselves and fails on any log
 * statement in {@code src/main/java} that calls one of them in the call's own arguments, or that
 * logs a same-file local composed from such a call, without this class (#764). Both halves of that
 * check extend themselves — a field added to a response record is covered the moment it exists, and
 * a log line added anywhere in main is scanned the moment it is written.
 *
 * <p>Those two shapes are the check's whole reach, and the limit is worth knowing at this class
 * rather than only at the test: a value that arrived at the log line through a method parameter, a
 * field or a return value is not tracked, because the scan reads source text and has no call graph
 * to follow it back to the accessor. So wrap the value where the accessor is read, and it stays
 * inside what is checked; wrap it several frames later and only the convention below is holding it.
 *
 * <p>What the check covers is the model-supplied half, which is the half that can be derived: the
 * response records name their own fields. A GitHub error body has no such record to read, so that
 * half remains a convention this class states and its call sites keep ({@code GitHubApiError}
 * collapses the body and each diagnostic it appends).
 *
 * <p>This is the log-destined counterpart to {@code MarkdownSafe}, and the two are deliberately
 * separate. {@code MarkdownSafe.oneLine} feeds text the bot posts to GitHub, where the wider class
 * below has a real rendering cost; a log record is a diagnostic identity rather than a rendering
 * surface, and pays that cost gladly. Widening the markdown collapser instead would change what the
 * bot posts.
 */
public final class LogSafe {

  /**
   * Collapses the whitespace of an untrusted string so one value stays on one log line.
   *
   * <p>Wider than {@code \s}, which java.util.regex reads as the ASCII six ({@code [
   * \t\n\x0B\f\r]}) unless the pattern asks for Unicode character classes. CR and LF being
   * collapsed closes the classic forged-record vector, but NEL (U+0085), LINE SEPARATOR (U+2028),
   * PARAGRAPH SEPARATOR (U+2029), NUL and the ANSI escape all survived it (#731) — and a log
   * viewer, a terminal, or a JSON/ECS shipper may treat any of them as a record boundary or as a
   * screen-control sequence. A caller that reaches for this class has already decided its value is
   * attacker-influenced text on its way to a log file and is already paying for a collapse pass on
   * that basis; this is that pass covering what it claims to.
   *
   * <p>{@code \p{IsCc}} is the Unicode general category rather than POSIX {@code \p{Cntrl}}, so it
   * reaches the C1 controls (U+0080–U+009F, NEL among them) as well as C0 and DEL.
   *
   * <p>{@code \p{IsZs}} covers the space separators {@code \s} leaves behind — NBSP (U+00A0), the
   * EM/EN and figure spaces (U+2000–U+200A), the narrow NBSP (U+202F), the medium mathematical
   * space (U+205F) and the ideographic space (U+3000). Without it the class contradicted the
   * contract this method's javadoc states, and inconsistently: {@code String.strip()} reads {@code
   * Character.isWhitespace}, which is true for U+2003 and U+3000 but false for U+00A0, U+2007 and
   * U+202F, so the first pair were trimmed at the ends yet never collapsed inside, and the second
   * three survived at every position. They forge no boundary, which is why this is the cheapest of
   * the four classes to justify; they are here because two values differing only by one of them
   * render identically in a log, which is the same harm as the invisible characters below.
   *
   * <p>{@code \p{IsCf}} is here for the same harm rather than for line integrity: bidi overrides
   * and isolates (RLO, LRM, LRI) reorder what an operator reads, and the invisible joiners and
   * spaces (ZWJ, ZWNJ, ZWSP, the BOM, the soft hyphen) let two different values render identically
   * — both forge a record's meaning as surely as a forged boundary forges its extent. The accepted
   * cost is that an echoed user string loses its grapheme clusters: an emoji ZWJ sequence or an
   * Indic conjunct is split apart. A logged value is a diagnostic identity rather than a rendering
   * surface, and which characters arrived is the question it exists to answer. Replacing with a
   * space rather than deleting is part of the same bargain — deletion would let {@code
   * admin<ZWSP>istrator} close up into a different real word, a space cannot.
   */
  private static final Pattern WHITESPACE =
      Pattern.compile("[\\s\\p{IsZs}\\p{IsCc}\\p{IsCf}\\u2028\\u2029]+");

  private LogSafe() {}

  /**
   * An untrusted string flattened to one log-safe line: every run of whitespace, control and format
   * characters becomes a single space, and the ends are trimmed so a value that began or ended with
   * such a run does not leave a stray space in the line. A {@code null} value flattens to the empty
   * string, so a caller never has to guard for one.
   */
  public static String oneLine(String value) {
    if (value == null) {
      return "";
    }
    return WHITESPACE.matcher(value).replaceAll(" ").strip();
  }
}
