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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import org.junit.jupiter.api.Test;

/** {@link CiFailureCitations}: which findings cite a check the CI-failure section records. */
class CiFailureCitationsTest {

  /** The section round 12 carried on ThrillhouseBot-test#155, as the resolver renders it. */
  private static final String ROUND_12_SECTION =
      """
      Checks on this commit that had completed without passing when this review started: 1

      ### test (conclusion: failure)
      Title: test
      Annotations (1 of 1):
      - .github:41 [failure] Process completed with exit code 1.
      """;

  /** The round-12 angular finding (4149712189), posted at low confidence. */
  private static final ReviewResponse.Finding ANGULAR_OFF_BY_ONE =
      new ReviewResponse.Finding(
          "high",
          "low",
          "angular/src/app/shift-board.ts",
          45,
          "Capacity check off by one: full shift still accepts an extra sign-up",
          "The guard is `if (shift.signups.length > shift.capacity) {`. The in-diff test \"a full"
              + " shift rejects further sign-ups\" exercises exactly this path and the assertion"
              + " fails. This matches the CI check on this commit: \"test (conclusion: failure)\""
              + " with annotation \".github:41 [failure] Process completed with exit code 1.\"",
          null,
          null);

  private static ReviewResponse.Finding described(String description) {
    return new ReviewResponse.Finding(
        "high", "low", "src/queue.zig", 27, "Copy limit off by one", description, null, null);
  }

  @Test
  void aFindingThatCitesTheFailingCheckIsRecognizedAndNamed() {
    var citations = CiFailureCitations.parse(ROUND_12_SECTION);

    assertTrue(citations.cites(ANGULAR_OFF_BY_ONE));
    var note = citations.noteFor(ANGULAR_OFF_BY_ONE);
    assertTrue(note.contains("records \"test\" as completed without passing"), note);
    assertTrue(note.contains("judged on the code"), note);
  }

  /** The zig finding (4149695274) names the check its own way: the "test" check failed. */
  @Test
  void theCheckCanBeNamedInTheFindingsOwnWords() {
    var zig =
        described(
            "The added test \"submit accepts copies equal to the configured maximum\" fails, and"
                + " the CI for this commit already shows it: the \"test\" check (conclusion:"
                + " failure).");

    assertTrue(CiFailureCitations.parse(ROUND_12_SECTION).cites(zig));
  }

  /** A failing check the section does not record cites nothing: "CI fails" buys no floor. */
  @Test
  void aCheckTheSectionDoesNotRecordIsNotCited() {
    var lint = described("The lint check fails in CI on this line.");

    var citations = CiFailureCitations.parse(ROUND_12_SECTION);
    assertFalse(citations.cites(lint));
    assertNull(citations.noteFor(lint));
  }

  /**
   * A check named "test" is a word every test-failure claim uses, so only the check named as a
   * check — quoted, or beside "check", "conclusion" or "job" — is a citation, and only together
   * with a failure.
   */
  @Test
  void aNameAloneIsNotACitation() {
    var citations = CiFailureCitations.parse(ROUND_12_SECTION);

    assertFalse(citations.cites(described("The new test asserts the wrong capacity.")));
    assertFalse(citations.cites(described("The test fails when copies equals the maximum.")));
    assertFalse(
        citations.cites(
            described("The added e2e test would fail in CI because the fallback never runs.")));
    assertFalse(citations.cites(described("The \"test\" check passes on this commit.")));
    assertTrue(citations.cites(described("The test job fails on this line.")));
    assertTrue(citations.cites(described("CI's `test` run fails here.")));
    assertTrue(citations.cites(described("The \u201Ctest\u201D run fails here.")));
    assertFalse(
        citations.cites(described("The quoted \"testing harness\" fails to reset its clock.")));
    assertFalse(citations.cites(described("The 'tests' folder setup fails on Windows.")));
  }

  /** A finding that denies the failure, or sets it aside, does not cite the check. */
  @Test
  void aDeniedOrDisclaimedFailureIsNotACitation() {
    var citations = CiFailureCitations.parse(ROUND_12_SECTION);

    assertFalse(
        citations.cites(
            described(
                "The test check does not fail on this commit; the failure comes from the guard.")));
    assertFalse(citations.cites(described("This is unrelated to the failing test check.")));
    assertFalse(citations.cites(described("The test check doesn't fail on this commit.")));
    assertFalse(citations.cites(described("The test check won\u2019t fail for this line.")));
  }

  @Test
  void checksTheSectionDidNotDetailAreRecordedToo() {
    var section =
        ROUND_12_SECTION + "\nAlso failing, not detailed: lint / eslint, build (windows-latest)\n";

    var citations = CiFailureCitations.parse(section);

    assertTrue(citations.cites(described("The eslint step of the lint / eslint check failed.")));
    assertTrue(citations.cites(described("The build (windows-latest) check fails in CI.")));
    assertTrue(citations.cites(described("The lint and eslint checks fail in CI on this line.")));
    assertFalse(citations.cites(described("Lint and eslint both fail on this line.")));
  }

  /**
   * The section is read back by the strings {@link CiFailureContextResolver} renders it with: a
   * section that resolver actually built records every check, detailed or only named.
   */
  @Test
  void aSectionTheResolverRendersIsReadBack() {
    var failed = new java.util.ArrayList<CiStatusEvaluator.FailedCheck>();
    for (var name : java.util.List.of("unit", "lint", "e2e", "docs", "build", "native image")) {
      failed.add(new CiStatusEvaluator.FailedCheck(name, "failure", 0L, null, null, null, 0));
    }
    var section =
        new CiFailureContextResolver(null, null, null, true, false, 20_000)
            .resolve(
                "a",
                "o",
                "r",
                new CiStatusEvaluator.CiEvaluation(
                    java.util.List.of(), false, true, new CiStatusEvaluator.CiFailures(failed, 0)));

    var citations = CiFailureCitations.parse(section);

    assertTrue(citations.cites(described("The e2e check fails in CI.")), section);
    assertTrue(citations.cites(described("The native image job fails in CI.")), section);
  }

  @Test
  void aBlankOrNamelessSectionRecordsNothing() {
    assertSame(CiFailureCitations.NONE, CiFailureCitations.parse(null));
    assertSame(CiFailureCitations.NONE, CiFailureCitations.parse("  "));
    assertSame(CiFailureCitations.NONE, CiFailureCitations.parse("### ··· (conclusion: failure)"));
    assertSame(CiFailureCitations.NONE, CiFailureCitations.parse("### (conclusion: failure)"));
    assertFalse(CiFailureCitations.NONE.cites(ANGULAR_OFF_BY_ONE));
    assertNull(CiFailureCitations.NONE.noteFor(ANGULAR_OFF_BY_ONE));
  }

  @Test
  void aFindingWithNoTextCitesNothing() {
    var bare = new ReviewResponse.Finding("high", "low", "a.ts", 1, null, null, null, null);

    assertFalse(CiFailureCitations.parse(ROUND_12_SECTION).cites(bare));
  }

  /** Check names are output the pull request controls, so a note repeats a bounded one. */
  @Test
  void aLongCheckNameIsClippedInTheNote() {
    String name = "x".repeat(400);
    var citations = CiFailureCitations.parse("### " + name + " (conclusion: failure)\n");

    var note = citations.noteFor(described("The CI check " + name + " fails."));

    assertEquals(
        CiFailureCitations.MAX_NAME_CHARS,
        note.substring(note.indexOf('"') + 1, note.indexOf('"', note.indexOf('"') + 1)).length());
  }
}
