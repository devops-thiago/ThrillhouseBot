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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hands the verified findings of a run that stood down for {@link ReviewSkipReason#HEAD_MOVED} to
 * the coalesced run that replaces it (#806).
 *
 * <p>#704 made a run whose head moved abandon its post, because the dispatcher's coalesced run for
 * the new head re-reviews and posts in its place. That is right for the review body, but the
 * standing-down run's findings — generated, verified and paid for — were dropped with it, and
 * nothing about a fresh pass guarantees it finds them again. On a pull request pushed to while
 * under review, every run that found something was discarded and the only outcome that reached
 * GitHub was an approval from the one run that found nothing. The superseded run's findings are
 * stashed here instead, and the replacement folds them into its previous-round context, where the
 * model is asked to confirm or resolve each one against the new head and the vanish, decline and
 * backstop passes treat them exactly as findings from a posted round.
 *
 * <p>The hand-off is keyed by pull request and holds one entry per pull request, so it can neither
 * leak across pull requests nor accumulate across pushes: a later abandon replaces the entry, and
 * the replacement run takes it exactly once. It is released only to the review of the head that
 * superseded the run — any other next review of the pull request drops it, because that review is
 * not the one that displaced it. The entry itself is capped in findings, and the store in pull
 * requests, so a burst of abandoned runs cannot grow it without bound. State is per replica and
 * lost on restart, which is the same bound the dispatcher's own coalescing state has.
 */
@ApplicationScoped
public class SupersededFindingsCarryover {

  /**
   * Findings kept per superseded run. A longer set loses its tail, which is the run's own newest
   * findings: the ones it was itself carrying, already confirmed once, lead the list.
   */
  static final int MAX_FINDINGS = 50;

  /** Pull requests held at once; the entry least recently stashed is evicted past this. */
  static final int MAX_PULL_REQUESTS = 256;

  /**
   * What one superseded run hands to its replacement: the head it reviewed, the head that displaced
   * it, and the findings it had verified by the time it stood down.
   */
  public record Carried(
      String supersededSha, String supersedingSha, List<ReviewResponse.Finding> findings) {

    /** No superseded run to carry from — the shape every ordinary review sees. */
    public static final Carried NONE = new Carried("", "", List.of());

    public Carried {
      findings = List.copyOf(findings);
    }

    public boolean isEmpty() {
      return findings.isEmpty();
    }
  }

  private record PrKey(String owner, String repo, int prNumber) {}

  private final ObjectMapper mapper;

  private final Object lock = new Object();

  /** Insertion-ordered so eviction past the cap drops the least recently stashed pull request. */
  private final Map<PrKey, Carried> entries = new LinkedHashMap<>();

  @Inject
  public SupersededFindingsCarryover(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Records the verified findings of a run that stood down because {@code supersedingSha} displaced
   * {@code supersededSha} while it reviewed. A run that verified nothing leaves nothing behind. The
   * count carried is logged at WARN: a replacement that then posts a quiet review must be readable
   * as "carried N and cleared them", never mistaken for a clean pass over a clean head.
   */
  public void stash(
      String owner,
      String repo,
      int prNumber,
      String supersededSha,
      String supersedingSha,
      List<ReviewResponse.Finding> findings) {
    if (findings.isEmpty()) {
      return;
    }
    var kept = findings.size() > MAX_FINDINGS ? findings.subList(0, MAX_FINDINGS) : findings;
    var key = new PrKey(owner, repo, prNumber);
    synchronized (lock) {
      // Re-inserted so the entry counts as the newest for eviction purposes.
      entries.remove(key);
      entries.put(key, new Carried(supersededSha, supersedingSha, kept));
      while (entries.size() > MAX_PULL_REQUESTS) {
        entries.remove(entries.keySet().iterator().next());
      }
    }
    Log.warnf(
        "Review of %s/%s #%d on head %s was superseded by %s — carrying %d of its %d verified"
            + " findings into the replacement run",
        owner,
        repo,
        prNumber,
        shortSha(supersededSha),
        shortSha(supersedingSha),
        kept.size(),
        findings.size());
  }

  /**
   * Removes and returns what the superseded run of this pull request left for the review of {@code
   * headSha}. The entry is dropped whether or not it is released: a review of any other head is not
   * the run that displaced the superseded one, and holding the findings for a review that may never
   * come would leak them into an unrelated later run.
   */
  public Carried take(String owner, String repo, int prNumber, String headSha) {
    Carried carried;
    synchronized (lock) {
      carried = entries.remove(new PrKey(owner, repo, prNumber));
    }
    if (carried == null) {
      return Carried.NONE;
    }
    if (!carried.supersedingSha().equalsIgnoreCase(headSha)) {
      Log.warnf(
          "Dropping %d findings carried from the superseded review of %s/%s #%d on head %s: this"
              + " review is of %s, not the head that superseded it (%s)",
          carried.findings().size(),
          owner,
          repo,
          prNumber,
          shortSha(carried.supersededSha()),
          shortSha(headSha),
          shortSha(carried.supersedingSha()));
      return Carried.NONE;
    }
    Log.infof(
        "Carrying %d findings from the superseded review of %s/%s #%d on head %s into the review"
            + " of %s as previous findings",
        carried.findings().size(),
        owner,
        repo,
        prNumber,
        shortSha(carried.supersededSha()),
        shortSha(headSha));
    return carried;
  }

  /** The persisted prior rounds of a review with a superseded run's findings folded in. */
  public record PriorRounds(List<String> jsons, List<ReviewResponse> parsed) {
    public PriorRounds {
      jsons = List.copyOf(jsons);
      parsed = List.copyOf(parsed);
    }
  }

  /**
   * Folds the carried findings into the round the review reports on — the newest persisted round
   * that raised findings ({@link FollowUpAnalyzer#effectivePreviousRoundIndex}), or a round of
   * their own when no persisted round did. Appended after that round's own findings, so every
   * posted finding keeps the id its inline comment marker carries and the carried ones take the ids
   * after them; the raw JSON is rebuilt alongside the parsed round so the rendered context, the id
   * space and the JSON the supersede pass re-reads still describe one round.
   */
  public PriorRounds merge(
      List<String> priorAiResponseJsons,
      List<ReviewResponse> priorAiResponses,
      List<ReviewResponse.Finding> carried) {
    var index = Math.max(FollowUpAnalyzer.effectivePreviousRoundIndex(priorAiResponses), 0);
    var jsons = new ArrayList<>(priorAiResponseJsons);
    var parsed = new ArrayList<>(priorAiResponses);
    if (parsed.isEmpty()) {
      var own = new ReviewResponse(carried, List.of(), null);
      jsons.add(toJson(own));
      parsed.add(own);
    } else {
      var base = parsed.get(index);
      var findings = new ArrayList<>(base.findings());
      findings.addAll(carried);
      var merged = new ReviewResponse(findings, base.previousFindingsStatus(), base.summary());
      jsons.set(index, toJson(merged));
      parsed.set(index, merged);
    }
    return new PriorRounds(jsons, parsed);
  }

  /**
   * The same JSON shape {@code FindingPipeline} persists, built through the tree model: a record of
   * plain fields cannot fail to serialize, and the tree route has no checked exception to swallow.
   */
  private String toJson(ReviewResponse response) {
    return mapper.valueToTree(response).toString();
  }

  /**
   * The summary's review-scope note for a carry-over, in the voice of the other scope notes: what
   * this review looked at that a fresh pass over the head would not have. Empty when nothing was
   * carried.
   */
  static String formatScopeNote(Carried carried) {
    if (carried.isEmpty()) {
      return "";
    }
    var count = carried.findings().size();
    return count
        + (count == 1 ? " finding" : " findings")
        + " from the review of superseded head `"
        + shortSha(carried.supersededSha())
        + "`, abandoned when the pull request head moved, "
        + (count == 1 ? "was" : "were")
        + " carried into this review as previous findings and re-checked against the current"
        + " head";
  }

  private static String shortSha(String sha) {
    return sha != null && sha.length() > 7 ? sha.substring(0, 7) : sha;
  }
}
