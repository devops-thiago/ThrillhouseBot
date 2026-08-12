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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GitHubWritePacer} — the spacing #579 asked for, so a burst of content-creating
 * calls is never produced rather than being produced and refused.
 *
 * <p>The clock is driven by hand and the waiting is recorded instead of served, so what is pinned
 * here is the arithmetic of the shared cursor: who waits, for how long, and who does not.
 */
class GitHubWritePacerTest {

  private static final Duration ONE_SECOND = Duration.ofSeconds(1);
  private static final Duration ONE_MINUTE = Duration.ofSeconds(60);

  /** Outside the mapped {@code thrillhousebot} namespace, so it is nobody's unknown key. */
  private static final String PROBE_KEY = "github-write-pacer-test.interval";

  private final List<Duration> waited = new ArrayList<>();
  private final AtomicLong nanos = new AtomicLong();

  private GitHubWritePacer pacer(Duration interval, Duration ceiling) {
    return new GitHubWritePacer(interval, ceiling, waited::add, nanos::get);
  }

  @Test
  void aLoneWriteOnAQuietRepoIsNotDelayedAtAll() {
    pacer(ONE_SECOND, ONE_MINUTE).acquire("a comment on o/r #7");

    assertEquals(List.of(), waited);
  }

  @Test
  void aBurstIsHandedOutOneIntervalApartInsteadOfAllAtOnce() {
    var pacer = pacer(ONE_SECOND, ONE_MINUTE);

    pacer.acquire("a comment on o/r #7");
    pacer.acquire("a comment on o/r #8");
    pacer.acquire("a review on o/r #9");

    // The first goes straight out; the rest queue behind it rather than racing it to GitHub.
    assertEquals(List.of(ONE_SECOND, Duration.ofSeconds(2)), waited);
  }

  @Test
  void aWriteThatArrivesAfterTheQueueDrainedIsNotDelayed() {
    var pacer = pacer(ONE_SECOND, ONE_MINUTE);

    pacer.acquire("a comment on o/r #7");
    nanos.addAndGet(Duration.ofSeconds(30).toNanos());
    pacer.acquire("a comment on o/r #7");

    // Pacing is a floor on spacing, not a queue that keeps charging a quiet repo.
    assertEquals(List.of(), waited);
  }

  @Test
  void aQueueLongerThanTheCeilingStopsWaitingAtTheCeiling() {
    var pacer = pacer(ONE_SECOND, Duration.ofMillis(1500));

    pacer.acquire("a comment on o/r #7");
    pacer.acquire("a comment on o/r #8");
    pacer.acquire("a comment on o/r #9");
    pacer.acquire("a comment on o/r #10");

    // 1s, then 2s and 3s clamped: past the ceiling the call goes out and the backoff covers it,
    // so a long queue can never park a finished command for minutes.
    assertEquals(List.of(ONE_SECOND, Duration.ofMillis(1500), Duration.ofMillis(1500)), waited);
  }

  @Test
  void aZeroIntervalTurnsPacingOffEntirely() {
    var pacer = pacer(Duration.ZERO, ONE_MINUTE);

    pacer.acquire("a comment on o/r #7");
    pacer.acquire("a comment on o/r #8");
    pacer.acquire("a comment on o/r #9");

    assertEquals(List.of(), waited);
  }

  @Test
  void anInterruptedWaitSendsTheCallAnywayAndKeepsTheInterrupt() {
    var interrupting =
        new GitHubWritePacer(
            ONE_SECOND,
            ONE_MINUTE,
            delay -> {
              throw new InterruptedException("shutting down");
            },
            nanos::get);

    interrupting.acquire("a comment on o/r #7");
    // The second one is the one that has to wait, and the wait is what gets interrupted.
    interrupting.acquire("a comment on o/r #7");

    // The limiter never costs the payload: the write goes out unpaced rather than failing.
    assertTrue(Thread.interrupted(), "interrupt status restored");
    assertFalse(Thread.currentThread().isInterrupted());
  }

  @Test
  void theEnvelopeIsAKnobAndFallsBackToTheGuidanceWhenItIsUnset() {
    System.setProperty(PROBE_KEY, "250ms");

    assertEquals(Duration.ofMillis(250), GitHubWritePacer.configured(PROBE_KEY, ONE_SECOND));
    assertEquals(
        ONE_SECOND, GitHubWritePacer.configured("github-write-pacer-test.unset", ONE_SECOND));
  }

  @Test
  void theDocumentedBareZeroSurvivesTheConfigConverterAndReallyDisablesPacing() {
    System.setProperty(PROBE_KEY, "0");

    // application.properties tells operators GITHUB_WRITE_MIN_INTERVAL=0 disables pacing, and that
    // promise runs through the config converter rather than through a Duration.ZERO handed to the
    // constructor. Worth pinning: an unconvertible value in the mapped namespace is a validation
    // error, so a converter that rejected a bare "0" would fail startup rather than disable
    // anything.
    var configured = GitHubWritePacer.configured(PROBE_KEY, ONE_SECOND);
    assertEquals(Duration.ZERO, configured);

    var pacer = new GitHubWritePacer(configured, ONE_MINUTE, waited::add, nanos::get);
    pacer.acquire("a comment on o/r #7");
    pacer.acquire("a comment on o/r #8");

    assertEquals(List.of(), waited);
  }

  @AfterEach
  void clearProbe() {
    System.clearProperty(PROBE_KEY);
  }
}
