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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FrameworkFalsePositiveFilterTest {

  private final FrameworkFalsePositiveFilter filter = new FrameworkFalsePositiveFilter();

  /** An `@Inject`-constructor-only bean, the #270 false-positive shape. */
  private static final String INJECTED_CTOR_DIFF =
      """
      ### src/main/java/dev/example/PrSummaryGenerator.java (modified, +6 -0)
      ```diff
      @@ -10,3 +10,9 @@
       public class PrSummaryGenerator {
      +  private final AiReviewService aiReviewService;
      +
      +  @Inject
      +  public PrSummaryGenerator(AiReviewService aiReviewService) {
      +    this.aiReviewService = aiReviewService;
      +  }
       }
      ```
      """;

  private static final String NO_INJECT_DIFF =
      """
      ### src/main/java/dev/example/PrSummaryGenerator.java (modified, +1 -0)
      ```diff
      @@ -10,2 +10,3 @@
       public class PrSummaryGenerator {
      +  private final AiReviewService aiReviewService;
       }
      ```
      """;

  private static ReviewResponse.Finding finding(String file, String title, String description) {
    return new ReviewResponse.Finding("medium", "medium", file, 12, title, description, null, null);
  }

  private static ReviewResponse.Finding noArgClaim(String title) {
    return finding(
        "src/main/java/dev/example/PrSummaryGenerator.java",
        title,
        "CDI requires a bean to be proxyable; add a constructor without arguments.");
  }

  private static ReviewResponse response(ReviewResponse.Finding... findings) {
    return new ReviewResponse(
        List.of(findings),
        List.of(),
        new ReviewResponse.Summary(
            findings.length, 0, 0, findings.length, 0, "assessment", "purpose", List.of()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Missing no-arg CDI constructor",
        "Missing no-argument constructor for CDI proxying",
        "Class lacks a default constructor",
        "Bean constructor takes arguments but CDI needs a zero-arg constructor",
        "Constructor should have no args for the CDI container"
      })
  void dropsNoArgConstructorClaimWhenDiffShowsInjectedConstructor(String title) {
    var result = filter.filter(response(noArgClaim(title)), INJECTED_CTOR_DIFF);

    assertEquals(0, result.findings().size());
    assertEquals(0, result.summary().totalFindings());
    assertEquals(0, result.summary().medium());
  }

  @Test
  void dropsClaimWhenAnnotationIsInlineWithConstructorDeclaration() {
    var diff =
        """
        ### src/main/java/dev/example/PrSummaryGenerator.java (modified, +1 -0)
        ```diff
        @@ -10,2 +10,3 @@
         public class PrSummaryGenerator {
        +  @Inject public PrSummaryGenerator(AiReviewService aiReviewService) {}
         }
        ```
        """;

    var result = filter.filter(response(noArgClaim("Missing no-arg constructor")), diff);

    assertEquals(0, result.findings().size());
  }

  @Test
  void dropsClaimWhenOtherAnnotationsSitBetweenInjectAndConstructor() {
    var diff =
        """
        ### src/main/java/dev/example/PrSummaryGenerator.java (modified, +4 -0)
        ```diff
        @@ -10,2 +10,6 @@
         public class PrSummaryGenerator {
        +  @Inject
        +  @Deprecated
        +  public PrSummaryGenerator(AiReviewService aiReviewService) {
        +  }
         }
        ```
        """;

    var result = filter.filter(response(noArgClaim("Missing default constructor")), diff);

    assertEquals(0, result.findings().size());
  }

  @Test
  void dropsClaimWhenClaimTextOnlyAppearsInDescription() {
    var f =
        finding(
            "src/main/java/dev/example/PrSummaryGenerator.java",
            "CDI bean instantiation failure",
            "The container cannot instantiate this bean because it has no default constructor.");

    var result = filter.filter(response(f), INJECTED_CTOR_DIFF);

    assertEquals(0, result.findings().size());
  }

  /** Diffs that do not prove an injection-annotated constructor, so the claim must survive. */
  static Stream<Arguments> nonRefutingDiffs() {
    return Stream.of(
        arguments("no @Inject constructor in the diff", NO_INJECT_DIFF),
        arguments(
            "@Inject on a field, not a constructor",
            """
            ### src/main/java/dev/example/PrSummaryGenerator.java (modified, +2 -0)
            ```diff
            @@ -10,2 +10,4 @@
             public class PrSummaryGenerator {
            +  @Inject
            +  AiReviewService aiReviewService;
             }
            ```
            """),
        arguments(
            "the @Inject constructor was deleted by this PR",
            """
            ### src/main/java/dev/example/PrSummaryGenerator.java (modified, +0 -3)
            ```diff
            @@ -10,5 +10,2 @@
             public class PrSummaryGenerator {
            -  @Inject
            -  public PrSummaryGenerator(AiReviewService aiReviewService) {
            -  }
             }
            ```
            """),
        arguments(
            "constructor invocation, not a declaration",
            """
            ### src/main/java/dev/example/PrSummaryGenerator.java (modified, +2 -0)
            ```diff
            @@ -10,2 +10,4 @@
             public class PrSummaryGenerator {
            +  @Inject
            +  void setUp() { var x = new PrSummaryGenerator(null); }
             }
            ```
            """),
        arguments(
            "constructor beyond the annotation lookahead",
            """
            ### src/main/java/dev/example/PrSummaryGenerator.java (modified, +8 -0)
            ```diff
            @@ -10,2 +10,10 @@
             public class PrSummaryGenerator {
            +  @Inject
            +  @A
            +  @B
            +  @C
            +  @D
            +  @E
            +  public PrSummaryGenerator(AiReviewService aiReviewService) {
            +  }
             }
            ```
            """),
        arguments(
            "annotation is the last kept line",
            """
            ### src/main/java/dev/example/PrSummaryGenerator.java (modified, +1 -0)
            ```diff
            @@ -10,2 +10,3 @@
             public class PrSummaryGenerator {
            +  @Inject
            ```
            """));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nonRefutingDiffs")
  void keepsClaimWhenDiffDoesNotProveInjectedConstructor(String label, String diff) {
    var response = response(noArgClaim("Missing no-arg constructor"));

    assertSame(response, filter.filter(response, diff));
  }

  @Test
  void keepsFindingWhoseNoArgAndConstructorMentionsAreUnrelated() {
    var f =
        finding(
            "src/main/java/dev/example/PrSummaryGenerator.java",
            "Constructor stores mutable list",
            "The constructor copies its input defensively before storing it in the internal cache"
                + " field, which keeps later mutations of the caller's collection from leaking in."
                + " Elsewhere, the CLI parser accepts no args, a separate concern entirely.");
    var response = response(f);

    assertSame(response, filter.filter(response, INJECTED_CTOR_DIFF));
  }

  @Test
  void keepsClaimWhenInjectedConstructorBelongsToAnotherFile() {
    var response =
        response(
            finding(
                "src/main/java/dev/example/OtherClass.java",
                "Missing no-arg constructor",
                "OtherClass needs a default constructor."));

    assertSame(response, filter.filter(response, INJECTED_CTOR_DIFF));
  }

  @Test
  void keepsUnrelatedFindingsUntouched() {
    var unrelated =
        finding(
            "src/main/java/dev/example/PrSummaryGenerator.java",
            "Possible NPE on summary field",
            "summary may be null when the model omits it.");
    var response = response(unrelated);

    assertSame(response, filter.filter(response, INJECTED_CTOR_DIFF));
  }

  @Test
  void dropsOnlyTheRefutedFindingAndRecountsSummary() {
    var kept =
        finding(
            "src/main/java/dev/example/PrSummaryGenerator.java",
            "Possible NPE on summary field",
            "summary may be null when the model omits it.");
    var result =
        filter.filter(response(noArgClaim("Missing no-arg constructor"), kept), INJECTED_CTOR_DIFF);

    assertEquals(List.of(kept), result.findings());
    assertEquals(1, result.summary().totalFindings());
    assertEquals(1, result.summary().medium());
  }

  @ParameterizedTest
  @NullAndEmptySource
  void passesThroughOnBlankDiff(String diff) {
    var response = response(noArgClaim("Missing no-arg constructor"));

    assertSame(response, filter.filter(response, diff));
  }

  @Test
  void passesThroughWhenThereAreNoFindings() {
    var response = response();

    assertSame(response, filter.filter(response, INJECTED_CTOR_DIFF));
  }

  @Test
  void dropsClaimWhenTitleIsNullAndDescriptionMatches() {
    var f =
        finding(
            "src/main/java/dev/example/PrSummaryGenerator.java",
            null,
            "The class is missing a no-arg constructor required by CDI.");

    var result = filter.filter(response(f), INJECTED_CTOR_DIFF);

    assertEquals(0, result.findings().size());
  }

  @Test
  void keepsClaimOnExtensionOnlyFileName() {
    var response =
        response(
            finding(
                "src/.java",
                "Missing no-arg constructor",
                "The class needs a default constructor."));

    assertSame(response, filter.filter(response, INJECTED_CTOR_DIFF));
  }

  @Test
  void keepsClaimWhenFindingHasNoFile() {
    var response =
        response(
            finding(null, "Missing no-arg constructor", "The class needs a default constructor."));

    assertSame(response, filter.filter(response, INJECTED_CTOR_DIFF));
  }

  @Test
  void keepsClaimOnNonJavaFile() {
    var response =
        response(
            finding(
                "src/main/js/generator.ts",
                "Missing no-arg constructor",
                "The class needs a default constructor."));

    assertSame(response, filter.filter(response, INJECTED_CTOR_DIFF));
  }
}
