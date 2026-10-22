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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import dev.thiagogonzaga.thrillhousebot.review.ChangeSymbols;
import dev.thiagogonzaga.thrillhousebot.review.Confidence;
import dev.thiagogonzaga.thrillhousebot.review.EvalTrailingGuidance;
import dev.thiagogonzaga.thrillhousebot.review.PromptSections;
import dev.thiagogonzaga.thrillhousebot.review.PromptTemplateEscaper;
import dev.thiagogonzaga.thrillhousebot.review.ReviewDimensionRouter;
import dev.thiagogonzaga.thrillhousebot.review.RiskLevel;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponseTruncatedException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewer;
import dev.thiagogonzaga.thrillhousebot.review.ai.ResponseCaps;
import dev.thiagogonzaga.thrillhousebot.review.ai.ResponseRepairService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponseParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewTokenLedger;
import dev.thiagogonzaga.thrillhousebot.review.ai.SummaryVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.SummaryVerifier;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * <p>Both prompts follow {@code REVIEW_DIMENSION_ROUTING_ENABLED} exactly as production does
 * (#665): off, every case runs against the monolithic prompts; on, each generator case gets the
 * system prompt routed from its own diff's files and each verifier case the carve-outs routed from
 * its candidate's file. Run it with routing on before turning routing on anywhere:
 *
 * <pre>{@code
 * REVIEW_DIMENSION_ROUTING_ENABLED=true QUARKUS_LANGCHAIN4J_OPENAI_API_KEY=... \
 *     ./mvnw test -Peval -Dtest=PromptEvalTest
 * }</pre>
 *
 * <p>{@code -Deval.only=<name>,<name>} runs only the cases whose name contains one of the given
 * parts, for a change that touches one prompt alone (the summary verification prompt, #1078, has
 * its own cases and reaches neither the review nor the verifier prompt).
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
  private static final int PARSE_ATTEMPTS = 3;
  private static final String ONLY = System.getProperty("eval.only", "");

  /** A generator sample with no parseable review: it matches no expectation, so it fails. */
  private static final String UNPARSED = "unparsed";

  @Inject FindingVerificationService findingVerificationService;
  @Inject PrReviewer prReviewer;
  @Inject AiReviewService aiReviewService;
  @Inject EvalCallRecorder callRecorder;
  @Inject ThrillhouseConfig thrillhouseConfig;
  @Inject ReviewResponseParser reviewResponseParser;
  @Inject ReviewDimensionRouter dimensionRouter;
  @Inject SummaryVerifier summaryVerifier;
  @Inject ResponseRepairService responseRepairService;
  @Inject ObjectMapper mapper;
  @Inject ReviewTokenLedger tokenLedger;
  @Inject ResponseCaps responseCaps;
  @Inject TokenCounter tokenCounter;

  @Test
  void promptsReproduceLabeledDogfoodOutcomes() throws Exception {
    assumeTrue(
        System.getenv("QUARKUS_LANGCHAIN4J_OPENAI_API_KEY") != null,
        "eval needs a real provider key in QUARKUS_LANGCHAIN4J_OPENAI_API_KEY");

    var report = new StringBuilder();
    var failed = 0;
    for (EvalCase evalCase : EvalCorpus.load()) {
      if (!selected(evalCase)) {
        continue;
      }
      var passes = 0;
      var outcomes = new StringBuilder();
      for (var sample = 0; sample < SAMPLES; sample++) {
        String outcome = outcomeOf(evalCase);
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

    Log.infof("Prompt eval report:%n%s", report);
    assertTrue(
        failed <= TOLERATED,
        "Prompt eval regressions: "
            + failed
            + " case(s) failed (tolerated "
            + TOLERATED
            + ")\n"
            + report);
  }

  /** Whether {@code -Deval.only} selects the case; with it unset, every case runs. */
  private static boolean selected(EvalCase evalCase) {
    return ONLY.isBlank()
        || Arrays.stream(ONLY.split(","))
            .map(String::strip)
            .anyMatch(part -> !part.isEmpty() && evalCase.name().contains(part));
  }

  private String outcomeOf(EvalCase evalCase) throws Exception {
    if (evalCase.isVerifierCase()) {
      return verifierOutcome(evalCase);
    }
    if (evalCase.isSummaryVerificationCase()) {
      return summaryVerificationOutcome(evalCase);
    }
    if (evalCase.isRepairCase()) {
      return repairOutcome(evalCase);
    }
    return evalCase.isSummaryCase() ? summaryOutcome(evalCase) : generatorOutcome(evalCase);
  }

  /**
   * Runs the summary call over the case's PR text, changed-files overview (each row with the names
   * its diff section declares, as production sends it) and findings, with the diagram requested,
   * and reports whether the description claim came back as a description gap and nowhere it reads
   * as implemented: "claim-gapped", else "claim-implemented" when the purpose, a file summary or
   * the diagram states it, or "claim-dropped" when it is in neither (#920).
   *
   * <p>The call goes through {@link AiReviewService#summarize}, as the pipeline makes it, so the
   * sample sees the repeat a call that reasoned its whole cap away gets (#1169). Only that repeat
   * is allowed: a sample that took any other further call (a retry, a repair, a re-ask) is
   * "needed-retry", and one cut at the cap is "cut", both failures.
   */
  private String summaryOutcome(EvalCase evalCase) throws Exception {
    var spec = evalCase.spec();
    var patches = new HashMap<String, String>();
    evalCase.files().forEach(f -> patches.put(f.filename(), f.patch()));
    var overview = new StringBuilder();
    for (var row : spec.changedFiles().split("\n")) {
      var path = row.substring(0, row.indexOf(' ') < 0 ? row.length() : row.indexOf(' '));
      overview
          .append(row)
          .append(ChangeSymbols.overviewSuffix(path, patches.get(path)))
          .append('\n');
    }
    var inputs =
        new AiReviewService.SummaryInputs(
            PromptTemplateEscaper.fence(
                PromptSections.prContext(orEmpty(spec.prTitle()), orEmpty(spec.prDescription()))),
            PromptTemplateEscaper.escape(new ObjectMapper().writeValueAsString(spec.findings())),
            PromptTemplateEscaper.escape(overview.toString()),
            "",
            PrReviewPrompts.DIAGRAM_REQUEST);
    // Shaped as the orchestrator creates one: the retry and repeat events read its fields.
    var session =
        ReviewSession.create(
            TestScopes.scope("eval/" + evalCase.name()),
            "eval/" + evalCase.name(),
            spec.sourcePr(),
            orEmpty(spec.prTitle()),
            "eval");
    session.id = EVAL_SESSION_ID;
    callRecorder.reset();
    ReviewResponse.Summary summary;
    try {
      summary = aiReviewService.summarize(session, inputs).summary();
    } catch (AiResponseTruncatedException _) {
      return "cut";
    } catch (RuntimeException e) {
      // Only the type is logged: the message can carry provider or model text.
      Log.infof("Summary sample failed (%s)", e.getClass().getSimpleName());
      return CALL_FAILED;
    } finally {
      Log.infof("Summary sample calls: %s", callRecorder.calls());
    }
    if (!EvalCallRecorder.oneCallOrItsRepeat(callRecorder.calls())) {
      return "needed-retry";
    }
    if (summary == null) {
      return "no-summary";
    }
    var implemented = new StringBuilder(orEmpty(summary.prPurpose()));
    implemented.append('\n').append(orEmpty(summary.overallAssessment()));
    summary.fileSummaries().forEach(f -> implemented.append('\n').append(orEmpty(f.summary())));
    implemented.append('\n').append(orEmpty(summary.walkthroughDiagram()));
    if (statesAsImplemented(spec.keywords(), implemented.toString())) {
      return "claim-implemented";
    }
    return mentionsAny(spec.keywords(), String.join("\n", summary.descriptionGaps()))
        ? EvalCase.CLAIM_GAPPED
        : "claim-dropped";
  }

  /**
   * Runs the production summary verification step, switched on, over the case's written summary,
   * diff, PR text and findings, and reports "contradicted-kept" when a statement naming a keyword
   * is still there, "true-statement-cut" when a kept keyword is gone, and otherwise
   * "contradicted-cut", or "nothing-cut" for a case that names nothing to cut (#1078). The step
   * fails open, so a provider failure surfaces as "contradicted-kept".
   */
  private String summaryVerificationOutcome(EvalCase evalCase) {
    var spec = evalCase.spec();
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    var diagram = mock(ThrillhouseConfig.DiagramConfig.class);
    when(config.review()).thenReturn(review);
    // The repeat after a call that reasoned its whole cap away reads the reasoning settings.
    when(config.ai()).thenReturn(thrillhouseConfig.ai());
    when(review.summaryVerificationEnabled()).thenReturn(true);
    when(review.diagram()).thenReturn(diagram);
    when(diagram.enabled()).thenReturn(true);
    var service =
        new SummaryVerificationService(
            summaryVerifier, config, mapper, tokenLedger, responseCaps, tokenCounter);
    var findings =
        spec.findings().stream()
            .map(
                f ->
                    new ReviewResponse.Finding(
                        f.risk(),
                        f.confidence(),
                        f.file(),
                        f.line(),
                        f.title(),
                        f.description(),
                        f.suggestionOld(),
                        f.suggestionNew()))
            .toList();
    var material =
        new SummaryVerificationService.Material(
            PromptTemplateEscaper.fence(
                PromptSections.prContext(orEmpty(spec.prTitle()), orEmpty(spec.prDescription()))),
            List.of(evalCase.diff()),
            "",
            findings,
            Integer.MAX_VALUE);
    // No review session: the unopened sentinel key means no ceiling gates the call.
    callRecorder.reset();
    var verified = service.verify(Long.MIN_VALUE, spec.summary(), material);
    Log.infof("Summary verification sample calls: %s", callRecorder.calls());
    return summaryVerificationGrade(
        spec.keywords(), spec.keptKeywords(), EvalCorpusTest.summaryText(verified));
  }

  /**
   * Runs the production repair step (#1079) over the case's failed answer. A review answer is
   * repaired after the parser's own refusal of it, and the sample is "repaired" when the result
   * holds exactly one finding per keyword, each titled by it; "not-repaired" when the step returned
   * nothing (a failed call, an answer the parser still refuses, or one the content checks
   * rejected), "content-changed" otherwise. A summary answer's {@code file_summaries} string is
   * re-asked, and the sample is "file-summaries-read" when the rows name exactly the keyword paths,
   * "no-rows" or "wrong-rows" otherwise.
   */
  private String repairOutcome(EvalCase evalCase) {
    var spec = evalCase.spec();
    var answer = spec.answer();
    // No review session: the unopened sentinel key means no ceiling gates the call.
    if (EvalCase.FILE_SUMMARIES_READ.equals(spec.expectation())) {
      var text = reviewResponseParser.unreadFileSummaries(answer).orElse("");
      var rows = responseRepairService.fileSummaries(Long.MIN_VALUE, text).orElse(List.of());
      if (rows.isEmpty()) {
        return "no-rows";
      }
      var paths = rows.stream().map(ReviewResponse.FileSummary::path).collect(Collectors.toSet());
      return paths.equals(Set.copyOf(spec.keywords()))
          ? EvalCase.FILE_SUMMARIES_READ
          : "wrong-rows";
    }
    IllegalArgumentException refusal;
    try {
      reviewResponseParser.parse(answer);
      return "parsed-without-repair";
    } catch (IllegalArgumentException e) {
      refusal = e;
    }
    var repaired =
        responseRepairService.repair(Long.MIN_VALUE, AiResponses.ModelLane.ACTIVE, answer, refusal);
    if (repaired.isEmpty()) {
      return "not-repaired";
    }
    var titles =
        repaired.get().response().findings().stream().map(ReviewResponse.Finding::title).toList();
    var kept =
        titles.size() == spec.keywords().size()
            && spec.keywords().stream()
                .allMatch(k -> titles.stream().anyMatch(t -> t != null && t.contains(k)));
    return kept ? EvalCase.REPAIRED : "content-changed";
  }

  /**
   * The outcome of a summary verification sample whose summary now reads {@code text}. A sentence
   * that names a keyword only to refute it is not the contradicted statement kept.
   */
  static String summaryVerificationGrade(List<String> keywords, List<String> kept, String text) {
    if (statesAsImplemented(keywords, text)) {
      return "contradicted-kept";
    }
    if (!kept.stream().allMatch(text::contains)) {
      return "true-statement-cut";
    }
    return keywords.isEmpty() ? EvalCase.NOTHING_CUT : EvalCase.CONTRADICTED_CUT;
  }

  /**
   * Whether a sentence of {@code text} mentions a keyword without refuting it: "the description's
   * reminder claim is not backed by any changed file" names the claim to say it is unconfirmed,
   * which is what the prompt asks for, so only a sentence with no refutation cue counts.
   */
  static boolean statesAsImplemented(List<String> keywords, String text) {
    return Arrays.stream(text.split("(?<=[.!?\n])"))
        .anyMatch(
            sentence ->
                mentionsAny(keywords, sentence)
                    && REFUTATION.stream().noneMatch(cue -> cue.matcher(sentence).find()));
  }

  /**
   * What marks a sentence as reporting a claim rather than stating it: it names the claim as the
   * description's or the PR text's, or says nothing in the change shows it. A bare negation does
   * not ("sends reminders without a scheduler" still states them), so the cues are claim words and
   * evidence-absence phrases, not "no" or "without" alone.
   */
  private static final List<Pattern> REFUTATION =
      List.of(
          Pattern.compile(
              "(?i)\\b(?:claims?|claimed|description|unconfirmed|unsupported|unverified)\\b"),
          Pattern.compile(
              "(?i)(?:\\bnot|n't) (?:implemented|backed|shown|present|found|evident)\\b"),
          Pattern.compile("(?i)\\b(?:missing|lacks?|absent)\\b"),
          // "No billing_runs row is written": the thing is said not to exist.
          Pattern.compile(
              "(?i)\\bno\\b[^.\\n]{0,60}?\\b(?:is|are|was|were) (?:written|added|created|stored|"
                  + "recorded|sent|made)\\b"),
          Pattern.compile("(?i)\\bno (?:changed )?(?:files?|code|evidence)\\b"),
          Pattern.compile("(?i)\\b(?:pr|pull request) (?:text|body|title)\\b"),
          Pattern.compile("(?i)\\b(?:stated|according to|only (?:from|in) the)\\b"));

  /**
   * The review session id the summary samples run under: no session row has it, so the dashboard
   * writes the observability listener makes for it update nothing.
   */
  private static final long EVAL_SESSION_ID = -1169L;

  /**
   * The outcome of a sample whose model call failed or timed out: a failing sample, not a crash.
   */
  static final String CALL_FAILED = "call-failed";

  private static boolean mentionsAny(List<String> keywords, String text) {
    var lower = text.toLowerCase(Locale.ROOT);
    return keywords.stream().anyMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
  }

  static boolean sampleMatchesExpectation(EvalCase evalCase, String outcome) {
    if (evalCase.judgesTheFix()) {
      var parts = outcome.split("\\+", 2);
      return parts.length == 2
          && evalCase.spec().expectedVerdicts().contains(parts[0])
          && parts[1].equals(evalCase.spec().expectation());
    }
    if (evalCase.isVerifierCase()) {
      return evalCase.spec().expectedVerdicts().contains(outcome);
    }
    return evalCase.isSummaryCase()
        ? summaryOutcomeMatches(evalCase.spec().expectation(), outcome)
        : outcome.equals(evalCase.spec().expectation());
  }

  /**
   * Whether a summary sample's outcome meets the case's expectation: "claim-not-gapped" is met by
   * any answer that did not report the claim as a gap, stated or not, and a failed or unparseable
   * call meets nothing. Any mention in description_gaps counts as a gap: the summary prompt reports
   * a claim a changed function can carry in pr_purpose or the file's summary and keeps it out of
   * description_gaps.
   */
  static boolean summaryOutcomeMatches(String expectation, String outcome) {
    if (EvalCase.CLAIM_NOT_GAPPED.equals(expectation)) {
      return "claim-implemented".equals(outcome) || "claim-dropped".equals(outcome);
    }
    return outcome.equals(expectation);
  }

  private static String expectationOf(EvalCase evalCase) {
    if (evalCase.judgesTheFix()) {
      return String.join("|", evalCase.spec().expectedVerdicts())
          + "+"
          + evalCase.spec().expectation();
    }
    return evalCase.isVerifierCase()
        ? String.join("|", evalCase.spec().expectedVerdicts())
        : evalCase.spec().expectation();
  }

  /**
   * Feeds the labeled candidate through the production verification path and classifies what came
   * back: dropped → rejected, risk or confidence lowered → downgraded, unchanged or raised by a
   * deterministic floor → confirmed.
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
    // No review session exists here; the unopened sentinel key means no ceiling ever gates the
    // call and its usage is dropped by the ledger's no-open-entry guard.
    var verified =
        findingVerificationService.verify(
            Long.MIN_VALUE, response, PromptTemplateEscaper.fence(evalCase.diff()), "", "");
    if (verified.findings().isEmpty()) {
      return "rejected";
    }
    var kept = verified.findings().get(0);
    var revision = evalCase.judgesTheFix() ? "+" + revisionOutcome(evalCase, candidate, kept) : "";
    // Both enums declare constants most- to least-severe, so a higher ordinal is a lower rating. A
    // deterministic floor can RAISE a rating (#570, #926), which is not a downgrade.
    var lowered =
        RiskLevel.fromString(kept.risk()).ordinal()
                > RiskLevel.fromString(candidate.risk()).ordinal()
            || Confidence.fromString(kept.confidence()).ordinal()
                > Confidence.fromString(candidate.confidence()).ordinal();
    return (lowered ? "downgraded" : "confirmed") + revision;
  }

  /**
   * What the verifier made of a kept finding's fix and description (#1080). For {@code
   * suggestion-fixed}: "suggestion-fixed" when the suggestion was dropped or replaced by code
   * holding every keyword, "suggestion-kept" when it is unchanged, "suggestion-still-broken"
   * otherwise. For {@code claim-cut}: "claim-cut" when no sentence names a keyword any more and the
   * suggestion is unchanged, "claim-kept" when one still does, "suggestion-changed" when the
   * correct suggestion was touched.
   */
  static String revisionOutcome(
      EvalCase evalCase, ReviewResponse.Finding raised, ReviewResponse.Finding kept) {
    var keywords =
        evalCase.spec().keywords() == null ? List.<String>of() : evalCase.spec().keywords();
    var suggestion = kept.suggestionNew();
    if (EvalCase.SUGGESTION_FIXED.equals(evalCase.spec().expectation())) {
      if (suggestion == null) {
        return EvalCase.SUGGESTION_FIXED;
      }
      if (suggestion.equals(raised.suggestionNew())) {
        return "suggestion-kept";
      }
      return keywords.stream().allMatch(suggestion::contains)
          ? EvalCase.SUGGESTION_FIXED
          : "suggestion-still-broken";
    }
    if (!java.util.Objects.equals(suggestion, raised.suggestionNew())) {
      return "suggestion-changed";
    }
    return mentionsAny(keywords, orEmpty(kept.description())) ? "claim-kept" : EvalCase.CLAIM_CUT;
  }

  /**
   * Runs the first-pass review prompt over the case's diff and reports whether any finding on the
   * target file mentions one of the case's keywords ("must-find" / "must-not-find"), or "unparsed"
   * when no attempt returned a review.
   */
  private String generatorOutcome(EvalCase evalCase) throws Exception {
    // The changed test files, newline-separated, as the assembler lists them for the review call.
    var relatedTests =
        EvalTrailingGuidance.relatedTests(
            evalCase.files().stream().map(GitHubPullRequestClient.FileDiff::filename).toList());
    // The trailing guidance a review of this diff carries (#979): mock fidelity when tests changed,
    // bug-fix efficacy when the body declares a fix, the heuristic failure-mode request when the
    // added lines introduce a parsing or matching rule, the patch-coverage list (#115), and the
    // stored maintainer decisions a case pins (#971).
    var repoInstructions =
        EvalTrailingGuidance.forCase(
            evalCase.diff(),
            relatedTests,
            orEmpty(evalCase.spec().prDescription()),
            orEmpty(evalCase.spec().patchCoverage()),
            orEmpty(evalCase.spec().learnings()));
    var inputs =
        new AiReviewService.PromptInputs(
            PromptTemplateEscaper.fence(evalCase.diff()),
            PromptTemplateEscaper.fence(
                PromptSections.prContext(
                    orEmpty(evalCase.spec().prTitle()), orEmpty(evalCase.spec().prDescription()))),
            "",
            "",
            PromptTemplateEscaper.fence(relatedTests),
            "",
            repoInstructions,
            "",
            dimensionRouter.systemPromptFor(evalCase.files()));
    var parsed = review(inputs);
    if (parsed == null) {
      return UNPARSED;
    }
    var found =
        parsed.findings().stream()
            .anyMatch(
                finding -> targetsCase(evalCase, finding) && mentionsKeyword(evalCase, finding));
    return found ? EvalCase.MUST_FIND : EvalCase.MUST_NOT_FIND;
  }

  /**
   * One review call, parsed. A response that does not parse, or a call that fails, is asked for
   * again, up to {@link #PARSE_ATTEMPTS} times, as {@code AiReviewService} retries any runtime
   * failure in production: one stray prose answer or one dropped stream used to abort the whole
   * run, losing every sample already taken. A sample that never parses returns {@code null} and is
   * scored {@link #UNPARSED}, a failed sample on every case, so a broken model or parser cannot
   * pass the must-not-find cases as reviews that found nothing. A call that outlives {@link
   * #SAMPLE_TIMEOUT_MINUTES} still ends the run.
   */
  private ReviewResponse review(AiReviewService.PromptInputs inputs) throws Exception {
    for (var attempt = 1; attempt <= PARSE_ATTEMPTS; attempt++) {
      var raw = new CompletableFuture<String>();
      prReviewer
          .reviewStream(inputs)
          .onPartialResponse(token -> {})
          .onCompleteResponse(response -> raw.complete(response.aiMessage().text()))
          .onError(raw::completeExceptionally)
          .start();
      try {
        return reviewResponseParser.parse(raw.get(SAMPLE_TIMEOUT_MINUTES, TimeUnit.MINUTES));
      } catch (ExecutionException _) {
        Log.infof("Eval sample attempt %d failed; retrying", attempt);
      } catch (RuntimeException _) {
        Log.infof("Eval sample attempt %d was not review JSON; retrying", attempt);
      }
    }
    Log.warnf("Eval sample produced no review in %d attempts", PARSE_ATTEMPTS);
    return null;
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
