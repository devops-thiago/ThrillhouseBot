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
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ChangelogEntryGenerator;
import dev.thiagogonzaga.thrillhousebot.review.DocGenerationService;
import dev.thiagogonzaga.thrillhousebot.review.PrDescriptionGenerator;
import dev.thiagogonzaga.thrillhousebot.review.PrImprovementService;
import dev.thiagogonzaga.thrillhousebot.review.ReviewContextLoader;
import dev.thiagogonzaga.thrillhousebot.review.ReviewDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator;
import dev.thiagogonzaga.thrillhousebot.review.UnitTestGenerator;
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
 * /describe}, {@code /changelog}, {@code /add-docs}, {@code /improve}, {@code /generate-tests},
 * {@code /resolve}, {@code /pause}, {@code /resume}. Work runs on the shared review executor so the
 * webhook 200-ack thread is never blocked by GitHub API calls.
 *
 * <p>Every recognized command is acknowledged with a 👀 reaction before it reaches here, so a path
 * that ends without posting reads on the PR as a hang. The rule these handlers follow is therefore:
 * an acknowledged command produces exactly one visible reply — the requested output, or a one-line
 * notice saying why there is none (paused, switched off, already satisfied, nothing to say). The
 * single deliberate exception is a command from someone without write access: replying there would
 * let any commenter make the bot post, so those stay silent and are only logged.
 */
@ApplicationScoped
public class CommentCommandService {

  private static final Logger log = LoggerFactory.getLogger(CommentCommandService.class);

  private static final String ACCEPT = "application/vnd.github+json";

  /** Posted when a review-generating command lands on a paused PR. */
  static final String PAUSED_NOTICE =
      "⏸️ ThrillhouseBot is paused on this PR. Comment `/resume` to re-enable reviews.";

  /**
   * Posted when {@code /summary} declines because the PR already carries a bot summary. The command
   * only regenerates a summary that is missing, so the way to get a fresh one is to delete the
   * existing comment first.
   */
  static final String SUMMARY_ALREADY_PRESENT =
      "📋 A ThrillhouseBot summary is already on this PR, so `/summary` did nothing. "
          + "Delete that summary comment and comment `/summary` again to regenerate it.";

  static final String HELP_TEXT =
      """
      ## 🤖 ThrillhouseBot commands

      | Command | What it does |
      |---------|--------------|
      | `/review` | Run a fresh review of this PR |
      | `/summary` | Post the PR summary if it isn't already on the PR (regenerates a deleted one) |
      | `/describe` | Suggest an improved PR title and description from the diff |
      | `/changelog` | Draft a CHANGELOG entry for this PR from the diff |
      | `/add-docs` | Suggest docstrings for the symbols changed in this PR |
      | `/improve` | Suggest committable improvements across the whole PR |
      | `/generate-tests` | Suggest unit tests for the code changed in this PR |
      | `/resolve` | Resolve ThrillhouseBot's open finding threads on this PR |
      | `/pause` | Silence the bot on this PR (no automatic or manual reviews) |
      | `/resume` | Re-enable the bot on a paused PR |
      | `/help` | Show this list |

      You can also use the mention form, e.g. `@Thrillhousebot review`. \
      Every command except `/help` requires write access to the repository.""";

  private final ExecutorService executor;
  private final GitHubAuthClient authClient;
  private final GitHubCommentClient commentClient;
  private final GitHubPullRequestClient prClient;
  private final GitHubReviewClient reviewClient;
  private final ReviewThreadService reviewThreadService;
  private final ReviewDispatcher reviewDispatcher;
  private final ReviewContextLoader contextLoader;
  private final PrPauseService prPauseService;
  private final ManualReviewAuthorizer authorizer;
  private final TriggerDetector triggerDetector;
  private final PrDescriptionGenerator descriptionGenerator;
  private final ChangelogEntryGenerator changelogGenerator;
  private final DocGenerationService docGenerationService;
  private final PrImprovementService improvementService;
  private final UnitTestGenerator testGenerator;
  private final ThrillhouseConfig config;

