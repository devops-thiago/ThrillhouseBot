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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The verdicts one review's calls returned on the linked issue's acceptance criteria (#1200),
 * gathered as each call returns: a multi-call review's batches record theirs in parallel. Which
 * criterion a verdict names, and how the batches' verdicts combine, is decided where the criteria
 * are known ({@link LinkedIssueCriteria#unmet}).
 */
public final class LinkedIssueVerdicts {

  /** Keeps nothing, for the shared {@link ReviewEvidence#NONE}: no review may read another's. */
  static final LinkedIssueVerdicts DISCARD = new LinkedIssueVerdicts(false);

  private final boolean keeps;
  private final List<ReviewResponse.CriterionVerdict> verdicts = new CopyOnWriteArrayList<>();

  /** An empty accumulator for one review. */
  public LinkedIssueVerdicts() {
    this(true);
  }

  private LinkedIssueVerdicts(boolean keeps) {
    this.keeps = keeps;
  }

  /** Adds one call's verdicts. */
  void record(List<ReviewResponse.CriterionVerdict> callVerdicts) {
    if (keeps) {
      verdicts.addAll(callVerdicts);
    }
  }

  /** Every verdict recorded so far, in the order the calls returned. */
  List<ReviewResponse.CriterionVerdict> all() {
    return List.copyOf(verdicts);
  }
}
