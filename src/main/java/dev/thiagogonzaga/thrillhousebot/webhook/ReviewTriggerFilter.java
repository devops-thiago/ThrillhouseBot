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
package dev.thiagogonzaga.thrillhousebot.webhook;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/**
 * Decides whether an automatic {@code pull_request} event should trigger a review. Operators use
 * this to cut noise/cost: skip drafts, gate on labels, or restrict base branches. The defaults
 * (skip nothing, require nothing, allow every branch) review every PR, preserving the original
 * behavior. Manual {@code /review} comments bypass these filters entirely.
 */
@ApplicationScoped
public class ReviewTriggerFilter {

  private final boolean skipDrafts;
  private final Set<String> requiredLabels;
  private final Set<String> excludedLabels;
  private final List<PathMatcher> baseBranchMatchers;
  private final List<PathMatcher> ignoredBaseBranchMatchers;

  @Inject
  public ReviewTriggerFilter(ThrillhouseConfig config) {
    this(
        config.webhook().triggers().skipDrafts(),
        config.webhook().triggers().requiredLabels().orElseGet(List::of),
        config.webhook().triggers().excludedLabels().orElseGet(List::of),
        config.webhook().triggers().baseBranches().orElseGet(List::of),
        config.webhook().triggers().ignoredBaseBranches().orElseGet(List::of));
  }

  /** Visible for tests. */
  ReviewTriggerFilter(
      boolean skipDrafts,
      List<String> requiredLabels,
      List<String> excludedLabels,
      List<String> baseBranches,
      List<String> ignoredBaseBranches) {
    this.skipDrafts = skipDrafts;
    this.requiredLabels = normalizeLabels(requiredLabels);
    this.excludedLabels = normalizeLabels(excludedLabels);
    this.baseBranchMatchers = compileGlobs(baseBranches, "base-branches");
    this.ignoredBaseBranchMatchers = compileGlobs(ignoredBaseBranches, "ignored-base-branches");
  }

  /**
   * @return a short, human-readable reason when the PR should not be auto-reviewed, or {@link
   *     Optional#empty()} when it should proceed to dispatch.
   */
  public Optional<String> skipReason(WebhookPayload.PullRequest pr) {
    if (pr == null) {
      return Optional.empty();
    }

    if (skipDrafts && pr.draft()) {
      return Optional.of("PR is a draft (webhook.triggers.skip-drafts=true)");
    }

    var base = pr.base() != null ? pr.base().ref() : null;
    if (matchesAny(ignoredBaseBranchMatchers, base)) {
      return Optional.of("base branch '" + base + "' matches ignored-base-branches");
    }
    if (!baseBranchMatchers.isEmpty() && !matchesAny(baseBranchMatchers, base)) {
      return Optional.of("base branch '" + base + "' is not in the base-branches allowlist");
    }

    var labels = labelNames(pr);
    if (!excludedLabels.isEmpty() && labels.stream().anyMatch(excludedLabels::contains)) {
      return Optional.of("PR carries an excluded label");
    }
    if (!requiredLabels.isEmpty() && labels.stream().noneMatch(requiredLabels::contains)) {
      return Optional.of("PR is missing a required label");
    }

    return Optional.empty();
  }

  private static Set<String> normalizeLabels(List<String> labels) {
    if (labels == null || labels.isEmpty()) {
      return Set.of();
    }
    var normalized = new LinkedHashSet<String>();
    for (String label : labels) {
      if (label != null && !label.isBlank()) {
        normalized.add(label.trim().toLowerCase(Locale.ROOT));
      }
    }
    return Set.copyOf(normalized);
  }

  /** Lower-cased names of the labels currently on the PR; empty when the payload omits them. */
  private static Set<String> labelNames(WebhookPayload.PullRequest pr) {
    if (pr.labels().isEmpty()) {
      return Set.of();
    }
    var names = new LinkedHashSet<String>();
    for (var label : pr.labels()) {
      // pr.labels() comes from List.copyOf, so no null elements — only the name can be absent.
      if (label.name() != null && !label.name().isBlank()) {
        names.add(label.name().trim().toLowerCase(Locale.ROOT));
      }
    }
    return names;
  }

  /**
   * Compiles gitignore-style glob patterns. These follow {@link FileSystems#getPathMatcher}
   * semantics: {@code *} matches within a single path segment and does <em>not</em> cross {@code
   * /}, so operators must use {@code **} to span slashes (e.g. {@code dependabot/**}, or {@code **}
   * alone to match every branch). Unparseable patterns are logged and dropped rather than failing
   * the whole filter.
   */
  private static List<PathMatcher> compileGlobs(List<String> patterns, String settingName) {
    if (patterns == null || patterns.isEmpty()) {
      return List.of();
    }
    var matchers = new ArrayList<PathMatcher>();
    for (String raw : patterns) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      var pattern = raw.trim();
      try {
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
      } catch (InvalidPathException | PatternSyntaxException e) {
        Log.warnf(e, "Ignoring invalid webhook.triggers.%s pattern: %s", settingName, pattern);
      }
    }
    return List.copyOf(matchers);
  }

  private static boolean matchesAny(List<PathMatcher> matchers, String branch) {
    if (matchers.isEmpty() || branch == null || branch.isBlank()) {
      return false;
    }
    Path path;
    try {
      path = Path.of(branch);
    } catch (InvalidPathException _) {
      return false;
    }
    return matchers.stream().anyMatch(m -> m.matches(path));
  }
}
