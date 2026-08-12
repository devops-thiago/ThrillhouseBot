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
package dev.thiagogonzaga.thrillhousebot.github;

import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded backoff for the GitHub calls that publish work the bot has already paid for.
 *
 * <p>#568: GitHub secondary-rate-limits rapid content creation and answers a comment POST with 403
 * and a {@code Retry-After}. Nothing honoured it, so a burst of on-demand commands lost 7 of 56
 * responses — every one of them <em>after</em> its model call had completed. A throttled post is
 * the cheapest failure in the run to repeat and the most expensive one to drop, so it is repeated.
 *
 * <h2>Why this cannot post the same comment twice</h2>
 *
 * A retry is attempted only when {@link GitHubApiError#isThrottled()} holds — a 429, or a 403 that
 * carries a {@code Retry-After}, an exhausted {@code x-ratelimit-remaining}, or GitHub's rate-limit
 * wording in the body. GitHub rejects a throttled content-creating request at the edge, before the
 * comment exists; the response is the rejection itself, not a report about a write that happened.
 * The ambiguous failures — a connection reset, a read timeout, a 5xx — are the ones where a write
 * may well have landed, and those are deliberately <em>not</em> retried here: they propagate on the
 * first attempt exactly as they did before. That is the whole duplicate-suppression argument, and
 * it is why the throttle test is a positive signal rather than "not a success".
 *
 * <h2>Why this cannot stall the pipeline</h2>
 *
 * Reviews and commands run on a virtual-thread-per-task executor, so a waiting attempt parks its
 * virtual thread and pins no platform thread. What a wait does hold is the per-PR serialization
 * slot in the dispatcher, so the wait is bounded twice over: at most {@value #MAX_ATTEMPTS}
 * attempts, and no single wait longer than {@link #MAX_DELAY_PER_ATTEMPT} however long a {@code
 * Retry-After} asks for. One call therefore waits at most {@value #MAX_ATTEMPTS} − 1 × 30s = 60s,
 * which is also long enough to outlast the minute-long window GitHub's content-creation secondary
 * limit uses. Once the attempts are spent the failure propagates unchanged, and the log says the
 * generated content was lost so an operator can see the command needs re-running.
 */
public final class GitHubWriteRetry {

  private static final Logger log = LoggerFactory.getLogger(GitHubWriteRetry.class);

  /** Total attempts, first included: one post plus at most two repeats. */
  public static final int MAX_ATTEMPTS = 3;

  /** Longest single wait honoured, however long a {@code Retry-After} asks for. */
  public static final Duration MAX_DELAY_PER_ATTEMPT = Duration.ofSeconds(30);

  /** Parks the current thread; the seam that lets the tests run the backoff without waiting. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration delay) throws InterruptedException;
  }

  /** The instance production uses: real sleeping, real clock. */
  static final GitHubWriteRetry DEFAULT =
      new GitHubWriteRetry(delay -> Thread.sleep(delay.toMillis()), Instant::now);

  private final Sleeper sleeper;
  private final Supplier<Instant> clock;

  GitHubWriteRetry(Sleeper sleeper, Supplier<Instant> clock) {
    this.sleeper = sleeper;
    this.clock = clock;
  }

  /**
   * Runs a content-creating GitHub call, repeating it while GitHub is throttling and the budget
   * allows. Returns the call's own result; rethrows the failure unchanged once the call is not
   * retryable or the budget is spent, so every existing caller keeps the exception type and the
   * fail-soft handling it already has.
   *
   * @param operation what is being posted, for the log — never credentials or comment text
   */
  public <T> T call(String operation, Supplier<T> operationCall) {
    for (int attempt = 1; ; attempt++) {
      try {
        return operationCall.get();
      } catch (WebApplicationException e) {
        var delay = retryDelay(operation, e, attempt);
        if (delay.isEmpty()) {
          throw e;
        }
        log.warn(
            "GitHub throttled {} — retrying in {}s (attempt {} of {})",
            operation,
            delay.get().toSeconds(),
            attempt + 1,
            MAX_ATTEMPTS);
        try {
          sleeper.sleep(delay.get());
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
          log.warn("Interrupted while backing off {} — the payload is lost", operation);
          throw e;
        }
      }
    }
  }

  /**
   * How long to wait before repeating this failure, or empty when it must not be repeated — either
   * because GitHub is refusing rather than throttling, or because the attempts are spent. The
   * spent-attempts case is logged, because it is the one where a completed generation is discarded
   * and the operator needs the response's own words to see why.
   */
  private Optional<Duration> retryDelay(
      String operation, WebApplicationException failure, int attempt) {
    var error = GitHubApiError.of(failure);
    if (error.isEmpty() || !error.get().isThrottled()) {
      return Optional.empty();
    }
    if (attempt >= MAX_ATTEMPTS) {
      log.warn(
          "GitHub still throttling {} after {} attempts — the generated content is lost and the"
              + " command has to be re-run. {}",
          operation,
          MAX_ATTEMPTS,
          error.get().diagnostics());
      return Optional.empty();
    }
    return Optional.of(min(error.get().retryDelay(attempt, clock.get()), MAX_DELAY_PER_ATTEMPT));
  }

  private static Duration min(Duration left, Duration right) {
    return left.compareTo(right) <= 0 ? left : right;
  }
}
