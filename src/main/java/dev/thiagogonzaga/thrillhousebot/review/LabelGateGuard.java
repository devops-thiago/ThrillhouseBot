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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Stands down an automatic run that a {@code webhook.triggers} label gate rejects on the labels the
 * pull request carries now (#1021): Dependabot labels a pull request a second or two after opening
 * it, and a label can also be added or removed while the review runs. Never for a manual {@code
 * /review}, which the gates do not apply to, and no read is made when no label gate is configured.
 * Unreadable labels let the run post: the webhook already gated the dispatch.
 */
@ApplicationScoped
public class LabelGateGuard implements PrePublishGuard {

  private final PullRequestLabelGate labelGate;

  @Inject
  public LabelGateGuard(PullRequestLabelGate labelGate) {
    this.labelGate = labelGate;
  }

  @Override
  public Optional<StandDown> check(Review review) {
    var req = review.req();
    if (req.isManualTrigger() || !labelGate.configured()) {
      return Optional.empty();
    }
    return labelGate
        .currentLabels(review.auth(), req.owner(), req.repo(), req.prNumber())
        .flatMap(labelGate::skipFor)
        .map(
            skip ->
                new StandDown(
                    skip.reason(),
                    skip.detail() + " — not posting the review of " + req.commitSha(),
                    PullRequestLabelGate.CHECK_TITLE,
                    PullRequestLabelGate.checkSummary(skip),
                    "Skipped: a label gate rejects the pull request",
                    "label-gated"));
  }
}
