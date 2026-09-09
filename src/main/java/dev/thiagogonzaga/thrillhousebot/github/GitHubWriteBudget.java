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

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ceiling on how long one review may spend waiting on GitHub's rate limit across all of its writes
 * (#734).
 *
 * <p>{@link GitHubWriteRetry} bounds one call at {@link GitHubWriteRetry#TOTAL_BUDGET}, and that
 * bound is per call. A review posts each finding by up to three routes — the line-anchored comment,
 * the same comment without its suggestion block, and the thread on the file (#721) — so a finding
 * GitHub refuses throughout can wait 3 × 90 seconds, and a review of fifty findings refused
 * throughout can hold its pull request's dispatcher slot for roughly 225 minutes. The dispatcher
 * serializes per pull request, so nothing else starves, but a review in that state is wedged for
 * hours with nothing in the log saying it is waiting rather than hung.
 *
 * <p>So a review opens a ledger for the length of its publication ({@link #within}), and every wait
 * the retry is about to serve on that thread is charged to it first ({@link #admits}). The wait
 * that crosses the ceiling is still served — the overrun is bounded by one clamped wait, and
 * refusing it would throw away waiting already paid for — and it is warned about once, naming the
 * review. Every throttled write after it is given up on without a wait: the retry treats the
 * refusal as it treats spent attempts, so the write falls to the routes and disclosures that
 * already exist for a write GitHub outlasted, and the review body says the budget is why (see
 * {@code ReviewPublisher}). A first attempt is never withheld — GitHub may have reopened, and a
 * write that lands is a finding saved — only the repeats are.
 *
 * <p>What is charged is the waiting the retry serves, not wall-clock time: the model calls before
 * publication and the HTTP round trips themselves are not what #734 measured, and a pacing wait in
 * {@link GitHubWritePacer} is bounded on its own. The ledger is thread state, for the reason {@link
 * GitHubLostWrites} keeps its deliveries on the thread: a review's routes run one after another on
 * the thread publishing it, and the retry that charges the ledger sits behind the REST client
 * interface with no handle to be passed one through. The size of the ceiling is the caller's to
 * name — the publisher reads it from the typed configuration, {@code
 * thrillhousebot.github.write-retry-budget} — so this class holds no configuration of its own.
 *
 * <p>Off outside a review: the on-demand commands and the thread replies post one piece of content
 * each, and the per-call bound is the right one for them.
 */
public final class GitHubWriteBudget {

  private static final Logger log = LoggerFactory.getLogger(GitHubWriteBudget.class);

  /** The review open on this thread, if one is. Absent while nothing is. */
  private static final ThreadLocal<Ledger> OPEN = new ThreadLocal<>();

  /** One review's running total against its ceiling. */
  private static final class Ledger {
    private final String review;
    private final Duration budget;
    private Duration spent = Duration.ZERO;
    private boolean exhausted;

    private Ledger(String review, Duration budget) {
      this.review = review;
      this.budget = budget;
    }
  }

  private GitHubWriteBudget() {}

  /**
   * Runs one review's publication under {@code budget}. A review already open on this thread is
   * rejoined rather than restarted — nested publication is still the same review, and a ledger that
   * reset on entry would let the inner scope spend what the outer one already had. A budget that is
   * zero or negative opens nothing, so every wait is admitted. The ledger is closed on every exit
   * path, so a review that fails leaves nothing behind for the next one on the thread.
   */
  public static void within(String review, Duration budget, Runnable work) {
    if (!budget.isPositive() || OPEN.get() != null) {
      work.run();
      return;
    }
    OPEN.set(new Ledger(review, budget));
    try {
      work.run();
    } finally {
      OPEN.remove();
    }
  }

  /**
   * Whether the retry may serve {@code wait} for {@code operation}, charging it to the review open
   * on this thread when one is. Always yes outside a review. The wait that crosses the ceiling is
   * admitted and is the last one that is; from then on the answer is no, and the retry gives the
   * write up. The crossing is the one line the review leaves at warning level about its waiting, so
   * it names the review, the ceiling and what was being posted when it was reached.
   */
  static boolean admits(String operation, Duration wait) {
    var ledger = OPEN.get();
    if (ledger == null) {
      return true;
    }
    if (ledger.exhausted) {
      return false;
    }
    ledger.spent = ledger.spent.plus(wait);
    if (ledger.spent.compareTo(ledger.budget) >= 0) {
      ledger.exhausted = true;
      log.warn(
          "The review of {} has spent its {}s write-retry budget waiting on GitHub's rate limit"
              + " ({}s, the wait for {} included) — later throttled writes in this review are not"
              + " retried, and the review body names the findings they carried",
          ledger.review,
          ledger.budget.toSeconds(),
          ledger.spent.toSeconds(),
          operation);
    }
    return true;
  }

  /**
   * The ceiling the review open on this thread has crossed, or empty while it has not — or while no
   * review is open at all. What the review body reads when it explains a finding no route
   * delivered.
   */
  public static Optional<Duration> exhausted() {
    var ledger = OPEN.get();
    return ledger != null && ledger.exhausted ? Optional.of(ledger.budget) : Optional.empty();
  }
}
