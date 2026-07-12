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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class DescriptionGapFilterTest {

  @Test
  void dropsGapClaimingIgnoredPomXmlIsMissingFromDiff() {
    var all = List.of(file("pom.xml", "modified", 1, 1), file("src/A.java", "modified", 5, 2));
    var reviewable = List.of(file("src/A.java", "modified", 5, 2));
    var gaps =
        List.of(
            "The PR description states the pom.xml is bumped to 0.4.0, but the provided diff does"
                + " not include any pom.xml changes.");

    var filtered = DescriptionGapFilter.dropIgnoredFilePresenceGaps(gaps, all, reviewable);

    assertTrue(filtered.isEmpty());
  }

  @Test
  void keepsGapAboutReviewableFiles() {
    var all = List.of(file("src/A.java", "modified", 5, 2));
    var reviewable = all;
    var gaps = List.of("Description claims tests were added, but no test files changed");

    var filtered = DescriptionGapFilter.dropIgnoredFilePresenceGaps(gaps, all, reviewable);

    assertEquals(gaps, filtered);
  }

  @Test
  void keepsSubstantiveGapMentioningIgnoredFileWithoutAbsenceClaim() {
    var all = List.of(file("pom.xml", "modified", 1, 1), file("README.md", "modified", 2, 0));
    var reviewable = List.of(file("README.md", "modified", 2, 0));
    var gaps = List.of("Description says pom.xml pins Quarkus 3.37 but README still lists 3.36");

    var filtered = DescriptionGapFilter.dropIgnoredFilePresenceGaps(gaps, all, reviewable);

    assertEquals(gaps, filtered);
  }

  @Test
  void keepsGapWhenAbsenceClaimTargetsReviewableFileDespiteIgnoredMention() {
    var all = List.of(file("pom.xml", "modified", 1, 1), file("README.md", "modified", 2, 0));
    var reviewable = List.of(file("README.md", "modified", 2, 0));
    var gaps = List.of("pom.xml is changed, but README is not in the diff");

    var filtered = DescriptionGapFilter.dropIgnoredFilePresenceGaps(gaps, all, reviewable);

    assertEquals(gaps, filtered);
  }

  @Test
  void dropsGapWhenIgnoredFileIsClaimedMissingByName() {
    var all = List.of(file("frontend/package-lock.json", "modified", 10, 2));
    var reviewable = List.<GitHubPullRequestClient.FileDiff>of();
    var gaps = List.of("package-lock.json is not in the diff");

    var filtered = DescriptionGapFilter.dropIgnoredFilePresenceGaps(gaps, all, reviewable);

    assertTrue(filtered.isEmpty());
  }

  @Test
  void returnsEmptyListForNullGaps() {
    assertTrue(
        DescriptionGapFilter.dropIgnoredFilePresenceGaps(
                null, List.of(file("pom.xml", "modified", 1, 1)), List.of())
            .isEmpty());
  }

  @Test
  void returnsGapsUnchangedWhenNoIgnoredFiles() {
    var all = List.of(file("src/A.java", "modified", 1, 0));
    var gaps = List.of("Description claims tests were added, but no test files changed");

    assertEquals(gaps, DescriptionGapFilter.dropIgnoredFilePresenceGaps(gaps, all, all));
  }

  @Test
  void returnsGapsUnchangedWhenAllFilesListEmpty() {
    var gaps = List.of("no changes");

    assertEquals(
        gaps, DescriptionGapFilter.dropIgnoredFilePresenceGaps(gaps, List.of(), List.of()));
  }

  @Test
  void dropsOnlyTheIgnoredFileGapAndKeepsOthers() {
    var all = List.of(file("pom.xml", "modified", 1, 1), file("src/A.java", "modified", 5, 2));
    var reviewable = List.of(file("src/A.java", "modified", 5, 2));
    var gaps =
        List.of(
            "The diff does not include any pom.xml changes.",
            "Description claims tests were added, but no test files changed");

    var filtered = DescriptionGapFilter.dropIgnoredFilePresenceGaps(gaps, all, reviewable);

    assertEquals(
        List.of("Description claims tests were added, but no test files changed"), filtered);
  }

  @Test
  void keepsGapWhenQualifiedPathDiffersFromIgnoredFileWithSameBasename() {
    var all =
        List.of(file("module-a/pom.xml", "modified", 1, 1), file("src/A.java", "modified", 5, 2));
    var reviewable = List.of(file("src/A.java", "modified", 5, 2));
    var gaps = List.of("module-b/pom.xml is not in the diff");

    var filtered = DescriptionGapFilter.dropIgnoredFilePresenceGaps(gaps, all, reviewable);

    assertEquals(gaps, filtered);
  }

  @Test
  void dropsGapWhenQualifiedPathMatchesIgnoredFile() {
    var all = List.of(file("module-a/pom.xml", "modified", 1, 1));
    var reviewable = List.<GitHubPullRequestClient.FileDiff>of();
    var gaps = List.of("module-a/pom.xml is not in the diff");

    var filtered = DescriptionGapFilter.dropIgnoredFilePresenceGaps(gaps, all, reviewable);

    assertTrue(filtered.isEmpty());
  }

  private static GitHubPullRequestClient.FileDiff file(
      String name, String status, int additions, int deletions) {
    return new GitHubPullRequestClient.FileDiff(
        name, status, additions, deletions, additions + deletions, null);
  }
}
