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
import java.util.List;
import java.util.Optional;

/**
 * Renders the opt-in "what moved since the last pass" section of a follow-up review outcome.
 *
 * <p>The section is separate from the persistent PR summary and carries no summary marker: that
 * marker would make {@code ReviewContextLoader} mistake it for the editable PR summary (#868). Its
 * counts come from the round's reconciled statuses, with closed findings named by locator and title
 * through {@link ClosedFindingNames}. Declined and superseded statuses are not fixes; an
 * earlier-round close recorded on this round is counted and named once (#1066).
 *
 * <p>Only a new finding or a closure earns a delta. Findings that merely stay open never add a
 * section on their own. A superseded finding refreshing the PR summary does not suppress the
 * section (#927); the publisher suppresses it on a successful explicit {@code /summary} command.
 * Coverage and verification disclosures stay with the counts, and the review keeps its actual
 * verdict and delivery fallback instead of making a separate delta write.
 */
final class FollowUpDeltaSummary {

  /**
   * First line of the delta section. Distinct from {@link PrSummaryGenerator#SUMMARY_HEADING} on
   * purpose — see the class javadoc.
   */
  static final String DELTA_HEADING = "## 🤖 ThrillhouseBot — changes since the last review";

  private static final String NEW_COUNT = "- **New findings this round:** ";
  private static final String RESOLVED_COUNT = "- **Previous findings resolved:** ";
  private static final String OPEN_COUNT = "- **Previous findings still open:** ";

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
    var newFindings = newFindingCount(result);
    var resolved = result.resolvedPreviousCount();
    if (newFindings == 0 && resolved == 0) {
      return Optional.empty();
    }
    var body =
        DELTA_HEADING
            + "\n\n"
            + NEW_COUNT
            + newFindings
            + "\n"
            + RESOLVED_COUNT
            + resolved
            + "\n"
            + ClosedFindingNames.bulletList(result, previousFindings, "  ")
            + OPEN_COUNT
            + result.unresolvedPreviousCount()
            + "\n";
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
    at += 2;
    while (at < lines.size() && lines.get(at).startsWith("  - ")) {
      at++;
    }
    return at < lines.size() && hasCount(lines.get(at), OPEN_COUNT) ? at + 1 : start;
  }

  private static boolean hasCount(String line, String prefix) {
    return line.startsWith(prefix)
        && line.length() > prefix.length()
        && line.substring(prefix.length()).chars().allMatch(Character::isDigit);
  }

  /**
   * The round's findings that are new: those that are not an earlier finding still open, raised
   * again. A double-check item replaced by this round's finding is listed among the still-open
   * findings as that finding (#969), and an exact re-raise is the earlier finding by identity
   * ({@link PrSummaryGenerator#reRaises}), so neither is reported as new; both are counted under
   * "still open".
   */
  static int newFindingCount(ReviewResult result) {
    var open = result.openPreviousFindings();
    return (int)
        result.findings().stream()
            .filter(f -> open.stream().noneMatch(o -> PrSummaryGenerator.reRaises(f, o)))
            .count();
  }
}
