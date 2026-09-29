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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiNoContent;
import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiOk;
import static dev.thiagogonzaga.thrillhousebot.review.ai.AiResults.aiTruncated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import org.junit.jupiter.api.Test;

/**
 * The single place every blocking command path decides whether a response was cut short. Its whole
 * job is to make a length stop distinguishable from bad JSON, so that is what these pin.
 */
class AiResponsesTest {

  private static final String ACTIVE_SETTING = "thrillhousebot.ai.models.\"m\".max-output-tokens";

  private static final ResponseCap ACTIVE_CAP =
      new ResponseCap(ModelLane.ACTIVE, 8192, ACTIVE_SETTING);

  private static final ResponseCap CONCISE_CAP =
      new ResponseCap(ModelLane.CONCISE, 8192, "REVIEW_CONCISE_MAX_OUTPUT_TOKENS");

  /** A length stop whose provider did report usage, as the blocking lanes' Result carries it. */
  private static Result<String> truncatedWithUsage(String partial, int prompt, int completion) {
    return Result.<String>builder()
        .content(partial)
        .finishReason(FinishReason.LENGTH)
        .tokenUsage(new TokenUsage(prompt, completion))
        .build();
  }

  @Test
  void aBlockingStopAtTheLicensedCapStatesTheFiguresAndNamesTheSettingThatSuppliedIt() {
    // #895: the figures come off the blocking Result too, so a /improve cut at its cap says what
    // was licensed, what was billed, and which setting to raise.
    var cutShort = truncatedWithUsage("partial", 1200, 8192);

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () -> AiResponses.textOrThrowOnTruncation(cutShort, "/improve assistant", ACTIVE_CAP));

