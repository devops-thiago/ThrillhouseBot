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
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Short-lived DB transactions for review session state — never spans external API calls. */
@ApplicationScoped
public class ReviewSessionPersistence {

  private final ReviewSessionRepository repository;

  @Inject
  public ReviewSessionPersistence(ReviewSessionRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void create(ReviewSession session) {
    session.persist();
  }

  /**
   * Returns the persisted AI response of the latest completed review for the same PR, excluding the
   * session currently in progress.
   */
  @Transactional
  public Optional<String> findPreviousAiResponseJson(
      String repository, int prNumber, long excludeSessionId) {
    return this.repository
        .find(
            "repository = ?1 and prNumber = ?2 and id <> ?3 and status = ?4"
                + " and aiResponseJson is not null order by id desc",
            repository,
            prNumber,
            excludeSessionId,
            ReviewSession.STATUS_COMPLETED)
        .firstResultOptional()
        .map(ReviewSession::getAiResponseJson);
  }

  /**
   * AI responses of every completed prior review for the PR, newest first. Follow-up analysis needs
   * the full history: a finding answered two rounds ago is absent from the latest response, and
   * with one round of memory it would be rediscovered as new.
   */
  @Transactional
  public List<String> findAllPriorAiResponseJsons(
      String repository, int prNumber, long excludeSessionId) {
    return this.repository
        .find(
            "repository = ?1 and prNumber = ?2 and id <> ?3 and status = ?4"
                + " and aiResponseJson is not null order by id desc",
            repository,
            prNumber,
            excludeSessionId,
            ReviewSession.STATUS_COMPLETED)
        .stream()
        .map(ReviewSession::getAiResponseJson)
        .toList();
  }

  /**
   * Whether any completed review exists for the PR. The first completed review posts the PR summary
   * comment, so this doubles as "has a summary already been generated" for the {@code /summary}
   * command gate.
   */
  @Transactional
  public boolean hasCompletedReview(String repository, int prNumber) {
    return this.repository.count(
            "repository = ?1 and prNumber = ?2 and status = ?3",
            repository,
            prNumber,
            ReviewSession.STATUS_COMPLETED)
        > 0;
  }

  /** Loads a managed session by ID and applies updates — safe after the entity becomes detached. */
  @Transactional
  public void update(long sessionId, Consumer<ReviewSession> mutator) {
    var managed = repository.findById(sessionId);
    if (managed == null) {
      return;
    }
    mutator.accept(managed);
  }
}
