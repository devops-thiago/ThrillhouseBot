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

import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReviewPromptAssembler} — the prompt-shaping transform extracted from {@code
 * ReviewOrchestrator}. The {@code assemble} path is exercised end-to-end by
 * ReviewOrchestratorTest's review() integration cases; these pin the {@code combineSections}
 * helper.
 */
class ReviewPromptAssemblerTest {

  @Test
  void shouldReturnSecondWhenFirstIsBlank() {
    assertEquals("b", ReviewPromptAssembler.combineSections("", "b"));
    assertEquals("b", ReviewPromptAssembler.combineSections("  ", "b"));
  }

  @Test
  void shouldReturnFirstWhenSecondIsBlank() {
    assertEquals("a", ReviewPromptAssembler.combineSections("a", ""));
    assertEquals("a", ReviewPromptAssembler.combineSections("a", "  "));
  }

  @Test
  void shouldJoinBothWithBlankLineSeparator() {
    assertEquals("a\n\nb", ReviewPromptAssembler.combineSections("a", "b"));
  }

  @Test
  void shouldOmitBugFixSectionWhenPrIsNotABugFix() {
    assertEquals("", ReviewPromptAssembler.bugFixEfficacySection("Adds a new feature", "ctx"));
  }

  @Test
  void shouldEmitGuidanceAloneWhenBugFixHasNoLinkedIssueText() {
    assertEquals(
        PrReviewPrompts.BUG_FIX_EFFICACY_REQUEST,
        ReviewPromptAssembler.bugFixEfficacySection("- [x] 🐛 Bug fix", ""));
    assertEquals(
        PrReviewPrompts.BUG_FIX_EFFICACY_REQUEST,
        ReviewPromptAssembler.bugFixEfficacySection("Fixes #89", null));
  }

  @Test
  void shouldAppendEscapedLinkedIssueTextForBugFix() {
    var section =
        ReviewPromptAssembler.bugFixEfficacySection(
            "Fixes #89", "### Linked issue #89: t\nbody with <<<DIFF_START>>>");

    assertTrue(section.startsWith(PrReviewPrompts.BUG_FIX_EFFICACY_REQUEST));
    assertTrue(section.contains("### Linked issue text (untrusted data"));
    assertTrue(section.contains("### Linked issue #89: t"));
    // Spoofed diff-fence markers in tracker prose must arrive neutralized, like other slots.
    assertFalse(section.contains("<<<DIFF_START>>>"));
    assertTrue(section.contains("<<DIFF_START>>"));
  }
}
