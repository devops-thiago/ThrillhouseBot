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

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a maintainer-reply thread write out of a batched review's pending window (#1434).
 *
 * <p>GitHub holds one pending review per user. While a batched round has its pending review open, a
 * thread reply the bot posts on the same pull request can be refused or attached to that pending
 * review, where nobody sees it until the round submits. So the batched path holds this guard from
 * opening the pending review until it is submitted or discarded, and {@link MaintainerReplyService}
 * waits for the hold to end, at most {@link #DEFAULT_MAX_WAIT}, before it replies. A wait that runs
 * out replies anyway: the guard narrows the race, it never drops a reply.
 *
 * <p>An entry lives only while its hold is open, so the map stays as small as the number of rounds
 * publishing at that moment.
 */
@ApplicationScoped
public class PendingReviewGuard {

  /** Longest a reply waits for a pending review to close. */
  static final Duration DEFAULT_MAX_WAIT = Duration.ofMinutes(2);

  private final Duration maxWait;
  private final ConcurrentHashMap<String, CountDownLatch> open = new ConcurrentHashMap<>();

  public PendingReviewGuard() {
    this(DEFAULT_MAX_WAIT);
  }

  PendingReviewGuard(Duration maxWait) {
    this.maxWait = maxWait;
  }

  /** An open pending-review window; closing it lets waiting replies through. */
  @FunctionalInterface
  public interface Hold extends AutoCloseable {
    @Override
    void close();
  }

  /** Marks the pull request's pending review open until the returned hold is closed. */
  public Hold hold(String owner, String repo, int prNumber) {
    var key = key(owner, repo, prNumber);
    var latch = new CountDownLatch(1);
    open.put(key, latch);
    return () -> {
      open.remove(key, latch);
      latch.countDown();
    };
  }

  /**
   * Waits until no pending review is open on the pull request, for at most the guard's bound.
   *
   * @return {@code true} when none is open, {@code false} when the wait ran out or was interrupted
   */
  public boolean awaitNoPendingReview(String owner, String repo, int prNumber) {
    var latch = open.get(key(owner, repo, prNumber));
    if (latch == null) {
      return true;
    }
    try {
      return latch.await(maxWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static String key(String owner, String repo, int prNumber) {
    return (owner + "/" + repo).toLowerCase(Locale.ROOT) + "#" + prNumber;
  }
}
