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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks up and resolves pull request review threads via GraphQL — the REST API can list inline
 * comments but cannot resolve the threads they belong to.
 */
@ApplicationScoped
public class ReviewThreadService {

  private static final Logger log = LoggerFactory.getLogger(ReviewThreadService.class);

  private static final String THREADS_QUERY =
      """
      query($owner: String!, $name: String!, $number: Int!, $after: String) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $number) {
            reviewThreads(first: 100, after: $after) {
              nodes { id isResolved comments(first: 1) { nodes { databaseId } } }
              pageInfo { hasNextPage endCursor }
            }
          }
        }
      }
      """;

  // Stops a malformed pageInfo from looping forever.
  private static final int MAX_THREAD_PAGES = 20;

  private static final String RESOLVE_MUTATION =
      """
      mutation($threadId: ID!) {
        resolveReviewThread(input: {threadId: $threadId}) { thread { isResolved } }
      }
      """;

  /**
   * Attaches one thread to a pending review (#1434). GitHub documents {@code pullRequestReviewId}
   * as an input of this mutation; the REST create-comment route documents no such field, so the
   * batched path uses this one. The thread takes its commit from the pending review.
   */
  private static final String ADD_THREAD_MUTATION =
      """
      mutation($reviewId: ID!, $body: String!, $path: String!, $line: Int, $side: DiffSide,
          $startLine: Int, $startSide: DiffSide,
          $subjectType: PullRequestReviewThreadSubjectType) {
        addPullRequestReviewThread(input: {pullRequestReviewId: $reviewId, body: $body,
            path: $path, line: $line, side: $side, startLine: $startLine, startSide: $startSide,
            subjectType: $subjectType}) { thread { id } }
      }
      """;

  /**
   * The App permission {@code resolveReviewThread} needs. GitHub does not document it: {@code Pull
   * requests: Read & write} alone is answered with {@code Resource not accessible by integration}.
   */
  public static final String RESOLVE_PERMISSION = "`Contents: Read & write`";

  /** A review thread keyed by its root comment, with its GraphQL node id. */
  public record ThreadRef(String id, boolean resolved) {}

  private final GitHubGraphQLClient graphQLClient;
  private final GitHubWriteRetry writeRetry;

  @Inject
  public ReviewThreadService(@RestClient GitHubGraphQLClient graphQLClient) {
    this(graphQLClient, GitHubWriteRetry.DEFAULT);
  }

  /** The same, backing off with {@code writeRetry}; the seam the tests run the backoff through. */
  ReviewThreadService(GitHubGraphQLClient graphQLClient, GitHubWriteRetry writeRetry) {
    this.graphQLClient = graphQLClient;
    this.writeRetry = writeRetry;
  }

  /**
   * Maps each thread's root-comment database id to the thread's node id and resolution state. Walks
   * every page so a PR with more than 100 review threads still has all its threads resolvable —
   * otherwise {@code /resolve} would silently skip bot findings past the first page.
   *
   * @throws GitHubGraphQLException when GitHub answers a page with {@code errors} and no thread
   *     list, which is a read that failed and not a pull request without threads (#1471)
   */
  public Map<Long, ThreadRef> threadsByRootComment(
      String auth, String owner, String repo, int prNumber) {
    var threads = new HashMap<Long, ThreadRef>();
    String after = null;
    int page = 0;
    do {
      var variables = new HashMap<String, Object>();
      variables.put("owner", owner);
      variables.put("name", repo);
      variables.put("number", prNumber);
      if (after != null) {
        variables.put("after", after);
      }
      var response =
          graphQLClient.execute(
              auth, new GitHubGraphQLClient.GraphQLRequest(THREADS_QUERY, variables));
      var reviewThreads =
          response.path("data").path("repository").path("pullRequest").path("reviewThreads");
      var errors = response.path("errors");
      if (reviewThreads.isMissingNode() && !errors.isEmpty()) {
        throw GitHubGraphQLException.from(errors);
      }
      for (JsonNode node : reviewThreads.path("nodes")) {
        var firstComment = node.path("comments").path("nodes").path(0);
        if (firstComment.path("databaseId").isNumber() && node.path("id").isTextual()) {
          threads.put(
              firstComment.path("databaseId").asLong(),
              new ThreadRef(node.path("id").asText(), node.path("isResolved").asBoolean()));
        }
      }
      var pageInfo = reviewThreads.path("pageInfo");
      after =
          pageInfo.path("hasNextPage").asBoolean(false)
              ? pageInfo.path("endCursor").asText(null)
              : null;
      page++;
    } while (after != null && page < MAX_THREAD_PAGES);
    return threads;
  }

