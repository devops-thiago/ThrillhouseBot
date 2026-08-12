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
 */
public final class GitHubLostWrites {

  private static final Logger log = LoggerFactory.getLogger(GitHubLostWrites.class);

  /**
   * How many pull requests may hold a pending notice at once, so the map cannot grow without end.
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

  /** How many posts this pull request has lost, and when the last one went. */
  private record Loss(int count, Instant at) {}

  /** The process-wide registry: a loss recorded on one code path is announced by any other. */
  static final GitHubLostWrites SHARED =
      new GitHubLostWrites(Instant::now, DEFAULT_MAX_TARGETS, DEFAULT_TTL);

  private final Map<Target, Loss> pending = new ConcurrentHashMap<>();
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
   * only cleared once the call has actually succeeded, so a post that is itself dropped does not
   * take the notice with it.
   */
  public <T> T carrying(Target target, Function<String, T> send) {
    int carried = pendingCount(target);
    var result = recording(target, () -> send.apply(notice(carried)));
    settle(target, carried);
    return result;
  }

  /**
   * Runs a content-creating call that cannot carry a notice — an inline comment or a thread reply —
   * but can still leave one behind when GitHub throttles it away.
   */
  public <T> T recording(Target target, Supplier<T> send) {
    try {
      return send.get();
    } catch (WebApplicationException e) {
      // Only a throttle: a permission refusal or a 422 is a defect to fix, not a post to re-run,
      // and announcing it on the PR would be noise on every single comment.
      if (GitHubApiError.of(e).filter(GitHubApiError::isThrottled).isPresent()) {
        remember(target);
      }
      throw e;
    }
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

  private int pendingCount(Target target) {
    var loss = pending.get(target);
    if (loss == null) {
      return 0;
    }
    if (expired(loss, clock.get())) {
      pending.remove(target, loss);
      return 0;
    }
    return loss.count();
  }

  /** Drops what was just announced, keeping anything lost while this post was in flight. */
  private void settle(Target target, int carried) {
    if (carried == 0) {
      return;
    }
    pending.computeIfPresent(
        target,
        (key, loss) -> loss.count() > carried ? new Loss(loss.count() - carried, loss.at()) : null);
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
        pending.merge(target, new Loss(1, now), (old, one) -> new Loss(old.count() + 1, now));
    log.warn(
        "Lost a throttled post on {} — the next comment the bot lands there will say so ({} now"
            + " pending)",
        target,
        total.count());
  }

  private boolean expired(Loss loss, Instant now) {
    return Duration.between(loss.at(), now).compareTo(ttl) > 0;
  }
}
