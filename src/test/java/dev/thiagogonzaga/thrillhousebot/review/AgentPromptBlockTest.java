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

import dev.thiagogonzaga.thrillhousebot.github.CommentBodyLimit;
import org.junit.jupiter.api.Test;

class AgentPromptBlockTest {

  @Test
  void renderIncludesScopeAndSuggestionHint() {
    var finding =
        new Finding(
            RiskLevel.HIGH,
            Confidence.HIGH,
            "src/Main.java",
            12,
            "Null deref",
            "First paragraph.\n\nSecond ignored.",
            "old",
            "new");
    var block = AgentPromptBlock.render(finding, true);
    assertTrue(block.contains("<summary>Agent prompt</summary>"));
    assertTrue(block.contains("src/Main.java"));
    assertTrue(block.contains("line 12"));
    assertTrue(block.contains("Null deref"));
    assertTrue(block.contains("First paragraph."));
    assertFalse(block.contains("Second ignored."));
    assertTrue(block.contains("suggested change"));
  }

  @Test
  void filelessFindingRendersNothing() {
    var finding = new Finding(RiskLevel.LOW, null, 0, "Title", "desc", null, null);
    assertTrue(AgentPromptBlock.render(finding, true).isEmpty());
  }

  @Test
  void withAgentPromptLeavesNullBodyUntouched() {
    var finding = new Finding(RiskLevel.LOW, "src/A.java", 1, "Title", "desc", null, null);
    assertEquals(null, AgentPromptBlock.withAgentPrompt(null, finding, true));
  }

  @Test
  void withAgentPromptAppendsTheRenderedBlock() {
    var finding = new Finding(RiskLevel.LOW, "src/A.java", 1, "Title", "desc", null, null);
    var out = AgentPromptBlock.withAgentPrompt("review text", finding, false);
    assertTrue(out.startsWith("review text\n\n"));
    assertTrue(out.contains("<summary>Agent prompt</summary>"));
  }

  @Test
  void withoutSuggestionUsesBehaviouralFixWording() {
    var finding =
        new Finding(
            RiskLevel.MEDIUM, Confidence.MEDIUM, "pkg/Foo.java", 3, "Race", "d", null, null);
    var block = AgentPromptBlock.render(finding, false);
    assertTrue(block.contains("behavioural"));
    assertFalse(block.contains("suggested change"));
  }

  @Test
  void blankFileRendersNothing() {
    var finding = new Finding(RiskLevel.LOW, "   ", 1, "Title", "desc", null, null);
    assertTrue(AgentPromptBlock.render(finding, true).isEmpty());
  }

  @Test
  void missingDescriptionUsesPlaceholder() {
    var finding = new Finding(RiskLevel.LOW, "src/X.java", 2, "Title", "  ", null, null);
    var block = AgentPromptBlock.render(finding, false);
    assertTrue(block.contains("(no description)"));
  }

  @Test
  void withAgentPromptSkipsAppendWhenBlockEmpty() {
    var finding = new Finding(RiskLevel.LOW, null, 0, "Title", "desc", null, null);
    assertEquals("body only", AgentPromptBlock.withAgentPrompt("body only", finding, true));
  }

  @Test
  void whitespaceOnlySuggestionFallsBackToBehaviouralWording() {
    var finding = new Finding(RiskLevel.HIGH, "src/A.java", 1, "T", "d", "old", "   ");
    var block = AgentPromptBlock.render(finding, true);
    assertTrue(block.contains("behavioural"));
  }

  @Test
  void nullDescriptionUsesPlaceholder() {
    var finding = new Finding(RiskLevel.LOW, "src/X.java", 2, "Title", null, null, null);
    var block = AgentPromptBlock.render(finding, false);
    assertTrue(block.contains("(no description)"));
  }

  @Test
  void singleParagraphDescriptionUsesTheWholeText() {
    var finding =
        new Finding(RiskLevel.MEDIUM, "pkg/Foo.java", 3, "Race", "Only one paragraph.", null, null);
    var block = AgentPromptBlock.render(finding, false);
    assertTrue(block.contains("Only one paragraph."));
  }

