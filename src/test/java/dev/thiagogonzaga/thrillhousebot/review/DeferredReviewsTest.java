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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.PullRequestClosure;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import java.time.Duration;
import java.util.ArrayList;
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
  void aHandOutSaysWhetherItsHeadIsHandedOutForTheFirstTime() {
    deferred.defer(request(1, "sha-a"), 0);
    assertEquals(
        List.of(new DeferredReviews.HandOut(request(1, "sha-a"), true)),
        deferred.takeDueHandOuts());

    deferred.requeue(request(1, "sha-a"), 0);
    assertEquals(
        List.of(new DeferredReviews.HandOut(request(1, "sha-a"), false)),
        deferred.takeDueHandOuts());

    deferred.defer(request(1, "sha-b"), 0);
    assertEquals(
        List.of(new DeferredReviews.HandOut(request(1, "sha-b"), true)),
        deferred.takeDueHandOuts());
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
  void aRequestThatComesBackLeavesTheNewerHeadAnEventDeferredInPlace() {
    deferred.defer(request(1, "sha-a"), 0);
    assertEquals(List.of(request(1, "sha-a")), deferred.takeDue());
    // A push arrives while the handed-out request waits for its turn in the dispatcher.
    deferred.defer(request(1, "sha-b"), 60_000);

    // The older request is turned away again: nothing to record, and the newer head stays.
    assertTrue(deferred.requeue(request(1, "sha-a"), 10));
    assertTrue(deferred.requeueFor(request(1, "sha-a"), Duration.ofMillis(10)));

    now.set(10);
    assertEquals(List.of(), deferred.takeDue());
    now.set(60_000);
    assertEquals(List.of(request(1, "sha-b")), deferred.takeDue());
  }

  @Test
  void aRequestThatComesBackWaitsAgainUntilTheGiveUpTime() {
    deferred.defer(request(1, "sha-a"), 0);
    deferred.takeDue();

    assertTrue(deferred.requeueFor(request(1, "SHA-A"), Duration.ofSeconds(30)));
    // It is still the head an event deferred, so an older request does not displace it.
    assertTrue(deferred.requeue(request(1, "sha-old"), 0));
    assertEquals(List.of(), deferred.takeDue());
    now.set(30_000);
    assertEquals(List.of(request(1, "SHA-A")), deferred.takeDue());

    now.set(GIVE_UP_MS);
    assertFalse(deferred.requeue(request(1, "sha-a"), now.get()));
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aRequestThatComesBackReplacesTheOneThatCameBackBeforeIt() {
    // Nothing waits for the pull request: its event was dispatched, and a limit turned it away.
    assertTrue(deferred.requeue(request(1, "sha-a"), 10));
    // The pull request's worker runs its requests in order, so this one is the newer.
    assertTrue(deferred.requeue(request(1, "sha-b"), 10));
    assertTrue(deferred.requeue(request(1, "sha-b"), 10));
    assertTrue(deferred.requeue(request(1, "sha-c"), 10));

    now.set(10);
    assertEquals(List.of(request(1, "sha-c")), deferred.takeDue());
  }

  @Test
  void aRequestThatComesBackReplacesAnEntryWhenEitherHeadIsUnknown() {
    deferred.defer(request(1, null), 0);
    assertTrue(deferred.requeue(request(1, "sha-a"), 0));
    assertEquals(List.of(request(1, "sha-a")), deferred.takeDue());

    deferred.defer(request(1, "sha-b"), 0);
    assertTrue(deferred.requeue(request(1, null), 0));
    assertEquals(List.of(request(1, null)), deferred.takeDue());
  }

  @Test
  void pastTheLimitARequestThatComesBackForANewPullRequestIsRefused() {
    assertTrue(deferred.defer(request(1, "sha-a"), 10));
    assertTrue(deferred.defer(request(2, "sha-b"), 10));
    assertTrue(deferred.defer(request(3, "sha-c"), 10));

    assertFalse(deferred.requeue(request(4, "sha-d"), 10));
    assertEquals(3, deferred.pending.size());
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
  void aHandedOutRequestIsHandedBackAndItsEntryStaysHandedOut() {
    deferred.defer(request(1, "sha-a"), 0);
    deferred.takeDue();

    assertEquals(
        DeferredReviews.HandedOut.HANDED_BACK, deferred.handBack(request(1, "sha-a"), _ -> true));

    assertFalse(deferred.isEmpty());
    assertEquals(List.of(), deferred.takeDue());
  }

  @Test
  void aHandedOutRequestTheDispatcherRefusesIsDropped() {
    deferred.defer(request(1, "sha-a"), 0);
    deferred.takeDue();

    assertEquals(
        DeferredReviews.HandedOut.DROPPED, deferred.handBack(request(1, "sha-a"), _ -> false));

    assertTrue(deferred.isEmpty());
  }

  @Test
  void aRequestIsNotHandedBackOnceAnEventReplacedOrRemovedItsEntry() {
    var handedBack = new ArrayList<ReviewOrchestrator.ReviewRequest>();
    // Never kept.
    assertEquals(
        DeferredReviews.HandedOut.SUPERSEDED,
        deferred.handBack(request(1, "sha-a"), handedBack::add));
    // Kept and not handed out yet.
    deferred.defer(request(1, "sha-a"), 0);
    assertEquals(
        DeferredReviews.HandedOut.SUPERSEDED,
        deferred.handBack(request(1, "sha-a"), handedBack::add));
    // Handed out, then kept again by an event for the same head.
    deferred.takeDue();
    deferred.defer(request(1, "sha-a"), 0);
    assertEquals(
        DeferredReviews.HandedOut.SUPERSEDED,
        deferred.handBack(request(1, "sha-a"), handedBack::add));
    // Handed out, then replaced by a newer head.
    deferred.takeDue();
    deferred.defer(request(1, "sha-b"), 0);
    assertEquals(
        DeferredReviews.HandedOut.SUPERSEDED,
        deferred.handBack(request(1, "sha-a"), handedBack::add));
    // The newer head handed out in turn does not make the older request current again.
    deferred.takeDue();
    assertEquals(
        DeferredReviews.HandedOut.SUPERSEDED,
        deferred.handBack(request(1, "sha-a"), handedBack::add));

    assertEquals(List.of(), handedBack);
    assertEquals(
        DeferredReviews.HandedOut.HANDED_BACK,
        deferred.handBack(request(1, "sha-b"), handedBack::add));
    assertEquals(List.of(request(1, "sha-b")), handedBack);
  }

  @Test
  void aHandBackThatThrowsLeavesTheEntryHandedOut() {
    deferred.defer(request(1, "sha-a"), 0);
    deferred.takeDue();

    var handedOut = request(1, "sha-a");

    assertThrows(
        IllegalStateException.class,
        () ->
            deferred.handBack(
                handedOut,
                _ -> {
                  throw new IllegalStateException("no worker");
                }));

    assertEquals(
        DeferredReviews.HandedOut.WAITS_AGAIN,
        deferred.waitAgain(request(1, "sha-a"), Duration.ofSeconds(30)));
  }

  @Test
  void onlyTheEntryOfTheHandedOutRequestIsDropped() {
    assertEquals(DeferredReviews.HandedOut.SUPERSEDED, deferred.dropHandedOut(request(1, "sha-a")));
    deferred.defer(request(1, "sha-a"), 0);
    deferred.takeDue();
    // A push arrives before the request is dropped: its head stays.
    deferred.defer(request(1, "sha-b"), 0);
    assertEquals(DeferredReviews.HandedOut.SUPERSEDED, deferred.dropHandedOut(request(1, "sha-a")));
    assertEquals(List.of(request(1, "sha-b")), deferred.takeDue());

    assertEquals(DeferredReviews.HandedOut.DROPPED, deferred.dropHandedOut(request(1, "sha-b")));
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aHandedOutRequestWaitsAgainUntilTheGiveUpTime() {
    deferred.defer(request(1, "sha-a"), 0);
    deferred.takeDue();

    now.set(1_000);
    assertEquals(
        DeferredReviews.HandedOut.WAITS_AGAIN,
        deferred.waitAgain(request(1, "sha-a"), Duration.ofSeconds(30)));
    now.set(30_999);
    assertEquals(List.of(), deferred.takeDue());
    now.set(31_000);
    assertEquals(List.of(request(1, "sha-a")), deferred.takeDue());

    // The hour is counted from the first hand-out, not from the last.
    now.set(GIVE_UP_MS);
    assertEquals(
        DeferredReviews.HandedOut.DROPPED,
        deferred.waitAgain(request(1, "sha-a"), Duration.ofSeconds(30)));
    assertTrue(deferred.isEmpty());
  }

  @Test
  void aRequestDoesNotWaitAgainOnceAnEventReplacedOrRemovedItsEntry() {
    var delay = Duration.ofSeconds(30);
    deferred.defer(request(1, "sha-a"), 0);
    deferred.takeDue();
    // A newer push was dispatched, which drops the entry, and its worker put it back.
    deferred.forget(request(1, "sha-a"));
    assertEquals(
        DeferredReviews.HandedOut.SUPERSEDED, deferred.waitAgain(request(1, "sha-a"), delay));
    assertTrue(deferred.isEmpty());

    deferred.requeue(request(1, "sha-b"), 500);
    assertEquals(
        DeferredReviews.HandedOut.SUPERSEDED, deferred.waitAgain(request(1, "sha-a"), delay));
    now.set(500);
    assertEquals(List.of(request(1, "sha-b")), deferred.takeDue());
  }

  @Test
  void droppingAPullRequestSaysWhetherAReviewStillWaited() {
    assertFalse(deferred.forgetWaiting(SCOPE, 1));

    deferred.defer(request(1, "sha-a"), 1_000);
    assertTrue(deferred.forgetWaiting(SCOPE, 1));
    assertTrue(deferred.isEmpty());

    // One already handed out is removed too, and is not a review this stopped.
    deferred.defer(request(1, "sha-a"), 0);
    deferred.takeDue();
    assertFalse(deferred.forgetWaiting(SCOPE, 1));
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
