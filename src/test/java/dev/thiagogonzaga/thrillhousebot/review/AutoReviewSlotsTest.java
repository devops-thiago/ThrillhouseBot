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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.AutoReviewSlots.Outcome;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AutoReviewSlotsTest {

  private static final long INSTALLATION = 1L;
  private static final long REPO = 7L;

  /** Starts {@code acquire} on its own thread and returns the thread with its eventual outcome. */
  private record Waiter(Thread thread, CompletableFuture<Outcome> outcome) {
    static Waiter start(AutoReviewSlots slots, long repositoryId) {
      var outcome = new CompletableFuture<Outcome>();
      var thread =
          Thread.ofVirtual()
              .start(
                  () -> outcome.complete(slots.acquire(INSTALLATION, repositoryId, () -> false)));
      return new Waiter(thread, outcome);
    }

    Outcome get() throws InterruptedException, ExecutionException, TimeoutException {
      return outcome.get(5, TimeUnit.SECONDS);
    }
  }

  private static void awaitWaiting(AutoReviewSlots slots, int expected) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (slots.waiting(INSTALLATION, REPO) != expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, slots.waiting(INSTALLATION, REPO));
  }

  @Test
  void theInjectedLimitsComeFromTheConfiguration() {
    var config = mock(ThrillhouseConfig.class);
    var review = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(review);
    when(review.autoReviewMaxConcurrentPerRepo()).thenReturn(1);
    when(review.autoReviewMaxQueuedPerRepo()).thenReturn(1);
    var slots = new AutoReviewSlots(config);

    assertEquals(Outcome.ACQUIRED, slots.acquire(INSTALLATION, REPO, () -> false));
    var waiter = Waiter.start(slots, REPO);
    awaitWaiting(slots, 1);
    assertEquals(Outcome.QUEUE_FULL, slots.acquire(INSTALLATION, REPO, () -> false));

    waiter.thread().interrupt();
  }

  @Test
  void aReviewPastTheRunningLimitWaitsUntilOneFinishes() throws Exception {
    var slots = new AutoReviewSlots(2, 10);
    assertEquals(Outcome.ACQUIRED, slots.acquire(INSTALLATION, REPO, () -> false));
    assertEquals(Outcome.ACQUIRED, slots.acquire(INSTALLATION, REPO, () -> false));

    var third = Waiter.start(slots, REPO);
    awaitWaiting(slots, 1);
    assertEquals(2, slots.running(INSTALLATION, REPO));

    slots.release(INSTALLATION, REPO);

    assertEquals(Outcome.ACQUIRED, third.get());
    assertEquals(2, slots.running(INSTALLATION, REPO));
    assertEquals(0, slots.waiting(INSTALLATION, REPO));
  }

  @Test
  void aReviewPastTheQueueLimitIsRefusedAtOnce() throws Exception {
    var slots = new AutoReviewSlots(1, 2);
    slots.acquire(INSTALLATION, REPO, () -> false);
    var first = Waiter.start(slots, REPO);
    awaitWaiting(slots, 1);
    var second = Waiter.start(slots, REPO);
    awaitWaiting(slots, 2);

    assertEquals(Outcome.QUEUE_FULL, slots.acquire(INSTALLATION, REPO, () -> false));
    assertEquals(2, slots.waiting(INSTALLATION, REPO));

    // The refused review took nothing: both waiters still get their turn.
    slots.release(INSTALLATION, REPO);
    slots.release(INSTALLATION, REPO);
    assertEquals(Outcome.ACQUIRED, first.get());
    assertEquals(Outcome.ACQUIRED, second.get());
  }

  @Test
  void withTheQueueLimitOffAnyNumberWait() {
    var slots = new AutoReviewSlots(1, 0);
    slots.acquire(INSTALLATION, REPO, () -> false);

    var waiters = new java.util.ArrayList<Waiter>();
    for (int i = 1; i <= 5; i++) {
      waiters.add(Waiter.start(slots, REPO));
      awaitWaiting(slots, i);
    }

    waiters.forEach(waiter -> waiter.thread().interrupt());
  }

  @Test
  void anotherRepositoryAndAnotherInstallationHaveTheirOwnSlots() {
    var slots = new AutoReviewSlots(1, 1);

    assertEquals(Outcome.ACQUIRED, slots.acquire(INSTALLATION, REPO, () -> false));
    assertEquals(Outcome.ACQUIRED, slots.acquire(INSTALLATION, REPO + 1, () -> false));
    assertEquals(Outcome.ACQUIRED, slots.acquire(INSTALLATION + 1, REPO, () -> false));
    assertEquals(3, slots.trackedRepositories());
  }

  @Test
  void aRepositoryIsForgottenOnceNothingOfItRunsOrWaits() throws Exception {
    var slots = new AutoReviewSlots(1, 5);
    slots.acquire(INSTALLATION, REPO, () -> false);
    var waiter = Waiter.start(slots, REPO);
    awaitWaiting(slots, 1);

    slots.release(INSTALLATION, REPO);
    assertEquals(Outcome.ACQUIRED, waiter.get());
    assertEquals(1, slots.trackedRepositories());

    slots.release(INSTALLATION, REPO);
    assertEquals(0, slots.trackedRepositories());
    assertEquals(0, slots.running(INSTALLATION, REPO));
    assertEquals(0, slots.waiting(INSTALLATION, REPO));
  }

  @Test
  void aReleaseWithNothingHeldChangesNothing() {
    var slots = new AutoReviewSlots(1, 5);

    slots.release(INSTALLATION, REPO);

    assertEquals(0, slots.trackedRepositories());
    assertEquals(Outcome.ACQUIRED, slots.acquire(INSTALLATION, REPO, () -> false));
  }

  @Test
  void anInterruptedWaiterGivesUpAndTheNextOneStillGetsTheSlot() throws Exception {
    var slots = new AutoReviewSlots(1, 5);
    slots.acquire(INSTALLATION, REPO, () -> false);
    var interruptFlagKept = new AtomicBoolean();
    var gaveUp = new CompletableFuture<Outcome>();
    var first =
        Thread.ofVirtual()
            .start(
                () -> {
                  var outcome = slots.acquire(INSTALLATION, REPO, () -> false);
                  interruptFlagKept.set(Thread.currentThread().isInterrupted());
                  gaveUp.complete(outcome);
                });
    awaitWaiting(slots, 1);
    var second = Waiter.start(slots, REPO);
    awaitWaiting(slots, 2);

    first.interrupt();

    assertEquals(Outcome.INTERRUPTED, gaveUp.get(5, TimeUnit.SECONDS));
    assertTrue(interruptFlagKept.get(), "the interrupt flag is set again for the caller");
    awaitWaiting(slots, 1);
    assertEquals(1, slots.running(INSTALLATION, REPO));

    slots.release(INSTALLATION, REPO);
    assertEquals(Outcome.ACQUIRED, second.get());
  }

  @Test
  void anInterruptedLastWaiterOfAnIdleRepositoryLeavesNoEntry() throws Exception {
    var slots = new AutoReviewSlots(1, 5);
    slots.acquire(INSTALLATION, REPO, () -> false);
    var waiter = Waiter.start(slots, REPO);
    awaitWaiting(slots, 1);
    slots.acquire(INSTALLATION, REPO + 1, () -> false);

    waiter.thread().interrupt();
    assertEquals(Outcome.INTERRUPTED, waiter.get());
    assertEquals(2, slots.trackedRepositories());

    slots.release(INSTALLATION, REPO);
    assertEquals(1, slots.trackedRepositories());
  }

  @Test
  void aWaiterWhoseReviewWasReplacedGivesItsPlaceUpWhenAskedAgain() throws Exception {
    var slots = new AutoReviewSlots(1, 5);
    slots.acquire(INSTALLATION, REPO, () -> false);
    var replaced = new AtomicBoolean();
    var outcome = new CompletableFuture<Outcome>();
    Thread.ofVirtual()
        .start(() -> outcome.complete(slots.acquire(INSTALLATION, REPO, replaced::get)));
    awaitWaiting(slots, 1);
    var other = Waiter.start(slots, REPO);
    awaitWaiting(slots, 2);

    // Asking again changes nothing while no review was replaced.
    slots.recheckWaiters(INSTALLATION, REPO);
    slots.recheckWaiters(INSTALLATION, REPO + 1);
    assertEquals(2, slots.waiting(INSTALLATION, REPO));

    replaced.set(true);
    slots.recheckWaiters(INSTALLATION, REPO);

    assertEquals(Outcome.SUPERSEDED, outcome.get(5, TimeUnit.SECONDS));
    awaitWaiting(slots, 1);
    assertEquals(1, slots.running(INSTALLATION, REPO));
    // The other waiter kept its place and gets the slot.
    slots.release(INSTALLATION, REPO);
    assertEquals(Outcome.ACQUIRED, other.get());
  }

  @Test
  void aWaiterInterruptedAsTheSlotFreesDoesNotTakeIt() throws Exception {
    var slots = new AutoReviewSlots(1, 5);
    slots.acquire(INSTALLATION, REPO, () -> false);
    var first = Waiter.start(slots, REPO);
    awaitWaiting(slots, 1);

    // A shutdown frees the slot and interrupts the waiter together. The second waiter's test runs
    // inside the queue, so both happen before the first waiter can look at the slot again.
    var done = new AtomicBoolean();
    var second = new CompletableFuture<Outcome>();
    Thread.ofVirtual()
        .start(
            () ->
                second.complete(
                    slots.acquire(
                        INSTALLATION,
                        REPO,
                        () -> {
                          if (!done.getAndSet(true)) {
                            slots.release(INSTALLATION, REPO);
                            first.thread().interrupt();
                          }
                          return false;
                        })));

    assertEquals(Outcome.INTERRUPTED, first.get());
    // The slot it did not take goes to the next waiter.
    assertEquals(Outcome.ACQUIRED, second.get(5, TimeUnit.SECONDS));
    assertEquals(1, slots.running(INSTALLATION, REPO));
    assertEquals(0, slots.waiting(INSTALLATION, REPO));
  }

  @Test
  void aReviewAlreadyReplacedWhenItReachesTheQueueDoesNotWait() {
    var slots = new AutoReviewSlots(1, 5);
    slots.acquire(INSTALLATION, REPO, () -> false);

    assertEquals(Outcome.SUPERSEDED, slots.acquire(INSTALLATION, REPO, () -> true));
    assertEquals(0, slots.waiting(INSTALLATION, REPO));
    assertEquals(1, slots.running(INSTALLATION, REPO));
  }

  @Test
  void withTheRunningLimitOffNothingWaitsAndNothingIsTracked() {
    var slots = new AutoReviewSlots(0, 1);

    for (int i = 0; i < 20; i++) {
      assertEquals(Outcome.ACQUIRED, slots.acquire(INSTALLATION, REPO, () -> false));
    }
    slots.release(INSTALLATION, REPO);
    slots.recheckWaiters(INSTALLATION, REPO);

    assertEquals(0, slots.trackedRepositories());
    assertEquals(0, slots.running(INSTALLATION, REPO));
  }
}
