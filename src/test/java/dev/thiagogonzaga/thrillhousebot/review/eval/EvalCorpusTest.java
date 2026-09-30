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
import java.util.List;
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
          c.isVerifierCase() || c.isGeneratorCase(),
          c.name() + ": kind must be 'verifier' or 'generator'");
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
