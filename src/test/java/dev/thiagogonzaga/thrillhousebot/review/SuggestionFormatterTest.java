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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SuggestionFormatterTest {

  private SuggestionFormatter formatter;

  @BeforeEach
  void setUp() {
    formatter = new SuggestionFormatter();
  }

  @Test
  void shouldFormatReviewCommentWithSuggestionByDefault() {
    var finding =
        new Finding(RiskLevel.HIGH, "Main.java", 10, "Bug", "Fix it", "old code", "new code");

    var comment = formatter.formatReviewComment(finding);

    assertTrue(comment.contains("HIGH"));
    assertTrue(comment.contains("Fix it"));
    assertTrue(comment.contains("```suggestion"));
    assertTrue(comment.contains("new code"));
  }

  @Test
  void shouldOmitSuggestionBlockWhenDisabled() {
    var finding = new Finding(RiskLevel.MEDIUM, "Main.java", 3, "Style", "Rename", "old", "new");

    var comment = formatter.formatReviewComment(finding, false);

    assertTrue(comment.contains("MEDIUM"));
    assertTrue(comment.contains("Rename"));
    assertFalse(comment.contains("```suggestion"));
  }

  @Test
  void shouldNotAddSuggestionBlockWhenFindingHasNoSuggestion() {
    var finding = new Finding(RiskLevel.LOW, "Main.java", 1, "Nit", "Rename", null, null);

    var comment = formatter.formatReviewComment(finding, true);

    assertTrue(comment.contains("LOW"));
    assertFalse(comment.contains("```suggestion"));
  }

  @Test
  void shouldReturnEmptySuggestionBlockForNullValues() {
    assertEquals("", formatter.formatSuggestionBlock(null, "new"));
    assertEquals("", formatter.formatSuggestionBlock("old", null));
  }

  @Test
  void shouldAppendHiddenFindingMarkerWhenIdProvided() {
    var finding = new Finding(RiskLevel.HIGH, "Main.java", 10, "Bug", "Fix it", null, null);

    var comment = formatter.formatReviewComment(finding, true, 3);

    assertTrue(comment.contains("<!-- thrillhousebot:finding=3 -->"));
  }

  @Test
  void shouldParseFindingMarkerFromCommentBody() {
    assertEquals(
        3,
        SuggestionFormatter.parseFindingMarker("x\n<!-- thrillhousebot:finding=3 -->").getAsInt());
    assertTrue(SuggestionFormatter.parseFindingMarker("no marker").isEmpty());
    assertTrue(SuggestionFormatter.parseFindingMarker(null).isEmpty());
    assertTrue(SuggestionFormatter.parseFindingMarker("").isEmpty());
    assertTrue(SuggestionFormatter.parseFindingMarker("   ").isEmpty());
    assertTrue(
        SuggestionFormatter.parseFindingMarker(
                "<!-- thrillhousebot:finding=99999999999999999999 -->")
            .isEmpty());
  }

  @Test
  void shouldOmitFindingMarkerWithoutId() {
    var finding = new Finding(RiskLevel.HIGH, "Main.java", 10, "Bug", "Fix it", null, null);

    assertFalse(formatter.formatReviewComment(finding).contains("thrillhousebot:finding"));
  }

  @Test
  void shouldNotShowConfidenceTagForHighConfidenceFindings() {
    var finding =
        new Finding(RiskLevel.HIGH, Confidence.HIGH, "Main.java", 10, "Bug", "Fix it", null, null);

    var comment = formatter.formatReviewComment(finding);

    assertFalse(comment.contains("confidence"));
  }

  @Test
  void shouldTagLowerConfidenceFindingsForVerification() {
    var finding =
        new Finding(
            RiskLevel.CRITICAL, Confidence.MEDIUM, "Main.java", 10, "Bug", "Fix it", null, null);

    var comment = formatter.formatReviewComment(finding);

    assertTrue(comment.contains("_(medium confidence — verify before acting)_"));
  }

  @Test
  void confidenceDisclaimerShouldBeEmptyForNullOrHighConfidence() {
    assertEquals("", SuggestionFormatter.confidenceDisclaimer(null));
    assertEquals("", SuggestionFormatter.confidenceDisclaimer(Confidence.HIGH));
    assertEquals(
        "_(low confidence — verify before acting)_",
        SuggestionFormatter.confidenceDisclaimer(Confidence.LOW));
  }

  @Test
  void shouldFormatDocCommentWithSymbolAndSuggestionBlock() {
    var comment =
        formatter.formatDocComment(
            "Foo.bar(int)", "public int bar(int x) {", "/** doc */\npublic int bar(int x) {");

    assertTrue(comment.contains("📝 Documentation for `Foo.bar(int)`"));
    assertTrue(comment.contains("```suggestion"));
    assertTrue(comment.contains("/** doc */"));
    assertTrue(comment.contains("public int bar(int x) {"));
  }

  @Test
  void shouldFormatDocCommentWithoutSymbol() {
    var blank = formatter.formatDocComment(" ", "old", "/** doc */\nold");
    var nullSymbol = formatter.formatDocComment(null, "old", "/** doc */\nold");

    for (var comment : new String[] {blank, nullSymbol}) {
      assertTrue(comment.contains("📝 Documentation**"), comment);
      assertFalse(comment.contains("for `"), comment);
      assertTrue(comment.contains("```suggestion"), comment);
    }
  }

  @Test
  void shouldFormatDocNoteStatingTheGapWithoutACommittableSuggestion() {
    var note = formatter.formatDocNote("Foo.bar(int)", "/** doc */");

    assertTrue(note.contains("📝 Documentation for `Foo.bar(int)`"), note);
    assertTrue(note.contains("missing documentation"), note);
    assertTrue(note.contains("/** doc */"), note);
    // It's a note describing the gap, not a committable suggestion.
    assertFalse(note.contains("```suggestion"), note);
  }

  @Test
  void shouldFormatDocNoteWithoutSymbolAndWithNullDraft() {
    var blank = formatter.formatDocNote(" ", null);
    var nullSymbol = formatter.formatDocNote(null, "/** doc */");

    for (var note : new String[] {blank, nullSymbol}) {
      assertTrue(note.contains("📝 Documentation**"), note);
      assertFalse(note.contains("for `"), note);
    }
    // A null draft renders an empty block rather than the literal "null".
    assertFalse(blank.contains("null"), blank);
  }

  @Test
  void shouldFormatImprovementCommentAsACommittableSuggestion() {
    var body =
        formatter.formatImprovementComment(
            "  Close the stream  ",
            " error-handling ",
            "  The stream leaks when read() throws.  ",
            "var in = open();",
            "try (var in = open()) {");

    assertTrue(body.contains("**✨ Improvement — Close the stream**"), body);
    assertTrue(body.contains("`error-handling`"), body);
    assertTrue(body.contains("The stream leaks when read() throws."), body);
    assertTrue(body.contains("```suggestion"), body);
    assertTrue(body.contains("try (var in = open()) {"), body);
  }

  @Test
  void shouldFormatImprovementCommentWhenTitleCategoryAndRationaleAreMissing() {
    var nulls = formatter.formatImprovementComment(null, null, null, "old", "new");
    var blanks = formatter.formatImprovementComment("  ", " ", " \n ", "old", "new");

    for (var body : new String[] {nulls, blanks}) {
      // The header degrades to the bare label rather than rendering "null" or an empty backtick
      // pair, and the committable suggestion is still emitted.
      assertTrue(body.startsWith("**✨ Improvement**"), body);
      assertFalse(body.contains("—"), body);
      assertFalse(body.contains("``` `"), body);
      assertFalse(body.contains("null"), body);
      assertTrue(body.contains("```suggestion"), body);
    }
  }

  @Test
  void shouldFormatImprovementBlockAsCopyPasteWithoutASuggestion() {
    var block =
        formatter.formatImprovementBlock(
            " Extract the retry loop ",
            " maintainability ",
            " Duplicated in three call sites. ",
            " src/Foo.java ",
            80,
            "retryPolicy.run(this::call);\n");

    assertTrue(block.startsWith("**Extract the retry loop**"), block);
    assertTrue(block.contains("`maintainability`"), block);
    assertTrue(block.contains("`src/Foo.java:80`"), block);
    assertTrue(block.contains("Duplicated in three call sites."), block);
    assertTrue(block.contains("retryPolicy.run(this::call);"), block);
    // Copy-paste only — it must never render as a committable suggestion.
    assertFalse(block.contains("```suggestion"), block);
  }

  @Test
  void shouldRenderImprovementBlockFencesByteExactly() {
    // Locks the exact fenced-block bytes: the fence constant carries the newline on each side, so
    // the block must open and close on their own lines with no blank line inside it.
    var block = formatter.formatImprovementBlock("T", null, null, null, 0, "line one\nline two");

    assertEquals("**T**\n\n```\nline one\nline two\n```\n", block);
  }

  @Test
  void shouldRenderDocNoteFencesByteExactly() {
    var note = formatter.formatDocNote("Foo.bar()", "/** doc */");

    // The blank line before the fence and the absence of one after it are the point of this
    // assertion: the fence constant supplies both newlines itself.
    assertEquals(
        """
        **📝 Documentation for `Foo.bar()`**
        This symbol is missing documentation. Suggested:

        ```
        /** doc */
        ```
        """,
        note);
  }

  @Test
  void shouldFormatImprovementBlockWhenEveryOptionalFieldIsMissing() {
    var nulls = formatter.formatImprovementBlock(null, null, null, null, 0, null);
    var blanks = formatter.formatImprovementBlock(" ", "  ", " \n ", "  ", 0, "code");

    assertTrue(nulls.startsWith("**Improvement**"), nulls);
    assertTrue(blanks.startsWith("**Improvement**"), blanks);
    for (var block : new String[] {nulls, blanks}) {
      assertFalse(block.contains("null"), block);
      assertFalse(block.contains(":0`"), block);
      assertFalse(block.contains("—"), block);
    }
  }

  @Test
  void shouldFormatGeneratedTestFileAsAPathHeadedCodeBlock() {
    var block =
        formatter.formatGeneratedTestFile(
            " src/test/java/FooTest.java ", "Java", " covers the null branch ", "class FooTest {}");

    assertTrue(block.startsWith("### `src/test/java/FooTest.java`\n"), block);
    assertTrue(block.contains("covers the null branch\n"), block);
    assertTrue(block.contains("```java\nclass FooTest {}\n```"), block);
    // A new file has no diff line to anchor a committable suggestion to.
    assertFalse(block.contains("```suggestion"), block);
  }

  @Test
  void shouldWidenTheFencePastBacktickRunsInTheTestSource() {
    var block = formatter.formatGeneratedTestFile("t/FooTest.java", "java", null, "a ``` b ```` c");

    assertTrue(block.contains("`````java\n"), block);
    assertTrue(block.endsWith("`````\n"), block);
  }

  @Test
  void shouldOmitAnUnusableLanguageTag() {
    for (var language : new String[] {null, "  ", "java\nnot a tag", "a".repeat(21)}) {
      var block = formatter.formatGeneratedTestFile("t/FooTest.java", language, null, "code");
      assertTrue(block.contains("```\ncode\n```"), block);
    }
  }

  @Test
  void shouldTolerateAMissingPathCoversAndCode() {
    var block = formatter.formatGeneratedTestFile(null, "java", "  ", null);

    assertTrue(block.startsWith("### ``\n"), block);
    assertFalse(block.contains("null"), block);
  }

  @Test
  void shouldKeepAModelSuppliedPathInsideItsHeadingCodeSpan() {
    // Every field here is model output; a prompt-injected diff must not restructure the comment.
    var block =
        formatter.formatGeneratedTestFile(
            "t/FooTest.java`\n\n## Injected\n```", "java", null, "code");

    assertTrue(block.startsWith("### `t/FooTest.java ## Injected `\n"), block);
    assertFalse(block.contains("\n## Injected"), block);
  }

  @Test
  void shouldFlattenAMultiLineCoversNote() {
    var block =
        formatter.formatGeneratedTestFile("t/FooTest.java", "java", "covers\n```\nboom", "code");

    // The "covers" note is model prose routed through MarkdownSafe.inline: flattened to one line
    // with its fence neutralized, so it cannot open a code block of its own.
    assertTrue(block.contains(MarkdownSafe.inline("covers\n```\nboom") + "\n"), block);
    assertFalse(block.contains("covers ```"), block);
    assertTrue(block.contains("```java\ncode\n```"), block);
  }

  // --- audit F2/F3: every model-supplied field is routed through the MarkdownSafe helper ---

  @Test
  void improvementBlockNeutralizesEveryModelFieldBreakout() {
    var block =
        formatter.formatImprovementBlock(
            "Title\n\n## Injected",
            "cat\negory",
            "why\n\n</details>",
            "src/Foo.java`\n## x",
            7,
            "code with ``` fence and ```` longer");

    // Title/category/rationale/file are flattened and neutralized; code is fenced with a widened
    // fence, so nothing can start a new block or close the copy-paste block early.
    assertTrue(block.startsWith("**" + MarkdownSafe.inline("Title\n\n## Injected") + "**"), block);
    assertTrue(block.contains("`" + MarkdownSafe.inlineCode("cat\negory") + "`"), block);
    assertTrue(block.contains("`" + MarkdownSafe.inlineCode("src/Foo.java`\n## x") + ":7`"), block);
    assertTrue(block.contains(MarkdownSafe.inline("why\n\n</details>")), block);
    assertFalse(block.contains("\n## Injected"), block);
    assertFalse(block.contains("\n## x"), block);
    assertFalse(block.contains("</details>"), block);
    assertTrue(block.contains("\n`````\n"), block);
    assertTrue(block.endsWith("`````\n"), block);
  }

  @Test
  void improvementCommentNeutralizesEveryModelFieldBreakout() {
    var body =
        formatter.formatImprovementComment(
            "Title\n## Injected", "ca\nt", "why\n\n</details>", "old", "new ``` code");

    assertTrue(
        body.startsWith("**✨ Improvement — " + MarkdownSafe.inline("Title\n## Injected") + "**"),
        body);
    assertTrue(body.contains("`" + MarkdownSafe.inlineCode("ca\nt") + "`"), body);
    assertTrue(body.contains(MarkdownSafe.inline("why\n\n</details>")), body);
    assertFalse(body.contains("\n## Injected"), body);
    assertFalse(body.contains("</details>"), body);
    // Suggestion fence widened past the ``` in the model code.
    assertTrue(body.contains("````suggestion\n"), body);
    assertTrue(body.endsWith("````\n"), body);
  }

  @Test
  void docNoteNeutralizesSymbolAndWidensTheDraftFence() {
    var note = formatter.formatDocNote("Foo`\n## X", "/** ``` */");

    assertTrue(
        note.startsWith("**📝 Documentation for `" + MarkdownSafe.inlineCode("Foo`\n## X") + "`**"),
        note);
    assertFalse(note.contains("\n## X"), note);
    assertTrue(note.contains("\n````\n"), note);
    assertTrue(note.endsWith("````\n"), note);
  }

  @Test
  void docCommentNeutralizesTheSymbol() {
    var comment = formatter.formatDocComment("Foo`\n## X", "old", "new");

    assertTrue(
        comment.startsWith(
            "**📝 Documentation for `" + MarkdownSafe.inlineCode("Foo`\n## X") + "`**"),
        comment);
    assertFalse(comment.contains("\n## X"), comment);
  }

  @Test
  void reviewCommentNeutralizesTheModelTitle() {
    var finding =
        new Finding(
            RiskLevel.HIGH, "Main.java", 10, "Bug\n\n</details>\n\n## X", "desc", null, null);

    var comment = formatter.formatReviewComment(finding);

    assertTrue(comment.contains(MarkdownSafe.inline("Bug\n\n</details>\n\n## X") + "**"), comment);
    assertFalse(comment.contains("\n## X"), comment);
    assertFalse(comment.contains("</details>"), comment);
  }

  @Test
  void suggestionBlockWidensThePastAFenceInTheModelCode() {
    var block = formatter.formatSuggestionBlock("old", "new ``` code");

    assertTrue(block.contains("````suggestion\n"), block);
    assertTrue(block.endsWith("````\n"), block);
  }
}
