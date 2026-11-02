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

import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Short-lived DB transactions for review session state — never spans external API calls. */
@ApplicationScoped
public class ReviewSessionPersistence {

  /**
   * The completed prior rounds of a pull request that persisted a response, newest first. Matched
   * by installation and repository id (#1220), so a renamed repository keeps its history and a
   * repository of the same name under another installation never contributes to it.
   */
  private static final String PRIOR_ROUNDS_QUERY =
      "installationId = ?1 and repositoryId = ?2 and prNumber = ?3 and id <> ?4 and status = ?5"
          + " and aiResponseJson is not null order by id desc";

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
      RepoScope scope, int prNumber, long excludeSessionId) {
    return this.repository
        .find(
            PRIOR_ROUNDS_QUERY,
            scope.installationId(),
            scope.repositoryId(),
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
      RepoScope scope, int prNumber, long excludeSessionId) {
    return this.repository
        .find(
            PRIOR_ROUNDS_QUERY,
            scope.installationId(),
            scope.repositoryId(),
            prNumber,
            excludeSessionId,
            ReviewSession.STATUS_COMPLETED)
        .stream()
        .map(ReviewSession::getAiResponseJson)
        .toList();
  }

  /**
   * Head commit SHAs of the same sessions {@link #findAllPriorAiResponseJsons} returns, in the same
   * newest-first order, so the review can tell which commit each prior round reviewed (#932). A
   * session with no recorded SHA contributes {@code null} at its position, keeping the two lists
   * aligned.
   */
  @Transactional
  public List<String> findAllPriorCommitShas(RepoScope scope, int prNumber, long excludeSessionId) {
    return this.repository
        .find(
            PRIOR_ROUNDS_QUERY,
            scope.installationId(),
            scope.repositoryId(),
            prNumber,
            excludeSessionId,
            ReviewSession.STATUS_COMPLETED)
        .stream()
        .map(ReviewSession::getCommitSha)
        .toList();
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
