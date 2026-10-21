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
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One labeled regression case from the prompt eval corpus: a real dogfood outcome pinned as {@code
 * (diff, candidate finding, expected verdict)} for verifier cases, {@code (diff, expected finding
 * presence)} for generator cases, {@code (PR text, changed files, findings, a description claim)}
 * for summary cases, or {@code (diff, findings, a written summary, the statements it must lose and
 * keep)} for summary verification cases, or {@code (a failed answer, the titles or paths it must
 * keep)} for repair cases. Loaded from {@code src/test/resources/evalcorpus/<name>/} by {@link
 * EvalCorpus}: {@code case.json} carries the spec, {@code diff.txt} the diff exactly as the review
 * pipeline formats it ("### path (status, +A -D)" sections with fenced patches).
 */
public record EvalCase(String name, String diff, Spec spec) {

  static final String KIND_VERIFIER = "verifier";
  static final String KIND_GENERATOR = "generator";
  static final String KIND_SUMMARY = "summary";
  static final String KIND_SUMMARY_VERIFICATION = "summary-verification";
  static final String KIND_REPAIR = "repair";
  static final String REPAIRED = "repaired";
  static final String FILE_SUMMARIES_READ = "file-summaries-read";
  static final String CONTRADICTED_CUT = "contradicted-cut";
  static final String NOTHING_CUT = "nothing-cut";
  static final String CLAIM_GAPPED = "claim-gapped";
  static final String CLAIM_NOT_GAPPED = "claim-not-gapped";
  static final String MUST_FIND = "must-find";
  static final String MUST_NOT_FIND = "must-not-find";
  static final String SUGGESTION_FIXED = "suggestion-fixed";
  static final String CLAIM_CUT = "claim-cut";

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
      List<String> dimensions,
      String learnings,
      String changedFiles,
      List<CandidateFinding> findings,
      ReviewResponse.Summary summary,
      List<String> keptKeywords,
      String answer) {}

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
    var lines = diff.split("\n", -1);
    for (int i = 0; i + 1 < lines.length; i++) {
      var header = SECTION_HEADER.matcher(lines[i]);
      if (!header.matches() || !"```diff".equals(lines[i + 1])) {
        continue;
      }
      var patch = new ArrayList<String>();
      int j = i + 2;
      while (j < lines.length && !"```".equals(lines[j])) {
        patch.add(lines[j++]);
      }
      files.add(
          new GitHubPullRequestClient.FileDiff(
              header.group(1), header.group(2), 0, 0, 0, String.join("\n", patch)));
      i = j;
    }
    return List.copyOf(files);
  }

  /** A section header, {@code ### path (status, +A -D)}. */
  private static final Pattern SECTION_HEADER = Pattern.compile("### (\\S+) \\((\\w+),[^)]*\\)");

  boolean isVerifierCase() {
    return KIND_VERIFIER.equals(spec.kind());
  }

  /**
   * A verifier case that also judges the finding's fix or description (#1080): {@code
   * suggestion-fixed} expects the broken suggestion replaced (by code holding every {@code
   * keywords} entry) or dropped, and {@code claim-cut} expects the side claim the {@code keywords}
   * name cut from the description while the correct suggestion stays. Either way the verdict must
   * be one of {@code expectedVerdicts}.
   */
  boolean judgesTheFix() {
    return isVerifierCase() && spec.expectation() != null;
  }

  boolean isGeneratorCase() {
    return KIND_GENERATOR.equals(spec.kind());
  }

  /**
   * A summary-call case (#920): the PR text, the changed-files overview and the verified findings
   * go to the summary prompt, and the description claim the {@code keywords} name must come back as
   * a description gap and nowhere it would read as implemented ({@code claim-gapped}), or, for a
   * claim the evidence can carry, must not come back as a gap ({@code claim-not-gapped}).
   */
  boolean isSummaryCase() {
    return KIND_SUMMARY.equals(spec.kind());
  }

  /**
   * A summary verification case (#1078): the written {@code summary}, the diff and the findings go
   * to the summary verification call, and every statement naming a {@code keywords} entry must be
   * gone from what comes back while every {@code keptKeywords} entry is still there ({@code
   * contradicted-cut}), or, for a summary whose every statement is true, nothing may be cut ({@code
   * nothing-cut}, with no keywords).
   */
  boolean isSummaryVerificationCase() {
    return KIND_SUMMARY_VERIFICATION.equals(spec.kind());
  }

  /**
   * A repair case (#1079): the failed {@code answer} goes to the repair step. For {@code repaired}
   * it is a review answer the parser refuses, and the repaired answer must hold exactly one finding
   * per {@code keywords} entry, each titled by it. For {@code file-summaries-read} it is a summary
   * answer whose {@code file_summaries} is a string, and the re-ask must return a row for every
   * {@code keywords} path and no other.
   */
  boolean isRepairCase() {
    return KIND_REPAIR.equals(spec.kind());
  }
}
