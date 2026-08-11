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
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponseTruncatedException;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses;
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
  private final RepoSettingsResolver repoSettingsResolver;

  @Inject
  public MaintainerReplyService(
      GitHubAuthClient authClient,
      ManualReviewAuthorizer authorizer,
      TriggerDetector triggerDetector,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubCommentClient commentClient,
      @RestClient GitHubPullRequestClient prClient,
      ReviewDiffFormatter diffFormatter,
      ReplyAssistant replyAssistant,
      RepoSettingsResolver repoSettingsResolver) {
    this.authClient = authClient;
    this.authorizer = authorizer;
    this.triggerDetector = triggerDetector;
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.prClient = prClient;
    this.diffFormatter = diffFormatter;
    this.replyAssistant = replyAssistant;
    this.repoSettingsResolver = repoSettingsResolver;
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

  /**
   * Acknowledgement posted for an {@code @thrillhousebot resolved} comment. Deliberately
   * deterministic prose rather than an assistant answer: the directive is an instruction the review
   * path acts on, not a question, and a generated reply could contradict or overstate what it does.
   * The wording is conditional because the clearing decision is made by the next review, which
   * matches each named finding against its own {@code path:line} and title (#548).
   */
  static final String CLEAR_DIRECTIVE_ACK =
      "Noted. Every previous finding your comment names by its `path:line` and title is treated as"
          + " cleared from the next review onward; any it does not name stays open.";

  private void handleMention(String auth, ReplyTask task) {
    if (FollowUpAnalyzer.isClearDirective(task.question())) {
      commentClient.createComment(
          auth,
          ACCEPT,
          task.owner(),
          task.repo(),
          task.prNumber(),
          new GitHubCommentClient.CreateCommentRequest(CLEAR_DIRECTIVE_ACK));
      Log.infof(
          "Acknowledged a maintainer clear directive on %s/%s #%d",
          task.owner(), task.repo(), task.prNumber());
      return;
    }
    var diff = fetchDiff(auth, task);
    String reply =
        generateReply(
            task.question(),
            PromptSections.prContext(task.prTitle(), task.prDescription()),
            "",
            diff.text(),
            "");
    if (reply == null) {
      return;
    }
    // Append the partial-coverage disclosure (empty unless the diff was line-capped), so a large-PR
    // answer is never presented as derived from the whole change — matching every other surface.
    commentClient.createComment(
        auth,
        ACCEPT,
        task.owner(),
        task.repo(),
        task.prNumber(),
        new GitHubCommentClient.CreateCommentRequest(reply + diff.disclosure()));
    Log.infof(
        "Posted conversational reply to mention on %s/%s #%d",
        task.owner(), task.repo(), task.prNumber());
  }

  /** Calls the assistant with already-raw inputs, escaping each for templating. Null on failure. */
  private String generateReply(
      String question, String prContext, String finding, String codeContext, String thread) {
    try {
      String reply =
          AiResponses.textOrThrowOnTruncation(
              replyAssistant.reply(
                  PromptTemplateEscaper.escape(question),
                  PromptTemplateEscaper.escape(prContext),
                  PromptTemplateEscaper.escape(finding),
                  // codeContext is the diff/hunk under discussion: fence it byte-exact.
                  PromptTemplateEscaper.fence(codeContext),
                  PromptTemplateEscaper.escape(thread)),
              "Maintainer reply");
      if (reply == null || reply.isBlank()) {
        Log.debug("Reply assistant produced an empty reply — posting nothing");
        return null;
      }
      return reply.strip();
    } catch (AiResponseTruncatedException e) {
      // Named separately from the generic failure: the cause is a cap the operator set, not a
      // provider error, and the message says which knob to raise.
      Log.warnf("Reply assistant — posting nothing. %s", e.getMessage());
      return null;
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
   */
  private static String renderThread(
      List<GitHubReviewClient.PullRequestComment> comments,
      long rootCommentId,
      long triggeringCommentId) {
    var sb = new StringBuilder();
    for (var c : comments) {
      if (c.inReplyToId() == null
          || c.inReplyToId() != rootCommentId
          || c.id() == triggeringCommentId) {
        continue;
      }
      String author = c.user() != null ? c.user().login() : "unknown";
      sb.append("- @").append(author).append(": ").append(c.body()).append("\n");
    }
    return sb.toString();
  }

  /**
   * The rendered PR diff handed to the reply model, plus any partial-coverage disclosure to append
   * to the posted answer ({@code ""} when nothing was omitted).
   */
  private record MentionDiff(String text, String disclosure) {
    static final MentionDiff EMPTY = new MentionDiff("", "");
  }

  private MentionDiff fetchDiff(String auth, ReplyTask task) {
    try {
      var files =
          prClient.getPullRequestFiles(auth, ACCEPT, task.owner(), task.repo(), task.prNumber());
      // Honor the repository's own ignore globs (#51) — the same trust boundary the review path and
      // every on-request command respect (#468/#469). A path a maintainer excluded in
      // .github/thrillhousebot.yml must not reach the model on a bot-mention reply. Resolved from
      // the base repo's default branch (no ref pinned), so a fork PR cannot inject its own ignore
      // list; fails soft to the global list, so a config load failure degrades rather than drops
      // the reply.
      var repoSettings =
          SoftLoaders.repoSettings(
              repoSettingsResolver,
              task.owner(),
              task.repo(),
              null,
              task.installationId(),
              "mention reply");
      var globs = diffFormatter.ignoreGlobs(repoSettings.ignoredFiles());
      var reviewable = diffFormatter.reviewableFiles(files, globs);
      // buildDiffStringWithStats keeps the omitted-file count the stats-free overload discarded, so
      // a line-capped diff can be disclosed instead of silently answered from a partial view.
      var formatted = diffFormatter.buildDiffStringWithStats(files, reviewable);
      String disclosure =
          formatted.truncated() ? ReviewResult.truncationDisclosure(formatted.omittedFiles()) : "";
      return new MentionDiff(formatted.text(), disclosure);
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch PR diff for mention reply, continuing without it", e);
      return MentionDiff.EMPTY;
    }
  }
}
