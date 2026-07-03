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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReactionClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds the 👀 acknowledgment reaction to the comment that triggered a command, so the commenter
 * sees the bot noticed within ~1s while the actual work runs asynchronously (a review can take
 * minutes). Called on the webhook ack thread, which must return the {@code 200} within GitHub's
 * ~10s delivery window — so the wait on the token lookup + reaction POST is bounded by {@code
 * ack-reaction-timeout} (the REST client's own read timeout is far larger) and abandoned when it
 * elapses; the reaction may still land late in the background. Best-effort by contract: a failure
 * (missing permission, rate limit, deleted comment) is logged and swallowed so it can never block
 * or fail the command itself.
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

  private final ThrillhouseConfig config;
  private final GitHubAuthClient authClient;
  private final GitHubReactionClient reactionClient;

  /**
   * Runs the reaction (token mint + POST) off the webhook acknowledgement thread so the wait can be
   * abandoned once {@code ack-reaction-timeout} elapses. Virtual threads keep an
   * abandoned-but-still-blocked reaction cheap if GitHub is slow to respond.
   */
  private final ExecutorService reactionExecutor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ack-reaction-", 0).factory());

  @Inject
  public AckReactionService(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      @RestClient GitHubReactionClient reactionClient) {
    this.config = config;
    this.authClient = authClient;
    this.reactionClient = reactionClient;
  }

  @PreDestroy
  void shutdown() {
    reactionExecutor.shutdownNow();
  }

  /**
   * Reacts 👀 on the triggering comment; never throws on expected failures and never waits past the
   * timeout. Only a fatal {@link Error} (OOM, etc.) propagates to the caller.
   */
  public void addEyes(
      long installationId, String owner, String repo, long commentId, CommentKind kind) {
    var timeout = config.review().ackReactionTimeout();
    Future<?> reaction =
        reactionExecutor.submit(() -> react(installationId, owner, repo, commentId, kind));
    try {
      reaction.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException _) {
      // Abandon the wait; the POST may still finish in the background.
      log.warn(
          "👀 ack reaction on {} comment {} on {}/{} exceeded {} on the ACK path — continuing"
              + " without waiting",
          kind,
          commentId,
          owner,
          repo,
          timeout);
    } catch (ExecutionException e) {
      // Best-effort on runtime failure; let fatal Errors propagate from the worker thread.
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      log.warn(
          "Failed to add 👀 ack reaction to {} comment {} on {}/{} (continuing)",
          kind,
          commentId,
          owner,
          repo,
          e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn(
          "Interrupted while adding 👀 ack reaction to {} comment {} on {}/{} (continuing)",
          kind,
          commentId,
          owner,
          repo,
          e);
    }
  }

  private void react(
      long installationId, String owner, String repo, long commentId, CommentKind kind) {
    var auth = authClient.getAuthHeader(installationId);
    var request = new GitHubReactionClient.CreateReactionRequest(EYES);
    if (kind == CommentKind.ISSUE) {
      reactionClient.createIssueCommentReaction(auth, ACCEPT, owner, repo, commentId, request);
    } else {
      reactionClient.createReviewCommentReaction(auth, ACCEPT, owner, repo, commentId, request);
    }
  }
}
