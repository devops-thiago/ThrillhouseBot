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
package dev.thiagogonzaga.thrillhousebot.github;

import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes a post that GitHub threw away visible on the pull request, instead of only in the bot's
 * log.
 *
 * <p>#578: {@link GitHubWriteRetry} bounds its budget, so a persistently throttled reply is still
 * dropped once the attempts are spent. Today that is logged and nothing else — from the PR the
 * command simply never answered, which is indistinguishable from the bot ignoring the user, and the
 * user has no way to know the right move is to run it again.
 *
 * <h2>Why the notice rides along instead of being posted</h2>
 *
 * The obvious remedy — reply "this was throttled, please re-run" — is itself a {@code
 * createComment}: the exact call that is being throttled, sent at the exact moment GitHub is
 * refusing it. So the notice is not posted on its own. It is held, and the <em>next</em> content
 * the bot successfully lands on that pull request carries it. That costs no additional
 * content-creating request (which is the whole point of #579), cannot be throttled separately from
 * the post it travels with, and appears on the PR the loss happened on rather than in a log the
 * user cannot read.
 *
 * <p>What it cannot do is promise delivery on a PR the bot never writes to again; that case is no
 * worse than today's log-only behaviour. A notice is also forgotten after {@link #DEFAULT_TTL},
 * because "a reply was dropped" glued onto a comment days later is noise rather than a signal.
 *
 * <p>The wording deliberately does not name the command. This layer sees a comment on a pull
 * request, not the {@code /describe} or {@code /improve} that produced it, and an honest "if you
 * were waiting on an answer, re-run it" is more useful than a guess.
 *
 * <h2>What counts as one loss</h2>
 *
 * A loss is a piece of content the pull request never received, not an HTTP call GitHub refused.
 * The two stopped being the same thing in #721, which gave a finding a second and third route to
 * the pull request: a refused line-anchored comment is now followed by a thread on the file, and
 * the finding is only lost when every route fails. Counting refusals would tell the maintainer
 * their content was thrown away while it sits on the PR above the notice — and count it twice over
 * when the finding carries a suggestion, because that route is tried with and without it (#729).
 * {@link #asOneDelivery} is how a caller says "these calls are one piece of content": inside it
 * nothing is remembered until every route has had its turn, and then only if none of them landed.
 */
public final class GitHubLostWrites {

  private static final Logger log = LoggerFactory.getLogger(GitHubLostWrites.class);

  /**
   * How many pull requests may hold an outstanding notice at once, so the map cannot grow without
   * end. An entry is dropped as soon as its notice has been delivered, so a slot is only held while
   * a pull request is genuinely still owed one.
   */
  static final int DEFAULT_MAX_TARGETS = 200;

  /** How long a pending notice stays worth announcing. */
  static final Duration DEFAULT_TTL = Duration.ofHours(6);

  /** The pull request a content-creating call was aimed at. */
  public record Target(String owner, String repo, int number) {
    @Override
    public String toString() {
      return owner + "/" + repo + " #" + number;
    }
  }

  /**
   * What this pull request has lost, as two running totals and the time of the last loss, under an
   * id identifying this run of losses.
   *
   * <p>The totals are watermarks rather than a single "outstanding" count on purpose. Two posts on
   * the same pull request can be in flight at once, each having read the same pending total, and a
   * fresh loss can land between their reads and their completions. Subtracting each carrier's
   * snapshot from a shared counter double-counts that overlap and can retire a loss neither post
   * actually announced — the user then never hears about it, which is the failure #578 exists to
   * remove. Advancing {@code announced} monotonically towards {@code lost} instead can only ever
   * repeat a notice, never drop one, and repeating is the harmless direction.
   *
   * <p>{@code id} exists because a fully announced entry is dropped rather than kept at zero. Both
   * totals restart from scratch when a later loss recreates the entry, so a carrier still in flight
   * from the previous run holds a snapshot whose {@code lost} may equal the new run's — and would
   * retire it. Comparing the id makes that stale carrier a no-op, which is what lets the entry be
   * dropped at all.
   */
  private record Loss(long id, long lost, long announced, Instant at) {
    int pending() {
      return (int) (lost - announced);
    }
  }

  /**
   * Stands in for a pull request with nothing pending, so no code path handles a null loss. Its id
   * matches no real entry — {@link #ids} only ever hands out positive ones.
   */
  private static final Loss NOTHING = new Loss(0, 0, 0, Instant.EPOCH);

  /**
   * One piece of content and the alternative routes being tried for it, while they are being tried.
   * {@code refused} is set by a throttled route, {@code landed} by a route that got through; a
   * delivery that ends refused and not landed is the one loss the whole group is worth.
   */
  private static final class Delivery {
    private final Target target;
    private boolean landed;
    private boolean refused;

    private Delivery(Target target) {
      this.target = target;
    }
  }

  /** The process-wide registry: a loss recorded on one code path is announced by any other. */
  static final GitHubLostWrites SHARED =
      new GitHubLostWrites(Instant::now, DEFAULT_MAX_TARGETS, DEFAULT_TTL);

  private final Map<Target, Loss> pending = new ConcurrentHashMap<>();
  private final AtomicLong ids = new AtomicLong();

  /**
   * The delivery whose routes are being tried on this thread, if any. Thread confinement is what
   * the routes already have — a finding's attempts run one after another on the thread publishing
   * that review — so it is also the cheapest way to reach {@link #recording} from {@link
   * #asOneDelivery} without threading a handle through the REST client interface that sits between
   * them. Held per instance, not per class, so a test's registry cannot see {@link #SHARED}'s
   * deliveries or vice versa.
   */
  private final ThreadLocal<Delivery> delivery = new ThreadLocal<>();

  private final Supplier<Instant> clock;
  private final int maxTargets;
  private final Duration ttl;

  GitHubLostWrites(Supplier<Instant> clock, int maxTargets, Duration ttl) {
    this.clock = clock;
    this.maxTargets = maxTargets;
    this.ttl = ttl;
  }

  /**
   * Runs a content-creating call whose body lands in the pull request's conversation, handing it
   * the pending notice to carry (or an empty string when there is nothing to say). The notice is
   * only marked announced once the call has actually succeeded, so a post that is itself dropped
   * does not take the notice with it, and a loss that lands while this post is in flight is still
   * waiting for the next one.
   */
  public <T> T carrying(Target target, Function<String, T> send) {
    var carried = snapshot(target);
    var result = recording(target, () -> send.apply(notice(carried.pending())));
    announce(target, carried);
    return result;
  }

  /**
   * Runs a content-creating call that cannot carry a notice — an inline comment or a thread reply —
   * but can still leave one behind when GitHub throttles it away.
   */
  public <T> T recording(Target target, Supplier<T> send) {
    var scope = deliveryFor(target);
    try {
      var result = send.get();
      if (scope != null) {
        scope.landed = true;
      }
      return result;
    } catch (WebApplicationException e) {
      // Only a throttle: a permission refusal or a 422 is a defect to fix, not a post to re-run,
      // and announcing it on the PR would be noise on every single comment.
      if (GitHubApiError.of(e).filter(GitHubApiError::isThrottled).isPresent()) {
        if (scope == null) {
          remember(target);
        } else {
          // Held, not remembered: a later route may still deliver this content (#729).
          scope.refused = true;
        }
      }
      throw e;
    }
  }

  /**
   * Runs several content-creating calls that are alternative routes to the pull request for one
   * piece of content — a finding's line-anchored comment, the same comment without its suggestion
   * block, and the thread on the file (#721) — so the pull request hears about at most one loss,
   * and only when no route delivered it.
   *
   * <p>Routes that fail for anything but a throttle are as invisible here as they are to {@link
   * #recording}: a 422 about the payload is a defect to fix rather than a post worth re-running. A
   * group in which some route was throttled and none landed is remembered exactly once, so a
   * finding that really is lost still announces itself — and announces itself once rather than once
   * per attempt.
   *
   * <p>Nesting for the <em>same</em> pull request reuses the outer group rather than opening a
   * second one, so a caller cannot lose another caller's routes by grouping its own. Nesting for a
   * different pull request cannot reuse it: a group speaks only for the pull request it was opened
   * on ({@link #deliveryFor}), so handing the inner routes the outer group would leave them with no
   * accounting at all and remember each of them separately — the per-route over-count #729 removed,
   * reintroduced for the nested caller and silently, with no log and no exception (#748). The inner
   * group therefore takes over the thread and hands the outer one back when it closes.
   */
  public <T> T asOneDelivery(Target target, Supplier<T> routes) {
    var outer = delivery.get();
    if (outer != null && outer.target.equals(target)) {
      return routes.get();
    }
    var scope = new Delivery(target);
    delivery.set(scope);
    try {
      return routes.get();
    } finally {
      if (outer == null) {
        delivery.remove();
      } else {
        delivery.set(outer);
      }
      if (scope.refused && !scope.landed) {
        remember(target);
      }
    }
  }

  /**
   * The delivery this call is a route of, or {@code null} when it is a post of its own. A delivery
   * for some other pull request is not this call's: the group's accounting only ever speaks for the
   * pull request it was opened on.
   */
  private Delivery deliveryFor(Target target) {
    var scope = delivery.get();
    return scope != null && scope.target.equals(target) ? scope : null;
  }

  /** The notice text for {@code lost} dropped posts, or empty when there is nothing pending. */
  static String notice(int lost) {
    if (lost <= 0) {
      return "";
    }
    return "> [!WARNING]\n> "
        + (lost == 1
            ? "**An earlier reply on this pull request was never posted.**"
            : "**" + lost + " earlier replies on this pull request were never posted.**")
        + " GitHub was rate-limiting the bot and the retries ran out, so work it had already"
        + " finished was thrown away. If you were waiting on an answer, run the command again.";
  }

  /** Puts {@code notice} above {@code body}, leaving the body untouched when there is no notice. */
  static String prepend(String notice, String body) {
    if (notice.isEmpty()) {
      return body;
    }
    return body == null || body.isBlank() ? notice : notice + "\n\n" + body;
  }

  /** What this pull request has pending right now, or {@link #NOTHING} when it has nothing. */
  private Loss snapshot(Target target) {
    var loss = pending.get(target);
    if (loss == null) {
      return NOTHING;
    }
    if (expired(loss, clock.get())) {
      pending.remove(target, loss);
      return NOTHING;
    }
    return loss;
  }

  /**
   * Marks everything the delivered post carried as announced, and drops the entry once nothing is
   * left pending on it so its slot goes back to the registry immediately rather than sitting
   * settled until the TTL sweeps it.
   *
   * <p>Two guards decide whether this delivery counts. The id must still match, or this carrier is
   * stale — the entry it read was already announced and dropped, and what sits here now is a later,
   * unrelated run of losses it never carried. The watermark must still be behind, or a second
   * carrier that read the same snapshot would retire a loss recorded after it. Failing either one
   * leaves the entry exactly as found.
   */
  private void announce(Target target, Loss carried) {
    if (carried.pending() == 0) {
      return;
    }
    pending.computeIfPresent(
        target,
        (key, loss) -> {
          if (loss.id() != carried.id() || loss.announced() >= carried.lost()) {
            return loss;
          }
          var settled = new Loss(loss.id(), loss.lost(), carried.lost(), loss.at());
          return settled.pending() == 0 ? null : settled;
        });
  }

  private void remember(Target target) {
    var now = clock.get();
    pending.values().removeIf(loss -> expired(loss, now));
    if (pending.size() >= maxTargets && !pending.containsKey(target)) {
      // Nothing left to do but say so in the log, which is exactly where #578 started.
      log.warn(
          "Lost a throttled post on {} and {} pull requests already hold a pending notice, so this"
              + " one is only recorded here",
          target,
          pending.size());
      return;
    }
    var total =
        pending.merge(
            target,
            new Loss(ids.incrementAndGet(), 1, 0, now),
            (old, one) -> new Loss(old.id(), old.lost() + 1, old.announced(), now));
    log.warn(
        "Lost a throttled post on {} — the next comment the bot lands there will say so ({} now"
            + " pending)",
        target,
        total.pending());
  }

  private boolean expired(Loss loss, Instant now) {
    return Duration.between(loss.at(), now).compareTo(ttl) > 0;
  }
}
