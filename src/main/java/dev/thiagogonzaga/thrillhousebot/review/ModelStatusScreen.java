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
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Holds the model's {@code previous_findings_status} entries to the findings they name and to the
 * code (#1322), as soon as a review call returns and before anything stores or reads them.
 *
 * <ol>
 *   <li>Each entry counts for the finding its echoed key names ({@link
 *       SuggestionFormatter#findingKey}, the key the thread marker carries; {@link
 *       EchoedKeys#resolve}): the finding under its id when that finding has the key, otherwise the
 *       one listed finding that has it, the entry renumbered to it and the renumbering logged
 *       (#1402), unless an entry whose id and key agree, or an earlier entry read by its key,
 *       already names that finding. An entry with no key, or a key no listed finding or two of them
 *       have, is dropped and logged. The finding is then unreported, so the approve backstop holds
 *       it as it holds any finding the model left out: a swapped id can neither close nor keep open
 *       the wrong finding.
 *   <li>A {@code resolved} entry is held to the finding's file as it stands since the round that
 *       raised the finding (#1319). It stands when the file changed since then: the fix may sit
 *       anywhere in the file, not only on the flagged line (#1364). It also stands when another
 *       file the finding's title or description names changed since then ({@link NamedFiles},
 *       #1316): a finding that a document and the code disagree is fixed in whichever file was
 *       wrong. Otherwise it is rewritten to {@code unresolved} under {@link #CONTRADICTED_NOTE},
 *       and the contradiction logged, when the file is unchanged. When that cannot be told (no
 *       comparison of the two heads), it stands when the flagged code is no longer in the diff
 *       ({@link FollowUpAnalyzer#isStillPresent}) or its suggested fix landed beside it ({@link
 *       FollowUpAnalyzer#suggestedFixLanded}), the rule #1322 set, or when a file it names changed
 *       ({@link NamedFiles}), as above. A finding with no file cannot be placed, so its {@code
 *       resolved} is held.
 * </ol>
 *
 * <p>The ids are the listing's ({@link PriorListing}): the effective previous round's findings,
 * then the older rounds' findings listed after them.
 *
 * <p>Stored rounds are never screened: they carry no key, and the replay that reads them trusts
 * what was screened when they were written.
 */
final class ModelStatusScreen {

  /** The note a {@code resolved} the code contradicts is held {@code unresolved} under. */
  static final String CONTRADICTED_NOTE =
      "reported resolved, but the finding's file is unchanged or its flagged code is still in the"
          + " diff and the suggested fix is not";

  private static final String STATUS_RESOLVED = "resolved";
  private static final String STATUS_UNRESOLVED = "unresolved";

  private final PriorListing listing;
  private final DiffLineResolver lineResolver;
  private final Map<String, String> renameTargets;

  /**
   * @param previous the findings the prompt listed by number, in order, raised by rounds not known
   * @param lineResolver the current diff
   * @param renameTargets each renamed file's new path, blank for a content-identical rename
   */
  ModelStatusScreen(
      List<ReviewResponse.Finding> previous,
      DiffLineResolver lineResolver,
      Map<String, String> renameTargets) {
    this(PriorListing.of(previous), lineResolver, renameTargets);
  }

  /**
   * @param listing the findings the prompt listed by number, each with the round that raised it
   * @param lineResolver the current diff, and the files changed since each prior round
   * @param renameTargets each renamed file's new path, blank for a content-identical rename
   */
  ModelStatusScreen(
      PriorListing listing, DiffLineResolver lineResolver, Map<String, String> renameTargets) {
    this.listing = listing;
    this.lineResolver = lineResolver;
    this.renameTargets = Map.copyOf(renameTargets);
  }

  /** {@code response} with its statuses screened; the same instance when nothing changed. */
  ReviewResponse screen(ReviewResponse response) {
    var screened = screen(response.previousFindingsStatus());
    if (screened.equals(response.previousFindingsStatus())) {
      return response;
    }
    return new ReviewResponse(
        response.findings(),
        screened,
        response.summary(),
        response.closedEarlierRound(),
        response.linkedIssueCriteria());
  }

  /**
   * The statuses that name a listed finding by key, each under that finding's number and each
   * {@code resolved} held to the code. A status counts for the finding its echoed key names (#1322,
   * #1402, {@link EchoedKeys#resolve}): its own number when that finding has the key, otherwise the
   * one listed finding that has it, renumbered to it, unless a status whose number and key agree,
   * or an earlier status read by its key, already names that finding. Any other status is dropped
   * and its finding stays held.
   */
  List<ReviewResponse.PreviousFindingStatus> screen(
      List<ReviewResponse.PreviousFindingStatus> statuses) {
    var previous = listing.findings();
    var named =
        EchoedKeys.resolve(
            previous.stream()
                .map(f -> SuggestionFormatter.findingKey(f.file(), f.line(), f.title()))
                .toList(),
            statuses.stream().map(ReviewResponse.PreviousFindingStatus::id).toList(),
            statuses.stream().map(ReviewResponse.PreviousFindingStatus::key).toList());
    var screened = new ArrayList<ReviewResponse.PreviousFindingStatus>(statuses.size());
    for (var i = 0; i < statuses.size(); i++) {
      var status = renumbered(statuses.get(i), named[i]);
      if (status == null) {
        continue;
      }
      var finding = previous.get(status.id() - 1);
      var round = listing.rounds().get(status.id() - 1);
      screened.add(contradicted(status, finding, round) ? held(status, finding) : status);
    }
    return List.copyOf(screened);
  }

  /**
   * {@code status} under number {@code named}, the finding its key names; {@code null} after
   * logging why when it names none, and logged when its own number was another finding's.
   */
  private ReviewResponse.PreviousFindingStatus renumbered(
      ReviewResponse.PreviousFindingStatus status, int named) {
    if (named == 0) {
      Log.infof(
          "Dropping the '%s' status for previous finding #%d: it echoes key '%s', which no listed"
              + " finding or two of them have, or whose finding another status already names; the"
              + " finding stays held",
          LogSafe.oneLine(status.status()),
          status.id(),
          LogSafe.oneLine(EchoedKeys.normalized(status.key())));
      return null;
    }
    if (named == status.id()) {
      return status;
    }
    var finding = listing.findings().get(named - 1);
    Log.infof(
        "Reading the '%s' status numbered #%d as previous finding #%d's '%s' (%s:%d): it echoes"
            + " that finding's key",
        LogSafe.oneLine(status.status()),
        status.id(),
        named,
        LogSafe.oneLine(finding.title()),
        LogSafe.oneLine(finding.file()),
        finding.line());
    return new ReviewResponse.PreviousFindingStatus(
        named, status.status(), status.note(), status.key());
  }

  /**
   * Whether {@code status} is a {@code resolved} the code does not bear out, for a finding the
   * prior round at {@code round} (newest first) raised.
   */
  private boolean contradicted(
      ReviewResponse.PreviousFindingStatus status, ReviewResponse.Finding finding, int round) {
    if (!STATUS_RESOLVED.equalsIgnoreCase(status.status())) {
      return false;
    }
    if (finding.file() == null) {
      return true;
    }
    return switch (lineResolver.fileChangeSince(round, finding.file())) {
      case CHANGED -> false;
      case UNCHANGED -> !NamedFiles.changedSince(round, finding, lineResolver);
      case UNKNOWN ->
          FollowUpAnalyzer.isStillPresent(finding, lineResolver, renameTargets)
              && !FollowUpAnalyzer.suggestedFixLanded(finding, lineResolver, renameTargets)
              && !NamedFiles.changedSince(round, finding, lineResolver);
    };
  }

  private static ReviewResponse.PreviousFindingStatus held(
      ReviewResponse.PreviousFindingStatus status, ReviewResponse.Finding finding) {
    Log.infof(
        "Holding previous finding #%d '%s' (%s:%d) unresolved: the model reported it resolved,"
            + " but the content check contradicts it (its file is unchanged since the round that"
            + " raised it, or its flagged code is still in the diff and its suggested fix did not"
            + " land)",
        status.id(),
        LogSafe.oneLine(finding.title()),
        LogSafe.oneLine(finding.file()),
        finding.line());
    return new ReviewResponse.PreviousFindingStatus(
        status.id(), STATUS_UNRESOLVED, CONTRADICTED_NOTE, status.key());
  }
}
