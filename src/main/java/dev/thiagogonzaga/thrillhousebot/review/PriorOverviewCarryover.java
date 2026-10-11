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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps the previous round's overview on a follow-up round of the same head (#944, #965).
 *
 * <p>The summary comment is edited in place every round (#868), so a round whose summary came back
 * with neither a "What this PR does" paragraph nor a single file summary rewrote a comment that had
 * both into one that had neither, every Changed Files row reading "Not summarized". On the head the
 * previous round reviewed nothing the overview describes has changed, so that round's overview and
 * file summaries (and its walkthrough diagram, the same description drawn) still hold and are kept.
 *
 * <p>Three shapes are filled:
 *
 * <ul>
 *   <li>an answer with no overview at all takes the previous round's overview, file summaries and
 *       diagram whole (#944);
 *   <li>an answer with an overview keeps it, and takes the previous round's summary for each file
 *       it has none for, and the previous diagram when it has none (#964: re-reviews dropped real
 *       file summaries row by row, and the Control-Flow Diagram, while the purpose paragraph
 *       survived);
 *   <li>a degraded round — the summary call skipped at the token ceiling or the call cap, refused,
 *       cut with nothing salvaged, or failed — has no answer, and takes the previous overview whole
 *       (#965). Its banner then says the overview is carried from the previous review of the same
 *       commit and only the counts are new ({@link #carried}), instead of saying only counts are
 *       shown.
 * </ul>
 *
 * <p>The previous overview is read from the round's persisted response, not from the rendered
 * comment: it is the model's own text, before the renderer escaped it, and the comment's table is
 * already cut to the row cap. It is taken only when the head is provably the one that round
 * reviewed ({@link ReviewContextLoader.ReviewContext#previousRoundHeadUnchanged()}); on a moved
 * head the paragraph could describe code that is gone, and the degraded shape says so honestly.
 */
final class PriorOverviewCarryover {

  private PriorOverviewCarryover() {}

  /**
   * {@code current} with the previous round's overview filled in where it has none, on the head
   * that round reviewed: whole when {@code current} is {@code null} or carries no overview, else
   * file by file and for the diagram. Returned unchanged when the head moved, when the previous
   * round had no overview, or when nothing is missing.
   */
  static ReviewResponse.Summary fill(
      ReviewResponse.Summary current, ReviewContextLoader.ReviewContext ctx) {
    if (!ctx.previousRoundHeadUnchanged()) {
      return current;
    }
    var prior = previousRoundSummary(ctx.priorAiResponses());
    if (!hasOverview(prior)) {
      return current;
    }
    if (hasOverview(current)) {
      return supplemented(current, prior);
    }
    Log.infof(
        "Summary has no overview on the head the previous round reviewed; kept that round's"
            + " overview and %d file summary(ies)",
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
    return withOverview(
        current, prior.prPurpose(), prior.fileSummaries(), prior.walkthroughDiagram());
  }

  /**
   * {@code current} plus the previous round's summary for each file {@code current} has no
   * non-blank summary for, and the previous diagram when {@code current} has none; {@code current}
   * itself when it lacks neither.
   */
  private static ReviewResponse.Summary supplemented(
      ReviewResponse.Summary current, ReviewResponse.Summary prior) {
    // Only the current answer's real summaries stay; a blank one gives way to the earlier entry.
    var files =
        new ArrayList<>(
            current.fileSummaries().stream().filter(f -> !isBlank(f.summary())).toList());
    var covered = new CoveredPaths();
    files.forEach(f -> covered.add(f.path()));
    var earlier =
        prior.fileSummaries().stream().filter(f -> isUsable(f) && covered.add(f.path())).toList();
    files.addAll(earlier);
    var added = earlier.size();
    var keepDiagram = isBlank(current.walkthroughDiagram()) && !isBlank(prior.walkthroughDiagram());
    if (added == 0 && !keepDiagram) {
      return current;
    }
    Log.infof(
        "Summary on the head the previous round reviewed kept %d earlier file summary(ies) and %d"
            + " earlier diagram(s)",
        added, keepDiagram ? 1 : 0);
    return withOverview(
        current,
        current.prPurpose(),
        files,
        keepDiagram ? prior.walkthroughDiagram() : current.walkthroughDiagram());
  }

  /**
   * The paths a file list already summarizes, matched as {@link FilePaths#same} matches them (one
   * path a suffix of the other at a directory boundary), indexed by file name so a lookup weighs
   * only the paths that could match rather than the whole list.
   */
  private static final class CoveredPaths {

    private final Map<String, List<String>> byFileName = new HashMap<>();

    /** Records {@code path}; {@code false} when a path it is the same as is already recorded. */
    boolean add(String path) {
      var name = path.substring(path.lastIndexOf('/') + 1);
      var same = byFileName.computeIfAbsent(name, k -> new ArrayList<>());
      if (same.stream().anyMatch(p -> FilePaths.same(p, path))) {
        return false;
      }
      same.add(path);
      return true;
    }
  }

  /**
   * The summary a degraded round persists (#965): the previous round's overview on the head that
   * round reviewed, under counts taken from the findings this round keeps, so the stored summary
   * does not say zero findings over a round that has them; {@code null}, the counts-only shape,
   * when there is no overview to carry.
   */
  static ReviewResponse.Summary fillDegraded(
      ReviewContextLoader.ReviewContext ctx, List<ReviewResponse.Finding> findings) {
    var counts = new int[RiskLevel.values().length];
    findings.forEach(f -> counts[RiskLevel.fromString(f.risk()).ordinal()]++);
    var countsOnly =
        new ReviewResponse.Summary(
            findings.size(),
            counts[RiskLevel.CRITICAL.ordinal()],
            counts[RiskLevel.HIGH.ordinal()],
            counts[RiskLevel.MEDIUM.ordinal()],
            counts[RiskLevel.LOW.ordinal()],
            null,
            null,
            List.of());
    var filled = fill(countsOnly, ctx);
    return filled == countsOnly ? null : filled;
  }

  /** Whether an earlier file summary names a path and says something. */
  private static boolean isUsable(ReviewResponse.FileSummary summary) {
    return !isBlank(summary.path()) && !isBlank(summary.summary());
  }

  private static ReviewResponse.Summary withOverview(
      ReviewResponse.Summary current,
      String purpose,
      List<ReviewResponse.FileSummary> files,
      String diagram) {
    return new ReviewResponse.Summary(
        current.totalFindings(),
        current.critical(),
        current.high(),
        current.medium(),
        current.low(),
        current.overallAssessment(),
        purpose,
        current.descriptionGaps(),
        current.suggestedLabels(),
        files,
        diagram,
        current.addressedGaps());
  }

  /**
   * Whether {@code summary}'s overview is the previous round's, carried whole on the head that
   * round reviewed: the same purpose paragraph and the same file summaries. The degraded paths'
   * banner reads this to say the overview is carried rather than that only counts are shown (#965).
   */
  static boolean carried(ReviewResponse.Summary summary, ReviewContextLoader.ReviewContext ctx) {
    if (!ctx.previousRoundHeadUnchanged() || !hasOverview(summary)) {
      return false;
    }
    var prior = previousRoundSummary(ctx.priorAiResponses());
    return hasOverview(prior)
        && Objects.equals(summary.prPurpose(), prior.prPurpose())
        && summary.fileSummaries().equals(prior.fileSummaries());
  }

  private static boolean isBlank(String text) {
    return text == null || text.isBlank();
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
