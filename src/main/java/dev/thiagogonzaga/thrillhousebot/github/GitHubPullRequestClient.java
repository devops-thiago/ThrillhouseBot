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

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "github-api")
@RegisterProvider(GitHubErrorLogger.class)
public interface GitHubPullRequestClient {

  // GitHub serves 30 PR files per page by default (100 max) and caps listings at 3000 files.
  int FILES_PER_PAGE = 100;
  int MAX_FILE_PAGES = 30;

  /** One HTTP attempt at reading a PR. Callers want {@link #getPullRequest} instead. */
  @GET
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}")
  @Produces(MediaType.APPLICATION_JSON)
  PullRequestDetails getPullRequestOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber);

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

  /** One HTTP attempt at editing a PR. Callers want {@link #updatePullRequest} instead. */
  @PATCH
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  PullRequestDetails updatePullRequestOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      UpdatePullRequestRequest request);

  /**
   * Replaces a PR's title and body — the opt-in {@code /describe} apply path — with the same
   * throttle backoff every other GitHub write gets. Losing this write to a throttle would post a
   * confirmation comment describing an edit that never happened, so it retries like the comment
   * writes do rather than only healing a rejected credential.
   */
  default PullRequestDetails updatePullRequest(
      String auth,
      String accept,
      String owner,
      String repo,
      int pullNumber,
      UpdatePullRequestRequest request) {
    return GitHubWriteRetry.DEFAULT.call(
        "an update of the title/body of PR " + owner + "/" + repo + "#" + pullNumber,
        auth,
        credential -> updatePullRequestOnce(credential, accept, owner, repo, pullNumber, request));
  }

  /** One HTTP attempt at a files page. Callers want {@link #getPullRequestFilesPage} instead. */
  @GET
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/files")
  @Produces(MediaType.APPLICATION_JSON)
  List<FileDiff> getPullRequestFilesPageOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @QueryParam("per_page") int perPage,
      @QueryParam("page") int page);

  /** One page of a PR's files, healing a rejected credential per page (#626). */
  default List<FileDiff> getPullRequestFilesPage(
      String auth,
      String accept,
      String owner,
      String repo,
      int pullNumber,
      int perPage,
      int page) {
    return GitHubTokenRefresh.SHARED.retrying(
        "PR files page " + page + " of " + owner + "/" + repo + "#" + pullNumber,
        auth,
        credential ->
            getPullRequestFilesPageOnce(
                credential, accept, owner, repo, pullNumber, perPage, page));
  }

  /**
   * Every changed file in the PR, walking pages of {@value #FILES_PER_PAGE} up to {@value
   * #MAX_FILE_PAGES} pages. Without pagination GitHub returns only the first 30 files, silently
   * truncating the diff (and therefore the review) for larger PRs.
   */
  default List<FileDiff> getPullRequestFiles(
      String auth, String accept, String owner, String repo, int pullNumber) {
    var all = new ArrayList<FileDiff>();
    List<FileDiff> batch;
    int page = 1;
    do {
      batch = getPullRequestFilesPage(auth, accept, owner, repo, pullNumber, FILES_PER_PAGE, page);
      if (batch != null) {
        all.addAll(batch);
      }
      page++;
    } while (batch != null && batch.size() == FILES_PER_PAGE && page <= MAX_FILE_PAGES);
    return all;
  }

  /** One HTTP attempt at a comparison. Callers want {@link #compareCommits} instead. */
  @GET
  @Path("/repos/{owner}/{repo}/compare/{base}...{head}")
  @Produces(MediaType.APPLICATION_JSON)
  CompareResponse compareCommitsOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("base") String base,
      @PathParam("head") String head);

  /** Compares two commits, healing a rejected credential once (#626). */
  default CompareResponse compareCommits(
      String auth, String accept, String owner, String repo, String base, String head) {
    return GitHubTokenRefresh.SHARED.retrying(
        "compare " + base + "..." + head + " on " + owner + "/" + repo,
        auth,
        credential -> compareCommitsOnce(credential, accept, owner, repo, base, head));
  }

  /** One HTTP attempt at reading a file. Callers want {@link #getFileContent} instead. */
  @GET
  @Path("/repos/{owner}/{repo}/contents/{path}")
  @Produces(MediaType.APPLICATION_JSON)
  FileContent getFileContentOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("path") String path,
      @QueryParam("ref") String ref);

  /** Reads one file's content at a ref, healing a rejected credential once (#626). */
  default FileContent getFileContent(
      String auth, String accept, String owner, String repo, String path, String ref) {
    return GitHubTokenRefresh.SHARED.retrying(
        "file content of " + path + " on " + owner + "/" + repo,
        auth,
        credential -> getFileContentOnce(credential, accept, owner, repo, path, ref));
  }

  /**
   * The repository's file listing at a ref, in one call. {@code recursive=1} walks every directory
   * so a caller can locate a file by name without probing paths; GitHub caps the response and sets
   * {@code truncated} when it did.
   *
   * @param treeSha a tree SHA, commit SHA, branch or tag name
   */
  @GET
  @Path("/repos/{owner}/{repo}/git/trees/{treeSha}")
  @Produces(MediaType.APPLICATION_JSON)
  TreeResponse getTreeOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("treeSha") String treeSha,
      @QueryParam("recursive") String recursive);

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
      @JsonProperty("changed_files") int changedFiles,
      int additions,
      int deletions) {
    public PullRequestDetails(String title, String body, Ref head, Ref base) {
      this(title, body, head, base, 0, 0, 0);
    }
  }

  record Ref(String sha, String ref) {
    public Ref(String sha) {
      this(sha, null);
    }
  }

  /** GitHub's hard maximum PR-title length, in characters; a longer title is rejected with 422. */
  int TITLE_MAX_LENGTH = 256;

  /**
   * Body of the PR update PATCH. Only the title and body fields are sent, so nothing else about the
   * PR (state, base, …) can change. Both fields are capped to the limit GitHub enforces for them,
   * like every other outgoing text field: the body with the shared truncation notice, the title
   * with a plain cut because the multi-line notice cannot go in a single-line field.
   */
  record UpdatePullRequestRequest(String title, String body) {
    public UpdatePullRequestRequest {
      if (title != null && title.length() > TITLE_MAX_LENGTH) {
        int keep = TITLE_MAX_LENGTH;
        // Never leave a dangling high surrogate at the cut point.
        if (Character.isHighSurrogate(title.charAt(keep - 1))) {
          keep--;
        }
        title = title.substring(0, keep);
      }
      body = CommentBodyLimit.cap(body);
    }
  }

  record FileDiff(
      String filename,
      String status, // added, modified, removed, renamed
      int additions,
      int deletions,
      int changes,
      String patch,
      @JsonProperty("previous_filename") String previousFilename) {

    /** Convenience overload when the rename source path is unused. */
    public FileDiff(
        String filename, String status, int additions, int deletions, int changes, String patch) {
      this(filename, status, additions, deletions, changes, patch, null);
    }
  }

  record CompareResponse(@JsonProperty("total_commits") int totalCommits, List<FileDiff> files) {
    public CompareResponse {
      files = files == null ? List.of() : List.copyOf(files);
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
