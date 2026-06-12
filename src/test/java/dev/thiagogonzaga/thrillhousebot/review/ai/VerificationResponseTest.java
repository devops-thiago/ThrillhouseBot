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
  void shouldAcceptEmptyVerdictList() {
    assertTrue(new VerificationResponse(List.of()).verdicts().isEmpty());
  }
}
