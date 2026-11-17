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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders the opt-in "what moved since the last pass" section of a follow-up review outcome.
 *
 * <p>The section is separate from the persistent PR summary and carries no summary marker: that
 * marker would make {@code ReviewContextLoader} mistake it for the editable PR summary (#868). Its
 * counts come from the round's reconciled statuses, the same counts the summary's status table and
 * the check run read ({@link ReviewResult#previousCount}), with closed findings named by locator
 * and title through {@link ClosedFindingNames}. Declined and superseded statuses are not fixes; a
 * superseded finding is counted and named on its own line (#1318), so resolved, superseded and
 * still open add up to the findings carried in less the declined ones. An earlier-round close
 * recorded on this round is counted and named once (#1066).
 *
 * <p>A thread this round posted for a finding that was only a "Things to double-check" item before
 * is counted under still open, as the item it carries (#969), so the section names it on a line of
 * its own; a new finding that went only under "Things to double-check" is counted as new, and the
 * new count says how many of those have no thread (#1312). Neither changes a count.
 *
 * <p>Only a new finding, a closure or such a thread earns a delta. Findings that merely stay open
 * never add a section on their own. A superseded finding refreshing the PR summary does not
 * suppress the section (#927); the publisher suppresses it on a successful explicit {@code
 * /summary} command. Coverage and verification disclosures stay with the counts, and the review
 * keeps its actual verdict and delivery fallback instead of making a separate delta write.
 */
final class FollowUpDeltaSummary {

  /**
   * First line of the delta section. Distinct from {@link PrSummaryGenerator#SUMMARY_HEADING} on
   * purpose — see the class javadoc.
   */
  static final String DELTA_HEADING = "## 🤖 ThrillhouseBot — changes since the last review";

  private static final String NEW_COUNT = "- **New findings this round:** ";
  private static final String RESOLVED_COUNT = "- **Previous findings resolved:** ";
  private static final String SUPERSEDED_COUNT =
      "- **Previous findings superseded (targeted code left the diff):** ";
  private static final String OPEN_COUNT = "- **Previous findings still open:** ";
  private static final String PROMOTED_COUNT =
      "- **Earlier double-check items now posted as threads:** ";
  private static final String UNTHREADED_SUFFIX = " under Things to double-check)";

  /** A count, and on the new-findings line how many of them have no thread (#1312). */
  private static final Pattern COUNT =
      Pattern.compile("\\d{1,9}(?: \\(\\d{1,9}" + Pattern.quote(UNTHREADED_SUFFIX) + ")?");

  private static final String NAME_INDENT = "  ";

  private FollowUpDeltaSummary() {}

  /**
   * The delta section body, or {@link Optional#empty()} when this round has no delta to report.
   *
   * <p>"Delta" means the round moved something: it raised at least one finding, or it closed at
   * least one previous finding. Previous findings that merely stayed open are reported inside a
   * section that renders for one of those reasons, but never trigger one on their own — a pass that
   * re-states the same open count is exactly the per-push noise this feature must not add, and it
   * is also the shape a miscounted carry-over takes, which is better left un-amplified.
   */
  static Optional<String> render(
      ReviewResult result, List<ReviewResponse.Finding> previousFindings) {
    var newFindings = PrSummaryGenerator.currentState(result).newFindings();
    var resolved = result.resolvedPreviousCount();
    var superseded = result.supersededPreviousCount();
    var promoted = promotedThreads(result, previousFindings);
    if (newFindings.isEmpty() && resolved == 0 && superseded == 0 && promoted.isEmpty()) {
      return Optional.empty();
    }
    var body =
        DELTA_HEADING
            + "\n\n"
            + NEW_COUNT
            + newFindings.size()
            + unthreadedClause(newFindings)
            + "\n"
            + RESOLVED_COUNT
            + resolved
            + "\n"
            + ClosedFindingNames.bulletList(
                result, previousFindings, NAME_INDENT, ClosedFindingNames.RESOLVED)
            + (superseded == 0
                ? ""
                : SUPERSEDED_COUNT
                    + superseded
                    + "\n"
                    + ClosedFindingNames.bulletList(
                        result, previousFindings, NAME_INDENT, ClosedFindingNames.SUPERSEDED))
            + OPEN_COUNT
            + result.unresolvedPreviousCount()
            + "\n"
            + promotedLines(promoted);
    var coverageDisclosure =
        ReviewResult.truncationDisclosure(result.omittedFiles(), result.truncation());
    body +=
        // Counts cover the reviewed portion only. Preserve named gaps and clipped files (F8).
        coverageDisclosure;
    // The gap disclosure already includes verification; add its brief only without gaps (#623).
    if (result.truncation().verification().disclosed() && coverageDisclosure.isEmpty()) {
      body += "\n\n> ⚠️ " + ReviewResult.verificationBrief(result.truncation().verification());
    }
    return Optional.of(body);
  }

  /** End of this producer's counts and closed-name section, never a following findings list. */
  static int endOfSection(List<String> lines, int start) {
    if (!lines.get(start).strip().equals(DELTA_HEADING)) {
      return start;
    }
    var at = start + 1;
    while (at < lines.size() && lines.get(at).isBlank()) {
      at++;
    }
    if (at + 1 >= lines.size()
        || !hasCount(lines.get(at), NEW_COUNT)
        || !hasCount(lines.get(at + 1), RESOLVED_COUNT)) {
      return start;
    }
    at = afterNames(lines, at + 2);
    if (at < lines.size() && hasCount(lines.get(at), SUPERSEDED_COUNT)) {
      at = afterNames(lines, at + 1);
    }
    if (at >= lines.size() || !hasCount(lines.get(at), OPEN_COUNT)) {
      return start;
    }
    at++;
    return at < lines.size() && hasCount(lines.get(at), PROMOTED_COUNT)
        ? afterNames(lines, at + 1)
        : at;
  }

  /** The first line from {@code at} that is not a named closure under a count. */
  private static int afterNames(List<String> lines, int at) {
    var next = at;
    while (next < lines.size() && lines.get(next).startsWith(NAME_INDENT + "- ")) {
      next++;
    }
    return next;
  }

  private static boolean hasCount(String line, String prefix) {
    return line.startsWith(prefix) && COUNT.matcher(line.substring(prefix.length())).matches();
  }

  /** {@code " (N under Things to double-check)"} for the new findings with no thread, or "". */
  private static String unthreadedClause(List<Finding> newFindings) {
    var unthreaded = newFindings.stream().filter(f -> !f.postsInline()).count();
    return unthreaded == 0 ? "" : " (" + unthreaded + UNTHREADED_SUFFIX;
  }

  /** The line counting {@code promoted} and the bullets naming them, or "" when there are none. */
  private static String promotedLines(List<Finding> promoted) {
    return promoted.isEmpty()
        ? ""
        : PROMOTED_COUNT
            + promoted.size()
            + "\n"
            + ClosedFindingNames.bulletList(promoted, NAME_INDENT);
  }

  /**
   * This round's threads that carry an earlier "Things to double-check" item (#1312): for each
   * previous finding the round holds open as replaced by one of its own findings ({@link
   * FollowUpAnalyzer#REPLACED_NOTE}), the finding that replaced it, the first of the round's
   * findings that {@link FollowUpAnalyzer#replacesUnthreaded replaces} it as {@code
   * FollowUpAnalyzer.replacedThisRound} picks it, when that finding posts inline. Each thread is
   * named once, however many items it carries.
   */
  static List<Finding> promotedThreads(
      ReviewResult result, List<ReviewResponse.Finding> previousFindings) {
    var previous = previousFindings == null ? List.<ReviewResponse.Finding>of() : previousFindings;
    Set<Finding> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    var promoted = new ArrayList<Finding>();
    for (var status : result.previousStatuses()) {
      var prior = replacedPrior(status, previous);
      if (prior == null) {
        continue;
      }
      var item = Finding.fromAiResponse(prior);
      result.findings().stream()
          .filter(finding -> FollowUpAnalyzer.replacesUnthreaded(finding, item))
          .findFirst()
          .filter(Finding::postsInline)
          .filter(seen::add)
          .ifPresent(promoted::add);
    }
    return promoted;
  }

  /** The item {@code status} holds open as replaced this round, or {@code null}. */
  private static ReviewResponse.Finding replacedPrior(
      ReviewResult.PreviousFindingStatus status, List<ReviewResponse.Finding> previous) {
    if (!FollowUpAnalyzer.REPLACED_NOTE.equals(status.note())) {
      return null;
    }
    if (status.fromEarlierRound()) {
      return status.earlierRound().finding();
    }
    return status.id() >= 1 && status.id() <= previous.size()
        ? previous.get(status.id() - 1)
        : null;
  }

  /**
   * The round's findings that are new: those the summary does not list as open since an earlier
   * review ({@link PrSummaryGenerator#currentState}). A double-check item replaced by this round's
   * finding is listed among the still-open findings as that finding (#969), and an exact re-raise
   * is the earlier finding by identity ({@link PrSummaryGenerator#reRaises}), so neither is
   * reported as new; both are counted under "still open". The check run counts the same set
   * (#1199).
   */
  static int newFindingCount(ReviewResult result) {
    return PrSummaryGenerator.currentState(result).newFindings().size();
  }
}
