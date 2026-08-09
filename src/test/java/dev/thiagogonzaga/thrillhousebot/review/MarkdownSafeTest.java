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
}
