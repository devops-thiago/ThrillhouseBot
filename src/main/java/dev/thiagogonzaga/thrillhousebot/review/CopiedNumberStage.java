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
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;

/**
 * Removes the line numbers a finding's quotes copied from the numbered diff (#1320, #1340), checked
 * against the text the call was given. It runs first, so the evidence, the quote validator and
 * every later stage read the quotes as code.
 */
@ApplicationScoped
class CopiedNumberStage implements FindingStage {

  @Override
  public ScreeningContext apply(ScreeningContext context) {
    var response = context.response();
    var source = context.quoteSource();
    if (response.findings().isEmpty() || source == null || source.isBlank()) {
      return context;
    }
    var copied = CopiedLineNumbers.of(source);
    var findings = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    int stripped = 0;
    for (var finding : response.findings()) {
      var next = copied.strip(finding);
      if (next != finding) {
        stripped++;
      }
      findings.add(next);
    }
    if (stripped == 0) {
      return context;
    }
    Log.infof("Removed copied diff line numbers from the quotes of %d finding(s)", stripped);
    return context.withResponse(
        new ReviewResponse(
            findings,
            response.previousFindingsStatus(),
            response.summary(),
            response.closedEarlierRound(),
            response.linkedIssueCriteria()));
  }
}
