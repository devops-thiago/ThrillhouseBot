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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.thiagogonzaga.thrillhousebot.review.PromptSections;
import dev.thiagogonzaga.thrillhousebot.review.PromptTemplateEscaper;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewer;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponseParser;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in regression suite for the review and verifier prompts (issue #113): replays each labeled
 * corpus case against the live AI provider and asserts the expected outcome, so a prompt edit that
 * fixes one false-positive class cannot silently reintroduce another.
 *
 * <p>Excluded from normal builds (JUnit tag {@code eval}, filtered by surefire); run it with a real
 * provider key before shipping any change to {@code PrReviewPrompts} or {@code
 * FindingVerifierPrompts}:
 *
 * <pre>{@code
 * QUARKUS_LANGCHAIN4J_OPENAI_API_KEY=... ./mvnw test -Peval -Dtest=PromptEvalTest
 * }</pre>
 *
 * <p>LLM nondeterminism is absorbed two ways: each case is sampled {@code -Deval.samples} times
 * (default 3) and judged by majority, and the suite tolerates {@code -Deval.tolerated} failing
 * cases (default 0) so a known-unfixed corpus label (an open prompt-hardening issue) can be carried
 * without blocking unrelated prompt work.
 *
 * <p>Caveat: {@link FindingVerificationService#verify} fails open by design, so a provider outage
 * during a verifier sample surfaces as a spurious "confirmed" — check the logged warning before
 * trusting a failure.
 */
@QuarkusTest
@Tag("eval")
class PromptEvalTest {

  private static final int SAMPLES = Integer.getInteger("eval.samples", 3);
  private static final int TOLERATED = Integer.getInteger("eval.tolerated", 0);
  private static final long SAMPLE_TIMEOUT_MINUTES = 10;

  @Inject FindingVerificationService findingVerificationService;
  @Inject PrReviewer prReviewer;
  @Inject ReviewResponseParser reviewResponseParser;

  @Test
  void promptsReproduceLabeledDogfoodOutcomes() throws Exception {
    assumeTrue(
        System.getenv("QUARKUS_LANGCHAIN4J_OPENAI_API_KEY") != null,
        "eval needs a real provider key in QUARKUS_LANGCHAIN4J_OPENAI_API_KEY");

    var report = new StringBuilder();
    var failed = 0;
    for (EvalCase evalCase : EvalCorpus.load()) {
      var passes = 0;
      var outcomes = new StringBuilder();
      for (var sample = 0; sample < SAMPLES; sample++) {
        String outcome =
            evalCase.isVerifierCase() ? verifierOutcome(evalCase) : generatorOutcome(evalCase);
        outcomes.append(sample == 0 ? "" : ", ").append(outcome);
        if (sampleMatchesExpectation(evalCase, outcome)) {
          passes++;
        }
      }
      var majority = passes * 2 > SAMPLES;
      if (!majority) {
        failed++;
      }
      report
          .append(majority ? "PASS " : "FAIL ")
          .append(evalCase.name())
          .append(" — expected ")
          .append(expectationOf(evalCase))
          .append(", got [")
          .append(outcomes)
          .append("] (")
          .append(passes)
          .append('/')
          .append(SAMPLES)
          .append(")\n");
    }

    assertTrue(
        failed <= TOLERATED,
        "Prompt eval regressions: "
            + failed
            + " case(s) failed (tolerated "
            + TOLERATED
            + ")\n"
            + report);
  }

  private static boolean sampleMatchesExpectation(EvalCase evalCase, String outcome) {
    if (evalCase.isVerifierCase()) {
      return evalCase.spec().expectedVerdicts().contains(outcome);
    }
    return outcome.equals(evalCase.spec().expectation());
  }

  private static String expectationOf(EvalCase evalCase) {
    return evalCase.isVerifierCase()
        ? String.join("|", evalCase.spec().expectedVerdicts())
        : evalCase.spec().expectation();
  }

  /**
   * Feeds the labeled candidate through the production verification path and classifies what came
   * back: dropped → rejected, risk or confidence lowered → downgraded, unchanged → confirmed.
   */
  private String verifierOutcome(EvalCase evalCase) {
    var f = evalCase.spec().finding();
    var candidate =
        new ReviewResponse.Finding(
            f.risk(),
            f.confidence(),
            f.file(),
            f.line(),
            f.title(),
            f.description(),
            f.suggestionOld(),
            f.suggestionNew());
    var response = new ReviewResponse(List.of(candidate), List.of(), null);
    var verified =
        findingVerificationService.verify(
            response, PromptTemplateEscaper.fence(evalCase.diff()), "", "");
    if (verified.findings().isEmpty()) {
      return "rejected";
    }
    var kept = verified.findings().get(0);
    var lowered =
        !kept.risk().equalsIgnoreCase(candidate.risk())
            || !kept.confidence().equalsIgnoreCase(candidate.confidence());
    return lowered ? "downgraded" : "confirmed";
  }

  /**
   * Runs the first-pass review prompt over the case's diff and reports whether any finding on the
   * target file mentions one of the case's keywords ("must-find" / "must-not-find").
   */
  private String generatorOutcome(EvalCase evalCase) throws Exception {
    var raw = new CompletableFuture<String>();
    prReviewer
        .reviewStream(
            PromptTemplateEscaper.fence(evalCase.diff()),
            PromptTemplateEscaper.escape(
                PromptSections.prContext(
                    orEmpty(evalCase.spec().prTitle()), orEmpty(evalCase.spec().prDescription()))),
            "",
            "",
            "",
            "",
            "")
        .onPartialResponse(token -> {})
        .onCompleteResponse(response -> raw.complete(response.aiMessage().text()))
        .onError(raw::completeExceptionally)
        .start();
    var parsed = reviewResponseParser.parse(raw.get(SAMPLE_TIMEOUT_MINUTES, TimeUnit.MINUTES));
    var found =
        parsed.findings().stream()
            .anyMatch(
                finding -> targetsCase(evalCase, finding) && mentionsKeyword(evalCase, finding));
    return found ? EvalCase.MUST_FIND : EvalCase.MUST_NOT_FIND;
  }

  private static boolean targetsCase(EvalCase evalCase, ReviewResponse.Finding finding) {
    var target = evalCase.spec().targetFile();
    return finding.file() != null
        && (finding.file().equals(target) || target.endsWith("/" + finding.file()));
  }

  private static boolean mentionsKeyword(EvalCase evalCase, ReviewResponse.Finding finding) {
    var text =
        (orEmpty(finding.title()) + " " + orEmpty(finding.description())).toLowerCase(Locale.ROOT);
    return evalCase.spec().keywords().stream()
        .anyMatch(keyword -> text.contains(keyword.toLowerCase(Locale.ROOT)));
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
