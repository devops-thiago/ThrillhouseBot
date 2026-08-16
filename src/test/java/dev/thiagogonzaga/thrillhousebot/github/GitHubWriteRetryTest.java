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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GitHubWriteRetry} — the backoff #568 asked for on the calls that publish work the
 * bot has already paid for, and, just as importantly, the failures it refuses to repeat.
 *
 * <p>The bounds are written out as literals (4 attempts, a 30-second ceiling on one wait) so the
 * tests pin them rather than restate whatever the production constants happen to say. The fourth
 * attempt is #722: the budget was three, and a measured 72-second content-creation block outlasted
 * it.
 */
class GitHubWriteRetryTest {

  private static final String SECONDARY_LIMIT_BODY =
      "{\"message\":\"You have exceeded a secondary rate limit.\"}";

  private final List<Duration> slept = new ArrayList<>();

  /** A retry whose waiting is recorded instead of served, on a clock that never moves. */
  private final GitHubWriteRetry retry =
      new GitHubWriteRetry(slept::add, () -> Instant.ofEpochSecond(1_800_000_000L));

  private static WebApplicationException failure(int status, String body, String... headers) {
    var builder = Response.status(status);
    for (int i = 0; i < headers.length; i += 2) {
      builder.header(headers[i], headers[i + 1]);
    }
    return new WebApplicationException(builder.entity(body).build());
  }

  private static WebApplicationException throttled(String... headers) {
    return failure(403, SECONDARY_LIMIT_BODY, headers);
  }

  @Test
  void aCallThatSucceedsIsNotRepeatedAndNeverWaits() {
    var calls = new AtomicInteger();

    var result = retry.call("a comment on o/r #7", () -> "posted " + calls.incrementAndGet());

    assertEquals("posted 1", result);
    assertEquals(1, calls.get());
    assertEquals(List.of(), slept);
  }

