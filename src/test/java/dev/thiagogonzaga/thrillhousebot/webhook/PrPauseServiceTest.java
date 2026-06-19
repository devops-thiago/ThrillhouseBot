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

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PrPauseServiceTest {

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
    assertFalse(service.isPaused("owner", "repo", 7));
    service.pause("owner", "repo", 7);
    assertTrue(service.isPaused("owner", "repo", 7));
  }

  @Test
  void resumeClearsPause() {
    service.pause("owner", "repo", 7);
    assertTrue(service.resume("owner", "repo", 7));
    assertFalse(service.isPaused("owner", "repo", 7));
  }

  @Test
  void resumeReturnsFalseWhenNotPaused() {
    assertFalse(service.resume("owner", "repo", 7));
  }

  @Test
  void pauseIsIdempotent() {
    service.pause("owner", "repo", 7);
    service.pause("owner", "repo", 7);

    assertTrue(service.isPaused("owner", "repo", 7));
    assertEquals(1, PausedPr.count("repository = ?1 and prNumber = ?2", "owner/repo", 7));
  }

  @Test
  void pauseIsScopedPerPr() {
    service.pause("owner", "repo", 7);

    assertFalse(service.isPaused("owner", "repo", 8));
    assertFalse(service.isPaused("other", "repo", 7));
  }
}
