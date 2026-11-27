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

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class FindingBasisTest {

  private static final String CI =
      """
      Checks on this commit that had completed without passing when this review started: 1

      ### test (conclusion: failure)
      Title: test
      Annotations (1 of 1):
      - .github:41 [failure] Process completed with exit code 1.
      """;

  private static final PathScopedInstructions RULES =
      new PathScopedInstructions(
          List.of(
              new PathScopedInstructions.AppliedScope(
                  "src/**", "Check nulls.", List.of("src/A.java")),
              new PathScopedInstructions.AppliedScope("docs/**", "No jargon.", List.of("docs/a"))),
          ".thrillhousebot.yml");

  private final FindingBasis basis = new FindingBasis();

  private static Finding finding(String description) {
    return new Finding(RiskLevel.LOW, "src/A.java", 10, "Title", description, null, null);
  }

  private static ReviewResponse.Finding raised() {
    return new ReviewResponse.Finding("low", "high", "src/A.java", 10, "Title", "desc", null, null);
  }

  private String render(FindingBasis from, Finding finding) {
    return from.render(finding, RULES, CiFailureCitations.parse(CI));
  }

  @Test
  void namesTheRuleTheCheckTheVerdictAndTheLearning() {
    basis.recordOutcome(raised(), "kept by the second pass");
    basis.shapedByLearning(raised(), 7);

    assertEquals(
        """
        **Basis**
        - Rule: the maintainers' rules scoped to `src/**` cover this file (from `.thrillhousebot.yml`)
        - CI: cites failing check `test`
        - Verified: kept by the second pass
        - Learning: L7
        """,
        render(basis, finding("The CI check \"test (conclusion: failure)\" fails on this.")));
  }

  @Test
  void namesAFailingTestAndItsCheckWithoutTheNamesBackticks() {
    var section =
        """
        Checks on this commit that had completed without passing when this review started: 1

        ### test (conclusion: failure)
        Failing tests named in the job log:
        - \u2716 returns exactly `slots` applications when enough are eligible (1.549323ms)
        """;

    var rendered =
        basis.render(
            finding(
                "The in-diff test 'returns exactly `slots` applications when enough are eligible'"
                    + " asserts this and fails on this commit."),
            PathScopedInstructions.NONE,
            CiFailureCitations.parse(section));

    assertEquals(
        """
        **Basis**
        - CI: cites failing test `returns exactly slots applications when enough are eligible` \
        of check `test`
        """,
        rendered);
  }

  // #1435: the CI line carries where the cited check annotated the finding's file.
  @Test
  void namesWhereTheCitedCheckAnnotatedTheFindingsFile() {
    var section =
        """
        Checks on this commit that had completed without passing when this review started: 2

        ### test (conclusion: failure)
        Title: test
        Annotations (6 of 9):
        - src/A.java:67 [failure] WaitlistServiceTest: expected: <2> but was: <3>
        - src/A.java:67 [failure] the same line again
        - src/A.java [warning] a note on the whole file
        - src/A.javascript:3 [failure] another file whose path only starts the same
        - src/Other.java:5 [failure] another file
        - .github:41 [failure] Process completed with exit code 1.
        Failing tests named in the job log:
        - src/A.java:99 is a test line, not an annotation

        ### lint (conclusion: failure)
        Annotations (1 of 1):
        - src/A.java:12 [failure] a check the finding does not cite
        """;

    assertEquals(
        "**Basis**\n- CI: cites failing check `test`; annotated at `src/A.java:67`,"
            + " `src/A.java`\n",
        basis.render(
            finding("The CI check \"test (conclusion: failure)\" fails on this."),
            PathScopedInstructions.NONE,
            CiFailureCitations.parse(section)));
  }

  @Test
  void namesAtMostThreeAnnotationLocations() {
    var section =
        """
        ### test (conclusion: failure)
        Annotations (4 of 4):
        - src/A.java:1 [failure] one
        - src/A.java:2 [failure] two
        - src/A.java:3 [failure] three
        - src/A.java:4 [failure] four
        """;

    assertEquals(
        "**Basis**\n- CI: cites failing check `test`; annotated at `src/A.java:1`,"
            + " `src/A.java:2`, `src/A.java:3`\n",
        basis.render(
            finding("The CI check \"test (conclusion: failure)\" fails on this."),
            PathScopedInstructions.NONE,
            CiFailureCitations.parse(section)));
  }

  @Test
  void readsAnAnnotationLocationOnlyAsAPathAndALineNumber() {
    var section =
        """
        ### test (conclusion: failure)
        Annotations (3 of 3):
        - src/A.java:x [failure] a colon that opens no line number
        - src/A.java:
        - src/A.java:8
        """;

    assertEquals(
        "**Basis**\n- CI: cites failing check `test`; annotated at `src/A.java:8`\n",
        basis.render(
            finding("The CI check \"test (conclusion: failure)\" fails on this."),
            PathScopedInstructions.NONE,
            CiFailureCitations.parse(section)));
  }

  @Test
  void aCheckNamedOnlyHasNoAnnotationLocation() {
    var section =
        """
        Also failing, not detailed:
        - build
        Annotations (1 of 1):
        - src/A.java:5 [failure] a list no detailed check opens
        """;

    assertEquals(
        "**Basis**\n- CI: cites failing check `build`\n",
        basis.render(
            finding("The CI check \"build\" fails on this."),
            PathScopedInstructions.NONE,
            CiFailureCitations.parse(section)));
  }

  @Test
  void aFindingWithNoFileGetsNoAnnotationLocation() {
    var section =
        """
        ### test (conclusion: failure)
        Annotations (1 of 1):
        - src/A.java:1 [failure] one
        """;

    for (var file : new String[] {null, " "}) {
      var nowhere =
          new Finding(
              RiskLevel.LOW,
              file,
              10,
              "Title",
              "The CI check \"test (conclusion: failure)\" fails on this.",
              null,
              null);

      assertEquals(
          "**Basis**\n- CI: cites failing check `test`\n",
          basis.render(nowhere, PathScopedInstructions.NONE, CiFailureCitations.parse(section)));
    }
  }

  @Test
  void aFindingWithoutADescriptionStillCarriesItsVerdict() {
    basis.recordOutcome(raised(), "kept by the second pass");

    assertEquals(
        "**Basis**\n- Verified: kept by the second pass\n",
        basis.render(finding(null), PathScopedInstructions.NONE, CiFailureCitations.NONE));
  }

  @Test
  void saysAnUnscreenedFindingWasNotScreened() {
    var rendered =
        basis.render(
            finding("desc\n\n" + FindingVerificationService.UNVERIFIED_NOTE),
            PathScopedInstructions.NONE,
            CiFailureCitations.NONE);

    assertEquals("**Basis**\n- Verified: not screened by the second pass\n", rendered);
  }

  @Test
  void isBlankWhenTheReviewEstablishedNothing() {
    assertEquals("", basis.render(finding("desc"), null, CiFailureCitations.NONE));
    assertEquals(
        "", basis.render(finding("desc"), PathScopedInstructions.NONE, CiFailureCitations.NONE));
  }

  @Test
  void theDiscardingBasisKeepsNothingAndAFindingWithNoFileIsSkipped() {
    var nowhere = new ReviewResponse.Finding("low", "high", null, 1, "Title", "d", null, null);

    FindingBasis.DISCARD.recordOutcome(raised(), "kept by the second pass");
    FindingBasis.DISCARD.shapedByLearning(raised(), 1);
    basis.recordOutcome(nowhere, "kept by the second pass");
    basis.shapedByLearning(nowhere, 1);

    for (var from : List.of(basis, FindingBasis.DISCARD)) {
      assertEquals(
          "", from.render(finding("desc"), PathScopedInstructions.NONE, CiFailureCitations.NONE));
    }
  }
}
