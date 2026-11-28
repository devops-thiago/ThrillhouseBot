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

import java.util.List;

/**
 * The reads of a pull request and the repository it changes, made with an installation token the
 * caller already holds. {@link GitHubPullRequestReads} makes each one through hub4j. The {@code
 * Once} methods make one attempt; callers use the others, which heal a rejected credential (#626).
 * The {@code accept} argument is not sent: hub4j chooses the media type.
 */
public interface GitHubPullRequestClient {

  // GitHub serves 30 PR files per page by default (100 max) and caps listings at 3000 files.
  int FILES_PER_PAGE = 100;
  int MAX_FILE_PAGES = 30;

  /** One HTTP attempt at reading a PR. Callers want {@link #getPullRequest} instead. */
  PullRequestDetails getPullRequestOnce(
      String auth, String accept, String owner, String repo, int pullNumber);

  /**
   * Reads a PR, minting a fresh installation token and repeating the GET once when GitHub rejects
   * the credential (#626) — a read late in a long review carries the token resolved at its start,
   * and a 401 leaves nothing to repair afterwards, unlike a write it simply never happened. See
   * {@link GitHubTokenRefresh}.
   */
  default PullRequestDetails getPullRequest(
      String auth, String accept, String owner, String repo, int pullNumber) {
    return GitHubTokenRefresh.SHARED.retrying(
        "PR read " + owner + "/" + repo + "#" + pullNumber,
        auth,
        credential -> getPullRequestOnce(credential, accept, owner, repo, pullNumber));
  }

  /** One attempt at the whole file listing. Callers want {@link #getPullRequestFiles} instead. */
  List<FileDiff> getPullRequestFilesOnce(
      String auth, String accept, String owner, String repo, int pullNumber);

  /**
   * Every changed file in the PR, in pages of {@value #FILES_PER_PAGE} up to {@value
   * #MAX_FILE_PAGES} pages. Without pagination GitHub returns only the first 30 files, silently
   * truncating the diff (and therefore the review) for larger PRs.
   */
  default List<FileDiff> getPullRequestFiles(
      String auth, String accept, String owner, String repo, int pullNumber) {
    return GitHubTokenRefresh.SHARED.retrying(
        "PR files of " + owner + "/" + repo + "#" + pullNumber,
        auth,
        credential -> getPullRequestFilesOnce(credential, accept, owner, repo, pullNumber));
  }

  /** One HTTP attempt at a comparison. Callers want {@link #compareCommits} instead. */
  CompareResponse compareCommitsOnce(
      String auth, String accept, String owner, String repo, String base, String head);

  /** Compares two commits, healing a rejected credential once (#626). */
  default CompareResponse compareCommits(
      String auth, String accept, String owner, String repo, String base, String head) {
    return GitHubTokenRefresh.SHARED.retrying(
        "compare " + base + "..." + head + " on " + owner + "/" + repo,
        auth,
        credential -> compareCommitsOnce(credential, accept, owner, repo, base, head));
  }

  /** One HTTP attempt at reading a file. Callers want {@link #getFileContent} instead. */
  FileContent getFileContentOnce(
      String auth, String accept, String owner, String repo, String path, String ref);

  /**
   * Reads one file's content at a ref, healing a rejected credential once (#626). The path comes
   * from model output and repository files, so one that is not a plain repository-relative path
   * ({@link #isRepositoryRelative}) is answered as a missing file without a request: the URL it
   * would build could address another endpoint of the API.
   */
  default FileContent getFileContent(
      String auth, String accept, String owner, String repo, String path, String ref) {
    if (!isRepositoryRelative(path)) {
      return null;
    }
    return GitHubTokenRefresh.SHARED.retrying(
        "file content of " + path + " on " + owner + "/" + repo,
        auth,
        credential -> getFileContentOnce(credential, accept, owner, repo, path, ref));
  }

