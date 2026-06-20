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

@RegisterRestClient(configKey = "github-api")
public interface GitHubCommentClient {

  @POST
  @Path("/repos/{owner}/{repo}/issues/{issueNumber}/comments")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  CommentResponse createComment(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("issueNumber") int issueNumber,
      CreateCommentRequest request);

  /**
   * Lists a PR's issue comments (the conversation thread, not the inline diff comments), oldest
   * first. Used to detect a summary comment the bot already posted so a re-review never duplicates
   * it; {@code perPage} is sized to cover the bot's summary, which is posted on the first review
   * and so sits among the earliest comments.
   */
  @GET
  @Path("/repos/{owner}/{repo}/issues/{issueNumber}/comments")
  @Produces(MediaType.APPLICATION_JSON)
  List<IssueComment> listComments(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("issueNumber") int issueNumber,
      @QueryParam("per_page") int perPage);

  record CreateCommentRequest(String body) {}

  record CommentResponse(long id, @JsonProperty("html_url") String htmlUrl) {}

  /** A PR conversation comment, carrying just the body and author needed to spot the bot's own. */
  record IssueComment(String body, User user) {
    public record User(String login) {}
  }
}
