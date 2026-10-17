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
package dev.thiagogonzaga.thrillhousebot.review.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.ReviewDimensionRouter;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewDimension;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Deterministic guard over the eval corpus itself — runs in every CI build (no AI call). It keeps
 * fixtures well-formed so the opt-in {@link PromptEvalTest} can trust them, and so a malformed new
 * case is caught at merge time rather than on the next eval run.
 */
class EvalCorpusTest {

  private static final Set<String> VALID_VERDICTS = Set.of("confirmed", "downgraded", "rejected");

  private final List<EvalCase> corpus = EvalCorpus.load();

  @Test
  void corpusIsSeededWithDogfoodOutcomes() {
    assertFalse(corpus.isEmpty(), "eval corpus must not be empty");
    assertEquals(corpus.size(), corpus.stream().map(EvalCase::name).distinct().count());
  }

  @Test
  void everyCaseDeclaresKindProvenanceAndDiff() {
    for (EvalCase c : corpus) {
      assertTrue(
          c.isVerifierCase()
              || c.isGeneratorCase()
              || c.isSummaryCase()
              || c.isSummaryVerificationCase(),
          c.name() + ": kind must be 'verifier', 'generator', 'summary' or 'summary-verification'");
      assertTrue(c.spec().sourcePr() > 0, c.name() + ": sourcePr must reference the dogfood PR");
      assertNotNull(c.spec().why(), c.name() + ": why must document the resolved outcome");
      assertFalse(c.spec().why().isBlank(), c.name() + ": why must document the resolved outcome");
      assertTrue(
          c.diff().startsWith("### "),
          c.name() + ": diff.txt must be in review-pipeline format (### path sections)");
      assertTrue(c.diff().contains("```diff"), c.name() + ": diff.txt must fence its patch");
    }
  }

  @Test
  void verifierCasesCarryFindingAndValidExpectedVerdicts() {
    for (EvalCase c : corpus) {
      if (!c.isVerifierCase()) {
        continue;
      }
      var finding = c.spec().finding();
      assertNotNull(finding, c.name() + ": verifier case needs a candidate finding");
      assertNotNull(finding.risk(), c.name() + ": finding.risk");
      assertNotNull(finding.confidence(), c.name() + ": finding.confidence");
      assertNotNull(finding.file(), c.name() + ": finding.file");
      assertNotNull(finding.title(), c.name() + ": finding.title");
      assertNotNull(finding.description(), c.name() + ": finding.description");
      var expected = c.spec().expectedVerdicts();
      assertNotNull(expected, c.name() + ": expectedVerdicts");
      assertFalse(expected.isEmpty(), c.name() + ": expectedVerdicts must not be empty");
      assertTrue(
          VALID_VERDICTS.containsAll(expected),
          c.name() + ": expectedVerdicts must be among " + VALID_VERDICTS);
      assertTrue(
          c.diff().contains(finding.file().substring(finding.file().lastIndexOf('/') + 1)),
          c.name() + ": the finding's file must appear in the diff");
    }
  }

  @Test
  void generatorCasesCarryExpectationTargetAndKeywords() {
    for (EvalCase c : corpus) {
      if (!c.isGeneratorCase()) {
        continue;
      }
      var expectation = c.spec().expectation();
      assertTrue(
          EvalCase.MUST_FIND.equals(expectation) || EvalCase.MUST_NOT_FIND.equals(expectation),
          c.name() + ": expectation must be 'must-find' or 'must-not-find'");
      assertNotNull(c.spec().targetFile(), c.name() + ": targetFile");
      assertNotNull(c.spec().keywords(), c.name() + ": keywords");
      assertFalse(c.spec().keywords().isEmpty(), c.name() + ": keywords must not be empty");
    }
  }

  @Test
  void summaryCasesCarryTheClaimTheChangedFilesAndTheFindings() {
    for (EvalCase c : corpus) {
      if (!c.isSummaryCase()) {
        continue;
      }
      assertTrue(
          Set.of(EvalCase.CLAIM_GAPPED, EvalCase.CLAIM_NOT_GAPPED).contains(c.spec().expectation()),
          c.name() + ": expectation");
      assertNotNull(c.spec().prDescription(), c.name() + ": the description holding the claim");
      assertNotNull(c.spec().keywords(), c.name() + ": keywords naming the claim");
      assertFalse(c.spec().keywords().isEmpty(), c.name() + ": keywords must not be empty");
      assertTrue(
          c.spec().keywords().stream()
              .anyMatch(
                  k ->
                      c.spec()
                          .prDescription()
                          .toLowerCase(Locale.ROOT)
                          .contains(k.toLowerCase(Locale.ROOT))),
          c.name() + ": the description must make the claim the keywords name");
      assertNotNull(c.spec().changedFiles(), c.name() + ": changedFiles overview");
      assertNotNull(c.spec().findings(), c.name() + ": findings (may be empty)");
      for (var file : c.files()) {
        assertTrue(
            c.spec().changedFiles().contains(file.filename()),
            c.name() + ": every diff section must be in the changed-files overview");
      }
    }
  }

