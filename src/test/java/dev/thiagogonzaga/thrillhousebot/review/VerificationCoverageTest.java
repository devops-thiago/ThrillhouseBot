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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link VerificationCoverage}'s outcome derivation and accumulation. */
class VerificationCoverageTest {

  @Test
  void outcomeFollowsTheCounts() {
    assertEquals(VerificationCoverage.Outcome.FULL, VerificationCoverage.EMPTY.outcome());
    assertEquals(VerificationCoverage.Outcome.FULL, new VerificationCoverage(3, 3).outcome());
    assertEquals(VerificationCoverage.Outcome.PARTIAL, new VerificationCoverage(3, 1).outcome());
    assertEquals(VerificationCoverage.Outcome.NONE, new VerificationCoverage(3, 0).outcome());

    assertFalse(new VerificationCoverage(3, 3).disclosed());
    assertTrue(new VerificationCoverage(3, 1).disclosed());
    assertEquals(2, new VerificationCoverage(3, 1).unverified());
  }

  @Test
  void countsAreClampedToTheRecordsInvariant() {
    // Salvage counts are best-effort over model output; a verified count past the candidate count
    // (or a negative one) must not produce a nonsensical disclosure.
    assertEquals(new VerificationCoverage(2, 2), new VerificationCoverage(2, 5));
    assertEquals(new VerificationCoverage(2, 0), new VerificationCoverage(2, -1));
    assertEquals(VerificationCoverage.EMPTY, new VerificationCoverage(-4, -1));
  }

  @Test
  void budgetPlanAccumulatesCoverageAndSnapshotsItsSlot() {
    // The plan is the shared carrier between the review pass and the verdict, so its accumulator
    // must sum contributions, ignore nulls, and never leak its live slot through the accessor.
    var live = new java.util.concurrent.atomic.AtomicReference<>(new VerificationCoverage(1, 1));
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            true,
            null,
            null,
            null,
            null,
            live);

    plan.recordVerificationCoverage(new VerificationCoverage(2, 0));
    plan.recordVerificationCoverage(null);

    assertEquals(new VerificationCoverage(3, 1), plan.verificationCoverage());
    // The record accessor returns a defensive snapshot of the same value, not the live slot.
    var snapshot = plan.verificationCoverageRef();
    snapshot.set(VerificationCoverage.EMPTY);
    assertEquals(new VerificationCoverage(3, 1), plan.verificationCoverage());
  }

  @Test
  void truncationDetailDefaultsANullCoverageToEmpty() {
    var detail =
        new ReviewResult.TruncationDetail(
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            SummaryDegradation.NONE,
            null);

    assertEquals(VerificationCoverage.EMPTY, detail.verification());
    assertTrue(detail.isEmpty());
  }

  @Test
  void plusSumsBatchesAndIgnoresEmptyContributions() {
    var total =
        new VerificationCoverage(3, 3)
            .plus(new VerificationCoverage(2, 0))
            .plus(VerificationCoverage.EMPTY)
            .plus(null);

    assertEquals(new VerificationCoverage(5, 3), total);
    assertEquals(VerificationCoverage.Outcome.PARTIAL, total.outcome());
    // The accumulator is order-insensitive, so parallel batch lanes may record in any order.
    assertEquals(
        new VerificationCoverage(2, 0).plus(new VerificationCoverage(3, 3)),
        new VerificationCoverage(3, 3).plus(new VerificationCoverage(2, 0)));
  }
}
