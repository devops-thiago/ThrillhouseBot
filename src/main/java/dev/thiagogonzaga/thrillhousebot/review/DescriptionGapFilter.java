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
    if (gap == null || gap.isBlank()) {
      return false;
    }
    var lower = gap.toLowerCase(Locale.ROOT);
    for (String path : ignoredPaths) {
      if (absenceTargetsFile(lower, path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when the gap's absence claim is about {@code path} specifically — not merely when the path
   * is mentioned elsewhere in the same sentence (e.g. "pom.xml changed, but README is not in the
   * diff").
   */
  private static boolean absenceTargetsFile(String lower, String path) {
    for (String name : fileNames(path)) {
      String q = Pattern.quote(name);
      if (Pattern.compile(
              "(?:does|do)\\s+not\\s+include\\s+(?:any\\s+)?(?:the\\s+)?(?:\\S+\\s+)*?" + q)
          .matcher(lower)
          .find()) {
        return true;
      }
      if (Pattern.compile("no\\s+" + q + "\\s+changes").matcher(lower).find()) {
        return true;
      }
      if (Pattern.compile(
              "\\b" + q + "\\b[^,]{0,40}\\b(?:is\\s+)?not\\s+(?:included|present|in|shown)\\b")
          .matcher(lower)
          .find()) {
        return true;
      }
      if (Pattern.compile("\\b" + q + "\\b[^,]{0,30}\\bmissing\\b").matcher(lower).find()) {
        return true;
      }
      if (Pattern.compile("\\b" + q + "\\b[^.]{0,30}patch\\s+omitted").matcher(lower).find()) {
        return true;
      }
    }
    return false;
  }

  private static List<String> fileNames(String path) {
    var file = path.toLowerCase(Locale.ROOT);
    var base = basename(path).toLowerCase(Locale.ROOT);
    return file.equals(base) ? List.of(file) : List.of(file, base);
  }

  private static String basename(String path) {
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }
}
