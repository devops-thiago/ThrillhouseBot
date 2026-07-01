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
import java.util.HashSet;
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
            PromptSections.prContext(task.prTitle(), task.prDescription()),
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
        generateReply(
            task.question(),
            PromptSections.prContext(task.prTitle(), task.prDescription()),
            "",
            fetchDiff(auth, task),
            "");
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
              // codeContext is the diff/hunk under discussion: fence it byte-exact.
              PromptTemplateEscaper.fence(codeContext),
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
    // The client walks every page, so the reply context sees the whole thread, not just page 1.
    try {
      return reviewClient.listPullRequestComments(auth, ACCEPT, t.owner(), t.repo(), t.prNumber());
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
   *
   * <p>Collects the whole thread by walking the reply chain, not just comments that point straight
   * at the root. GitHub review threads are usually flat — every reply's {@code in_reply_to_id} is
   * the root comment — but a reply to a reply points at its immediate parent, so a filter keyed
   * only on the root drops those nested replies (a maintainer clarifying or disputing mid-thread)
   * from the context handed to the assistant. Following the chain is correct under both models: a
   * flat thread yields exactly the direct replies, a nested one additionally yields the deeper
   * ones.
   *
   * <p>Relies on the chronological (oldest-first) order the comment list arrives in — a reply is
   * always created after its parent — so a single forward pass sees each parent before its replies.
   */
  private static String renderThread(
      List<GitHubReviewClient.PullRequestComment> comments,
      long rootCommentId,
      long triggeringCommentId) {
    var threadIds = new HashSet<Long>();
    threadIds.add(rootCommentId);
    var sb = new StringBuilder();
    for (var c : comments) {
      // A comment joins the thread when it replies, directly or transitively, to something already
      // in it; record its id so its own nested replies are picked up on a later iteration.
      if (c.inReplyToId() == null || !threadIds.contains(c.inReplyToId())) {
        continue;
      }
      threadIds.add(c.id());
      // Render every thread comment except the triggering one: it anchors this reply (and any
      // descendants) but is shown only as the question, never repeated in the rendered thread.
      if (c.id() != triggeringCommentId) {
        String author = c.user() != null ? c.user().login() : "unknown";
        sb.append("- @").append(author).append(": ").append(c.body()).append("\n");
      }
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
}
