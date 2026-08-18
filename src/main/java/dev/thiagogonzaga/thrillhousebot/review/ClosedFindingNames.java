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
import java.util.List;

/**
 * Names the previous findings a follow-up round closed, for every surface that reports the closure.
 *
 * <p>Each entry carries the {@code path:line} locator and the title — the same two identifiers an
 * {@code @thrillhousebot resolved} directive uses to name a finding (#548), so a maintainer can
 * read the match straight off the surface (#714). Without them a round published a bare count and
 * nothing anywhere said <em>which</em> finding closed: the maintainer had to diff the PR's thread
 * state across rounds to find out, and one who named several findings in a single directive could
 * not tell which the reviewer matched and which it silently skipped — matching is by {@code
 * path:line} plus title and the guards reject several spellings, so a skip is a real outcome.
 *
 * <p>Shared by the opt-in follow-up delta comment and the review body because the delta alone did
 * not reach a default install: it is gated on {@code thrillhousebot.review.follow-up-summary
 * .enabled}, which defaults to false, and the other naming surface — a reply on the closed
 * finding's thread — does not exist for a finding published with no thread at all (#712). That
 * threadless finding is exactly the one the clearing directive is the only available action for
 * (#709), so it is where the attribution is needed most and where it was missing (#737). The review
 * body posts on every round with something to say and is behind no flag, so the names ride it.
 *
 * <p>Every finding the {@code resolved} count covers is named, not only the directive-cleared ones:
 * the list has to add up to the integer it explains, and a fix that landed is just as unnamed
 * without it. Ids are 1-based positions in the previous round, so an id outside that list names
 * nothing and is skipped rather than guessed at — the count stays authoritative either way.
 */
final class ClosedFindingNames {

  private ClosedFindingNames() {}

  /**
   * How many closed findings a surface names before rolling the rest up as a count. Matches the
   * bound {@code ReviewResult.nameList} puts on the coverage disclosure's file names, for the same
   * reason: one comment carries all of this, and a PR can close many findings in one round.
   */
  private static final int NAMED_LIMIT = 10;

  /**
   * Opening words of the review-body section. Kept as a constant because {@code
   * FollowUpAnalyzer.isSelfAuthoredStatusBody} recognizes bodies by it: this section is the bot's
   * own prose about findings it has just closed, so a later round must never read it back as
   * "issues flagged in the previous review" and re-open them (#455).
   */
  static final String REVIEW_BODY_LEAD_IN = "ThrillhouseBot closed ";

  /**
   * The review-body section naming this round's closures, or an empty string when nothing can be
   * named. Renders the authoritative {@link ReviewResult#resolvedPreviousCount()} above the names,
   * the same order the delta comment puts them in.
   */
  static String reviewBodySection(
      ReviewResult result, List<ReviewResponse.Finding> previousFindings) {
    var bullets = bulletList(result, previousFindings, "");
    if (bullets.isEmpty()) {
      return "";
    }
    return REVIEW_BODY_LEAD_IN
        + result.resolvedPreviousCount()
        + " previous finding(s) this round:\n\n"
        + bullets;
  }

  /**
   * The bullet list naming each finding the {@code resolved} count covers, one {@code path:line} —
   * title line each at {@code indent}, or an empty string when none can be named.
   */
  static String bulletList(
      ReviewResult result, List<ReviewResponse.Finding> previousFindings, String indent) {
    if (previousFindings == null || previousFindings.isEmpty()) {
      return "";
    }
    var named = new ArrayList<String>();
    for (var status : result.previousStatuses()) {
      if (!"resolved".equalsIgnoreCase(status.status())
          || status.id() < 1
          || status.id() > previousFindings.size()) {
        continue;
      }
      named.add(describe(previousFindings.get(status.id() - 1)));
    }
    if (named.isEmpty()) {
      return "";
    }
    var sb = new StringBuilder();
    for (var entry : named.subList(0, Math.min(named.size(), NAMED_LIMIT))) {
      sb.append(indent).append("- ").append(entry).append("\n");
    }
    if (named.size() > NAMED_LIMIT) {
      sb.append(indent).append("- …and ").append(named.size() - NAMED_LIMIT).append(" more\n");
    }
    return sb.toString();
  }

  /**
   * One closed finding as {@code `path:line` — title}. Both halves go through {@link MarkdownSafe}:
   * they are model-authored text spliced into a list item, and a raw newline or backtick would
   * restructure the surface. A finding with no title renders as the bare locator rather than a
   * dangling dash — the locator alone still identifies it.
   */
  private static String describe(ReviewResponse.Finding finding) {
    var locator = MarkdownSafe.inlineCode(finding.file() + ":" + finding.line());
    var title = MarkdownSafe.inline(finding.title());
    return title.isBlank() ? "`" + locator + "`" : "`" + locator + "` — " + title;
  }
}
