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

import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Tracks which pull requests the bot has been paused on. Short DB transactions only — never spans
 * an external API call.
 *
 * <p>A pause belongs to an installation and repository id (#1220). A pause stored before #1220 that
 * the startup backfill could not match has no ids; it is still honored, and lifted by {@link
 * #resume}, by its exact {@code owner/repo} name. Unlike other unmatched rows, which are never
 * read, dropping it would silently resume reviews a maintainer explicitly stopped.
 */
@ApplicationScoped
public class PrPauseService {

  private static final String SCOPED =
      "installationId = ?1 and repositoryId = ?2 and prNumber = ?3";

  private static final String SCOPED_OR_UNMATCHED =
      "(" + SCOPED + ") or (installationId is null and repository = ?4 and prNumber = ?3)";

  private final PausedPrRepository repository;

  @Inject
  public PrPauseService(PausedPrRepository repository) {
    this.repository = repository;
  }

  /** Whether the bot is currently paused on {@code owner/repo} PR {@code prNumber}. */
  @Transactional
  public boolean isPaused(RepoScope scope, String owner, String repo, int prNumber) {
    return repository.count(
            SCOPED_OR_UNMATCHED,
            scope.installationId(),
            scope.repositoryId(),
            prNumber,
            repoKey(owner, repo))
        > 0;
  }

  /** Pauses the bot on the PR. Idempotent: a second pause leaves the single row untouched. */
  @Transactional
  public void pause(RepoScope scope, String owner, String repo, int prNumber) {
    if (repository.count(SCOPED, scope.installationId(), scope.repositoryId(), prNumber) > 0) {
      return;
    }
    var paused = new PausedPr();
    paused.installationId = scope.installationId();
    paused.repositoryId = scope.repositoryId();
    paused.repository = repoKey(owner, repo);
    paused.prNumber = prNumber;
    repository.persist(paused);
  }

  /** Resumes the bot on the PR. Returns whether a paused row was actually removed. */
  @Transactional
  public boolean resume(RepoScope scope, String owner, String repo, int prNumber) {
    return repository.delete(
            SCOPED_OR_UNMATCHED,
            scope.installationId(),
            scope.repositoryId(),
            prNumber,
            repoKey(owner, repo))
        > 0;
  }

  private static String repoKey(String owner, String repo) {
    return owner + "/" + repo;
  }
}
