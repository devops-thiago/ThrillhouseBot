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
 * How much of the review's finding set the second-pass verification audit actually covered: {@code
 * candidates} findings were handed to the verifier, and {@code verified} of them received a
 * verdict. The verifier fails open by design — a finding it never ruled on posts exactly as the
 * reviewer raised it — so this record exists purely so the posted review can disclose that state
 * instead of leaving it log-only (#623): production measured roughly one review in three publishing
 * findings no second stage had screened, with nothing on any surface saying so.
 *
 * <p>The gap between the two counts arises on the verifier's known soft-failure paths: an empty
 * response body, a response cut mid-JSON whose salvage recovered only the verdicts that closed
 * before the cut (#546/#617), a call skipped at the review's token spend ceiling, or any other
 * error the fail-open contract absorbs. None of these gates approval — the disclosure changes what
 * the review says, never what it decides.
 */
@RegisterForReflection
public record VerificationCoverage(int candidates, int verified) {

  /** No verification to account for: the verifier was disabled, or there was nothing to verify. */
  public static final VerificationCoverage EMPTY = new VerificationCoverage(0, 0);

  /**
   * The disclosure-facing shape of the two counts. Only {@link #PARTIAL} and {@link #NONE} render a
   * clause; {@link #FULL} also covers the nothing-to-verify state, where there is no degradation to
   * disclose.
   */
  public enum Outcome {
    /** Every candidate received a verdict (or there were no candidates); nothing to disclose. */
    FULL,
    /** Some candidates received a verdict; the rest posted unverified. */
    PARTIAL,
    /** No candidate received a verdict; the whole finding set posted unverified. */
    NONE
  }

  public VerificationCoverage {
    // Salvage counts are best-effort over model output; clamping keeps the record's invariant
    // (0 <= verified <= candidates) instead of trusting every caller to.
    candidates = Math.max(candidates, 0);
    verified = Math.clamp(verified, 0, candidates);
  }

  public Outcome outcome() {
    if (candidates == 0 || verified == candidates) {
      return Outcome.FULL;
    }
    return verified == 0 ? Outcome.NONE : Outcome.PARTIAL;
  }

  /** Whether the posted review owes the reader a clause about this coverage. */
  public boolean disclosed() {
    return outcome() != Outcome.FULL;
  }

  /** How many candidates never received a verdict and posted as the reviewer raised them. */
  public int unverified() {
    return candidates - verified;
  }

  /**
   * Element-wise sum, the accumulator for a multi-batch review where each batch runs its own
   * verification call: the review-wide disclosure reports the totals, not any one batch.
   */
  public VerificationCoverage plus(VerificationCoverage other) {
    if (other == null || other.candidates == 0) {
      return this;
    }
    return new VerificationCoverage(candidates + other.candidates, verified + other.verified);
  }
}
