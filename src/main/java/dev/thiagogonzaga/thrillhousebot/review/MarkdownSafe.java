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

import java.util.regex.Pattern;

/**
 * The single place model-supplied text is neutralized before it is spliced into markdown the bot
 * posts to GitHub. Every render site that concatenates a model string into a comment, summary, or
 * suggestion must route it through one of these operations, so the "a model field breaks out of the
 * enclosing block" class of defect (a fence that closes early, a {@code </details>} that ends a
 * collapsible, a newline that starts a heading, a pipe that splits a table row) is fixed in one
 * place rather than re-litigated at each new call site.
 *
 * <p>Two core operations:
 *
 * <ul>
 *   <li><b>fenced code</b> — {@link #fencedBlock(String)} / {@link #suggestionBlock(String)} widen
 *       the fence past the longest backtick run in the content, so a {@code ```} inside model code
 *       cannot close the block early and escape the surrounding container.
 *   <li><b>inline</b> — {@link #inline(String)} flattens a model string to a single line and
 *       neutralizes the fence / {@code <details>} / HTML / pipe / backtick break-outs; {@link
 *       #inlineCode(String)} is the variant for a string placed inside a {@code `...`} span, and
 *       {@link #tableCell(String)} the variant for one markdown table cell.
 *   <li><b>prose</b> — {@link #prose(String)} for a model field that is markdown of its own (a
 *       finding's description, a status note, a reply) and {@link #proseLine(String)} for one
 *       flattened to a line: the markdown is kept, and what must not reach a reader is taken out.
 * </ul>
 *
 * <p>Every operation except the code ones also keeps the text from notifying anyone: an {@code @}
 * that could start a mention of a user or a team is followed by a zero-width space
 * ({@code @&#8203;name}), which renders the same and links nobody. Text the bot writes itself, such
 * as the {@code @thrillhousebot resolved} hints, does not pass through here and is posted as
 * written.
 */
public final class MarkdownSafe {

  /** Any whitespace run, including the line breaks that would restructure a rendered comment. */
  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

  private MarkdownSafe() {}

  /**
   * A model string flattened to a single line — every whitespace run, including newlines, becomes a
   * single space — with no other transformation. This is the primitive the other inline operations
   * build on; prefer {@link #inline(String)} unless a caller specifically needs the raw flatten
   * (backticks and angle brackets left intact). A {@code null} value flattens to the empty string.
   */
  public static String oneLine(String value) {
    if (value == null) {
      return "";
    }
    return WHITESPACE_RUN.matcher(value.strip()).replaceAll(" ");
  }

  /**
   * A model string made safe to splice into block-level markdown prose (a bullet, a bold heading):
   * flattened to one line, then the characters that could break out of the enclosing block are
   * neutralized — {@code <} is escaped so no HTML tag such as {@code </details>} can form,
   * backticks are escaped so a {@code ```} cannot open a code span, and {@code |} is escaped so it
   * cannot split a table row. Text with none of these is returned flattened and otherwise
   * unchanged.
   */
  public static String inline(String value) {
    return MarkdownProse.mentions(oneLine(value))
        .replace("<", "&lt;")
        .replace("`", "&#96;")
        .replace("|", "\\|");
  }

  /**
   * A model field that is markdown in its own right, made safe to post as a block of its own:
   * emphasis, lists, links, code spans and fenced code are kept, and three things are taken out. An
   * {@code @} that could mention a user or a team is guarded; a {@code <} that could open an HTML
   * tag or comment (a {@code </details>}, a hidden marker of the bot's own) is escaped; and a
   * fenced block marked {@code suggestion} becomes a plain one, since on a review comment GitHub
   * offers such a block as a change to commit and only {@link #suggestionBlock} may post one. A
   * fenced block left open is closed, so it cannot take in what the bot appends. A {@code null}
   * value is the empty string.
   *
   * <p>Code is left as written, in spans that close on their own line and in fenced blocks, but for
   * its line endings: a carriage return ends a line as a line feed does and is posted as one. A
   * span holding a {@code |} is the exception: GitHub reads it as text in a table row, so it is
   * posted as text. See {@link MarkdownProse} for why the text is rewritten rather than only
   * screened.
   */
  public static String prose(String value) {
    return value == null ? "" : MarkdownProse.block(value);
  }

  /** {@link #prose(String)} for a field rendered on one line, such as a list bullet. */
  public static String proseLine(String value) {
    return MarkdownProse.line(oneLine(value));
  }

