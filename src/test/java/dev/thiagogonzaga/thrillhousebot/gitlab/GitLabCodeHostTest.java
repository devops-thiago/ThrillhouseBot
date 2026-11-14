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
package dev.thiagogonzaga.thrillhousebot.gitlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.gitlab.GitLabMergeRequestClient.Commit;
import dev.thiagogonzaga.thrillhousebot.gitlab.GitLabMergeRequestClient.Comparison;
import dev.thiagogonzaga.thrillhousebot.gitlab.GitLabMergeRequestClient.Diff;
import dev.thiagogonzaga.thrillhousebot.gitlab.GitLabMergeRequestClient.DiffRefs;
import dev.thiagogonzaga.thrillhousebot.gitlab.GitLabMergeRequestClient.MergeRequest;
import dev.thiagogonzaga.thrillhousebot.gitlab.GitLabMergeRequestClient.RepositoryFile;
import dev.thiagogonzaga.thrillhousebot.gitlab.GitLabMergeRequestClient.TreeItem;
import dev.thiagogonzaga.thrillhousebot.platform.CodeHost.FileDiff;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitLabCodeHostTest {

  private static final String AUTH = "Bearer token";
  private static final String PROJECT = "group/sub/app";

  private final GitLabMergeRequestClient client = mock(GitLabMergeRequestClient.class);
  private final GitLabCodeHost host = new GitLabCodeHost(client);

  @Test
  void aMergeRequestReadsAsAPullRequestWithItsMergeBaseAsTheBase() {
    when(client.getMergeRequest(AUTH, PROJECT, 7))
        .thenReturn(
            new MergeRequest(
                "Add retries",
                "Retries the upload",
                "head1",
                "feature/retries",
                "main",
                new DiffRefs("base1", "head1", "start1"),
                "12"));

    var pr = host.getPullRequest(AUTH, "group/sub", "app", 7);

    assertEquals("Add retries", pr.title());
    assertEquals("Retries the upload", pr.body());
    assertEquals("head1", pr.head().sha());
    assertEquals("feature/retries", pr.head().ref());
    assertEquals("base1", pr.base().sha());
    assertEquals("main", pr.base().ref());
    assertEquals(12, pr.changedFiles());
    assertEquals(0, pr.additions());
    assertEquals(0, pr.deletions());
  }

  @Test
  void aMergeRequestWithoutItsOwnShaTakesTheHeadOfItsDiffRefs() {
    when(client.getMergeRequest(AUTH, PROJECT, 7))
        .thenReturn(
            new MergeRequest(
                "t", null, null, "b", "main", new DiffRefs("base1", "head2", "s"), null));

    var pr = host.getPullRequest(AUTH, "group/sub", "app", 7);

    assertEquals("head2", pr.head().sha());
    assertEquals("base1", pr.base().sha());
  }

  @Test
  void aMergeRequestWithNoDiffRefsHasNoBaseSha() {
    when(client.getMergeRequest(AUTH, PROJECT, 7))
        .thenReturn(new MergeRequest("t", null, null, "b", "main", null, null));

    var pr = host.getPullRequest(AUTH, "group/sub", "app", 7);

    assertNull(pr.head().sha());
    assertNull(pr.base().sha());
    assertEquals(0, pr.changedFiles());
  }

  @Test
  void aMissingAnswerStaysMissing() {
    assertNull(host.getPullRequest(AUTH, "group/sub", "app", 7));
    assertNull(host.compareCommits(AUTH, "group/sub", "app", "a", "b"));
    assertNull(host.getFileContent(AUTH, "group/sub", "app", "README.md", "main"));
  }

  @Test
  void theChangedFilesCountReadsACappedFigureAndTreatsAnythingElseAsUnknown() {
    assertEquals(12, GitLabCodeHost.changesCount(" 12 "));
    assertEquals(1000, GitLabCodeHost.changesCount("1000+"));
    assertEquals(0, GitLabCodeHost.changesCount(null));
    assertEquals(0, GitLabCodeHost.changesCount("many"));
  }

  @Test
  void aDiffIsMappedToItsStatusAndItsLinesAreCountedFromTheHunks() {
    var modified =
        GitLabCodeHost.fileDiff(
            new Diff(
                "src/A.java",
                "src/A.java",
                "@@ -1,3 +1,4 @@\n context\n-old\n+new\n+added\n\\ No newline at end of file\n",
                false,
                false,
                false));

    assertEquals("src/A.java", modified.filename());
    assertEquals("modified", modified.status());
    assertEquals(2, modified.additions());
    assertEquals(1, modified.deletions());
    assertEquals(3, modified.changes());
    assertTrue(modified.patch().startsWith("@@ -1,3 +1,4 @@"));
    assertNull(modified.previousFilename());

    assertEquals(
        "added", GitLabCodeHost.fileDiff(diff("@@ -0,0 +1 @@\n+x\n", true, false, false)).status());
    assertEquals(
        "removed",
        GitLabCodeHost.fileDiff(diff("@@ -1 +0,0 @@\n-x\n", false, false, true)).status());
  }

  @Test
  void aRenameCarriesItsOldPathAndAPureOneHasNoPatch() {
    var renamed =
        GitLabCodeHost.fileDiff(new Diff("old/A.java", "new/A.java", "", false, true, false));

    assertEquals("new/A.java", renamed.filename());
    assertEquals("renamed", renamed.status());
    assertEquals("old/A.java", renamed.previousFilename());
    assertNull(renamed.patch());
    assertEquals(0, renamed.changes());
    assertNull(GitLabCodeHost.fileDiff(new Diff("a", "a", null, false, false, false)).patch());
  }

  @Test
  void theChangedFilesAreReadPageByPageUntilAShortPage() {
    when(client.getMergeRequestDiffsPage(AUTH, PROJECT, 7, GitLabCodeHost.PER_PAGE, 1))
        .thenReturn(
            Collections.nCopies(
                GitLabCodeHost.PER_PAGE, diff("@@ -1 +1 @@\n+x\n", false, false, false)));
    when(client.getMergeRequestDiffsPage(AUTH, PROJECT, 7, GitLabCodeHost.PER_PAGE, 2))
        .thenReturn(List.of(diff("@@ -1 +1 @@\n+y\n", false, false, false)));

    List<FileDiff> files = host.getPullRequestFiles(AUTH, "group/sub", "app", 7);

    assertEquals(GitLabCodeHost.PER_PAGE + 1, files.size());
    verify(client, never()).getMergeRequestDiffsPage(eq(AUTH), eq(PROJECT), eq(7), anyInt(), eq(3));
  }

  @Test
  void theChangedFilesStopAtThePageCapAndSurviveAMissingPage() {
    when(client.getMergeRequestDiffsPage(eq(AUTH), eq(PROJECT), eq(7), anyInt(), anyInt()))
        .thenReturn(Collections.nCopies(GitLabCodeHost.PER_PAGE, diff("", false, false, false)));

    assertEquals(
        GitLabCodeHost.PER_PAGE * GitLabCodeHost.MAX_DIFF_PAGES,
        host.getPullRequestFiles(AUTH, "group/sub", "app", 7).size());
    when(client.getMergeRequestDiffsPage(eq(AUTH), eq(PROJECT), eq(8), anyInt(), anyInt()))
        .thenReturn(null);
    assertTrue(host.getPullRequestFiles(AUTH, "group/sub", "app", 8).isEmpty());
  }

  @Test
  void aComparisonCountsItsCommitsAndMapsItsDiffs() {
    when(client.compare(AUTH, PROJECT, "a", "b"))
        .thenReturn(
            new Comparison(
                List.of(new Commit("c1"), new Commit("c2")),
                List.of(diff("@@ -1 +1 @@\n+x\n", false, false, false))));

    var comparison = host.compareCommits(AUTH, "group/sub", "app", "a", "b");

    assertEquals(2, comparison.totalCommits());
    assertEquals(1, comparison.files().size());
    assertEquals(1, comparison.files().getFirst().additions());
  }

  @Test
  void anEmptyComparisonHasNoCommitsAndNoFiles() {
    when(client.compare(AUTH, PROJECT, "a", "b")).thenReturn(new Comparison(null, null));

    var comparison = host.compareCommits(AUTH, "group/sub", "app", "a", "b");

    assertEquals(0, comparison.totalCommits());
    assertTrue(comparison.files().isEmpty());
  }

  @Test
  void aFileIsReadAtItsRef() {
    when(client.getFile(AUTH, PROJECT, "docs/README.md", "main"))
        .thenReturn(new RepositoryFile("README.md", "docs/README.md", 5, "base64", "aGVsbG8="));

    var file = host.getFileContent(AUTH, "group/sub", "app", "docs/README.md", "main");

    assertEquals("README.md", file.name());
    assertEquals("docs/README.md", file.path());
    assertEquals("aGVsbG8=", file.content());
    assertEquals("base64", file.encoding());
    assertEquals(5, file.size());
  }

  @Test
  void theFileListingIsReadPageByPageWithNoSizes() {
    when(client.getTreePage(AUTH, PROJECT, "main", true, GitLabCodeHost.PER_PAGE, 1))
        .thenReturn(
            Collections.nCopies(GitLabCodeHost.PER_PAGE, new TreeItem("src/A.java", "blob")));
    when(client.getTreePage(AUTH, PROJECT, "main", true, GitLabCodeHost.PER_PAGE, 2))
        .thenReturn(List.of(new TreeItem("src", "tree")));

    var tree = host.getTree(AUTH, "group/sub", "app", "main");

    assertEquals(GitLabCodeHost.PER_PAGE + 1, tree.tree().size());
    assertEquals("blob", tree.tree().getFirst().type());
    assertEquals(0, tree.tree().getFirst().size());
    assertEquals("main", tree.sha());
    assertFalse(tree.truncated());
  }

  @Test
  void aFileListingPastThePageCapIsTruncatedAndAMissingPageIsNot() {
    when(client.getTreePage(eq(AUTH), eq(PROJECT), eq("main"), eq(true), anyInt(), anyInt()))
        .thenReturn(Collections.nCopies(GitLabCodeHost.PER_PAGE, new TreeItem("a", "blob")));

    var tree = host.getTree(AUTH, "group/sub", "app", "main");

    assertTrue(tree.truncated());
    assertEquals(GitLabCodeHost.PER_PAGE * GitLabCodeHost.MAX_TREE_PAGES, tree.tree().size());
    when(client.getTreePage(eq(AUTH), eq(PROJECT), eq("other"), eq(true), anyInt(), anyInt()))
        .thenReturn(null);
    var missing = host.getTree(AUTH, "group/sub", "app", "other");
    assertFalse(missing.truncated());
    assertTrue(missing.tree().isEmpty());
  }

  private static Diff diff(String text, boolean added, boolean renamed, boolean deleted) {
    return new Diff("a", "a", text, added, renamed, deleted);
  }
}