    var message = thrown.getMessage();
    assertTrue(message.contains("licensed max_tokens=8192 (from " + ACTIVE_SETTING + ")"), message);
    assertTrue(message.contains("billed 8192 completion tokens, 1200 prompt tokens"), message);
    assertTrue(message.contains("so raise " + ACTIVE_SETTING), message);
    assertEquals(TruncationReport.Stop.AT_CAP, thrown.report().stop());
    assertEquals(1200, thrown.inputTokens());
    assertEquals(8192, thrown.outputTokens());
  }

  @Test
  void aBlockingStopShortOfTheLicensedCapDoesNotAdviseRaisingIt() {
    // The production shape: max_tokens=96000 licensed, the provider stopped at 65536. A higher
    // setting would change nothing, so the message must not send the operator to it.
    var cap = new ResponseCap(ModelLane.ACTIVE, 96_000, ACTIVE_SETTING);

    var cutShort = truncatedWithUsage("partial", 163_342, 65_536);

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () -> AiResponses.textOrThrowOnTruncation(cutShort, "/improve assistant", cap));

    var message = thrown.getMessage();
    assertTrue(message.contains("billed 65536 completion tokens, 163342 prompt tokens"), message);
    assertTrue(message.contains("30464 tokens short of the licensed cap"), message);
    assertTrue(message.contains("will not move it"), message);
    assertFalse(message.contains("so raise"), message);
    assertFalse(message.contains("Raise the active model's"), message);
  }

  @Test
  void aConciseStopAtItsCapNamesTheConciseSettingNotTheActiveModels() {
    // #581 carried into #895: at the cap on the concise lane, the setting to raise is the concise
    // one, and the aside says why the active model's is not.
    var cutShort = truncatedWithUsage("{\"verdicts\":[", 900, 8192);

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () ->
                AiResponses.textOrThrowOnTruncation(cutShort, "Finding verification", CONCISE_CAP));

    var message = thrown.getMessage();
    assertTrue(message.contains("so raise REVIEW_CONCISE_MAX_OUTPUT_TOKENS"), message);
    assertTrue(message.contains("does not cap it"), message);
    assertTrue(thrown.conciseModelImplicated());
  }

  @Test
  void aStopWithoutUsageKeepsTheLanesAdviceAndSaysTheUsageWasNotReported() {
    var cutShort = aiTruncated("partial");

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () -> AiResponses.textOrThrowOnTruncation(cutShort, "/improve assistant", ACTIVE_CAP));

    var message = thrown.getMessage();
    assertTrue(message.contains("usage not reported by the provider"), message);
    assertTrue(message.contains("licensed max_tokens=8192"), message);
    assertTrue(message.contains("Raise the active model's max-output-tokens"), message);
  }

  @Test
  void returnsTheTextOfACompletedResponse() {
    assertEquals(
        "{\"docs\":[]}",
        AiResponses.textOrThrowOnTruncation(aiOk("{\"docs\":[]}"), "X", ACTIVE_CAP));
  }

  @Test
  void passesANullResultThrough() {
    // "no response" is a different condition from "cut short" and the callers already handle it.
    assertNull(AiResponses.textOrThrowOnTruncation(null, "X", ACTIVE_CAP));
  }

  @Test
  void passesAnAbsentContentBodyThrough() {
    // A reasoning model can burn the whole output budget on reasoning tokens and complete with no
    // content and no length stop. That is the same "no response" soft failure, not a truncation.
    assertNull(AiResponses.textOrThrowOnTruncation(aiNoContent(), "X", CONCISE_CAP));
  }

  @Test
  void throwsOnALengthStopRatherThanReturningTheCutBody() {
    // Returning the partial would send it to a JSON parser, which reports "not valid JSON" — the
    // exact misdiagnosis this exists to prevent.
    var cutShort = aiTruncated("{\"docs\":[{\"file\":\"A");

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () -> AiResponses.textOrThrowOnTruncation(cutShort, "X", ACTIVE_CAP));

    assertTrue(
        thrown.getMessage().contains("max-output-tokens"),
        "the message must name the knob an operator can change: " + thrown.getMessage());
  }

  @Test
  void namesTheActiveModelsCapForACallOnTheDefaultModel() {
    var cutShort = aiTruncated("partial");

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () -> AiResponses.textOrThrowOnTruncation(cutShort, "/improve assistant", ACTIVE_CAP));

    assertTrue(
        thrown.getMessage().contains("Raise the active model's max-output-tokens"),
        thrown.getMessage());
    assertFalse(
        thrown.getMessage().contains("REVIEW_CONCISE_MAX_OUTPUT_TOKENS"),
        "the concise cap does not bound a default-model call: " + thrown.getMessage());
    assertFalse(thrown.conciseModelImplicated(), "the call did not run on the concise model");
  }

  @Test
  void namesTheConciseCapForACallOnTheConciseModel() {
    // The concise named model never receives the active model's max-output-tokens, so telling the
    // operator to raise it would send them to a setting that cannot affect this call.
    var cutShort = aiTruncated("{\"verdicts\":[{\"id");

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () ->
                AiResponses.textOrThrowOnTruncation(cutShort, "Finding verification", CONCISE_CAP));

    assertTrue(
        thrown.getMessage().contains("raise REVIEW_CONCISE_MAX_OUTPUT_TOKENS"),
        "the message must name the knob that actually caps this lane: " + thrown.getMessage());
    assertTrue(
        thrown.conciseModelImplicated(),
        "the failure must carry the concise flag so downstream copy names the same knob");
  }

  @Test
  void carriesTheCutBodyOnTheFailureSoTheLaneCanSalvageIt() {
    // #580: Result#content() holds what the model produced before the cap stopped it — output that
    // was generated and billed. Dropping it here left every blocking lane with nothing to salvage
    // from, however much of its response had completed before the cut.
    var cut = "{\"verdicts\":[{\"id\":1,\"verdict\":\"valid\"},{\"id\":2,\"verd";
    var cutShort = aiTruncated(cut);

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () ->
                AiResponses.textOrThrowOnTruncation(cutShort, "Finding verification", CONCISE_CAP));

    assertEquals(cut, thrown.partialBody(), "the paid, cut body must travel with the failure");
  }

  @Test
  void theCarriedBodyIsWhatTheSalvagerRecoversTheCompletedElementsFrom() {
    // The point of carrying it: the body is well-formed up to the cut, so the review lane's own
    // salvage machinery recovers the elements that closed. Pinned end to end because a body no
    // consumer could use would be no better than the null it replaced.
    var cut = "{\"verdicts\":[{\"id\":1,\"verdict\":\"valid\"},{\"id\":2,\"verd";
    var cutShort = aiTruncated(cut);

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () ->
                AiResponses.textOrThrowOnTruncation(cutShort, "Finding verification", CONCISE_CAP));

    var salvaged =
        new TruncatedResponseSalvager(new ObjectMapper())
            .salvageArray(thrown.partialBody(), "verdicts", VerificationResponse.Verdict.class);

    assertEquals(1, salvaged.size(), "the verdict that closed before the cut is recoverable");
    assertEquals(1, salvaged.get(0).id());
  }

  @Test
  void namesTheCallSoTheOperatorKnowsWhichCommandWasCutShort() {
    var cutShort = aiTruncated("partial");

    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () -> AiResponses.textOrThrowOnTruncation(cutShort, "/improve assistant", ACTIVE_CAP));

    assertTrue(thrown.getMessage().startsWith("/improve assistant"), thrown.getMessage());
  }
}
