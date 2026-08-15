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
import java.util.function.Function;
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
 * wording in the body — or when {@link GitHubApiError#isExpiredCredential()} holds and a fresher
 * installation token can be minted. GitHub rejects a throttled content-creating request at the
 * edge, before the comment exists, and rejects an unauthenticated one earlier still; the response
 * is the rejection itself, not a report about a write that happened. The ambiguous failures — a
 * connection reset, a read timeout, a 5xx — are the ones where a write may well have landed, and
 * those are deliberately <em>not</em> retried here: they propagate on the first attempt exactly as
 * they did before. That is the whole duplicate-suppression argument, and it is why the throttle
 * test is a positive signal rather than "not a success".
 *
 * <h2>Why a repeat alone was not enough</h2>
 *
 * #624: the backoff below re-read the same {@code Authorization} header on every attempt, so a
 * review whose installation token expired mid-run drew {@code 401 Bad credentials} three times over
 * and threw the generation away anyway. A dead credential is the one failure a repeat cannot fix on
 * its own, so an attempt that draws a 401 swaps the credential through {@link GitHubTokenRefresh}
 * before repeating. That happens at most once per call and does not spend a backoff attempt — the
 * refreshed request has not been throttled, it has not been sent.
 *
 * <h2>Why this cannot stall the pipeline</h2>
 *
 * Reviews and commands run on a virtual-thread-per-task executor, so a waiting attempt parks its
 * virtual thread and pins no platform thread. What a wait does hold is the per-PR serialization
 * slot in the dispatcher, so the wait is bounded twice over: at most {@value #MAX_ATTEMPTS}
 * attempts, and no single wait longer than {@link #MAX_DELAY_PER_ATTEMPT} however long a {@code
 * Retry-After} asks for. One call therefore waits at most {@link #TOTAL_BUDGET}. Once the attempts
 * are spent the failure propagates unchanged, and the log says the generated content was lost so an
 * operator can see the command needs re-running.
 *
 * <h2>Why the budget is what it is</h2>
 *
 * #722: the budget was three attempts, and its own documentation claimed the resulting 60s was
 * "long enough to outlast the minute-long window GitHub's content-creation secondary limit uses".
 * That was an assumption, and measurement contradicted it. During one dogfood round GitHub answered
 * content creation with {@code 403 You have exceeded a secondary rate limit and have been
 * temporarily blocked from content creation} — with {@code x-ratelimit-remaining} still at 4771, so
 * primary quota was nowhere near exhausted — across a window of <strong>72 seconds</strong>,
 * simultaneously on unrelated pull requests. Sixty seconds of budget expired inside it and the
 * writes were given up on.
 *
 * <p>The budget is therefore sized to outlast a window of that width with margin, which is what
 * {@value #MAX_ATTEMPTS} attempts buys. It is deliberately not sized to outlast an arbitrary block:
 * a secondary limit that outlasts this is telling the deployment it is writing too fast, and the
 * answer there is the pacing in {@link GitHubWritePacer}, not a longer wait holding a PR's slot.
 */
public final class GitHubWriteRetry {

  private static final Logger log = LoggerFactory.getLogger(GitHubWriteRetry.class);

  /** Total attempts, first included: one post plus at most three repeats. */
  public static final int MAX_ATTEMPTS = 4;

  /** Longest single wait honoured, however long a {@code Retry-After} asks for. */
  public static final Duration MAX_DELAY_PER_ATTEMPT = Duration.ofSeconds(30);

  /**
   * Longest one call can wait in total: every repeat waiting the per-attempt ceiling. Derived
   * rather than written down so it cannot drift from the two bounds that produce it, and exposed
   * because {@link GitHubWritePacer} sizes its own ceiling against it — a caller waiting for a
   * pacing slot must never wait longer than simply being refused and repeated would take.
   */
  public static final Duration TOTAL_BUDGET = MAX_DELAY_PER_ATTEMPT.multipliedBy(MAX_ATTEMPTS - 1L);

  /** Parks the current thread; the seam that lets the tests run the backoff without waiting. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration delay) throws InterruptedException;
  }

  /** The instance production uses: real sleeping, real clock, the shared pacer and token seam. */
  static final GitHubWriteRetry DEFAULT =
      new GitHubWriteRetry(
          delay -> Thread.sleep(delay.toMillis()),
          Instant::now,
          GitHubWritePacer.DEFAULT,
          GitHubTokenRefresh.SHARED);

  private final Sleeper sleeper;
  private final Supplier<Instant> clock;
  private final GitHubWritePacer pacer;
  private final GitHubTokenRefresh credentials;

  /** A retry that only backs off, for the tests that pin the backoff rather than the pacing. */
  GitHubWriteRetry(Sleeper sleeper, Supplier<Instant> clock) {
    this(sleeper, clock, GitHubWritePacer.NONE, new GitHubTokenRefresh());
  }

  GitHubWriteRetry(Sleeper sleeper, Supplier<Instant> clock, GitHubWritePacer pacer) {
    this(sleeper, clock, pacer, new GitHubTokenRefresh());
  }

  GitHubWriteRetry(
      Sleeper sleeper,
      Supplier<Instant> clock,
      GitHubWritePacer pacer,
      GitHubTokenRefresh credentials) {
    this.sleeper = sleeper;
    this.clock = clock;
    this.pacer = pacer;
    this.credentials = credentials;
  }

  /**
   * Runs a content-creating GitHub call, repeating it while GitHub is throttling and the budget
   * allows. Returns the call's own result; rethrows the failure unchanged once the call is not
   * retryable or the budget is spent, so every existing caller keeps the exception type and the
   * fail-soft handling it already has.
   *
   * <p>Every attempt — the first one included — waits for its slot in the shared {@link
   * GitHubWritePacer} first, so a burst is spaced out before GitHub has to refuse it (#579) and the
   * budget below is left for the throttling this cannot prevent.
   *
   * @param operation what is being posted, for the log — never credentials or comment text
   */
  public <T> T call(String operation, Supplier<T> operationCall) {
    return call(operation, null, _ -> operationCall.get());
  }

  /**
   * The same, for a call that presents an installation token. {@code operationCall} is handed the
   * credential to use rather than closing over one, so an attempt GitHub answers with 401 can be
   * repeated with a freshly minted token instead of re-sending the dead one (#624).
   *
   * <p>The refresh happens at most once per call and outside the attempt budget: a request that was
   * turned away for its credential was never throttled, so charging it a backoff attempt would
   * spend the budget that exists for the throttling this cannot prevent. Once the credential has
   * been swapped a second 401 is final — the installation itself is refusing, and nothing fresher
   * exists to try.
   *
   * @param auth the {@code Authorization} header to start with, or {@code null} for a call that
   *     carries no credential of its own and so has nothing to refresh
   */
  public <T> T call(String operation, String auth, Function<String, T> operationCall) {
    var credential = auth;
    var refreshable = true;
    // The loop is unbounded and `attempt` is not what counts it round: an attempt is spent at the
    // one point below where a wait GitHub asked for has actually been served. A credential swap
    // repeats the call without advancing it — the "outside the attempt budget" rule above — so a
    // counted for-loop here would change how many attempts a refreshed call gets.
    var attempt = 1;
    while (true) {
      try {
        pacer.acquire(operation);
        return operationCall.apply(credential);
      } catch (WebApplicationException e) {
        var fresh = replacementCredential(operation, credential, refreshable, e);
        if (fresh.isPresent()) {
          credential = fresh.get();
          refreshable = false;
          continue;
        }
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
        attempt++;
      }
    }
  }

  /**
   * The fresher credential to repeat this failure with, or empty when there is none — either
   * because the one swap this call is allowed has already been spent, or because {@link
   * GitHubTokenRefresh} has nothing fresher to offer for this failure. Empty is what sends the
   * failure on to the backoff below, so a swap that cannot happen costs the caller nothing.
   */
  private Optional<String> replacementCredential(
      String operation, String credential, boolean refreshable, WebApplicationException failure) {
    if (!refreshable) {
      return Optional.empty();
    }
    return credentials.replacementFor(operation, credential, failure);
  }

  /**
   * How long to wait before repeating this failure, or empty when it must not be repeated — either
   * because GitHub is refusing rather than throttling, or because the attempts are spent. The
   * spent-attempts case is logged, because it is the one where a completed generation is discarded
   * and the operator needs the response's own words to see why.
   *
   * <p>That line sits behind a level check because {@link GitHubApiError#diagnostics()} builds its
   * string eagerly — a parameter placeholder defers the {@code toString}, not the call that
   * produces the argument. The message itself is unchanged: this is the warning that surfaced the
   * issue-624 diagnosis in production, so what it prints when it prints must stay exactly as it
   * was.
   */
  private Optional<Duration> retryDelay(
      String operation, WebApplicationException failure, int attempt) {
    var error = GitHubApiError.of(failure);
    if (error.isEmpty() || !error.get().isThrottled()) {
      return Optional.empty();
    }
    if (attempt >= MAX_ATTEMPTS) {
      if (log.isWarnEnabled()) {
        log.warn(
            "GitHub still throttling {} after {} attempts — the generated content is lost and the"
                + " command has to be re-run. {}",
            operation,
            MAX_ATTEMPTS,
            error.get().diagnostics());
      }
      return Optional.empty();
    }
    return Optional.of(min(error.get().retryDelay(attempt, clock.get()), MAX_DELAY_PER_ATTEMPT));
  }

  private static Duration min(Duration left, Duration right) {
    return left.compareTo(right) <= 0 ? left : right;
  }
}
