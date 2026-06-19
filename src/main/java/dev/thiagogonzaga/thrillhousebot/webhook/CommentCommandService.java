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

import dev.thiagogonzaga.thrillhousebot.config.ReviewExecutor;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ReviewDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes the comment commands beyond {@code /review} — {@code /help}, {@code /summary}, {@code
 * /resolve}, {@code /pause}, {@code /resume}. Work runs on the shared review executor so the
 * webhook 200-ack thread is never blocked by GitHub API calls.
 */
@ApplicationScoped
public class CommentCommandService {

  private static final Logger log = LoggerFactory.getLogger(CommentCommandService.class);

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String BOT_LOGIN = "thrillhousebot[bot]";

  // GitHub serves 30 review comments per page by default; request the 100 max and walk a bounded
  // number of pages so /resolve covers every bot finding thread, not just the first page.
  private static final int COMMENTS_PER_PAGE = 100;
  private static final int MAX_COMMENT_PAGES = 10;

  /** Posted when a review-generating command lands on a paused PR. */
  static final String PAUSED_NOTICE =
      "⏸️ ThrillhouseBot is paused on this PR. Comment `/resume` to re-enable reviews.";

  static final String HELP_TEXT =
      """
      ## 🤖 ThrillhouseBot commands

      | Command | What it does |
      |---------|--------------|
      | `/review` | Run a fresh review of this PR |
      | `/summary` | Post the PR summary if one has not been generated yet |
      | `/resolve` | Resolve ThrillhouseBot's open finding threads on this PR |
      | `/pause` | Silence the bot on this PR (no automatic or manual reviews) |
      | `/resume` | Re-enable the bot on a paused PR |
      | `/help` | Show this list |

      You can also use the mention form, e.g. `@Thrillhousebot review`. \
      Every command except `/help` requires write access to the repository.""";

  private final ExecutorService executor;
  private final GitHubAuthClient authClient;
  private final GitHubCommentClient commentClient;
  private final GitHubReviewClient reviewClient;
  private final ReviewThreadService reviewThreadService;
  private final ReviewDispatcher reviewDispatcher;
  private final ReviewSessionPersistence sessionPersistence;
  private final PrPauseService prPauseService;
  private final ManualReviewAuthorizer authorizer;

  @Inject
  public CommentCommandService(
      @ReviewExecutor ExecutorService executor,
      GitHubAuthClient authClient,
      @RestClient GitHubCommentClient commentClient,
      @RestClient GitHubReviewClient reviewClient,
      ReviewThreadService reviewThreadService,
      ReviewDispatcher reviewDispatcher,
      ReviewSessionPersistence sessionPersistence,
      PrPauseService prPauseService,
      ManualReviewAuthorizer authorizer) {
    this.executor = executor;
    this.authClient = authClient;
    this.commentClient = commentClient;
    this.reviewClient = reviewClient;
    this.reviewThreadService = reviewThreadService;
    this.reviewDispatcher = reviewDispatcher;
    this.sessionPersistence = sessionPersistence;
    this.prPauseService = prPauseService;
    this.authorizer = authorizer;
  }

  /** PR coordinates and commenter identity for one command. */
  public record CommandContext(
      CommentCommand command,
      String owner,
      String repo,
      int prNumber,
      String defaultBranch,
      long installationId,
      String login,
      String authorAssociation) {}

  /** Runs the command's effect asynchronously. */
  public void handle(CommandContext ctx) {
    executor.execute(() -> execute(ctx));
  }

  /** Posts the paused notice asynchronously — used when a {@code /review} lands on a paused PR. */
  public void notifyPaused(CommandContext ctx) {
    executor.execute(
        () -> {
          try {
            postComment(authClient.getAuthHeader(ctx.installationId()), ctx, PAUSED_NOTICE);
          } catch (RuntimeException e) {
            log.warn(
                "Failed to post paused notice on {}/{} #{}", ctx.owner(), ctx.repo(), num(ctx), e);
          }
        });
  }

  /** Visible for tests: performs the command effect on the calling thread. */
  void execute(CommandContext ctx) {
    try {
      var auth = authClient.getAuthHeader(ctx.installationId());
      switch (ctx.command()) {
        case HELP -> postComment(auth, ctx, HELP_TEXT);
        case SUMMARY -> handleSummary(ctx, auth);
        case RESOLVE -> handleResolve(ctx, auth);
        case PAUSE -> handlePause(ctx, auth);
        case RESUME -> handleResume(ctx, auth);
        default -> log.debug("CommentCommandService ignoring command {}", ctx.command());
      }
    } catch (RuntimeException e) {
      log.error(
          "Failed to handle {} command on {}/{} #{}",
          ctx.command(),
          ctx.owner(),
          ctx.repo(),
          num(ctx),
          e);
    }
  }

  private void handleSummary(CommandContext ctx, String auth) {
    if (!authorized(ctx)) {
      log.info("Ignoring unauthorized /summary from @{} on PR #{}", ctx.login(), num(ctx));
      return;
    }
    if (prPauseService.isPaused(ctx.owner(), ctx.repo(), ctx.prNumber())) {
      postComment(auth, ctx, PAUSED_NOTICE);
      return;
    }
    if (sessionPersistence.hasCompletedReview(repository(ctx), ctx.prNumber())) {
      // A summary was already generated on the first review; the command is a no-op by design.
      log.info(
          "Ignoring /summary — a summary already exists on {}/{} #{}",
          ctx.owner(),
          ctx.repo(),
          num(ctx));
      return;
    }
    log.info(
        "Generating summary via review for {}/{} #{} (triggered by @{})",
        ctx.owner(),
        ctx.repo(),
        num(ctx),
        ctx.login());
    reviewDispatcher.dispatch(
        new ReviewOrchestrator.ReviewRequest(
            ctx.owner(),
            ctx.repo(),
            ctx.prNumber(),
            "",
            "(manual summary)",
            "",
            "",
            ctx.defaultBranch(),
            ctx.installationId(),
            true));
  }

