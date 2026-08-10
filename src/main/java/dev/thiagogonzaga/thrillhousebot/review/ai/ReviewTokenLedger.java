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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-review accumulator of the tokens the provider reports as actually consumed — input plus
 * output, across every AI call the review makes, retries included — and the enforcement point for
 * the {@code REVIEW_MAX_TOKENS_PER_REVIEW} spend ceiling.
 *
 * <p>Usage is fed by {@link OtelObservabilityListener#onResponse}: the listener correlates each
 * provider response back to its review via the session id that {@link AiReviewService} binds
 * through {@link ReviewSessionContext} before starting a stream, and the langchain4j contract
 * pinned by {@code StreamingChatModelListenerOrderingTest} guarantees the listener's {@code
 * onResponse} runs before the call's own completion handler resolves — so by the time the review
 * path decides whether to make its <em>next</em> call, the previous call's spend is already in the
 * ledger.
 *
 * <p>Entries follow an open/record/clear lifecycle keyed by session id: {@link
 * dev.thiagogonzaga.thrillhousebot.review.FindingPipeline} opens the entry before its first call
 * and clears it when the review path is done, and {@link #record} drops usage for a session with no
 * open entry — so a stale provider callback landing after its review finished cannot re-create a
 * row that would never be cleaned up. A stale callback landing <em>during</em> the review (e.g. a
 * timed-out attempt whose response still arrives) is counted: those tokens were billed, which is
 * exactly what the ceiling meters. Counters are {@link LongAdder}s in a {@link ConcurrentHashMap}
 * because parallel map-reduce batches record from concurrent virtual threads.
 */
@ApplicationScoped
public class ReviewTokenLedger {

  private final ConcurrentHashMap<Long, LongAdder> spentBySession = new ConcurrentHashMap<>();
  private final long maxTokensPerReview;

  @Inject
  public ReviewTokenLedger(ThrillhouseConfig config) {
    this.maxTokensPerReview = config.review().maxTokensPerReview();
  }

  /** Opens the ledger entry for a review so its calls' usage is accumulated. Idempotent. */
  public void open(long sessionId) {
    spentBySession.computeIfAbsent(sessionId, id -> new LongAdder());
  }

  /**
   * Adds one call's provider-reported usage to its review's total. Null counts (providers that omit
   * a side of the usage) count as zero; a session with no open entry is ignored (see class doc).
   */
  public void recordUsage(long sessionId, Integer inputTokens, Integer outputTokens) {
    var spent = spentBySession.get(sessionId);
    if (spent == null) {
      return;
    }
    spent.add(
        (inputTokens == null ? 0L : inputTokens) + (outputTokens == null ? 0L : outputTokens));
  }

  /** Total tokens (input + output) recorded for this review so far; 0 when nothing is open. */
  public long tokensSpent(long sessionId) {
    var spent = spentBySession.get(sessionId);
    return spent == null ? 0L : spent.sum();
  }

  /** The configured ceiling in tokens; {@code <= 0} means the ceiling is off. */
  public long ceiling() {
    return maxTokensPerReview;
  }

  /**
   * True when the ceiling is enabled and this review's recorded spend has reached it. "Reached" is
   * {@code >=}: a review that consumed exactly its budget has nothing left to pay the next call
   * with.
   */
  public boolean ceilingReached(long sessionId) {
    return maxTokensPerReview > 0 && tokensSpent(sessionId) >= maxTokensPerReview;
  }

  /**
   * The ledger key for a session: its persisted id, or a sentinel for a session that has none. In
   * production the orchestrator persists the session before the pipeline runs, so the sentinel
   * exists for unpersisted sessions (unit tests, defensive callers) — mapping them here, at the
   * ledger boundary, means no call site ever unboxes the nullable {@code ReviewSession.id} itself.
   */
  public static long keyFor(dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession session) {
    return session.id == null ? Long.MIN_VALUE : session.id;
  }

  /**
   * Refuses the next AI call once the ceiling is reached, logging spent-vs-ceiling and throwing the
   * typed error; a no-op while the ceiling is off or not yet reached.
   */
  public void ensureCallAllowed(long sessionId) {
    if (!ceilingReached(sessionId)) {
      return;
    }
    var spent = tokensSpent(sessionId);
    Log.warnf(
        "AI call for review session %d refused: %d tokens spent >= the %d-token per-review ceiling"
            + " (REVIEW_MAX_TOKENS_PER_REVIEW)",
        sessionId, spent, maxTokensPerReview);
    throw new TokenSpendCeilingExceededException(spent, maxTokensPerReview);
  }

  /** Drops the review's entry. Recording for the session stops until it is opened again. */
  public void clear(long sessionId) {
    spentBySession.remove(sessionId);
  }
}
