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
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Git Data API: read a commit's tree and write trees, commits, and branch refs. Used by the {@code
 * /fix} command to commit a proposed fix onto a bot-owned branch without a local clone. Every write
 * here requires the App's {@code contents: write} permission.
 */
@RegisterRestClient(configKey = "github-api")
public interface GitHubGitDataClient {

  @GET
  @Path("/repos/{owner}/{repo}/git/commits/{commitSha}")
  @Produces(MediaType.APPLICATION_JSON)
  GitCommit getCommit(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("commitSha") String commitSha);

  @POST
  @Path("/repos/{owner}/{repo}/git/trees")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  GitObject createTree(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      CreateTreeRequest request);

  @POST
  @Path("/repos/{owner}/{repo}/git/commits")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  GitObject createCommit(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      CreateCommitRequest request);

  @POST
  @Path("/repos/{owner}/{repo}/git/refs")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  GitObject createRef(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      CreateRefRequest request);

  /** A commit object; only the tree pointer is read here. */
  record GitCommit(String sha, TreeRef tree) {}

  record TreeRef(String sha) {}

  /**
   * One entry of a tree write. {@code content} carries the new UTF-8 file text inline — GitHub
   * creates the blob implicitly — with {@code mode} {@code 100644} and {@code type} {@code blob}
   * for a regular file.
   */
  record TreeEntry(String path, String mode, String type, String content) {
    /** A regular (non-executable) file entry with inline content. */
    public static TreeEntry file(String path, String content) {
      return new TreeEntry(path, "100644", "blob", content);
    }
  }

  /** Tree write layered on {@code base_tree} so unlisted paths are carried over unchanged. */
  record CreateTreeRequest(@JsonProperty("base_tree") String baseTree, List<TreeEntry> tree) {
    public CreateTreeRequest {
      tree = tree == null ? List.of() : List.copyOf(tree);
    }
  }

  record CreateCommitRequest(String message, String tree, List<String> parents) {
    public CreateCommitRequest {
      parents = parents == null ? List.of() : List.copyOf(parents);
    }
  }

  /** {@code ref} must be fully qualified, e.g. {@code refs/heads/thrillhousebot/fix-12}. */
  record CreateRefRequest(String ref, String sha) {}

  /** A created git object (tree, commit, or ref target); only the sha is read. */
  record GitObject(String sha) {}
}
