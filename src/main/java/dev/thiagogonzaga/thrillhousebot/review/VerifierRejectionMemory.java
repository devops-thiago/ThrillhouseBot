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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers, per pull request and head commit, the candidate findings the second-pass audit threw
 * out, so a later round on that same head drops them instead of putting them to the model again
 * (#711).
 *
 * <p>The verifier reached opposite verdicts on one claim and one commit: a CRITICAL SQL-injection
 * candidate it rejected with correct reasoning was published forty minutes later, on an unchanged
 * head, at the highest severity on a public route. A second audit of the same claim on the same
 * code is not a second opinion — it is the same question asked again of a sampled model, and the
 * answer that reaches the pull request is whichever round happened to post. Only one of the two can
 * be right, and nothing about being later makes the second one it.
 *
 * <p>Nor is a second audit guaranteed to happen at all. Verification fails open by design: an empty
 * response body, a timeout, a refusal, or the review's spend ceiling all keep the candidate exactly
 * as the reviewer raised it, and {@link VerificationCoverage} was added because production measured
 * roughly one review in three publishing findings no second stage had screened. On such a round a
 * claim the audit had already rejected posts with nothing standing between it and the pull request.
 * That is the shape production showed on 2026-09-16, where the review body oscillated 0, 1, 0, 2,
 * 1, 0 findings across rounds on near-identical code, and one round logged "Finding verification
 * returned no response body — keeping the 1 unverified finding(s)".
 *
 * <p>Determinism cannot come from the inputs here. Two rounds on one head do not see the same
 * material: the previous-findings section grows with every posted round, the pull request context
 * is bounded against a budget that moves with the diff, and the conversation the review reads
 * changes as people comment. The verdict is made stable by remembering it instead.
 *
 * <p>Only rejections are remembered, never confirmations. Re-raising a claim the audit rejected is
 * the failure #711 reports; re-auditing one it confirmed costs nothing but a call, and pinning a
 * "confirmed" would turn a single false positive into one the bot repeats for the life of the head.
 * The asymmetry is the safe direction: this store can only ever publish fewer findings than today.
 *
 * <p>An entry is bound to the head it was gathered on. A push replaces it, because a rejection is
 * an answer about code and the code changed — the memory must not outlive the lines that earned it.
 * The store holds one entry per pull request, capped in findings, and is capped in pull requests,
 * evicting the one least recently written. It lives in memory per replica, so a restart falls back
 * to today's re-litigation, the same bound {@link CiHoldRegistry} and {@link
 * SupersededFindingsCarryover} accept.
 */
@ApplicationScoped
public class VerifierRejectionMemory {

  /**
   * Rejections held per pull request. Past this the oldest are forgotten: a claim raised many
   * rounds ago and never raised since is the one whose suppression is worth the least.
   */
  static final int MAX_REJECTIONS = 50;

  /** Pull requests remembered at once; the entry least recently written is evicted past this. */
  static final int MAX_PULL_REQUESTS = 256;

  private record PrKey(String repository, int prNumber) {}

  /** A pull request's rejections and the head they were reached on. */
  private record Entry(String headSha, List<ReviewResponse.Finding> rejected) {}

  private final Object lock = new Object();

  /** Insertion-ordered so eviction past the cap drops the least recently written pull request. */
  private final Map<PrKey, Entry> entries = new LinkedHashMap<>();

  /**
   * The candidates to hand the verifier: {@code response} without the findings a previous round
   * already rejected on this head. Applied before the call rather than after it, so the claim is
   * neither re-litigated nor paid for, and so the drop holds on the rounds where no verdict comes
   * back at all.
   *
   * <p>A remembered rejection is matched the way every other cross-round comparison in the review
   * matches one — {@link FollowUpAnalyzer#isSameFinding} — rather than on exact text: the model
   * rewords a title between rounds, and a claim that survives only by being phrased differently is
   * the same claim.
   */
  public ReviewResponse withoutRejectionsOnThisHead(
      ReviewSession session, ReviewResponse response) {
    if (response.findings().isEmpty()) {
      return response;
    }
    var rejected = rejectedAt(session);
    if (rejected.isEmpty()) {
      return response;
    }
    var kept = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    for (ReviewResponse.Finding finding : response.findings()) {
      if (wasRejected(finding, rejected)) {
        Log.infof(
            "Dropping finding '%s' (%s:%d): the second-pass audit rejected the same claim on head"
                + " %s in an earlier round of this pull request",
            LogSafe.oneLine(finding.title()),
            LogSafe.oneLine(finding.file()),
            finding.line(),
            session.getCommitSha());
      } else {
        kept.add(finding);
      }
    }
    if (kept.size() == response.findings().size()) {
      return response;
    }
    return new ReviewResponse(
        kept,
        response.previousFindingsStatus(),
        FindingVerificationService.recount(response.summary(), kept));
  }

