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
import java.util.Locale;
import java.util.Map;

/**
 * Holds the model's {@code previous_findings_status} entries to the findings they name and to the
 * code (#1322), as soon as a review call returns and before anything stores or reads them.
 *
 * <ol>
 *   <li>Each entry must echo the key of the finding listed under its id ({@link
 *       SuggestionFormatter#findingKey}, the key the thread marker carries). An entry with no key,
 *       a key of another finding or round, or an id that lists nothing is dropped and logged. The
 *       finding is then unreported, so the approve backstop holds it as it holds any finding the
 *       model left out: a swapped id can neither close nor keep open the wrong finding.
 *   <li>A {@code resolved} entry stands only when the finding's flagged code is no longer in the
 *       diff ({@link FollowUpAnalyzer#isStillPresent}) or its suggested fix landed beside it
 *       ({@link FollowUpAnalyzer#suggestedFixLanded}). Otherwise it is rewritten to {@code
 *       unresolved} under {@link #CONTRADICTED_NOTE} and the contradiction is logged. A finding
 *       with no file cannot be placed, so its {@code resolved} is held too.
 * </ol>
 *
 * <p>Stored rounds are never screened: they carry no key, and the replay that reads them trusts
 * what was screened when they were written.
 */
final class ModelStatusScreen {

  /** The note a {@code resolved} the code contradicts is held {@code unresolved} under. */
  static final String CONTRADICTED_NOTE =
      "reported resolved, but the flagged code is still in the diff and the suggested fix is not";

  private static final String STATUS_RESOLVED = "resolved";
  private static final String STATUS_UNRESOLVED = "unresolved";

  private final List<ReviewResponse.Finding> previous;
  private final DiffLineResolver lineResolver;
  private final Map<String, String> renameTargets;

  /**
   * @param previous the findings the prompt listed by number, in order (the effective previous
   *     round, {@link FollowUpAnalyzer#effectivePreviousFindings})
   * @param lineResolver the current diff
   * @param renameTargets each renamed file's new path, blank for a content-identical rename
   */
  ModelStatusScreen(
      List<ReviewResponse.Finding> previous,
      DiffLineResolver lineResolver,
      Map<String, String> renameTargets) {
    this.previous = List.copyOf(previous);
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

  /** The statuses that name their finding by key, each {@code resolved} held to the code. */
  List<ReviewResponse.PreviousFindingStatus> screen(
      List<ReviewResponse.PreviousFindingStatus> statuses) {
    var screened = new ArrayList<ReviewResponse.PreviousFindingStatus>(statuses.size());
    for (var status : statuses) {
      var finding = listedFinding(status);
      if (finding == null) {
        continue;
      }
      screened.add(contradicted(status, finding) ? held(status, finding) : status);
    }
    return List.copyOf(screened);
  }

  /** The finding {@code status} names by id and echoed key, or {@code null} after logging why. */
  private ReviewResponse.Finding listedFinding(ReviewResponse.PreviousFindingStatus status) {
    var id = status.id();
    if (id < 1 || id > previous.size()) {
      Log.infof(
          "Dropping the status for previous finding #%d: no finding is listed under that id", id);
      return null;
    }
    var finding = previous.get(id - 1);
    var expected = SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title());
    var echoed = status.key() == null ? "" : status.key().strip().toLowerCase(Locale.ROOT);
    if (!expected.equals(echoed)) {
      Log.infof(
          "Dropping the '%s' status for previous finding #%d '%s' (%s:%d): it echoes key '%s', not"
              + " the finding's %s; the finding stays held",
          LogSafe.oneLine(status.status()),
          id,
          LogSafe.oneLine(finding.title()),
          LogSafe.oneLine(finding.file()),
          finding.line(),
          LogSafe.oneLine(echoed),
          LogSafe.oneLine(expected));
      return null;
    }
    return finding;
  }

  /** Whether {@code status} is a {@code resolved} the code does not bear out. */
  private boolean contradicted(
      ReviewResponse.PreviousFindingStatus status, ReviewResponse.Finding finding) {
    if (!STATUS_RESOLVED.equalsIgnoreCase(status.status())) {
      return false;
    }
    if (finding.file() == null) {
      return true;
    }
    return FollowUpAnalyzer.isStillPresent(finding, lineResolver, renameTargets)
        && !FollowUpAnalyzer.suggestedFixLanded(finding, lineResolver, renameTargets);
  }

  private static ReviewResponse.PreviousFindingStatus held(
      ReviewResponse.PreviousFindingStatus status, ReviewResponse.Finding finding) {
    Log.infof(
        "Holding previous finding #%d '%s' (%s:%d) unresolved: the model reported it resolved,"
            + " but its flagged code is still in the diff and its suggested fix did not land",
        status.id(),
        LogSafe.oneLine(finding.title()),
        LogSafe.oneLine(finding.file()),
        finding.line());
    return new ReviewResponse.PreviousFindingStatus(
        status.id(), STATUS_UNRESOLVED, CONTRADICTED_NOTE, status.key());
  }
}
