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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.ArtifactZipFetcher;
import dev.thiagogonzaga.thrillhousebot.github.GitHubActionsClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettings;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Patch coverage for the diff under review: the lines this pull request ADDS that the repository's
 * own test run never executed, rendered as a compact prompt section (issue #115).
 *
 * <p><strong>Where the data comes from.</strong> The bot never builds the pull request, so it
 * cannot compute coverage itself and must be handed a report. It reads one the repository's CI
 * already produced: a workflow artifact, named by the repository in {@code
 * .github/thrillhousebot.yml} under {@code review.coverage-artifact}, attached to a completed
 * workflow run for <em>exactly the head commit under review</em>. That constraint is the point —
 * GitHub filters runs by {@code head_sha}, so the line numbers in the report and the line numbers
 * in the diff describe the same revision. A report read from the repository tree, or from a run on
 * a nearby commit, would not have that property, and coverage attributed to the wrong revision
 * points at the wrong lines.
 *
 * <p><strong>What happens when a repository provides nothing — the common case.</strong> Nothing.
 * There is no fallback, no guess, and no heuristic: a repository that declares no artifact name,
 * whose run uploaded no artifact by that name, whose artifact has expired, or whose report is not
 * JaCoCo XML contributes no section at all and its review is byte-for-byte what it is today. This
 * feature is therefore inert for most repositories by design, and deliberately so — an invented
 * coverage signal would be worse than none, because the reviewer is told to raise its confidence on
 * the strength of it.
 *
 * <p>Every fetch is best-effort. Nothing here can fail a review.
 */
@ApplicationScoped
public class PatchCoverageResolver {

  private static final String ACCEPT = "application/vnd.github+json";

  /** Only a finished run has uploaded its artifacts. */
  private static final String COMPLETED = "completed";

  /**
   * Run conclusions whose coverage report may be trusted as measured fact. {@code success} is the
   * ordinary green run; {@code neutral} is a deliberate non-failing outcome (a run that chose to
   * pass without asserting). Every other completed conclusion — {@code failure}, {@code cancelled},
   * {@code timed_out}, {@code skipped}, {@code action_required}, {@code stale} — is a run that did
   * not finish its work, so its report describes an aborted build, not the code under review.
   */
  private static final Set<String> USABLE_CONCLUSIONS = Set.of("success", "neutral");

  /** Runs whose artifact list is fetched before the search gives up, bounding the API cost. */
  static final int MAX_RUNS_PROBED = 10;

  /** Files listed in the rendered section, most uncovered added lines first. */
  static final int MAX_FILES_RENDERED = 12;

  /** Line ranges listed per file before the remainder is rolled up into a count. */
  static final int MAX_RANGES_PER_FILE = 12;

  /** Character cap on the whole section, so coverage context can never rival the diff. */
  static final int MAX_TOTAL_CHARS = 2_000;

  /** Heading of the rendered section. Package-private so tests and callers agree on it. */
  static final String SECTION_HEADING =
      "### Patch coverage for this diff (from the repository's own CI coverage report)";

  private final GitHubActionsClient actionsClient;
  private final ArtifactZipFetcher zipFetcher;
  private final boolean enabled;

  @Inject
  public PatchCoverageResolver(
      @RestClient GitHubActionsClient actionsClient,
      ArtifactZipFetcher zipFetcher,
      ThrillhouseConfig config) {
    this(actionsClient, zipFetcher, config.review().patchCoverage().enabled());
  }

  /** Visible for tests: the deployment kill switch is passed directly. */
  PatchCoverageResolver(
      GitHubActionsClient actionsClient, ArtifactZipFetcher zipFetcher, boolean enabled) {
    this.actionsClient = actionsClient;
    this.zipFetcher = zipFetcher;
    this.enabled = enabled;
  }

  /**
   * The prompt-ready uncovered-changed-lines section, or {@code ""} when there is nothing truthful
   * to say — the feature is off, the repository named no artifact, the head SHA is unknown, no
   * report could be read, or every added line the report knows about is covered.
   *
   * @param reviewableFiles the post-ignore-filter file list the rest of the review already uses, so
   *     a file the ignore set removed from review scope is never reported as under-tested
   */
  String resolve(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      RepoSettings repoSettings,
      List<GitHubPullRequestClient.FileDiff> reviewableFiles) {
    if (!enabled) {
      return "";
    }
    // Never null: RepoSettings normalizes an absent name to "" in its compact constructor.
    var artifactName = repoSettings.coverageArtifact();
    if (artifactName.isBlank()) {
      return "";
    }
    var headSha = req.commitSha();
    if (headSha == null || headSha.isBlank() || reviewableFiles.isEmpty()) {
      return "";
    }
    try {
      var report = loadReport(auth, req.owner(), req.repo(), headSha, artifactName.strip());
      if (report.isEmpty()) {
        return "";
      }
      var uncovered = intersectWithAddedLines(report, reviewableFiles);
      if (uncovered.isEmpty()) {
        Log.debugf("Coverage report for %s covers every added line", headSha);
        return "";
      }
      Log.infof(
          "Patch coverage: %d changed file(s) have added lines with no covering test",
          uncovered.size());
      return render(uncovered);
    } catch (RuntimeException e) {
      Log.warn("Patch-coverage resolution failed, continuing without it", e);
      return "";
    }
  }

  // ---------------------------------------------------------------- sourcing

  /** The coverage report the named artifact holds, or an empty report when there is none. */
  private JacocoCoverageReport loadReport(
      String auth, String owner, String repo, String headSha, String artifactName) {
    var artifactId = findArtifactId(auth, owner, repo, headSha, artifactName);
    if (artifactId == null) {
      Log.debugf("No '%s' artifact on a completed run for %s", artifactName, headSha);
      return JacocoCoverageReport.EMPTY;
    }
    var zipBytes = download(auth, owner, repo, artifactId);
    return JacocoCoverageReport.fromArtifactZip(zipBytes);
  }

  /**
   * The id of the named, unexpired artifact on a completed workflow run for {@code headSha}, or
   * {@code null} when no run has one. The {@code head_sha} filter is applied by GitHub and
   * re-checked here, because attributing another commit's coverage to this diff would point the
   * reviewer at line numbers that mean nothing.
   */
  private Long findArtifactId(
      String auth, String owner, String repo, String headSha, String artifactName) {
    // One page on purpose, and not the single-page truncation this project flags elsewhere.
    // GitHub applies head_sha as a query parameter, so the page holds only the runs for the one
    // commit under review — never the repository's most recent activity — and exceeding
    // RUNS_PER_PAGE would take that many completed workflows or re-runs on a single SHA. If a
    // repository ever does, the cost is bounded and is already this feature's designed degrade
    // path: the named artifact is not found, no coverage section is produced, and the review
    // proceeds exactly as it does for every repository that publishes no report at all. Walking
    // further pages would spend an extra API call on every review to change nothing for any of
    // them.
    var runs =
        actionsClient.listWorkflowRuns(
            auth, ACCEPT, owner, repo, headSha, COMPLETED, GitHubActionsClient.RUNS_PER_PAGE);
    if (runs == null) {
      return null;
    }
    // Declarative rather than a loop with jumps: the policy is "only SUCCEEDED runs for this exact
    // commit, and at most MAX_RUNS_PROBED of them", and saying it once here keeps the walk below to
    // the single thing it does — look for the named artifact. The conclusion filter is necessary
    // but not sufficient: a run reported success can still upload a partial report, and this cannot
    // tell that apart — but a report from a run that failed, was cancelled, or timed out (commonly
    // still uploaded via `if: always()`) is the run stopping short, not measured coverage, and its
    // ci=0 regions must not reach the model as fact.
    var candidates =
        runs.workflowRuns().stream()
            .filter(run -> headSha.equalsIgnoreCase(run.headSha()))
            .filter(run -> isUsableConclusion(run.conclusion()))
            .limit(MAX_RUNS_PROBED)
            .toList();
    for (var run : candidates) {
      var artifactId = artifactIdOnRun(auth, owner, repo, run.id(), artifactName);
      if (artifactId != null) {
        return artifactId;
      }
    }
    return null;
  }

  /** Whether a completed run's conclusion means its coverage report can be trusted as fact. */
  private static boolean isUsableConclusion(String conclusion) {
    return conclusion != null && USABLE_CONCLUSIONS.contains(conclusion.toLowerCase(Locale.ROOT));
  }

  /** The named, unexpired artifact's id on one run, or {@code null} when it has none. */
  private Long artifactIdOnRun(
      String auth, String owner, String repo, long runId, String artifactName) {
    // Single page again, for the same reason and with more headroom: this is scoped to ONE run
    // and asks for GitHub's maximum page size, so it truncates only for a run that uploaded more
    // than ARTIFACTS_PER_PAGE artifacts. The consequence is identical — the report is not found
    // and the feature contributes nothing.
    var artifacts =
        actionsClient.listRunArtifacts(
            auth, ACCEPT, owner, repo, runId, GitHubActionsClient.ARTIFACTS_PER_PAGE);
    if (artifacts == null) {
      return null;
    }
    return artifacts.artifacts().stream()
        .filter(artifact -> !artifact.expired() && artifactName.equalsIgnoreCase(artifact.name()))
        .map(GitHubActionsClient.Artifact::id)
        .findFirst()
        .orElse(null);
  }

  /**
   * The artifact archive's bytes. GitHub answers the download endpoint with a redirect to a
   * pre-signed blob URL, which is fetched separately and without the installation token.
   */
  private byte[] download(String auth, String owner, String repo, long artifactId) {
    try (var response = actionsClient.downloadArtifact(auth, ACCEPT, owner, repo, artifactId)) {
      var location = response == null ? null : response.getLocation();
      if (location == null) {
        Log.debugf("Artifact download for %d returned no redirect location", artifactId);
        return new byte[0];
      }
      return zipFetcher.fetch(location);
    }
  }

  // ---------------------------------------------------------------- intersection

  /** One changed file and the lines it adds that the report records as never executed. */
  record UncoveredFile(String path, NavigableSet<Integer> lines) {}

  /**
   * The added lines of each reviewable file that the report records as executable-but-unhit, in
   * descending order of how many there are, so the render cap drops the least informative files.
   * Deletions and surviving context lines are excluded — only what this pull request introduces is
   * this review's responsibility.
   */
  static List<UncoveredFile> intersectWithAddedLines(
      JacocoCoverageReport report, List<GitHubPullRequestClient.FileDiff> reviewableFiles) {
    // Resolve the whole file list at once so a report entry that suffix-matches more than one of
    // these repository paths (a multi-module build's same-named class) is dropped rather than
    // attributed to each — a per-file lookup cannot see that cross-file collision.
    var uncoveredByPath =
        report.uncoveredLinesByPath(
            reviewableFiles.stream().map(GitHubPullRequestClient.FileDiff::filename).toList());
    var result = new ArrayList<UncoveredFile>();
    for (var file : reviewableFiles) {
      // Absent means either the report says nothing about this file or the attribution was
      // ambiguous and was dropped; a present entry always carries at least one uncovered line.
      var reportedUncovered = uncoveredByPath.get(file.filename());
      if (reportedUncovered == null) {
        continue;
      }
      var uncoveredAdditions = new TreeSet<Integer>();
      for (var added : addedLines(file.patch())) {
        if (reportedUncovered.contains(added)) {
          uncoveredAdditions.add(added);
        }
      }
      if (!uncoveredAdditions.isEmpty()) {
        result.add(new UncoveredFile(file.filename(), uncoveredAdditions));
      }
    }
    result.sort(
        (left, right) -> {
          var bySize = Integer.compare(right.lines().size(), left.lines().size());
          return bySize != 0 ? bySize : left.path().compareTo(right.path());
        });
    return List.copyOf(result);
  }

  /**
   * The new-file line numbers of a patch's added ({@code +}) lines. Context lines advance the
   * counter but are not added lines, and deletions exist only on the left side, so neither is
   * reported as under-tested by this change.
   */
  static NavigableSet<Integer> addedLines(String patch) {
    var added = new TreeSet<Integer>();
    if (patch == null || patch.isBlank()) {
      return added;
    }
    var newLine = 0;
    var inHunk = false;
    for (var raw : patch.split("\n", -1)) {
      var hunkStart = DiffLineResolver.HUNK_HEADER.matcher(raw);
      if (raw.startsWith("@@") && hunkStart.find()) {
        newLine = Integer.parseInt(hunkStart.group(1));
        inHunk = true;
      } else if (inHunk && !raw.isEmpty()) {
        // '+' is an added line and advances the right side; ' ' is context and only advances it;
        // '-' exists on the left side alone and does neither.
        var marker = raw.charAt(0);
        if (marker == '+') {
          added.add(newLine);
          newLine++;
        } else if (marker == ' ') {
          newLine++;
        }
      }
    }
    return added;
  }

  // ---------------------------------------------------------------- rendering

  /** The prompt-ready section: one line per file, its uncovered added lines collapsed to ranges. */
  static String render(List<UncoveredFile> uncovered) {
    var out = new StringBuilder(SECTION_HEADING).append('\n');
    out.append(
        """
        Lines this pull request ADDS that the coverage report for this exact commit records as \
        executable and never executed by any test. Line numbers are new-file numbers, matching \
        the diff.
        """);
    var listed = Math.min(uncovered.size(), MAX_FILES_RENDERED);
    for (var i = 0; i < listed; i++) {
      var file = uncovered.get(i);
      out.append("- ")
          .append(file.path())
          .append(": ")
          .append(formatRanges(file.lines()))
          .append('\n');
    }
    if (uncovered.size() > listed) {
      out.append("- (")
          .append(uncovered.size() - listed)
          .append(" more changed file(s) with uncovered added lines)\n");
    }
    var rendered = out.toString().stripTrailing();
    return rendered.length() > MAX_TOTAL_CHARS
        ? ConfigKeyContextResolver.truncate(rendered, MAX_TOTAL_CHARS)
            + "\n… (patch coverage truncated)"
        : rendered;
  }

  /** Consecutive line numbers collapsed to {@code start-end}, capped at a readable width. */
  static String formatRanges(NavigableSet<Integer> lines) {
    var ranges = new ArrayList<String>();
    Integer start = null;
    Integer previous = null;
    var remaining = 0;
    for (var line : lines) {
      if (start == null) {
        start = line;
      } else if (line != previous + 1) {
        if (ranges.size() >= MAX_RANGES_PER_FILE) {
          remaining++;
        } else {
          ranges.add(formatRange(start, previous));
        }
        start = line;
      }
      previous = line;
    }
    if (start != null) {
      if (ranges.size() >= MAX_RANGES_PER_FILE) {
        remaining++;
      } else {
        ranges.add(formatRange(start, previous));
      }
    }
    var joined = String.join(", ", ranges);
    return remaining == 0 ? joined : joined + ", and " + remaining + " more range(s)";
  }

  private static String formatRange(int start, int end) {
    return start == end ? String.valueOf(start) : start + "-" + end;
  }
}
