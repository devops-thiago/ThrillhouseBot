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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReactionClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures maintainer finding feedback from GitHub reactions and reply-body heuristics.
 *
 * <p>GitHub Apps do not receive a {@code reaction} webhook event, so reactions are polled via the
 * Reactions REST API when (1) a human replies on a review thread or (2) a follow-up review already
 * has inline comments loaded. Best-effort: failures are logged and never fail the webhook ACK or
 * the review.
 */
@ApplicationScoped
public class FindingFeedbackCaptureService {

  private static final Logger log = LoggerFactory.getLogger(FindingFeedbackCaptureService.class);

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String CONTENT_PLUS_ONE = "+1";
  private static final String CONTENT_MINUS_ONE = "-1";
  private static final int MAX_CONCURRENT_CAPTURES = 8;
  private static final Set<String> WRITE_PERMISSIONS = Set.of("admin", "maintain", "write");
  private static final Set<String> WRITE_CAPABLE_ASSOCIATIONS =
      Set.of("OWNER", "MEMBER", "COLLABORATOR");

  /** Cap on finding threads polled during a follow-up review (package-visible for tests). */
  static final int MAX_FINDINGS_PER_CAPTURE = 40;

  /**
   * Reply bodies that count as an explicit "not useful" signal when a human replies on a finding
   * thread. Kept conservative to avoid false training data for #38. Split into simple patterns so
   * each stays under Sonar's regex-complexity budget.
   */
  private static final List<Pattern> NOT_USEFUL_REPLY_PATTERNS =
      List.of(
          Pattern.compile("(?is)\\bnot\\s+useful\\b"),
          Pattern.compile("(?is)\\bfalse\\s+positive\\b"),
          Pattern.compile("(?is)\\bnot\\s+a\\s+(?:real\\s+)?(?:bug|issue)\\b"),
          Pattern.compile("(?is)\\bnoise\\b"),
          Pattern.compile("👎"),
          Pattern.compile(":-1:"));

  private final FindingFeedbackService feedbackService;
  private final BotIdentity botIdentity;
  private final GitHubAuthClient authClient;
  private final GitHubReactionClient reactionClient;
  private final GitHubReviewClient reviewClient;
  private final GitHubInstallationClient installationClient;

