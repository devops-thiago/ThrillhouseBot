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

/**
 * How a finding is looked up after the pipeline has rebuilt it. Evidence is resolved against the
 * findings as the model raised them and read back after the quote validator, the framework filter
 * and the deduplicator have each returned a new object, so the lookup cannot be by identity.
 * Location and title survive every stage between resolution and verification; {@code
 * suggestion_old} does not, which is why it is not part of the key.
 */
record FindingKey(String file, int line, String title) {

  /** The key for a finding worth resolving evidence for, or {@code null} when it cites no file. */
  static FindingKey of(ReviewResponse.Finding finding) {
    if (finding == null || finding.file() == null || finding.file().isBlank()) {
      return null;
    }
    return new FindingKey(finding.file(), finding.line(), finding.title());
  }
}
