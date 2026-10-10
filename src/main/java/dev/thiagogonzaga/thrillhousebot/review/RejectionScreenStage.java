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

/**
 * Drops a claim the audit already rejected on this head before the verification call, rather than
 * put it to the verifier again (#711). A second verdict on unchanged code is a re-roll of the same
 * question, and on the fail-open paths there is no second verdict at all.
 */
@ApplicationScoped
class RejectionScreenStage implements FindingStage {

  private final VerifierRejectionMemory rejectionMemory;

  @Inject
  RejectionScreenStage(VerifierRejectionMemory rejectionMemory) {
    this.rejectionMemory = rejectionMemory;
  }

  @Override
  public ScreeningContext apply(ScreeningContext context) {
    return context.withResponse(
        rejectionMemory.withoutRejectionsOnThisHead(context.session(), context.response()));
  }
}
