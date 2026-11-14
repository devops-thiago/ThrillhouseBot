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
import java.util.Collections;
import java.util.List;

/**
 * The prior findings a follow-up round lists to the model by number, each beside the prior round
 * that raised it (#1319): first the effective previous round's findings, ids {@code 1..effective}
 * as always, then the findings older rounds left open whose file changed since the round that
 * raised them ({@link FollowUpAnalyzer#priorListing}), numbered on from there. A finding whose file
 * is unchanged is still open without asking, so it is not listed.
 *
 * <p>Only ids up to {@code effective} name the effective round's findings, the id space every
 * stored round and every id-keyed consumer reads. A status on a later id is about an older round's
 * finding: it is taken out of the round's own statuses ({@link #ofEffectiveRound}) and handed to
 * the approve backstop by the finding's key under {@link FollowUpAnalyzer#EARLIER_ROUND_ID} ({@link
 * #earlierRoundResolutions}), which closes the finding there and records the close with the round,
 * as it records any earlier round's close.
 *
 * @param findings the listed findings, in listing order: entry {@code i} is listed as id {@code i +
 *     1}
 * @param rounds the position among the prior rounds (newest first) of the round that raised each
 *     entry, or {@code -1} when it is not known
 * @param effective how many leading entries are the effective previous round's
 */
record PriorListing(List<ReviewResponse.Finding> findings, List<Integer> rounds, int effective) {

  private static final String STATUS_RESOLVED = "resolved";

  PriorListing {
    findings = List.copyOf(findings);
    rounds = List.copyOf(rounds);
  }

  /** {@code previous} listed alone, raised by a round that is not known. */
  static PriorListing of(List<ReviewResponse.Finding> previous) {
    return new PriorListing(previous, Collections.nCopies(previous.size(), -1), previous.size());
  }

  /** Whether {@code id} lists an older round's finding rather than one of the effective round's. */
  boolean listsEarlier(int id) {
    return id > effective && id <= findings.size();
  }

  /** {@code statuses} without those on an older round's finding; the same list when none is. */
  List<ReviewResponse.PreviousFindingStatus> ofEffectiveRound(
      List<ReviewResponse.PreviousFindingStatus> statuses) {
    if (statuses.stream().noneMatch(status -> listsEarlier(status.id()))) {
      return statuses;
    }
    return statuses.stream().filter(status -> !listsEarlier(status.id())).toList();
  }

  /**
   * The {@code resolved} statuses on an older round's finding, each moved to {@link
   * FollowUpAnalyzer#EARLIER_ROUND_ID} and carrying that finding's key, so the backstop can find
   * the finding it names without an id into a round it does not report on.
   */
  List<ReviewResponse.PreviousFindingStatus> earlierRoundResolutions(
      List<ReviewResponse.PreviousFindingStatus> statuses) {
    return statuses.stream()
        .filter(status -> listsEarlier(status.id()))
        .filter(status -> STATUS_RESOLVED.equalsIgnoreCase(status.status()))
        .map(
            status -> {
              var finding = findings.get(status.id() - 1);
              return new ReviewResponse.PreviousFindingStatus(
                  FollowUpAnalyzer.EARLIER_ROUND_ID,
                  STATUS_RESOLVED,
                  status.note(),
                  SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title()));
            })
        .toList();
  }
}
