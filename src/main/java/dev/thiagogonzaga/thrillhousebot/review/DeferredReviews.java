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

import dev.thiagogonzaga.thrillhousebot.github.PullRequestClosure;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The automatic reviews a limit held back and that still have to run: one per pull request, for the
 * latest head a limit held. A push inside the rate window ({@link AutoReviewRateLimiter}) or past a
 * full repository queue ({@link AutoReviewSlots}) is kept here instead of dropped, within the
 * bounds below, and {@link DeferredReviewRelease} hands it back to {@link ReviewDispatcher} once it
 * is due. Without this a head pushed inside the window was never reviewed, and an approval of the
 * previous head stayed on code nobody read.
 *
 * <p>Bounds. A pull request has one entry, and a newer request replaces it, so a burst of pushes
 * costs one review. A request that comes back from the dispatcher ({@link #requeue}) is not a newer
 * one: it leaves another head an event put here since in place, and is dropped in its favour. At
 * most {@link #MAX_PENDING} pull requests have an entry; past that a request for a pull request
 * that has none is refused and the caller records the skip, as it did before. An entry leaves when
 * the pull request is closed or merged, when a newer event for the pull request is dispatched,
 * filtered out or skipped because its head is already reviewed, when {@link DeferredReviewRelease}
 * finds the pull request paused, finds it a draft that is skipped or has its hand-back refused by
 * the executor, and at shutdown. Handing an entry out does not remove it, and neither does its
 * review running: unless one of those removes it or a request for another head replaces it, it
 * stays, counted towards the limit, until {@link #GIVE_UP_AFTER} has passed since it was first
 * handed out. A request for the same head that comes back after that long (a repository queue that
 * stays full) is refused and its entry removed, instead of retried without end.
 *
 * <p>A request handed out is still the pull request's only while its entry is the one {@link
 * #takeDue} left. An event that arrives before the request is handed back replaces or removes that
 * entry, so {@link DeferredReviewRelease} goes through {@link #handBack}, {@link #dropHandedOut}
 * and {@link #waitAgain}, which act on that entry alone and report {@link HandedOut#SUPERSEDED}
 * otherwise: the request then gives way and nothing is put back, removed or counted for it.
 *
 * <p>State is in memory, like the limits it serves: a restart forgets the reviews still waiting.
 */
@ApplicationScoped
public class DeferredReviews {

  /** Most pull requests with a review waiting, or handed out less than an hour ago. */
  static final int MAX_PENDING = 10_000;

  /** How long after its first hand-out the same head may be deferred again. */
  static final Duration GIVE_UP_AFTER = Duration.ofHours(1);

  private static final long NEVER = -1;

  /** A pull request as the signed webhook payload names it. */
  record Key(long installationId, long repositoryId, int prNumber) {}

  /**
   * One pull request's entry.
   *
   * @param request the review to run, for the latest head a limit held back
   * @param dueAt epoch millis from which the review may be handed out
   * @param released whether it was handed out and has not come back
   * @param firstReleasedAt epoch millis this head was first handed out, or {@link #NEVER}
   * @param fromEvent whether an event put this head here, rather than a request coming back
   */
  record Pending(
      ReviewOrchestrator.ReviewRequest request,
      long dueAt,
      boolean released,
      long firstReleasedAt,
      boolean fromEvent) {}

  /**
   * A request handed out.
   *
   * @param request the review to hand back to the dispatcher
   * @param first whether this head is handed out for the first time; {@code false} for one a limit
   *     turned away again since, which comes back every time it is due
   */
  public record HandOut(ReviewOrchestrator.ReviewRequest request, boolean first) {}

  /** What became of a request {@link #takeDue} handed out, when it came back to its entry. */
  public enum HandedOut {
    /** The dispatcher took it. Its entry stays, marked as handed out. */
    HANDED_BACK,
    /** It waits to be handed out again. */
    WAITS_AGAIN,
    /** Its entry was removed and no review waits for the pull request: the caller counts a skip. */
    DROPPED,
    /**
     * The entry is no longer this request's: an event replaced it with a newer request, or removed
     * it. Nothing was changed, and the request is not to be reviewed or counted.
     */
    SUPERSEDED
  }

  /** What became of a request offered to the store. */
  private enum Outcome {
    KEPT,
    REFUSED,
    NEWER_HEAD_WAITS
  }

  final ConcurrentHashMap<Key, Pending> pending = new ConcurrentHashMap<>();

  private final int maxPending;
  private final LongSupplier clock;

  @Inject
  public DeferredReviews() {
    this(MAX_PENDING, System::currentTimeMillis);
  }

  /** Visible for tests: a small limit and a controllable clock. */
  DeferredReviews(int maxPending, LongSupplier clock) {
    this.maxPending = maxPending;
    this.clock = clock;
  }

  /**
   * Keeps {@code request} to run from {@code dueAt}, in place of any request waiting for its pull
   * request. For the request of an event that just arrived, which is the newest the pull request
   * has; one that comes back from the dispatcher goes through {@link #requeue}.
   *
   * @return {@code false} when it was not kept, so the caller records the skip: as many pull
   *     requests have an entry as may and this one has none, or this head has been coming back for
   *     {@link #GIVE_UP_AFTER}
   */
  public boolean defer(ReviewOrchestrator.ReviewRequest request, long dueAt) {
    return keep(request, dueAt, true) == Outcome.KEPT;
  }

  /** Like {@link #defer(ReviewOrchestrator.ReviewRequest, long)}, due {@code delay} from now. */
  public boolean deferFor(ReviewOrchestrator.ReviewRequest request, Duration delay) {
    return defer(request, clock.getAsLong() + delay.toMillis());
  }

  /**
   * Keeps {@code request} to run from {@code dueAt} when it comes back from the dispatcher: a limit
   * turned it away on its worker. Such a request can be older than the entry: a push that arrived
   * while it waited for its turn put a newer head here. That head stays, and this request is
   * dropped in its favour. Only for the pull request's worker; a request {@link #takeDue} handed
   * out and that could not be handed back goes through {@link #waitAgain}.
   *
   * @return {@code false} when the pull request is left with no review waiting, so the caller
   *     records the skip, as for {@link #defer}; {@code true} when this request was kept or a newer
   *     head waits in its place
   */
  public boolean requeue(ReviewOrchestrator.ReviewRequest request, long dueAt) {
    return keep(request, dueAt, false) != Outcome.REFUSED;
  }

  /** Like {@link #requeue(ReviewOrchestrator.ReviewRequest, long)}, due {@code delay} from now. */
  public boolean requeueFor(ReviewOrchestrator.ReviewRequest request, Duration delay) {
    return requeue(request, clock.getAsLong() + delay.toMillis());
  }

  private Outcome keep(ReviewOrchestrator.ReviewRequest request, long dueAt, boolean fromEvent) {
    long now = clock.getAsLong();
    var outcome = new Outcome[] {Outcome.REFUSED};
    pending.compute(
        key(request),
        (_, previous) -> {
          if (!fromEvent && newerHeadWaits(previous, request)) {
            outcome[0] = Outcome.NEWER_HEAD_WAITS;
            return previous;
          }
          boolean sameHead = sameHead(previous, request);
          long retryingSince = sameHead ? previous.firstReleasedAt() : NEVER;
          if (previous == null && pending.size() >= maxPending) {
            return null;
          }
          if (retryingSince != NEVER && now - retryingSince >= GIVE_UP_AFTER.toMillis()) {
            return null;
          }
          outcome[0] = Outcome.KEPT;
          return new Pending(
              request,
              dueAt,
              false,
              retryingSince,
              fromEvent || (sameHead && previous.fromEvent()));
        });
    logOutcome(outcome[0], request, dueAt - now);
    return outcome[0];
  }

  private static void logOutcome(
      Outcome outcome, ReviewOrchestrator.ReviewRequest request, long delayMs) {
    if (outcome == Outcome.KEPT) {
      Log.infof(
          "Automatic review of %s/%s #%d (sha: %s) deferred for %dms: a limit on automatic reviews"
              + " holds it back, and it runs once the limit allows",
          request.owner(),
          request.repo(),
          request.prNumber(),
          request.commitSha(),
          Math.max(0, delayMs));
    } else if (outcome == Outcome.NEWER_HEAD_WAITS) {
      Log.infof(
          "Automatic review of %s/%s #%d (sha: %s) not deferred again: a newer head of the pull"
              + " request waits for the limit, and that one is reviewed",
          request.owner(), request.repo(), request.prNumber(), request.commitSha());
    }
  }

  /**
   * Whether the entry holds another head that an event put there. A head only a returning request
   * put there is replaced: the pull request's worker runs its requests in order, so the one that
   * returns later is the newer of the two.
   */
  private static boolean newerHeadWaits(
      Pending previous, ReviewOrchestrator.ReviewRequest request) {
    return previous != null
        && previous.fromEvent()
        && previous.request().commitSha() != null
        && request.commitSha() != null
        && !previous.request().commitSha().equalsIgnoreCase(request.commitSha());
  }

  private static boolean sameHead(Pending previous, ReviewOrchestrator.ReviewRequest request) {
    return previous != null
        && previous.request().commitSha() != null
        && previous.request().commitSha().equalsIgnoreCase(request.commitSha());
  }

  /**
   * Hands out the requests that are due, each once: an entry stays, marked as handed out, until a
   * request for its pull request is kept in its place, its pull request is forgotten, or {@link
   * #GIVE_UP_AFTER} has passed since its first hand-out.
   */
  public List<ReviewOrchestrator.ReviewRequest> takeDue() {
    return takeDueHandOuts().stream().map(HandOut::request).toList();
  }

  /** Like {@link #takeDue}, saying for each request whether its head is handed out again. */
  public List<HandOut> takeDueHandOuts() {
    long now = clock.getAsLong();
    var due = new ArrayList<HandOut>();
    for (var key : pending.keySet()) {
      pending.computeIfPresent(key, (_, entry) -> afterTick(entry, now, due));
    }
    return due;
  }

  /** The entry a tick leaves in place of {@code entry}; {@code null} removes it. */
  private static Pending afterTick(Pending entry, long now, List<HandOut> due) {
    if (entry.released()) {
      return now - entry.firstReleasedAt() >= GIVE_UP_AFTER.toMillis() ? null : entry;
    }
    if (entry.dueAt() > now) {
      return entry;
    }
    boolean first = entry.firstReleasedAt() == NEVER;
    due.add(new HandOut(entry.request(), first));
    return new Pending(
        entry.request(),
        entry.dueAt(),
        true,
        first ? now : entry.firstReleasedAt(),
        entry.fromEvent());
  }

  /**
   * Hands {@code request} to {@code dispatch} if its entry is still the one {@link #takeDue} left,
   * and removes the entry when {@code dispatch} refuses it. The check and the hand-back are one
   * step for the pull request: an event dispatched before it has removed the entry, so the request
   * is not handed back over the event's newer one, and an event dispatched after it queues behind
   * the request and replaces it.
   *
   * @param dispatch queues the request, and answers {@code false} when it could not; it must not
   *     come back to this store
   * @return {@link HandedOut#HANDED_BACK}, {@link HandedOut#DROPPED} when {@code dispatch} refused
   *     the request, or {@link HandedOut#SUPERSEDED}
   */
  public HandedOut handBack(
      ReviewOrchestrator.ReviewRequest request,
      Predicate<ReviewOrchestrator.ReviewRequest> dispatch) {
    var fate = new HandedOut[] {HandedOut.SUPERSEDED};
    pending.computeIfPresent(
        key(request),
        (_, entry) -> {
          if (!handedOut(entry, request)) {
            return entry;
          }
          boolean taken = dispatch.test(request);
          fate[0] = taken ? HandedOut.HANDED_BACK : HandedOut.DROPPED;
          return taken ? entry : null;
        });
    return fate[0];
  }

  /**
   * Removes the entry of {@code request} if it is still the one {@link #takeDue} left.
   *
   * @return {@link HandedOut#DROPPED} or {@link HandedOut#SUPERSEDED}
   */
  public HandedOut dropHandedOut(ReviewOrchestrator.ReviewRequest request) {
    var fate = new HandedOut[] {HandedOut.SUPERSEDED};
    pending.computeIfPresent(
        key(request),
        (_, entry) -> {
          if (!handedOut(entry, request)) {
            return entry;
          }
          fate[0] = HandedOut.DROPPED;
          return null;
        });
    return fate[0];
  }

  /**
   * Makes {@code request} wait {@code delay} to be handed out again, if its entry is still the one
   * {@link #takeDue} left, and removes the entry instead once {@link #GIVE_UP_AFTER} has passed
   * since its first hand-out.
   *
   * @return {@link HandedOut#WAITS_AGAIN}, {@link HandedOut#DROPPED} when it was given up, or
   *     {@link HandedOut#SUPERSEDED}
   */
  public HandedOut waitAgain(ReviewOrchestrator.ReviewRequest request, Duration delay) {
    long now = clock.getAsLong();
    var fate = new HandedOut[] {HandedOut.SUPERSEDED};
    pending.computeIfPresent(
        key(request),
        (_, entry) -> {
          if (!handedOut(entry, request)) {
            return entry;
          }
          if (now - entry.firstReleasedAt() >= GIVE_UP_AFTER.toMillis()) {
            fate[0] = HandedOut.DROPPED;
            return null;
          }
          fate[0] = HandedOut.WAITS_AGAIN;
          return new Pending(
              request, now + delay.toMillis(), false, entry.firstReleasedAt(), entry.fromEvent());
        });
    return fate[0];
  }

  /**
   * Whether {@code entry} is the one {@link #takeDue} left when it handed {@code request} out. A
   * request kept since, for the same head or another, is not handed out yet.
   */
  private static boolean handedOut(Pending entry, ReviewOrchestrator.ReviewRequest request) {
    return entry.released() && entry.request().equals(request);
  }

  /** Drops whatever is kept for the pull request: a newer event decides about it now. */
  public void forget(RepoScope scope, int prNumber) {
    pending.remove(new Key(scope.installationId(), scope.repositoryId(), prNumber));
  }

  /**
   * Drops whatever is kept for the pull request, like {@link #forget(RepoScope, int)}.
   *
   * @return whether a review that still waited to be handed out was dropped, so the caller counts
   *     the skip; {@code false} when nothing was kept, and for an entry already handed out, whose
   *     review this does not stop
   */
  public boolean forgetWaiting(RepoScope scope, int prNumber) {
    var removed = pending.remove(new Key(scope.installationId(), scope.repositoryId(), prNumber));
    return removed != null && !removed.released();
  }

  /** Drops whatever is kept for the request's pull request. */
  public void forget(ReviewOrchestrator.ReviewRequest request) {
    pending.remove(key(request));
  }

  /** A closed or merged pull request needs no review. In memory only: it runs on the ack path. */
  void onClosure(@Observes PullRequestClosure closure) {
    if (closure.closed()) {
      forget(closure.scope(), closure.prNumber());
    }
  }

  /** Whether nothing is kept, waiting or handed out. */
  public boolean isEmpty() {
    return pending.isEmpty();
  }

  /** Drops every entry. Called at shutdown, when nothing more is handed out. */
  public void clear() {
    pending.clear();
  }

  private static Key key(ReviewOrchestrator.ReviewRequest request) {
    return new Key(request.installationId(), request.repositoryId(), request.prNumber());
  }
}
