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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import org.junit.jupiter.api.Test;

/** Each lane's cap comes from the setting that supplies it to that lane's model builder (#895). */
class ResponseCapsTest {

  @Test
  void theActiveLaneCarriesTheActiveModelsMaxOutputTokensUnderItsPerModelKey() {
    var cap = TestResponseCaps.of(96_000, 8192).forLane(ModelLane.ACTIVE);

    assertEquals(ModelLane.ACTIVE, cap.lane());
    assertEquals(96_000, cap.tokens());
    assertEquals(
        "thrillhousebot.ai.models.\"" + TestResponseCaps.MODEL + "\".max-output-tokens",
        cap.setting());
  }

  @Test
  void theConciseLaneCarriesTheConciseCapNotTheActiveModels() {
    var cap = TestResponseCaps.of(96_000, 8192).forLane(ModelLane.CONCISE);

    assertEquals(ModelLane.CONCISE, cap.lane());
    assertEquals(8192, cap.tokens());
    assertEquals("REVIEW_CONCISE_MAX_OUTPUT_TOKENS", cap.setting());
  }

  @Test
  void anUnsetSettingMeansTheRequestCarriedNoCap() {
    var caps = TestResponseCaps.of(null, null);

    assertNull(caps.forLane(ModelLane.ACTIVE).tokens());
    assertNull(caps.forLane(ModelLane.CONCISE).tokens());
  }
}
