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
}
