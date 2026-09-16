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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReviewEvidence} — the two resolvers a review opens together, and the single
 * character budget that is the reason they are opened together (#475).
 */
class ReviewEvidenceTest {

  private final GitHubPullRequestClient prClient = mock(GitHubPullRequestClient.class);

  private static String path(int i) {
    return "src/main/java/app/File" + i + ".java";
  }

  /** Content whose resolved note is wide enough to spend a whole note's worth of the budget. */
  private static final String WIDE = ("var x = \"" + "y".repeat(300) + "\";\n").repeat(8);

  private void givenFile(String p) {
    when(prClient.getFileContent(any(), any(), eq("o"), eq("r"), eq(p), eq("sha")))
        .thenReturn(
            new GitHubPullRequestClient.FileContent(
                p,
                p,
                Base64.getEncoder().encodeToString(WIDE.getBytes(StandardCharsets.UTF_8)),
                "base64",
                WIDE.length()));
  }

  private static ReviewResponse.Finding finding(String file) {
    return new ReviewResponse.Finding(
        "medium",
        "high",
        file,
        1,
        "Untested branch on " + file,
        "The branch decides what the caller renders.",
        "var x = \"" + "y".repeat(300) + "\";",
        "z");
  }

  /**
   * The budget spans both resolvers, so cited-location notes that fill it leave nothing for context
   * evidence. Capping each resolver on its own would let their sum grow with every resolver added,
   * which is what must never rival the diff the verifier is reading.
   */
  @Test
  void spendsOneBudgetAcrossBothResolvers() {
    var files = new ArrayList<FileDiff>();
    var findings = new ArrayList<ReviewResponse.Finding>();
    var coverage = new StringBuilder(PatchCoverageResolver.SECTION_HEADING).append('\n');
    for (var i = 0; i < EvidenceBudget.MAX_TOTAL_CHARS / EvidenceBudget.MAX_NOTE_CHARS + 1; i++) {
      givenFile(path(i));
      files.add(new FileDiff(path(i), "modified", 1, 0, 1, "@@ -1 +1 @@\n+x"));
      findings.add(finding(path(i)));
      coverage.append("- ").append(path(i)).append(": 1-8\n");
    }
    var ctx = context(files, coverage.toString());

    var evidence =
        ReviewEvidence.forReview(new CitedLocationResolver(prClient), "t", "o", "r", "sha", ctx)
            .forFindings(findings);

    var last = findings.get(findings.size() - 1);
    assertNotNull(
        evidence.citedLocations().forFinding(findings.get(0)),
        "the first finding's location resolves while the budget is whole");
    assertNull(
        evidence.contextEvidence().forFinding(last),
        "context evidence past what the cited locations already spent is dropped");
  }

  @Test
  void carriesBothResolversEvidenceForOneFinding() {
    givenFile(path(0));
    var files = List.of(new FileDiff(path(0), "modified", 1, 0, 1, "@@ -1 +1 @@\n+x"));
    var ctx = context(files, PatchCoverageResolver.SECTION_HEADING + "\n- " + path(0) + ": 1-8\n");
    var only = finding(path(0));

    var evidence =
        ReviewEvidence.forReview(new CitedLocationResolver(prClient), "t", "o", "r", "sha", ctx)
            .forFindings(List.of(only));

    assertNotNull(evidence.citedLocations().forFinding(only), "the cited location resolves");
    assertNotNull(evidence.contextEvidence().forFinding(only), "the measurement is attached");
  }

  @Test
  void resolvesNothingWithoutARepositoryOrAContext() {
    var only = finding(path(0));

    var evidence = ReviewEvidence.NONE.forFindings(List.of(only));

    assertNull(evidence.citedLocations().forFinding(only));
    assertNull(evidence.contextEvidence().forFinding(only));
  }

  private static ReviewContextLoader.ReviewContext context(
      List<FileDiff> files, String patchCoverage) {
    return new ReviewContextLoader.ReviewContext(
        files,
        "diff",
        "base",
        0,
        List.of(),
        List.of(),
        List.of(),
        true,
        false,
        null,
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "stack",
        "",
        "",
        patchCoverage,
        files,
        () -> new DiffLineResolver(Map.of()),
        new ReviewContextLoader.PrTotals(1, 1, 1));
  }
}
