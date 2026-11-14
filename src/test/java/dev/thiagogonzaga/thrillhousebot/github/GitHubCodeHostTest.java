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
package dev.thiagogonzaga.thrillhousebot.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class GitHubCodeHostTest {

  private static final String AUTH = "Bearer token";
  private static final String ACCEPT = "application/vnd.github+json";

  private final GitHubPullRequestClient client = mock(GitHubPullRequestClient.class);
  private final GitHubCodeHost host = new GitHubCodeHost(client);

  @Test
  void aPullRequestIsCopiedFieldForField() {
    when(client.getPullRequest(AUTH, ACCEPT, "o", "r", 7))
        .thenReturn(
            new GitHubPullRequestClient.PullRequestDetails(
                "Title",
                "Body",
                new GitHubPullRequestClient.Ref("head1", "feature"),
                new GitHubPullRequestClient.Ref("base1", "main"),
                3,
                10,
                4));

    var pr = host.getPullRequest(AUTH, "o", "r", 7);

    assertEquals("Title", pr.title());
    assertEquals("Body", pr.body());
    assertEquals("head1", pr.head().sha());
    assertEquals("feature", pr.head().ref());
    assertEquals("base1", pr.base().sha());
    assertEquals("main", pr.base().ref());
    assertEquals(3, pr.changedFiles());
    assertEquals(10, pr.additions());
    assertEquals(4, pr.deletions());
  }

  @Test
  void aPullRequestWithoutRefsKeepsThemMissing() {
    when(client.getPullRequest(AUTH, ACCEPT, "o", "r", 7))
        .thenReturn(new GitHubPullRequestClient.PullRequestDetails("Title", null, null, null));

    var pr = host.getPullRequest(AUTH, "o", "r", 7);

    assertNull(pr.head());
    assertNull(pr.base());
  }

  @Test
  void aMissingAnswerStaysMissing() {
    assertNull(host.getPullRequest(AUTH, "o", "r", 7));
    assertNull(host.compareCommits(AUTH, "o", "r", "a", "b"));
    assertNull(host.getFileContent(AUTH, "o", "r", "README.md", "main"));
    assertNull(host.getTree(AUTH, "o", "r", "main"));
    when(client.getPullRequestFiles(AUTH, ACCEPT, "o", "r", 7)).thenReturn(null);
    assertNull(host.getPullRequestFiles(AUTH, "o", "r", 7));
  }

  @Test
  void theChangedFilesKeepTheirPatchAndRenameSource() {
    when(client.getPullRequestFiles(AUTH, ACCEPT, "o", "r", 7))
        .thenReturn(
            List.of(
                new GitHubPullRequestClient.FileDiff(
                    "new/A.java", "renamed", 2, 1, 3, "@@ -1 +1 @@", "old/A.java")));

    var file = host.getPullRequestFiles(AUTH, "o", "r", 7).getFirst();

    assertEquals("new/A.java", file.filename());
    assertEquals("renamed", file.status());
    assertEquals(2, file.additions());
    assertEquals(1, file.deletions());
    assertEquals(3, file.changes());
    assertEquals("@@ -1 +1 @@", file.patch());
    assertEquals("old/A.java", file.previousFilename());
  }

  @Test
  void aComparisonKeepsItsCommitCountAndFiles() {
    when(client.compareCommits(AUTH, ACCEPT, "o", "r", "a", "b"))
        .thenReturn(
            new GitHubPullRequestClient.CompareResponse(
                2,
                List.of(new GitHubPullRequestClient.FileDiff("A.java", "modified", 1, 0, 1, "p"))));

    var comparison = host.compareCommits(AUTH, "o", "r", "a", "b");

    assertEquals(2, comparison.totalCommits());
    assertEquals("A.java", comparison.files().getFirst().filename());
  }

  @Test
  void aFileIsReadAtItsRef() {
    when(client.getFileContent(AUTH, ACCEPT, "o", "r", "README.md", "main"))
        .thenReturn(
            new GitHubPullRequestClient.FileContent("README.md", "README.md", "aGk=", "base64", 2));

    var file = host.getFileContent(AUTH, "o", "r", "README.md", "main");

    assertEquals("README.md", file.name());
    assertEquals("README.md", file.path());
    assertEquals("aGk=", file.content());
    assertEquals("base64", file.encoding());
    assertEquals(2, file.size());
  }

  @Test
  void theFileListingIsAskedForRecursively() {
    when(client.getTree(AUTH, ACCEPT, "o", "r", "main", "1"))
        .thenReturn(
            new GitHubPullRequestClient.TreeResponse(
                "tree1",
                List.of(new GitHubPullRequestClient.TreeEntry("A.java", "blob", 9)),
                true));

    var tree = host.getTree(AUTH, "o", "r", "main");

    assertEquals("tree1", tree.sha());
    assertEquals("A.java", tree.tree().getFirst().path());
    assertEquals("blob", tree.tree().getFirst().type());
    assertEquals(9, tree.tree().getFirst().size());
    assertTrue(tree.truncated());
  }
}
