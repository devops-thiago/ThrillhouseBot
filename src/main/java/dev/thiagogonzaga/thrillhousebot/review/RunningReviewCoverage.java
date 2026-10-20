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
package dev.thiagogonzaga.thrillhousebot.review;

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The GitHub reads and writes {@link ReviewDispatcher} needs to tell whether a manual {@code
 * /review} asks for a commit that a running review already covers (#1152): the pull request's head
 * right now, and the reply that answers the command instead of a second review.
 */
@ApplicationScoped
public class RunningReviewCoverage {

  private static final Logger log = LoggerFactory.getLogger(RunningReviewCoverage.class);

  private static final String ACCEPT = "application/vnd.github+json";

  private final GitHubAuthClient authClient;
  private final GitHubPullRequestClient prClient;
  private final GitHubCommentClient commentClient;

  @Inject
  public RunningReviewCoverage(
      GitHubAuthClient authClient,
      @RestClient GitHubPullRequestClient prClient,
      @RestClient GitHubCommentClient commentClient) {
    this.authClient = authClient;
    this.prClient = prClient;
    this.commentClient = commentClient;
  }

  /**
   * The pull request's head SHA as GitHub reports it now. Empty when the read fails or names no
   * head, in which case the caller queues the {@code /review} as it would without this check.
   */
  public Optional<String> currentHead(ReviewOrchestrator.ReviewRequest req) {
    try {
      var auth = authClient.getAuthHeader(req.installationId());
      var pr = prClient.getPullRequest(auth, ACCEPT, req.owner(), req.repo(), req.prNumber());
      var head =
          Optional.ofNullable(pr)
              .map(GitHubPullRequestClient.PullRequestDetails::head)
              .map(GitHubPullRequestClient.Ref::sha)
              .filter(sha -> !sha.isBlank());
      if (head.isEmpty()) {
        log.warn(
            "GitHub returned no head for {}/{} #{} — queueing the /review",
            req.owner(),
            req.repo(),
            req.prNumber());
      }
      return head;
    } catch (RuntimeException e) {
      log.warn(
          "Could not read the head of {}/{} #{} — queueing the /review",
          req.owner(),
          req.repo(),
          req.prNumber(),
          e);
      return Optional.empty();
    }
  }

  /**
   * Answers the {@code /review} with a note that the running review covers {@code headSha}.
   * Best-effort: a failed post is logged and the command is still not queued again.
   */
  public void notifyCovered(ReviewOrchestrator.ReviewRequest req, String headSha) {
    try {
      var auth = authClient.getAuthHeader(req.installationId());
      commentClient.createComment(
          auth,
          ACCEPT,
          req.owner(),
          req.repo(),
          req.prNumber(),
          new GitHubCommentClient.CreateCommentRequest(coveredNotice(headSha)));
    } catch (RuntimeException e) {
      log.warn(
          "Failed to reply to /review on {}/{} #{}", req.owner(), req.repo(), req.prNumber(), e);
    }
  }

  static String coveredNotice(String headSha) {
    var shortSha = headSha.length() > 7 ? headSha.substring(0, 7) : headSha;
    return "A review of `"
        + shortSha
        + "` is already running and covers this `/review`, so no second review was started."
        + " Push a new commit or comment `/review` again after it finishes to review again.";
  }
}
