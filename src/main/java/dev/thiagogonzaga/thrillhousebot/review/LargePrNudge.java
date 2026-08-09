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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.util.Optional;

/**
 * The opt-in "large PR, nothing inline" note the summary comment carries when {@code
 * thrillhousebot.review.large-pr-nudge.enabled} is on.
 *
 * <p>A large refactor that comes back with no inline finding is either genuinely clean or a shallow
 * pass, and nothing else in the summary distinguishes them: the Risk Assessment table shows four
 * zeroes and the celebration reads as a clean bill of health either way. This renders the
 * distinction the bot cannot make itself, and hands it to the maintainer.
 *
 * <p><b>Why a note and not a second model pass.</b> The two deeper modes the original request
 * imagined already run or already exist as their own command: a large PR is reviewed as
 * token-budgeted batches by {@link DiffBudgetPlanner} plus {@link FindingPipeline}, so map-reduce
 * is the <em>first</em> mode, not an escalation from it, and {@code /improve} is an on-demand
 * whole-PR pass a maintainer can run in one comment. What is left to "escalate" into is a re-run of
 * the same batches against the same model — which would double the AI spend on exactly the largest
 * PRs (up to {@code max-ai-calls} extra calls each) for sampling variance, and would have to push
 * past the verifier, quote validator and hedge demotion that exist to keep false positives out. Two
 * lines of prose cost nothing and put the judgement where it belongs.
 *
 * <p><b>What "no findings" means here.</b> The trigger is "no finding opened an inline thread" —
 * {@link Finding#postsInline()} false for every finding in the built {@link ReviewResult}, which is
 * the set surviving quote validation, the framework filter, dedupe, the verifier and the
 * replied-duplicate drop. That covers both halves of the reported symptom at once: zero findings,
 * and the "all low confidence" round where every finding was routed to "Things to double-check" and
 * the diff itself still shows nothing.
 *
 * <p>Deliberately keyed off <em>this</em> round's findings only, never the previous-findings
 * statuses or the unresolved count. Issue #455 records that a round returning zero findings
 * corrupts the previous-findings context — the bot's own review body is fed back as a pseudo
 * finding and the unresolved count drifts upward — and a zero-finding round is precisely the round
 * this heuristic fires on. Reading either signal here would make the nudge appear or vanish on a
 * phantom. The findings list carries no such defect: it is rebuilt from this round's model calls.
 *
 * <p>Advisory only. The APPROVE gates in {@link VerdictBuilder} are untouched: holding a merge on
 * the suspicion that a clean review might be wrong would block every large PR that really is clean,
 * and the heuristic is nowhere near good enough to earn that.
 *
 * @param enabled master switch
 * @param minFiles changed-file count at or above which the nudge applies; {@code 0} disables this
 *     dimension
 * @param minChangedLines additions + deletions at or above which the nudge applies; {@code 0}
 *     disables this dimension
 */
record LargePrNudge(boolean enabled, int minFiles, int minChangedLines) {

  /** The off switch, used by callers that have no configuration to read (tests, defaults). */
  static final LargePrNudge DISABLED = new LargePrNudge(false, 0, 0);

  /** Section heading of the rendered note; also the marker tests and callers match on. */
  static final String NUDGE_HEADING = "### ⚠️ Large PR — no inline findings";

  static LargePrNudge from(ThrillhouseConfig.LargePrNudgeConfig config) {
    return new LargePrNudge(config.enabled(), config.minFiles(), config.minChangedLines());
  }

  /**
   * The note for this review, or {@link Optional#empty()} when it does not apply — the feature is
   * off, the PR is under both thresholds, or at least one finding opened an inline thread.
   *
   * <p>{@code filesChanged}/{@code additions}/{@code deletions} are the PR-level totals the summary
   * already renders under "Changes Overview" (GitHub's own, or the diff-derived fallback), so the
   * size test and the size the note quotes are the same numbers a reader sees above it.
   */
  Optional<String> render(int filesChanged, int additions, int deletions, ReviewResult result) {
    if (!enabled
        || !overThreshold(filesChanged, additions, deletions)
        || opensInlineThread(result)) {
      return Optional.empty();
    }
    var sb = new StringBuilder();
    sb.append(NUDGE_HEADING).append("\n\n");
    sb.append("This review opened no inline findings across ")
        .append(filesChanged)
        .append(filesChanged == 1 ? " changed file (+" : " changed files (+")
        .append(additions)
        .append(" -")
        .append(deletions)
        .append(").");
    var lowerConfidence = result.doubleCheckFindings().size();
    if (lowerConfidence > 0) {
      sb.append(" The ")
          .append(lowerConfidence)
          .append(
              lowerConfidence == 1 ? " finding it did raise was" : " findings it did raise were")
          .append(
              " too low-confidence to post on the diff (see \"Things to double-check\" above).");
    }
    sb.append(
        " A change this size coming back clean is worth a second look: it may genuinely be"
            + " clean, but it is also what a shallow pass looks like.\n\n");
    sb.append(
        "Re-run `/review` for a fresh pass, or `/improve` for a whole-PR improvement pass, before"
            + " treating this as a clean bill of health. This note is advisory — it does not hold"
            + " approval.\n\n");
    return Optional.of(sb.toString());
  }

  /**
   * Whether the PR is large enough to expect a review to find something. Either dimension suffices
   * — a wide change touching many small files and a narrow change rewriting one huge file are both
   * the shape this heuristic is for — and a non-positive bound switches its dimension off rather
   * than matching every PR.
   */
  private boolean overThreshold(int filesChanged, int additions, int deletions) {
    return (minFiles > 0 && filesChanged >= minFiles)
        || (minChangedLines > 0 && additions + deletions >= minChangedLines);
  }

  /**
   * Whether any finding opened an inline review thread. A finding that only reached the summary's
   * "Things to double-check" section left the diff itself unmarked, which is the reported symptom
   * ("0–1 inline findings on a large refactor"), so it does not suppress the nudge.
   */
  private static boolean opensInlineThread(ReviewResult result) {
    return result.findings().stream().anyMatch(Finding::postsInline);
  }
}
