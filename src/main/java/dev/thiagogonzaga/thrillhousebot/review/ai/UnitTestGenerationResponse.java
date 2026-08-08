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

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Objects;

/**
 * Parsed JSON returned by the {@link UnitTestAssistant} for one {@code /generate-tests} request.
 * {@code notes} carries the model's coverage caveats and is normalized to an empty string when
 * absent.
 */
@RegisterForReflection
public record UnitTestGenerationResponse(List<GeneratedTestFile> tests, String notes) {

  public UnitTestGenerationResponse {
    // The model may emit null array elements; drop them before copying so a single bad entry never
    // fails the whole command.
    tests = List.copyOf(withoutNulls(tests));
    notes = notes == null ? "" : notes;
  }

  private static List<GeneratedTestFile> withoutNulls(List<GeneratedTestFile> values) {
    return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
  }

  /** The proposed test files that carry enough data to render, in the model's own order. */
  public List<GeneratedTestFile> postableTests() {
    return tests.stream().filter(GeneratedTestFile::isPostable).toList();
  }

  /** One proposed test file: where it belongs, what it covers, and its complete source. */
  @RegisterForReflection
  public record GeneratedTestFile(String path, String language, String covers, String code) {

    /**
     * Whether this proposal carries the data needed to render a copy-paste block: a target path and
     * the test source itself. {@code language} and {@code covers} are presentation-only, so a
     * missing one degrades the block rather than dropping the tests.
     */
    public boolean isPostable() {
      return path != null && !path.isBlank() && code != null && !code.isBlank();
    }
  }
}
