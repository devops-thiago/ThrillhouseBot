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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GitHubWriteRetry} — the backoff #568 asked for on the calls that publish work the
 * bot has already paid for, and, just as importantly, the failures it refuses to repeat.
 *
 * <p>The bounds are written out as literals (3 attempts, a 30-second ceiling on one wait) so the
 * tests pin them rather than restate whatever the production constants happen to say.
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
  void aPersistentThrottleGivesUpAfterThreeAttempts() {
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

    // Bounded: one post and two repeats, so a throttled PR cannot hold its dispatcher slot forever.
    assertSame(lastFailure, thrown);
    assertEquals(3, calls.get());
    assertEquals(List.of(Duration.ofSeconds(4), Duration.ofSeconds(4)), slept);
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

    // 30s a wait, twice: the whole call waits at most a minute whatever GitHub asks for.
    assertEquals(List.of(Duration.ofSeconds(30), Duration.ofSeconds(30)), slept);
  }

  @Test
  void anExhaustedWindowWaitsUntilItsResetInstant() {
    var calls = new AtomicInteger();

    var result =
        retry.call(
            "a comment on o/r #7",
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw throttled("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1800000021");
              }
              return "posted";
            });

    assertEquals("posted", result);
    assertEquals(List.of(Duration.ofSeconds(21)), slept);
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
}
