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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * stashed here instead, and the replacement adds those whose code the new diff still holds to its
 * own findings, where they are screened, verified and posted like any finding it raised. No reader
 * has seen them, so they are never listed to the model, or counted, as previous findings (#1411).
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

  /** Findings kept per superseded run. A longer set loses its tail. */
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

  private final Object lock = new Object();

  /** Insertion-ordered so eviction past the cap drops the least recently stashed pull request. */
  private final Map<PrKey, Carried> entries = new LinkedHashMap<>();

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
            + " of %s",
        carried.findings().size(),
        owner,
        repo,
        prNumber,
        shortSha(carried.supersededSha()),
        shortSha(headSha));
    return carried;
  }

  /**
   * The carried findings whose flagged code the current diff still holds, among the lines the head
   * has or the ones the pull request removes, each on the path the diff has it at. One whose code
   * the diff holds in neither place is left behind. A finding on a line of the base that the
   * superseding push went on to remove is therefore kept, as a finding about removed code.
   */
  static List<ReviewResponse.Finding> stillInDiff(
      Carried carried, DiffLineResolver lineResolver, Map<String, String> renameTargets) {
    return carried.findings().stream()
        .map(finding -> onCurrentPath(finding, renameTargets))
        .filter(
            finding ->
                lineResolver.isFindingPresent(finding.file(), finding.suggestionOld())
                    || lineResolver
                        .locateRemovedQuote(finding.file(), finding.suggestionOld(), finding.line())
                        .isPresent())
        .toList();
  }

  /**
   * {@code response} with the {@code carried} findings on one of {@code files} ahead of its own, so
   * the call that saw the file screens them like the findings it raised. A carried finding the
   * response raises again itself is left out: the fresh one was written against the current head.
   */
  static ReviewResponse joined(
      ReviewResponse response,
      List<ReviewResponse.Finding> carried,
      List<GitHubPullRequestClient.FileDiff> files) {
    var names = namesOf(files);
    return ahead(
        response,
        carried.stream()
            .filter(f -> names.contains(f.file()) && !raisedAgain(response, f))
            .toList());
  }

  private static boolean raisedAgain(ReviewResponse response, ReviewResponse.Finding carried) {
    return response.findings().stream()
        .anyMatch(
            own ->
                FilePaths.same(own.file(), carried.file())
                    && DefectIdentity.sameDefect(own, carried));
  }

  /**
   * {@code response} with the {@code carried} findings on none of {@code screened}, the files a
   * review call saw: no call can screen them, so they stand as the superseded run verified them.
   */
  static ReviewResponse joinedUnscreened(
      ReviewResponse response,
      List<ReviewResponse.Finding> carried,
      List<GitHubPullRequestClient.FileDiff> screened) {
    var names = namesOf(screened);
    return ahead(response, carried.stream().filter(f -> !names.contains(f.file())).toList());
  }

  private static Set<String> namesOf(List<GitHubPullRequestClient.FileDiff> files) {
    return files.stream()
        .map(GitHubPullRequestClient.FileDiff::filename)
        .collect(Collectors.toSet());
  }

  private static ReviewResponse ahead(
      ReviewResponse response, List<ReviewResponse.Finding> carried) {
    if (carried.isEmpty()) {
      return response;
    }
    var findings = new ArrayList<>(carried);
    findings.addAll(response.findings());
    return response.withFindings(findings);
  }

  private static ReviewResponse.Finding onCurrentPath(
      ReviewResponse.Finding finding, Map<String, String> renameTargets) {
    var path = renameTargets.getOrDefault(finding.file(), finding.file());
    if (path.equals(finding.file())) {
      return finding;
    }
    return new ReviewResponse.Finding(
        finding.risk(),
        finding.confidence(),
        path,
        finding.line(),
        finding.title(),
        finding.description(),
        finding.suggestionOld(),
        finding.suggestionNew());
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
        + " carried into this review and checked against the current head. What still holds is"
        + " posted with this review's findings";
  }

  private static String shortSha(String sha) {
    return sha != null && sha.length() > 7 ? sha.substring(0, 7) : sha;
  }
}
