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

/** A single review finding, enriched from the AI response. */
public record Finding(
    RiskLevel risk,
    Confidence confidence,
    String file,
    int line,
    String title,
    String description,
    String suggestionOld,
    String suggestionNew) {
  public Finding {
    // Findings persisted before the confidence field existed keep their blocking semantics.
    if (confidence == null) {
      confidence = Confidence.HIGH;
    }
  }

  /** Convenience constructor for callers that predate the confidence field. */
  public Finding(
      RiskLevel risk,
      String file,
      int line,
      String title,
      String description,
      String suggestionOld,
      String suggestionNew) {
    this(risk, Confidence.HIGH, file, line, title, description, suggestionOld, suggestionNew);
  }

  public static Finding fromAiResponse(ReviewResponse.Finding ai) {
    return new Finding(
        RiskLevel.fromString(ai.risk()),
        Confidence.fromString(ai.confidence()),
        ai.file(),
        ai.line(),
        ai.title(),
        ai.description(),
        ai.suggestionOld(),
        ai.suggestionNew());
  }

  public boolean hasSuggestion() {
    return suggestionOld != null
        && !suggestionOld.isBlank()
        && suggestionNew != null
        && !suggestionNew.isBlank();
  }
}
