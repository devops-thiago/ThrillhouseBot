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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Drops {@code description_gaps} bullets that falsely claim an ignored file is missing from the PR.
 * Ignored files (e.g. {@code pom.xml}, lockfiles) appear in the diff overview as {@code (path
 * skipped: matches ignored pattern, +N -M)} without their patch; the model sometimes treats that as
 * "not changed".
 */
final class DescriptionGapFilter {

  private static final Pattern ABSENCE_CLAIM =
      Pattern.compile(
          "(?i)(not\\s+(?:include|present|in)|does\\s+not\\s+include|missing\\s+from|no\\s+\\S+\\s+changes|not\\s+shown|patch\\s+omitted|not\\s+in\\s+the\\s+(?:diff|change))");

  private DescriptionGapFilter() {}

  static List<String> dropIgnoredFilePresenceGaps(
      List<String> gaps,
      List<GitHubPullRequestClient.FileDiff> allFiles,
      List<GitHubPullRequestClient.FileDiff> reviewableFiles) {
    if (gaps == null || gaps.isEmpty() || allFiles == null || allFiles.isEmpty()) {
      return gaps == null ? List.of() : gaps;
    }
    Set<String> reviewable =
        reviewableFiles == null
            ? Set.of()
            : reviewableFiles.stream()
                .map(GitHubPullRequestClient.FileDiff::filename)
                .collect(Collectors.toSet());
    Set<String> ignoredPaths =
        allFiles.stream()
            .map(GitHubPullRequestClient.FileDiff::filename)
            .filter(path -> !reviewable.contains(path))
            .collect(Collectors.toSet());
    if (ignoredPaths.isEmpty()) {
      return gaps;
    }
    return gaps.stream().filter(gap -> !isIgnoredFilePresenceGap(gap, ignoredPaths)).toList();
  }

  private static boolean isIgnoredFilePresenceGap(String gap, Set<String> ignoredPaths) {
    if (gap == null || gap.isBlank() || !ABSENCE_CLAIM.matcher(gap).find()) {
      return false;
    }
    var lower = gap.toLowerCase(Locale.ROOT);
    for (String path : ignoredPaths) {
      if (lower.contains(path.toLowerCase(Locale.ROOT))) {
        return true;
      }
      var base = basename(path);
      if (!base.isBlank() && lower.contains(base.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private static String basename(String path) {
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }
}
