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
import dev.thiagogonzaga.thrillhousebot.github.GitHubCheckRunClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Builds the opt-in static-analysis review context (#34): the annotations that completed checks had
 * attached to the files this pull request changes, when the review started. That covers Code
 * Scanning — GitHub publishes its alerts on a pull request as annotations of a check run named
 * after the analysis tool, such as "CodeQL", which passes when no alert reaches the failure
 * threshold — and every linter that annotates. With the CI-failure context on (#59, {@link
 * CiFailureContextResolver}) a failing run whose annotations it already reads is left out here;
 * every other run is kept, so the most severe alerts are not the ones the review misses.
 *
 * <p>The checks are not fetched here: they come from the {@link CiStatusEvaluator.CiEvaluation} the
 * CI gate reads for the same head ({@link CiStatusEvaluator.CiFailures#annotated()}), which needs
 * only the Checks permission the App already has. This class reads one page of annotations for at
 * most {@link ThrillhouseConfig.StaticAnalysisConfig#MAX_CHECKS} of them — checks of an app other
 * than GitHub Actions first, since a passing Actions job commonly carries nothing but the runner's
 * own notices — and keeps those on a reviewable changed file, less an Actions job's runner notices
 * on the workflow ({@link CiFailureContextResolver#isRunnerNotice}), so an alert on a file the pull
 * request does not touch never reaches the prompt.
 *
 * <p>Every string here is written by tooling the pull request can change, so each field goes
 * through {@link CiFailureContextResolver#oneLine} (control and formatting characters removed,
 * clipped) and the section is capped at {@code max-chars}; the caller fences it like every other
 * untrusted slot. Best-effort throughout: nothing here can fail a review.
 */
@ApplicationScoped
public class StaticAnalysisContextResolver {

  private static final String ACCEPT = "application/vnd.github+json";

  private static final String SUCCESS = "success";

  /**
   * Annotations requested per check — one page, one call, at the largest page GitHub serves, so a
   * linter that annotates many files is less likely to push the changed files' annotations off it.
   */
  static final int ANNOTATIONS_PAGE_SIZE = 100;

  /** Annotations rendered per check, failure-level first. */
  static final int MAX_ANNOTATIONS_PER_CHECK = 10;

  /** The line the section opens with, before the number of checks it describes. */
  static final String OPENING =
      "Checks on this commit that had completed when this review started, with annotations on"
          + " files this pull request changes: ";

  /** Appended when the whole section had to be cut to {@code max-chars}. */
  static final String TRUNCATION_NOTE =
      "[Static-analysis context truncated to its character budget]";

  private final GitHubCheckRunClient checkRunClient;
  private final boolean enabled;
  private final boolean ciContextOn;
  private final int maxChars;

  @Inject
  public StaticAnalysisContextResolver(
      @RestClient GitHubCheckRunClient checkRunClient, ThrillhouseConfig config) {
    this(
        checkRunClient,
        config.review().staticAnalysis().enabled(),
        config.review().ciContext().enabled(),
        config.review().staticAnalysis().maxChars());
  }

  /**
   * Visible for tests: the switches and the cap are passed directly. {@code ciContextOn} says
   * whether the CI-failure context already carries the failing checks' annotations.
   */
  StaticAnalysisContextResolver(
      GitHubCheckRunClient checkRunClient, boolean enabled, boolean ciContextOn, int maxChars) {
    this.checkRunClient = checkRunClient;
    this.enabled = enabled;
    this.ciContextOn = ciContextOn;
    this.maxChars = maxChars;
  }

  /** Whether the static-analysis context is switched on for this deployment. */
  boolean enabled() {
    return enabled;
  }

  /**
   * The static-analysis section for the review prompt, unfenced, or {@code ""} when the feature is
   * off, no completed check had annotations when {@code evaluation} was read, or none of them is on
   * a file in {@code changedFiles}.
   */
  String resolve(
      String auth,
      String owner,
      String repo,
      CiStatusEvaluator.CiEvaluation evaluation,
      List<GitHubPullRequestClient.FileDiff> changedFiles) {
    if (!enabled
        || evaluation == null
        || evaluation.failures().annotated().isEmpty()
        || changedFiles == null
        || changedFiles.isEmpty()) {
      return "";
    }
    var paths =
        changedFiles.stream()
            .map(GitHubPullRequestClient.FileDiff::filename)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    return render(auth, owner, repo, candidates(evaluation.failures()), paths);
  }

  /**
   * The annotated checks worth a call, in the order they are read: every one but the check runs
   * whose annotations the CI-failure section already reads (with that section on, a failing run
   * among the {@link CiFailureContextResolver#MAX_CHECKS_DETAILED} it describes in full), and
   * checks of an app other than GitHub Actions ahead of Actions jobs and, within each, a run that
   * did not succeed ahead of one that did, the read's own order kept otherwise. Runs are matched by
   * id, not name: a commit status or a same-named run that failed does not hide a run that passed.
   */
  private List<CiStatusEvaluator.AnnotatedCheck> candidates(CiStatusEvaluator.CiFailures failures) {
    var readByCiSection =
        ciContextOn ? CiFailureContextResolver.runsWithAnnotationsRead(failures) : Set.<Long>of();
    return failures.annotated().stream()
        .filter(check -> !readByCiSection.contains(check.checkRunId()))
        .sorted(
            Comparator.comparing(StaticAnalysisContextResolver::isActionsJob)
                .thenComparing(check -> SUCCESS.equals(check.conclusion())))
        .toList();
  }

  private static boolean isActionsJob(CiStatusEvaluator.AnnotatedCheck check) {
    return CiFailureContextResolver.GITHUB_ACTIONS_APP.equals(check.appSlug());
  }

  /** Failure-level first, then warnings, then notices and anything else. */
  private static int levelRank(GitHubCheckRunClient.Annotation annotation) {
    var level = annotation.annotationLevel();
    if ("failure".equalsIgnoreCase(level)) {
      return 0;
    }
    return "warning".equalsIgnoreCase(level) ? 1 : 2;
  }

  private String render(
      String auth,
      String owner,
      String repo,
      List<CiStatusEvaluator.AnnotatedCheck> candidates,
      Set<String> paths) {
    var blocks = new ArrayList<String>();
    var read = Math.min(candidates.size(), ThrillhouseConfig.StaticAnalysisConfig.MAX_CHECKS);
    for (var check : candidates.subList(0, read)) {
      var onChangedFiles = annotationsOnChangedFiles(auth, owner, repo, check, paths);
      if (!onChangedFiles.isEmpty()) {
        blocks.add(block(check, onChangedFiles));
      }
    }
    if (blocks.isEmpty()) {
      return "";
    }
    var section = OPENING + blocks.size() + "\n" + String.join("", blocks);
    return CiFailureContextResolver.cap(section, maxChars, TRUNCATION_NOTE);
  }

  private List<GitHubCheckRunClient.Annotation> annotationsOnChangedFiles(
      String auth,
      String owner,
      String repo,
      CiStatusEvaluator.AnnotatedCheck check,
      Set<String> paths) {
    List<GitHubCheckRunClient.Annotation> page;
    try {
      page =
          checkRunClient.listAnnotations(
              auth, ACCEPT, owner, repo, check.checkRunId(), ANNOTATIONS_PAGE_SIZE);
    } catch (RuntimeException e) {
      Log.debugf(e, "Could not read annotations of check run %d", check.checkRunId());
      return List.of();
    }
    if (page == null) {
      return List.of();
    }
    // Only an Actions job carries the runner's own notices on the workflow; another app's notice
    // on a workflow file is that tool's finding about it.
    var dropRunnerNotices = isActionsJob(check);
    return page.stream()
        .filter(Objects::nonNull)
        .filter(annotation -> annotation.path() != null && paths.contains(annotation.path()))
        .filter(
            annotation ->
                !(dropRunnerNotices && CiFailureContextResolver.isRunnerNotice(annotation)))
        // An error a tool reports matters more than its warnings, and those more than its notices.
        .sorted(Comparator.comparingInt(StaticAnalysisContextResolver::levelRank))
        .toList();
  }

  private static String block(
      CiStatusEvaluator.AnnotatedCheck check, List<GitHubCheckRunClient.Annotation> annotations) {
    var sb = new StringBuilder("\n");
    sb.append(CiFailureContextResolver.CHECK_HEADING)
        .append(CiFailureContextResolver.oneLine(check.name()))
        .append(CiFailureContextResolver.CONCLUSION)
        .append(
            CiFailureContextResolver.oneLine(
                check.conclusion() == null ? "unknown" : check.conclusion()))
        .append(")\n");
    var shown = Math.min(annotations.size(), MAX_ANNOTATIONS_PER_CHECK);
    sb.append("Annotations on changed files (")
        .append(shown)
        .append(" of ")
        .append(annotations.size())
        .append(" read):\n");
    for (var annotation : annotations.subList(0, shown)) {
      sb.append("- ").append(CiFailureContextResolver.formatAnnotation(annotation)).append('\n');
    }
    return sb.toString();
  }
}
