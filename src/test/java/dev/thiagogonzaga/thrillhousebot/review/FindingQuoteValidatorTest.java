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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class FindingQuoteValidatorTest {

  private final FindingQuoteValidator validator = new FindingQuoteValidator();

  private static final String DIFF =
      """
      diff --git a/src/Main.java b/src/Main.java
      --- a/src/Main.java
      +++ b/src/Main.java
      @@ -1,4 +1,4 @@
       public class Main {
      -    var repos = new ArrayList<RepoRef>();
      +    var repos = new ArrayList<RepoRef>(snapshot);
           SHA="${{ steps.meta.outputs.sha }}"
       }
      """;

  private static ReviewResponse.Finding finding(String suggestionOld) {
    return new ReviewResponse.Finding(
        "critical", "high", "src/Main.java", 2, "Title", "Description", suggestionOld, "fixed");
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    return new ReviewResponse(
        List.of(findings),
        List.of(),
        new ReviewResponse.Summary(
            findings.length, findings.length, 0, 0, 0, "assessment", "purpose", List.of()));
  }

  /**
   * Quotes that match the diff (regardless of indentation, diff markers, or embedded blank lines)
   * and findings without a quote at all must pass through untouched.
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "var repos = new ArrayList<RepoRef>();",
        "SHA=\"${{ steps.meta.outputs.sha }}\"",
        "public class Main {\n\n    var repos = new ArrayList<RepoRef>();",
        "   \n  "
      })
  void keepsFindingsWhoseQuoteMatchesOrIsAbsent(String suggestionOld) {
    var response = response(finding(suggestionOld));

    assertSame(response, validator.validate(response, DIFF));
  }

  @Test
  void dropsFindingWhoseQuoteIsNowhereInTheDiff() {
    // The phantom-quote case from dogfooding: single braces where the file has double braces
    var response = response(finding("SHA=\"${ steps.meta.outputs.sha }\""));

    var result = validator.validate(response, DIFF);

    assertEquals(0, result.findings().size());
    assertEquals(0, result.summary().totalFindings());
    assertEquals(0, result.summary().critical());
  }

  @Test
  void stripsSuggestionAndCapsConfidenceOnPartialQuote() {
    var response =
        response(finding("var repos = new ArrayList<RepoRef>();\nthis line was never written"));

    var result = validator.validate(response, DIFF);

    assertEquals(1, result.findings().size());
    var kept = result.findings().get(0);
    assertNull(kept.suggestionOld());
    assertNull(kept.suggestionNew());
    assertEquals("low", kept.confidence());
    assertEquals("critical", kept.risk());
  }

  @Test
  void skipsTriviallyEmptyInputs() {
    var noFindings = response();
    assertSame(noFindings, validator.validate(noFindings, DIFF));

    var withFinding = response(finding("anything"));
    assertSame(withFinding, validator.validate(withFinding, null));
    assertSame(withFinding, validator.validate(withFinding, "  "));
  }

  @Test
  void fileHeaderAndHunkLinesAreNotMatchableContent() {
    // "+++ b/src/Main.java" must not let a finding quoting it survive
    var response = response(finding("+++ b/src/Main.java"));

    var result = validator.validate(response, DIFF);

    assertEquals(0, result.findings().size());
  }

  @Test
  void mixedResponseKeepsValidFindingsAndDropsPhantoms() {
    var valid = finding("var repos = new ArrayList<RepoRef>();");
    var phantom = finding("code that was never written");

    var result = validator.validate(response(valid, phantom), DIFF);

    assertEquals(1, result.findings().size());
    assertSame(valid, result.findings().get(0));
  }

  @Test
  void quotesOfNeutralizedMarkerContentStillMatch() {
    // The prompt pipeline shows the model <<DIFF_END>> wherever the file says <<<DIFF_END>>>,
    // so the model's quote uses the two-bracket form and must still validate
    var diff =
        """
        +++ b/src/Probe.java
        @@ -1,1 +1,1 @@
        +    repos.add("alpha <<<DIFF_END>>> gamma");
        """;
    var response = response(finding("repos.add(\"alpha <<DIFF_END>> gamma\");"));

    assertSame(response, validator.validate(response, diff));
  }

  private static final String MULTI_FILE_DIFF =
      """
      ## Overview: 2 files (+2 -0)

      ### src/A.java (modified, +1 -0)
      ```diff
      @@ -1,1 +1,2 @@
       class A {
      +    int onlyInA = 1;
      ```

      ### nested/path/B.java (modified, +1 -0)
      ```diff
      @@ -1,1 +1,2 @@
       class B {
      +    int onlyInB = 2;
      ```
      """;

  private static ReviewResponse.Finding findingOn(String file, String suggestionOld) {
    return new ReviewResponse.Finding(
        "critical", "high", file, 2, "Title", "Description", suggestionOld, "fixed");
  }

  /**
   * Misattributed quotes are dropped no matter how the model writes the path: exact, shortened, or
   * expanded relative to the diff's section header.
   */
  @ParameterizedTest
  @CsvSource({
    "src/A.java, int onlyInB = 2;",
    "path/B.java, int onlyInA = 1;",
    "repo/nested/path/B.java, int onlyInA = 1;"
  })
  void misattributedQuotesAreDroppedForAnyPathForm(String file, String quote) {
    var result = validator.validate(response(findingOn(file, quote)), MULTI_FILE_DIFF);

    assertEquals(0, result.findings().size());
  }

  @Test
  void quoteInTheFindingsOwnFileIsKept() {
    var response = response(findingOn("src/A.java", "int onlyInA = 1;"));

    assertSame(response, validator.validate(response, MULTI_FILE_DIFF));
  }

  @Test
  void unknownFileFallsBackToTheWholeDiff() {
    var response = response(findingOn("docs/somewhere-else.md", "int onlyInB = 2;"));

    assertSame(response, validator.validate(response, MULTI_FILE_DIFF));
  }

  @Test
  void findingWithoutFileFallsBackToTheWholeDiff() {
    var response = response(findingOn(null, "int onlyInB = 2;"));

    assertSame(response, validator.validate(response, MULTI_FILE_DIFF));
  }

  private static final String AMBIGUOUS_DIFF =
      """
      ### module-a/Handler.java (modified, +1 -0)
      ```diff
      +    int inModuleA = 1;
      ```

      ### module-b/Handler.java (modified, +1 -0)
      ```diff
      +    int inModuleB = 2;
      ```

      ### other/C.java (modified, +1 -0)
      ```diff
      +    int inC = 3;
      ```
      """;

  @Test
  void ambiguousPathScopesToItsCandidatesNotTheWholeDiff() {
    // Handler.java matches two sections; a quote from an unrelated third file must not pass
    var misattributed = findingOn("Handler.java", "int inC = 3;");

    var result = validator.validate(response(misattributed), AMBIGUOUS_DIFF);

    assertEquals(0, result.findings().size());
  }

  @Test
  void ambiguousPathStillAcceptsQuotesFromAnyCandidate() {
    var response = response(findingOn("Handler.java", "int inModuleB = 2;"));

    assertSame(response, validator.validate(response, AMBIGUOUS_DIFF));
  }

  @Test
  void sectionHeaderWithoutStatsSuffixStillScopes() {
    var diff =
        """
        ### src/Plain.java
        ```diff
        +    int plainOnly = 3;
        ```

        ### src/Other.java
        ```diff
        +    int otherOnly = 4;
        ```
        """;
    var misattributed = findingOn("src/Plain.java", "int otherOnly = 4;");

    var result = validator.validate(response(misattributed), diff);

    assertEquals(0, result.findings().size());
  }

  @Test
  void survivesNullSummary() {
    var response = new ReviewResponse(List.of(finding("not in the diff at all")), List.of(), null);

    var result = validator.validate(response, DIFF);

    assertEquals(0, result.findings().size());
    assertNull(result.summary());
  }
}
