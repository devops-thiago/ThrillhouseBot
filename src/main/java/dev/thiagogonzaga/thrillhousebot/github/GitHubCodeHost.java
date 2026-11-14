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

import dev.thiagogonzaga.thrillhousebot.platform.CodeHost;
import java.util.List;

/**
 * GitHub's {@link CodeHost}: the REST client's answers, record for record. The client keeps its own
 * wire records and its credential healing (#626); this class only adds the media type and copies
 * the fields across.
 */
public final class GitHubCodeHost implements CodeHost {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String RECURSIVE = "1";

  private final GitHubPullRequestClient client;

  public GitHubCodeHost(GitHubPullRequestClient client) {
    this.client = client;
  }

  @Override
  public PullRequestDetails getPullRequest(String auth, String owner, String repo, int pullNumber) {
    var pr = client.getPullRequest(auth, ACCEPT, owner, repo, pullNumber);
    if (pr == null) {
      return null;
    }
    return new PullRequestDetails(
        pr.title(),
        pr.body(),
        ref(pr.head()),
        ref(pr.base()),
        pr.changedFiles(),
        pr.additions(),
        pr.deletions());
  }

  @Override
  public List<FileDiff> getPullRequestFiles(
      String auth, String owner, String repo, int pullNumber) {
    return files(client.getPullRequestFiles(auth, ACCEPT, owner, repo, pullNumber));
  }

  @Override
  public CompareResponse compareCommits(
      String auth, String owner, String repo, String base, String head) {
    var comparison = client.compareCommits(auth, ACCEPT, owner, repo, base, head);
    if (comparison == null) {
      return null;
    }
    return new CompareResponse(comparison.totalCommits(), files(comparison.files()));
  }

  @Override
  public FileContent getFileContent(
      String auth, String owner, String repo, String path, String ref) {
    var file = client.getFileContent(auth, ACCEPT, owner, repo, path, ref);
    if (file == null) {
      return null;
    }
    return new FileContent(file.name(), file.path(), file.content(), file.encoding(), file.size());
  }

  @Override
  public TreeResponse getTree(String auth, String owner, String repo, String ref) {
    var tree = client.getTree(auth, ACCEPT, owner, repo, ref, RECURSIVE);
    if (tree == null) {
      return null;
    }
    return new TreeResponse(
        tree.sha(),
        tree.tree().stream().map(e -> new TreeEntry(e.path(), e.type(), e.size())).toList(),
        tree.truncated());
  }

  private static Ref ref(GitHubPullRequestClient.Ref ref) {
    return ref == null ? null : new Ref(ref.sha(), ref.ref());
  }

  private static List<FileDiff> files(List<GitHubPullRequestClient.FileDiff> files) {
    if (files == null) {
      return null;
    }
    return files.stream()
        .map(
            f ->
                new FileDiff(
                    f.filename(),
                    f.status(),
                    f.additions(),
                    f.deletions(),
                    f.changes(),
                    f.patch(),
                    f.previousFilename()))
        .toList();
  }
}
