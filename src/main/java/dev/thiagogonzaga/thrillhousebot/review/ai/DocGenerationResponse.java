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

/** Parsed JSON returned by the {@link DocGenerator} for one {@code /add-docs} request. */
@RegisterForReflection
public record DocGenerationResponse(List<DocSuggestion> docs) {
  public DocGenerationResponse {
    // The model may emit null array elements; drop them before copying so a single bad entry never
    // fails the whole command.
    docs = List.copyOf(withoutNulls(docs));
  }

  private static List<DocSuggestion> withoutNulls(List<DocSuggestion> values) {
    return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
  }

  /** A documentation suggestion for one changed symbol, anchored at its declaration line. */
  @RegisterForReflection
  public record DocSuggestion(
      String file,
      int line,
      String symbol,
      @JsonProperty("suggestion_old") String suggestionOld,
      @JsonProperty("suggestion_new") String suggestionNew) {

    /** Whether this suggestion carries the data needed to post a committable suggestion block. */
    public boolean isPostable() {
      return file != null
          && !file.isBlank()
          && line > 0
          && suggestionNew != null
          && !suggestionNew.isBlank();
    }
  }
}