  @Test
  void summaryVerificationCasesCarryTheSummaryAndTheStatementsToCutAndKeep() {
    var found = false;
    for (EvalCase c : corpus) {
      if (!c.isSummaryVerificationCase()) {
        continue;
      }
      found = true;
      var expectation = c.spec().expectation();
      assertTrue(
          Set.of(EvalCase.CONTRADICTED_CUT, EvalCase.NOTHING_CUT).contains(expectation),
          c.name() + ": expectation");
      var summary = c.spec().summary();
      assertNotNull(summary, c.name() + ": the written summary");
      assertNotNull(c.spec().findings(), c.name() + ": findings (may be empty)");
      var written = summaryText(summary);
      assertNotNull(c.spec().keywords(), c.name() + ": keywords naming what to cut");
      assertEquals(
          EvalCase.NOTHING_CUT.equals(expectation),
          c.spec().keywords().isEmpty(),
          c.name() + ": only a nothing-cut case names nothing to cut");
      for (var keyword : c.spec().keywords()) {
        assertTrue(written.contains(keyword), c.name() + ": the summary must state " + keyword);
      }
      assertNotNull(c.spec().keptKeywords(), c.name() + ": keptKeywords naming what to keep");
      assertFalse(c.spec().keptKeywords().isEmpty(), c.name() + ": keptKeywords must not be empty");
      for (var keyword : c.spec().keptKeywords()) {
        assertTrue(written.contains(keyword), c.name() + ": the summary must state " + keyword);
      }
      for (var row : summary.fileSummaries()) {
        assertTrue(
            c.files().stream().anyMatch(f -> f.filename().equals(row.path())),
            c.name() + ": every summarized file must be a diff section: " + row.path());
      }
    }
    assertTrue(found, "the corpus needs a summary verification case (#1078)");
  }

  /** Every statement of {@code summary} the summary verification call reads, one per line. */
  static String summaryText(ReviewResponse.Summary s) {
    var text = new StringBuilder(s.prPurpose() == null ? "" : s.prPurpose());
    s.fileSummaries().forEach(f -> text.append('\n').append(f.summary()));
    text.append('\n').append(s.walkthroughDiagram() == null ? "" : s.walkthroughDiagram());
    return text.toString();
  }

  @Test
  void everyRoutedDimensionHasAMustFindCase() {
    // #665: a routed dimension with no must-find case can be gated out of the prompt with nothing
    // turning red. Each needs one before routing is trusted.
    for (var dimension : ReviewDimension.values()) {
      if (dimension.alwaysOn()) {
        continue;
      }
      assertTrue(
          corpus.stream()
              .anyMatch(
                  c ->
                      c.isGeneratorCase()
                          && EvalCase.MUST_FIND.equals(c.spec().expectation())
                          && c.dimensions().contains(dimension)),
          "no must-find generator case declares routed dimension " + dimension);
    }
  }

  @Test
  void everyCaseDiffParsesIntoTheFilesTheRouterReads() {
    for (EvalCase c : corpus) {
      var files = c.files();
      assertFalse(files.isEmpty(), c.name() + ": diff.txt has no parsable ### path section");
      for (var file : files) {
        assertFalse(file.patch().isBlank(), c.name() + ": empty patch for " + file.filename());
      }
      if (c.isGeneratorCase()) {
        assertTrue(
            files.stream().anyMatch(f -> f.filename().equals(c.spec().targetFile())),
            c.name() + ": targetFile must be one of the diff's sections");
      }
    }
  }

  @Test
  void routingKeepsEveryDimensionACaseDependsOn() {
    // The deterministic half of the recall gate (#665): the live eval shows the model finds the
    // defect when the block is there; this shows the router puts the block there for the case's
    // files, in every build, without an API key.
    for (EvalCase c : corpus) {
      var routed =
          c.isVerifierCase()
              ? ReviewDimensionRouter.dimensionsForPaths(List.of(c.spec().finding().file()))
              : ReviewDimensionRouter.routeFiles(c.files()).dimensions();
      for (var dimension : c.dimensions()) {
        assertTrue(
            routed.contains(dimension),
            c.name() + ": routing left out " + dimension + " (routed " + routed + ")");
      }
    }
  }
}
