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
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/**
 * Picks the model a pull request's review calls go to (#43). A low-risk pull request goes to {@code
 * AI_ROUTING_LOW_RISK_MODEL}; everything else, and every pull request while that is unset, goes to
 * {@code AI_MODEL}.
 *
 * <p>Low risk is decided from cheap deterministic signals over the reviewable files (ignored files
 * are never sent, so they do not count). A pull request with any file, under its new or its old
 * name, on a high-risk path is never low risk, even when that file also matches a low-risk glob (a
 * README under {@code .github/workflows/}). Otherwise it is low risk when every file is a low-risk
 * path (docs, text, lockfiles), or when the change is at most {@code
 * AI_ROUTING_LOW_RISK_MAX_CHANGED_LINES} lines. A doubtful case resolves to the stronger model: a
 * missed defect costs more than the tokens the cheaper model saves.
 */
@ApplicationScoped
public class ModelRouter {

  private final Optional<String> lowRiskModel;
  private final int maxChangedLines;
  private final ReviewDiffFormatter.IgnoreGlobs lowRiskPaths;
  private final ReviewDiffFormatter.IgnoreGlobs highRiskPaths;

  @Inject
  public ModelRouter(ThrillhouseConfig config) {
    var routing = config.ai().routing();
    this.lowRiskModel = routing.lowRiskModel().filter(m -> !m.isBlank());
    this.maxChangedLines = routing.lowRiskMaxChangedLines();
    this.lowRiskPaths = ReviewDiffFormatter.IgnoreGlobs.compile(routing.lowRiskPaths());
    this.highRiskPaths = ReviewDiffFormatter.IgnoreGlobs.compile(routing.highRiskPaths());
  }

  /** The model the review calls over {@code files} go to; empty for {@code AI_MODEL}. */
  public Optional<String> route(List<FileDiff> files) {
    if (lowRiskModel.isEmpty() || files.isEmpty()) {
      return Optional.empty();
    }
    var reason = lowRiskReason(files);
    if (reason == null) {
      return Optional.empty();
    }
    Log.infof(
        "Routing the review of %d file(s) to the low-risk model %s: %s",
        files.size(), lowRiskModel.get(), reason);
    return lowRiskModel;
  }

  /** Why {@code files} are low risk, or {@code null} when they are not. */
  private String lowRiskReason(List<FileDiff> files) {
    // The high-risk list wins over an overlapping low-risk glob: the operator named that path.
    if (files.stream().anyMatch(this::highRisk)) {
      return null;
    }
    if (files.stream().allMatch(f -> lowRiskPaths.matches(f.filename()))) {
      return "every file is a low-risk path";
    }
    if (maxChangedLines <= 0) {
      return null;
    }
    var changedLines = files.stream().mapToLong(f -> (long) f.additions() + f.deletions()).sum();
    return changedLines <= maxChangedLines
        ? changedLines + " changed line(s), at most " + maxChangedLines
        : null;
  }

  private boolean highRisk(FileDiff file) {
    return highRiskPaths.matches(file.filename())
        || (file.previousFilename() != null && highRiskPaths.matches(file.previousFilename()));
  }
}
