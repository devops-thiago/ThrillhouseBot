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

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers, per pull request, the head a review is running on or the verdict the strict CI gate
 * held back on that head, so a later CI completion can be routed to the pull request and answered
 * without a second review (#825).
 *
 * <p>Under {@code REVIEW_CI_GATING=strict} a review that finds nothing while required CI is still
 * pending ends as a neutral COMMENT, and nothing came back to it when CI turned green: the bot saw
 * no CI events, so every green pull request needed a manual {@code /review} that re-ran the model
 * to reach a conclusion it already had. The orchestrator now records the head it reviews here
 * before reading the CI gate, and the verdict when the gate was all that held APPROVE back. A
 * {@code check_suite} or {@code status} event for that head then finds its pull request(s) through
 * {@link #pullRequestsAt} and {@link CiHoldRevisit} re-evaluates the gate alone.
 *
 * <p>The head is tracked from the start of the review, not only once a verdict is held, because the
 * gate is read concurrently with the model call: CI that completes during the call completed after
 * the read, and its event would otherwise arrive while no verdict is held yet and be dropped. A
 * recheck dispatched for a tracked head is queued behind the running review by the dispatcher, and
 * finds either the held verdict or nothing to do.
 *
 * <p>One entry per pull request: a review of a newer head replaces the entry, so a completion for
 * an older head no longer matches anything. The store is capped in pull requests, evicting the one
 * least recently written, and lives in memory per replica — a hold lost to a restart falls back to
 * today's manual {@code /review}, the same bound {@link SupersededFindingsCarryover} accepts.
 */
@ApplicationScoped
public class CiHoldRegistry {

  /** Pull requests remembered at once; the entry least recently written is evicted past this. */
  static final int MAX_PULL_REQUESTS = 256;

  /**
   * What a re-evaluation needs to post the verdict a review held on CI: the head it reviewed, the
   * base branch its required contexts are resolved against, the check run to conclude, and the
   * dashboard link that check run carries.
   */
  public record HeldVerdict(String headSha, String baseRef, long checkRunId, String detailsUrl) {}

  private record PrKey(String owner, String repo, int prNumber) {}

  /** A pull request's current head and, once a review ended on the CI hold, its held verdict. */
  private record Entry(String headSha, HeldVerdict verdict) {}

  private final Object lock = new Object();

  /** Insertion-ordered so eviction past the cap drops the least recently written pull request. */
  private final Map<PrKey, Entry> entries = new LinkedHashMap<>();

  /**
   * Records that a review of {@code headSha} is starting, replacing whatever the pull request had:
   * any verdict held on an older head is obsolete once a newer head is under review.
   */
  public void track(String owner, String repo, int prNumber, String headSha) {
    put(new PrKey(owner, repo, prNumber), new Entry(headSha, null));
  }

  /** Records the verdict a finished review held on CI for its head. */
  public void hold(String owner, String repo, int prNumber, HeldVerdict verdict) {
    put(new PrKey(owner, repo, prNumber), new Entry(verdict.headSha(), verdict));
  }

  /** Forgets the pull request: its review ended without a hold, or the hold was posted. */
  public void release(String owner, String repo, int prNumber) {
    synchronized (lock) {
      entries.remove(new PrKey(owner, repo, prNumber));
    }
  }

  /**
   * The pull requests whose tracked or held head is {@code headSha}, in the order they were
   * written. A CI event names a commit, not a pull request — its {@code pull_requests} list is
   * empty for a fork — so this is how the webhook finds who to recheck.
   */
  public List<Integer> pullRequestsAt(String owner, String repo, String headSha) {
    var found = new ArrayList<Integer>();
    synchronized (lock) {
      for (var entry : entries.entrySet()) {
        var key = entry.getKey();
        if (key.owner().equals(owner)
            && key.repo().equals(repo)
            && entry.getValue().headSha().equalsIgnoreCase(headSha)) {
          found.add(key.prNumber());
        }
      }
    }
    return found;
  }

  /**
   * The verdict held for the pull request, only when it was held on exactly {@code headSha}: empty
   * while a review is still running, after the head moved on, or when nothing is remembered.
   */
  public Optional<HeldVerdict> heldAt(String owner, String repo, int prNumber, String headSha) {
    synchronized (lock) {
      var entry = entries.get(new PrKey(owner, repo, prNumber));
      if (entry == null || entry.verdict() == null || !entry.headSha().equalsIgnoreCase(headSha)) {
        return Optional.empty();
      }
      return Optional.of(entry.verdict());
    }
  }

  /** Visible for tests that assert the cap. */
  int size() {
    synchronized (lock) {
      return entries.size();
    }
  }

  private void put(PrKey key, Entry entry) {
    synchronized (lock) {
      // Re-inserted so the entry counts as the newest for eviction purposes.
      entries.remove(key);
      entries.put(key, entry);
      while (entries.size() > MAX_PULL_REQUESTS) {
        entries.remove(entries.keySet().iterator().next());
      }
    }
  }
}
