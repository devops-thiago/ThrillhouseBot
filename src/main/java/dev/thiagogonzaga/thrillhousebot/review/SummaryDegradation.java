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

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * How (if at all) the review's summary prose was degraded. The findings themselves are complete in
 * every state — only the summary is affected — so none of these holds approval; they exist so the
 * posted review can disclose the degradation and name the knob to raise instead of staying
 * log-only. At most one degradation can occur per review: the summary call either had its response
 * cut at the model's length cap ({@link #RESPONSE_CUT}, #500 scope A) or was skipped (or refused
 * mid-call) at the review's token spend ceiling ({@link #SKIPPED_AT_CEILING}, #518) — the two are
 * reached on disjoint control paths, and a single enum keeps the impossible both-at-once state
 * unrepresentable where a pair of booleans would allow it.
 */
@RegisterForReflection
public enum SummaryDegradation {
  /** The summary call completed normally (or the review has no summary degradation to disclose). */
  NONE,

  /**
   * The summary response was cut at the model's length cap (max-output-tokens /
   * REVIEW_CONCISE_MAX_OUTPUT_TOKENS): the prose was salvaged from the cut response or replaced by
   * the counts-only fallback.
   */
  RESPONSE_CUT,

  /**
   * The summary call was skipped (or refused mid-call) because the review's token spend ceiling
   * (REVIEW_MAX_TOKENS_PER_REVIEW) was reached; the counts-only fallback stands in for the prose.
   */
  SKIPPED_AT_CEILING
}
