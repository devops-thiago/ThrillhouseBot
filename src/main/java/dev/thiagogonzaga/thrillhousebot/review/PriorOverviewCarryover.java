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
import io.quarkus.logging.Log;
import java.util.List;

/**
 * Keeps the previous round's overview on a follow-up round whose summary call answered without one
 * (#944).
 *
 * <p>The summary comment is edited in place every round (#868), so a round whose summary came back
 * with neither a "What this PR does" paragraph nor a single file summary rewrote a comment that had
 * both into one that had neither, every Changed Files row reading "Not summarized". On the head the
 * previous round reviewed nothing the overview describes has changed, so that round's overview and
 * file summaries (and its walkthrough diagram, the same description drawn) still hold and are kept.
 *
 * <p>The previous overview is read from the round's persisted response, not from the rendered
 * comment: it is the model's own text, before the renderer escaped it, and the comment's table is
 * already cut to the row cap. It is taken only when the head is provably the one that round
 * reviewed ({@link ReviewContextLoader.ReviewContext#previousRoundHeadUnchanged()}); on a moved
 * head the paragraph could describe code that is gone, and the degraded shape says so honestly.
 *
 * <p>Only the summary call's normal answer is filled this way. The degraded paths (the call cut,
 * skipped or failed) post a banner saying only the finding counts are shown, and filling them would
 * make that banner untrue.
 */
final class PriorOverviewCarryover {

  private PriorOverviewCarryover() {}

  /**
   * {@code current}, or when it carries no overview, a summary holding the previous round's
   * overview, file summaries and diagram beside {@code current}'s counts, gaps and labels. Returned
   * unchanged when it has an overview, when the head moved, or when the previous round had none.
   */
  static ReviewResponse.Summary fill(
      ReviewResponse.Summary current, ReviewContextLoader.ReviewContext ctx) {
    if (hasOverview(current) || !ctx.previousRoundHeadUnchanged()) {
      return current;
    }
    var prior = previousRoundSummary(ctx.priorAiResponses());
    if (!hasOverview(prior)) {
      return current;
    }
    Log.infof(
        "Summary call returned no overview on the head the previous round reviewed; kept that"
            + " round's overview and %d file summary(ies)",
        prior.fileSummaries().size());
    if (current == null) {
      return new ReviewResponse.Summary(
          0,
          0,
          0,
          0,
          0,
          null,
          prior.prPurpose(),
          List.of(),
          List.of(),
          prior.fileSummaries(),
          prior.walkthroughDiagram(),
          List.of());
    }
    return new ReviewResponse.Summary(
        current.totalFindings(),
        current.critical(),
        current.high(),
        current.medium(),
        current.low(),
        current.overallAssessment(),
        prior.prPurpose(),
        current.descriptionGaps(),
        current.suggestedLabels(),
        prior.fileSummaries(),
        prior.walkthroughDiagram(),
        current.addressedGaps());
  }

  /**
   * The summary of the round the head comparison was made against: the same effective previous
   * round {@link ReviewContextLoader} takes the head from, so the overview and the head check name
   * one round. The list holds no null entries (the context copies it); a null list, which the
   * context never passes, reads as no previous round.
   */
  static ReviewResponse.Summary previousRoundSummary(List<ReviewResponse> priorResponses) {
    if (priorResponses == null) {
      return null;
    }
    var index = Math.max(FollowUpAnalyzer.effectivePreviousRoundIndex(priorResponses), 0);
    if (index >= priorResponses.size()) {
      return null;
    }
    return priorResponses.get(index).summary();
  }

  /** Whether a summary carries an overview: a purpose paragraph or at least one file summary. */
  static boolean hasOverview(ReviewResponse.Summary summary) {
    return summary != null
        && ((summary.prPurpose() != null && !summary.prPurpose().isBlank())
            || !summary.fileSummaries().isEmpty());
  }
}
