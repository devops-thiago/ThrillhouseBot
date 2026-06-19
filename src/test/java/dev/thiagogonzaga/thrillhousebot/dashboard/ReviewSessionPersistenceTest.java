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

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ReviewSessionPersistenceTest extends ReviewSessionTestSupport {

  @Inject ReviewSessionPersistence persistence;

  @Test
  void shouldCreateSessionInTransaction() throws Exception {
    ReviewSession session = ReviewSession.create("owner/repo", 7, "Title", "sha1");
    session.setStatus(ReviewSession.STATUS_IN_PROGRESS);

    tx.begin();
    persistence.create(session);
    tx.commit();

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertNotNull(loaded);
    assertEquals("owner/repo", loaded.getRepository());
    assertEquals(7, loaded.getPrNumber());
    assertEquals(ReviewSession.STATUS_IN_PROGRESS, loaded.getStatus());
    // create() must assign the unguessable public id used in GitHub-facing links
    assertNotNull(loaded.getPublicId());
    assertEquals(36, loaded.getPublicId().length());
  }

  @Test
  void shouldNoOpUpdateWhenSessionDoesNotExist() {
    assertDoesNotThrow(
        () ->
            persistence.update(
                999_999L, managed -> managed.setStatus(ReviewSession.STATUS_FAILED)));

    // No session may be created as a side effect of updating a missing ID
    assertEquals(0, ReviewSession.count());
  }

  @Test
  void shouldUpdateDetachedSessionById() throws Exception {
    var session = persistSession();
    session.setStatus(ReviewSession.STATUS_COMPLETED);
    session.setCriticalFindings(2);

    persistence.update(
        session.id,
        managed -> {
          managed.setStatus(session.getStatus());
          managed.setCriticalFindings(session.getCriticalFindings());
        });

    ReviewSession loaded = ReviewSession.findById(session.id);
    assertEquals(ReviewSession.STATUS_COMPLETED, loaded.getStatus());
    assertEquals(2, loaded.getCriticalFindings());
  }

  @Test
  void shouldFindLatestPreviousAiResponseForSamePr() throws Exception {
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_COMPLETED, "{\"v\":1}");
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_COMPLETED, "{\"v\":2}");
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_FAILED, "{\"v\":3}");
    persistSessionWith("owner/repo", 4, ReviewSession.STATUS_COMPLETED, "{\"v\":4}");
    var current = persistSessionWith("owner/repo", 3, ReviewSession.STATUS_IN_PROGRESS, null);

    var json = persistence.findPreviousAiResponseJson("owner/repo", 3, current.id);

    assertEquals(Optional.of("{\"v\":2}"), json);
  }

  @Test
  void shouldNotReturnAiResponseOfCurrentSession() throws Exception {
    var current = persistSessionWith("owner/repo", 3, ReviewSession.STATUS_COMPLETED, "{\"v\":1}");

    assertTrue(persistence.findPreviousAiResponseJson("owner/repo", 3, current.id).isEmpty());
  }

  @Test
  void shouldReturnEmptyWhenPreviousSessionsHaveNoAiResponse() throws Exception {
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_COMPLETED, null);
    var current = persistSessionWith("owner/repo", 3, ReviewSession.STATUS_IN_PROGRESS, null);

    assertTrue(persistence.findPreviousAiResponseJson("owner/repo", 3, current.id).isEmpty());
  }

  @Test
  void shouldReturnAllPriorAiResponsesNewestFirst() throws Exception {
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_COMPLETED, "{\"v\":1}");
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_COMPLETED, "{\"v\":2}");
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_FAILED, "{\"v\":3}");
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_COMPLETED, null);
    persistSessionWith("owner/repo", 4, ReviewSession.STATUS_COMPLETED, "{\"v\":4}");
    var current = persistSessionWith("owner/repo", 3, ReviewSession.STATUS_IN_PROGRESS, null);

    var jsons = persistence.findAllPriorAiResponseJsons("owner/repo", 3, current.id);

    assertEquals(java.util.List.of("{\"v\":2}", "{\"v\":1}"), jsons);
  }

  @Test
  void shouldReturnEmptyListWhenNoPriorSessions() throws Exception {
    var current = persistSessionWith("owner/repo", 3, ReviewSession.STATUS_COMPLETED, "{\"v\":1}");

    assertTrue(persistence.findAllPriorAiResponseJsons("owner/repo", 3, current.id).isEmpty());
  }

  @Test
  void shouldReportCompletedReviewExists() throws Exception {
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_COMPLETED, "{\"v\":1}");

    assertTrue(persistence.hasCompletedReview("owner/repo", 3));
  }

  @Test
  void shouldNotReportCompletedReviewForOtherStatusesOrPrs() throws Exception {
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_IN_PROGRESS, null);
    persistSessionWith("owner/repo", 3, ReviewSession.STATUS_FAILED, null);
    persistSessionWith("owner/repo", 4, ReviewSession.STATUS_COMPLETED, "{\"v\":1}");

    // No completed review for PR #3, and the completed one on PR #4 must not leak across.
    assertFalse(persistence.hasCompletedReview("owner/repo", 3));
    assertFalse(persistence.hasCompletedReview("other/repo", 4));
  }

  private ReviewSession persistSession() throws Exception {
    tx.begin();
    ReviewSession session = ReviewSession.create("owner/repo", 3, "PR", "abc");
    session.persist();
    session.flush();
    tx.commit();
    return session;
  }

  private ReviewSession persistSessionWith(
      String repository, int prNumber, String status, String aiResponseJson) throws Exception {
    tx.begin();
    ReviewSession session = ReviewSession.create(repository, prNumber, "PR", "abc");
    session.setStatus(status);
    session.setAiResponseJson(aiResponseJson);
    session.persist();
    session.flush();
    tx.commit();
    return session;
  }
}
