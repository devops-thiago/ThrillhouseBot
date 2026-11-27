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

import dev.thiagogonzaga.thrillhousebot.github.PullRequestClosure;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeferredReviewsTest {

  private static final RepoScope SCOPE =
      new RepoScope(TestScopes.INSTALLATION_ID, TestScopes.REPOSITORY_ID);
  private static final long GIVE_UP_MS = DeferredReviews.GIVE_UP_AFTER.toMillis();

  private final AtomicLong now = new AtomicLong(0);
  private final DeferredReviews deferred = new DeferredReviews(3, now::get);

  @Test
  void theInjectedStoreStartsEmptyAndKeepsARequest() {
    var store = new DeferredReviews();

    assertTrue(store.isEmpty());
    assertTrue(store.defer(request(1, "sha-a"), Long.MAX_VALUE));
    assertFalse(store.isEmpty());
    assertEquals(List.of(), store.takeDue());
  }

  @Test
  void aDeferredReviewIsHandedOutOnceItIsDueAndOnlyOnce() {
    assertTrue(deferred.defer(request(1, "sha-a"), 60_000));

    now.set(59_999);
    assertEquals(List.of(), deferred.takeDue());

    now.set(60_000);
    assertEquals(List.of(request(1, "sha-a")), deferred.takeDue());
    assertEquals(List.of(), deferred.takeDue());
  }

  @Test
  void aNewerHeadReplacesTheOneWaitingForItsPullRequest() {
    deferred.defer(request(1, "sha-a"), 60_000);
    deferred.defer(request(1, "sha-b"), 60_000);
    deferred.defer(request(2, "sha-c"), 60_000);

    now.set(60_000);

    var due = deferred.takeDue();
    assertEquals(2, due.size());
    assertTrue(due.contains(request(1, "sha-b")));
    assertTrue(due.contains(request(2, "sha-c")));
    assertEquals(2, deferred.pending.size());
  }

  @Test
  void aDelayIsCountedFromNow() {
    now.set(1_000);
    assertTrue(deferred.deferFor(request(1, "sha-a"), Duration.ofSeconds(30)));

    now.set(30_999);
    assertEquals(List.of(), deferred.takeDue());
    now.set(31_000);
    assertEquals(List.of(request(1, "sha-a")), deferred.takeDue());
  }

  @Test
  void pastTheLimitANewPullRequestIsRefusedAndAKnownOneStillReplaced() {
    assertTrue(deferred.defer(request(1, "sha-a"), 10));
    assertTrue(deferred.defer(request(2, "sha-b"), 10));
    assertTrue(deferred.defer(request(3, "sha-c"), 10));

    assertFalse(deferred.defer(request(4, "sha-d"), 10));
    assertTrue(deferred.defer(request(3, "sha-e"), 10));

    assertEquals(3, deferred.pending.size());
    now.set(10);
    assertTrue(deferred.takeDue().contains(request(3, "sha-e")));
  }

  @Test
  void aHeadThatKeepsComingBackIsRefusedAfterTheGiveUpTime() {
    deferred.defer(request(1, "sha-a"), 0);
    assertEquals(1, deferred.takeDue().size());

    // The same head is turned away again and again, as by a repository queue that stays full.
    now.set(GIVE_UP_MS - 1);
    assertTrue(deferred.defer(request(1, "SHA-A"), now.get()));
    assertEquals(1, deferred.takeDue().size());

    now.set(GIVE_UP_MS);
    assertFalse(deferred.defer(request(1, "sha-a"), now.get()));
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aNewHeadStartsItsOwnGiveUpTime() {
    deferred.defer(request(1, "sha-a"), 0);
    deferred.takeDue();

    now.set(GIVE_UP_MS);
    assertTrue(deferred.defer(request(1, "sha-b"), now.get()));
    assertEquals(List.of(request(1, "sha-b")), deferred.takeDue());
  }

  @Test
  void aRequestWithNoHeadNeverCountsAsTheSameHead() {
    deferred.defer(request(1, null), 0);
    deferred.takeDue();

    now.set(GIVE_UP_MS);
    assertTrue(deferred.defer(request(1, null), now.get()));
  }

  @Test
  void aHandedOutEntryIsForgottenOnceItCouldNoLongerComeBack() {
    deferred.defer(request(1, "sha-a"), 0);
    deferred.takeDue();

    now.set(GIVE_UP_MS - 1);
    deferred.takeDue();
    assertFalse(deferred.isEmpty());

    now.set(GIVE_UP_MS);
    assertEquals(List.of(), deferred.takeDue());
    assertTrue(deferred.isEmpty());
  }

  @Test
  void closingAPullRequestDropsItsDeferredReviewAndReopeningDoesNot() {
    deferred.defer(request(1, "sha-a"), 0);
    deferred.defer(request(2, "sha-b"), 0);

    deferred.onClosure(new PullRequestClosure(SCOPE, 1, true));
    deferred.onClosure(new PullRequestClosure(SCOPE, 2, false));

    assertEquals(List.of(request(2, "sha-b")), deferred.takeDue());
  }

  @Test
  void aPullRequestIsForgottenByScopeOrByRequestAndEverythingAtShutdown() {
    deferred.defer(request(1, "sha-a"), 0);
    deferred.defer(request(2, "sha-b"), 0);
    deferred.defer(request(3, "sha-c"), 0);

    deferred.forget(SCOPE, 1);
    deferred.forget(request(2, "another-head"));
    assertEquals(1, deferred.pending.size());

    deferred.clear();
    assertTrue(deferred.isEmpty());
  }

  private static ReviewOrchestrator.ReviewRequest request(int prNumber, String sha) {
    return new ReviewOrchestrator.ReviewRequest(
        "owner",
        "repo",
        prNumber,
        sha,
        "title",
        "",
        "base",
        "main",
        TestScopes.INSTALLATION_ID,
        TestScopes.REPOSITORY_ID,
        false);
  }
}
