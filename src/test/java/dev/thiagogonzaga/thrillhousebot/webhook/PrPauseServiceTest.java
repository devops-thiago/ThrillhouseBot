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

import static org.junit.jupiter.api.Assertions.*;

import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PrPauseServiceTest {

  private static final RepoScope SCOPE = new RepoScope(1L, 70L);

  @Inject PrPauseService service;
  @Inject UserTransaction tx;

  @AfterEach
  void cleanup() throws Exception {
    tx.begin();
    PausedPr.deleteAll();
    tx.commit();
  }

  @Test
  void pauseThenIsPausedReturnsTrue() {
    assertFalse(service.isPaused(SCOPE, "owner", "repo", 7));
    service.pause(SCOPE, "owner", "repo", 7);
    assertTrue(service.isPaused(SCOPE, "owner", "repo", 7));
  }

  @Test
  void resumeClearsPause() {
    service.pause(SCOPE, "owner", "repo", 7);
    assertTrue(service.resume(SCOPE, "owner", "repo", 7));
    assertFalse(service.isPaused(SCOPE, "owner", "repo", 7));
  }

  @Test
  void resumeReturnsFalseWhenNotPaused() {
    assertFalse(service.resume(SCOPE, "owner", "repo", 7));
  }

  @Test
  void pauseIsIdempotentAndStoresTheScope() {
    service.pause(SCOPE, "owner", "repo", 7);
    service.pause(SCOPE, "owner", "repo", 7);

    assertTrue(service.isPaused(SCOPE, "owner", "repo", 7));
    assertEquals(
        1,
        PausedPr.count(
            "installationId = ?1 and repositoryId = ?2 and repository = ?3 and prNumber = ?4",
            1L,
            70L,
            "owner/repo",
            7));
  }

  @Test
  void pauseIsScopedPerPrRepositoryAndInstallation() {
    service.pause(SCOPE, "owner", "repo", 7);

    assertFalse(service.isPaused(SCOPE, "owner", "repo", 8));
    assertFalse(service.isPaused(new RepoScope(1L, 71L), "owner", "repo", 7), "re-created");
    assertFalse(service.isPaused(new RepoScope(2L, 70L), "owner", "repo", 7), "installation");
  }

  @Test
  void aRenamedRepositoryStaysPaused() {
    service.pause(SCOPE, "owner", "old-name", 7);

    assertTrue(service.isPaused(SCOPE, "owner", "new-name", 7));
    assertTrue(service.resume(SCOPE, "owner", "new-name", 7));
  }

  @Test
  void anUnmatchedLegacyPauseIsHonoredAndLiftedByItsExactName() throws Exception {
    tx.begin();
    var legacy = new PausedPr();
    legacy.repository = "owner/repo";
    legacy.prNumber = 7;
    legacy.persist();
    tx.commit();

    assertTrue(service.isPaused(SCOPE, "owner", "repo", 7));
    assertFalse(service.isPaused(SCOPE, "Owner", "Repo", 7), "matched by its exact name only");
    assertFalse(service.isPaused(SCOPE, "owner", "repo", 8));
    assertTrue(service.resume(SCOPE, "owner", "repo", 7));
    assertFalse(service.isPaused(SCOPE, "owner", "repo", 7));
    assertEquals(0, PausedPr.count());
  }
}
