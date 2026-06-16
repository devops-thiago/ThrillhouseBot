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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

  @Test
  void ambiguousQuoteCapsConfidenceAndDropsSuggestion() {
    var diff =
        """
        ### src/Test.java (modified, +2 -2)
        ```diff
        @@ -10,6 +10,6 @@
         public void testOne() {
        -    assertEquals(1, val);
        +    assertEquals(2, val);
         }
         public void testTwo() {
        -    assertEquals(1, val);
        +    assertEquals(2, val);
         }
        ```
        """;
    // The two occurrences sit at right-side lines 11 and 14, so their 3-line tolerance windows
    // ([8,14] and [11,17]) overlap. The finding at line 12 falls inside both, uniquely selecting
    // neither — so the outcome depends on correct right-side line tracking, not mere distance.
    var finding =
        new ReviewResponse.Finding(
            "critical",
            "high",
            "src/Test.java",
            12,
            "Title",
            "Description",
            "assertEquals(1, val);",
            "assertEquals(2, val);");
    var response = response(finding);

    var result = validator.validate(response, diff);

    assertEquals(1, result.findings().size());
    var kept = result.findings().get(0);
    assertNull(kept.suggestionOld());
    assertNull(kept.suggestionNew());
    assertEquals("low", kept.confidence());
  }

  private static final String DUPLICATE_QUOTE_DIFF =
      """
      ### src/Test.java (modified, +2 -2)
      ```diff
      @@ -10,3 +10,3 @@
       public void testOne() {
      -    assertEquals(1, val);
      +    assertEquals(2, val);
       }
      @@ -20,3 +20,3 @@
       public void testTwo() {
      -    assertEquals(1, val);
      +    assertEquals(2, val);
       }
      ```
      """;

  // Same duplicate quote, but each hunk header carries git's trailing function-context suffix.
  private static final String DUPLICATE_QUOTE_DIFF_TRAILING_HEADER =
      """
      ### src/Test.java (modified, +2 -2)
      ```diff
      @@ -10,3 +10,3 @@ public void testOne() {
       public void testOne() {
      -    assertEquals(1, val);
      +    assertEquals(2, val);
       }
      @@ -20,3 +20,3 @@ public void testTwo() {
       public void testTwo() {
      -    assertEquals(1, val);
      +    assertEquals(2, val);
       }
      ```
      """;

  static Stream<Arguments> uniquelySelectedDuplicateQuotes() {
    return Stream.of(
        // line 21 uniquely selects testTwo — the upper occurrence's tolerance window
        arguments(DUPLICATE_QUOTE_DIFF, 21),
        // line 11 selects the earlier testOne — exercises the lower-bound edge of the window
        arguments(DUPLICATE_QUOTE_DIFF, 11),
        // trailing context after @@ must still parse the +start, so line 21 selects testTwo
        arguments(DUPLICATE_QUOTE_DIFF_TRAILING_HEADER, 21));
  }

  /**
   * When a quote appears in multiple locations but the finding's line uniquely selects one (within
   * the 3-line tolerance), the finding is kept intact regardless of the hunk-header form.
   */
  @ParameterizedTest
  @MethodSource("uniquelySelectedDuplicateQuotes")
  void uniqueQuoteAmongDuplicatesIsKept(String diff, int line) {
    var finding =
        new ReviewResponse.Finding(
            "critical",
            "high",
            "src/Test.java",
            line,
            "Title",
            "Description",
            "assertEquals(1, val);",
            "assertEquals(2, val);");

    var result = validator.validate(response(finding), diff);

    assertEquals(1, result.findings().size());
    var kept = result.findings().get(0);
    assertEquals("assertEquals(1, val);", kept.suggestionOld());
    assertEquals("high", kept.confidence());
  }

  @Test
  void quoteWithInterleavedAdditionsIsKept() {
    var diff =
        """
        ### src/Test.java (modified, +1 -0)
        ```diff
        @@ -10,4 +10,5 @@
         public void run() {
             stepOne();
        +    insertedStep();
             stepTwo();
         }
        ```
        """;
    // The quote has "stepOne();" and "stepTwo();" which are separated by "+    insertedStep();" in
    // the diff.
    // Since "+    insertedStep();" is an addition, it should be ignored when matching
    // suggestionOld.
    var quote = "stepOne();\nstepTwo();";
    var finding =
        new ReviewResponse.Finding(
            "critical", "high", "src/Test.java", 11, "Title", "Description", quote, "replacement");
    var response = response(finding);

    var result = validator.validate(response, diff);

    assertEquals(1, result.findings().size());
    var kept = result.findings().get(0);
    assertEquals(quote, kept.suggestionOld());
    assertEquals("high", kept.confidence());
  }

  @Test
  void ambiguousAcrossFilesWhenFindingFileUnresolved() {
    var diff =
        """
        ### src/A.java (modified, +1 -1)
        ```diff
        @@ -10,3 +10,3 @@
         public void a() {
        -    doThing();
        +    doThingElse();
         }
        ```
        ### src/B.java (modified, +1 -1)
        ```diff
        @@ -10,3 +10,3 @@
         public void b() {
        -    doThing();
        +    doThingElse();
         }
        ```
        """;
    // The finding's file matches no section, so scoping falls back to the whole-diff union where
    // "doThing();" appears in both files at right-side line 11 (each file restarts its own
    // numbering). Line 11 sits in both tolerance windows, so neither is uniquely selected.
    var finding =
        new ReviewResponse.Finding(
            "critical",
            "high",
            "src/Unknown.java",
            11,
            "Title",
            "Description",
            "doThing();",
            "doThingElse();");
    var response = response(finding);

    var result = validator.validate(response, diff);

    assertEquals(1, result.findings().size());
    var kept = result.findings().get(0);
    assertNull(kept.suggestionOld());
    assertEquals("low", kept.confidence());
  }

  @Test
  void unparseableHunkHeaderIsIgnored() {
    var diff =
        """
        ### src/Test.java (modified, +1 -1)
        ```diff
        @@ this is not a valid hunk header @@
         public void run() {
        -    doThing();
        +    doThingElse();
         }
        ```
        """;
    // A line that starts with @@ but carries no parseable +start leaves right-side numbering at its
    // default; the single quoted occurrence has no duplicate to disambiguate and is kept.
    var finding =
        new ReviewResponse.Finding(
            "critical",
            "high",
            "src/Test.java",
            1,
            "Title",
            "Description",
            "doThing();",
            "doThingElse();");
    var response = response(finding);

    var result = validator.validate(response, diff);

    assertEquals(1, result.findings().size());
    assertEquals("doThing();", result.findings().get(0).suggestionOld());
  }

  @Test
  void blankFileFallsBackToTheWholeDiff() {
    var response = response(findingOn("   ", "int onlyInB = 2;"));

    assertSame(response, validator.validate(response, MULTI_FILE_DIFF));
  }

  private static final String DESCRIPTION_DIFF =
      """
      ### DashboardAccessChecker.java (modified, +1 -1)
      ```diff
      @@ -220,3 +220,3 @@
       public List<RepoRef> installedRepos(String accountOwner) {
      -    return repos.stream().filter(r -> r.owner().equals(accountOwner)).toList();
      +    return repos.stream().filter(r -> r.owner().equalsIgnoreCase(accountOwner)).toList();
       }
      ```
      """;

  private static final String MATCHED_OLD =
      "return repos.stream().filter(r -> r.owner().equals(accountOwner)).toList();";

  private static ReviewResponse.Finding describedFinding(String suggestionOld, String description) {
    return new ReviewResponse.Finding(
        "medium",
        "high",
        "DashboardAccessChecker.java",
        221,
        "Potential NullPointerException when accountOwner is null",
        description,
        suggestionOld,
        "fixed");
  }

  /**
   * The PR #101 dogfood case: {@code suggestion_old} quotes the real changed line (a FULL match)
   * but the description's supporting mechanism is a chained call that exists nowhere. The finding
   * is kept but demoted — suggestion stripped, confidence capped — exactly like a partial quote.
   */
  @Test
  void demotesFindingWhenDescriptionCitesPhantomChainedCall() {
    var finding =
        describedFinding(
            MATCHED_OLD,
            "accountOwner is obtained from `dashboardConfig.accountOwner().orElse(null)` in"
                + " evaluateAccess(), so it can be null here.");
    var response = response(finding);

    var result = validator.validate(response, DESCRIPTION_DIFF);

    assertEquals(1, result.findings().size());
    var kept = result.findings().get(0);
    assertNull(kept.suggestionOld());
    assertNull(kept.suggestionNew());
    assertEquals("low", kept.confidence());
    assertEquals("medium", kept.risk());
  }

  /**
   * A description that cites no absent chained call leaves the finding untouched: an absent (null
   * or blank) description, a chained call genuinely in the diff, a bare method name, or a dotted
   * field access without a call all pass through unchanged. This also covers #120 face (b): a full
   * nested lambda chain present in the diff is one whole citation (its inner predicate is not
   * surfaced separately), and malformed candidates — unbalanced parentheses or an unterminated
   * string literal — consume no argument list and are never flagged.
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        // blank: no citations to check
        "   ",
        // chained call genuinely present in the diff
        "The filter `r.owner().equals(accountOwner)` is case-sensitive, which misses matches.",
        // bare method names are not distinctive enough to flag against the diff window
        "accountOwner flows from installedRepos() and evaluateAccess(); it may be null.",
        // a dotted field access without a call is ignored
        "The configured `dashboardConfig.accountOwner` default is applied here.",
        // face (b): the full lambda chain present in the diff is one citation, kept whole
        "The filter `repos.stream().filter(r -> r.owner().equals(accountOwner)).toList()` is"
            + " case-sensitive, which misses matches.",
        // unbalanced parentheses consume no argument list, so nothing distinctive is flagged
        "The incomplete call `obj.process(arg` appears only in the prose.",
        // an unterminated string literal swallows the remainder, so no call chain is consumed
        "It mentions `obj.run(\"oops` without ever closing the quote.",
        // a null-guard clause cites no call chain, so the present chain still matches and is kept
        "Guard `owner == null || accountOwner == null` before"
            + " `r.owner().equals(accountOwner)`, which is present.",
        // a present chain wrapped in a bare-name call: the inner chain is descended into and found
        // in the diff, so the finding is not over-demoted
        "The value `requireNonNull(r.owner().equals(accountOwner))` is recomputed each call."
      })
  void keepsFindingWhenDescriptionHasNoAbsentChainedCall(String description) {
    var response = response(describedFinding(MATCHED_OLD, description));

    assertSame(response, validator.validate(response, DESCRIPTION_DIFF));
  }

  /** A phantom citation demotes even a finding that carries no suggestion_old of its own. */
  @Test
  void demotesQuotelessFindingWhenDescriptionCitesPhantomChainedCall() {
    var finding =
        describedFinding(
            null,
            "The value from `config.lookup().orElse(null)` is never null-checked before use.");
    var response = response(finding);

    var result = validator.validate(response, DESCRIPTION_DIFF);

    assertEquals(1, result.findings().size());
    assertEquals("low", result.findings().get(0).confidence());
  }

  /**
   * The NO_QUOTE keep-branch: a finding with no suggestion_old whose description cites no absent
   * code (a present chained call, or none at all) passes through untouched — distinct from the
   * FULL-arm keep tests, which all carry a matching suggestion_old.
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        // chained call genuinely present in the diff
        "The filter `r.owner().equals(accountOwner)` is case-sensitive, which misses matches.",
        // no chained call at all
        "accountOwner may be null at this point."
      })
  void keepsQuotelessFindingWhenDescriptionHasNoAbsentChainedCall(String description) {
    var response = response(describedFinding(null, description));

    assertSame(response, validator.validate(response, DESCRIPTION_DIFF));
  }

  /**
   * #120 face (a): a citation whose argument is itself a call or a lambda (nested parentheses) used
   * to be either truncated to a paren-less fragment and dropped unchecked, or split so the chain's
   * fabricated tail was never seen — in both cases the phantom escaped at full confidence. The
   * whole chain is now extracted as one citation and, being absent from the diff, is demoted.
   * Covers a nested-call argument, a lambda chain sharing a real prefix but ending in an invented
   * tail, a receiver that is itself a call, and a string literal whose escaped quote must not end
   * it early.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // nested-call argument: was truncated to the paren-less "config.lookup" and dropped
        "accountOwner comes from `config.lookup(getKey()).orElse(null)`, so it may be null.",
        // lambda chain sharing a real prefix; the old split missed the invented .orElseThrow tail
        "It evaluates `repos.stream().filter(r -> r.owner().equals(accountOwner))"
            + ".orElseThrow(IllegalStateException::new)` on every request.",
        // the receiver itself takes arguments
        "The value `getConfig().lookup(key).orElse(null)` is never null-checked before use.",
        // an escaped quote inside the string-literal argument must not terminate the literal early,
        // and the receiver name exercises underscore/digit identifier characters
        "It validates with `pattern_2.match(\"a\\\"b\").orElseThrow()` before continuing.",
        // a char-literal slash holds no real bracket, a leading dollar sign is a valid identifier
        // start, and the sentence break exercises a dot that is not followed by an identifier
        "Two call sites exist. The `path.split('/')` output (the $tmp local) is never checked.",
        // a lone apostrophe inside the cited call's arguments must not swallow the rest of the
        // citation: the phantom chain is still extracted whole and, being absent, demoted
        "accountOwner is `dashboardConfig.lookup(the user's key).orElse(null)`, which may be null.",
        // a phantom chain wrapped in a bare-name call must still be checked: the wrapper itself is
        // not a citation, but the inner config.fetch(accountId) is extracted and, being absent,
        // demoted
        "It is wrapped as `requireNonNull(config.fetch(accountId))`, which can throw."
      })
  void demotesFindingWhenDescriptionCitesAbsentNestedCallChain(String description) {
    var result =
        validator.validate(response(describedFinding(MATCHED_OLD, description)), DESCRIPTION_DIFF);

    assertEquals(1, result.findings().size());
    var kept = result.findings().get(0);
    assertNull(kept.suggestionOld());
    assertNull(kept.suggestionNew());
    assertEquals("low", kept.confidence());
    assertEquals("medium", kept.risk());
  }

  private static final String LITERAL_DIFF =
      """
      ### src/Router.java (modified, +1 -1)
      ```diff
      @@ -3,3 +3,3 @@
       void route() {
      -    handlers.put(label(")"), fallback).register();
      +    handlers.put(label(")"), primary).register();
       }
      ```
      """;

  /**
   * A parenthesis inside a string literal must not be counted as a bracket: the {@code ")"} literal
   * argument is spanned whole, so a present chain that embeds it matches the diff and is kept. A
   * naive depth counter would truncate the citation at the literal's parenthesis and wrongly
   * demote.
   */
  @Test
  void keepsFindingWhenDescriptionCitesPresentChainWithLiteralParenArgument() {
    var finding =
        new ReviewResponse.Finding(
            "medium",
            "high",
            "src/Router.java",
            4,
            "Title",
            "It registers via `handlers.put(label(\")\"), fallback).register()` at startup.",
            "handlers.put(label(\")\"), fallback).register();",
            "fixed");
    var response = response(finding);

    assertSame(response, validator.validate(response, LITERAL_DIFF));
  }

  /**
   * Mechanism (a), #121: a chain the description cites that is also the finding's own {@code
   * suggestion_new} is the proposed fix — a hypothetical, absent from the diff by definition. The
   * chain is still extracted, but since it is absent from {@code DESCRIPTION_DIFF} and restated in
   * {@code suggestion_new}, it is not counted as a fabricated mechanism (the deterministic form of
   * #106's "existing code, not the suggested fix"), so a finding that describes its own fix is not
   * demoted. Its absence from the diff is what makes the {@code suggestion_new} restatement the
   * only thing keeping it.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "The corrected expression is `%s`, which handles the null owner.",
        "Guard the dereference; the fix is `%s` applied at the call site."
      })
  void keepsFindingWhenDescriptionRestatesItsSuggestionNew(String descriptionTemplate) {
    var fix = "Optional.ofNullable(accountOwner).map(Owner::name).orElse(null)";
    var finding =
        new ReviewResponse.Finding(
            "medium",
            "high",
            "DashboardAccessChecker.java",
            221,
            "Potential NullPointerException when accountOwner is null",
            descriptionTemplate.formatted(fix),
            MATCHED_OLD,
            fix);
    var response = response(finding);

    assertSame(response, validator.validate(response, DESCRIPTION_DIFF));
  }

  /**
   * Mechanism (b), #121: a description that cites a present core mechanism keeps its suggestion
   * even when an incidental off-diff helper is cited <em>first</em> — the guard must not
   * short-circuit to a demotion on the first absent chain.
   */
  @Test
  void keepsFindingWhenCoreCitedChainIsPresentDespiteOffDiffHelper() {
    var finding =
        describedFinding(
            MATCHED_OLD,
            "The off-diff helper `logger.atDebug().log()` traces it, but"
                + " `r.owner().equals(accountOwner)` is the case-sensitive filter that misses"
                + " matches.");
    var response = response(finding);

    assertSame(response, validator.validate(response, DESCRIPTION_DIFF));
  }

  /**
   * Mechanism (b), #121: when EVERY cited chain is absent the finding is still demoted — the
   * all-absent semantics must not be inverted into "keep if any chain is absent".
   */
  @Test
  void demotesFindingWhenEveryCitedChainIsAbsent() {
    var finding =
        describedFinding(
            MATCHED_OLD,
            "The mechanism `cfg.first().orElse(null)` and `cfg.second().get()` are both dereferenced"
                + " unguarded.");
    var response = response(finding);

    var result = validator.validate(response, DESCRIPTION_DIFF);

    assertEquals(1, result.findings().size());
    var kept = result.findings().get(0);
    assertNull(kept.suggestionOld());
    assertNull(kept.suggestionNew());
    assertEquals("low", kept.confidence());
  }

  /**
   * Mechanism (a)+(b) interaction, #121: a chain that is BOTH verbatim-present in the diff AND
   * restated in {@code suggestion_new} (the common "the fix wraps the existing call" shape) must
   * not be subtracted as the proposed fix — its diff presence grounds the finding. Naming a
   * separate off-diff helper alongside it must not demote. Diff presence wins over fix-restatement.
   */
  @Test
  void keepsFindingWhenPresentChainIsAlsoRestatedInSuggestionNew() {
    var finding =
        new ReviewResponse.Finding(
            "medium",
            "high",
            "DashboardAccessChecker.java",
            221,
            "Potential NullPointerException when accountOwner is null",
            // present core chain (verbatim in the diff) cited alongside an off-diff helper
            "`r.owner().equals(accountOwner)` is case-sensitive; `cfg.opts().get()` set the owner.",
            MATCHED_OLD,
            // the proposed fix wraps the present chain, so it restates it verbatim
            "r != null && r.owner().equals(accountOwner)");
    var response = response(finding);

    assertSame(response, validator.validate(response, DESCRIPTION_DIFF));
  }

  /**
   * Mechanism (a), #121: a finding with no {@code suggestion_new} to subtract still demotes when
   * its only cited chain is a phantom. The null-suggestion path must be a no-op (nothing to
   * subtract), not an accidental keep — covering the {@code suggestionNew == null} branch.
   */
  @Test
  void demotesPhantomCitationWhenFindingHasNoSuggestionNew() {
    var finding =
        new ReviewResponse.Finding(
            "medium",
            "high",
            "DashboardAccessChecker.java",
            221,
            "Potential NullPointerException when accountOwner is null",
            "It dereferences `dashboardConfig.accountOwner().orElse(null)` unguarded.",
            MATCHED_OLD,
            null);
    var response = response(finding);

    var result = validator.validate(response, DESCRIPTION_DIFF);

    assertEquals(1, result.findings().size());
    assertEquals("low", result.findings().get(0).confidence());
  }

  @Test
  void noNewlineIndicatorIsNotQuotableContent() {
    var diff =
        """
        ### src/Test.java (modified, +1 -1)
        ```diff
        @@ -1,1 +1,1 @@
        -oldValue
        +newValue
        \\ No newline at end of file
        ```
        """;
    // The "\\ No newline at end of file" indicator is diff plumbing; a finding quoting it must not
    // validate against the diff.
    var response = response(findingOn("src/Test.java", "\\ No newline at end of file"));

    var result = validator.validate(response, diff);

    assertEquals(0, result.findings().size());
  }
}
