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

/**
 * Resolves the evidence the verifier is handed for findings that cite code outside the diff.
 *
 * <p>It runs first, so it reads the findings as the model raised them: the quote validator after it
 * nulls {@code suggestion_old} on exactly the finding whose quote is outside the call's window,
 * which is the finding the cited-location round exists to resolve (#650/#475).
 */
@ApplicationScoped
class EvidenceStage implements FindingStage {

  @Override
  public ScreeningContext apply(ScreeningContext context) {
    return context.withAttached(context.evidence().forFindings(context.response().findings()));
  }
}
