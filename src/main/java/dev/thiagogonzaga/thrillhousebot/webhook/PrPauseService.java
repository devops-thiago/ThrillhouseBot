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
package dev.thiagogonzaga.thrillhousebot.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Tracks which pull requests the bot has been paused on. Short DB transactions only — never spans
 * an external API call.
 */
@ApplicationScoped
public class PrPauseService {

  private static final String BY_PR = "repository = ?1 and prNumber = ?2";

  private final PausedPrRepository repository;

  @Inject
  public PrPauseService(PausedPrRepository repository) {
    this.repository = repository;
  }

  /** Whether the bot is currently paused on {@code owner/repo} PR {@code prNumber}. */
  @Transactional
  public boolean isPaused(String owner, String repo, int prNumber) {
    return repository.count(BY_PR, repoKey(owner, repo), prNumber) > 0;
  }

  /** Pauses the bot on the PR. Idempotent: a second pause leaves the single row untouched. */
  @Transactional
  public void pause(String owner, String repo, int prNumber) {
    var key = repoKey(owner, repo);
    if (repository.count(BY_PR, key, prNumber) > 0) {
      return;
    }
    var paused = new PausedPr();
    paused.repository = key;
    paused.prNumber = prNumber;
    repository.persist(paused);
  }

  /** Resumes the bot on the PR. Returns whether a paused row was actually removed. */
  @Transactional
  public boolean resume(String owner, String repo, int prNumber) {
    return repository.delete(BY_PR, repoKey(owner, repo), prNumber) > 0;
  }

  private static String repoKey(String owner, String repo) {
    return owner + "/" + repo;
  }
}
