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
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReplyAssistant;
import dev.thiagogonzaga.thrillhousebot.webhook.ManualReviewAuthorizer;
import dev.thiagogonzaga.thrillhousebot.webhook.TriggerDetector;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Answers a maintainer who replied to one of the bot's review findings, or who mentioned {@code
 * @thrillhousebot} in a PR thread, with a contextual AI reply.
 *
 * <p>Runs off the webhook ACK path (on the review executor): it loads the surrounding context,
 * builds a threaded prompt, calls the {@link ReplyAssistant}, and posts the answer back into the
 * same thread (for inline review comments) or as a PR comment (for top-level mentions). Every step
 * fails soft — a maintainer asking a question must never break, and a failure simply means no reply
 * is posted rather than a noisy error on the PR.
 */
@ApplicationScoped
public class MaintainerReplyService {

  private static final String ACCEPT = "application/vnd.github+json";

  private final GitHubAuthClient authClient;
  private final ManualReviewAuthorizer authorizer;
  private final TriggerDetector triggerDetector;
  private final GitHubReviewClient reviewClient;
  private final GitHubCommentClient commentClient;
  private final GitHubPullRequestClient prClient;
  private final ReviewDiffFormatter diffFormatter;
  private final ReplyAssistant replyAssistant;

  @Inject
  public MaintainerReplyService(
      GitHubAuthClient authClient,
      ManualReviewAuthorizer authorizer,
      TriggerDetector triggerDetector,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubCommentClient commentClient,
      @RestClient GitHubPullRequestClient prClient,
      ReviewDiffFormatter diffFormatter,
      ReplyAssistant replyAssistant) {
    this.authClient = authClient;
    this.authorizer = authorizer;
    this.triggerDetector = triggerDetector;
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.prClient = prClient;
    this.diffFormatter = diffFormatter;
    this.replyAssistant = replyAssistant;
  }

  /**
   * Context for one conversational reply, captured from the webhook so the heavy work can run
   * asynchronously.
   *
   * @param owner repository owner login
   * @param repo repository name
   * @param prNumber pull request number
   * @param installationId GitHub App installation id
   * @param commenterLogin login of the maintainer who triggered the reply
   * @param authorAssociation the commenter's {@code author_association} (for cheap auth rejection)
   * @param question the maintainer's message the bot must answer
   * @param prTitle PR title (best-effort context)
   * @param prDescription PR body (best-effort context)
   * @param reviewThread {@code true} to answer inside an inline review thread, {@code false} to
   *     post a top-level PR comment for a mention
   * @param rootCommentId the review thread's root comment id (the inline comment to reply under);
   *     {@code null} for a top-level mention
   * @param triggeringCommentId id of the comment that triggered this reply, excluded from the
   *     rendered thread so the question is not shown twice
   * @param mentioned whether the maintainer explicitly {@code @}-mentioned the bot
   * @param diffHunk the inline comment's diff hunk (review-thread locality); {@code null} otherwise
   */
  public record ReplyTask(
      String owner,
      String repo,
      int prNumber,
      long installationId,
      String commenterLogin,
      String authorAssociation,
      String question,
      String prTitle,
      String prDescription,
      boolean reviewThread,
      Long rootCommentId,
      long triggeringCommentId,
      boolean mentioned,
      String diffHunk) {}

