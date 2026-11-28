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

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Why each finding of one review was raised and how it was checked (#1435), from what the pipeline
 * itself established: the maintainers' scoped rules covering the file, the failing check the
 * finding cites and where that check annotated the file, the second pass's verdict and the review
 * learning that demoted it. Every value is a name, a path, an id or a fixed phrase; none is model
 * or diff prose. Batches record into it concurrently.
 */
final class FindingBasis implements FindingVerificationService.Outcomes {

  /** Records nothing, for a caller with no review of its own to account for. */
  static final FindingBasis DISCARD = new FindingBasis(false);

  private final boolean recording;
  private final Map<FindingKey, String> verdicts = new ConcurrentHashMap<>();
  private final Map<FindingKey, Long> learnings = new ConcurrentHashMap<>();

  FindingBasis() {
    this(true);
  }

  private FindingBasis(boolean recording) {
    this.recording = recording;
  }

  @Override
  public void recordOutcome(ReviewResponse.Finding finding, String outcome) {
    var key = FindingKey.of(finding);
    if (recording && key != null) {
      verdicts.put(key, outcome);
    }
  }

  /** Notes that the review learning {@code id} shaped {@code finding}. */
  void shapedByLearning(ReviewResponse.Finding finding, long id) {
    var key = FindingKey.of(finding);
    if (recording && key != null) {
      learnings.put(key, id);
    }
  }

  /** The block a finding's comment carries, or blank when the review established nothing for it. */
  String render(Finding finding, PathScopedInstructions rules, CiFailureCitations ci) {
    var lines = new ArrayList<String>();
    var key = new FindingKey(finding.file(), finding.line(), finding.title());
    ruleLine(finding.file(), rules, lines);
    var cited = ci.citedNames(finding.title(), finding.description());
    if (!cited.isEmpty()) {
      var annotated = ci.annotatedLocations(finding.file(), finding.title(), finding.description());
      lines.add(
          "CI: cites "
              + String.join(", ", cited)
              + (annotated.isEmpty() ? "" : "; annotated at " + String.join(", ", annotated)));
    }
    var description = finding.description();
    if (description != null && description.contains(FindingVerificationService.UNVERIFIED_NOTE)) {
      lines.add("Verified: not screened by the second pass");
    } else if (verdicts.containsKey(key)) {
      lines.add("Verified: " + verdicts.get(key));
    }
    if (learnings.containsKey(key)) {
      lines.add("Learning: L" + learnings.get(key));
    }
    return lines.isEmpty()
        ? ""
        : lines.stream().collect(Collectors.joining("\n- ", "**Basis**\n- ", "\n"));
  }

  private static void ruleLine(String file, PathScopedInstructions rules, List<String> lines) {
    if (rules == null) {
      return;
    }
    var globs =
        rules.scopes().stream()
            .filter(scope -> scope.files().contains(file))
            .map(scope -> "`" + MarkdownSafe.inlineCode(scope.glob()) + "`")
            .collect(Collectors.joining(", "));
    if (!globs.isEmpty()) {
      lines.add(
          "Rule: the maintainers' rules scoped to "
              + globs
              + " cover this file (from `"
              + MarkdownSafe.inlineCode(rules.source())
              + "`)");
    }
  }
}
