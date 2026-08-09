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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.github.ArtifactZipFetcher;
import dev.thiagogonzaga.thrillhousebot.github.GitHubActionsClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettings;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PatchCoverageResolver} — sourcing the repository's own coverage report for
 * the exact commit under review and reducing it to the added lines no test executed (#115).
 */
class PatchCoverageResolverTest {

  private static final String HEAD_SHA = "abc1234def5678";
  private static final String ARTIFACT = "coverage-report";
  private static final URI BLOB = URI.create("https://blob.example/artifact.zip");
  private static final String FILE =
      "src/main/java/dev/thiagogonzaga/thrillhousebot/review/CiStatusEvaluator.java";

  /**
   * Right-side numbering from the hunk header: 10 is context, 11 and 12 are added, the deletion is
   * left-side only, and 13 is context again.
   */
  private static final String PATCH =
      """
      @@ -10,3 +10,4 @@
       var offending = new ArrayList<CiCheck>();
      +if (!ciGating.evaluatesCi()) {
      +  return new CiEvaluation(List.of(), false, true);
      -var seen = new HashSet<String>();
       return new CiEvaluation(offending, unreadable);""";

  /** Lines 11, 12 and 13 are all executable and unhit; only 11 and 12 are added by the patch. */
  private static final String REPORT =
      """
      <report name="r">
        <package name="dev/thiagogonzaga/thrillhousebot/review">
          <sourcefile name="CiStatusEvaluator.java">
            <line nr="10" mi="0" ci="2"/>
            <line nr="11" mi="3" ci="0"/>
            <line nr="12" mi="3" ci="0"/>
            <line nr="13" mi="3" ci="0"/>
          </sourcefile>
        </package>
      </report>
      """;

  private final GitHubActionsClient actionsClient = mock(GitHubActionsClient.class);
  private final ArtifactZipFetcher zipFetcher = mock(ArtifactZipFetcher.class);

  private PatchCoverageResolver resolver(boolean enabled) {
    return new PatchCoverageResolver(actionsClient, zipFetcher, enabled);
  }

  private static ReviewOrchestrator.ReviewRequest request() {
    return new ReviewOrchestrator.ReviewRequest(
        "o", "r", 7, HEAD_SHA, "title", "body", "basesha", "main", 1L, false, "main", false);
  }

  private static RepoSettings settingsNaming(String artifact) {
    return new RepoSettings(List.of(), List.of(), artifact, ".github/thrillhousebot.yml");
  }

  private static List<FileDiff> changedFile() {
    return List.of(new FileDiff(FILE, "modified", 2, 1, 3, PATCH));
  }

  private static byte[] zippedReport(String xml) {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("jacoco.xml"));
      zip.write(xml.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /** A completed run for the head SHA that uploaded {@code artifactName} with id 99. */
  private void givenRunWithArtifact(String artifactName) {
    givenRuns(new GitHubActionsClient.WorkflowRun(42L, "ci", HEAD_SHA, "completed", "success"));
    when(actionsClient.listRunArtifacts(any(), any(), eq("o"), eq("r"), eq(42L), anyInt()))
        .thenReturn(
            new GitHubActionsClient.RunArtifacts(
                1, List.of(new GitHubActionsClient.Artifact(99L, artifactName, 1_024, false))));
  }

  private void givenRuns(GitHubActionsClient.WorkflowRun... runs) {
    when(actionsClient.listWorkflowRuns(
            any(), any(), eq("o"), eq("r"), eq(HEAD_SHA), eq("completed"), anyInt()))
        .thenReturn(new GitHubActionsClient.WorkflowRuns(runs.length, List.of(runs)));
  }

  private void givenDownloadRedirectsTo(URI location) {
    var response = mock(Response.class);
    when(response.getLocation()).thenReturn(location);
    when(actionsClient.downloadArtifact(any(), any(), eq("o"), eq("r"), eq(99L)))
        .thenReturn(response);
  }

  private String resolve(boolean enabled, String artifact) {
    return resolver(enabled).resolve("token", request(), settingsNaming(artifact), changedFile());
  }

  @Nested
  class Gating {

    @Test
    void contributesNothingAndCallsNoApiWhenTheDeploymentSwitchIsOff() {
      assertEquals("", resolve(false, ARTIFACT));

      verifyNoInteractions(actionsClient, zipFetcher);
    }

    @Test
    void contributesNothingAndCallsNoApiWhenTheRepositoryNamesNoArtifact() {
      assertEquals(
          "",
          resolver(true).resolve("token", request(), RepoSettings.EMPTY, changedFile()),
          "the common case: a repository that declares nothing gets no coverage context");

      verifyNoInteractions(actionsClient, zipFetcher);
    }

    @Test
    void contributesNothingWhenThereAreNoReviewableFiles() {
      assertEquals(
          "", resolver(true).resolve("token", request(), settingsNaming(ARTIFACT), List.of()));

      verifyNoInteractions(actionsClient, zipFetcher);
    }
  }

  @Nested
  class Sourcing {

    @Test
    void reportsTheAddedLinesTheReportRecordsAsNeverExecuted() {
      givenRunWithArtifact(ARTIFACT);
      givenDownloadRedirectsTo(BLOB);
      when(zipFetcher.fetch(BLOB)).thenReturn(zippedReport(REPORT));

      var section = resolve(true, ARTIFACT);

      assertTrue(section.startsWith(PatchCoverageResolver.SECTION_HEADING), section);
      assertTrue(section.contains("- " + FILE + ": 11-12"), section);
      assertFalse(
          section.contains("13"),
          "line 13 is uncovered but is context, not something this PR added: " + section);
    }

    @Test
    void ignoresRunsForAnotherCommit() {
      givenRuns(new GitHubActionsClient.WorkflowRun(42L, "ci", "otherSha", "completed", "success"));

      assertEquals(
          "",
          resolve(true, ARTIFACT),
          "coverage from a different revision would point at meaningless line numbers");
      verify(actionsClient, never())
          .listRunArtifacts(any(), any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void ignoresAnArtifactWithAnotherNameOrAnExpiredOne() {
      givenRunWithArtifact("build-logs");
      assertEquals("", resolve(true, ARTIFACT), "only the artifact the repository named is read");

      reset(actionsClient);
      givenRuns(new GitHubActionsClient.WorkflowRun(42L, "ci", HEAD_SHA, "completed", "success"));
      when(actionsClient.listRunArtifacts(any(), any(), eq("o"), eq("r"), eq(42L), anyInt()))
          .thenReturn(
              new GitHubActionsClient.RunArtifacts(
                  1, List.of(new GitHubActionsClient.Artifact(99L, ARTIFACT, 1_024, true))));

      assertEquals("", resolve(true, ARTIFACT), "an expired artifact has no bytes left to read");
      verifyNoInteractions(zipFetcher);
    }

    @Test
    void degradesToNoContextWhenTheDownloadCannotBeFollowed() {
      givenRunWithArtifact(ARTIFACT);
      givenDownloadRedirectsTo(null);

      assertEquals("", resolve(true, ARTIFACT));
      verifyNoInteractions(zipFetcher);
    }

    @Test
    void degradesToNoContextWhenTheApiFails() {
      when(actionsClient.listWorkflowRuns(any(), any(), any(), any(), any(), any(), anyInt()))
          .thenThrow(new IllegalStateException("502 from GitHub"));

      assertEquals(
          "", resolve(true, ARTIFACT), "a coverage fetch failure must never fail a review");
    }

    @Test
    void degradesToNoContextWhenEveryAddedLineIsCovered() {
      givenRunWithArtifact(ARTIFACT);
      givenDownloadRedirectsTo(BLOB);
      when(zipFetcher.fetch(BLOB))
          .thenReturn(
              zippedReport(
                  """
                  <report name="r">
                    <package name="dev/thiagogonzaga/thrillhousebot/review">
                      <sourcefile name="CiStatusEvaluator.java">
                        <line nr="11" mi="0" ci="4"/>
                        <line nr="12" mi="0" ci="4"/>
                      </sourcefile>
                    </package>
                  </report>
                  """));

      assertEquals("", resolve(true, ARTIFACT), "nothing to say is said with nothing");
    }
  }

  @Nested
  class Rendering {

    @Test
    void collapsesConsecutiveLinesAndCapsTheRanges() {
      var lines = new TreeSet<Integer>(List.of(1, 2, 3, 9, 20, 21));

      assertEquals("1-3, 9, 20-21", PatchCoverageResolver.formatRanges(lines));

      var many = new TreeSet<Integer>();
      for (var i = 0; i < (PatchCoverageResolver.MAX_RANGES_PER_FILE + 5) * 2; i += 2) {
        many.add(i + 1);
      }
      assertTrue(
          PatchCoverageResolver.formatRanges(many).endsWith("and 5 more range(s)"),
          PatchCoverageResolver.formatRanges(many));
    }

    @Test
    void capsHowManyFilesReachThePrompt() {
      var files = new ArrayList<PatchCoverageResolver.UncoveredFile>();
      for (var i = 0; i < PatchCoverageResolver.MAX_FILES_RENDERED + 3; i++) {
        files.add(
            new PatchCoverageResolver.UncoveredFile(
                "src/File" + i + ".java", new TreeSet<>(List.of(1))));
      }

      var rendered = PatchCoverageResolver.render(List.copyOf(files));

      assertTrue(
          rendered.contains("(3 more changed file(s) with uncovered added lines)"), rendered);
      assertTrue(rendered.length() <= PatchCoverageResolver.MAX_TOTAL_CHARS + 40, "bounded output");
    }

    @Test
    void ordersTheWorstCoveredFilesFirstSoTheCapDropsTheLeastInformative() {
      var report =
          JacocoCoverageReport.parse(
              new java.io.ByteArrayInputStream(
                  """
                  <report name="r">
                    <package name="a">
                      <sourcefile name="Few.java"><line nr="1" mi="1" ci="0"/></sourcefile>
                      <sourcefile name="Many.java">
                        <line nr="1" mi="1" ci="0"/>
                        <line nr="2" mi="1" ci="0"/>
                      </sourcefile>
                    </package>
                  </report>
                  """
                      .getBytes(StandardCharsets.UTF_8)));
      var files =
          List.of(
              new FileDiff("a/Few.java", "modified", 1, 0, 1, "@@ -1,0 +1,1 @@\n+x"),
              new FileDiff("a/Many.java", "modified", 2, 0, 2, "@@ -1,0 +1,2 @@\n+x\n+y"));

      var uncovered = PatchCoverageResolver.intersectWithAddedLines(report, files);

      assertEquals(
          List.of("a/Many.java", "a/Few.java"),
          uncovered.stream().map(PatchCoverageResolver.UncoveredFile::path).toList());
    }
  }
}
