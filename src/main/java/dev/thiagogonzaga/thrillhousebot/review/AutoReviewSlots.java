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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Bounds the automatic reviews of one repository: at most {@code
 * thrillhousebot.review.auto-review-max-concurrent-per-repo} run at once, and at most {@code
 * auto-review-max-queued-per-repo} wait for a slot. Anyone who can open pull requests on a
 * repository starts automatic reviews, so without a bound the number of pull requests alone decides
 * how many reviews, and how many model calls, run at the same time.
 *
 * <p>A review past the running limit waits on its own worker thread, so a burst of pull requests is
 * reviewed a few at a time instead of being dropped. A freed slot goes to the longest waiter unless
 * a review arriving at that moment takes it first; the limit holds either way. {@link
 * ReviewDispatcher} keeps one worker per pull request, so the queue holds one entry per pull
 * request however often it is pushed. Only a review past the queue limit is refused. Manual reviews
 * never come here, and a waiting review gives its place up when a newer request for its pull
 * request arrives ({@link #recheckWaiters}), so a manual review is not held behind the automatic
 * one it replaces. State is in memory per replica, and a repository's entry is removed when nothing
 * of it runs or waits.
 */
@ApplicationScoped
public class AutoReviewSlots {

  /** What {@link #acquire} decided. Only {@link #ACQUIRED} is paired with a {@link #release}. */
  public enum Outcome {
    /** The caller holds a slot and must release it. */
    ACQUIRED,
    /** As many reviews of the repository wait as may; this one is not run. */
    QUEUE_FULL,
    /** The wait was interrupted (shutdown); the interrupt flag is set again. */
    INTERRUPTED,
    /** The caller's own test said, while it waited, that the review is no longer wanted. */
    SUPERSEDED
  }

  /** A repository as the signed webhook payload names it: installation and repository id. */
  private record Key(long installationId, long repositoryId) {}

  private static final class Repository {
    int running;
    int waiting;
    final Condition freed;

    Repository(Condition freed) {
      this.freed = freed;
    }
  }

  private final int maxConcurrent;
  private final int maxQueued;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Key, Repository> repositories = new HashMap<>();

  @Inject
  public AutoReviewSlots(ThrillhouseConfig config) {
    this(
        config.review().autoReviewMaxConcurrentPerRepo(),
        config.review().autoReviewMaxQueuedPerRepo());
  }

  /** Visible for tests: {@code 0} turns the matching limit off. */
  AutoReviewSlots(int maxConcurrent, int maxQueued) {
    this.maxConcurrent = maxConcurrent;
    this.maxQueued = maxQueued;
  }

  /**
   * Takes a slot for an automatic review of {@code repository}, waiting while as many run as may.
   * With the running limit off it returns {@link Outcome#ACQUIRED} at once and keeps no state.
   *
   * @param superseded asked before each wait; {@code true} ends the wait with {@link
   *     Outcome#SUPERSEDED}. {@link #recheckWaiters} makes the waiters of a repository ask again.
   */
  public Outcome acquire(long installationId, long repositoryId, BooleanSupplier superseded) {
    if (maxConcurrent <= 0) {
      return Outcome.ACQUIRED;
    }
    var repository = new Key(installationId, repositoryId);
    lock.lock();
    try {
      var state =
          repositories.computeIfAbsent(repository, _ -> new Repository(lock.newCondition()));
      if (state.running < maxConcurrent) {
        state.running++;
        return Outcome.ACQUIRED;
      }
      if (maxQueued > 0 && state.waiting >= maxQueued) {
        return Outcome.QUEUE_FULL;
      }
      state.waiting++;
      try {
        while (state.running >= maxConcurrent) {
          if (superseded.getAsBoolean()) {
            return leaveQueue(repository, state, Outcome.SUPERSEDED);
          }
          state.freed.await();
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        return leaveQueue(repository, state, Outcome.INTERRUPTED);
      }
      if (Thread.currentThread().isInterrupted()) {
        // A shutdown can free the slot and interrupt the waiter together; the review is not run.
        return leaveQueue(repository, state, Outcome.INTERRUPTED);
      }
      state.waiting--;
      state.running++;
      return Outcome.ACQUIRED;
    } finally {
      lock.unlock();
    }
  }

  /** Takes a waiter out of the queue without a slot. Called with the lock held. */
  private Outcome leaveQueue(Key repository, Repository state, Outcome outcome) {
    state.waiting--;
    // The signal this waiter may have consumed goes to the next one.
    state.freed.signal();
    forgetIfIdle(repository, state);
    return outcome;
  }

  /**
   * Makes every review waiting for a slot of the repository ask its {@code superseded} test again.
   * Called when a newer request arrives for a pull request, whose review may be among them.
   */
  public void recheckWaiters(long installationId, long repositoryId) {
    if (maxConcurrent <= 0) {
      return;
    }
    lock.lock();
    try {
      var state = repositories.get(new Key(installationId, repositoryId));
      if (state != null) {
        state.freed.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  /** Returns the slot an {@link Outcome#ACQUIRED} call took and wakes the longest waiter. */
  public void release(long installationId, long repositoryId) {
    if (maxConcurrent <= 0) {
      return;
    }
    var repository = new Key(installationId, repositoryId);
    lock.lock();
    try {
      var state = repositories.get(repository);
      if (state == null) {
        return;
      }
      state.running--;
      state.freed.signal();
      forgetIfIdle(repository, state);
    } finally {
      lock.unlock();
    }
  }

  private void forgetIfIdle(Key repository, Repository state) {
    if (state.running <= 0 && state.waiting == 0) {
      repositories.remove(repository);
    }
  }

  /** Visible for tests: automatic reviews of the repository holding a slot. */
  int running(long installationId, long repositoryId) {
    lock.lock();
    try {
      var state = repositories.get(new Key(installationId, repositoryId));
      return state == null ? 0 : state.running;
    } finally {
      lock.unlock();
    }
  }

  /** Visible for tests: automatic reviews of the repository waiting for a slot. */
  int waiting(long installationId, long repositoryId) {
    lock.lock();
    try {
      var state = repositories.get(new Key(installationId, repositoryId));
      return state == null ? 0 : state.waiting;
    } finally {
      lock.unlock();
    }
  }

  /** Visible for tests: repositories with a review running or waiting. */
  int trackedRepositories() {
    lock.lock();
    try {
      return repositories.size();
    } finally {
      lock.unlock();
    }
  }
}
