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
public interface GitHubReviewClient {
  @POST
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  ReviewResponse createReview(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      CreateReviewRequest request);

  @GET
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews")
  @Produces(MediaType.APPLICATION_JSON)
  List<ReviewResponse> listReviews(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber);

  // GitHub serves 30 inline review comments per page by default; request the 100 max and walk a
  // bounded number of pages so follow-up dedup, /resolve and the unresolved-status backstop never
  // run on a silently truncated set on a busy PR.
  int COMMENTS_PER_PAGE = 100;
  int MAX_COMMENT_PAGES = 10;

  @GET
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/comments")
  @Produces(MediaType.APPLICATION_JSON)
  List<PullRequestComment> listPullRequestCommentsPage(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @QueryParam("per_page") int perPage,
      @QueryParam("page") int page);

  /**
   * Lists a PR's inline review comments, walking pages of {@value #COMMENTS_PER_PAGE} up to {@value
   * #MAX_COMMENT_PAGES} pages so a busy PR's threads are not silently truncated. A single-page
   * fetch caps at GitHub's 30-per-page default and drops everything past page one, which would make
   * follow-up dedup re-raise replied findings and leave addressed threads unresolved. Stops at the
   * first short/empty page.
   */
  default List<PullRequestComment> listPullRequestComments(
      String auth, String accept, String owner, String repo, int pullNumber) {
    var all = new ArrayList<PullRequestComment>();
    List<PullRequestComment> batch;
    int page = 1;
    do {
      batch =
          listPullRequestCommentsPage(
              auth, accept, owner, repo, pullNumber, COMMENTS_PER_PAGE, page);
      if (batch != null) {
        all.addAll(batch);
      }
      page++;
    } while (batch != null && batch.size() == COMMENTS_PER_PAGE && page <= MAX_COMMENT_PAGES);
    return all;
  }

  @POST
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/comments")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  PullRequestCommentResponse createPullRequestComment(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      CreatePullRequestCommentRequest request);

  @POST
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/comments/{commentId}/replies")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  PullRequestCommentResponse replyToReviewComment(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @PathParam("commentId") long commentId,
      ReplyToReviewCommentRequest request);

  @DELETE
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews/{reviewId}")
  void deletePendingReview(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @PathParam("reviewId") long reviewId);

  record CreateReviewRequest(
      @JsonProperty("commit_id") String commitId,
      String body,
      String event, // APPROVE, REQUEST_CHANGES, COMMENT
      List<ReviewComment> comments) {
    public CreateReviewRequest {
      comments = comments == null ? List.of() : List.copyOf(comments);
    }
  }

  record ReviewComment(
      String path,
      Integer line,
      @JsonProperty("start_line") Integer startLine,
      @JsonProperty("start_side") String startSide,
      String side,
      String body) {}

  record CreatePullRequestCommentRequest(
      @JsonProperty("commit_id") String commitId,
      String body,
      String path,
      int line,
      String side) {}

  /** Reply posted into an existing review thread, keyed by the thread's root comment id in path. */
  record ReplyToReviewCommentRequest(String body) {}

  record PullRequestCommentResponse(long id, String body, String path, Integer line) {}

  /** An inline review comment; replies carry the root comment's id in {@code inReplyToId}. */
  record PullRequestComment(
      long id,
      @JsonProperty("in_reply_to_id") Long inReplyToId,
      String path,
      String body,
      ReviewResponse.User user) {}

  record ReviewResponse(
      long id,
      String body,
      String state, // APPROVED, CHANGES_REQUESTED, COMMENTED
      @JsonProperty("commit_id") String commitId,
      User user) {
    public record User(String login) {}
  }
}
