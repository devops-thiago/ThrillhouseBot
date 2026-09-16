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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContextEvidenceResolver} — attaching to a finding the review-context
 * material it rests on, so the verifier judges the measurement or the rule instead of demoting the
 * finding for resting on a section nobody handed it (#475).
 */
class ContextEvidenceResolverTest {

  private static final String PATH = "src/main/java/app/Renderer.java";

  /** The section {@code PatchCoverageResolver.render} emits, as the review pass receives it. */
  private static final String COVERAGE_SECTION =
      """
      ### Patch coverage for this diff (from the repository's own CI coverage report)
      Lines this pull request ADDS that the coverage report for this exact commit records as \
      executable and never executed by any test. Line numbers are new-file numbers, matching the \
      diff.
      - src/main/java/app/Renderer.java: 40-47, 61
      - src/main/java/app/Parser.java: 12
      - (2 more changed file(s) with uncovered added lines)""";

  private static final String RULES =
      "Every renderer must escape untrusted text before it reaches the DOM.";

  private static ReviewResponse.Finding finding(String file, int line, String description) {
    return new ReviewResponse.Finding(
        "medium", "high", file, line, "Untested branch", description, "x", "y");
  }

  private static ContextEvidenceResolver.Round round(
      String coverage, PathScopedInstructions scoped) {
    return ContextEvidenceResolver.forReview(coverage, scoped, new EvidenceBudget());
  }

  private static PathScopedInstructions scoped(String glob, String rules, String... files) {
    return new PathScopedInstructions(
        List.of(new PathScopedInstructions.AppliedScope(glob, rules, List.of(files))), ".github");
  }

  private static String evidence(ContextEvidenceResolver.Round round, ReviewResponse.Finding f) {
    return round.locate(List.of(f)).forFinding(f);
  }

  @Test
  void carriesTheMeasurementForALineTheCoverageSectionLists() {
    var finding = finding(PATH, 42, "The new fallback branch decides what the caller renders.");

    var note = evidence(round(COVERAGE_SECTION, PathScopedInstructions.NONE), finding);

    assertNotNull(note, "a finding on a measured-uncovered line must carry its measurement");
    assertTrue(note.contains("lists line 42 of `" + PATH + "`"), note);
    assertTrue(note.contains("40-47, 61"), note);
  }

  /**
   * The supporting direction does not wait to be asked: a finding that never says the word carries
   * the measurement anyway, because the measurement is what stops the verifier treating the line as
   * one some unshown test covers.
   */
  @Test
  void carriesTheMeasurementEvenWhenTheFindingNeverMentionsCoverage() {
    var finding = finding(PATH, 61, "This branch returns null for an empty body.");

    var note = evidence(round(COVERAGE_SECTION, PathScopedInstructions.NONE), finding);

    assertNotNull(note, "the measurement belongs to the line, not to the finding's wording");
    assertTrue(note.contains("lists line 61"), note);
  }

  @Test
  void contradictsACoverageClaimAboutALineTheSectionDoesNotList() {
    var finding = finding(PATH, 12, "The coverage report shows this line is never executed.");

    var note = evidence(round(COVERAGE_SECTION, PathScopedInstructions.NONE), finding);

    assertNotNull(note, "an attributed measurement the section lacks must be contradicted");
    assertTrue(note.contains("as never executed: 40-47, 61"), note);
    assertTrue(note.contains("The cited line 12 is not among them"), note);
  }

  @Test
  void contradictsACoverageClaimAboutAFileTheSectionDoesNotList() {
    var finding =
        finding("src/main/java/app/Other.java", 3, "The coverage report lists this line.");

    var note = evidence(round(COVERAGE_SECTION, PathScopedInstructions.NONE), finding);

    assertNotNull(note, "a measurement claimed for an unlisted file must be contradicted");
    assertTrue(
        note.contains("lists no uncovered added line in `src/main/java/app/Other.java`"), note);
    assertTrue(note.contains("does not establish that the file's lines are covered"), note);
  }

  /**
   * The case the guard exists for: no report was read at all, so "the coverage report shows this
   * line is never executed" is a sentence the finding wrote for itself.
   */
  @Test
  void contradictsACoverageClaimWhenThisReviewSuppliedNoSection() {
    var finding = finding(PATH, 42, "This line is never executed, so the branch is untested.");

    var note = evidence(round("", PathScopedInstructions.NONE), finding);

    assertNotNull(note, "a measurement claimed where none was taken must be contradicted");
    assertTrue(note.contains("supplied no patch-coverage section"), note);
  }

  @Test
  void saysNothingWhenTheFindingRestsOnNoContextAtAll() {
    var finding = finding(PATH, 42, "The loop is quadratic over an unbounded collection.");

    assertNull(
        evidence(round("", PathScopedInstructions.NONE), finding),
        "a finding grounded in the diff alone must reach the verifier exactly as it did before");
  }

  @Test
  void quotesTheScopedRulesGoverningTheFindingsFile() {
    var finding = finding(PATH, 42, "Renders the title without escaping it.");

    var note = evidence(round("", scoped("src/main/java/app/**", RULES, PATH)), finding);

    assertNotNull(note, "a finding raised under a scoped rule must carry the rule");
    assertTrue(note.contains("files matching `src/main/java/app/**`"), note);
    assertTrue(note.contains(RULES), note);
  }

  @Test
  void saysNothingAboutScopesThatGovernAnotherFile() {
    var finding = finding(PATH, 42, "Renders the title without escaping it.");

    assertNull(
        evidence(round("", scoped("docs/**", RULES, "docs/a.md")), finding),
        "a scope that does not govern the file must not travel with the finding");
  }

  @Test
  void boundsAVerboseScopesRules() {
    var verbose = "Escape everything. ".repeat(80);
    var finding = finding(PATH, 42, "Renders the title without escaping it.");

    var note = evidence(round("", scoped("src/**", verbose, PATH)), finding);

    assertTrue(note.contains("(rules truncated)"), note);
    assertTrue(
        note.length() < verbose.length(),
        "one scope's prose must not be quoted in full into every finding it governs");
  }

  @Test
  void carriesBothDimensionsWhenTheFindingRestsOnBoth() {
    var finding = finding(PATH, 42, "The escaping branch here is never executed.");

    var note = evidence(round(COVERAGE_SECTION, scoped("src/**", RULES, PATH)), finding);

    assertTrue(note.contains("lists line 42"), note);
    assertTrue(note.contains(RULES), note);
  }

  /**
   * The budget is the review's, not this resolver's: what the cited-location round has already
   * spent is gone here too, so the two together can never outgrow the diff they sit beside.
   */
  @Test
  void dropsANoteThatNoLongerFitsTheReviewsSharedBudget() {
    var budget = new EvidenceBudget();
    for (var i = 0; i < EvidenceBudget.MAX_TOTAL_CHARS / EvidenceBudget.MAX_NOTE_CHARS; i++) {
      assertNotNull(budget.attach("x".repeat(EvidenceBudget.MAX_NOTE_CHARS)));
    }
    var finding = finding(PATH, 42, "The new fallback branch decides what the caller renders.");

    var round =
        ContextEvidenceResolver.forReview(COVERAGE_SECTION, PathScopedInstructions.NONE, budget);

    assertNull(
        round.locate(List.of(finding)).forFinding(finding),
        "evidence past the review's character budget is dropped, not attached");
  }

  @Test
  void findsTheSectionsPathForACitationThatLostALeadingDirectory() {
    var finding = finding("app/Renderer.java", 42, "The branch here is never executed.");

    var note = evidence(round(COVERAGE_SECTION, PathScopedInstructions.NONE), finding);

    assertNotNull(note, "a citation missing a leading directory still names one changed file");
    assertTrue(note.contains("lists line 42 of `" + PATH + "`"), note);
  }

  @Test
  void refusesToGuessBetweenTwoListedFilesACitationMatches() {
    var section =
        """
        ### Patch coverage for this diff (from the repository's own CI coverage report)
        - src/a/Renderer.java: 40-47
        - src/b/Renderer.java: 40-47""";
    var finding = finding("Renderer.java", 42, "The coverage report lists this line.");

    var note = evidence(round(section, PathScopedInstructions.NONE), finding);

    assertTrue(
        note.contains("lists no uncovered added line in `Renderer.java`"),
        "naming one of two matching files' measurements as this finding's would be a guess");
  }

  @Test
  void attachesNothingWhenTheReviewLoadedNoSectionAndNoScopes() {
    var round = ContextEvidenceResolver.forReview(null, null, new EvidenceBudget());

    assertSame(
        FindingVerificationService.ContextEvidence.NONE,
        round.locate(null),
        "a call with no findings must not build a lookup");
    assertSame(FindingVerificationService.ContextEvidence.NONE, round.locate(List.of()));
    assertTrue(
        evidence(round, finding(PATH, 42, "The coverage report lists this line."))
            .contains("supplied no patch-coverage section"),
        "a null section reads as the absent section it is, not as a section listing nothing");
  }

  /**
   * Findings are keyed by the location and title the model wrote, so two findings the model raised
   * at one anchor share an entry and one citing no file at all has none.
   */
  @Test
  void keysEvidenceByTheLocationAndTitleTheModelWrote() {
    var first = finding(PATH, 42, "The new fallback branch decides what the caller renders.");
    var second = finding(PATH, 42, "The same anchor, raised twice in one call.");
    var fileless = finding("", 0, "The coverage report lists this line.");

    var located =
        round(COVERAGE_SECTION, PathScopedInstructions.NONE)
            .locate(List.of(first, second, fileless));

    assertNotNull(located.forFinding(first));
    assertEquals(located.forFinding(first), located.forFinding(second), "one entry per anchor");
    assertNull(located.forFinding(fileless), "a finding citing no file has nothing to match");
  }

  @Test
  void quotesEveryScopeThatGovernsTheFile() {
    var both =
        new PathScopedInstructions(
            List.of(
                new PathScopedInstructions.AppliedScope("src/**", RULES, List.of(PATH)),
                new PathScopedInstructions.AppliedScope(
                    "**/Renderer.java", "Renderers are append-only.", List.of(PATH))),
            ".github");
    var finding = finding(PATH, 42, "Renders the title without escaping it.");

    var note = evidence(round("", both), finding);

    assertTrue(note.contains(RULES), note);
    assertTrue(note.contains("Renderers are append-only."), note);
    assertTrue(note.contains("files matching `**/Renderer.java`"), note);
  }

  @Test
  void governsAFileACitationNamesWithoutItsLeadingDirectory() {
    var finding = finding("app/Renderer.java", 42, "Renders the title without escaping it.");

    var note = evidence(round("", scoped("src/**", RULES, PATH)), finding);

    assertNotNull(note, "a citation missing a leading directory still names a governed file");
    assertTrue(note.contains(RULES), note);
  }

  /** Model output and repository YAML are both allowed to leave text out; neither may throw. */
  @Test
  void toleratesAScopeWithNoRulesAndAFindingWithNoProse() {
    var round = round(COVERAGE_SECTION, scoped("src/**", null, PATH));
    var measured = new ReviewResponse.Finding("low", "low", PATH, 42, null, null, null, null);
    var unmeasured = new ReviewResponse.Finding("low", "low", PATH, 12, null, null, null, null);

    var onMeasured = evidence(round, measured);
    var onUnmeasured = evidence(round, unmeasured);

    assertNotNull(onMeasured, "the measurement is attached however little the finding says");
    assertTrue(onMeasured.contains("lists line 42"), onMeasured);
    assertTrue(onMeasured.contains("verbatim:\n"), onMeasured);
    // A finding with no prose credits the report with nothing, so nothing is contradicted.
    assertFalse(onUnmeasured.contains("patch-coverage section"), onUnmeasured);
    assertTrue(onUnmeasured.contains("files matching `src/**`"), onUnmeasured);
  }

  @Test
  void ignoresARangeBoundThatIsNotANumber() {
    assertFalse(ContextEvidenceResolver.rangesContain("12-x", 13));
    assertFalse(ContextEvidenceResolver.rangesContain("12-18", 11));
  }

  /**
   * The section is read back, not recomputed, so the two sides have to agree on its shape: this
   * parses what the renderer itself emits rather than a fixture that could drift away from it.
   */
  @Test
  void readsBackTheSectionTheRendererItselfEmits() {
    var rendered =
        PatchCoverageResolver.render(
            List.of(
                new PatchCoverageResolver.UncoveredFile(PATH, new TreeSet<>(List.of(40, 41, 61))),
                new PatchCoverageResolver.UncoveredFile(
                    "src/main/java/app/Parser.java", new TreeSet<>(List.of(12)))));

    var parsed = ContextEvidenceResolver.parseUncovered(rendered);

    assertEquals("40-41, 61", parsed.get(PATH), rendered);
    assertEquals("12", parsed.get("src/main/java/app/Parser.java"), rendered);
  }

  @Test
  void readsTheSectionsOwnRangesBackOutOfIt() {
    var parsed = ContextEvidenceResolver.parseUncovered(COVERAGE_SECTION);

    assertTrue(parsed.containsKey(PATH), parsed.toString());
    assertTrue(ContextEvidenceResolver.rangesContain(parsed.get(PATH), 47));
    assertTrue(ContextEvidenceResolver.rangesContain(parsed.get(PATH), 61));
    assertFalse(ContextEvidenceResolver.rangesContain(parsed.get(PATH), 48));
    assertFalse(parsed.containsKey("(2 more changed file(s) with uncovered added lines)"));
  }

  @Test
  void ignoresARolledUpRangeCountThatIsNotALineNumber() {
    assertTrue(ContextEvidenceResolver.rangesContain("12-18, and 3 more range(s)", 15));
    assertFalse(ContextEvidenceResolver.rangesContain("12-18, and 3 more range(s)", 99));
  }
}