  /**
   * Whether {@code path} names a file inside the repository: not blank, no leading {@code /}, and
   * no {@code ..} segment under either separator. A name that only contains two dots, such as
   * {@code a..b.txt}, is an ordinary file.
   */
  static boolean isRepositoryRelative(String path) {
    if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")) {
      return false;
    }
    for (var segment : path.split("[/\\\\]", -1)) {
      if (segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  /**
   * The repository's file listing at a ref. {@code recursive=1} walks every directory so a caller
   * can locate a file by name without probing paths; GitHub caps the response and sets {@code
   * truncated} when it did.
   *
   * @param treeSha a tree SHA, commit SHA, branch or tag name
   */
  TreeResponse getTreeOnce(
      String auth, String accept, String owner, String repo, String treeSha, String recursive);

  /** Reads a tree listing, healing a rejected credential once (#626). */
  default TreeResponse getTree(
      String auth, String accept, String owner, String repo, String treeSha, String recursive) {
    return GitHubTokenRefresh.SHARED.retrying(
        "tree of " + owner + "/" + repo + " at " + treeSha,
        auth,
        credential -> getTreeOnce(credential, accept, owner, repo, treeSha, recursive));
  }

  record PullRequestDetails(
      String title,
      String body,
      Ref head,
      Ref base,
      int changedFiles,
      int additions,
      int deletions,
      // open or closed (a merged pull request is closed); null when GitHub gives none.
      String state,
      // false when GitHub gives none.
      boolean draft) {
    public PullRequestDetails(String title, String body, Ref head, Ref base) {
      this(title, body, head, base, 0, 0, 0);
    }

    /** Details of a pull request that is not a draft. */
    public PullRequestDetails(
        String title,
        String body,
        Ref head,
        Ref base,
        int changedFiles,
        int additions,
        int deletions,
        String state) {
      this(title, body, head, base, changedFiles, additions, deletions, state, false);
    }

    /** Details whose state is not known. */
    public PullRequestDetails(
        String title,
        String body,
        Ref head,
        Ref base,
        int changedFiles,
        int additions,
        int deletions) {
      this(title, body, head, base, changedFiles, additions, deletions, null);
    }
  }

  record Ref(String sha, String ref) {
    public Ref(String sha) {
      this(sha, null);
    }
  }

  record FileDiff(
      String filename,
      String status, // added, modified, removed, renamed
      int additions,
      int deletions,
      int changes,
      String patch,
      String previousFilename,
      // The file's blob SHA at the pull request's head (#1319); null when GitHub gives none.
      String sha) {

    /** A file whose blob SHA is not known. */
    public FileDiff(
        String filename,
        String status,
        int additions,
        int deletions,
        int changes,
        String patch,
        String previousFilename) {
      this(filename, status, additions, deletions, changes, patch, previousFilename, null);
    }

    /** Convenience overload when the rename source path is unused. */
    public FileDiff(
        String filename, String status, int additions, int deletions, int changes, String patch) {
      this(filename, status, additions, deletions, changes, patch, null, null);
    }
  }

  /**
   * A comparison of two commits. {@code status} is GitHub's word for how the head relates to the
   * base ({@code ahead}, {@code behind}, {@code identical} or {@code diverged}); with a three-dot
   * comparison, {@code files} is the change from their merge base, which is the change from the
   * base only when the head is {@code ahead} of it or {@code identical} to it.
   */
  record CompareResponse(int totalCommits, List<FileDiff> files, String status) {
    public CompareResponse {
      files = files == null ? List.of() : List.copyOf(files);
    }

    /** A comparison whose status is not known. */
    public CompareResponse(int totalCommits, List<FileDiff> files) {
      this(totalCommits, files, null);
    }
  }

  record FileContent(
      String name,
      String path,
      String content, // Base64-encoded
      String encoding, // "base64"
      long size) {}

  /**
   * One entry of a git tree listing; {@code type} is {@code blob}, {@code tree} or {@code commit}.
   */
  record TreeEntry(String path, String type, long size) {}

  /**
   * A git tree listing; {@code truncated} is true when GitHub dropped entries from the response.
   */
  record TreeResponse(String sha, List<TreeEntry> tree, boolean truncated) {
    public TreeResponse {
      tree = tree == null ? List.of() : List.copyOf(tree);
    }
  }
}
