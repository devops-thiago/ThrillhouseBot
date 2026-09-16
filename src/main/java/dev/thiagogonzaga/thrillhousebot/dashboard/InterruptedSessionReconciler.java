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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives a terminal status, on startup, to the session rows a restart or a crash left {@code
 * in_progress} (#863).
 *
 * <p>A session row is written {@code in_progress} when the review starts and updated when it ends,
 * so a review killed between those two writes never got its terminal one and stayed {@code
 * in_progress} for the life of the database. Those rows count as running reviews forever and hide
 * the genuinely in-flight ones among them.
 *
 * <p>Nothing carries a review across a restart: it runs on an in-memory executor whose queue dies
 * with the process, so every row still {@code in_progress} when the application boots belongs to a
 * review that is over, whatever killed it. The sweep is therefore unconditional rather than
 * age-based, and it also reconciles rows stranded long before this code existed.
 *
 * <p>Sweeping every row at boot is only safe because the bot is a single process (README, "Known
 * limitations"): no other replica can own an {@code in_progress} row. If the bot ever runs more
 * than one replica, this has to become ownership-scoped — a replica identifier or a lease on the
 * row — or a booting replica would fail the reviews another one is still running.
 *
 * <p>The reconciled row is recorded as {@code failed} carrying {@link #INTERRUPTED_ERROR_MESSAGE}
 * instead of under a status of its own. {@code failed} is the only terminal status the dashboard
 * treats as an ended review: an unknown status would keep rendering as the pending hourglass the
 * stale rows already show, and it would fall outside both the completed and the failed counters on
 * the overview, leaving the totals not adding up. The message is what separates an interruption
 * from a review that failed on its own, and it is already on the session page. Only the status and
 * the message are written: the tokens and cost the review had paid for before it died stay on the
 * row.
 */
@ApplicationScoped
public class InterruptedSessionReconciler {

  private static final Logger log = LoggerFactory.getLogger(InterruptedSessionReconciler.class);

  /** Reason recorded on a session the bot never got to finish, shown on its dashboard page. */
  static final String INTERRUPTED_ERROR_MESSAGE =
      "Review interrupted before it finished (bot restart or crash)";

  private final ReviewSessionRepository repository;

  @Inject
  public InterruptedSessionReconciler(ReviewSessionRepository repository) {
    this.repository = repository;
  }

  void onStart(@Observes StartupEvent event) {
    try {
      var reconciled = reconcile();
      if (reconciled > 0) {
        log.info("Marked {} review session(s) interrupted by an earlier run as failed", reconciled);
      }
    } catch (RuntimeException e) {
      log.warn("Failed to reconcile interrupted review sessions; they stay in progress", e);
    }
  }

  /**
   * Moves every {@code in_progress} row to {@code failed} with the interrupted reason and returns
   * how many rows were changed. A bulk update rather than a read-modify-write: it touches only the
   * two columns, so nothing else recorded on the row can be overwritten.
   */
  @Transactional
  public int reconcile() {
    return repository.update(
        "status = ?1, errorMessage = ?2 where status = ?3",
        ReviewSession.STATUS_FAILED,
        INTERRUPTED_ERROR_MESSAGE,
        ReviewSession.STATUS_IN_PROGRESS);
  }
}
