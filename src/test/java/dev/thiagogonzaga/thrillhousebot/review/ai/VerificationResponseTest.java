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

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerificationResponseTest {

  @Test
  void shouldNormalizeNullVerdictsToEmptyList() {
    assertTrue(new VerificationResponse(null).verdicts().isEmpty());
  }

  @Test
  void shouldCopyVerdictsDefensively() {
    var verdicts = new ArrayList<VerificationResponse.Verdict>();
    verdicts.add(new VerificationResponse.Verdict(1, "confirmed", "low", "high", "ok"));

    var response = new VerificationResponse(verdicts);
    verdicts.clear();

    List<VerificationResponse.Verdict> copied = response.verdicts();
    assertEquals(1, copied.size());
    assertThrows(UnsupportedOperationException.class, () -> copied.remove(0));
  }

  @Test
  void verdictShouldExposeAllFields() {
    var verdict = new VerificationResponse.Verdict(2, "rejected", "medium", "low", "speculative");

    assertEquals(2, verdict.id());
    assertEquals("rejected", verdict.verdict());
    assertEquals("medium", verdict.risk());
    assertEquals("low", verdict.confidence());
    assertEquals("speculative", verdict.reason());
  }

  @Test
  void theSuggestionJudgmentAndCutsAreRead() throws Exception {
    // #1080: the fields the verifier now answers beside the verdict.
    var response =
        new ObjectMapper()
            .readValue(
                """
                {"verdicts": [{"id": 1, "verdict": "confirmed", "suggestion": "replace",
                  "suggestion_new": "COPY --from=build /out/pickbatch /usr/local/bin/pickbatchd",
                  "cut": ["The constant is misspelt."]}]}
                """,
                VerificationResponse.class);

    var verdict = response.verdicts().get(0);
    assertEquals("replace", verdict.suggestion());
    assertEquals(
        "COPY --from=build /out/pickbatch /usr/local/bin/pickbatchd", verdict.suggestionNew());
    assertEquals(List.of("The constant is misspelt."), verdict.cut());
  }

  @Test
  void strayShapesInTheNewFieldsAreReadAsFarAsTheyHoldText() throws Exception {
    var response =
        new ObjectMapper()
            .readValue(
                """
                {"verdicts": [
                  {"id": 1, "verdict": "confirmed", "suggestion": {"kind": "drop"},
                   "suggestion_new": ["line one", 2, "line two"], "cut": "One sentence."},
                  {"id": 2, "verdict": "rejected", "suggestion_new": 7, "cut": [null, 3, "Kept."]},
                  {"id": 3, "verdict": "confirmed", "cut": null, "suggestion": null}]}
                """,
                VerificationResponse.class);

    var first = response.verdicts().get(0);
    assertNull(first.suggestion(), "an object holds no label");
    assertEquals("line one\nline two", first.suggestionNew());
    assertEquals(List.of("One sentence."), first.cut());
    var second = response.verdicts().get(1);
    assertNull(second.suggestionNew());
    assertEquals(List.of("Kept."), second.cut());
    var third = response.verdicts().get(2);
    assertEquals(List.of(), third.cut());
    assertNull(third.suggestion());
  }

  @Test
  void aVerdictWithoutTheNewFieldsHasNothingToApply() {
    var verdict = new VerificationResponse.Verdict(1, "confirmed", "low", "high", "ok");

    assertNull(verdict.suggestion());
    assertNull(verdict.suggestionNew());
    assertEquals(List.of(), verdict.cut());
    var withNulls =
        new VerificationResponse.Verdict(
            1, "confirmed", "low", "high", "ok", null, null, java.util.Arrays.asList("a", null));
    assertEquals(List.of("a"), withNulls.cut());
  }

  @Test
  void shouldAcceptEmptyVerdictList() {
    assertTrue(new VerificationResponse(List.of()).verdicts().isEmpty());
  }
}
