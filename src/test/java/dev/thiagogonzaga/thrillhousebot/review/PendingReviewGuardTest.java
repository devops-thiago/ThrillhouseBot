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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** #1434: thread replies wait out a batched review's pending window, within a bound. */
class PendingReviewGuardTest {

  @Test
  void nothingOpenLetsTheReplyThroughAtOnce() {
    assertTrue(new PendingReviewGuard().awaitNoPendingReview("o", "r", 1));
  }

  @Test
  void anOpenHoldBlocksOnlyItsOwnPullRequestUntilTheWaitRunsOut() {
    var guard = new PendingReviewGuard(Duration.ofMillis(20));
    try (var _ = guard.hold("Owner", "Repo", 7)) {
      assertFalse(guard.awaitNoPendingReview("owner", "repo", 7));
      assertTrue(guard.awaitNoPendingReview("owner", "repo", 8));
    }
    assertTrue(guard.awaitNoPendingReview("owner", "repo", 7));
  }

  @Test
  void closingTheHoldReleasesAWaitingReply() throws Exception {
    var guard = new PendingReviewGuard(Duration.ofSeconds(5));
    var hold = guard.hold("o", "r", 7);
    var cleared = new AtomicBoolean();
    var waiter =
        Thread.ofVirtual().start(() -> cleared.set(guard.awaitNoPendingReview("o", "r", 7)));
    waiter.join(Duration.ofMillis(100));
    assertTrue(waiter.isAlive());

    hold.close();
    waiter.join(Duration.ofSeconds(5));

    assertTrue(cleared.get());
  }

  @Test
  void anInterruptedWaitReportsTheReviewStillOpenAndKeepsTheInterrupt() {
    var guard = new PendingReviewGuard(Duration.ofSeconds(5));
    try (var _ = guard.hold("o", "r", 7)) {
      Thread.currentThread().interrupt();
      assertFalse(guard.awaitNoPendingReview("o", "r", 7));
      assertTrue(Thread.interrupted());
    }
  }

  @Test
  void aStaleHoldClosingLaterDoesNotReleaseANewerOne() {
    var guard = new PendingReviewGuard(Duration.ofMillis(20));
    var stale = guard.hold("o", "r", 7);
    try (var _ = guard.hold("o", "r", 7)) {
      stale.close();
      assertFalse(guard.awaitNoPendingReview("o", "r", 7));
    }
  }
}
