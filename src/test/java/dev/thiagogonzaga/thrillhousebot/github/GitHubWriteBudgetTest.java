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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GitHubWriteBudget} — the per-review ceiling #734 asked for on the waiting {@link
 * GitHubWriteRetry} serves. The retry bounds one call at 90 seconds, and a review posts one call
 * per route per finding, so a review throttled on every finding could hold its pull request's
 * dispatcher slot for hours with nothing in the log saying it was waiting rather than hung.
 *
 * <p>The arithmetic is pinned with hand-picked waits rather than a clock: what the budget counts is
 * the waiting the retry was about to serve, and that is what these hand it.
 */
class GitHubWriteBudgetTest {

  private static final String REVIEW = "o/r #7";
  private static final String OPERATION = "an inline comment on o/r #7";
  private static final Duration FOUR_SECONDS = Duration.ofSeconds(4);
  private static final Duration SIX_SECONDS = Duration.ofSeconds(6);

  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
  private final Logger julLogger = Logger.getLogger(GitHubWriteBudget.class.getName());
  private final Handler capture =
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
  private Level originalLevel;

  @BeforeEach
  void captureLogging() {
    originalLevel = julLogger.getLevel();
    julLogger.setLevel(Level.ALL);
    julLogger.addHandler(capture);
  }

  @AfterEach
  void restoreLogging() {
    julLogger.removeHandler(capture);
    julLogger.setLevel(originalLevel);
  }

  private List<String> warnings() {
    return logged.stream()
        .filter(entry -> entry.getLevel().intValue() >= Level.WARNING.intValue())
        .map(entry -> entry.getMessage() + " " + Arrays.toString(entry.getParameters()))
        .toList();
  }

  @Test
  void outsideAnyReviewEveryWaitIsAdmitted() {
    // The on-demand commands and the thread replies post outside a review, and keep the
    // per-call bound alone: a ledger that nobody opened bounds nothing.
    assertTrue(GitHubWriteBudget.admits(OPERATION, Duration.ofHours(1)));
    assertEquals(Optional.empty(), GitHubWriteBudget.exhausted());
    assertEquals(List.of(), warnings());
  }

  @Test
  void aReviewIsStoppedOnceItsWaitingCrossesTheBudget() {
    var admitted = new ArrayList<Boolean>();

    GitHubWriteBudget.within(
        REVIEW,
        SIX_SECONDS,
        () -> {
          admitted.add(GitHubWriteBudget.admits(OPERATION, FOUR_SECONDS));
          assertEquals(Optional.empty(), GitHubWriteBudget.exhausted(), "4s of 6s is not spent");
          // The wait that crosses the ceiling is still served — the overrun is bounded by one
          // clamped wait — and it is the last one that is.
          admitted.add(GitHubWriteBudget.admits(OPERATION, FOUR_SECONDS));
          assertEquals(Optional.of(SIX_SECONDS), GitHubWriteBudget.exhausted());
          admitted.add(GitHubWriteBudget.admits(OPERATION, Duration.ofSeconds(1)));
        });

    assertEquals(List.of(true, true, false), admitted);
    // Closed with the review: the next review on this thread starts from nothing.
    assertEquals(Optional.empty(), GitHubWriteBudget.exhausted());
    assertTrue(GitHubWriteBudget.admits(OPERATION, FOUR_SECONDS));
  }

  @Test
  void crossingTheBudgetIsWarnedAboutOnceNamingTheReviewAndTheBudget() {
    GitHubWriteBudget.within(
        REVIEW,
        Duration.ofSeconds(1),
        () -> {
          GitHubWriteBudget.admits(OPERATION, Duration.ofSeconds(1));
          GitHubWriteBudget.admits(OPERATION, Duration.ofSeconds(1));
          GitHubWriteBudget.admits(OPERATION, Duration.ofSeconds(1));
        });

    var warnings = warnings();
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.getFirst().contains(REVIEW), warnings.toString());
    assertTrue(warnings.getFirst().contains("write-retry budget"), warnings.toString());
    assertTrue(warnings.getFirst().contains("1s"), warnings.toString());
  }

  @Test
  void aZeroBudgetTurnsTheCeilingOff() {
    GitHubWriteBudget.within(
        REVIEW,
        Duration.ZERO,
        () -> {
          assertTrue(GitHubWriteBudget.admits(OPERATION, Duration.ofHours(1)));
          assertEquals(Optional.empty(), GitHubWriteBudget.exhausted());
        });

    assertEquals(List.of(), warnings());
  }

  @Test
  void aNestedScopeRejoinsTheOpenReviewRatherThanStartingALedgerOfItsOwn() {
    var admitted = new ArrayList<Boolean>();

    GitHubWriteBudget.within(
        REVIEW,
        SIX_SECONDS,
        () -> {
          admitted.add(GitHubWriteBudget.admits(OPERATION, FOUR_SECONDS));
          GitHubWriteBudget.within(
              REVIEW,
              Duration.ofHours(1),
              () -> {
                // Charged to the review already open, under its budget, not to a fresh hour.
                admitted.add(GitHubWriteBudget.admits(OPERATION, FOUR_SECONDS));
                admitted.add(GitHubWriteBudget.admits(OPERATION, FOUR_SECONDS));
              });
          // The inner scope closing does not close the review it joined.
          admitted.add(GitHubWriteBudget.admits(OPERATION, FOUR_SECONDS));
          assertEquals(Optional.of(SIX_SECONDS), GitHubWriteBudget.exhausted());
        });

    assertEquals(List.of(true, true, false, false), admitted);
    assertEquals(Optional.empty(), GitHubWriteBudget.exhausted());
  }

  @Test
  void theScopeIsClosedEvenWhenTheReviewThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            GitHubWriteBudget.within(
                REVIEW,
                Duration.ofSeconds(1),
                () -> {
                  GitHubWriteBudget.admits(OPERATION, Duration.ofSeconds(1));
                  throw new IllegalStateException("the review failed");
                }));

    assertEquals(Optional.empty(), GitHubWriteBudget.exhausted());
    assertTrue(GitHubWriteBudget.admits(OPERATION, FOUR_SECONDS));
  }
}
