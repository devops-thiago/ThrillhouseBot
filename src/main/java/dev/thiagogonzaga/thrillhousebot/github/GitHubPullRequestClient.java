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
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "github-api")
public interface GitHubPullRequestClient {

  // GitHub serves 30 PR files per page by default (100 max) and caps listings at 3000 files.
  int FILES_PER_PAGE = 100;
  int MAX_FILE_PAGES = 30;

  @GET
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}")
  @Produces(MediaType.APPLICATION_JSON)
  PullRequestDetails getPullRequest(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber);

  @GET
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/files")
  @Produces(MediaType.APPLICATION_JSON)
  List<FileDiff> getPullRequestFilesPage(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @QueryParam("per_page") int perPage,
      @QueryParam("page") int page);

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

  @GET
  @Path("/repos/{owner}/{repo}/compare/{base}...{head}")
  @Produces(MediaType.APPLICATION_JSON)
  CompareResponse compareCommits(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("base") String base,
      @PathParam("head") String head);

  @GET
  @Path("/repos/{owner}/{repo}/contents/{path}")
  @Produces(MediaType.APPLICATION_JSON)
  FileContent getFileContent(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("path") String path,
      @QueryParam("ref") String ref);

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
}
