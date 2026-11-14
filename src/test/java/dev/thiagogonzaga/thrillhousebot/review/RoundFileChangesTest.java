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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.CompareResponse;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.FileChange;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which files changed since each prior round's head (#1319), from one comparison per head. */
class RoundFileChangesTest {

  private static final String HEAD = "headsha1";
  private static final String ROUND = "roundsha";

  private final GitHubPullRequestClient prClient = mock(GitHubPullRequestClient.class);

  private DiffLineResolver.RoundChanges changes(String... heads) {
    return RoundFileChanges.of(
        prClient, "auth", "o", "r", HEAD, Arrays.asList(heads), heads.length, false);
  }

  private void compares(String base, CompareResponse response) {
    when(prClient.compareCommits(anyString(), anyString(), eq("o"), eq("r"), eq(base), eq(HEAD)))
        .thenReturn(response);
  }

  private static FileDiff file(String name) {
    return new FileDiff(name, "modified", 1, 1, 2, "@@ -1 +1 @@\n-a\n+b");
  }

  @Test
  void aListedFileChangedAndAnyOtherIsUnchangedFromOneComparison() {
    compares(ROUND, new CompareResponse(1, List.of(file("src/A.java")), "ahead"));
    var changes = changes(ROUND);

    assertEquals(FileChange.CHANGED, changes.since(0, "src/A.java"));
    assertEquals(FileChange.CHANGED, changes.since(0, "repo/src/A.java"));
    assertEquals(FileChange.UNCHANGED, changes.since(0, "src/B.java"));
    verify(prClient, times(1))
        .compareCommits(anyString(), anyString(), eq("o"), eq("r"), eq(ROUND), eq(HEAD));
  }

  @Test
  void aRenamedFileChangedUnderBothNamesUnlessItsContentDidNot() {
    compares(
        ROUND,
        new CompareResponse(
            1,
            List.of(
                new FileDiff("src/New.java", "renamed", 1, 0, 1, "", "src/Old.java"),
                new FileDiff("src/Moved.java", "renamed", 0, 0, 0, null, "src/Was.java")),
            "identical"));
    var changes = changes(ROUND);

    assertEquals(FileChange.CHANGED, changes.since(0, "src/Old.java"));
    assertEquals(FileChange.CHANGED, changes.since(0, "src/New.java"));
    assertEquals(FileChange.UNCHANGED, changes.since(0, "src/Was.java"));
  }

  @Test
  void theRoundsOwnHeadIsUnchangedWithoutAComparison() {
    var changes = changes("HEADSHA1");

    assertEquals(FileChange.UNCHANGED, changes.since(0, "src/A.java"));
    verify(prClient, never())
        .compareCommits(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void aDivergedOrUnknownComparisonTellsNothing() {
    compares("diverged", new CompareResponse(2, List.of(file("src/A.java")), "diverged"));
    compares("nostatus", new CompareResponse(1, List.of(file("src/A.java"))));
    var changes = changes("diverged", "nostatus");

    assertEquals(FileChange.UNKNOWN, changes.since(0, "src/B.java"));
    assertEquals(FileChange.UNKNOWN, changes.since(1, "src/B.java"));
  }

  @Test
  void aComparisonAtGitHubsFileCapTellsNothing() {
    var files = new ArrayList<FileDiff>();
    for (var i = 0; i < RoundFileChanges.MAX_LISTED_FILES; i++) {
      files.add(file("f" + i));
    }
    compares(ROUND, new CompareResponse(1, files, "ahead"));

    assertEquals(FileChange.UNKNOWN, changes(ROUND).since(0, "src/B.java"));
  }

  @Test
  void aFailedComparisonTellsNothingAndIsNotRetried() {
    when(prClient.compareCommits(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("boom"));
    var changes = changes(ROUND);

    assertEquals(FileChange.UNKNOWN, changes.since(0, "src/A.java"));
    assertEquals(FileChange.UNKNOWN, changes.since(0, "src/B.java"));
    verify(prClient, times(1))
        .compareCommits(anyString(), anyString(), eq("o"), eq("r"), eq(ROUND), eq(HEAD));
  }

  @Test
  void roundsPastTheComparisonCapTellNothing() {
    var heads = new String[RoundFileChanges.MAX_COMPARISONS + 1];
    for (var i = 0; i < heads.length; i++) {
      heads[i] = "round" + i;
      compares(heads[i], new CompareResponse(1, List.of(), "ahead"));
    }
    var changes = changes(heads);

    for (var i = 0; i < RoundFileChanges.MAX_COMPARISONS; i++) {
      assertEquals(FileChange.UNCHANGED, changes.since(i, "src/A.java"));
    }
    assertEquals(FileChange.UNKNOWN, changes.since(RoundFileChanges.MAX_COMPARISONS, "src/A.java"));
  }

  @Test
  void aRoundWithNoKnownHeadOrOutsideTheHistoryTellsNothing() {
    var changes = changes(ROUND, null, " ");

    assertEquals(FileChange.UNKNOWN, changes.since(-1, "src/A.java"));
    assertEquals(FileChange.UNKNOWN, changes.since(1, "src/A.java"));
    assertEquals(FileChange.UNKNOWN, changes.since(2, "src/A.java"));
    assertEquals(FileChange.UNKNOWN, changes.since(3, "src/A.java"));
  }

  @Test
  void aHistoryThatCannotBeAlignedKnowsNothing() {
    var heads = List.of(ROUND);
    var none = DiffLineResolver.RoundChanges.NONE;

    assertSame(none, RoundFileChanges.of(prClient, "a", "o", "r", HEAD, heads, 2, false));
    assertSame(none, RoundFileChanges.of(prClient, "a", "o", "r", HEAD, heads, 1, true));
    assertSame(none, RoundFileChanges.of(prClient, "a", "o", "r", null, heads, 1, false));
    assertSame(none, RoundFileChanges.of(prClient, "a", "o", "r", " ", heads, 1, false));
    assertEquals(FileChange.UNKNOWN, none.since(0, "src/A.java"));
  }

  @Test
  void theResolverAsksTheRoundChangesAndKnowsNothingOfANullFile() {
    var resolver =
        new DiffLineResolver(
            java.util.Map.of(),
            List.of(),
            DiffLineResolver.HeadFiles.NONE,
            (round, path) -> FileChange.CHANGED);

    assertEquals(FileChange.CHANGED, resolver.fileChangeSince(0, "src/A.java"));
    assertEquals(FileChange.UNKNOWN, resolver.fileChangeSince(0, null));
    assertEquals(
        FileChange.UNKNOWN, new DiffLineResolver(java.util.Map.of()).fileChangeSince(0, "a"));
  }
}
