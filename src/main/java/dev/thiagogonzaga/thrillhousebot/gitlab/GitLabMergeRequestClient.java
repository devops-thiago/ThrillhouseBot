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

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The GitLab REST API (v4) reads a review needs (#37). {@code project} is the project's full path
 * ({@code group/subgroup/project}); the client sends it, and a file path, as one encoded path
 * segment, which is how GitLab takes both ({@code GitLabMergeRequestClientTest} pins the encoding).
 */
@RegisterRestClient(configKey = "gitlab-api")
public interface GitLabMergeRequestClient {

  @GET
  @Path("/projects/{project}/merge_requests/{iid}")
  @Produces(MediaType.APPLICATION_JSON)
  MergeRequest getMergeRequest(
      @HeaderParam("Authorization") String auth,
      @PathParam("project") String project,
      @PathParam("iid") int iid);

  /** One page of a merge request's file diffs (GitLab 15.7 and later). */
  @GET
  @Path("/projects/{project}/merge_requests/{iid}/diffs")
  @Produces(MediaType.APPLICATION_JSON)
  List<Diff> getMergeRequestDiffsPage(
      @HeaderParam("Authorization") String auth,
      @PathParam("project") String project,
      @PathParam("iid") int iid,
      @QueryParam("per_page") int perPage,
      @QueryParam("page") int page);

  /** Compares two revisions from their merge base, GitLab's default ({@code straight=false}). */
  @GET
  @Path("/projects/{project}/repository/compare")
  @Produces(MediaType.APPLICATION_JSON)
  Comparison compare(
      @HeaderParam("Authorization") String auth,
      @PathParam("project") String project,
      @QueryParam("from") String from,
      @QueryParam("to") String to);

  @GET
  @Path("/projects/{project}/repository/files/{path}")
  @Produces(MediaType.APPLICATION_JSON)
  RepositoryFile getFile(
      @HeaderParam("Authorization") String auth,
      @PathParam("project") String project,
      @PathParam("path") String path,
      @QueryParam("ref") String ref);

  /** One page of the repository's file listing at a ref. */
  @GET
  @Path("/projects/{project}/repository/tree")
  @Produces(MediaType.APPLICATION_JSON)
  List<TreeItem> getTreePage(
      @HeaderParam("Authorization") String auth,
      @PathParam("project") String project,
      @QueryParam("ref") String ref,
      @QueryParam("recursive") boolean recursive,
      @QueryParam("per_page") int perPage,
      @QueryParam("page") int page);

  /**
   * @param sha the head commit of the source branch
   * @param changesCount the number of changed files as GitLab words it: a number, or a capped
   *     figure such as {@code 1000+}
   */
  @RegisterForReflection
  record MergeRequest(
      String title,
      String description,
      String sha,
      @JsonProperty("source_branch") String sourceBranch,
      @JsonProperty("target_branch") String targetBranch,
      @JsonProperty("diff_refs") DiffRefs diffRefs,
      @JsonProperty("changes_count") String changesCount) {}

  /** {@code baseSha} is the merge base of the source and target branches. */
  @RegisterForReflection
  record DiffRefs(
      @JsonProperty("base_sha") String baseSha,
      @JsonProperty("head_sha") String headSha,
      @JsonProperty("start_sha") String startSha) {}

  /**
   * One file's diff. {@code diff} is the unified diff from its first hunk header, empty for a file
   * with no textual change and for one GitLab collapsed or found too large.
   */
  @RegisterForReflection
  record Diff(
      @JsonProperty("old_path") String oldPath,
      @JsonProperty("new_path") String newPath,
      String diff,
      @JsonProperty("new_file") boolean newFile,
      @JsonProperty("renamed_file") boolean renamedFile,
      @JsonProperty("deleted_file") boolean deletedFile) {}

  @RegisterForReflection
  record Comparison(List<Commit> commits, List<Diff> diffs) {}

  @RegisterForReflection
  record Commit(String id) {}

  /** {@code content} is in {@code encoding}, which GitLab always answers as {@code base64}. */
  @RegisterForReflection
  record RepositoryFile(
      @JsonProperty("file_name") String fileName,
      @JsonProperty("file_path") String filePath,
      long size,
      String encoding,
      String content) {}

  /** {@code type} is {@code blob}, {@code tree} or {@code commit}. */
  @RegisterForReflection
  record TreeItem(String path, String type) {}
}
