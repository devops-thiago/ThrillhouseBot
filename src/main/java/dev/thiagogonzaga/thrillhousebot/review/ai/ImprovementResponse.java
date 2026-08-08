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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Objects;

/** Parsed JSON returned by the {@link PrImproveAssistant} for one {@code /improve} request. */
@RegisterForReflection
public record ImprovementResponse(List<Improvement> improvements) {
  public ImprovementResponse {
    // The model may emit null array elements; drop them before copying so a single bad entry never
    // fails the whole command.
    improvements = List.copyOf(withoutNulls(improvements));
  }

  private static List<Improvement> withoutNulls(List<Improvement> values) {
    return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
  }

  /** One proposed improvement, anchored at the lines it replaces on the right side of the diff. */
  @RegisterForReflection
  public record Improvement(
      String file,
      int line,
      String title,
      String category,
      String rationale,
      @JsonProperty("suggestion_old") String suggestionOld,
      @JsonProperty("suggestion_new") String suggestionNew) {

    /**
     * Whether this improvement carries the data needed to render a suggestion: a file, a positive
     * line, the code it replaces ({@code suggestion_old}, the anchor it is matched against in the
     * diff), and the replacement ({@code suggestion_new}). Entries without them are dropped rather
     * than posted as an unactionable comment.
     */
    public boolean isPostable() {
      return file != null
          && !file.isBlank()
          && line > 0
          && suggestionOld != null
          && !suggestionOld.isBlank()
          && suggestionNew != null
          && !suggestionNew.isBlank();
    }
  }
}
