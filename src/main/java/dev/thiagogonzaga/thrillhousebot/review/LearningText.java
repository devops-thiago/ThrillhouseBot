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
import java.util.regex.Pattern;

/**
 * Shapes maintainer prose into the text a {@link ReviewLearning} stores, and decides whether it may
 * be stored at all (#38).
 *
 * <p>A learning is replayed into every later review of its repository, so two properties matter
 * more here than anywhere a comment is only read once. It must never carry a credential: a text
 * with anything credential-shaped in it is refused outright rather than masked, because a masked
 * learning is a half-sentence nobody meant, and the maintainer can restate it without the value.
 * And it must stay one bounded line of data: quoted material (the bot's own finding, quoted back in
 * a reply) is dropped, control and format characters that could reorder or hide text are removed,
 * whitespace collapses, and the result is clipped.
 */
final class LearningText {

  /**
   * Credential shapes, one small pattern each so every one stays readable and inside the regex
   * complexity budget. The sigils are distinctive enough that prose never carries them; the
   * assignment shape needs eight value characters so "password: yes" in an explanation is not a
   * secret.
   */
  /**
   * An assigned value that looks like a secret rather than a word: eight or more characters with at
   * least one that is not a letter, so "password: required" in an explanation is not refused.
   */
  private static final String SECRET_VALUE = "(?=[^\\s'\"]*[^\\sA-Za-z'\"])[^\\s'\"]{8,}";

  private static final List<Pattern> CREDENTIAL_SHAPES =
      List.of(
          Pattern.compile("(?i)\\b(?:gh[pousr]_|github_pat_)\\w{4,}"),
          Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
          Pattern.compile("-----BEGIN [A-Z ]{0,40}PRIVATE KEY-----"),
          Pattern.compile("(?<![\\w-])eyJ[\\w-]{8,}\\.[\\w-]{4,}"),
          Pattern.compile("\\bxox[abposr]-[\\w-]{8,}"),
          Pattern.compile("\\bsk-(?:ant-|proj-)?[\\w-]{16,}"),
          Pattern.compile("\\bAIza[\\w-]{30,}"),
          // A token, unlike the word after "bearer" in prose, carries a digit.
          Pattern.compile("(?i)\\bbearer\\s+(?=[\\w.~+/=-]*\\d)[\\w.~+/=-]{12,}"),
          Pattern.compile(
              "(?i)\\b(?:password|passwd|secret)\\s{0,4}[:=]\\s{0,4}['\"]?" + SECRET_VALUE),
          Pattern.compile(
              "(?i)\\b(?:api|access|client)[_-]?(?:key|token|secret)\\s{0,4}[:=]\\s{0,4}['\"]?"
                  + SECRET_VALUE));

  private static final Pattern BLOCKQUOTE_LINE = Pattern.compile("(?m)^[ \\t]{0,8}>.*$");
  private static final Pattern ANSI_CSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");

  /** Control and format characters (bidi overrides, zero-width joiners), plus any whitespace. */
  private static final Pattern WHITESPACE_AND_INVISIBLES =
      Pattern.compile("[\\s\\p{IsZs}\\p{IsCc}\\p{IsCf}\\u2028\\u2029]+");

  private LearningText() {}

  /** Whether {@code text} carries anything credential-shaped; such a text is never stored. */
  static boolean containsCredential(String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }
    for (var shape : CREDENTIAL_SHAPES) {
      if (shape.matcher(text).find()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Maintainer prose reduced to one clean line of at most {@code maxChars}: quoted lines dropped,
   * code kept (it is often the point) but flattened, invisibles removed, whitespace collapsed.
   * Empty when nothing of the maintainer's own is left.
   */
  static String normalize(String text, int maxChars) {
    if (text == null || text.isBlank()) {
      return "";
    }
    var withoutQuotes = BLOCKQUOTE_LINE.matcher(text).replaceAll(" ");
    var withoutAnsi = ANSI_CSI.matcher(withoutQuotes).replaceAll(" ");
    var flat = WHITESPACE_AND_INVISIBLES.matcher(withoutAnsi).replaceAll(" ").strip();
    return clip(flat, maxChars);
  }

  /**
   * One line for a value the bot did not write but did not choose to keep whole either — a title or
   * a path — with no quote stripping, since neither carries a quoted reply.
   */
  static String oneLine(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    var withoutAnsi = ANSI_CSI.matcher(text).replaceAll(" ");
    return clip(WHITESPACE_AND_INVISIBLES.matcher(withoutAnsi).replaceAll(" ").strip(), maxChars);
  }

  /** {@code text} cut to {@code maxChars}, the cut marked with an ellipsis. */
  static String clip(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return text;
    }
    var end = Math.max(0, maxChars - 1);
    if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end).stripTrailing() + "…";
  }
}
