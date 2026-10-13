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
package dev.thiagogonzaga.thrillhousebot.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link CiFailureOverview}: the overview's sentence on each failing check is computed (#1075). */
class CiFailureOverviewTest {

  private static final String SECTION =
      """
      ### test (conclusion: failure)
      Failing tests named in the job log:
      - \u2716 returns exactly `slots` applications when enough are eligible (1.549323ms)
      """;

  /** The round-2 purpose on ThrillhouseBot-test#254 (6125936933), opening and closing sentence. */
  private static final String NODE_PURPOSE =
      "Introduces a new TypeScript service under node/ that pulls open grant applications. CI on"
          + " this commit fails the check 'test' (failing test 'returns exactly `slots`"
          + " applications when enough are eligible'), which no finding among the three verified"
          + " above explains.";

  /** The finding that explains that test (4248942723), open since round 1. */
  private static final Finding SHORTLIST =
      new Finding(
          RiskLevel.HIGH,
          "node/src/scoring.ts",
          36,
          "Shortlist returns slots-1 items, violating the 25-slot contract",
          "The in-diff test 'returns exactly `slots` applications when enough are eligible'"
              + " asserts this directly, and the CI check 'test' on this commit already failed on"
              + " it.",
          null,
          null);

  private static ReviewResponse.Summary summary(String assessment, String purpose) {
    return new ReviewResponse.Summary(
        1, 0, 1, 0, 0, assessment, purpose, List.of(), List.of(), List.of(), null, List.of());
  }

  /**
   * An open finding cites the failing test: the model's "no finding explains" sentence is replaced.
   */
  @Test
  void aCitedFailureReplacesTheModelsSentenceWithTheExplainingFinding() {
    var applied =
        CiFailureOverview.apply(
            summary(
                "Request changes. The test check fails, which no finding explains.", NODE_PURPOSE),
            CiFailureCitations.parse(SECTION),
            List.of(SHORTLIST));

    assertEquals(
        "Introduces a new TypeScript service under node/ that pulls open grant applications. CI on"
            + " this commit fails the check \"test\" (failing test \"returns exactly `slots`"
            + " applications when enough are eligible\"), which the finding \"Shortlist returns"
            + " slots-1 items, violating the 25-slot contract\" explains.",
        applied.prPurpose());
    assertEquals("Request changes.", applied.overallAssessment());
  }

  /** No open finding cites the failure: the computed sentence states it alone (#1091). */
  @Test
  void anUncitedFailureIsStatedAlone() {
    var unrelated =
        new Finding(RiskLevel.MEDIUM, "node/src/triage.ts", 18, "O(n^2) scan", "Slow.", null, null);

    var applied =
        CiFailureOverview.apply(
            summary("Request changes.", NODE_PURPOSE),
            CiFailureCitations.parse(SECTION),
            List.of(unrelated));

    assertEquals(
        "Introduces a new TypeScript service under node/ that pulls open grant applications. CI on"
            + " this commit fails the check \"test\" (failing test \"returns exactly `slots`"
            + " applications when enough are eligible\").",
        applied.prPurpose());
  }

  /**
   * A purpose that already holds the computed sentence, in either form, gets it once: the stated
   * failure names no finding, so it is removed by its opening rather than as a verdict (#1091).
   */
  @Test
  void applyingTwiceKeepsOneSentencePerCheck() {
    var unrelated =
        new Finding(RiskLevel.MEDIUM, "node/src/triage.ts", 18, "O(n^2) scan", "Slow.", null, null);
    var citations = CiFailureCitations.parse(SECTION);

    for (var open : List.of(List.of(SHORTLIST), List.of(unrelated))) {
      var once =
          CiFailureOverview.apply(summary("Request changes.", NODE_PURPOSE), citations, open);
      var twice = CiFailureOverview.apply(once, citations, open);

      assertEquals(once.prPurpose(), twice.prPurpose());
    }
    var stated =
        "Adds a scheduler. CI on this commit fails the check \"test\" (failing test \"returns"
            + " exactly `slots` applications when enough are eligible\").";
    assertEquals(
        "Adds a scheduler.",
        CiFailureOverview.apply(
                summary("Request changes.", stated), CiFailureCitations.NONE, List.of())
            .prPurpose());
  }

  /**
   * With no CI-failure section, a carried overview's stale sentence still goes, and nothing is
   * added; a summary with nothing to change is returned as it was.
   */
  @Test
  void withoutASectionOnlyTheModelsSentenceGoes() {
    var applied =
        CiFailureOverview.apply(
            summary("Request changes.", NODE_PURPOSE), CiFailureCitations.NONE, List.of());

    assertEquals(
        "Introduces a new TypeScript service under node/ that pulls open grant applications.",
        applied.prPurpose());
    var clean = summary("Request changes.", "Adds a scheduler.");
    assertSame(clean, CiFailureOverview.apply(clean, CiFailureCitations.NONE, List.of()));
    assertNull(CiFailureOverview.apply(null, CiFailureCitations.parse(SECTION), List.of()));
  }

  /** A purpose that held only the call's CI verdict is replaced by the computed sentence. */
  @Test
  void aPurposeOfOnlyTheCallsVerdictBecomesTheComputedSentence() {
    var onlyVerdict =
        "CI on this commit fails the check 'test', which no finding among the three verified above"
            + " explains.";

    var applied =
        CiFailureOverview.apply(
            summary("Request changes.", onlyVerdict),
            CiFailureCitations.parse(SECTION),
            List.of(SHORTLIST));

    assertEquals(
        "CI on this commit fails the check \"test\" (failing test \"returns exactly `slots`"
            + " applications when enough are eligible\"), which the finding \"Shortlist returns"
            + " slots-1 items, violating the 25-slot contract\" explains.",
        applied.prPurpose());
  }

  /** A summary with no purpose paragraph gets no lone CI sentence. */
  @Test
  void aBlankPurposeGetsNoSentence() {
    var blank = summary("Request changes.", "");

    assertSame(blank, CiFailureOverview.apply(blank, CiFailureCitations.parse(SECTION), List.of()));
    var none = summary("Request changes.", null);
    assertSame(none, CiFailureOverview.apply(none, CiFailureCitations.parse(SECTION), List.of()));
  }

  /** Sentences about the change that mention checks, findings or explanations stay. */
  @Test
  void ordinarySentencesStay() {
    var purpose =
        "Adds a health check and explains the retry policy in the docs. The findings page lists"
            + " each check. It explains why the CI job runs nightly. This finding explains the"
            + " cache policy. Adds a bounds check on totals and explains the finding that flagged"
            + " the wrap. The finding explains why parsing fails on empty input.";

    assertSame(purpose, CiFailureOverview.withoutCiVerdicts(purpose));
    assertNull(CiFailureOverview.withoutCiVerdicts(null));
  }
}
