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

import java.util.EnumMap;
import java.util.Map;

/**
 * Where a published finding goes: an inline review thread on the diff, or a bullet in the PR
 * summary's collapsed "Things to double-check" block (#1077). The decision is this one table over
 * the finding's severity and confidence, and every surface reads it through {@link
 * Finding#postsInline}: the publisher, the summary's Key Findings and double-check lists, the
 * surface dedupe, the large-PR nudge and the follow-up rules that need to know whether a finding
 * has a thread.
 *
 * <pre>
 * risk \ confidence   high     medium   low
 * critical            inline   inline   inline
 * high                inline   inline   inline
 * medium              inline   inline   double-check
 * low                 inline   inline   double-check
 * </pre>
 *
 * <p>A critical or high finding stays on the diff at any confidence, so a severe but uncertain
 * defect is still where the author reads; its comment carries the "verify before acting" hedge. A
 * medium or low finding the reviewer itself doubts goes to the collapsed block, which renders it
 * with the low-confidence hedge ({@code PrSummaryGenerator}); every double-check cell is a "low"
 * confidence one, so that hedge is the right one for every item listed there.
 *
 * <p>The table is applied to the grades a finding publishes with, after every step that moves them
 * and on both review lanes: the verifier's lowering and its deterministic floors ({@code
 * FindingVerificationService.floorEstablishedConfidence}, which lifts a "low" finding whose
 * evidence is established, a failing CI check it cites or a taint path in the diff, to "medium", so
 * it posts inline), then {@link SeverityCalibrator}'s anchored classes. The grade a finding is
 * routed on is therefore its floored or anchored one, never the model's raw label.
 */
public enum FindingPlacement {
  /** An inline review thread on the finding's line. */
  INLINE,

  /** A bullet under "Things to double-check" in the PR summary, with no thread. */
  DOUBLE_CHECK;

  private static final Map<RiskLevel, Map<Confidence, FindingPlacement>> TABLE = table();

  private static Map<RiskLevel, Map<Confidence, FindingPlacement>> table() {
    var table = new EnumMap<RiskLevel, Map<Confidence, FindingPlacement>>(RiskLevel.class);
    table.put(RiskLevel.CRITICAL, row(INLINE, INLINE, INLINE));
    table.put(RiskLevel.HIGH, row(INLINE, INLINE, INLINE));
    table.put(RiskLevel.MEDIUM, row(INLINE, INLINE, DOUBLE_CHECK));
    table.put(RiskLevel.LOW, row(INLINE, INLINE, DOUBLE_CHECK));
    return table;
  }

  private static Map<Confidence, FindingPlacement> row(
      FindingPlacement high, FindingPlacement medium, FindingPlacement low) {
    var row = new EnumMap<Confidence, FindingPlacement>(Confidence.class);
    row.put(Confidence.HIGH, high);
    row.put(Confidence.MEDIUM, medium);
    row.put(Confidence.LOW, low);
    return row;
  }

  /** The placement of a finding graded {@code risk} at {@code confidence}. */
  public static FindingPlacement of(RiskLevel risk, Confidence confidence) {
    return TABLE.get(risk).get(confidence);
  }
}