  /**
   * Records what this round's audit threw out: the candidates it was handed that its result no
   * longer carries. That difference is exactly the audit's own removals — a "rejected" verdict, and
   * the deterministic self-retraction screen in front of the call — because every other stage of
   * verification changes a finding's risk or confidence and keeps its location and title, which is
   * what the difference is taken on.
   *
   * <p>A round whose verification failed open removed nothing and so remembers nothing, leaving the
   * earlier rounds' answers standing.
   */
  public void remember(
      ReviewSession session,
      List<ReviewResponse.Finding> candidates,
      List<ReviewResponse.Finding> published) {
    if (!identifies(session)) {
      return;
    }
    var rejected = removed(candidates, published);
    if (rejected.isEmpty()) {
      return;
    }
    var key = new PrKey(session.getRepository(), session.getPrNumber());
    int held;
    synchronized (lock) {
      var previous = entries.remove(key);
      var kept =
          previous == null || !previous.headSha().equalsIgnoreCase(session.getCommitSha())
              ? new ArrayList<ReviewResponse.Finding>()
              : new ArrayList<>(previous.rejected());
      kept.addAll(rejected);
      var overflow = kept.size() - MAX_REJECTIONS;
      if (overflow > 0) {
        kept.subList(0, overflow).clear();
      }
      // Re-inserted so the entry counts as the newest for eviction purposes.
      entries.put(key, new Entry(session.getCommitSha(), List.copyOf(kept)));
      while (entries.size() > MAX_PULL_REQUESTS) {
        entries.remove(entries.keySet().iterator().next());
      }
      held = kept.size();
    }
    Log.infof(
        "Remembering %d finding(s) the audit rejected for %s#%d at %s (%d held); a later round on"
            + " the same head will not put them to the verifier again",
        rejected.size(),
        session.getRepository(),
        session.getPrNumber(),
        session.getCommitSha(),
        held);
  }

  /** The rejections remembered for this pull request, only when they were reached on its head. */
  private List<ReviewResponse.Finding> rejectedAt(ReviewSession session) {
    if (!identifies(session)) {
      return List.of();
    }
    synchronized (lock) {
      var entry = entries.get(new PrKey(session.getRepository(), session.getPrNumber()));
      return entry == null || !entry.headSha().equalsIgnoreCase(session.getCommitSha())
          ? List.of()
          : entry.rejected();
    }
  }

  private static boolean wasRejected(
      ReviewResponse.Finding finding, List<ReviewResponse.Finding> rejected) {
    for (ReviewResponse.Finding prior : rejected) {
      if (FollowUpAnalyzer.isSameFinding(finding, prior)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The candidates absent from the published set, compared on {@link FindingKey} — the same
   * within-one-round identity the evidence resolvers read a rebuilt finding back by. Exact rather
   * than tolerant here on purpose: both lists come from one call, so a near-match is a second
   * finding, not the same one reworded.
   *
   * <p>A finding citing no file is skipped. There is no anchor to recognize it by on the next
   * round, so remembering it could only ever match something else.
   */
  private static List<ReviewResponse.Finding> removed(
      List<ReviewResponse.Finding> candidates, List<ReviewResponse.Finding> published) {
    var survived = new HashSet<FindingKey>();
    for (ReviewResponse.Finding finding : published) {
      var key = FindingKey.of(finding);
      if (key != null) {
        survived.add(key);
      }
    }
    var rejected = new ArrayList<ReviewResponse.Finding>();
    for (ReviewResponse.Finding finding : candidates) {
      var key = FindingKey.of(finding);
      if (key != null && !survived.contains(key)) {
        rejected.add(finding);
      }
    }
    return rejected;
  }

  /**
   * Whether the session names a pull request and a head to key on. A blank head is not a head: a
   * rejection stored under one would be recalled by every later round whose head is equally
   * unknown, which is the cross-push suppression the head key exists to prevent.
   */
  private static boolean identifies(ReviewSession session) {
    return !isBlank(session.getRepository()) && !isBlank(session.getCommitSha());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Visible for tests that assert the caps. */
  int size() {
    synchronized (lock) {
      return entries.size();
    }
  }
}
