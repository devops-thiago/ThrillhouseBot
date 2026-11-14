package dev.thiagogonzaga.thrillhousebot.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CopiedLineNumbersTest {

  // Numbered for the review call as: 10 " int a;", "-int b;", 11 "+int c;", 12 "+",
  // 13 " int e;", 14 " " (an empty context line), 15 "+int f;", "\ No newline at end of file".
  private static final String DIFF =
      """
      ### src/A.java (modified, +3 -1)
      ```diff
      @@ -10,4 +10,6 @@ class A {
       int a;
      -int b;
      +int c;
      +
       int e;
      \s
      +int f;
      \\ No newline at end of file
      ```

      ### other/A.java (modified, +1 -0)
      ```diff
      @@ -1,1 +11,2 @@
       int z;
      +int y;
      ```

      ### src/list.yml (added, +3 -0)
      ```diff
      @@ -0,0 +1,3 @@
      +items:
      +  - a
      +  - b
      ```
      """;

  private final CopiedLineNumbers copied = CopiedLineNumbers.of(DIFF);

  @Test
  void removesTheNumbersOfACopiedRun() {
    assertEquals("int c;\n", strip("src/A.java", "11 +int c;\n12 +"));
    assertEquals("int a;", strip("src/A.java", "10  int a;"));
    assertEquals("\nint c;", strip("src/A.java", "\n11 +int c;"));
  }

  @Test
  void aCopyThatDroppedTheMarkerIsStrippedWhenItsCodeMatches() {
    // #1357: the model kept the number but not the "+" or the context space.
    assertEquals("int c;", strip("src/A.java", "11 int c;"));
    assertEquals("int a;", strip("src/A.java", "10 int a;"));
    assertEquals(
        "int a;\nint c;\n\nint f;",
        strip("src/A.java", "10 int a;\n-int b;\n11 +int c;\n14\n15 int f;"));
  }

  @Test
  void anIndentedCopyThatDroppedTheMarkerKeepsItsIndentation() {
    // "2   - a" reads like a context line holding " - a", but line 2 is "+  - a": its code, two
    // spaces included, follows the number and one space.
    assertEquals("items:\n  - a\n  - b", strip("src/list.yml", "1 items:\n2   - a\n3   - b"));

    var result = copied.strip(finding("src/list.yml", 2, "2   - a", "2   - c\n9   - d"));
    assertEquals("  - a", result.suggestionOld());
    assertEquals("  - c\n9   - d", result.suggestionNew());
  }

  @Test
  void anUnmarkedCopyWhoseCodeDiffersOrWhoseLineIsMissingIsLeftAsWritten() {
    for (var quote :
        new String[] {
          "11 int x;", // line 11 holds "int c;"
          "10 int a;\n11 int x;", // one line of the run does not match
          "99 int c;", // no line 99
          "1 2" // no line 1
        }) {
      var finding = finding("src/A.java", 11, quote, null);
      assertSame(finding, copied.strip(finding), quote);
    }
  }

  @Test
  void newCodeLosesUnmarkedNumbersInsideTheConfirmedCopyOnly() {
    var result = copied.strip(finding("src/A.java", 11, "11 int c;", "11 long c;\n9 w;"));

    assertEquals("int c;", result.suggestionOld());
    assertEquals("long c;\n9 w;", result.suggestionNew());
  }

  @Test
  void aRunStartingOnARemovedLineKeepsOnlyItsNewSideCode() {
    assertEquals("int c;", strip("src/A.java", "-int b;\n11 +int c;"));
    assertEquals("int a;\nint c;", strip("src/A.java", "10  int a;\n-int b;\n11 +int c;"));
    assertEquals("int f;", strip("src/A.java", "15 +int f;\n\\ No newline at end of file"));
  }

  @Test
  void anEmptyContextLineCopiedWithoutItsTrailingSpacesIsPartOfTheRun() {
    assertEquals("int e;\n\nint f;", strip("src/A.java", "13  int e;\n14\n15 +int f;"));
    assertEquals("int e;\n\nint f;", strip("src/A.java", "13  int e;\n14  \n15 +int f;"));
  }

  @Test
  void theLineTheFindingCitesDoesNotMatter() {
    for (int cited : new int[] {-1, 0, 1, 10, 12, 500}) {
      var result = copied.strip(finding("src/A.java", cited, "11 +int c;", null));
      assertEquals("int c;", result.suggestionOld(), "cited " + cited);
    }
  }

  @Test
  void codeThatOnlyLooksNumberedIsLeftAsWritten() {
    for (var quote :
        new String[] {
          "1 + 2", // line 1 of src/A.java is not "+ 2"
          "11 +int x;", // line 11 holds other code
          "11 +int c;\n- note", // "- note" is not a removed line here
          "11 +int c;\nplain", // a line the diff does not number
          "-int b;", // a removed line alone copies no number
          "200 OK",
          "\n"
        }) {
      var finding = finding("src/A.java", 11, quote, null);
      assertSame(finding, copied.strip(finding), quote);
    }
  }

  @Test
  void aShortPathIsCheckedAgainstTheSectionThatConfirmsTheWholeQuote() {
    assertEquals("int c;", strip("A.java", "11 +int c;"));
    assertEquals("int y;", strip("A.java", "12 +int y;"));
    // Line 11 is "+int c;" in one section and " int z;" in the other: no section holds both.
    var mixed = finding("A.java", 11, "11 +int c;\n11  int z;", null);
    assertSame(mixed, copied.strip(mixed));
    assertEquals("int c;", strip("repo/src/A.java", "11 +int c;"));
  }

  @Test
  void aFindingOutsideTheDiffOrWithoutAQuoteIsUnchanged() {
    for (var finding :
        new ReviewResponse.Finding[] {
          finding("Missing.java", 11, "11 +int c;", null),
          finding(null, 11, "11 +int c;", null),
          finding(" ", 11, "11 +int c;", null),
          finding("src/A.java", 11, null, "11 +long c;"),
          finding("src/A.java", 11, " ", "11 +long c;")
        }) {
      assertSame(finding, copied.strip(finding));
    }
  }

  @Test
  void newCodeLosesOnlyTheNumbersInsideTheConfirmedCopy() {
    var result =
        copied.strip(
            finding("src/A.java", 11, "11 +int c;\n12 +", "9 +w;\n11 +long c;\n12\n13 +x;"));

    assertEquals("int c;\n", result.suggestionOld());
    assertEquals("9 +w;\nlong c;\n\n13 +x;", result.suggestionNew());
  }

  @Test
  void newCodeKeepsItsDashLinesAndIsUntouchedWithoutAConfirmedCopy() {
    var yaml = copied.strip(finding("src/list.yml", 1, "1 +items:", "1 +items:\n- a\n\\ b"));
    assertEquals("items:\n- a\n\\ b", yaml.suggestionNew());

    var unconfirmed = finding("src/A.java", 11, "int c;", "11 +long c;");
    assertSame(unconfirmed, copied.strip(unconfirmed));

    var noNew = copied.strip(finding("src/A.java", 11, "11 +int c;", null));
    assertNull(noNew.suggestionNew());
  }

  @Test
  void keepsTheRestOfTheFinding() {
    var original =
        new ReviewResponse.Finding("high", "medium", "src/A.java", 9, "t", "d", "11 +int c;", "n");

    var result = copied.strip(original);

    assertEquals(
        new ReviewResponse.Finding("high", "medium", "src/A.java", 9, "t", "d", "int c;", "n"),
        result);
  }

  @Test
  void everyLineTheAnnotatorNumbersIsConfirmed() {
    var numbered = Pattern.compile("^(\\d+) [+ ](.*)$");
    int checked = 0;
    String file = null;
    for (var line : DiffLineNumbers.annotate(DIFF).split("\n")) {
      if (line.startsWith("### ")) {
        file = DiffIndex.sectionFilename(line);
      }
      var m = numbered.matcher(line);
      if (m.matches()) {
        assertEquals(m.group(2), strip(file, line), line);
        checked++;
      }
    }
    assertEquals(11, checked);
  }

  @Test
  void aDiffWithNothingNumberedConfirmsNothing() {
    for (var diff : new String[] {null, "", "plain text", "### A.java (added, +1 -0)\n+x"}) {
      var index = CopiedLineNumbers.of(diff);
      var finding = finding("A.java", 1, "1 +x", null);
      assertSame(finding, index.strip(finding), String.valueOf(diff));
    }
    // A clipped block's lines before its first hunk header carry no number to confirm.
    var clipped = CopiedLineNumbers.of("### A.java (added, +1 -0)\n```diff\n+x\n```");
    var finding = finding("A.java", 1, "1 +x", null);
    assertSame(finding, clipped.strip(finding));
  }

  private String strip(String file, String quote) {
    return copied.strip(finding(file, 11, quote, null)).suggestionOld();
  }

  private static ReviewResponse.Finding finding(
      String file, int line, String suggestionOld, String suggestionNew) {
    return new ReviewResponse.Finding(
        "low", "high", file, line, "title", "description", suggestionOld, suggestionNew);
  }
}
