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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import io.quarkus.logging.Log;
import java.util.List;

/**
 * Best-effort loaders shared by the review path and the on-request suggestion commands ({@code
 * /describe}, {@code /changelog}, {@code /add-docs}). Each fetch degrades to empty/null on failure
 * — enrichment context must never fail the operation — so the soft fetch-and-degrade lives here
 * once instead of being copy-pasted into every caller. {@code context} labels the operation in
 * logs.
 */
final class SoftLoaders {

  private static final String ACCEPT = "application/vnd.github+json";

  private SoftLoaders() {}

  /** The PR's current title/body/refs, or {@code null} if it could not be fetched. */
  static GitHubPullRequestClient.PullRequestDetails pullRequest(
      GitHubPullRequestClient prClient,
      String auth,
      String owner,
      String repo,
      int prNumber,
      String context) {
    try {
      return prClient.getPullRequest(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.warnf(e, "Failed to fetch PR for %s on %s/%s #%d", context, owner, repo, prNumber);
      return null;
    }
  }

  /** The PR's changed files, or an empty list if they could not be fetched. */
  static List<GitHubPullRequestClient.FileDiff> files(
      GitHubPullRequestClient prClient,
      String auth,
      String owner,
      String repo,
      int prNumber,
      String context) {
    try {
      return prClient.getPullRequestFiles(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.warnf(e, "Failed to fetch PR files for %s on %s/%s #%d", context, owner, repo, prNumber);
      return List.of();
    }
  }

  /** The detected project stack, or empty when it could not be resolved. */
  static String projectStack(
      ProjectStackResolver resolver,
      String owner,
      String repo,
      String defaultBranch,
      long installationId,
      String context) {
    try {
      return resolver.resolve(owner, repo, defaultBranch, installationId);
    } catch (RuntimeException e) {
      Log.warnf(e, "Project stack resolution failed for %s, continuing without it", context);
      return "";
    }
  }

  /**
   * The resolved repository instructions, or {@link
   * InstructionsResolver.ResolvedInstructions#EMPTY}.
   */
  static InstructionsResolver.ResolvedInstructions instructions(
      InstructionsResolver resolver,
      String owner,
      String repo,
      String defaultBranch,
      long installationId,
      String context) {
    try {
      return resolver.resolve(owner, repo, defaultBranch, installationId);
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Instructions resolution failed for %s on %s/%s, continuing without them",
          context,
          owner,
          repo);
      return InstructionsResolver.ResolvedInstructions.EMPTY;
    }
  }
}