  @Inject
  public CommentCommandService(
      @ReviewExecutor ExecutorService executor,
      GitHubAuthClient authClient,
      @RestClient GitHubCommentClient commentClient,
      @RestClient GitHubPullRequestClient prClient,
      @RestClient GitHubReviewClient reviewClient,
      ReviewThreadService reviewThreadService,
      ReviewDispatcher reviewDispatcher,
      ReviewContextLoader contextLoader,
      PrPauseService prPauseService,
      ManualReviewAuthorizer authorizer,
      TriggerDetector triggerDetector,
      PrDescriptionGenerator descriptionGenerator,
      ChangelogEntryGenerator changelogGenerator,
      DocGenerationService docGenerationService,
      PrImprovementService improvementService,
      UnitTestGenerator testGenerator,
      ThrillhouseConfig config) {
    this.executor = executor;
    this.authClient = authClient;
    this.commentClient = commentClient;
    this.prClient = prClient;
    this.reviewClient = reviewClient;
    this.reviewThreadService = reviewThreadService;
    this.reviewDispatcher = reviewDispatcher;
    this.contextLoader = contextLoader;
    this.prPauseService = prPauseService;
    this.authorizer = authorizer;
    this.triggerDetector = triggerDetector;
    this.descriptionGenerator = descriptionGenerator;
    this.changelogGenerator = changelogGenerator;
    this.docGenerationService = docGenerationService;
    this.improvementService = improvementService;
    this.testGenerator = testGenerator;
    this.config = config;
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
        case DESCRIBE -> handleDescribe(ctx, auth);
        case CHANGELOG -> handleChangelog(ctx, auth);
        case ADD_DOCS -> handleAddDocs(ctx, auth);
        case IMPROVE -> handleImprove(ctx, auth);
        case GENERATE_TESTS -> handleGenerateTests(ctx, auth);
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
    if (contextLoader.botSummaryCommentExists(auth, ctx.owner(), ctx.repo(), ctx.prNumber())) {
      log.info(
          "Declining /summary — a summary comment is already present on {}/{} #{}",
          ctx.owner(),
          ctx.repo(),
          num(ctx));
      postComment(auth, ctx, SUMMARY_ALREADY_PRESENT);
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
            true,
            "",
            true));
  }

  private void handleDescribe(CommandContext ctx, String auth) {
    if (!authorized(ctx)) {
      log.info("Ignoring unauthorized /describe from @{} on PR #{}", ctx.login(), num(ctx));
      return;
    }
    if (prPauseService.isPaused(ctx.owner(), ctx.repo(), ctx.prNumber())) {
      postComment(auth, ctx, PAUSED_NOTICE);
      return;
    }
    if (config.review().describe().apply()) {
      describeAndApply(ctx, auth);
      return;
    }
    log.info(
        "Generating title/description suggestion for {}/{} #{} (triggered by @{})",
        ctx.owner(),
        ctx.repo(),
        num(ctx),
        ctx.login());
    var suggestion =
        descriptionGenerator.generate(
            ctx.owner(),
            ctx.repo(),
            ctx.prNumber(),
            ctx.defaultBranch(),
            ctx.installationId(),
            auth);
    postComment(auth, ctx, suggestion != null ? suggestion : noOutputNotice("/describe"));
  }

  /**
   * The opt-in {@code /describe} apply path ({@code thrillhousebot.review.describe.apply=true}):
   * replaces the PR's title and body with the generated suggestion, then posts the confirmation
   * comment that preserves what was replaced. It reaches the PR only behind the same write-access
   * gate as every command plus that explicit config, and it degrades to the suggest-only comment —
   * never to silence, and never to a confirmation of an edit that did not happen — both when the
   * model output did not parse into a title and description and when the PR update itself failed.
   */
  private void describeAndApply(CommandContext ctx, String auth) {
    log.info(
        "Generating title/description for {}/{} #{} to apply (triggered by @{})",
        ctx.owner(),
        ctx.repo(),
        num(ctx),
        ctx.login());
    var suggestion =
        descriptionGenerator.generateSuggestion(
            ctx.owner(),
            ctx.repo(),
            ctx.prNumber(),
            ctx.defaultBranch(),
            ctx.installationId(),
            auth);
    if (suggestion == null) {
      postComment(auth, ctx, noOutputNotice("/describe"));
      return;
    }
    if (!suggestion.applicable()) {
      log.info(
          "/describe output for {}/{} #{} did not parse into a title and description —"
              + " posting it as a suggestion instead",
          ctx.owner(),
          ctx.repo(),
          num(ctx));
      postComment(auth, ctx, suggestion.suggestBody());
      return;
    }
    try {
      prClient.updatePullRequest(
          auth,
          ACCEPT,
          ctx.owner(),
          ctx.repo(),
          ctx.prNumber(),
          new GitHubPullRequestClient.UpdatePullRequestRequest(
              suggestion.title(), suggestion.description()));
    } catch (RuntimeException e) {
      log.warn(
          "Failed to update the title/body of {}/{} #{} — posting the suggestion instead",
          ctx.owner(),
          ctx.repo(),
          num(ctx),
          e);
      postComment(auth, ctx, suggestion.suggestBody());
      return;
    }
    // The audit trail of the overwrite: this log line for the operator, and the confirmation
    // comment below — carrying the replaced title and body — for the PR itself.
    log.info(
        "Applied /describe to {}/{} #{} (triggered by @{}): PR title and description replaced",
        ctx.owner(),
        ctx.repo(),
        num(ctx),
        ctx.login());
    postComment(auth, ctx, suggestion.applyBody());
  }

  private void handleChangelog(CommandContext ctx, String auth) {
    if (!authorized(ctx)) {
      log.info("Ignoring unauthorized /changelog from @{} on PR #{}", ctx.login(), num(ctx));
      return;
    }
    if (prPauseService.isPaused(ctx.owner(), ctx.repo(), ctx.prNumber())) {
      postComment(auth, ctx, PAUSED_NOTICE);
      return;
    }
    log.info(
        "Drafting changelog entry for {}/{} #{} (triggered by @{})",
        ctx.owner(),
        ctx.repo(),
        num(ctx),
        ctx.login());
    var entry =
        changelogGenerator.generate(
            ctx.owner(),
            ctx.repo(),
            ctx.prNumber(),
            ctx.defaultBranch(),
            ctx.installationId(),
            auth);
    postComment(auth, ctx, entry != null ? entry : noOutputNotice("/changelog"));
  }

  private void handleAddDocs(CommandContext ctx, String auth) {
    if (!authorized(ctx)) {
      log.info("Ignoring unauthorized /add-docs from @{} on PR #{}", ctx.login(), num(ctx));
      return;
    }
    if (!config.review().addDocsEnabled()) {
      declineAsDisabled(ctx, auth, "/add-docs", "add-docs-enabled");
      return;
    }
    if (prPauseService.isPaused(ctx.owner(), ctx.repo(), ctx.prNumber())) {
      postComment(auth, ctx, PAUSED_NOTICE);
      return;
    }
    log.info(
        "Generating docs for {}/{} #{} (triggered by @{})",
        ctx.owner(),
        ctx.repo(),
        num(ctx),
        ctx.login());
    docGenerationService.handle(
        new DocGenerationService.DocTask(
            ctx.owner(), ctx.repo(), ctx.prNumber(), ctx.defaultBranch(), ctx.installationId()));
  }

  private void handleImprove(CommandContext ctx, String auth) {
    if (!authorized(ctx)) {
      log.info("Ignoring unauthorized /improve from @{} on PR #{}", ctx.login(), num(ctx));
      return;
    }
    if (!config.review().improveEnabled()) {
      declineAsDisabled(ctx, auth, "/improve", "improve-enabled");
      return;
    }
    if (prPauseService.isPaused(ctx.owner(), ctx.repo(), ctx.prNumber())) {
      postComment(auth, ctx, PAUSED_NOTICE);
      return;
    }
    log.info(
        "Generating improvements for {}/{} #{} (triggered by @{})",
        ctx.owner(),
        ctx.repo(),
        num(ctx),
        ctx.login());
    improvementService.handle(
        new PrImprovementService.ImproveTask(
            ctx.owner(), ctx.repo(), ctx.prNumber(), ctx.defaultBranch(), ctx.installationId()),
        auth);
  }

  private void handleGenerateTests(CommandContext ctx, String auth) {
    if (!authorized(ctx)) {
      log.info("Ignoring unauthorized /generate-tests from @{} on PR #{}", ctx.login(), num(ctx));
      return;
    }
    if (!config.review().generateTestsEnabled()) {
      declineAsDisabled(ctx, auth, "/generate-tests", "generate-tests-enabled");
      return;
    }
    if (prPauseService.isPaused(ctx.owner(), ctx.repo(), ctx.prNumber())) {
      postComment(auth, ctx, PAUSED_NOTICE);
      return;
    }
    log.info(
        "Generating unit tests for {}/{} #{} (triggered by @{})",
        ctx.owner(),
        ctx.repo(),
        num(ctx),
        ctx.login());
    var suggestion =
        testGenerator.generate(
            ctx.owner(),
            ctx.repo(),
            ctx.prNumber(),
            ctx.defaultBranch(),
            ctx.installationId(),
            auth);
    postComment(auth, ctx, suggestion != null ? suggestion : noOutputNotice("/generate-tests"));
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
      return;
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
    for (var c :
        reviewClient.listPullRequestComments(
            auth, ACCEPT, ctx.owner(), ctx.repo(), ctx.prNumber())) {
      if (c.inReplyToId() == null
          && c.user() != null
          && triggerDetector.isBotComment(c.user().login())) {
        ids.add(c.id());
      }
    }
    return ids;
  }

  /**
   * Logs and answers a command that is switched off for this deployment, naming the config key an
   * administrator would flip. The kill-switch check sits after the authorization check in every
   * handler precisely so this reply exists: a notice posted before authorization would let any
   * commenter make the bot speak on a repository whose maintainers turned the command off.
   */
  private void declineAsDisabled(
      CommandContext ctx, String auth, String command, String configKey) {
    log.info("Declining {} on PR #{} — the command is disabled", command, num(ctx));
    postComment(
        auth,
        ctx,
        "🚫 `"
            + command
            + "` is disabled on this ThrillhouseBot deployment. An administrator can turn it back"
            + " on with `thrillhousebot.review."
            + configKey
            + "=true`.");
  }

  /**
   * The reply for a generator that produced nothing to post. The two causes are indistinguishable
   * from here — the generators return {@code null} both when the PR gave them nothing worth saying
   * and when every batch call failed — so the notice names both rather than assert a verdict the
   * caller cannot support.
   */
  private static String noOutputNotice(String command) {
    return "🤖 ThrillhouseBot has no `"
        + command
        + "` output to post for this PR — it found nothing to suggest, or the generation did not"
        + " complete. Re-run `"
        + command
        + "` to try again.";
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
