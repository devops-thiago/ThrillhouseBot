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
import java.util.function.Supplier;
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
   *
   * <p>A caller with more than one route to the same piece of content wraps them in {@link
   * #asOneComment}, which is what keeps a comment a later route delivered from being announced as
   * lost (#729).
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

  /**
   * Runs {@code routes} — several content-creating calls that are alternative ways of getting the
   * <em>same</em> content onto the pull request — as one delivery, so a throttle that costs one
   * route its turn is only announced to the pull request if no route delivered the content at all
   * (#729).
   *
   * <p>Without this the accounting counts refused HTTP calls rather than lost content: #721's
   * file-level fallback lands the finding and the review body published moments later still leads
   * with "an earlier reply on this pull request was never posted … run the command again", twice
   * over for a finding whose suggestion block earned the line-anchored route a second attempt.
   *
   * <p>The routes need not all be {@link #createPullRequestComment}: #704's fallback carries a
   * refused review body over to {@link GitHubCommentClient#createComment}, and those two are one
   * delivery for the same reason (#748). What has to match is the pull request they aim at — a
   * group speaks for one pull request only.
   *
   * <p>Lives here rather than at the call site because the notice registry is this package's, and
   * {@link #createPullRequestComment} on this interface is the call it was built for.
   */
  static <T> T asOneComment(String owner, String repo, int pullNumber, Supplier<T> routes) {
    return GitHubLostWrites.SHARED.asOneDelivery(
        new GitHubLostWrites.Target(owner, repo, pullNumber), routes);
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

  /**
   * One HTTP attempt at deleting a pending review. Callers want {@link #deletePendingReview}
   * instead.
   */
  @DELETE
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews/{reviewId}")
  void deletePendingReviewOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @PathParam("reviewId") long reviewId);

  /**
   * Deletes a pending pull request review, with the throttle backoff described on {@link
   * GitHubWriteRetry}.
   */
  default void deletePendingReview(
      String auth, String accept, String owner, String repo, int pullNumber, long reviewId) {
    GitHubWriteRetry.DEFAULT.call(
        "delete pending review " + reviewId + " on " + owner + "/" + repo + " #" + pullNumber,
        auth,
        credential -> {
          deletePendingReviewOnce(credential, accept, owner, repo, pullNumber, reviewId);
          return null;
        });
  }

  /** One HTTP attempt at dismissing a review. Callers want {@link #dismissReview} instead. */
  @PUT
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews/{reviewId}/dismissals")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  ReviewResponse dismissReviewOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @PathParam("reviewId") long reviewId,
      DismissReviewRequest request);

  /**
   * Dismisses a submitted review, with the throttle backoff described on {@link GitHubWriteRetry}.
   * GitHub keeps the review and its comments and marks it {@code DISMISSED}, so it no longer counts
   * towards the pull request's approvals. The route needs the App's "Pull requests: write"
   * permission. GitHub's reference adds that on a protected branch the caller must be a repository
   * administrator or on the rule's list of people, teams and apps that may dismiss reviews.
   */
  default ReviewResponse dismissReview(
      String auth,
      String accept,
      String owner,
      String repo,
      int pullNumber,
      long reviewId,
      String message) {
    return GitHubWriteRetry.DEFAULT.call(
        "dismiss review " + reviewId + " on " + owner + "/" + repo + " #" + pullNumber,
        auth,
        credential ->
            dismissReviewOnce(
                credential,
                accept,
                owner,
                repo,
                pullNumber,
                reviewId,
                new DismissReviewRequest(message, DISMISS_EVENT)));
  }

  /** One HTTP attempt at reading one review. Callers want {@link #getReview} instead. */
  @GET
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews/{reviewId}")
  @Produces(MediaType.APPLICATION_JSON)
  ReviewResponse getReviewOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @PathParam("reviewId") long reviewId);

  /** Reads one review, healing a rejected credential once (#626). */
  default ReviewResponse getReview(
      String auth, String accept, String owner, String repo, int pullNumber, long reviewId) {
    return GitHubTokenRefresh.SHARED.retrying(
        "review " + reviewId + " on " + owner + "/" + repo + "#" + pullNumber,
        auth,
        credential -> getReviewOnce(credential, accept, owner, repo, pullNumber, reviewId));
  }

  /**
   * One HTTP attempt at submitting a pending review. Callers want {@link #submitReview} instead.
   * GitHub's REST reference documents this route as {@code POST
   * /repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}/events} ("Submit a review for a
   * pull request"); {@code PUT} on {@code …/reviews/{review_id}} updates a review, it does not
   * submit one.
   */
  @POST
  @Path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews/{reviewId}/events")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  ReviewResponse submitReviewOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("pullNumber") int pullNumber,
      @PathParam("reviewId") long reviewId,
      SubmitReviewRequest request);

  /**
   * Submits a pending pull request review, with the throttle backoff described on {@link
   * GitHubWriteRetry}.
   */
  default ReviewResponse submitReview(
      String auth,
      String accept,
      String owner,
      String repo,
      int pullNumber,
      long reviewId,
      SubmitReviewRequest request) {
    return GitHubLostWrites.SHARED.carrying(
        new GitHubLostWrites.Target(owner, repo, pullNumber),
        notice ->
            GitHubWriteRetry.DEFAULT.call(
                "submit review " + reviewId + " on " + owner + "/" + repo + " #" + pullNumber,
                auth,
                credential ->
                    submitReviewOnce(
                        credential,
                        accept,
                        owner,
                        repo,
                        pullNumber,
                        reviewId,
                        new SubmitReviewRequest(
                            GitHubLostWrites.prepend(notice, request.body()), request.event()))));
  }

  /**
   * Creates a review left in {@code PENDING} state so inline threads can attach before it is
   * submitted (#1434). The response's {@code node_id} is what {@code
   * ReviewThreadService#addPendingReviewThread} attaches each thread to.
   */
  default ReviewResponse createPendingReview(
      String auth, String accept, String owner, String repo, int pullNumber, String commitId) {
    return createReview(
        auth,
        accept,
        owner,
        repo,
        pullNumber,
        new CreateReviewRequest(commitId, "", null, List.of()));
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CreateReviewRequest(
      @JsonProperty("commit_id") String commitId,
      String body,
      String event, // APPROVE, REQUEST_CHANGES, COMMENT; null leaves the review PENDING
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
   *
   * <p>{@code subjectType} selects what the thread hangs off. It is null — and omitted — for the
   * line-anchored comments that are the normal case, and {@value #SUBJECT_TYPE_FILE} for a thread
   * filed on the file as a whole (#712). GitHub rejects a file-level request that also carries a
   * position, so {@code line}, {@code side} and both range fields are nullable and left out
   * together; the {@code NON_NULL} inclusion above is what keeps them off that payload.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CreatePullRequestCommentRequest(
      @JsonProperty("commit_id") String commitId,
      String body,
      String path,
      Integer line,
      String side,
      @JsonProperty("start_line") Integer startLine,
      @JsonProperty("start_side") String startSide,
      @JsonProperty("subject_type") String subjectType) {
    public CreatePullRequestCommentRequest {
      body = CommentBodyLimit.cap(body);
    }

    /** The line-anchored shape, which names no subject type — GitHub defaults it to the line. */
    public CreatePullRequestCommentRequest(
        String commitId,
        String body,
        String path,
        Integer line,
        String side,
        Integer startLine,
        String startSide) {
      this(commitId, body, path, line, side, startLine, startSide, null);
    }

    /** A thread on the file as a whole: no line, no side, no range — only the path. */
    public static CreatePullRequestCommentRequest onFile(
        String commitId, String body, String path) {
      return new CreatePullRequestCommentRequest(
          commitId, body, path, null, null, null, null, SUBJECT_TYPE_FILE);
    }

    /** Whether this is a thread on the file as a whole rather than on a line. */
    public boolean fileLevel() {
      return SUBJECT_TYPE_FILE.equals(subjectType);
    }
  }

  /**
   * Payload for {@link #submitReviewOnce}: the body and one of APPROVE, REQUEST_CHANGES, COMMENT.
   */
  record SubmitReviewRequest(String body, String event) {
    public SubmitReviewRequest {
      body = CommentBodyLimit.cap(body);
    }
  }

  /** The one {@code event} GitHub accepts on the dismissal route. */
  String DISMISS_EVENT = "DISMISS";

  /** Payload for {@link #dismissReviewOnce}: the reason shown on the pull request's timeline. */
  record DismissReviewRequest(String message, String event) {}

  /** GitHub's {@code subject_type} for a review thread that targets a whole file. */
  String SUBJECT_TYPE_FILE = "file";

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
   * NONE}/…). The follow-up analyzer uses it as the first check that tells a maintainer's reply,
   * which may clear an approve hold or overrule a finding, from a fork-PR author's, which may not.
   * It grants nothing by itself: the author's write access must also be confirmed with GitHub.
   *
   * <p>{@code line} and {@code originalLine} are the comment's line on the current and on the
   * original diff ({@code null} when GitHub gives none, as for a file-level comment or an outdated
   * line). The in-thread clear directive compares a {@code path:line} it names against them
   * (#1066).
   */
  record PullRequestComment(
      long id,
      @JsonProperty("in_reply_to_id") Long inReplyToId,
      String path,
      String body,
      ReviewResponse.User user,
      @JsonProperty("author_association") String authorAssociation,
      Integer line,
      @JsonProperty("original_line") Integer originalLine,
      // The commit the comment was posted on (#1319); null when GitHub gives none.
      @JsonProperty("original_commit_id") String originalCommitId) {

    /** A comment whose original commit is not known. */
    public PullRequestComment(
        long id,
        Long inReplyToId,
        String path,
        String body,
        ReviewResponse.User user,
        String authorAssociation,
        Integer line,
        Integer originalLine) {
      this(id, inReplyToId, path, body, user, authorAssociation, line, originalLine, null);
    }

    /** A comment with no line information, as most callers and tests build one. */
    public PullRequestComment(
        long id,
        Long inReplyToId,
        String path,
        String body,
        ReviewResponse.User user,
        String authorAssociation) {
      this(id, inReplyToId, path, body, user, authorAssociation, null, null);
    }

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

  /**
   * A pull request review. {@code nodeId} is the review's GraphQL node id, which the batched path
   * hands to {@code addPullRequestReviewThread} to attach a thread to a pending review (#1434).
   */
  record ReviewResponse(
      long id,
      String body,
      String state, // PENDING, APPROVED, CHANGES_REQUESTED, COMMENTED, DISMISSED
      @JsonProperty("commit_id") String commitId,
      User user,
      @JsonProperty("node_id") String nodeId) {
    public record User(String login) {}

    /** A review whose node id is not known. */
    public ReviewResponse(long id, String body, String state, String commitId, User user) {
      this(id, body, state, commitId, user, null);
    }
  }
}
