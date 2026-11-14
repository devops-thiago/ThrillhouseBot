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
package dev.thiagogonzaga.thrillhousebot.platform;

import java.util.List;

/**
 * What a review reads from the platform that hosts the repository (#37): the pull request, its
 * changed files, a comparison between two commits, one file's content and the repository's file
 * listing. One implementation per platform; {@link CodeHostProducer} hands out the one {@code
 * GIT_PLATFORM} names, so callers never ask which platform they are on.
 *
 * <p>The records are the platform-neutral shape the review pipeline works on. They follow the
 * GitHub vocabulary the pipeline was written against ("pull request", the {@code added} / {@code
 * modified} / {@code removed} / {@code renamed} statuses, a per-file unified-diff {@code patch}
 * that starts at its first hunk header), and another platform's implementation maps its own answer
 * onto it.
 *
 * <p>{@code auth} is the complete {@code Authorization} header value for the platform. {@code
 * owner} and {@code repo} together name the repository: on GitLab {@code owner} is the full
 * namespace path, nested groups included.
 */
public interface CodeHost {

  /** The pull request's title, description and its head and base revisions. */
  PullRequestDetails getPullRequest(String auth, String owner, String repo, int pullNumber);

  /** Every changed file of the pull request, up to the platform's own listing cap. */
  List<FileDiff> getPullRequestFiles(String auth, String owner, String repo, int pullNumber);

  /** The files that differ between two commits, from their merge base. */
  CompareResponse compareCommits(String auth, String owner, String repo, String base, String head);

  /**
   * One file's content at a ref. A file that does not exist surfaces as the REST client's {@code
   * WebApplicationException}, on every platform.
   */
  FileContent getFileContent(String auth, String owner, String repo, String path, String ref);

  /**
   * The repository's whole file listing at a ref, every directory walked.
   *
   * @param ref a commit SHA, branch or tag name
   */
  TreeResponse getTree(String auth, String owner, String repo, String ref);

  /**
   * @param changedFiles the number of changed files the platform reports, 0 when it reports none
   * @param additions added lines across the pull request, 0 when the platform does not report it
   * @param deletions removed lines across the pull request, 0 when the platform does not report it
   */
  record PullRequestDetails(
      String title,
      String body,
      Ref head,
      Ref base,
      int changedFiles,
      int additions,
      int deletions) {
    public PullRequestDetails(String title, String body, Ref head, Ref base) {
      this(title, body, head, base, 0, 0, 0);
    }
  }

  /** A revision: its commit SHA and, when known, the branch name. */
  record Ref(String sha, String ref) {
    public Ref(String sha) {
      this(sha, null);
    }
  }

  /**
   * One changed file.
   *
   * @param status {@code added}, {@code modified}, {@code removed} or {@code renamed}
   * @param patch the file's unified diff from its first hunk header, or null when the platform
   *     sends none (a binary file, a pure rename, a diff over the platform's size limit)
   * @param previousFilename the path before a rename, otherwise null
   */
  record FileDiff(
      String filename,
      String status,
      int additions,
      int deletions,
      int changes,
      String patch,
      String previousFilename) {

    /** Convenience overload when the rename source path is unused. */
    public FileDiff(
        String filename, String status, int additions, int deletions, int changes, String patch) {
      this(filename, status, additions, deletions, changes, patch, null);
    }
  }

  record CompareResponse(int totalCommits, List<FileDiff> files) {
    public CompareResponse {
      files = files == null ? List.of() : List.copyOf(files);
    }
  }

  /**
   * @param content the file's bytes in {@code encoding}
   * @param encoding {@code base64} on every platform so far; line breaks inside the content are
   *     allowed, so decode with the MIME decoder
   */
  record FileContent(String name, String path, String content, String encoding, long size) {}

  /**
   * One entry of a file listing; {@code type} is {@code blob}, {@code tree} or {@code commit}.
   *
   * @param size the blob's size in bytes, 0 when the platform's listing does not carry it
   */
  record TreeEntry(String path, String type, long size) {}

  /**
   * A file listing; {@code truncated} is true when the platform or the page cap dropped entries.
   */
  record TreeResponse(String sha, List<TreeEntry> tree, boolean truncated) {
    public TreeResponse {
      tree = tree == null ? List.of() : List.copyOf(tree);
    }
  }
}
