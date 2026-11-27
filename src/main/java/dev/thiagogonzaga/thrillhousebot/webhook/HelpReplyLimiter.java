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

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Limits how often {@code /help} is answered for commenters without write access: once per pull
 * request in each {@link #WINDOW}. {@code /help} is the one command open to anyone, so without a
 * limit any commenter could make the bot post the command list as often as they comment. The list
 * never changes, so a second asker inside the window finds the first answer on the same pull
 * request. A commenter with write access is not limited and never comes here. State is in memory
 * per replica.
 */
@ApplicationScoped
public class HelpReplyLimiter {

  /** One answer per pull request in this long for commenters without write access. */
  static final Duration WINDOW = Duration.ofHours(1);

  /** Sweep expired entries once the map reaches this size, like the other in-memory TTL caches. */
  static final int SWEEP_THRESHOLD = 10_000;

  private record Key(long installationId, long repositoryId, int prNumber) {}

  /** Pull request → epoch millis until which a further {@code /help} is not answered. */
  private final ConcurrentHashMap<Key, Long> deadlines = new ConcurrentHashMap<>();

  private final int sweepThreshold;
  private final LongSupplier clock;

  public HelpReplyLimiter() {
    this(SWEEP_THRESHOLD, System::currentTimeMillis);
  }

  /** Visible for tests: allows controlling the clock and the sweep threshold. */
  HelpReplyLimiter(int sweepThreshold, LongSupplier clock) {
    this.sweepThreshold = Math.max(1, sweepThreshold);
    this.clock = clock;
  }

  /**
   * @return {@code true} when this {@code /help} may be answered, which starts the pull request's
   *     window; {@code false} while an earlier answer's window is still open
   */
  public boolean tryAcquire(long installationId, long repositoryId, int prNumber) {
    long now = clock.getAsLong();
    var granted = new boolean[1];
    deadlines.compute(
        new Key(installationId, repositoryId, prNumber),
        (ignored, deadline) -> {
          if (deadline != null && deadline > now) {
            return deadline;
          }
          granted[0] = true;
          return now + WINDOW.toMillis();
        });
    if (granted[0] && deadlines.size() >= sweepThreshold) {
      deadlines.values().removeIf(deadline -> deadline <= now);
    }
    return granted[0];
  }

  /** Visible for tests: pull requests with an entry, expired or not. */
  int tracked() {
    return deadlines.size();
  }
}
