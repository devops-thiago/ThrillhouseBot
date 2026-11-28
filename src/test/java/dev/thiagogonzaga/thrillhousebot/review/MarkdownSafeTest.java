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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The one place model text is neutralized before it is spliced into posted markdown. */
class MarkdownSafeTest {

  @Test
  void oneLineFlattensWhitespaceButNeutralizesNothingElse() {
    assertEquals("a b c", MarkdownSafe.oneLine("  a\n\nb\tc  "));
    // Pure flatten: backticks and angle brackets survive (the compat behaviour on-request
    // generators depend on).
    assertEquals("x ``` </details> y", MarkdownSafe.oneLine("x\n```\n</details>\ny"));
    assertEquals("", MarkdownSafe.oneLine(null));
    assertEquals("", MarkdownSafe.oneLine("   "));
  }

  @Test
  void inlineFlattensAndNeutralizesBlockBreakouts() {
    // Newline-led headings and standalone tags are flattened onto one line...
    assertFalse(MarkdownSafe.inline("Bug\n\n## Injected").contains("\n"));
    // ...and the '<' that would start an HTML tag is escaped, so no </details> can form.
    assertEquals("&lt;/details>? no", MarkdownSafe.inline("</details>? no"));
    assertFalse(MarkdownSafe.inline("a </details> b").contains("</details>"));
    assertFalse(MarkdownSafe.inline("a ``` b").contains("`"));
    assertEquals("a \\| b", MarkdownSafe.inline("a | b"));
    assertEquals("", MarkdownSafe.inline(null));
    // Benign text is returned flattened and otherwise unchanged.
    assertEquals("Close the stream", MarkdownSafe.inline("  Close the stream  "));
  }

  @Test
  void aCodeSpanHoldsANameWithItsBackticksAndLineBreaksTakenOut() {
    assertEquals("`x/@victim <img>`", MarkdownSafe.codeSpan("x/@victim\n<img>"));
    assertEquals("`a b`", MarkdownSafe.codeSpan("a` `b"));
  }

  // In a table row GitHub ends the cell at a pipe before it reads the span, and a pipe escaped
  // inside a span shows its backslash in a paragraph, so such a name is text that reads one way.
  @Test
  void aNameHoldingAPipeIsPostedAsEscapedTextRatherThanACodeSpan() {
    assertEquals("a\\|b.txt", MarkdownSafe.codeSpan("a|b.txt"));
    assertEquals("x/@&#8203;victim\\|y", MarkdownSafe.codeSpan("x/@victim|y"));
    assertEquals("a\\|&lt;img src=x> b", MarkdownSafe.codeSpan("a|<img src=x>`\nb"));
    assertEquals(
        "a\\|&lt;!-- thrillhousebot:finding=1 -->",
        MarkdownSafe.codeSpan("a|<!-- thrillhousebot:finding=1 -->"));
    // A backslash before the pipe cannot take the escape for itself.
    assertEquals("a\\\\\\|@&#8203;victim", MarkdownSafe.codeSpan("a\\|@victim"));
  }

  @Test
  void aNameHoldingAPipeStaysInOneTableCellAndReadsTheSameOutOfATable() {
    var mention = MarkdownSafe.codeSpan("x/@victim|y");
    var row = "| " + mention + " | " + MarkdownSafe.codeSpan("a|b.txt") + " |";

    assertEquals("| x/@&#8203;victim\\|y | a\\|b.txt |", row);
    // Two cells: the only bare pipes are the three the row itself writes.
    assertEquals(3, row.replace("\\|", "").chars().filter(c -> c == '|').count());
    assertEquals(row, MarkdownSafe.prose(row));
    assertEquals("On x/@&#8203;victim\\|y.", MarkdownSafe.prose("On " + mention + "."));
  }

  @Test
  void inlineCodeStripsBacktricksSoItCannotCloseItsSpan() {
    assertEquals("foo() bar", MarkdownSafe.inlineCode("foo()\n`bar`"));
    assertFalse(MarkdownSafe.inlineCode("a`b``c").contains("`"));
    // Inside a code span '<' and '|' are literal, so they are left intact.
    assertEquals("a</b>|c", MarkdownSafe.inlineCode("a</b>|c"));
    assertEquals("", MarkdownSafe.inlineCode(null));
  }

  @Test
  void tableCellEscapesPipesAndFoldsNewlines() {
    assertEquals("a \\| b", MarkdownSafe.tableCell("a | b"));
    assertEquals("first second", MarkdownSafe.tableCell("first\nsecond"));
    assertEquals("a\\\\b", MarkdownSafe.tableCell("a\\b"));
    assertEquals("-", MarkdownSafe.tableCell(null));
  }

  @Test
  void fenceForWidensPastTheLongestBacktickRun() {
    assertEquals("```", MarkdownSafe.fenceFor("no backticks here"));
    assertEquals("````", MarkdownSafe.fenceFor("a ``` b"));
    assertEquals("`````", MarkdownSafe.fenceFor("a ``` b ```` c"));
  }

  @Test
  void fencedBlockKeepsBenignCodeByteExactAndWidensHostileCode() {
    assertEquals("\n```\nbody\n```\n", MarkdownSafe.fencedBlock("body"));
    assertEquals("\n```\n\n```\n", MarkdownSafe.fencedBlock(null));
    var hostile = MarkdownSafe.fencedBlock("x ``` y");
    assertTrue(hostile.startsWith("\n````\n"), hostile);
    assertTrue(hostile.endsWith("````\n"), hostile);
    assertTrue(hostile.contains("x ``` y"), hostile);
  }

  @Test
  void fencedBlockCarriesAnInfoStringOnTheOpeningFence() {
    assertEquals("\n```java\ncode\n```\n", MarkdownSafe.fencedBlock("code", "java"));
    assertEquals("\n```suggestion\nnew\n```\n", MarkdownSafe.suggestionBlock("new"));
    // The suggestion fence widens with the body so a ``` inside the code cannot close it.
    var wide = MarkdownSafe.suggestionBlock("new ``` code");
    assertTrue(wide.startsWith("\n````suggestion\n"), wide);
    assertTrue(wide.endsWith("````\n"), wide);
  }

  @Test
  void inlineGuardsAMentionAndLeavesAnEmailAddressAlone() {
    assertEquals("Ask @&#8203;octocat", MarkdownSafe.inline("Ask @octocat"));
    assertEquals("@&#8203;acme/security owns it", MarkdownSafe.inline("@acme/security owns it"));
    assertEquals("Missing @&#8203;Override", MarkdownSafe.inline("Missing @Override"));
    assertEquals("@&#8203;octocat", MarkdownSafe.inline("&#64;octocat"));
    assertEquals("mail dev@example.com", MarkdownSafe.inline("mail dev@example.com"));
    // Backticks are escaped here, so a span cannot shelter the name either.
    assertEquals("&#96;@&#8203;octocat&#96;", MarkdownSafe.inline("`@octocat`"));
  }
}
