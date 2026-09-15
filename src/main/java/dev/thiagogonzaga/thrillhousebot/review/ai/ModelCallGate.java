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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The process-wide ceiling on model calls in flight at once ({@code AI_MAX_CONCURRENT_CALLS},
 * #838).
 *
 * <p>Nothing bounded how many calls the bot had open against the provider. The dispatcher reviews
 * different pull requests in parallel on virtual threads and a large review fans its batches out,
 * so N reviews of M batches open up to N×M streams, each followed by a verifier call. A provider
 * that queues requests behind a per-account slot count and times the queue out, as Ollama cloud
 * does, refuses the surplus with {@code timed out waiting for a concurrent request slot}, and every
 * refusal spent one of the call's retry attempts. With a ceiling set, the surplus waits here for a
 * slot instead, where the wait is logged and nothing is billed.
 *
 * <p>Every model call takes a slot: the streamed review batches and final summary in {@link
 * AiReviewService}, and every blocking call through {@link BoundedChatModel}. A slot is held for
 * the whole call and returned once when the call ends, however it ends.
 *
 * <p>The semaphore is fair, so slots go out in the order calls asked for them and a burst of
 * batches from one review cannot starve a call that has waited longer. The wait is bounded by
 * {@code thrillhousebot.review.ai-timeout-seconds}, apart from the call's own timeout: a call that
 * finds no slot in that time fails as a transient error without reaching the provider, and a call
 * that gets one still has its whole timeout to run. Charging the wait to the call would cancel a
 * late-starting call partway through a response that is already being billed, and would break the
 * documented rule that the client-side wait is at least the HTTP timeout ({@code AI_TIMEOUT}),
 * whose clock starts only when the request goes out.
 *
 * <p>{@code 0}, the default, builds no semaphore at all, so an unbounded deployment takes no lock
 * and behaves as it did before.
 */
@ApplicationScoped
public class ModelCallGate {

  private static final Logger log = LoggerFactory.getLogger(ModelCallGate.class);

  /** Waits up to this long are not logged: a slot freed by a short call is not worth a line. */
  static final Duration SLOW_WAIT = Duration.ofSeconds(5);

  private final int limit;
  private final Duration maxWait;

  /** Null when the ceiling is off. */
  private final Semaphore slots;

  private final LongSupplier nanoClock;

  @Inject
  public ModelCallGate(ThrillhouseConfig config) {
    this(
        config.ai().maxConcurrentCalls(),
        Duration.ofSeconds(config.review().aiTimeoutSeconds()),
        System::nanoTime);
  }

  ModelCallGate(int limit, Duration maxWait, LongSupplier nanoClock) {
    this.limit = limit;
    this.maxWait = maxWait;
    this.slots = limit > 0 ? new Semaphore(limit, true) : null;
    this.nanoClock = nanoClock;
  }

  /**
   * Takes a slot for {@code call}, waiting for one while the ceiling is reached. The caller closes
   * the returned slot when the call ends.
   *
   * @param call what is being sent, for the log and the failure message; never prompt text
   * @throws AiReviewException when no slot frees within the bound, or when the wait is interrupted,
   *     in which case the interrupt flag is restored first
   */
  Slot acquire(String call) {
    if (slots == null) {
      return Slot.UNBOUNDED;
    }
    long start = nanoClock.getAsLong();
    if (!tryAcquire(call)) {
      throw new AiReviewException(
          call
              + " found no free model call slot within "
              + maxWait
              + " (AI_MAX_CONCURRENT_CALLS="
              + limit
              + ")",
          1,
          null);
    }
    var waited = Duration.ofNanos(nanoClock.getAsLong() - start);
    if (waited.compareTo(SLOW_WAIT) > 0) {
      log.info(
          "{} waited {} ms for a model call slot (AI_MAX_CONCURRENT_CALLS={})",
          call,
          waited.toMillis(),
          limit);
    }
    return new Slot(slots, waited);
  }

  private boolean tryAcquire(String call) {
    try {
      // The timed tryAcquire honours the semaphore's fairness; the untimed one would barge.
      return slots.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiReviewException(
          call + " was interrupted while waiting for a model call slot", 1, e);
    }
  }

  /** Free slots right now; {@link Integer#MAX_VALUE} with no ceiling. Visible for tests. */
  int availableSlots() {
    return slots == null ? Integer.MAX_VALUE : slots.availablePermits();
  }

  /** Calls waiting for a slot right now. Visible for tests. */
  int queuedCalls() {
    return slots == null ? 0 : slots.getQueueLength();
  }

  /**
   * One call's hold on the ceiling. Closing returns the slot once; a second close does nothing, so
   * a path that closes it early and a {@code try}-with-resources around it cannot mint a slot.
   */
  static final class Slot implements AutoCloseable {

    /** The slot every call gets when the ceiling is off: nothing to wait for, nothing to return. */
    static final Slot UNBOUNDED = new Slot(null, Duration.ZERO);

    private final Semaphore slots;
    private final Duration waited;
    private final AtomicBoolean returned = new AtomicBoolean();

    Slot(Semaphore slots, Duration waited) {
      this.slots = slots;
      this.waited = waited;
    }

    /** How long the call waited for this slot. */
    Duration waited() {
      return waited;
    }

    @Override
    public void close() {
      if (slots != null && returned.compareAndSet(false, true)) {
        slots.release();
      }
    }
  }
}
