package dev.thiagogonzaga.thrillhousebot.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DiffLineNumbersTest {

  @Nested
  class Annotate {

    @Test
    void numbersAddedAndContextLinesFromTheHunkHeader() {
      var diff =
          """
          ### src/A.java (modified, +2 -1)
          ```diff
          @@ -10,3 +10,4 @@ class A {
           int a;
          -int b;
          +int c;
          +int d;
           int e;
          ```
          """;

      assertEquals(
          """
          ### src/A.java (modified, +2 -1)
          ```diff
          @@ -10,3 +10,4 @@ class A {
          10  int a;
          -int b;
          11 +int c;
          12 +int d;
          13  int e;
          ```
          """,
          DiffLineNumbers.annotate(diff));
    }

    @Test
    void restartsAtEachHunk() {
      var diff = "```diff\n@@ -1,1 +1,1 @@\n+a\n@@ -99998,2 +99999,2 @@\n b\n+c\n```";

      assertEquals(
          "```diff\n@@ -1,1 +1,1 @@\n1 +a\n@@ -99998,2 +99999,2 @@\n99999  b\n100000 +c\n```",
          DiffLineNumbers.annotate(diff));
    }

    @Test
    void linesBeforeAnyHunkHeaderNoNewlineMarkersAndEmptyLinesStayUnnumbered() {
      var diff = "```diff\n orphan\n@@ -1,2 +1,2 @@\n+x\n\n y\n\\ No newline at end of file\n```\n";

      assertEquals(
          "```diff\n orphan\n@@ -1,2 +1,2 @@\n1 +x\n\n2  y\n\\ No newline at end of file\n```\n",
          DiffLineNumbers.annotate(diff));
    }

    @Test
    void aLineThatOnlyLooksLikeAHunkHeaderStartsNoCount() {
      var diff = "```diff\n@@ not a header @@\n+x\n```";

      assertEquals(diff, DiffLineNumbers.annotate(diff));
    }

    @Test
    void anUnclosedBlockIsAnnotatedToTheEnd() {
      assertEquals(
          "```diff\n@@ -1 +1 @@\n1 +a", DiffLineNumbers.annotate("```diff\n@@ -1 +1 @@\n+a"));
    }

    @Test
    void textOutsideDiffBlocksIsUnchangedAndEachBlockStartsAfresh() {
      var skipped = "### a (removed, +1 -0)\n(a skipped: matches ignored pattern, +1 -0)\n";
      assertSame(skipped, DiffLineNumbers.annotate(skipped));

      var mixed = "```text\n+x\n```\n```diff\n@@ -1 +1 @@\n+y\n```\n+z\n```diff\n+orphan\n```";
      assertEquals(
          "```text\n+x\n```\n```diff\n@@ -1 +1 @@\n1 +y\n```\n+z\n```diff\n+orphan\n```",
          DiffLineNumbers.annotate(mixed));
    }

    @Test
    void aFenceInsideAMarkdownPatchDoesNotCloseTheBlock() {
      var diff = "```diff\n@@ -1,3 +1,4 @@\n ```java\n+int a;\n ```\n+after\n```";

      assertEquals(
          "```diff\n@@ -1,3 +1,4 @@\n1  ```java\n2 +int a;\n3  ```\n4 +after\n```",
          DiffLineNumbers.annotate(diff));
    }

    @Test
    void nullPassesThrough() {
      assertNull(DiffLineNumbers.annotate(null));
    }

    @Test
    void everyNumberAgreesWithTheResolver() {
      // The empty line is one the formatter or a trimmed patch can leave: neither side counts it.
      var patch =
          "@@ -5,3 +7,4 @@\n keep\n-gone\n+one\n\n+two\n keep2\n@@ -40 +43,2 @@\n+tail\n end";
      var resolver = new DiffLineResolver(Map.of("F.java", patch));
      var numbered = Pattern.compile("^(\\d+) [+ ](.*)$");
      int checked = 0;

      for (var line : DiffLineNumbers.annotate("```diff\n" + patch + "\n```").split("\n")) {
        var m = numbered.matcher(line);
        if (m.matches()) {
          assertEquals(m.group(2), resolver.getLineText("F.java", Integer.parseInt(m.group(1))));
          checked++;
        }
      }
      assertEquals(6, checked);
    }
  }

  @Nested
  class StripLeaked {

    @Test
    void removesCopiedNumbersAndMarkers() {
      assertEquals(
          "    int c;\n    int d;",
          DiffLineNumbers.stripLeaked("11 +    int c;\n12 +    int d;", 11));
      assertEquals("a\n\nb", DiffLineNumbers.stripLeaked("1  a\n\n2  b", 1));
      assertEquals("int a;", DiffLineNumbers.stripLeaked("10  int a;", 10));
      assertEquals(
          "\n  int a;\nint b;", DiffLineNumbers.stripLeaked("\n10    int a;\n11 +int b;", 12));
    }

    @Test
    void aRunCopiedAcrossAChangeKeepsOnlyItsNewSideCode() {
      assertEquals(
          "int a;\nint c;", DiffLineNumbers.stripLeaked("11  int a;\n-int b;\n12 +int c;", 11));
      assertEquals(
          "x\ny", DiffLineNumbers.stripLeaked("3 +x\n\\ No newline at end of file\n4 +y", 3));
      var listWithDash = "1  item one\n- note\nplain";
      assertSame(listWithDash, DiffLineNumbers.stripLeaked(listWithDash, 1));
    }

    @Test
    void aRunIsStrippedOnlyNearTheCitedLine() {
      var list = "1  item one\n2  item two";
      assertSame(list, DiffLineNumbers.stripLeaked(list, 40));
      assertSame(list, DiffLineNumbers.stripLeaked(list, -1));
      assertEquals("item one\nitem two", DiffLineNumbers.stripLeaked(list, 4));
      assertSame(list, DiffLineNumbers.stripLeaked(list, 6));
    }

    @Test
    void aRunEndingAtOrAroundTheCitedLineIsStripped() {
      var run = "45 +a();\n46 +b();\n47 +c();\n48 +d();\n49 +e();";
      var code = "a();\nb();\nc();\nd();\ne();";
      assertEquals(code, DiffLineNumbers.stripLeaked(run, 50));
      assertEquals(code, DiffLineNumbers.stripLeaked(run, 47));
      assertEquals(code, DiffLineNumbers.stripLeaked(run, 52));
      assertSame(run, DiffLineNumbers.stripLeaked(run, 53));
    }

    @Test
    void aSingleNumberedLineIsStrippedOnlyWhenItsNumberIsTheCitedLine() {
      // On its own "1 + 2" is as likely to be the code as a copied "1 +" and " 2".
      for (int cited : new int[] {-1, 2, 11}) {
        assertSame("1 + 2", DiffLineNumbers.stripLeaked("1 + 2", cited));
      }
      assertEquals(" 2", DiffLineNumbers.stripLeaked("1 + 2", 1));
    }

    @Test
    void leavesCodeThatIsNotACopiedRunAlone() {
      for (var quote :
          new String[] {
            "1 + 2\n5 + 6", // numbers that do not run on
            "200 OK", // no marker after the space
            "1  a\nplain", // a line without a number
            "x = 1 + 2", // does not start with a digit
            "1234567890 +x", // longer than any line number
            ""
          }) {
        assertSame(quote, DiffLineNumbers.stripLeaked(quote, 1), quote);
      }
      assertNull(DiffLineNumbers.stripLeaked(null, 1));
    }
  }
}
