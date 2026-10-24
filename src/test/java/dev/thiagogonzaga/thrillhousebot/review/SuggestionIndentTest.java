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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

/** #1197: the cases where a suggestion's indentation is left as written. */
class SuggestionIndentTest {

  private static final List<String> TABBED = List.of("\tif ok {", "\t\treturn", "\t}");

  @Test
  void aMissingOrOneLineSuggestionIsNeverChanged() {
    assertNull(SuggestionIndent.restore("a\n  b", null, List.of("  a", "  b")));
    var oneLine = "a();";
    assertSame(
        oneLine, SuggestionIndent.restore("a();\n  b();", oneLine, List.of("  a();", "  b();")));
  }

  @Test
  void aQuoteThatDoesNotPairWithTheReplacedLinesChangesNothing() {
    var suggestion = "x();\n    y();";
    assertSame(suggestion, SuggestionIndent.restore("x();", suggestion, List.of("    x();")));
    assertSame(
        suggestion,
        SuggestionIndent.restore(
            "x();\n    y();", suggestion, List.of("    x();", "    y();", "z")));
  }

  @Test
  void theFirstLineIsRestoredOnlyWhenTheQuoteLostItAloneAndTheSuggestionCarriesTheLoss() {
    var replaced = List.of("    x();", "    y();");
    var lostFirst = "x();\n    y();";
    assertEquals(
        "    z();\n    y();", SuggestionIndent.restore(lostFirst, "z();\n    y();", replaced));
    // A blank line in the quote has no line of the diff to pair with.
    assertEquals(
        "    z();\n\n    y();",
        SuggestionIndent.restore("x();\n\n    y();", "z();\n\n    y();", replaced));

    // The suggestion's first line is indented already, or is blank: left alone.
    var indented = "  z();\n    y();";
    assertSame(indented, SuggestionIndent.restore(lostFirst, indented, replaced));
    var blankFirst = "\n    y();";
    assertSame(blankFirst, SuggestionIndent.restore(lostFirst, blankFirst, replaced));

    // A later quoted line differs too, so it is not the first-line loss.
    var shifted = "z();\n  y();";
    assertSame(shifted, SuggestionIndent.restore("x();\n  y();", shifted, replaced));
    // The quote's first line is not a prefix of the replaced one's indent.
    var mixed = " z();\n\t\ty();";
    assertSame(
        mixed, SuggestionIndent.restore(" x();\n\t\ty();", mixed, List.of("\t\tx();", "\t\ty();")));
  }

  @Test
  void spacesBecomeTabsOnlyAtOneWidthThatHoldsForEveryPair() {
    var quote = "  if ok {\n    return\n  }";
    assertEquals(
        "\tif ok {\n\n\t\treturn nil\n\t}",
        SuggestionIndent.restore(quote, "  if ok {\n\n    return nil\n  }", TABBED));

    var suggestion = "  if ok {\n    return\n  }";
    // Widths 2 and 3 in one quote.
    assertSame(
        suggestion, SuggestionIndent.restore("  if ok {\n      return\n  }", suggestion, TABBED));
    // Three spaces for two tabs.
    assertSame(
        suggestion, SuggestionIndent.restore("  if ok {\n   return\n  }", suggestion, TABBED));
    // The quote already uses tabs.
    assertSame(
        suggestion, SuggestionIndent.restore("\tif ok {\n\t\treturn\n\t}", suggestion, TABBED));
    // A replaced line at column 0 quoted with an indent.
    assertSame(
        suggestion,
        SuggestionIndent.restore(
            " func f() {\n  return", suggestion, List.of("func f() {", "\treturn")));
    // A quoted line at column 0 over a tab-indented one: no width holds for that pair.
    assertSame(
        suggestion,
        SuggestionIndent.restore("a\n  b\n  c", suggestion, List.of("\t\t\ta", "\tb", "\tc")));
    // Both losses at once: the first line at column 0 and spaces for tabs. Left as written rather
    // than converted with the first line still at column 0.
    var both = "for {\n  b\n  c";
    assertSame(both, SuggestionIndent.restore(both, both, List.of("\tfor {", "\tb", "\tc")));
    // Every line at column 0: nothing to convert.
    var flat = "a()\nb()";
    assertSame(flat, SuggestionIndent.restore("a()\nb()", flat, List.of("a()", "b()")));
  }

  @Test
  void aSuggestionLineThatIsNotWholeTabsInSpacesKeepsTheSuggestionAsWritten() {
    var quote = "  if ok {\n    return\n  }";
    var odd = "  if ok {\n     return\n  }";
    assertSame(odd, SuggestionIndent.restore(quote, odd, TABBED));
    var tabbed = "  if ok {\n\t\treturn\n  }";
    assertSame(tabbed, SuggestionIndent.restore(quote, tabbed, TABBED));
  }
}
