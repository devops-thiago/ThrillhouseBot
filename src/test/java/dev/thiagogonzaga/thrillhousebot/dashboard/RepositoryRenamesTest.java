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

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.thiagogonzaga.thrillhousebot.github.InstallationChange;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import dev.thiagogonzaga.thrillhousebot.review.FindingFeedback;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearning;
import dev.thiagogonzaga.thrillhousebot.webhook.PausedPr;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A renamed or transferred repository's stored rows show its new name (#1222). */
@QuarkusTest
class RepositoryRenamesTest extends ReviewSessionTestSupport {

  private static final RepoScope SCOPE = new RepoScope(905L, 70L);

  @Inject Event<InstallationChange> changes;
  @Inject EntityManager em;

  @AfterEach
  void cleanUp() throws Exception {
    tx.begin();
    PausedPr.deleteAll();
    FindingFeedback.deleteAll();
    ReviewLearning.deleteAll();
    tx.commit();
  }

  @Test
  void everyTableTakesTheNewNameByRepositoryId() throws Exception {
    tx.begin();
    ReviewSession.create(SCOPE, "owner/old", 1, "PR", "sha").persist();
    ReviewSession.create(new RepoScope(905L, 71L), "owner/other", 1, "PR", "sha").persist();
    em.createNativeQuery(
            "insert into pausedpr (id, prnumber, repository, installationid, repositoryid)"
                + " values (nextval('pausedpr_seq'), 1, 'owner/old', 905, 70)")
        .executeUpdate();
    em.createNativeQuery(
            "insert into finding_feedback (id, createdat, githubcommentid, prnumber, reactorlogin,"
                + " repository, signal, source, installationid, repositoryid) values"
                + " (nextval('finding_feedback_seq'), current_timestamp, 1, 1, 'u', 'owner/old',"
                + " 'useful', 'reaction', 905, 70)")
        .executeUpdate();
    em.createNativeQuery(
            "insert into review_learning (id, active, author, createdat, dedupkey, installationid,"
                + " repositoryid, kind, repository, sourceprnumber, sourceurl, text) values"
                + " (nextval('review_learning_seq'), true, 'm', current_timestamp, 'k', 905, 70,"
                + " 'convention', 'owner/old', 1, 'u', 't')")
        .executeUpdate();
    tx.commit();

    changes.fire(
        new InstallationChange(
            InstallationChange.Kind.REPOSITORY_RENAMED,
            905L,
            List.of(new InstallationChange.Repository(70L, " Owner/New "))));

    assertEquals(1, ReviewSession.count("repository = 'Owner/New' and repositoryId = 70"));
    assertEquals(1, ReviewSession.count("repository = 'owner/other'"));
    assertEquals(1, PausedPr.count("repository = 'Owner/New'"));
    assertEquals(1, FindingFeedback.count("repository = 'Owner/New'"));
    assertEquals(1, ReviewLearning.count("repository = 'owner/new'"));
  }

  @Test
  void otherChangesAnUnknownIdAndABlankNameChangeNothing() throws Exception {
    tx.begin();
    ReviewSession.create(SCOPE, "owner/old", 1, "PR", "sha").persist();
    tx.commit();

    changes.fire(new InstallationChange(InstallationChange.Kind.GONE, 905L, List.of()));
    changes.fire(
        new InstallationChange(
            InstallationChange.Kind.REPOSITORY_RENAMED,
            905L,
            List.of(
                new InstallationChange.Repository(99L, "owner/ghost"),
                new InstallationChange.Repository(70L, " "),
                new InstallationChange.Repository(70L, null))));

    assertEquals(1, ReviewSession.count("repository = 'owner/old'"));
  }
}
