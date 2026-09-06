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

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

  /**
   * #763. {@code bind} registered its call id in two steps — look the session's set up, then add to
   * it — and a sibling stream finishing in between unmapped the set the second step wrote to. The
   * add then landed on an orphan, {@link ReviewSessionContext#isActiveCall} read the mapping the
   * session no longer had, and the usage callback for a live stream was discarded as stale, so its
   * tokens never reached the ledger the per-review spend ceiling reads (#509). Under-counting there
   * lets a review that should have degraded keep spending, which is the opposite of what the
   * control exists to do.
   *
   * <p>The interleaving is driven rather than raced. The session's set is swapped for one whose
   * {@code add} is a rendezvous point, which is the only instant that matters: it is reached inside
   * the map's per-bin lock once the registration is atomic, and outside it before. So the sibling
   * {@code invalidate} is released exactly there and the binder then waits for one of the two
   * outcomes that can follow — the invalidate <em>completed</em> (it was never excluded, the
   * pre-#763 shape) or it <em>parked</em> on the bin this thread holds (it was excluded, the fixed
   * shape). Both are observable states rather than elapsed time, so neither outcome depends on
   * which thread wins a sleep, and the fixed code cannot deadlock waiting for a thread it is itself
   * blocking. Against the pre-#763 {@code computeIfAbsent(...).add(...)} this fails with the new
   * call id inactive.
   */
  @Test
  void bindRegistersAtomicallyAgainstASiblingInvalidateEmptyingTheSession() throws Exception {
    ReviewSessionContext.bind(11L, 1);
    var sibling = ReviewSessionContext.currentCallId();
    ReviewSessionContext.clear();

    var atAdd = new CountDownLatch(1);
    var invalidated = new CountDownLatch(1);
    var invalidator =
        new Thread(
            () -> {
              try {
                atAdd.await();
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
              }
              // The whole window: this empties the session's set, which unmaps it.
              ReviewSessionContext.invalidate(11L, sibling);
              invalidated.countDown();
            },
            "sibling-invalidate");
    activeCalls().put(11L, new RendezvousSet(sibling, atAdd, invalidated, invalidator));
    invalidator.start();

    ReviewSessionContext.bind(11L, 2);
    var bound = ReviewSessionContext.currentCallId();
    ReviewSessionContext.clear();
    invalidator.join();

    assertTrue(
        ReviewSessionContext.isActiveCall(11L, bound),
        "a call bound while a sibling emptied the session must stay registered");
    assertFalse(
        ReviewSessionContext.isActiveCall(11L, sibling), "the invalidated sibling must be gone");
  }

  @SuppressWarnings("unchecked")
  private static ConcurrentHashMap<Long, Set<Long>> activeCalls() throws Exception {
    var field = ReviewSessionContext.class.getDeclaredField("ACTIVE_CALLS");
    field.setAccessible(true);
    return (ConcurrentHashMap<Long, Set<Long>>) field.get(null);
  }

  /**
   * A session's in-flight set whose {@code add} hands control to a sibling {@code invalidate}
   * first, so the registration either completes under the map's bin lock or completes after the
   * session has been unmapped — the two shapes {@link
   * #bindRegistersAtomicallyAgainstASiblingInvalidateEmptyingTheSession} tells apart.
   */
  private static final class RendezvousSet extends AbstractSet<Long> {

    /**
     * How many consecutive polls must see the sibling blocked before its park is believed. One
     * reading could be a transient monitor anywhere in the release path; a thread excluded by the
     * bin lock this thread holds stays blocked until that lock is released, which never happens
     * while this method runs.
     */
    private static final int STABLE_BLOCKED_POLLS = 5;

    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final Set<Long> delegate = ConcurrentHashMap.newKeySet();
    private final CountDownLatch atAdd;
    private final CountDownLatch invalidated;
    private final Thread invalidator;

    private RendezvousSet(
        long seeded, CountDownLatch atAdd, CountDownLatch invalidated, Thread invalidator) {
      this.atAdd = atAdd;
      this.invalidated = invalidated;
      this.invalidator = invalidator;
      // Seeded directly so the rendezvous below only ever fires for the racing bind.
      delegate.add(seeded);
    }

    @Override
    public boolean add(Long callId) {
      atAdd.countDown();
      awaitSiblingSettled();
      return delegate.add(callId);
    }

    private void awaitSiblingSettled() {
      var deadline = System.nanoTime() + DEADLINE_NANOS;
      var blockedPolls = 0;
      try {
        while (!invalidated.await(1, TimeUnit.MILLISECONDS)) {
          blockedPolls = invalidator.getState() == Thread.State.BLOCKED ? blockedPolls + 1 : 0;
          if (blockedPolls >= STABLE_BLOCKED_POLLS) {
            return;
          }
          if (System.nanoTime() - deadline > 0) {
            throw new AssertionError("the sibling invalidate neither finished nor parked");
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }

    @Override
    public boolean remove(Object callId) {
      return delegate.remove(callId);
    }

    @Override
    public boolean contains(Object callId) {
      return delegate.contains(callId);
    }

    @Override
    public Iterator<Long> iterator() {
      return delegate.iterator();
    }

    @Override
    public int size() {
      return delegate.size();
    }
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
