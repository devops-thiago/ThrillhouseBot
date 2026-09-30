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

import java.util.Set;

/**
 * The review dimensions of {@link PrReviewPrompts#SYSTEM}, in prompt order (#665). Each one is a
 * block of its own ({@link PrReviewPrompts#dimensionBlock}), so a review call can carry only the
 * blocks its files can use. The first three are always on: correctness, security and regressions
 * apply to any change, and no signal is cheap and certain enough to route them out.
 *
 * <p>The number is the block's position in prompt order and appears only in logs. The prompt prints
 * none (#918): the core names a dimension by what it checks, never by number, because a model that
 * sees numbered headings cites them back in the findings a maintainer reads.
 */
public enum ReviewDimension {
  FUNCTIONAL_CORRECTNESS(1, "functional-correctness", true),
  SECURITY(2, "security", true),
  REGRESSIONS(3, "regressions", true),
  COMMENT_CONTRADICTS_CODE(4, "comment-contradicts-code", false),
  CODE_QUALITY_AND_COMPLEXITY(5, "code-quality-and-complexity", false),
  PAGINATION(6, "pagination", false),
  CONFIG_IAC(7, "config-iac", false),
  MOCK_FIDELITY(8, "mock-fidelity", false),
  PRODUCER_CONSUMER(9, "producer-consumer", false),
  CONFIG_KEY_DOCUMENTATION(10, "config-key-documentation", false);

  /** Every dimension: the monolithic prompt, and what a call gets with routing off. */
  public static final Set<ReviewDimension> ALL = Set.of(values());

  private final int number;
  private final String label;
  private final boolean alwaysOn;

  ReviewDimension(int number, String label, boolean alwaysOn) {
    this.number = number;
    this.label = label;
    this.alwaysOn = alwaysOn;
  }

  /**
   * The dimension's position in the prompt's order, for logs. The prompt itself prints no number
   * (#918): a numbered heading is a label the model cites back in posted findings.
   */
  public int number() {
    return number;
  }

  /** A stable lower-case name for logs, e.g. {@code 7:config-iac}. */
  public String label() {
    return number + ":" + label;
  }

  /** Whether every review call carries this dimension, whatever its files. */
  public boolean alwaysOn() {
    return alwaysOn;
  }
}
