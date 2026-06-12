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

  @Transactional(TxType.REQUIRES_NEW)
  public void recordModelUsage(
      long sessionId,
      String model,
      int inputTokens,
      int outputTokens,
      double cost,
      long durationMs) {
    var session = repository.findById(sessionId);
    if (session == null) {
      return;
    }
    session.setModel(model);
    session.setInputTokens(inputTokens);
    session.setOutputTokens(outputTokens);
    session.setCost(cost);
    session.setDurationMs(durationMs);
    session.persist();
  }

  @Transactional(TxType.REQUIRES_NEW)
  public void recordFailure(long sessionId, String errorMessage, long durationMs) {
    var session = repository.findById(sessionId);
    if (session == null) {
      return;
    }
    session.setStatus(ReviewSession.STATUS_FAILED);
    session.setErrorMessage(errorMessage);
    session.setDurationMs(durationMs);
    session.persist();
  }
}
