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
import org.junit.jupiter.params.provider.CsvSource;
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

  /** ThrillhouseBot-test#203, round 2: the carried label used as the subject of a status clause. */
  @Test
  void aLabelLeadingAStatusClauseLeavesOnlyTheGap() {
    var gap =
        "G2 stands partially unaddressed: the description omits the deployment/CI half"
            + " (Dockerfile, workflow).";

    assertEquals(
        List.of("The description omits the deployment/CI half (Dockerfile, workflow)."),
        DescriptionGapLabels.stripIssued(List.of(gap), 2));
    assertEquals(
        List.of("The description omits the deployment/CI half."),
        DescriptionGapLabels.stripIssued(
            List.of(
                "**G2 stands partially unaddressed**: the description omits the"
                    + " deployment/CI half."),
            2));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "G1 is still open — the cap is not enforced.|The cap is not enforced.",
        "**G1**: the cap is not enforced.|The cap is not enforced.",
        "G2 is still not addressed by any file changed here: the CI half.|"
            + "A previously listed gap is still not addressed by any file changed here: the CI"
            + " half.",
        "G2 remains open because no workflow builds the image.|"
            + "A previously listed gap remains open because no workflow builds the image.",
        "G1 and G2 are both still open since nothing changed.|"
            + "Previously listed gaps are both still open since nothing changed.",
        "G2 the description omits the CI half.|The description omits the CI half.",
        "The TTL is a constant, as in G2.|The TTL is a constant.",
        "The TTL is a constant (see G1) and never read.|The TTL is a constant and never read.",
        "The TTL is a constant (G1, G2).|The TTL is a constant.",
        "The TTL is a constant (G3, see G1).|The TTL is a constant (G3).",
        "The cap is missing (as with G1).|The cap is missing.",
        "The cap is missing, cf G2.|The cap is missing.",
        "The TTL is a constant (per the issue; see G2).|The TTL is a constant (per the issue).",
        "The TTL is a constant (not configurable).|The TTL is a constant (not configurable).",
        "The cap is missing, same as G1; the TTL too.|The cap is missing; the TTL too.",
        "Unlike G2, the TTL half is covered.|Unlike a previously listed gap, the TTL half is"
            + " covered.",
        "Unlike G1 and G3, the TTL half is covered.|Unlike a previously listed gap and G3, the"
            + " TTL half is covered.",
        "G1 and G3 remain open here.|A previously listed gap and G3 remain open here.",
        "The cap is missing, same as G2 and G3.|The cap is missing, same as a previously listed"
            + " gap and G3."
      })
  void anIssuedLabelUsedAsASubjectOrAReferenceIsRewritten(String gap, String expected) {
    assertEquals(List.of(expected), DescriptionGapLabels.stripIssued(List.of(gap), 2));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "G3 stands partially unaddressed: the description omits the CI half.",
        "The TTL is a constant, as in G3.",
        "G1 GC is named in the description, but the JVM flags select ZGC.",
        "The flags select the G1 GC, not ZGC.",
        "The flags select ZGC (see G1 GC).",
        "G1-specific pause targets are documented but never set.",
        "Gateway retries are described as bounded, but the loop has no limit.",
        "The description says G12 is supported.",
        "G0 is the baseline profile, but the code reads G3 settings."
      })
  void aLabelTheRoundDidNotIssueOrANameIsLeftAsWritten(String gap) {
    assertEquals(List.of(gap), DescriptionGapLabels.stripIssued(List.of(gap), 2));
  }

  @Test
  void withNoLabelsIssuedEveryGapIsLeftAsWritten() {
    var gaps =
        List.of(
            "G1 GC is named in the description, but the JVM flags select ZGC.",
            "G2 stands partially unaddressed: the description omits the CI half.");

    assertEquals(gaps, DescriptionGapLabels.stripIssued(gaps, 0));
  }

  @Test
  void aGapThatWasOnlyAReferenceIsDropped() {
    assertEquals(
        List.of(ISSUE_GAP), DescriptionGapLabels.stripIssued(List.of("(see G1)", ISSUE_GAP), 1));
  }

  /**
   * ThrillhouseBot-test#206, round 2: the carried gap reported again with its label in a trailing
   * parenthetical next to a status remark; the remark stays, the label goes.
   */
  @Test
  void aLabelInsideAParentheticalWithARemarkLeavesTheRemark() {
    var gap =
        "Linked issue #31: abstracts over 200 characters are rejected — the endpoint accepts"
            + " over-long abstracts with 201 instead of rejecting them (G1, still not addressed).";

    assertEquals(
        List.of(
            "Linked issue #31: abstracts over 200 characters are rejected — the endpoint accepts"
                + " over-long abstracts with 201 instead of rejecting them (still not addressed)."),
        DescriptionGapLabels.stripIssued(List.of(gap), 1));
    assertEquals(
        List.of("The endpoint accepts over-long abstracts with 201."),
        DescriptionGapLabels.stripIssued(
            List.of("The endpoint accepts over-long abstracts with 201 (G1)."), 1));
    assertEquals(
        List.of(gap.replace("G1", "G2")),
        DescriptionGapLabels.stripIssued(List.of(gap.replace("G1", "G2")), 1));
  }
}
