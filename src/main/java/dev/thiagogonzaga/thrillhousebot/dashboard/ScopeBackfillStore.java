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
import dev.thiagogonzaga.thrillhousebot.review.FindingFeedbackRepository;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningRepository;
import dev.thiagogonzaga.thrillhousebot.webhook.PausedPrRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The database side of {@link ScopeBackfill}: finds the repositories that still have rows without
 * an installation or repository id, and fills those rows once a repository is matched. Short
 * transactions only; the GitHub calls happen outside them.
 */
@ApplicationScoped
public class ScopeBackfillStore {

  private final ReviewSessionRepository sessions;
  private final PausedPrRepository pauses;
  private final FindingFeedbackRepository feedback;
  private final ReviewLearningRepository learnings;

  @Inject
  public ScopeBackfillStore(
      ReviewSessionRepository sessions,
      PausedPrRepository pauses,
      FindingFeedbackRepository feedback,
      ReviewLearningRepository learnings) {
    this.sessions = sessions;
    this.pauses = pauses;
    this.feedback = feedback;
    this.learnings = learnings;
  }

  /** Lower-cased {@code owner/repo} names that still have rows without a scope, in any table. */
  @Transactional
  public Set<String> unscopedRepositories() {
    var names = new TreeSet<String>();
    var em = sessions.getEntityManager();
    for (var query :
        new String[] {
          "select distinct lower(s.repository) from ReviewSession s"
              + " where s.installationId is null and s.repository is not null",
          "select distinct lower(p.repository) from PausedPr p where p.installationId is null",
          "select distinct lower(f.repository) from FindingFeedback f"
              + " where f.installationId is null",
          "select distinct lower(l.repository) from ReviewLearning l where l.repositoryId is null"
        }) {
      names.addAll(em.createQuery(query, String.class).getResultList());
    }
    return names;
  }

  /**
   * Gives every unscoped row of {@code repository} (any casing) the matched scope, and returns how
   * many rows changed. A learning already names its installation, so it is filled only when that
   * matches. A legacy pause whose pull request is already paused under the scope is removed rather
   * than duplicated.
   */
  @Transactional
  public int apply(String repository, RepoScope scope) {
    var name = repository.strip().toLowerCase(Locale.ROOT);
    var installationId = scope.installationId();
    var repositoryId = scope.repositoryId();
    var changed =
        sessions.update(
            "installationId = ?1, repositoryId = ?2"
                + " where installationId is null and lower(repository) = ?3",
            installationId,
            repositoryId,
            name);
    changed +=
        feedback.update(
            "installationId = ?1, repositoryId = ?2"
                + " where installationId is null and lower(repository) = ?3",
            installationId,
            repositoryId,
            name);
    changed +=
        learnings.update(
            "repositoryId = ?2 where repositoryId is null and installationId = ?1"
                + " and lower(repository) = ?3",
            installationId,
            repositoryId,
            name);
    for (var pause : pauses.list("installationId is null and lower(repository) = ?1", name)) {
      var alreadyPaused =
          pauses.count(
                  "installationId = ?1 and repositoryId = ?2 and prNumber = ?3",
                  installationId,
                  repositoryId,
                  pause.prNumber)
              > 0;
      if (alreadyPaused) {
        pauses.delete(pause);
      } else {
        pause.installationId = installationId;
        pause.repositoryId = repositoryId;
        pauses.flush();
      }
      changed++;
    }
    return changed;
  }
}
