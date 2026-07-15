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
import dev.thiagogonzaga.thrillhousebot.github.GitHubReactionClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

  /** Cap on finding threads polled during a follow-up review (package-visible for tests). */
  static final int MAX_FINDINGS_PER_CAPTURE = 40;

  /**
   * Reply bodies that count as an explicit "not useful" signal when a human replies on a finding
   * thread. Kept conservative to avoid false training data for #38.
   */
  private static final Pattern NOT_USEFUL_REPLY =
      Pattern.compile(
          "(?is).*\\b(not\\s+useful|false\\s+positive|not\\s+a\\s+(real\\s+)?(bug|issue)|noise)\\b.*"
              + "|.*👎.*|.*:-1:.*");

  private final FindingFeedbackService feedbackService;
  private final BotIdentity botIdentity;
  private final GitHubAuthClient authClient;
  private final GitHubReactionClient reactionClient;
  private final GitHubReviewClient reviewClient;

  private final ExecutorService captureExecutor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("finding-feedback-", 0).factory());

  @Inject
  public FindingFeedbackCaptureService(
      FindingFeedbackService feedbackService,
      BotIdentity botIdentity,
      GitHubAuthClient authClient,
      @RestClient GitHubReactionClient reactionClient,
      @RestClient GitHubReviewClient reviewClient) {
    this.feedbackService = feedbackService;
    this.botIdentity = botIdentity;
    this.authClient = authClient;
    this.reactionClient = reactionClient;
    this.reviewClient = reviewClient;
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
      String replyBody) {
    captureExecutor.execute(
        () -> {
          try {
            captureOnReviewReply(
                installationId, owner, repo, prNumber, rootCommentId, replyAuthorLogin, replyBody);
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
              .sorted(Comparator.comparingLong(GitHubReviewClient.PullRequestComment::id))
              .limit(MAX_FINDINGS_PER_CAPTURE)
              .toList();
      for (var comment : candidates) {
        captureReactions(auth, owner, repo, prNumber, comment.id(), comment.body());
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

  void captureOnReviewReply(
      long installationId,
      String owner,
      String repo,
      int prNumber,
      long rootCommentId,
      String replyAuthorLogin,
      String replyBody) {
    var auth = authClient.getAuthHeader(installationId);
    String rootBody = fetchCommentBody(auth, owner, repo, rootCommentId);
    OptionalInt findingIndex = SuggestionFormatter.parseFindingMarker(rootBody);
    if (findingIndex.isEmpty()) {
      return;
    }
    captureReactions(auth, owner, repo, prNumber, rootCommentId, rootBody);
    captureReplyHeuristic(
        owner, repo, prNumber, rootCommentId, findingIndex.getAsInt(), replyAuthorLogin, replyBody);
  }

  private String fetchCommentBody(String auth, String owner, String repo, long commentId) {
    try {
      var comment = reviewClient.getPullRequestComment(auth, ACCEPT, owner, repo, commentId);
      return comment != null ? comment.body() : null;
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
    OptionalInt findingIndex = SuggestionFormatter.parseFindingMarker(commentBody);
    if (findingIndex.isEmpty()) {
      return;
    }
    var repoKey = owner + "/" + repo;
    Integer index = findingIndex.getAsInt();
    listAndRecord(auth, owner, repo, repoKey, prNumber, commentId, index, CONTENT_PLUS_ONE);
    listAndRecord(auth, owner, repo, repoKey, prNumber, commentId, index, CONTENT_MINUS_ONE);
  }

  private void listAndRecord(
      String auth,
      String owner,
      String repo,
      String repoKey,
      int prNumber,
      long commentId,
      Integer findingIndex,
      String content) {
    String signal =
        CONTENT_PLUS_ONE.equals(content)
            ? FindingFeedback.SIGNAL_USEFUL
            : FindingFeedback.SIGNAL_NOT_USEFUL;
    for (int page = 1; page <= GitHubReactionClient.MAX_REACTION_PAGES; page++) {
      List<GitHubReactionClient.Reaction> reactions;
      try {
        reactions =
            reactionClient.listReviewCommentReactions(
                auth,
                ACCEPT,
                owner,
                repo,
                commentId,
                content,
                GitHubReactionClient.REACTIONS_PER_PAGE,
                page);
      } catch (RuntimeException e) {
        log.debug(
            "Failed to list {} reactions on review comment {} in {}/{} page {} (continuing)",
            content,
            commentId,
            owner,
            repo,
            page,
            e);
        return;
      }
      if (reactions == null || reactions.isEmpty()) {
        return;
      }
      for (var reaction : reactions) {
        if (reaction == null || reaction.user() == null || reaction.user().login() == null) {
          continue;
        }
        if (botIdentity.matches(reaction.user().login())) {
          continue;
        }
        feedbackService.record(
            repoKey,
            prNumber,
            commentId,
            findingIndex,
            signal,
            FindingFeedback.SOURCE_REACTION,
            reaction.user().login(),
            reaction.id());
      }
      if (reactions.size() < GitHubReactionClient.REACTIONS_PER_PAGE) {
        return;
      }
    }
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
        || !NOT_USEFUL_REPLY.matcher(replyBody).matches()) {
      return;
    }
    feedbackService.record(
        owner + "/" + repo,
        prNumber,
        rootCommentId,
        findingIndex,
        FindingFeedback.SIGNAL_NOT_USEFUL,
        FindingFeedback.SOURCE_REPLY_HEURISTIC,
        replyAuthorLogin,
        null);
  }
}
