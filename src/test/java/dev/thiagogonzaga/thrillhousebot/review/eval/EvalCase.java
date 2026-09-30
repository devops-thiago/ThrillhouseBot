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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewDimension;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
      String patchCoverage,
      String prTitle,
      String prDescription,
      List<String> dimensions) {}

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

  /**
   * The review dimensions this case depends on ({@link ReviewDimension} names, #665): the blocks
   * the routed prompt must carry for the case to be answerable at all. Empty when the case declares
   * none.
   */
  List<ReviewDimension> dimensions() {
    return spec.dimensions() == null
        ? List.of()
        : spec.dimensions().stream().map(ReviewDimension::valueOf).toList();
  }

  /**
   * The diff parsed back into the per-file patches the router reads: one entry per {@code ### path
   * (status, ...)} section, its fenced {@code diff} block as the patch.
   */
  List<GitHubPullRequestClient.FileDiff> files() {
    var files = new ArrayList<GitHubPullRequestClient.FileDiff>();
    var matcher = SECTION.matcher(diff);
    while (matcher.find()) {
      files.add(
          new GitHubPullRequestClient.FileDiff(
              matcher.group(1), matcher.group(2), 0, 0, 0, matcher.group(3)));
    }
    return List.copyOf(files);
  }

  private static final Pattern SECTION =
      Pattern.compile("(?m)^### (\\S+) \\((\\w+)[^)]*\\)\\n```diff\\n(.*?)\\n```", Pattern.DOTALL);

  boolean isVerifierCase() {
    return KIND_VERIFIER.equals(spec.kind());
  }

  boolean isGeneratorCase() {
    return KIND_GENERATOR.equals(spec.kind());
  }
}
