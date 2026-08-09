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
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
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
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @Test
    void contributesNothingForABlankArtifactNameOrAnUnknownHeadSha() {
      assertEquals("", resolve(true, "   "), "whitespace is not an artifact name anyone uploaded");

      var noSha =
          new ReviewOrchestrator.ReviewRequest(
              "o", "r", 7, null, "title", "body", "basesha", "main", 1L, false, "main", false);
      var blankSha =
          new ReviewOrchestrator.ReviewRequest(
              "o", "r", 7, "  ", "title", "body", "basesha", "main", 1L, false, "main", false);
      assertEquals(
          "",
          resolver(true).resolve("token", noSha, settingsNaming(ARTIFACT), changedFile()),
          "without a head SHA there is no revision to attribute coverage to");
      assertEquals(
          "", resolver(true).resolve("token", blankSha, settingsNaming(ARTIFACT), changedFile()));

      verifyNoInteractions(actionsClient, zipFetcher);
    }

    @Test
    void theInjectionConstructorReadsTheDeploymentKillSwitch() {
      var config = mock(ThrillhouseConfig.class, RETURNS_DEEP_STUBS);
      when(config.review().patchCoverage().enabled()).thenReturn(false);

      var injected = new PatchCoverageResolver(actionsClient, zipFetcher, config);

      assertEquals(
          "",
          injected.resolve("token", request(), settingsNaming(ARTIFACT), changedFile()),
          "the CDI constructor must wire thrillhousebot.review.patch-coverage.enabled");
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
    void ignoresAnArtifactWithAnotherName() {
      givenRunWithArtifact("build-logs");
      // The download is fully wired: were the name check to fall away, this test would report
      // uncovered lines instead of passing on an unstubbed collaborator's default.
      givenDownloadRedirectsTo(BLOB);
      when(zipFetcher.fetch(BLOB)).thenReturn(zippedReport(REPORT));

      assertEquals("", resolve(true, ARTIFACT), "only the artifact the repository named is read");
      verifyNoInteractions(zipFetcher);
    }

    @Test
    void ignoresAnExpiredArtifactEvenThoughItsBytesWouldStillParse() {
      givenRuns(new GitHubActionsClient.WorkflowRun(42L, "ci", HEAD_SHA, "completed", "success"));
      when(actionsClient.listRunArtifacts(any(), any(), eq("o"), eq("r"), eq(42L), anyInt()))
          .thenReturn(
              new GitHubActionsClient.RunArtifacts(
                  1, List.of(new GitHubActionsClient.Artifact(99L, ARTIFACT, 1_024, true))));
      // Same wiring: a readable report is waiting behind the download, so "" can only be the
      // expiry check doing its job — not an unmocked collaborator quietly skipping the path.
      givenDownloadRedirectsTo(BLOB);
      when(zipFetcher.fetch(BLOB)).thenReturn(zippedReport(REPORT));

      assertEquals("", resolve(true, ARTIFACT), "GitHub has already deleted an expired artifact");
      verifyNoInteractions(zipFetcher);
    }

    @Test
    void ignoresCoverageFromARunThatDidNotSucceed() {
      // With the common `if: always()` upload, a run that aborted still attaches the artifact — but
      // its ci=0 regions are the run stopping short, not measured fact. A failed conclusion must be
      // skipped before its artifacts are even listed.
      givenRuns(new GitHubActionsClient.WorkflowRun(42L, "ci", HEAD_SHA, "completed", "failure"));
      when(actionsClient.listRunArtifacts(any(), any(), eq("o"), eq("r"), eq(42L), anyInt()))
          .thenReturn(
              new GitHubActionsClient.RunArtifacts(
                  1, List.of(new GitHubActionsClient.Artifact(99L, ARTIFACT, 1_024, false))));
      givenDownloadRedirectsTo(BLOB);
      when(zipFetcher.fetch(BLOB)).thenReturn(zippedReport(REPORT));

      assertEquals(
          "",
          resolve(true, ARTIFACT),
          "coverage from a failed or cancelled run is not something the model may treat as fact");
      verify(actionsClient, never())
          .listRunArtifacts(any(), any(), any(), any(), anyLong(), anyInt());
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
    void degradesToNoContextWhenNoRunOrArtifactListCanBeRead() {
      when(actionsClient.listWorkflowRuns(
              any(), any(), eq("o"), eq("r"), eq(HEAD_SHA), eq("completed"), anyInt()))
          .thenReturn(null);
      assertEquals(
          "", resolve(true, ARTIFACT), "a null runs payload is not a run with no artifact");

      reset(actionsClient);
      givenRuns(new GitHubActionsClient.WorkflowRun(42L, "ci", HEAD_SHA, "completed", "success"));
      when(actionsClient.listRunArtifacts(any(), any(), any(), any(), anyLong(), anyInt()))
          .thenReturn(null);
      assertEquals("", resolve(true, ARTIFACT), "a null artifact list degrades to no coverage");

      verifyNoInteractions(zipFetcher);
    }

    @Test
    void probesOnlyABoundedNumberOfRunsForTheSameCommit() {
      var runs = new GitHubActionsClient.WorkflowRun[PatchCoverageResolver.MAX_RUNS_PROBED + 4];
      for (var i = 0; i < runs.length; i++) {
        runs[i] =
            new GitHubActionsClient.WorkflowRun(i + 1L, "ci", HEAD_SHA, "completed", "success");
      }
      givenRuns(runs);
      when(actionsClient.listRunArtifacts(any(), any(), any(), any(), anyLong(), anyInt()))
          .thenReturn(new GitHubActionsClient.RunArtifacts(0, List.of()));

      assertEquals("", resolve(true, ARTIFACT));

      verify(actionsClient, times(PatchCoverageResolver.MAX_RUNS_PROBED))
          .listRunArtifacts(any(), any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void degradesToNoContextWhenTheDownloadEndpointAnswersWithNothing() {
      givenRunWithArtifact(ARTIFACT);
      when(actionsClient.downloadArtifact(any(), any(), eq("o"), eq("r"), eq(99L)))
          .thenReturn(null);

      assertEquals("", resolve(true, ARTIFACT));
      verifyNoInteractions(zipFetcher);
    }

    /**
     * The three ways a perfectly readable report can still leave nothing to say. They are one
     * behaviour over three fixtures — the download is wired identically for each, and only the
     * report differs — so they are parameterized rather than copied. The label is the case, and the
     * reason travels with it into the failure message.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reportsThatYieldNoUncoveredAddedLines")
    void aReadableReportWithNoUncoveredAddedLinesProducesNoSection(
        String label, String report, String why) {
      givenRunWithArtifact(ARTIFACT);
      givenDownloadRedirectsTo(BLOB);
      when(zipFetcher.fetch(BLOB)).thenReturn(zippedReport(report));

      assertEquals("", resolve(true, ARTIFACT), why);
    }

    static Stream<Arguments> reportsThatYieldNoUncoveredAddedLines() {
      return Stream.of(
          arguments(
              "the report measures none of the changed files",
              """
              <report name="r">
                <package name="somewhere/else">
                  <sourcefile name="Other.java"><line nr="1" mi="1" ci="0"/></sourcefile>
                </package>
              </report>
              """,
              "a readable report that measures none of the changed files says nothing about them"),
          arguments(
              "every uncovered line falls outside the diff",
              """
              <report name="r">
                <package name="dev/thiagogonzaga/thrillhousebot/review">
                  <sourcefile name="CiStatusEvaluator.java">
                    <line nr="900" mi="3" ci="0"/>
                  </sourcefile>
                </package>
              </report>
              """,
              "pre-existing untested code is not this pull request's business"),
          arguments(
              "every added line is covered",
              """
              <report name="r">
                <package name="dev/thiagogonzaga/thrillhousebot/review">
                  <sourcefile name="CiStatusEvaluator.java">
                    <line nr="11" mi="0" ci="4"/>
                    <line nr="12" mi="0" ci="4"/>
                  </sourcefile>
                </package>
              </report>
              """,
              "nothing to say is said with nothing"));
    }
  }

  @Nested
  class AddedLineExtraction {

    @Test
    void aFileWithNoPatchContributesNothing() {
      assertTrue(PatchCoverageResolver.addedLines(null).isEmpty());
      assertTrue(PatchCoverageResolver.addedLines("   ").isEmpty());
      assertTrue(
          PatchCoverageResolver.addedLines("no hunk header here\njust prose").isEmpty(),
          "lines before the first hunk header have no right-side numbering to report");
    }

    @Test
    void ignoresAnAtAtLineThatIsNotAHunkHeaderAndEmptyLines() {
      var patch = "@@ malformed header @@\n@@ -1,2 +5,3 @@\n+added\n\n context\n+also";

      assertEquals(
          List.of(5, 7),
          List.copyOf(PatchCoverageResolver.addedLines(patch)),
          "an unparseable @@ line must not reset the counter, and a bare empty line is skipped");
    }
  }

  @Nested
  class Rendering {

    @Test
    void collapsesConsecutiveLinesAndCapsTheRanges() {
      var lines = new TreeSet<Integer>(List.of(1, 2, 3, 9, 20, 21));

      assertEquals("", PatchCoverageResolver.formatRanges(new TreeSet<>()), "no lines, no ranges");
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
    void truncatesASectionThatWouldRivalTheDiff() {
      var files = new ArrayList<PatchCoverageResolver.UncoveredFile>();
      for (var i = 0; i < PatchCoverageResolver.MAX_FILES_RENDERED; i++) {
        var lines = new TreeSet<Integer>();
        for (var line = 1; line <= 40; line += 2) {
          lines.add(line + i * 1_000);
        }
        files.add(
            new PatchCoverageResolver.UncoveredFile(
                "src/main/java/dev/thiagogonzaga/thrillhousebot/review/VeryLongName" + i + ".java",
                lines));
      }

      var rendered = PatchCoverageResolver.render(List.copyOf(files));

      assertTrue(rendered.endsWith("… (patch coverage truncated)"), rendered);
      assertTrue(
          rendered.length() <= PatchCoverageResolver.MAX_TOTAL_CHARS + 30,
          "the section is bounded even when every file has many scattered ranges");
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

    @Test
    void dropsAReportEntryThatMatchesMoreThanOneRepositoryFile() {
      // One report entry in a shared package suffix-matches the same-named class in two modules of
      // a multi-module build. Attributing module-a's uncovered lines to module-b (or the other way)
      // is the symmetric twin of the existing N-entries->1-path guard and must be refused.
      var report =
          JacocoCoverageReport.parse(
              new java.io.ByteArrayInputStream(
                  """
                  <report name="multi">
                    <package name="com/example">
                      <sourcefile name="Foo.java"><line nr="1" mi="1" ci="0"/></sourcefile>
                    </package>
                  </report>
                  """
                      .getBytes(StandardCharsets.UTF_8)));
      var files =
          List.of(
              new FileDiff(
                  "module-a/src/main/java/com/example/Foo.java",
                  "modified",
                  1,
                  0,
                  1,
                  "@@ -1,0 +1,1 @@\n+x"),
              new FileDiff(
                  "module-b/src/main/java/com/example/Foo.java",
                  "modified",
                  1,
                  0,
                  1,
                  "@@ -1,0 +1,1 @@\n+x"));

      var uncovered = PatchCoverageResolver.intersectWithAddedLines(report, files);

      assertTrue(
          uncovered.isEmpty(),
          () ->
              "one report entry matches both modules' same-named class; attributing it to either is"
                  + " a guess: "
                  + uncovered.stream().map(PatchCoverageResolver.UncoveredFile::path).toList());
    }

    @Test
    void breaksATieOnPathSoTheOrderIsStableAcrossReviews() {
      var report =
          JacocoCoverageReport.parse(
              new java.io.ByteArrayInputStream(
                  """
                  <report name="r">
                    <package name="a">
                      <sourcefile name="Zebra.java"><line nr="1" mi="1" ci="0"/></sourcefile>
                      <sourcefile name="Alpha.java"><line nr="1" mi="1" ci="0"/></sourcefile>
                    </package>
                  </report>
                  """
                      .getBytes(StandardCharsets.UTF_8)));
      var files =
          List.of(
              new FileDiff("a/Zebra.java", "modified", 1, 0, 1, "@@ -1,0 +1,1 @@\n+x"),
              new FileDiff("a/Alpha.java", "modified", 1, 0, 1, "@@ -1,0 +1,1 @@\n+x"));

      var uncovered = PatchCoverageResolver.intersectWithAddedLines(report, files);

      assertEquals(
          List.of("a/Alpha.java", "a/Zebra.java"),
          uncovered.stream().map(PatchCoverageResolver.UncoveredFile::path).toList(),
          "equal counts must not leave the section's order at the mercy of file-list order");
    }
  }
}