  /**
   * Opens a thread on the pending review {@code reviewNodeId}, from the same comment the REST route
   * would post, and returns the new thread's node id. The write is paced ({@link
   * GitHubWriteRetry#call} takes a {@link GitHubWritePacer} slot before each attempt), backed off
   * on a throttle and counted as a dropped post exactly as {@link
   * GitHubReviewClient#createPullRequestComment} is, so a finding's routes keep their one-delivery
   * accounting ({@link GitHubReviewClient#asOneComment}). That holds for a throttle GitHub reports
   * in the {@code errors} of an HTTP 200 as well as for one it answers with a 403 or 429: the
   * errors are read inside the retry, and a throttle among them is raised there as the 429 {@link
   * GitHubWriteRetry} backs off and {@link GitHubLostWrites} counts. A refusal is never counted as
   * a delivered thread.
   *
   * @throws GitHubGraphQLException when GitHub answers with {@code errors} and no thread, once any
   *     throttle among them has outlasted its retries
   */
  public String addPendingReviewThread(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String reviewNodeId,
      GitHubReviewClient.CreatePullRequestCommentRequest comment) {
    var variables = new HashMap<String, Object>();
    variables.put("reviewId", reviewNodeId);
    variables.put("body", comment.body());
    variables.put("path", comment.path());
    if (comment.fileLevel()) {
      variables.put("subjectType", "FILE");
    } else {
      putPresent(variables, "line", comment.line());
      putPresent(variables, "side", comment.side());
      putPresent(variables, "startLine", comment.startLine());
      putPresent(variables, "startSide", comment.startSide());
    }
    var request = new GitHubGraphQLClient.GraphQLRequest(ADD_THREAD_MUTATION, variables);
    try {
      return GitHubLostWrites.SHARED.recording(
          new GitHubLostWrites.Target(owner, repo, prNumber),
          () ->
              writeRetry.call(
                  "a pending review thread on " + owner + "/" + repo + " #" + prNumber,
                  auth,
                  credential -> createdThreadId(graphQLClient.executeOnce(credential, request))));
    } catch (WebApplicationException e) {
      if (e.getCause() instanceof GitHubGraphQLException refusal) {
        throw refusal;
      }
      throw e;
    }
  }

  /**
   * The thread the mutation opened, or GitHub's refusal: as the throttle {@link GitHubWriteRetry}
   * repeats when its errors say GitHub is rate limiting, and as itself otherwise.
   */
  private static String createdThreadId(JsonNode response) {
    var created =
        response.path("data").path("addPullRequestReviewThread").path("thread").path("id");
    if (created.isTextual()) {
      return created.asText();
    }
    var refusal = GitHubGraphQLException.from(response.path("errors"));
    throw refusal.isThrottled() ? refusal.asHttpThrottle() : refusal;
  }

  private static void putPresent(Map<String, Object> variables, String name, Object value) {
    if (value != null) {
      variables.put(name, value);
    }
  }

  /**
   * Resolves a review thread; returns whether GitHub reports it resolved afterwards.
   *
   * @throws GitHubGraphQLException when GitHub answers with {@code errors} and no confirmation
   */
  public boolean resolve(String auth, String threadId) {
    var response =
        graphQLClient.execute(
            auth,
            new GitHubGraphQLClient.GraphQLRequest(RESOLVE_MUTATION, Map.of("threadId", threadId)));
    var resolved =
        response
            .path("data")
            .path("resolveReviewThread")
            .path("thread")
            .path("isResolved")
            .asBoolean(false);
    if (!resolved) {
      var errors = response.path("errors");
      if (!errors.isEmpty()) {
        throw GitHubGraphQLException.from(errors);
      }
      log.warn("GitHub did not confirm resolution of review thread {}", threadId);
    }
    return resolved;
  }
}
