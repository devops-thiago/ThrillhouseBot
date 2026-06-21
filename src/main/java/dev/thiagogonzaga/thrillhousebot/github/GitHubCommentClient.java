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

  // GitHub serves 30 issue comments per page by default; request the 100 max and walk a bounded
  // number of pages so the bot's summary is not missed on a busy PR. Comments are returned
  // oldest-first and the summary is posted on the first review, so it normally sits among the
  // earliest comments — but on a PR that already had many comments before that first review the
  // summary can fall past page 1, and a single-page fetch would miss it and re-post a duplicate
  // summary.
  int COMMENTS_PER_PAGE = 100;
  int MAX_COMMENT_PAGES = 10;

  @GET
  @Path("/repos/{owner}/{repo}/issues/{issueNumber}/comments")
  @Produces(MediaType.APPLICATION_JSON)
  List<IssueComment> listCommentsPage(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("issueNumber") int issueNumber,
      @QueryParam("per_page") int perPage,
      @QueryParam("page") int page);

  /**
   * Lists a PR's issue comments (the conversation thread, not the inline diff comments), oldest
   * first, walking pages of {@value #COMMENTS_PER_PAGE} up to {@value #MAX_COMMENT_PAGES} pages so
   * a busy PR's comments are not silently truncated. Used to detect a summary comment the bot
   * already posted so a re-review never duplicates it; a single-page fetch could miss the summary
   * on a busy PR and re-post it. Stops at the first short/empty page.
   */
  default List<IssueComment> listComments(
      String auth, String accept, String owner, String repo, int issueNumber) {
    var all = new ArrayList<IssueComment>();
    List<IssueComment> batch;
    int page = 1;
    do {
      batch = listCommentsPage(auth, accept, owner, repo, issueNumber, COMMENTS_PER_PAGE, page);
      if (batch != null) {
        all.addAll(batch);
      }
      page++;
    } while (batch != null && batch.size() == COMMENTS_PER_PAGE && page <= MAX_COMMENT_PAGES);
    return all;
  }

  record CreateCommentRequest(String body) {}

  record CommentResponse(long id, @JsonProperty("html_url") String htmlUrl) {}

  /** A PR conversation comment, carrying just the body and author needed to spot the bot's own. */
  record IssueComment(String body, GitHubReviewClient.ReviewResponse.User user) {}
}
