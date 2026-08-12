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
public interface GitHubCommentClient {

  /** One HTTP attempt at creating a comment. Callers want {@link #createComment} instead. */
  @POST
  @Path("/repos/{owner}/{repo}/issues/{issueNumber}/comments")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  CommentResponse createCommentOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("issueNumber") int issueNumber,
      CreateCommentRequest request);

  /**
   * Posts a comment, backing off and reposting while GitHub is throttling (#568). This is the last
   * step of a command that has already run its model call to completion, so a 403 from GitHub's
   * content-creation secondary rate limit used to discard a finished, paid-for generation outright
   * — 7 of 56 responses in the burst that motivated this. {@link GitHubWriteRetry} repeats the post
   * only on a positively identified throttle, never on an ambiguous failure that might already have
   * created the comment, so no reply is ever posted twice.
   *
   * <p>This is also the surface that tells the user when a post was lost anyway (#578): a comment
   * dropped once the budget is spent leaves a notice behind, and the next comment to land on the
   * same pull request carries it up front. See {@link GitHubLostWrites} for why the notice travels
   * with a comment rather than being posted as one.
   */
  default CommentResponse createComment(
      String auth,
      String accept,
      String owner,
      String repo,
      int issueNumber,
      CreateCommentRequest request) {
    return GitHubLostWrites.SHARED.carrying(
        new GitHubLostWrites.Target(owner, repo, issueNumber),
        notice ->
            GitHubWriteRetry.DEFAULT.call(
                "a comment on " + owner + "/" + repo + " #" + issueNumber,
                auth,
                credential ->
                    createCommentOnce(
                        credential,
                        accept,
                        owner,
                        repo,
                        issueNumber,
                        new CreateCommentRequest(
                            GitHubLostWrites.prepend(notice, request.body())))));
  }

  // GitHub serves 30 issue comments per page by default; 100 is the maximum.
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

  @GET
  @Path("/repos/{owner}/{repo}/issues/{issueNumber}")
  @Produces(MediaType.APPLICATION_JSON)
  IssueDetails getIssue(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("issueNumber") int issueNumber);

  /** One HTTP attempt at editing a comment. Callers want {@link #updateComment} instead. */
  @PATCH
  @Path("/repos/{owner}/{repo}/issues/comments/{commentId}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  CommentResponse updateCommentOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("commentId") long commentId,
      CreateCommentRequest request);

  /**
   * Edits a comment, with the same throttle backoff {@link #createComment} gets. Editing the bot's
   * existing summary in place carries the regenerated markdown of a superseded round, so losing it
   * to a throttle leaves the PR showing a summary that describes code the diff no longer has.
   */
  default CommentResponse updateComment(
      String auth,
      String accept,
      String owner,
      String repo,
      long commentId,
      CreateCommentRequest request) {
    return GitHubWriteRetry.DEFAULT.call(
        "an edit of comment " + commentId + " on " + owner + "/" + repo,
        auth,
        credential -> updateCommentOnce(credential, accept, owner, repo, commentId, request));
  }

  record CreateCommentRequest(String body) {
    public CreateCommentRequest {
      body = CommentBodyLimit.cap(body);
    }
  }

  /** Title and body of a linked issue, fetched to ground the bug-fix efficacy check. */
  record IssueDetails(int number, String title, String body) {}

  record CommentResponse(long id, @JsonProperty("html_url") String htmlUrl) {}

  /**
   * A PR conversation comment: the id (to edit the bot's own summary in place), plus the body and
   * author needed to spot it.
   *
   * <p>{@code authorAssociation} is GitHub's per-comment statement of the author's relationship to
   * the repository ({@code OWNER}/{@code MEMBER}/{@code COLLABORATOR}/{@code CONTRIBUTOR}/{@code
   * NONE}/…), carried for the same reason {@link GitHubReviewClient.PullRequestComment} carries it:
   * the follow-up analyzer only lets a write-capable maintainer's conversation comment clear an
   * approve hold, never a drive-by commenter's.
   */
  record IssueComment(
      long id,
      String body,
      GitHubReviewClient.ReviewResponse.User user,
      @JsonProperty("author_association") String authorAssociation) {

    /**
     * Back-compat convenience for callers/tests that carry no author association. Defaults it to
     * {@code null}, which the analyzer treats as not write-capable — the safe direction, since a
     * comment with an unknown association must not clear a hold.
     */
    public IssueComment(long id, String body, GitHubReviewClient.ReviewResponse.User user) {
      this(id, body, user, null);
    }

    /** Convenience constructor for callers that never edit the comment. */
    public IssueComment(String body, GitHubReviewClient.ReviewResponse.User user) {
      this(0, body, user, null);
    }
  }
}
