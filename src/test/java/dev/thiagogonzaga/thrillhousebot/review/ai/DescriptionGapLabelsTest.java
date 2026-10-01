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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DescriptionGapLabelsTest {

  private static final String ISSUE_GAP =
      "Linked issue #119: patient with an existing bed rejected with 409 Conflict — no changed"
          + " line implements this check.";

  /** ThrillhouseBot-test#185, round 3: the label echoed in front of the carried gap. */
  @Test
  void aLabelInFrontOfAGapIsRemoved() {
    assertEquals(List.of(ISSUE_GAP), DescriptionGapLabels.stripAll(List.of("G1: " + ISSUE_GAP)));
  }

  /** ThrillhouseBot-test#186, round 3: a bullet that was only the label, before the real gap. */
  @Test
  void aGapThatIsOnlyALabelIsDropped() {
    assertEquals(List.of(ISSUE_GAP), DescriptionGapLabels.stripAll(List.of("G1", ISSUE_GAP)));
    assertEquals(
        List.of(), DescriptionGapLabels.stripAll(List.of("G12", " [G3] ", "(G4):", "G2 —")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "G1: the TTL is a constant.",
        "G12: the TTL is a constant.",
        "  G1 : the TTL is a constant.",
        "G1 — the TTL is a constant.",
        "G1 - the TTL is a constant.",
        "[G1] the TTL is a constant.",
        "[ G1 ]: the TTL is a constant.",
        "(G1) the TTL is a constant.",
        "(G1) – the TTL is a constant."
      })
  void everyLabelShapeTheModelEchoesIsRemoved(String gap) {
    assertEquals(List.of("the TTL is a constant."), DescriptionGapLabels.stripAll(List.of(gap)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Gateway retries are described as bounded, but the loop has no limit.",
        "G1 GC is named in the description, but the JVM flags select ZGC.",
        "G1-specific pause targets are documented but never set.",
        "GDPR export is promised but no endpoint is added.",
        "The description says G1: it is not.",
        "",
        "  "
      })
  void aGapWithoutALeadingLabelIsLeftAsWritten(String gap) {
    assertEquals(List.of(gap), DescriptionGapLabels.stripAll(List.of(gap)));
  }
}