  private void handleResolve(CommandContext ctx, String auth) {
    if (!authorized(ctx)) {
      log.info("Ignoring unauthorized /resolve from @{} on PR #{}", ctx.login(), num(ctx));
      return;
    }
    var botRootCommentIds = botRootCommentIds(auth, ctx);
    if (botRootCommentIds.isEmpty()) {
      postComment(auth, ctx, "No open ThrillhouseBot finding threads to resolve.");
      return;
    }
    var threads =
        reviewThreadService.threadsByRootComment(auth, ctx.owner(), ctx.repo(), ctx.prNumber());
    var resolved = 0;
    for (long rootCommentId : botRootCommentIds) {
      var thread = threads.get(rootCommentId);
      if (thread == null || thread.resolved()) {
        continue;
      }
      // A failure on one thread (e.g. a transient GraphQL error) must not abort the rest.
      try {
        if (reviewThreadService.resolve(auth, thread.id())) {
          resolved++;
        }
      } catch (RuntimeException e) {
        log.warn(
            "Failed to resolve thread {} on {} #{} (continuing)",
            thread.id(),
            repository(ctx),
            num(ctx),
            e);
      }
    }
    postComment(
        auth,
        ctx,
        resolved > 0
            ? "✅ Resolved " + resolved + " ThrillhouseBot finding thread(s)."
            : "No open ThrillhouseBot finding threads to resolve.");
  }

  private void handlePause(CommandContext ctx, String auth) {
    if (!authorized(ctx)) {
      log.info("Ignoring unauthorized /pause from @{} on PR #{}", ctx.login(), num(ctx));
      return;
    }
    if (!pauseIfPossible(ctx)) {
      return; // genuine failure already logged — do not post a misleading confirmation
    }
    postComment(
        auth,
        ctx,
        "⏸️ ThrillhouseBot is now paused on this PR — automatic and manual reviews are silenced. "
            + "Comment `/resume` to re-enable.");
  }

  /**
   * Pauses the PR, tolerating a concurrent {@code /pause} that wins the insert race. The unique
   * constraint lets only one of two simultaneous inserts succeed; the loser's transaction throws,
   * but the PR is paused either way — so a row that exists afterwards counts as success.
   *
   * @return whether the PR is paused after this call (newly, or by a concurrent {@code /pause})
   */
  private boolean pauseIfPossible(CommandContext ctx) {
    try {
      prPauseService.pause(ctx.owner(), ctx.repo(), ctx.prNumber());
      return true;
    } catch (RuntimeException e) {
      boolean paused = prPauseService.isPaused(ctx.owner(), ctx.repo(), ctx.prNumber());
      if (paused) {
        log.debug("Concurrent /pause already paused {}/{} #{}", ctx.owner(), ctx.repo(), num(ctx));
      } else {
        log.error("Failed to pause {}/{} #{}", ctx.owner(), ctx.repo(), num(ctx), e);
      }
      return paused;
    }
  }

  private void handleResume(CommandContext ctx, String auth) {
    if (!authorized(ctx)) {
      log.info("Ignoring unauthorized /resume from @{} on PR #{}", ctx.login(), num(ctx));
      return;
    }
    var wasPaused = prPauseService.resume(ctx.owner(), ctx.repo(), ctx.prNumber());
    postComment(
        auth,
        ctx,
        wasPaused
            ? "▶️ ThrillhouseBot resumed on this PR. Comment `/review` to run a review now."
            : "ThrillhouseBot was not paused on this PR.");
  }

  /**
   * Root-comment ids of the bot's own inline finding comments (thread roots carry no reply id).
   * Walks every page so a PR with many comments does not leave later-page bot threads unresolved.
   */
  private List<Long> botRootCommentIds(String auth, CommandContext ctx) {
    var ids = new ArrayList<Long>();
    List<GitHubReviewClient.PullRequestComment> comments;
    int page = 1;
    do {
      comments =
          reviewClient.listPullRequestComments(
              auth, ACCEPT, ctx.owner(), ctx.repo(), ctx.prNumber(), COMMENTS_PER_PAGE, page);
      if (comments != null) {
        for (var c : comments) {
          if (c.inReplyToId() == null
              && c.user() != null
              && BOT_LOGIN.equalsIgnoreCase(c.user().login())) {
            ids.add(c.id());
          }
        }
      }
      page++;
      // A short (or absent) page is GitHub's last-page marker; a full page means walk the next one.
    } while (comments != null && comments.size() == COMMENTS_PER_PAGE && page <= MAX_COMMENT_PAGES);
    return ids;
  }

  private boolean authorized(CommandContext ctx) {
    return authorizer.isAuthorized(
        ctx.owner(), ctx.repo(), ctx.installationId(), ctx.login(), ctx.authorAssociation());
  }

  private void postComment(String auth, CommandContext ctx, String body) {
    commentClient.createComment(
        auth,
        ACCEPT,
        ctx.owner(),
        ctx.repo(),
        ctx.prNumber(),
        new GitHubCommentClient.CreateCommentRequest(body));
  }

  private static String repository(CommandContext ctx) {
    return ctx.owner() + "/" + ctx.repo();
  }

  private static int num(CommandContext ctx) {
    return ctx.prNumber();
  }
}
