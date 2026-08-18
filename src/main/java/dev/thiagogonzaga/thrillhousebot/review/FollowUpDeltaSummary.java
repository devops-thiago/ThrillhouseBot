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
 * Renders the short "what moved since the last pass" comment posted on a follow-up review when
 * {@code thrillhousebot.review.follow-up-summary.enabled} is on.
 *
 * <p>Deliberately <em>not</em> a second render method on {@link PrSummaryGenerator}: that class
 * assembles the 500-line first-review summary from the model's summary object, the PR-level diff
 * stats, the walkthrough table and the optional diagram, and is gated on its own config. This
 * comment needs none of that — it is a pure function of the {@link ReviewResult} counts and the
 * previous round's findings it names them from — so folding it in would mean threading unrelated
 * inputs through, and would put two different comment shapes behind one entry point. Keeping it
 * separate also keeps {@link PrSummaryGenerator#SUMMARY_HEADING} out of the rendered body, which
 * matters: that heading is the marker {@code ReviewContextLoader.isBotSummaryComment} uses to
 * recognize the bot's summary, and a delta comment carrying it would be mistaken for one — edited
 * in place on a superseded round, and counted as an already-posted summary when deciding whether a
 * review is the first.
 *
 * <p>Three counts are rendered, all sourced from the statuses the follow-up pipeline already
 * produced rather than recomputed here: findings raised this round, previous findings the round
 * closed, and previous findings still open. The closed ones are also named, one {@code path:line} —
 * title line each ({@link ClosedFindingNames}). A {@code justified} status (declined by a
 * maintainer) is in none of them — it is neither newly fixed nor still open — and {@code
 * superseded} is not counted either: it is an auto-close because the targeted code left the diff,
 * not something the round fixed. A superseded round also re-posts the full summary, and the caller
 * skips this comment whenever that re-post lands.
 *
 * <p>The counts are only as good as the previous-finding statuses handed to them: issue #455
 * records that a round returning zero findings corrupts the previous-findings context, which can
 * both drop a real finding out of tracking and inflate the still-open count. That defect is tracked
 * separately; the guard below limits the blast radius here, since a round whose only "movement" is
 * a phantom carry-over renders nothing at all.
 */
final class FollowUpDeltaSummary {

  /**
   * First line of the delta comment. Distinct from {@link PrSummaryGenerator#SUMMARY_HEADING} on
   * purpose — see the class javadoc.
   */
  static final String DELTA_HEADING = "## 🤖 ThrillhouseBot — changes since the last review";

  private FollowUpDeltaSummary() {}

  /**
   * The delta comment body, or {@link Optional#empty()} when this round has no delta to report.
   *
   * <p>"Delta" means the round moved something: it raised at least one finding, or it closed at
   * least one previous finding. Previous findings that merely stayed open are reported inside a
   * comment that posts for one of those reasons, but never trigger one on their own — a pass that
   * re-states the same open count is exactly the per-push noise this feature must not add, and it
   * is also the shape a miscounted carry-over takes, which is better left un-amplified.
   */
  static Optional<String> render(
      ReviewResult result, List<ReviewResponse.Finding> previousFindings) {
    var newFindings = result.totalFindings();
    var resolved = result.resolvedPreviousCount();
    if (newFindings == 0 && resolved == 0) {
      return Optional.empty();
    }
    var coverageDisclosure =
        ReviewResult.truncationDisclosure(result.omittedFiles(), result.truncation());
    var body =
        DELTA_HEADING
            + "\n\n"
            + "- **New findings this round:** "
            + newFindings
            + "\n"
            + "- **Previous findings resolved:** "
            + resolved
            + "\n"
            + ClosedFindingNames.bulletList(result, previousFindings, "  ")
            + "- **Previous findings still open:** "
            + result.unresolvedPreviousCount()
            + "\n"
            // Same partial-coverage wording the on-demand commands use: these counts cover only the
            // reviewed portion of a truncated diff, so the comment has to say so. Pass the
            // truncation detail, not just the count — on the budgeted path the count folds in
            // clipped files, which are partially analyzed, not omitted (F8).
            + coverageDisclosure;
    // The delta's counts must not read as fully screened when verification did not cover them
    // (#623) — same disclosure the posted review and check run carry, in its one-sentence form.
    // Only when no coverage disclosure rendered above: that one folds the verification clause in
    // alongside the file gaps, and the same fact must not stack twice on one comment.
    if (result.truncation().verification().disclosed() && coverageDisclosure.isEmpty()) {
      body += "\n\n> ⚠️ " + ReviewResult.verificationBrief(result.truncation().verification());
    }
    return Optional.of(body);
  }
}
