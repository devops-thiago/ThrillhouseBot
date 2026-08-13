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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "github-api")
@RegisterProvider(GitHubErrorLogger.class)
public interface GitHubReviewClient {

  /** One HTTP attempt at posting a review. Callers want {@link #createReview} instead. */
  @POST
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  ReviewResponse createReviewOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      CreateReviewRequest request);

  /**
   * Posts the review, backing off and reposting while GitHub is throttling (#568). The review body
   * and every inline finding in it are the run's model output, so a throttled 403 here discards the
   * whole generation. See {@link GitHubWriteRetry} for why the repeat cannot post it twice.
   *
   * <p>The review body is the second surface that can carry a dropped-post notice (#578): it lands
   * in the pull request's conversation, so a reply GitHub threw away earlier is announced here when
   * a review is what comes next.
   */
  default ReviewResponse createReview(
      String auth,
      String accept,
      String owner,
      String repo,
      int pullNumber,
      CreateReviewRequest request) {
    return GitHubLostWrites.SHARED.carrying(
        new GitHubLostWrites.Target(owner, repo, pullNumber),
        notice ->
            GitHubWriteRetry.DEFAULT.call(
                "a review on " + owner + "/" + repo + " #" + pullNumber,
                auth,
                credential ->
                    createReviewOnce(
                        credential,
                        accept,
                        owner,
                        repo,
                        pullNumber,
                        new CreateReviewRequest(
                            request.commitId(),
                            GitHubLostWrites.prepend(notice, request.body()),
                            request.event(),
                            request.comments()))));
  }

  // GitHub serves 30 reviews per page by default; request the 100 max and bound the page walk.
  int REVIEWS_PER_PAGE = 100;
  int MAX_REVIEW_PAGES = 10;

  @GET
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews")
  @Produces(MediaType.APPLICATION_JSON)
  List<ReviewResponse> listReviewsPageOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @QueryParam("per_page") int perPage,
      @QueryParam("page") int page);

  /** One page of a PR's reviews, healing a rejected credential per page (#626). */
  default List<ReviewResponse> listReviewsPage(
      String auth,
      String accept,
      String owner,
      String repo,
      int pullNumber,
      int perPage,
      int page) {
    return GitHubTokenRefresh.SHARED.retrying(
        "reviews page " + page + " of " + owner + "/" + repo + "#" + pullNumber,
        auth,
        credential ->
            listReviewsPageOnce(credential, accept, owner, repo, pullNumber, perPage, page));
  }

  /**
   * Lists a PR's reviews, walking pages of {@value #REVIEWS_PER_PAGE} up to {@value
   * #MAX_REVIEW_PAGES} pages so a long-lived PR's reviews are not silently truncated. A single-page
   * fetch caps at GitHub's 30-per-page default, which would hide a prior bot review (skewing
   * first-review detection and the backstop) and leave a pending bot review past page one
   * undismissed. Stops at the first short/empty page.
   */
  default List<ReviewResponse> listReviews(
      String auth, String accept, String owner, String repo, int pullNumber) {
    var all = new ArrayList<ReviewResponse>();
    List<ReviewResponse> batch;
    int page = 1;
    do {
      batch = listReviewsPage(auth, accept, owner, repo, pullNumber, REVIEWS_PER_PAGE, page);
      if (batch != null) {
        all.addAll(batch);
      }
      page++;
    } while (batch != null && batch.size() == REVIEWS_PER_PAGE && page <= MAX_REVIEW_PAGES);
    return all;
  }

  // GitHub serves 30 inline comments per page by default; request the 100 max and bound the walk.
  int COMMENTS_PER_PAGE = 100;
  int MAX_COMMENT_PAGES = 10;

  @GET
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/comments")
  @Produces(MediaType.APPLICATION_JSON)
  List<PullRequestComment> listPullRequestCommentsPageOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @QueryParam("per_page") int perPage,
      @QueryParam("page") int page);

  /** One page of a PR's inline comments, healing a rejected credential per page (#626). */
  default List<PullRequestComment> listPullRequestCommentsPage(
      String auth,
      String accept,
      String owner,
      String repo,
      int pullNumber,
      int perPage,
      int page) {
    return GitHubTokenRefresh.SHARED.retrying(
        "inline comments page " + page + " of " + owner + "/" + repo + "#" + pullNumber,
        auth,
        credential ->
            listPullRequestCommentsPageOnce(
                credential, accept, owner, repo, pullNumber, perPage, page));
  }

  @GET
  @Path("/repos/{owner}/{repo}/pulls/comments/{commentId}")
  @Produces(MediaType.APPLICATION_JSON)
  PullRequestComment getPullRequestCommentOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("commentId") long commentId);

  /** Reads one inline comment, healing a rejected credential once (#626). */
  default PullRequestComment getPullRequestComment(
      String auth, String accept, String owner, String repo, long commentId) {
    return GitHubTokenRefresh.SHARED.retrying(
        "inline comment " + commentId + " on " + owner + "/" + repo,
        auth,
        credential -> getPullRequestCommentOnce(credential, accept, owner, repo, commentId));
  }

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

  /**
   * One HTTP attempt at an inline comment. Callers want {@link #createPullRequestComment} instead.
   */
  @POST
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/comments")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  PullRequestCommentResponse createPullRequestCommentOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      CreatePullRequestCommentRequest request);

  /**
   * Posts an inline comment, with the throttle backoff described on {@link GitHubWriteRetry}. An
   * inline comment is anchored to a diff line, so it is a poor place to announce an unrelated
   * dropped post and does not carry a notice — but losing one is still a loss the pull request
   * should hear about, so it leaves a notice for the next comment to carry (#578).
   */
  default PullRequestCommentResponse createPullRequestComment(
      String auth,
      String accept,
      String owner,
      String repo,
      int pullNumber,
      CreatePullRequestCommentRequest request) {
    return GitHubLostWrites.SHARED.recording(
        new GitHubLostWrites.Target(owner, repo, pullNumber),
        () ->
            GitHubWriteRetry.DEFAULT.call(
                "an inline comment on " + owner + "/" + repo + " #" + pullNumber,
                auth,
                credential ->
                    createPullRequestCommentOnce(
                        credential, accept, owner, repo, pullNumber, request)));
  }

  /** One HTTP attempt at a thread reply. Callers want {@link #replyToReviewComment} instead. */
  @POST
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/comments/{commentId}/replies")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  PullRequestCommentResponse replyToReviewCommentOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @PathParam("commentId") long commentId,
      ReplyToReviewCommentRequest request);

  /**
   * Replies in a review thread, with the throttle backoff described on {@link GitHubWriteRetry}.
   * Like an inline comment it belongs to its thread rather than to the conversation, so it leaves a
   * dropped-post notice behind (#578) without carrying one.
   */
  default PullRequestCommentResponse replyToReviewComment(
      String auth,
      String accept,
      String owner,
      String repo,
      int pullNumber,
      long commentId,
      ReplyToReviewCommentRequest request) {
    return GitHubLostWrites.SHARED.recording(
        new GitHubLostWrites.Target(owner, repo, pullNumber),
        () ->
            GitHubWriteRetry.DEFAULT.call(
                "a reply to comment " + commentId + " on " + owner + "/" + repo + " #" + pullNumber,
                auth,
                credential ->
                    replyToReviewCommentOnce(
                        credential, accept, owner, repo, pullNumber, commentId, request)));
  }

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
      body = CommentBodyLimit.cap(body);
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

  /**
   * A pull request review comment. When {@code startLine} is set, the comment spans the inclusive
   * range {@code start_line}..{@code line} so a GitHub suggestion replaces every line in it; a
   * single-line comment leaves both range fields null and they are omitted from the payload.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CreatePullRequestCommentRequest(
      @JsonProperty("commit_id") String commitId,
      String body,
      String path,
      int line,
      String side,
      @JsonProperty("start_line") Integer startLine,
      @JsonProperty("start_side") String startSide) {
    public CreatePullRequestCommentRequest {
      body = CommentBodyLimit.cap(body);
    }
  }

  /** Reply posted into an existing review thread, keyed by the thread's root comment id in path. */
  record ReplyToReviewCommentRequest(String body) {
    public ReplyToReviewCommentRequest {
      body = CommentBodyLimit.cap(body);
    }
  }

  record PullRequestCommentResponse(long id, String body, String path, Integer line) {}

  /**
   * An inline review comment; replies carry the root comment's id in {@code inReplyToId}.
   *
   * <p>{@code authorAssociation} is GitHub's per-comment statement of the author's relationship to
   * the repository ({@code OWNER}/{@code MEMBER}/{@code COLLABORATOR}/{@code CONTRIBUTOR}/{@code
   * NONE}/…). The follow-up analyzer uses it to tell a write-capable maintainer's reply — which may
   * clear an approve hold or overrule a finding — from a fork-PR author's, which may not.
   */
  record PullRequestComment(
      long id,
      @JsonProperty("in_reply_to_id") Long inReplyToId,
      String path,
      String body,
      ReviewResponse.User user,
      @JsonProperty("author_association") String authorAssociation) {

    /**
     * Back-compat convenience for callers/tests that carry no author association. Defaults it to
     * {@code null}, which the analyzer treats as not write-capable — the safe direction, since a
     * comment with an unknown association must not clear a hold or overrule a finding.
     */
    public PullRequestComment(
        long id, Long inReplyToId, String path, String body, ReviewResponse.User user) {
      this(id, inReplyToId, path, body, user, null);
    }
  }

  record ReviewResponse(
      long id,
      String body,
      String state, // APPROVED, CHANGES_REQUESTED, COMMENTED
      @JsonProperty("commit_id") String commitId,
      User user) {
    public record User(String login) {}
  }
}