  @Test
  void aThrottledPostIsRepeatedAfterTheWaitGitHubAskedFor() {
    var calls = new AtomicInteger();

    var result =
        retry.call(
            "a comment on o/r #7",
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw throttled("Retry-After", "12");
              }
              return "posted";
            });

    // The generation was already paid for, so the second attempt is what saves it.
    assertEquals("posted", result);
    assertEquals(2, calls.get());
    assertEquals(List.of(Duration.ofSeconds(12)), slept);
  }

  @Test
  void aPermissionRefusalFailsOnTheFirstAttemptWithoutWaiting() {
    var calls = new AtomicInteger();
    var refusal = failure(403, "{\"message\":\"Resource not accessible by integration\"}");

    var thrown =
        assertThrows(
            WebApplicationException.class,
            () ->
                retry.call(
                    "a comment on o/r #7",
                    () -> {
                      calls.incrementAndGet();
                      throw refusal;
                    }));

    // Repeating this would only burn the rate-limit budget; the caller's handling is unchanged.
    assertSame(refusal, thrown);
    assertEquals(1, calls.get());
    assertEquals(List.of(), slept);
  }

  @Test
  void aServerFailureIsNeverRepeatedBecauseTheWriteMayHaveLanded() {
    var calls = new AtomicInteger();

    assertThrows(
        WebApplicationException.class,
        () ->
            retry.call(
                "a comment on o/r #7",
                () -> {
                  calls.incrementAndGet();
                  throw failure(502, "<html>bad gateway</html>", "Retry-After", "1");
                }));

    // This is the duplicate-comment case: a 502 says nothing about whether the comment exists.
    assertEquals(1, calls.get());
    assertEquals(List.of(), slept);
  }

  @Test
  void aFailureWithNoResponseAtAllIsNeverRepeated() {
    var calls = new AtomicInteger();
    var connectionLost =
        new WebApplicationException("connection reset") {
          @Override
          public Response getResponse() {
            return null;
          }
        };

    assertThrows(
        WebApplicationException.class,
        () ->
            retry.call(
                "a comment on o/r #7",
                () -> {
                  calls.incrementAndGet();
                  throw connectionLost;
                }));

    assertEquals(1, calls.get());
    assertEquals(List.of(), slept);
  }

  @Test
  void aNonHttpFailurePropagatesUntouched() {
    var boom = new IllegalStateException("serialization failed");

    var thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                retry.call(
                    "a comment on o/r #7",
                    () -> {
                      throw boom;
                    }));

    assertSame(boom, thrown);
    assertEquals(List.of(), slept);
  }

  @Test
  void aPersistentThrottleGivesUpAfterFourAttempts() {
    var calls = new AtomicInteger();
    var lastFailure = throttled("Retry-After", "4");

    var thrown =
        assertThrows(
            WebApplicationException.class,
            () ->
                retry.call(
                    "a comment on o/r #7",
                    () -> {
                      calls.incrementAndGet();
                      throw lastFailure;
                    }));

    // Bounded: one post and three repeats, so a throttled PR cannot hold its dispatcher slot
    // forever. The third repeat is what #722 added, after a measured 72-second content-creation
    // block outlasted the two the budget had.
    assertSame(lastFailure, thrown);
    assertEquals(4, calls.get());
    assertEquals(
        List.of(Duration.ofSeconds(4), Duration.ofSeconds(4), Duration.ofSeconds(4)), slept);
  }

  @Test
  void aSpentBudgetStillGivesUpSilentlyWhenWarningsAreOff() {
    // The give-up line sits behind a level check, because diagnostics() builds its string eagerly
    // and a parameter placeholder only defers the toString. With the logger off nothing may be
    // logged, and the retry must still spend the same four attempts and rethrow the same failure.
    var julLogger = Logger.getLogger(GitHubWriteRetry.class.getName());
    var logged = new CopyOnWriteArrayList<LogRecord>();
    var capture =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            logged.add(entry);
          }

          @Override
          public void flush() {
            // Nothing is buffered.
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
    var originalLevel = julLogger.getLevel();
    julLogger.setLevel(Level.OFF);
    julLogger.addHandler(capture);
    var calls = new AtomicInteger();
    var lastFailure = throttled("Retry-After", "4");

    try {
      var thrown =
          assertThrows(
              WebApplicationException.class,
              () ->
                  retry.call(
                      "a comment on o/r #7",
                      () -> {
                        calls.incrementAndGet();
                        throw lastFailure;
                      }));

      assertSame(lastFailure, thrown);
      assertEquals(4, calls.get());
      assertEquals(
          List.of(Duration.ofSeconds(4), Duration.ofSeconds(4), Duration.ofSeconds(4)), slept);
      assertTrue(logged.isEmpty(), logged.toString());
    } finally {
      julLogger.removeHandler(capture);
      julLogger.setLevel(originalLevel);
    }
  }

  @Test
  void aRetryAfterLongerThanTheCeilingIsClampedRatherThanObeyed() {
    var calls = new AtomicInteger();

    assertThrows(
        WebApplicationException.class,
        () ->
            retry.call(
                "a review on o/r #7",
                () -> {
                  calls.incrementAndGet();
                  throw throttled("Retry-After", "3600");
                }));

    // 30s a wait, three times: the whole call waits at most TOTAL_BUDGET whatever GitHub asks for.
    assertEquals(
        List.of(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30)), slept);
    assertEquals(
        GitHubWriteRetry.TOTAL_BUDGET,
        slept.stream().reduce(Duration.ZERO, Duration::plus),
        "the clamped waits add up to exactly the documented budget");
  }

  @Test
  void anExhaustedWindowWaitsUntilItsResetInstant() {
    var calls = new AtomicInteger();

    // Deliberately NOT the content-creation wording: that block is floored regardless of the
    // rate-limit headers (#722), because they describe the primary window it leaves untouched. This
    // is the plain primary exhaustion, where the reset instant really is the deadline.
    var result =
        retry.call(
            "a comment on o/r #7",
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw failure(
                    403,
                    "{\"message\":\"API rate limit exceeded\"}",
                    "x-ratelimit-remaining",
                    "0",
                    "x-ratelimit-reset",
                    "1800000021");
              }
              return "posted";
            });

    assertEquals("posted", result);
    assertEquals(List.of(Duration.ofSeconds(21)), slept);
  }

  /**
   * #732. Every caller of this loop — {@link GitHubLostWrites} included — catches {@code
   * WebApplicationException}, because that is the failure a GitHub write produces. An {@code
   * x-ratelimit-reset} outside the range of an {@code Instant} used to replace it with a {@code
   * DateTimeException} thrown from inside the delay derivation, which no catch on the way out
   * matched: the write was lost and the record of its loss went with it. Only an intermediary sends
   * a header like this, and only the one header is needed.
   */
  @Test
  void anOutOfRangeResetHeaderStillFailsAsTheExceptionEveryCallerCatches() {
    var calls = new AtomicInteger();
    var throttle =
        throttled(
            "x-ratelimit-remaining", "0", "x-ratelimit-reset", String.valueOf(Long.MAX_VALUE));

    var thrown =
        assertThrows(
            WebApplicationException.class,
            () ->
                retry.call(
                    "a comment on o/r #7",
                    () -> {
                      calls.incrementAndGet();
                      throw throttle;
                    }));

    // Unusable is unspecified: the linear fallback takes over and the budget runs its course, so
    // the caller sees the same rejection it would have seen from any other spent throttle.
    assertSame(throttle, thrown);
    assertEquals(4, calls.get());
    assertEquals(
        List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15)), slept);
  }

  /** The same header at the other end of the range, on a throttle that clears on the repeat. */
  @Test
  void anOutOfRangeResetHeaderOnAThrottleFallsBackToTheLinearWait() {
    var calls = new AtomicInteger();

    var result =
        retry.call(
            "a comment on o/r #7",
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw throttled(
                    "x-ratelimit-remaining",
                    "0",
                    "x-ratelimit-reset",
                    String.valueOf(Long.MIN_VALUE));
              }
              return "posted";
            });

    assertEquals("posted", result);
    assertEquals(List.of(Duration.ofSeconds(5)), slept);
  }

  @Test
  void everyAttemptWaitsForItsPacingSlotIncludingTheRepeats() {
    var paced = new ArrayList<Duration>();
    var calls = new AtomicInteger();
    var pacedRetry =
        new GitHubWriteRetry(
            slept::add,
            () -> Instant.ofEpochSecond(1_800_000_000L),
            new GitHubWritePacer(
                Duration.ofSeconds(1), Duration.ofSeconds(60), paced::add, () -> 0L));

    var result =
        pacedRetry.call(
            "a comment on o/r #7",
            () -> {
              if (calls.incrementAndGet() < 3) {
                throw throttled("Retry-After", "1");
              }
              return "posted";
            });

    // A repeat is a content-creating call too, so it queues rather than jumping the limiter.
    assertEquals("posted", result);
    assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), paced);
  }

  @Test
  void anInterruptedBackoffStopsImmediatelyAndKeepsTheInterrupt() {
    var calls = new AtomicInteger();
    var interrupting =
        new GitHubWriteRetry(
            delay -> {
              throw new InterruptedException("shutting down");
            },
            Instant::now);
    var lastFailure = throttled("Retry-After", "5");

    var thrown =
        assertThrows(
            WebApplicationException.class,
            () ->
                interrupting.call(
                    "a comment on o/r #7",
                    () -> {
                      calls.incrementAndGet();
                      throw lastFailure;
                    }));

    assertSame(lastFailure, thrown);
    assertEquals(1, calls.get());
    // The flag is restored so the shutdown the interrupt announced is not swallowed here.
    assertTrue(Thread.interrupted(), "interrupt status restored");
    assertFalse(Thread.currentThread().isInterrupted());
  }

  /**
   * The #624 half: the one failure the backoff above cannot fix by repeating, because every repeat
   * presents the same dead installation token. The seam here belongs to this retry instance, so
   * nothing in these tests touches the process-wide binding.
   */
  @Nested
  class ExpiredCredentials {

    private static final String DEAD = "Bearer expired-token";
    private static final String FRESH = "Bearer minted-token";

    private final GitHubTokenRefresh credentials = new GitHubTokenRefresh();

    private final GitHubWriteRetry refreshing =
        new GitHubWriteRetry(
            slept::add,
            () -> Instant.ofEpochSecond(1_800_000_000L),
            GitHubWritePacer.NONE,
            credentials);

    private final AtomicInteger mints = new AtomicInteger();

    private WebApplicationException badCredentials() {
      return failure(401, "{\"message\":\"Bad credentials\",\"status\":\"401\"}");
    }

    private void minting(String replacement) {
      credentials.bind(
          _ -> {
            mints.incrementAndGet();
            return Optional.ofNullable(replacement);
          });
    }

    @Test
    void aRejectedCredentialIsReplacedAndTheWriteRepeatedWithIt() {
      minting(FRESH);
      var presented = new ArrayList<String>();

      var result =
          refreshing.call(
              "a review on o/r #532",
              DEAD,
              credential -> {
                presented.add(credential);
                if (DEAD.equals(credential)) {
                  throw badCredentials();
                }
                return "posted";
              });

      // The generation was already paid for; a fresh token is the only thing that can save it.
      assertEquals("posted", result);
      assertEquals(List.of(DEAD, FRESH), presented);
      // A rejected credential was never throttled, so the repeat costs no wait and no attempt.
      assertEquals(List.of(), slept);
    }

    @Test
    void aCredentialIsReplacedOnlyOncePerCall() {
      minting(FRESH);
      var calls = new AtomicInteger();

      assertThrows(
          WebApplicationException.class,
          () ->
              refreshing.call(
                  "a review on o/r #532",
                  DEAD,
                  _ -> {
                    calls.incrementAndGet();
                    throw badCredentials();
                  }));

      // The installation itself is refusing; a third token would be no fresher than the second.
      assertEquals(2, calls.get());
      assertEquals(1, mints.get());
    }

    @Test
    void aThrottleIsNotMistakenForADeadCredential() {
      minting(FRESH);
      var calls = new AtomicInteger();

      var result =
          refreshing.call(
              "a comment on o/r #7",
              DEAD,
              credential -> {
                if (calls.incrementAndGet() == 1) {
                  throw throttled("Retry-After", "3");
                }
                return "posted with " + credential;
              });

      assertEquals("posted with " + DEAD, result);
      assertEquals(0, mints.get(), "a 403 says nothing about the credential");
      assertEquals(List.of(Duration.ofSeconds(3)), slept);
    }

    @Test
    void aCredentialThatCannotBeReplacedFailsOnTheFirstAttempt() {
      minting(null);
      var calls = new AtomicInteger();
      var rejection = badCredentials();

      var thrown =
          assertThrows(
              WebApplicationException.class,
              () ->
                  refreshing.call(
                      "a review on o/r #532",
                      DEAD,
                      _ -> {
                        calls.incrementAndGet();
                        throw rejection;
                      }));

      assertSame(rejection, thrown);
      assertEquals(1, calls.get());
    }

    @Test
    void aReplacementIdenticalToTheRejectedCredentialIsNotWorthRepeating() {
      minting(DEAD);
      var calls = new AtomicInteger();

      assertThrows(
          WebApplicationException.class,
          () ->
              refreshing.call(
                  "a review on o/r #532",
                  DEAD,
                  _ -> {
                    calls.incrementAndGet();
                    throw badCredentials();
                  }));

      // The cache is already holding the newest token GitHub will issue and it is still refused.
      assertEquals(1, calls.get());
    }

    @Test
    void aFailureWhileMintingLeavesTheRejectionThatExplainsTheLoss() {
      credentials.bind(
          _ -> {
            throw new IllegalStateException("the token endpoint is down too");
          });
      var rejection = badCredentials();

      var thrown =
          assertThrows(
              WebApplicationException.class,
              () ->
                  refreshing.call(
                      "a review on o/r #532",
                      DEAD,
                      _ -> {
                        throw rejection;
                      }));

      assertSame(rejection, thrown, "the 401 is what the operator needs to see, not a mint error");
    }

    @Test
    void aCallCarryingNoCredentialHasNothingToReplace() {
      minting(FRESH);
      var rejection = badCredentials();

      assertThrows(
          WebApplicationException.class,
          () ->
              refreshing.call(
                  "a check of o/r",
                  () -> {
                    throw rejection;
                  }));

      assertEquals(0, mints.get());
    }

    @Test
    void theOneShotFormLeavesAWriteThatSucceedsAlone() {
      minting(FRESH);

      var result = credentials.retrying("check run 1 on o/r", DEAD, credential -> credential);

      assertEquals(DEAD, result);
      assertEquals(0, mints.get(), "a credential GitHub accepted is never replaced");
    }

    /** The one-shot form, for the writes that have no backoff loop of their own. */
    @Test
    void theOneShotFormReplacesAndRepeatsExactlyOnce() {
      minting(FRESH);
      var presented = new ArrayList<String>();

      var result =
          credentials.retrying(
              "check run 94131141478 on o/r",
              DEAD,
              credential -> {
                presented.add(credential);
                if (DEAD.equals(credential)) {
                  throw badCredentials();
                }
                return "updated";
              });

      assertEquals("updated", result);
      assertEquals(List.of(DEAD, FRESH), presented);
    }

    @Test
    void theOneShotFormRethrowsWhenNothingCanBeMinted() {
      minting(null);
      var rejection = badCredentials();

      var thrown =
          assertThrows(
              WebApplicationException.class,
              () ->
                  credentials.retrying(
                      "check run 1 on o/r",
                      DEAD,
                      _ -> {
                        throw rejection;
                      }));

      assertSame(rejection, thrown);
    }

    @Test
    void anUnboundSeamLeavesEveryRefusalExactlyAsItWas() {
      credentials.bind(null);
      var rejection = badCredentials();

      var thrown =
          assertThrows(
              WebApplicationException.class,
              () ->
                  credentials.retrying(
                      "check run 1 on o/r",
                      DEAD,
                      _ -> {
                        throw rejection;
                      }));

      assertSame(rejection, thrown);
    }
  }

  /**
   * #722. The budget used to be three attempts, documented as "long enough to outlast the
   * minute-long window GitHub's content-creation secondary limit uses". A dogfood round measured a
   * content-creation block lasting 72 seconds — with primary quota nowhere near exhausted — and
   * sixty seconds of budget expired inside it, so the writes were given up on and the findings lost
   * their threads.
   */
  @Nested
  class TheMeasuredSecondaryLimitWindow {

    /** The window measured in #722, which the budget has to outlast to be worth having. */
    private static final Duration OBSERVED_BLOCK = Duration.ofSeconds(72);

    /** The body measured in #722, verbatim: the generic wording AND the clause naming the block. */
    private static final String BLOCK_BODY =
        "{\"message\":\"You have exceeded a secondary rate limit and have been temporarily blocked"
            + " from content creation. Please retry your request again later.\"}";

    /**
     * The block, driven end to end on a clock the recorded waits advance: GitHub keeps refusing
     * until as much simulated time has passed as the measured window lasted, so what is pinned is
     * the wall clock the budget actually spans rather than how many attempts it took to get there.
     *
     * @param headers what GitHub sends alongside the block body
     */
    private void spansTheBlock(String... headers) {
      var calls = new AtomicInteger();
      var elapsed = new AtomicLong();
      var start = Instant.ofEpochSecond(1_800_000_000L);
      var backoff =
          new GitHubWriteRetry(
              wait -> elapsed.addAndGet(wait.toSeconds()), () -> start.plusSeconds(elapsed.get()));

      var result =
          backoff.call(
              "an inline comment on o/r #7",
              () -> {
                calls.incrementAndGet();
                if (elapsed.get() < OBSERVED_BLOCK.toSeconds()) {
                  throw failure(403, BLOCK_BODY, headers);
                }
                return "posted";
              });

      assertEquals("posted", result, "the write GitHub was blocking has to land in the end");
      assertTrue(
          elapsed.get() >= OBSERVED_BLOCK.toSeconds(),
          "the budget has to span the block, not expire inside it — waited only "
              + elapsed.get()
              + "s");
    }

    /**
     * #730, the branch that was still broken: GitHub names a deadline of its own, and it is far
     * shorter than the block. Before the floor covered this branch, three waits of five seconds
     * gave up 15 seconds into a 72-second block — half of what the linear fallback #722 replaced
     * would have spread.
     */
    @Test
    void aBlockIsOutlastedEvenWhenGitHubNamesAShortDeadline() {
      spansTheBlock("Retry-After", "5");
    }

    /**
     * #722's own shape, kept as the control on the other branch: no {@code Retry-After}, primary
     * quota nowhere near exhausted, and a reset instant that belongs to that untouched primary
     * window and so is already in the past. This one passed before #730 and has to keep passing.
     */
    @Test
    void aBlockIsOutlastedWhenGitHubNamesNoDeadlineAtAll() {
      spansTheBlock(
          "x-ratelimit-remaining",
          "4771",
          "x-ratelimit-reset",
          String.valueOf(Instant.ofEpochSecond(1_800_000_000L).getEpochSecond() - 90));
    }

    @Test
    void aBlockAsLongAsTheMeasuredOneIsOutlasted() {
      var calls = new AtomicInteger();
      var elapsed = new AtomicLong();
      // A clock the recorded waits actually advance, so GitHub stays blocked until as much time
      // has passed as the measured window lasted.
      var backoff =
          new GitHubWriteRetry(
              wait -> elapsed.addAndGet(wait.toSeconds()),
              () -> Instant.ofEpochSecond(1_800_000_000L));

      var result =
          backoff.call(
              "a comment on o/r #7",
              () -> {
                calls.incrementAndGet();
                if (elapsed.get() < OBSERVED_BLOCK.toSeconds()) {
                  throw throttled("Retry-After", "30");
                }
                return "posted";
              });

      assertEquals("posted", result);
      assertTrue(
          elapsed.get() >= OBSERVED_BLOCK.toSeconds(),
          "the budget has to span the block, not expire inside it — waited only " + elapsed.get());
      assertTrue(calls.get() >= 4, "a block this wide costs more than the old two repeats");
    }

    @Test
    void theTotalBudgetOutlastsIt() {
      assertTrue(
          GitHubWriteRetry.TOTAL_BUDGET.compareTo(OBSERVED_BLOCK) > 0,
          "TOTAL_BUDGET ("
              + GitHubWriteRetry.TOTAL_BUDGET
              + ") must outlast the measured "
              + OBSERVED_BLOCK
              + " block");
    }

    @Test
    void theBudgetIsDerivedFromTheTwoBoundsThatProduceIt() {
      // Both values are read into locals first. Passing the constant itself as the actual argument
      // reads to SonarCloud (java:S3415) as the expected value in the wrong position, since a
      // static final field is exactly what that rule looks for on the right-hand side.
      var budget = GitHubWriteRetry.TOTAL_BUDGET;
      var derivedFromTheBounds =
          GitHubWriteRetry.MAX_DELAY_PER_ATTEMPT.multipliedBy(GitHubWriteRetry.MAX_ATTEMPTS - 1L);

      assertEquals(Duration.ofSeconds(90), budget);
      assertEquals(Duration.ofSeconds(90), derivedFromTheBounds);
    }
  }
}
