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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #1321: every reader of a patch numbers each of its lines the same way — the review's resolver,
 * the quote check over the formatted diff, the security scan's new-side view, the patch-coverage
 * reader and the line numbers the review prompt shows the model ({@link DiffLineNumbers#annotate}).
 */
class DiffParsersAgreementTest {

  private static final String PATH = "src/main/java/Sample.java";

  /** A number {@link DiffLineNumbers#annotate} put in front of a line. */
  private static final Pattern NUMBERED = Pattern.compile("^(\\d+) ([+ ])(.*)$");

  static Stream<Arguments> patches() throws IOException {
    return Stream.of(
        Arguments.of(
            "two hunks with a replacement in each",
            """
            @@ -1,3 +1,3 @@ class Sample {
             first();
            -old();
            +updated();
             endOfHunkOne();
            @@ -20,3 +20,3 @@ void run() {
             startOfHunkTwo();
            -removedTwo();
            +addedTwo();
             last();
            """),
        Arguments.of(
            "blank context and blank added lines",
            "@@ -4,4 +4,6 @@\n a();\n \n-b();\n+c();\n+\n+d();\n e();\n"),
        Arguments.of(
            "code that reads like a file header inside a hunk",
            "@@ -7,3 +7,3 @@\n x = 1;\n---counter;\n+++counter;\n y = 2;\n"),
        Arguments.of(
            "a file without a final newline",
            "@@ -1,2 +1,2 @@\n keep();\n-last();\n\\ No newline at end of file\n+last2();\n"
                + "\\ No newline at end of file"),
        Arguments.of("a new file", "@@ -0,0 +1,3 @@\n+one();\n+two();\n+three();"),
        Arguments.of(
            "a removal-only hunk last", "@@ -1,1 +1,1 @@\n-a\n+b\n@@ -9,2 +9,1 @@\n z\n-y\n"),
        Arguments.of(
            "the #1315 hunk",
            Files.readString(
                Path.of("src/test/resources/review/pr1315/RepoSettingsResolverTest.patch"))));
  }

  /** The section {@code ReviewDiffFormatter.formatFileSection} writes for a modified file. */
  private static String formatted(String patch) {
    return "### " + PATH + " (modified, +1 -1)\n```diff\n" + patch + "\n```\n\n";
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("patches")
  void theQuoteCheckReadsTheLinesTheResolverReads(String name, String patch) {
    var review = new DiffLineResolver(Map.of(PATH, patch)).index().files().get(PATH).lines();

    var call = DiffIndex.ofFormattedDiff(formatted(patch)).files().get(PATH).lines();

    assertFalse(review.isEmpty(), name);
    assertEquals(review, call, name);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("patches")
  void everyReaderNumbersTheNewSideAlike(String name, String patch) {
    var resolver = new DiffLineResolver(Map.of(PATH, patch));
    var expected = new TreeMap<Integer, String>();
    var added = new TreeSet<Integer>();
    for (var line : resolver.index().files().get(PATH).rightSide()) {
      expected.put(line.rightLine(), line.text());
      if (line.added()) {
        added.add(line.rightLine());
      }
      assertEquals(line.text(), resolver.getLineText(PATH, line.rightLine()), name);
      assertEquals(
          line.rightLine(), resolver.resolveRightSideLine(PATH, line.rightLine()).getAsInt());
    }

    var scan = new TreeMap<Integer, String>();
    PatchLines.parse(patch).lines().forEach(line -> scan.put(line.number(), line.text()));

    assertEquals(expected, scan, name);
    assertEquals(added, PatchCoverageResolver.addedLines(patch), name);
    assertEquals(expected, promptNumbers(patch), name);
  }

  /** The lines the annotated prompt diff numbers, by their number, without number or marker. */
  private static TreeMap<Integer, String> promptNumbers(String patch) {
    var numbered = new TreeMap<Integer, String>();
    for (String line : DiffLineNumbers.annotate(formatted(patch)).split("\n", -1)) {
      var number = NUMBERED.matcher(line);
      if (number.matches()) {
        numbered.put(Integer.parseInt(number.group(1)), number.group(3));
      }
    }
    return numbered;
  }
}