  @Test
  void includeSuggestionWithoutBlockStillUsesBehaviouralWording() {
    var finding = new Finding(RiskLevel.HIGH, "src/A.java", 1, "T", "d", null, null);
    var block = AgentPromptBlock.render(finding, true);
    assertTrue(block.contains("behavioural"));
    assertFalse(block.contains("suggested change"));
  }

  @Test
  void validSuggestionNamesApplyingTheCommentBlock() {
    var finding = new Finding(RiskLevel.HIGH, "src/F.java", 5, "Bug", "d", "old", "new");
    var block = AgentPromptBlock.render(finding, true);
    assertTrue(block.contains("Recommended fix: apply the suggested change in the comment above"));
  }

  @Test
  void withAgentPromptAppendsBlockWhenIncludeSuggestionIsOn() {
    var finding = new Finding(RiskLevel.HIGH, "src/F.java", 5, "Bug", "d", "old", "new");
    var out = AgentPromptBlock.withAgentPrompt("body", finding, true);
    assertTrue(out.startsWith("body\n\n"));
    assertTrue(out.contains("apply the suggested change in the comment above"));
  }

  // A path is placed in a code span once and in prose once; each gets the escaping its place needs.
  @Test
  void aPathInTheCodeSpanIsNotEntityEscapedAndProseStillIs() {
    var finding = new Finding(RiskLevel.HIGH, "src/a|b`c.java", 4, "T", "d", null, null);

    var block = AgentPromptBlock.render(finding, false);

    assertTrue(block.contains("Fix this review finding in `src/a|bc.java` (line 4)."), block);
    assertTrue(block.contains("Scope: src/a\\|b&#96;c.java:4."), block);
    assertFalse(block.contains("`src/a\\|b"), block);
  }

  @Test
  void aLongProblemParagraphIsCutAtAWordWithAnEllipsis() {
    var paragraph = "word ".repeat(400).strip();
    var finding = new Finding(RiskLevel.HIGH, "src/A.java", 1, "T", paragraph, null, null);

    var block = AgentPromptBlock.render(finding, false);

    var problem = block.substring(block.indexOf("Problem: T. ") + 12, block.indexOf("\n\nScope:"));
    assertTrue(problem.endsWith("word…"), problem);
    assertTrue(problem.length() <= AgentPromptBlock.MAX_PROBLEM_CHARS + 1, problem);
    assertTrue(block.endsWith("</details>"));
  }

  @Test
  void aLongParagraphWithNoSpaceIsCutAtTheLimitWithoutSplittingASurrogatePair() {
    var noSpace = "x".repeat(AgentPromptBlock.MAX_PROBLEM_CHARS - 1) + "\uD83D\uDE00tail";

    var cut = AgentPromptBlock.capped(noSpace);

    assertEquals("x".repeat(AgentPromptBlock.MAX_PROBLEM_CHARS - 1) + "…", cut);
    assertEquals(
        "y".repeat(AgentPromptBlock.MAX_PROBLEM_CHARS) + "…",
        AgentPromptBlock.capped("y".repeat(AgentPromptBlock.MAX_PROBLEM_CHARS + 5)));
  }

  @Test
  void aCommentNearGitHubsLimitKeepsItsBodyWithoutAnUnclosedBlock() {
    var finding =
        new Finding(RiskLevel.HIGH, "src/A.java", 1, "T", "word ".repeat(5_000), null, null);
    var body = "b".repeat(CommentBodyLimit.MAX_LENGTH - 500);

    var out = AgentPromptBlock.withAgentPrompt(body, finding, false);

    assertEquals(body, out);
  }

  @Test
  void aVeryLongDescriptionStillRendersAClosedBlockUnderTheLimit() {
    var finding =
        new Finding(RiskLevel.HIGH, "src/A.java", 1, "T", "word ".repeat(20_000), null, null);

    var out = AgentPromptBlock.withAgentPrompt("review text", finding, false);

    assertTrue(out.endsWith("</details>"), out);
    assertTrue(out.length() < CommentBodyLimit.MAX_LENGTH);
  }
}
