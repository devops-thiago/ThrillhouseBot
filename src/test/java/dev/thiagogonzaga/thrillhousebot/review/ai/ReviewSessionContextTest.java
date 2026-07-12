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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit tests for concurrent call-id tracking in {@link ReviewSessionContext}. */
class ReviewSessionContextTest {

  @AfterEach
  void tearDown() {
    ReviewSessionContext.reset();
  }

  @Test
  void concurrentBindsKeepDistinctActiveCallIds() {
    ReviewSessionContext.bind(7L, 1);
    var first = ReviewSessionContext.currentCallId();
    ReviewSessionContext.clear();

    ReviewSessionContext.bind(7L, 1);
    var second = ReviewSessionContext.currentCallId();
    ReviewSessionContext.clear();

    assertNotNull(first);
    assertNotNull(second);
    assertNotEquals(first, second);
    assertTrue(ReviewSessionContext.isActiveCall(7L, first));
    assertTrue(ReviewSessionContext.isActiveCall(7L, second));

    ReviewSessionContext.invalidate(7L, first);
    assertFalse(ReviewSessionContext.isActiveCall(7L, first));
    assertTrue(ReviewSessionContext.isActiveCall(7L, second));
  }

  @Test
  void invalidateDoesNotDropConcurrentlyBoundCall() throws Exception {
    var workerBound = new CountDownLatch(1);
    var newCallId = new AtomicLong();

    ReviewSessionContext.bind(10L, 1);
    var stale = ReviewSessionContext.currentCallId();
    ReviewSessionContext.clear();

    var binder =
        new Thread(
            () -> {
              ReviewSessionContext.bind(10L, 2);
              newCallId.set(ReviewSessionContext.currentCallId());
              ReviewSessionContext.clear();
              workerBound.countDown();
            });
    binder.start();
    workerBound.await();

    ReviewSessionContext.invalidate(10L, stale);
    binder.join();

    assertTrue(ReviewSessionContext.isActiveCall(10L, newCallId.get()));
    ReviewSessionContext.invalidate(10L, newCallId.get());
    assertFalse(ReviewSessionContext.isActiveCall(10L, newCallId.get()));
  }

  @Test
  void invalidateUnknownCallIdIsNoOp() {
    ReviewSessionContext.invalidate(99L, 12345L);
    assertFalse(ReviewSessionContext.isActiveCall(99L, 12345L));
  }

  @Test
  void invalidateLastCallRemovesSessionEntry() {
    ReviewSessionContext.bind(3L, 1);
    var callId = ReviewSessionContext.currentCallId();
    ReviewSessionContext.clear();

    ReviewSessionContext.invalidate(3L, callId);
    assertFalse(ReviewSessionContext.isActiveCall(3L, callId));

    // Second invalidate of the same (now missing) session must also be a no-op.
    ReviewSessionContext.invalidate(3L, callId);
    assertFalse(ReviewSessionContext.isActiveCall(3L, callId));
  }

  @Test
  void invalidateSessionDropsEveryActiveCall() {
    ReviewSessionContext.bind(5L, 1);
    var first = ReviewSessionContext.currentCallId();
    ReviewSessionContext.clear();
    ReviewSessionContext.bind(5L, 2);
    var second = ReviewSessionContext.currentCallId();
    ReviewSessionContext.clear();

    ReviewSessionContext.invalidate(5L);
    assertFalse(ReviewSessionContext.isActiveCall(5L, first));
    assertFalse(ReviewSessionContext.isActiveCall(5L, second));
  }

  @Test
  void accessorsReturnNullWithoutBinding() {
    assertNull(ReviewSessionContext.currentSessionId());
    assertNull(ReviewSessionContext.currentAttempt());
    assertNull(ReviewSessionContext.currentCallId());
  }
}
