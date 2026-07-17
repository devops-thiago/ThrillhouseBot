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
package dev.thiagogonzaga.thrillhousebot.review.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One labeled regression case from the prompt eval corpus: a real dogfood outcome pinned as {@code
 * (diff, candidate finding, expected verdict)} for verifier cases, or {@code (diff, expected
 * finding presence)} for generator cases. Loaded from {@code src/test/resources/evalcorpus/<name>/}
 * by {@link EvalCorpus}: {@code case.json} carries the spec, {@code diff.txt} the diff exactly as
 * the review pipeline formats it ("### path (status, +A -D)" sections with fenced patches).
 */
public record EvalCase(String name, String diff, Spec spec) {

  static final String KIND_VERIFIER = "verifier";
  static final String KIND_GENERATOR = "generator";
  static final String MUST_FIND = "must-find";
  static final String MUST_NOT_FIND = "must-not-find";

  /**
   * The JSON body of {@code case.json}; unknown properties fail loading to keep fixtures honest.
   */
  public record Spec(
      String kind,
      int sourcePr,
      String why,
      List<String> expectedVerdicts,
      CandidateFinding finding,
      String expectation,
      String targetFile,
      List<String> keywords,
      String prTitle,
      String prDescription) {}

  /** The candidate finding a verifier case feeds to the second-pass audit. */
  public record CandidateFinding(
      String risk,
      String confidence,
      String file,
      int line,
      String title,
      String description,
      @JsonProperty("suggestion_old") String suggestionOld,
      @JsonProperty("suggestion_new") String suggestionNew) {}

  boolean isVerifierCase() {
    return KIND_VERIFIER.equals(spec.kind());
  }

  boolean isGeneratorCase() {
    return KIND_GENERATOR.equals(spec.kind());
  }
}
