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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How severely a finding must score before {@link ReviewState#fromFindings} escalates to {@link
 * ReviewState#REQUEST_CHANGES}. Operators pick a mode for their risk appetite; the finding verifier
 * still demotes hedged claims first — this only interprets the post-verifier risk/confidence pair.
 */
public enum BlockingStrictness {
  /**
   * Default: only {@link RiskLevel#CRITICAL}/{@link RiskLevel#HIGH} findings the model is {@link
   * Confidence#HIGH}ly confident in block the merge.
   */
  BALANCED,

  /**
   * Any {@link RiskLevel#CRITICAL}/{@link RiskLevel#HIGH} finding blocks, regardless of confidence.
   * Prefer for security-heavy repos; note that verifier demotions to medium/low confidence will
   * still block under this mode.
   */
  STRICT,

  /** Only {@link RiskLevel#CRITICAL} findings with {@link Confidence#HIGH} block the merge. */
  LENIENT;

  /** Values accepted by {@link #fromString(String)}, in ascending strictness order. */
  public static final List<String> ALLOWED = List.of("lenient", "balanced", "strict");

  /** Wire / config value normalized to lowercase. */
  public static String normalize(String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Parses a config string into a mode. Returns empty when the value is not one of {@link #ALLOWED}
   * (callers reject at boot rather than silently falling back).
   */
  public static Optional<BlockingStrictness> fromString(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return switch (normalize(value)) {
      case "balanced" -> Optional.of(BALANCED);
      case "strict" -> Optional.of(STRICT);
      case "lenient" -> Optional.of(LENIENT);
      default -> Optional.empty();
    };
  }

  /**
   * Whether this finding alone is enough to escalate the review to {@code REQUEST_CHANGES}. Two
   * independent gates, kept separate on purpose (#645): {@link #severityQualifies(RiskLevel)} asks
   * whether the defect class is severe enough for this mode, and the confidence gate asks whether
   * the review demonstrated it. Only {@link #STRICT} drops the second one.
   */
  public boolean isBlocking(Finding finding) {
    if (finding == null) {
      return false;
    }
    return severityQualifies(finding.risk())
        && (this == STRICT || finding.confidence() == Confidence.HIGH);
  }

  /**
   * Whether this finding cleared the severity gate but was denied blocking eligibility by the
   * confidence gate alone — the case where the verdict turns on a hedge rather than on the risk.
   *
   * <p>This is the silent demotion #645 measured: a hedge the review prompt itself mandates when a
   * mitigation is not visible in the diff (#575) removes a HIGH finding from blocking
   * consideration, and the published finding says only "verify before acting". Surfacing the
   * predicate is what lets {@link VerdictBuilder} say so where the verdict is explained; the gate
   * itself is unchanged, so no finding starts or stops blocking because of this method.
   *
   * <p>Always {@code false} under {@link #STRICT}, which has no confidence gate to withhold on.
   */
  public boolean withheldByConfidence(Finding finding) {
    return finding != null && severityQualifies(finding.risk()) && !isBlocking(finding);
  }

  /** Whether the finding's risk alone meets this mode's bar, before confidence is considered. */
  private boolean severityQualifies(RiskLevel risk) {
    return this == LENIENT ? risk == RiskLevel.CRITICAL : isCriticalOrHigh(risk);
  }

  private static boolean isCriticalOrHigh(RiskLevel risk) {
    return risk == RiskLevel.CRITICAL || risk == RiskLevel.HIGH;
  }
}
