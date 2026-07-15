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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * GitHub <a href="https://docs.github.com/en/rest/reactions/reactions">reactions API</a> for PR
 * conversation comments and inline review comments. Create and list ride on the existing {@code
 * issues: write} / {@code pull_requests: write} app permissions — no manifest change. GitHub Apps
 * do not receive a {@code reaction} webhook event, so listing is how finding feedback (#324) is
 * captured.
 */
@RegisterRestClient(configKey = "github-api")
public interface GitHubReactionClient {

  @POST
  @Path("/repos/{owner}/{repo}/issues/comments/{commentId}/reactions")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  void createIssueCommentReaction(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("commentId") long commentId,
      CreateReactionRequest request);

  @POST
  @Path("/repos/{owner}/{repo}/pulls/comments/{commentId}/reactions")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  void createReviewCommentReaction(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("commentId") long commentId,
      CreateReactionRequest request);

  @GET
  @Path("/repos/{owner}/{repo}/pulls/comments/{commentId}/reactions")
  @Produces(MediaType.APPLICATION_JSON)
  List<Reaction> listReviewCommentReactions(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("commentId") long commentId,
      @QueryParam("content") String content,
      @QueryParam("per_page") int perPage);

  @GET
  @Path("/repos/{owner}/{repo}/issues/comments/{commentId}/reactions")
  @Produces(MediaType.APPLICATION_JSON)
  List<Reaction> listIssueCommentReactions(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("commentId") long commentId,
      @QueryParam("content") String content,
      @QueryParam("per_page") int perPage);

  /** One of GitHub's fixed reaction contents, e.g. {@code eyes}. */
  record CreateReactionRequest(String content) {}

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Reaction(
      long id, String content, User user, @JsonProperty("created_at") String createdAt) {

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String login, long id) {}
  }
}
