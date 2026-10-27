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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import java.util.Comparator;

/**
 * Ranks a pull request's changed files by review priority before they are packed into batches
 * (#666), from the same path globs model routing reads (#43): {@code AI_ROUTING_HIGH_RISK_PATHS}
 * and {@code AI_ROUTING_LOW_RISK_PATHS}. With {@code REVIEW_TRIAGE_ENABLED} on, the planner packs
 * the higher tiers first, so the call cap leaves out the lowest-value files rather than whatever is
 * smallest. Deterministic, and no model call.
 *
 * <p>Build manifests stay first (#935): small, yet they decide what the build produces. A file on a
 * high-risk path, under its new or its old name, comes next, even when a low-risk glob also matches
 * it, as for routing. A file on a low-risk path comes last. Within a tier the larger change goes
 * first, as without triage.
 */
final class FileTriage {

  /** A file's review priority, highest first. */
  enum Tier {
    BUILD_MANIFEST,
    HIGH_RISK,
    NORMAL,
    LOW_RISK
  }

  private final ReviewDiffFormatter.IgnoreGlobs highRiskPaths;
  private final ReviewDiffFormatter.IgnoreGlobs lowRiskPaths;

  FileTriage(
      ReviewDiffFormatter.IgnoreGlobs highRiskPaths, ReviewDiffFormatter.IgnoreGlobs lowRiskPaths) {
    this.highRiskPaths = highRiskPaths;
    this.lowRiskPaths = lowRiskPaths;
  }

  /** The triage {@code config} sets up, read from the model-routing globs. */
  static FileTriage of(ThrillhouseConfig config) {
    var routing = config.ai().routing();
    return new FileTriage(
        ReviewDiffFormatter.IgnoreGlobs.compile(routing.highRiskPaths()),
        ReviewDiffFormatter.IgnoreGlobs.compile(routing.lowRiskPaths()));
  }

  /** {@code file}'s tier. An unnamed file is {@link Tier#NORMAL}: no path says otherwise. */
  Tier tier(FileDiff file) {
    var name = file.filename();
    if (name == null) {
      return Tier.NORMAL;
    }
    if (ReviewDiffFormatter.isBuildManifest(name)) {
      return Tier.BUILD_MANIFEST;
    }
    var previous = file.previousFilename();
    if (highRiskPaths.matches(name) || (previous != null && highRiskPaths.matches(previous))) {
      return Tier.HIGH_RISK;
    }
    return lowRiskPaths.matches(name) ? Tier.LOW_RISK : Tier.NORMAL;
  }

  /** Orders files by {@link #tier}, highest first; the caller breaks ties. */
  Comparator<FileDiff> byTier() {
    return Comparator.comparing(this::tier);
  }
}
