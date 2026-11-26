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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

/** Persists AI usage metrics onto a review session by ID — avoids holding mutable entity refs. */
@ApplicationScoped
public class ReviewSessionUpdater {

  private final ReviewSessionRepository repository;

  @Inject
  public ReviewSessionUpdater(ReviewSessionRepository repository) {
    this.repository = repository;
  }

  /**
   * Records one AI call's usage onto the session. Map-reduce and verifier reviews issue several
   * calls per session; tokens and cost are accumulated so the dashboard reflects the full review
   * spend rather than only the last call. The call's duration is not added: calls run in parallel
   * and are retried, so their sum is not the review's time, which the review's final status writes
   * instead (#1417).
   *
   * @return the session with this call's usage added, or null when there is no such session
   */
  @Transactional(TxType.REQUIRES_NEW)
  public ReviewSession recordModelUsage(
      long sessionId,
      String model,
      int inputTokens,
      int outputTokens,
      double cost,
      boolean pricingMissing) {
    // Parallel map-reduce batches accumulate onto the same row — lock so concurrent
    // REQUIRES_NEW transactions cannot lose updates.
    var session = repository.findById(sessionId, LockModeType.PESSIMISTIC_WRITE);
    if (session == null) {
      return null;
    }
    session.setModel(model);
    session.setInputTokens(session.getInputTokens() + inputTokens);
    session.setOutputTokens(session.getOutputTokens() + outputTokens);
    session.setCost(session.getCost() + cost);
    session.setPricingMissing(session.isPricingMissing() || pricingMissing);
    session.persist();
    return session;
  }

  /**
   * Counts one failed AI call on the session (#1417). The call may be retried and the review may
   * still complete, so the status, the error message and the duration are left alone: only the
   * review's final outcome writes them. A write that lands after the review finished therefore
   * cannot turn a completed session into a failed one.
   */
  @Transactional(TxType.REQUIRES_NEW)
  public void recordFailedModelCall(long sessionId) {
    var session = repository.findById(sessionId, LockModeType.PESSIMISTIC_WRITE);
    if (session == null) {
      return;
    }
    session.setFailedModelCalls(session.getFailedModelCalls() + 1);
    session.persist();
  }
}
