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
package dev.thiagogonzaga.thrillhousebot.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HelpReplyLimiterTest {

  private static final long WINDOW_MS = HelpReplyLimiter.WINDOW.toMillis();

  @Test
  void theFirstHelpOnAPullRequestIsAnsweredAndTheNextOnesAreNotUntilTheWindowEnds() {
    var now = new AtomicLong(1_000);
    var limiter = new HelpReplyLimiter(HelpReplyLimiter.SWEEP_THRESHOLD, now::get);

    assertTrue(limiter.tryAcquire(1L, 7L, 42));
    assertFalse(limiter.tryAcquire(1L, 7L, 42));
    now.addAndGet(WINDOW_MS - 1);
    assertFalse(limiter.tryAcquire(1L, 7L, 42));

    now.addAndGet(1);
    assertTrue(limiter.tryAcquire(1L, 7L, 42));
    assertFalse(limiter.tryAcquire(1L, 7L, 42));
  }

  @Test
  void eachPullRequestRepositoryAndInstallationHasItsOwnWindow() {
    var limiter = new HelpReplyLimiter(HelpReplyLimiter.SWEEP_THRESHOLD, () -> 0L);

    assertTrue(limiter.tryAcquire(1L, 7L, 42));
    assertTrue(limiter.tryAcquire(1L, 7L, 43));
    assertTrue(limiter.tryAcquire(1L, 8L, 42));
    assertTrue(limiter.tryAcquire(2L, 7L, 42));
    assertFalse(limiter.tryAcquire(1L, 7L, 42));
  }

  @Test
  void expiredEntriesAreReclaimedOnceTheThresholdIsReachedAndLiveOnesStay() {
    var now = new AtomicLong(0);
    var limiter = new HelpReplyLimiter(3, now::get);

    limiter.tryAcquire(1L, 7L, 1);
    limiter.tryAcquire(1L, 7L, 2);
    now.addAndGet(WINDOW_MS);
    limiter.tryAcquire(1L, 7L, 3);

    // The two expired entries went when the third reached the threshold; the live one stayed.
    assertEquals(1, limiter.tracked());
    assertFalse(limiter.tryAcquire(1L, 7L, 3));
  }

  @Test
  void theApplicationsLimiterAnswersTheFirstHelp() {
    var limiter = new HelpReplyLimiter();

    assertTrue(limiter.tryAcquire(1L, 7L, 42));
    assertFalse(limiter.tryAcquire(1L, 7L, 42));
  }
}
