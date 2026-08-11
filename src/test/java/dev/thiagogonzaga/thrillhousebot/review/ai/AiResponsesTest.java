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

import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import org.junit.jupiter.api.Test;

/**
 * The single place every blocking command path decides whether a response was cut short. Its whole
 * job is to make a length stop distinguishable from bad JSON, so that is what these pin.
 */
class AiResponsesTest {

  @Test
  void returnsTheTextOfACompletedResponse() {
    assertEquals(
        "{\"docs\":[]}",
        AiResponses.textOrThrowOnTruncation(aiOk("{\"docs\":[]}"), "X", ModelLane.ACTIVE));
  }

  @Test
  void passesANullResultThrough() {
    // "no response" is a different condition from "cut short" and the callers already handle it.
    assertNull(AiResponses.textOrThrowOnTruncation(null, "X", ModelLane.ACTIVE));
  }

  @Test
  void passesAnAbsentContentBodyThrough() {
    // A reasoning model can burn the whole output budget on reasoning tokens and complete with no
    // content and no length stop. That is the same "no response" soft failure, not a truncation.
    assertNull(AiResponses.textOrThrowOnTruncation(aiNoContent(), "X", ModelLane.CONCISE));
  }

  @Test
  void throwsOnALengthStopRatherThanReturningTheCutBody() {
    // Returning the partial would send it to a JSON parser, which reports "not valid JSON" — the
    // exact misdiagnosis this exists to prevent.
    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () ->
                AiResponses.textOrThrowOnTruncation(
                    aiTruncated("{\"docs\":[{\"file\":\"A"), "X", ModelLane.ACTIVE));

    assertTrue(
        thrown.getMessage().contains("max-output-tokens"),
        "the message must name the knob an operator can change: " + thrown.getMessage());
  }

  @Test
  void namesTheActiveModelsCapForACallOnTheDefaultModel() {
    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () ->
                AiResponses.textOrThrowOnTruncation(
                    aiTruncated("partial"), "/improve assistant", ModelLane.ACTIVE));

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
    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () ->
                AiResponses.textOrThrowOnTruncation(
                    aiTruncated("{\"verdicts\":[{\"id"),
                    "Finding verification",
                    ModelLane.CONCISE));

    assertTrue(
        thrown.getMessage().contains("raise REVIEW_CONCISE_MAX_OUTPUT_TOKENS"),
        "the message must name the knob that actually caps this lane: " + thrown.getMessage());
    assertTrue(
        thrown.conciseModelImplicated(),
        "the failure must carry the concise flag so downstream copy names the same knob");
  }

  @Test
  void namesTheCallSoTheOperatorKnowsWhichCommandWasCutShort() {
    var thrown =
        assertThrows(
            AiResponseTruncatedException.class,
            () ->
                AiResponses.textOrThrowOnTruncation(
                    aiTruncated("partial"), "/improve assistant", ModelLane.ACTIVE));

    assertTrue(thrown.getMessage().startsWith("/improve assistant"), thrown.getMessage());
  }
}
