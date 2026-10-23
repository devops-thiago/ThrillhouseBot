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

import dev.thiagogonzaga.thrillhousebot.github.InstallationChange;
import dev.thiagogonzaga.thrillhousebot.review.FindingFeedbackRepository;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningRepository;
import dev.thiagogonzaga.thrillhousebot.webhook.PausedPrRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the stored {@code owner/repo} of a renamed or transferred repository current (#1222). Rows
 * are read by installation and repository id, so their history already follows the repository; this
 * only updates the name they show. A repository id with no rows changes nothing.
 */
@ApplicationScoped
public class RepositoryRenames {

  private static final Logger log = LoggerFactory.getLogger(RepositoryRenames.class);
  private static final String RENAME = "repository = ?1 where repositoryId = ?2";

  private final ReviewSessionRepository sessions;
  private final PausedPrRepository pauses;
  private final FindingFeedbackRepository feedback;
  private final ReviewLearningRepository learnings;

  @Inject
  public RepositoryRenames(
      ReviewSessionRepository sessions,
      PausedPrRepository pauses,
      FindingFeedbackRepository feedback,
      ReviewLearningRepository learnings) {
    this.sessions = sessions;
    this.pauses = pauses;
    this.feedback = feedback;
    this.learnings = learnings;
  }

  /**
   * Runs on the webhook's thread with the other observers of the same change, so a failed update is
   * logged, not thrown: it must neither fail the delivery nor stop the dashboard dropping its
   * cached scopes. Each rename commits in its own transaction.
   */
  void onInstallationChange(@Observes InstallationChange change) {
    if (change.kind() != InstallationChange.Kind.REPOSITORY_RENAMED) {
      return;
    }
    for (var repository : change.repositories()) {
      if (repository.fullName() != null && !repository.fullName().isBlank()) {
        var fullName = repository.fullName().strip();
        int renamed;
        try {
          renamed = QuarkusTransaction.requiringNew().call(() -> rename(repository.id(), fullName));
        } catch (RuntimeException e) {
          log.warn(
              "Could not rename the stored rows of repository {} to {}; they keep the old name",
              repository.id(),
              fullName,
              e);
          continue;
        }
        log.info(
            "Renamed {} stored row(s) of repository {} to {}", renamed, repository.id(), fullName);
      }
    }
  }

  private int rename(long repositoryId, String fullName) {
    var changed = sessions.update(RENAME, fullName, repositoryId);
    changed += pauses.update(RENAME, fullName, repositoryId);
    changed += feedback.update(RENAME, fullName, repositoryId);
    // Learnings keep their names lower-cased; their dedup keys stay as they were written.
    changed += learnings.update(RENAME, fullName.toLowerCase(Locale.ROOT), repositoryId);
    return changed;
  }
}
