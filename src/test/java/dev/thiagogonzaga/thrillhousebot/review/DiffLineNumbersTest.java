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
    void nullPassesThrough() {
      assertNull(DiffLineNumbers.annotate(null));
    }

    @Test
    void everyNumberAgreesWithTheResolver() {
      var patch = "@@ -5,3 +7,4 @@\n keep\n-gone\n+one\n+two\n keep2\n@@ -40 +43,2 @@\n+tail\n end";
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
          "    int c;\n    int d;", DiffLineNumbers.stripLeaked("11 +    int c;\n12 +    int d;"));
      assertEquals("int a;", DiffLineNumbers.stripLeaked("10  int a;"));
      assertEquals("a\n\nb", DiffLineNumbers.stripLeaked("1  a\n\n2  b"));
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
        assertSame(quote, DiffLineNumbers.stripLeaked(quote), quote);
      }
      assertNull(DiffLineNumbers.stripLeaked(null));
    }
  }
}