  /**
   * One line of {@code text} as it read before {@link #proseLine} guarded and escaped it, for text
   * the bot reads back from its own comments and compares with what a model wrote: the mention
   * guard, the escaped {@code <} and backtick, and the escaped run of three or more tildes are
   * undone. It is not the reverse of {@link #prose} for a block, whose fences are rewritten, nor of
   * {@link #inline}, whose escaped {@code |} stays.
   */
  public static String unguarded(String text) {
    var unescaped =
        text.replace("@" + MarkdownProse.MENTION_GUARD, "@")
            .replace("@\u200B", "@")
            .replace("&lt;", "<")
            .replace("&#96;", "`");
    return ESCAPED_TILDE_RUN
        .matcher(unescaped)
        .replaceAll(run -> "~".repeat(run.end() - run.start() >> 1));
  }

  /** Three or more tildes as {@link MarkdownProse} escapes them: a backslash before each. */
  private static final Pattern ESCAPED_TILDE_RUN = Pattern.compile("(?:\\\\~){3,}+");

  /**
   * A model string made safe to place inside a {@code `...`} inline code span: flattened to one
   * line with its backticks removed, so a backtick in the value cannot close the span and let the
   * rest inject markdown. Inside a code span {@code <} and {@code |} are literal, so they are left
   * as-is.
   */
  public static String inlineCode(String value) {
    return oneLine(value).replace("`", "");
  }

  /**
   * A name the bot did not write (a changed file's path, say) as a code span of its own: {@link
   * #inlineCode} between backticks, so nothing in it is read as a mention, a tag or a link.
   */
  public static String codeSpan(String value) {
    return "`" + inlineCode(value) + "`";
  }

  /**
   * A model string made safe for a single Markdown table cell: a literal {@code |} would break the
   * column and a line break would break the whole row, so the pipe (and the backslash that would
   * otherwise consume the escape) are escaped and any run of line breaks is folded into one space.
   * A {@code null} value renders the empty-cell placeholder.
   */
  public static String tableCell(String value) {
    if (value == null) {
      return "-";
    }
    return value.replace("\\", "\\\\").replace("|", "\\|").replaceAll("[\r\n]+", " ");
  }

  /**
   * {@link #tableCell(String)} for a cell that holds prose rather than a path inside a code span (a
   * file's summary, a check's name): the same escaping, with the cell's mentions guarded and its
   * HTML escaped as {@link #proseLine} does, and its code spans kept.
   */
  public static String proseCell(String value) {
    if (value == null) {
      return "-";
    }
    return MarkdownProse.cell(value.replaceAll("[\r\n]+", " "))
        .replace("\\", "\\\\")
        .replace("|", "\\|");
  }

  /**
   * A code fence at least one backtick longer than the longest backtick run in {@code content}
   * (never shorter than the usual three), so fenced content inside the block cannot close it early.
   */
  public static String fenceFor(String content) {
    int longest = 0;
    int run = 0;
    for (int i = 0; i < content.length(); i++) {
      run = content.charAt(i) == '`' ? run + 1 : 0;
      longest = Math.max(longest, run);
    }
    return "`".repeat(Math.max(3, longest + 1));
  }

  /**
   * A plain (non-committable) fenced code block whose fence widens past any backtick run in the
   * model body, so a {@code ```} the model emitted cannot close the block early and let its content
   * escape the enclosing container. A {@code null} body renders an empty block. Backtick-free
   * bodies keep the usual three-backtick fence.
   */
  public static String fencedBlock(String code) {
    return fencedBlock(code, "");
  }

  /**
   * A fenced code block carrying an info string on its opening fence (a validated language tag, or
   * {@code "suggestion"} for a committable block). The info string is trusted by the caller — it is
   * never model prose — and shares the widened fence with the body.
   */
  public static String fencedBlock(String code, String infoString) {
    var body = code == null ? "" : code.stripTrailing();
    var fence = fenceFor(body);
    return "\n" + fence + infoString + "\n" + body + "\n" + fence + "\n";
  }

  /**
   * A committable GitHub {@code suggestion} block with the same widening fence. Backtick-free code
   * keeps the exact three-backtick {@code ```suggestion} GitHub renders as one-click-appliable.
   */
  public static String suggestionBlock(String code) {
    return fencedBlock(code, "suggestion");
  }
}