  private final ExecutorService captureExecutor =
      new ThreadPoolExecutor(
          0,
          MAX_CONCURRENT_CAPTURES,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          Thread.ofVirtual().name("finding-feedback-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  @Inject
  public FindingFeedbackCaptureService(
      FindingFeedbackService feedbackService,
      BotIdentity botIdentity,
      GitHubAuthClient authClient,
      @RestClient GitHubReactionClient reactionClient,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubInstallationClient installationClient) {
    this.feedbackService = feedbackService;
    this.botIdentity = botIdentity;
    this.authClient = authClient;
    this.reactionClient = reactionClient;
    this.reviewClient = reviewClient;
    this.installationClient = installationClient;
  }

  @PreDestroy
  void shutdown() {
    captureExecutor.shutdownNow();
  }

  /**
   * Schedules best-effort capture for a review-thread reply: fetch the root body (to confirm it is
   * a bot finding), poll 👍/👎 on that root, and apply reply-body heuristics. Never blocks the
   * webhook ACK thread beyond queueing the task.
   */
  public void scheduleCaptureOnReviewReply(
      long installationId,
      String owner,
      String repo,
      int prNumber,
      long rootCommentId,
      String replyAuthorLogin,
      String replyAuthorAssociation,
      String replyBody) {
    try {
      captureExecutor.execute(
          () -> {
            try {
              captureOnReviewReply(
                  installationId,
                  owner,
                  repo,
                  prNumber,
                  rootCommentId,
                  new ReviewReply(replyAuthorLogin, replyAuthorAssociation, replyBody));
            } catch (RuntimeException e) {
              log.warn(
                  "Finding feedback capture failed for {}/{} #{} comment {} (continuing)",
                  owner,
                  repo,
                  prNumber,
                  rootCommentId,
                  e);
            }
          });
    } catch (RejectedExecutionException _) {
      log.warn(
          "Finding feedback capture at capacity for {}/{} #{} comment {}; dropping task",
          owner,
          repo,
          prNumber,
          rootCommentId);
    }
  }

  /**
   * Polls reactions on bot finding-root comments already present in the loaded inline comment list
   * (every prior round on the PR, not only the immediately previous AI response). Candidates are
   * ordered by comment id ascending so the {@link #MAX_FINDINGS_PER_CAPTURE} cap is deterministic.
   * Uses the already-loaded auth header and comments — no extra comment list fetch.
   */
  public void captureOnPriorFindings(
      String auth,
      String owner,
      String repo,
      int prNumber,
      List<GitHubReviewClient.PullRequestComment> inlineComments) {
    if (inlineComments == null || inlineComments.isEmpty()) {
      return;
    }
    try {
      var candidates =
          inlineComments.stream()
              .filter(c -> c != null && c.inReplyToId() == null)
              .filter(c -> c.user() != null && botIdentity.matches(c.user().login()))
              .filter(c -> SuggestionFormatter.parseFindingMarker(c.body()).isPresent())
              .sorted(
                  Comparator.comparingLong(GitHubReviewClient.PullRequestComment::id).reversed())
              .limit(MAX_FINDINGS_PER_CAPTURE)
              .toList();
      var permissionCache = new HashMap<String, Boolean>();
      for (var comment : candidates) {
        captureReactions(
            auth, owner, repo, prNumber, comment.id(), comment.body(), permissionCache);
      }
    } catch (RuntimeException e) {
      log.warn(
          "Finding feedback capture on prior findings failed for {}/{} #{} (continuing)",
          owner,
          repo,
          prNumber,
          e);
    }
  }

  /** The reply that may carry feedback: who wrote it, their author association, and its body. */
  record ReviewReply(String authorLogin, String authorAssociation, String body) {}

  void captureOnReviewReply(
      long installationId,
      String owner,
      String repo,
      int prNumber,
      long rootCommentId,
      String replyAuthorLogin,
      String replyBody) {
    captureOnReviewReply(
        installationId,
        owner,
        repo,
        prNumber,
        rootCommentId,
        new ReviewReply(replyAuthorLogin, "OWNER", replyBody));
  }

  void captureOnReviewReply(
      long installationId,
      String owner,
      String repo,
      int prNumber,
      long rootCommentId,
      ReviewReply reply) {
    var replyAuthorLogin = reply.authorLogin();
    var replyBody = reply.body();
    var auth = authClient.getAuthHeader(installationId);
    var permissionCache = new HashMap<String, Boolean>();
    if (!mayHoldWriteAccess(reply.authorAssociation())
        || !hasWriteAccess(auth, owner, repo, replyAuthorLogin, permissionCache)) {
      return;
    }
    var root = fetchRootComment(auth, owner, repo, rootCommentId);
    if (root == null || root.user() == null || !botIdentity.matches(root.user().login())) {
      return;
    }
    OptionalInt findingIndex = SuggestionFormatter.parseFindingMarker(root.body());
    if (findingIndex.isEmpty()) {
      return;
    }
    captureReactions(auth, owner, repo, prNumber, rootCommentId, root.body(), permissionCache);
    captureReplyHeuristic(
        owner, repo, prNumber, rootCommentId, findingIndex.getAsInt(), replyAuthorLogin, replyBody);
  }

  private GitHubReviewClient.PullRequestComment fetchRootComment(
      String auth, String owner, String repo, long commentId) {
    try {
      return reviewClient.getPullRequestComment(auth, ACCEPT, owner, repo, commentId);
    } catch (RuntimeException e) {
      log.debug(
          "Failed to fetch review comment {} on {}/{} for feedback capture (continuing)",
          commentId,
          owner,
          repo,
          e);
      return null;
    }
  }

  void captureReactions(
      String auth, String owner, String repo, int prNumber, long commentId, String commentBody) {
    captureReactions(auth, owner, repo, prNumber, commentId, commentBody, new HashMap<>());
  }

  private void captureReactions(
      String auth,
      String owner,
      String repo,
      int prNumber,
      long commentId,
      String commentBody,
      Map<String, Boolean> permissionCache) {
    OptionalInt findingIndex = SuggestionFormatter.parseFindingMarker(commentBody);
    if (findingIndex.isEmpty()) {
      return;
    }
    var ctx =
        new ReactionPollContext(
            auth,
            owner,
            repo,
            owner + "/" + repo,
            prNumber,
            commentId,
            findingIndex.getAsInt(),
            permissionCache);
    listAndRecord(ctx, CONTENT_PLUS_ONE);
    listAndRecord(ctx, CONTENT_MINUS_ONE);
  }

  private record ReactionPollContext(
      String auth,
      String owner,
      String repo,
      String repoKey,
      int prNumber,
      long commentId,
      Integer findingIndex,
      Map<String, Boolean> permissionCache) {}

  private void listAndRecord(ReactionPollContext ctx, String content) {
    String signal =
        CONTENT_PLUS_ONE.equals(content)
            ? FindingFeedback.SIGNAL_USEFUL
            : FindingFeedback.SIGNAL_NOT_USEFUL;
    for (int page = 1; page <= GitHubReactionClient.MAX_REACTION_PAGES; page++) {
      List<GitHubReactionClient.Reaction> reactions = fetchReactionPage(ctx, content, page);
      if (reactions.isEmpty()) {
        return;
      }
      persistEligibleReactions(ctx, signal, reactions);
      if (reactions.size() < GitHubReactionClient.REACTIONS_PER_PAGE) {
        return;
      }
    }
  }

  private List<GitHubReactionClient.Reaction> fetchReactionPage(
      ReactionPollContext ctx, String content, int page) {
    try {
      var reactions =
          reactionClient.listReviewCommentReactions(
              ctx.auth(),
              ACCEPT,
              ctx.owner(),
              ctx.repo(),
              ctx.commentId(),
              content,
              GitHubReactionClient.REACTIONS_PER_PAGE,
              page);
      return reactions == null ? List.of() : reactions;
    } catch (RuntimeException e) {
      log.debug(
          "Failed to list {} reactions on review comment {} in {}/{} page {} (continuing)",
          content,
          ctx.commentId(),
          ctx.owner(),
          ctx.repo(),
          page,
          e);
      return List.of();
    }
  }

  private void persistEligibleReactions(
      ReactionPollContext ctx, String signal, List<GitHubReactionClient.Reaction> reactions) {
    for (var reaction : reactions) {
      String login = humanReactorLogin(reaction);
      if (login == null
          || !hasWriteAccess(ctx.auth(), ctx.owner(), ctx.repo(), login, ctx.permissionCache())) {
        continue;
      }
      feedbackService.recordFeedback(
          new FindingFeedbackService.FeedbackInput(
              ctx.repoKey(),
              ctx.prNumber(),
              ctx.commentId(),
              ctx.findingIndex(),
              signal,
              FindingFeedback.SOURCE_REACTION,
              login,
              reaction.id()));
    }
  }

  /** Non-bot reactor login, or {@code null} when the reaction should be ignored. */
  private String humanReactorLogin(GitHubReactionClient.Reaction reaction) {
    if (reaction == null || reaction.user() == null || reaction.user().login() == null) {
      return null;
    }
    String login = reaction.user().login();
    return botIdentity.matches(login) ? null : login;
  }

  private boolean hasWriteAccess(
      String auth, String owner, String repo, String login, Map<String, Boolean> permissionCache) {
    if (login == null || login.isBlank()) {
      return false;
    }
    return permissionCache.computeIfAbsent(
        login.toLowerCase(Locale.ROOT),
        ignored -> {
          try {
            var permission =
                installationClient.collaboratorPermission(auth, ACCEPT, owner, repo, login);
            var level = permission == null ? null : permission.permission();
            return level != null
                && WRITE_PERMISSIONS.contains(level.strip().toLowerCase(Locale.ROOT));
          } catch (RuntimeException e) {
            log.debug(
                "Failed to verify feedback actor @{} on {}/{}; ignoring signal",
                login,
                owner,
                repo,
                e);
            return false;
          }
        });
  }

  private static boolean mayHoldWriteAccess(String authorAssociation) {
    return authorAssociation != null
        && WRITE_CAPABLE_ASSOCIATIONS.contains(authorAssociation.strip().toUpperCase(Locale.ROOT));
  }

  void captureReplyHeuristic(
      String owner,
      String repo,
      int prNumber,
      long rootCommentId,
      int findingIndex,
      String replyAuthorLogin,
      String replyBody) {
    if (replyAuthorLogin == null
        || replyAuthorLogin.isBlank()
        || botIdentity.matches(replyAuthorLogin)
        || replyBody == null
        || replyBody.isBlank()
        || !isNotUsefulReply(replyBody)) {
      return;
    }
    feedbackService.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            owner + "/" + repo,
            prNumber,
            rootCommentId,
            findingIndex,
            FindingFeedback.SIGNAL_NOT_USEFUL,
            FindingFeedback.SOURCE_REPLY_HEURISTIC,
            replyAuthorLogin,
            null));
  }

  private static boolean isNotUsefulReply(String body) {
    return NOT_USEFUL_REPLY_PATTERNS.stream().anyMatch(p -> p.matcher(body).find());
  }
}