  /** Builds and posts the reply. Swallows every failure after logging it. */
  @ActivateRequestContext
  public void handle(ReplyTask task) {
    try {
      if (!authorizer.isAuthorized(
          task.owner(),
          task.repo(),
          task.installationId(),
          task.commenterLogin(),
          task.authorAssociation())) {
        Log.infof(
            "Ignoring conversational reply request from @%s on %s/%s #%d — not authorized",
            task.commenterLogin(), task.owner(), task.repo(), task.prNumber());
        return;
      }
      var auth = authClient.getAuthHeader(task.installationId());
      if (task.reviewThread()) {
        handleReviewThreadReply(auth, task);
      } else {
        handleMention(auth, task);
      }
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Failed to post conversational reply on %s/%s #%d",
          task.owner(),
          task.repo(),
          task.prNumber());
    }
  }

  private void handleReviewThreadReply(String auth, ReplyTask task) {
    var comments = listReviewComments(auth, task);
    var root = findRoot(comments, task.rootCommentId());
    boolean rootIsBot =
        root != null && root.user() != null && triggerDetector.isBotComment(root.user().login());
    // Only answer when the maintainer is actually addressing the bot: a reply on the bot's own
    // finding thread, or an explicit mention. A reply between two humans on a human-started thread
    // must not pull the bot in.
    if (!task.mentioned() && !rootIsBot) {
      Log.debugf(
          "Skipping review-thread reply on %s/%s #%d — not a bot thread and no mention",
          task.owner(), task.repo(), task.prNumber());
      return;
    }
    if (task.rootCommentId() == null) {
      Log.debugf("Skipping review-thread reply with no resolvable root comment");
      return;
    }

    String finding = rootIsBot ? root.body() : "";
    String thread = renderThread(comments, task.rootCommentId(), task.triggeringCommentId());

    String reply =
        generateReply(
            task.question(),
            buildPrContext(task),
            finding,
            task.diffHunk() != null ? task.diffHunk() : "",
            thread);
    if (reply == null) {
      return;
    }
    reviewClient.replyToReviewComment(
        auth,
        ACCEPT,
        task.owner(),
        task.repo(),
        task.prNumber(),
        task.rootCommentId(),
        new GitHubReviewClient.ReplyToReviewCommentRequest(reply));
    Log.infof(
        "Posted conversational reply in review thread %d on %s/%s #%d",
        task.rootCommentId(), task.owner(), task.repo(), task.prNumber());
  }

  private void handleMention(String auth, ReplyTask task) {
    String reply =
        generateReply(task.question(), buildPrContext(task), "", fetchDiff(auth, task), "");
    if (reply == null) {
      return;
    }
    commentClient.createComment(
        auth,
        ACCEPT,
        task.owner(),
        task.repo(),
        task.prNumber(),
        new GitHubCommentClient.CreateCommentRequest(reply));
    Log.infof(
        "Posted conversational reply to mention on %s/%s #%d",
        task.owner(), task.repo(), task.prNumber());
  }

  /** Calls the assistant with already-raw inputs, escaping each for templating. Null on failure. */
  private String generateReply(
      String question, String prContext, String finding, String codeContext, String thread) {
    try {
      String reply =
          replyAssistant.reply(
              PromptTemplateEscaper.escape(question),
              PromptTemplateEscaper.escape(prContext),
              PromptTemplateEscaper.escape(finding),
              PromptTemplateEscaper.escape(codeContext),
              PromptTemplateEscaper.escape(thread));
      if (reply == null || reply.isBlank()) {
        Log.debug("Reply assistant produced an empty reply — posting nothing");
        return null;
      }
      return reply.strip();
    } catch (RuntimeException e) {
      Log.warn("Reply assistant call failed — posting nothing", e);
      return null;
    }
  }

  private List<GitHubReviewClient.PullRequestComment> listReviewComments(String auth, ReplyTask t) {
    try {
      var comments =
          reviewClient.listPullRequestComments(auth, ACCEPT, t.owner(), t.repo(), t.prNumber());
      return comments != null ? comments : List.of();
    } catch (RuntimeException e) {
      Log.warn("Failed to list PR review comments for reply context", e);
      return List.of();
    }
  }

  private static GitHubReviewClient.PullRequestComment findRoot(
      List<GitHubReviewClient.PullRequestComment> comments, Long rootCommentId) {
    if (rootCommentId == null) {
      return null;
    }
    return comments.stream().filter(c -> c.id() == rootCommentId).findFirst().orElse(null);
  }

  /**
   * Replies already on this thread, oldest first, excluding the message that triggered the reply.
   */
  private static String renderThread(
      List<GitHubReviewClient.PullRequestComment> comments,
      long rootCommentId,
      long triggeringCommentId) {
    var sb = new StringBuilder();
    for (var c : comments) {
      if (c.inReplyToId() == null || c.inReplyToId() != rootCommentId) {
        continue;
      }
      if (c.id() == triggeringCommentId) {
        continue;
      }
      String author = c.user() != null ? c.user().login() : "unknown";
      sb.append("- @").append(author).append(": ").append(c.body()).append("\n");
    }
    return sb.toString();
  }

  private String fetchDiff(String auth, ReplyTask task) {
    try {
      var files =
          prClient.getPullRequestFiles(auth, ACCEPT, task.owner(), task.repo(), task.prNumber());
      return diffFormatter.buildDiffString(files);
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch PR diff for mention reply, continuing without it", e);
      return "";
    }
  }

  private static String buildPrContext(ReplyTask task) {
    var sb = new StringBuilder();
    if (task.prTitle() != null && !task.prTitle().isBlank()) {
      sb.append("Title: ").append(task.prTitle().strip()).append('\n');
    }
    if (task.prDescription() != null && !task.prDescription().isBlank()) {
      sb.append("Description:\n").append(task.prDescription().strip()).append('\n');
    }
    return sb.toString();
  }
}
