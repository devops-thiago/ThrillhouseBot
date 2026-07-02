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
package dev.thiagogonzaga.thrillhousebot.webhook;

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReactionClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds the 👀 acknowledgment reaction to the comment that triggered a command, so the commenter
 * sees the bot noticed within ~1s while the actual work runs asynchronously (a review can take
 * minutes). Called synchronously on the webhook ack thread — one token lookup plus one reaction
 * POST fits comfortably inside GitHub's 10-second delivery window. Best-effort by contract: a
 * failure (missing permission, rate limit, deleted comment) is logged and swallowed so it can never
 * block or fail the command itself.
 */
@ApplicationScoped
public class AckReactionService {

  private static final Logger log = LoggerFactory.getLogger(AckReactionService.class);

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String EYES = "eyes";

  /** Which reactions endpoint the comment lives under. */
  public enum CommentKind {
    /** A PR conversation comment ({@code issue_comment} event). */
    ISSUE,
    /** An inline review-thread comment ({@code pull_request_review_comment} event). */
    REVIEW
  }

  private final GitHubAuthClient authClient;
  private final GitHubReactionClient reactionClient;

  @Inject
  public AckReactionService(
      GitHubAuthClient authClient, @RestClient GitHubReactionClient reactionClient) {
    this.authClient = authClient;
    this.reactionClient = reactionClient;
  }

  /** Reacts 👀 on the triggering comment; never throws. */
  public void addEyes(
      long installationId, String owner, String repo, long commentId, CommentKind kind) {
    try {
      var auth = authClient.getAuthHeader(installationId);
      var request = new GitHubReactionClient.CreateReactionRequest(EYES);
      if (kind == CommentKind.ISSUE) {
        reactionClient.createIssueCommentReaction(auth, ACCEPT, owner, repo, commentId, request);
      } else {
        reactionClient.createReviewCommentReaction(auth, ACCEPT, owner, repo, commentId, request);
      }
    } catch (RuntimeException e) {
      log.warn(
          "Failed to add 👀 ack reaction to {} comment {} on {}/{} (continuing)",
          kind,
          commentId,
          owner,
          repo,
          e);
    }
  }
}
