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

import dev.thiagogonzaga.thrillhousebot.platform.CodeHost;
import java.util.ArrayList;
import java.util.List;

/**
 * GitLab's {@link CodeHost} (#37): a merge request read as a pull request.
 *
 * <p>What GitLab does not report is left at the value the record documents for "unknown": a merge
 * request has no added and removed line totals, and a file listing no blob sizes. Per-file line
 * counts are counted from the diff itself, since GitLab sends none.
 */
public final class GitLabCodeHost implements CodeHost {

  static final int PER_PAGE = 100;

  /** As many changed files as the GitHub listing can return (3000). */
  static final int MAX_DIFF_PAGES = 30;

  /** A file listing stops here and reports itself truncated. */
  static final int MAX_TREE_PAGES = 50;

  private final GitLabMergeRequestClient client;

  public GitLabCodeHost(GitLabMergeRequestClient client) {
    this.client = client;
  }

  @Override
  public PullRequestDetails getPullRequest(String auth, String owner, String repo, int pullNumber) {
    var mr = client.getMergeRequest(auth, project(owner, repo), pullNumber);
    if (mr == null) {
      return null;
    }
    var refs = mr.diffRefs();
    var headSha = mr.sha();
    String baseSha = null;
    if (refs != null) {
      baseSha = refs.baseSha();
      if (headSha == null) {
        headSha = refs.headSha();
      }
    }
    return new PullRequestDetails(
        mr.title(),
        mr.description(),
        new Ref(headSha, mr.sourceBranch()),
        new Ref(baseSha, mr.targetBranch()),
        changesCount(mr.changesCount()),
        0,
        0);
  }

  @Override
  public List<FileDiff> getPullRequestFiles(
      String auth, String owner, String repo, int pullNumber) {
    var project = project(owner, repo);
    var all = new ArrayList<FileDiff>();
    List<GitLabMergeRequestClient.Diff> batch;
    int page = 1;
    do {
      batch = client.getMergeRequestDiffsPage(auth, project, pullNumber, PER_PAGE, page);
      if (batch != null) {
        batch.forEach(diff -> all.add(fileDiff(diff)));
      }
      page++;
    } while (batch != null && batch.size() == PER_PAGE && page <= MAX_DIFF_PAGES);
    return all;
  }

  @Override
  public CompareResponse compareCommits(
      String auth, String owner, String repo, String base, String head) {
    var comparison = client.compare(auth, project(owner, repo), base, head);
    if (comparison == null) {
      return null;
    }
    return new CompareResponse(
        comparison.commits().size(),
        comparison.diffs().stream().map(GitLabCodeHost::fileDiff).toList());
  }

  @Override
  public FileContent getFileContent(
      String auth, String owner, String repo, String path, String ref) {
    var file = client.getFile(auth, project(owner, repo), path, ref);
    if (file == null) {
      return null;
    }
    return new FileContent(file.name(), file.path(), file.content(), file.encoding(), file.size());
  }

  @Override
  public TreeResponse getTree(String auth, String owner, String repo, String ref) {
    var project = project(owner, repo);
    var entries = new ArrayList<TreeEntry>();
    List<GitLabMergeRequestClient.TreeItem> batch;
    int page = 1;
    do {
      batch = client.getTreePage(auth, project, ref, true, PER_PAGE, page);
      if (batch != null) {
        batch.forEach(item -> entries.add(new TreeEntry(item.path(), item.type(), 0)));
      }
      page++;
    } while (batch != null && batch.size() == PER_PAGE && page <= MAX_TREE_PAGES);
    var truncated = batch != null && batch.size() == PER_PAGE;
    return new TreeResponse(ref, entries, truncated);
  }

  /** The project's full path: {@code owner} is the namespace, nested groups included. */
  static String project(String owner, String repo) {
    return owner + "/" + repo;
  }

  static FileDiff fileDiff(GitLabMergeRequestClient.Diff diff) {
    var text = diff.diff();
    var patch = text == null || text.isBlank() ? null : text;
    int additions = 0;
    int deletions = 0;
    if (patch != null) {
      for (var line : patch.split("\n", -1)) {
        if (line.startsWith("+")) {
          additions++;
        } else if (line.startsWith("-")) {
          deletions++;
        }
      }
    }
    return new FileDiff(
        diff.newPath(),
        status(diff),
        additions,
        deletions,
        additions + deletions,
        patch,
        diff.renamedFile() ? diff.oldPath() : null);
  }

  private static String status(GitLabMergeRequestClient.Diff diff) {
    if (diff.newFile()) {
      return "added";
    }
    if (diff.deletedFile()) {
      return "removed";
    }
    return diff.renamedFile() ? "renamed" : "modified";
  }

  /** {@code "12"} is 12 and a capped {@code "1000+"} is 1000; anything else is unknown, 0. */
  static int changesCount(String raw) {
    if (raw == null) {
      return 0;
    }
    var digits = raw.strip();
    if (digits.endsWith("+")) {
      digits = digits.substring(0, digits.length() - 1);
    }
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException _) {
      return 0;
    }
  }
}
