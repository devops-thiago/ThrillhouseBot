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
package dev.thiagogonzaga.thrillhousebot.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CiHoldRegistry}, the per-replica memory of verdicts held on CI (#825). */
class CiHoldRegistryTest {

  private static final CiHoldRegistry.HeldVerdict VERDICT =
      new CiHoldRegistry.HeldVerdict("abc123", "main", 7L, "https://bot.example/session/x");

  private final CiHoldRegistry registry = new CiHoldRegistry();

  @Test
  void trackedHeadMapsToItsPullRequestWithoutAVerdict() {
    registry.track("owner", "repo", 42, "abc123");

    assertEquals(List.of(42), registry.pullRequestsAt("owner", "repo", "abc123"));
    assertTrue(registry.heldAt("owner", "repo", 42, "abc123").isEmpty());
  }

  @Test
  void heldVerdictIsFoundByPullRequestAndHead() {
    registry.track("owner", "repo", 42, "abc123");
    registry.hold("owner", "repo", 42, VERDICT);

    var held = registry.heldAt("owner", "repo", 42, "abc123");
    assertTrue(held.isPresent());
    assertEquals(7L, held.get().checkRunId());
    assertEquals("main", held.get().baseRef());
    assertEquals(List.of(42), registry.pullRequestsAt("owner", "repo", "ABC123"));
  }

  @Test
  void heldVerdictIsNotFoundForAnotherHead() {
    registry.hold("owner", "repo", 42, VERDICT);

    assertTrue(registry.heldAt("owner", "repo", 42, "def456").isEmpty());
    assertTrue(registry.pullRequestsAt("owner", "repo", "def456").isEmpty());
    assertTrue(registry.pullRequestsAt("owner", "other", "abc123").isEmpty());
    assertTrue(registry.pullRequestsAt("other", "repo", "abc123").isEmpty());
  }

  @Test
  void trackingANewHeadReplacesTheHeldVerdict() {
    registry.hold("owner", "repo", 42, VERDICT);

    registry.track("owner", "repo", 42, "def456");

    assertTrue(registry.heldAt("owner", "repo", 42, "abc123").isEmpty());
    assertTrue(registry.pullRequestsAt("owner", "repo", "abc123").isEmpty());
    assertEquals(List.of(42), registry.pullRequestsAt("owner", "repo", "def456"));
  }

  @Test
  void releaseForgetsThePullRequest() {
    registry.hold("owner", "repo", 42, VERDICT);

    registry.release("owner", "repo", 42);

    assertTrue(registry.heldAt("owner", "repo", 42, "abc123").isEmpty());
    assertEquals(0, registry.size());
  }

  @Test
  void twoPullRequestsAtTheSameHeadAreBothReported() {
    registry.hold("owner", "repo", 42, VERDICT);
    registry.hold("owner", "repo", 43, VERDICT);

    assertEquals(List.of(42, 43), registry.pullRequestsAt("owner", "repo", "abc123"));
  }

  @Test
  void oldestPullRequestIsEvictedPastTheCap() {
    for (int pr = 1; pr <= CiHoldRegistry.MAX_PULL_REQUESTS + 1; pr++) {
      registry.hold("owner", "repo", pr, VERDICT);
    }

    assertEquals(CiHoldRegistry.MAX_PULL_REQUESTS, registry.size());
    assertTrue(registry.heldAt("owner", "repo", 1, "abc123").isEmpty());
    assertFalse(registry.heldAt("owner", "repo", 2, "abc123").isEmpty());
  }

  @Test
  void reholdingAPullRequestMakesItNewestForEviction() {
    for (int pr = 1; pr <= CiHoldRegistry.MAX_PULL_REQUESTS; pr++) {
      registry.hold("owner", "repo", pr, VERDICT);
    }
    registry.hold("owner", "repo", 1, VERDICT);

    registry.hold("owner", "repo", CiHoldRegistry.MAX_PULL_REQUESTS + 1, VERDICT);

    assertFalse(registry.heldAt("owner", "repo", 1, "abc123").isEmpty());
    assertTrue(registry.heldAt("owner", "repo", 2, "abc123").isEmpty());
  }
}
