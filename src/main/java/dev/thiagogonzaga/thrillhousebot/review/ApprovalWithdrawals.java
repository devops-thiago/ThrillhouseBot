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
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubApiError;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubWriteBudget;
import io.quarkus.logging.Log;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dismisses the approvals this bot still has on a pull request: for a round that does not end in an
 * approving review ({@link #afterRound}), and when commits were pushed after them ({@link
 * #afterPush}). {@link ReviewPublisher} owns the one instance.
 */
final class ApprovalWithdrawals {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String REVIEW_APPROVED = "APPROVED";
  private static final String REVIEW_DISMISSED = "DISMISSED";

  private static final String DISMISSAL_REFUSED_HINT =
      "GitHub refused the call, so the bot's older approvals were not tried. Check that the App has"
          + " Pull requests: write and that branch protection lets it dismiss reviews";

  private static final String DISMISSAL_FAILED_HINT =
      "The failure is not a permission refusal, so the bot's older approvals are still tried";

  private final GitHubReviewClient reviewClient;
  private final ThrillhouseConfig config;
  private final BotIdentity botIdentity;

  ApprovalWithdrawals(
      GitHubReviewClient reviewClient, ThrillhouseConfig config, BotIdentity botIdentity) {
    this.reviewClient = reviewClient;
    this.config = config;
    this.botIdentity = botIdentity;
  }

  /**
   * Dismisses the approvals this bot still has on the pull request, for a round that does not end
   * in an approving review. GitHub goes on counting an {@code APPROVED} review after its author
   * posts a later {@code COMMENT} one, so without this an approval of an earlier head would keep
   * satisfying a required-review rule for code the bot has since declined to approve.
   *
   * <p>Only a review that is still {@code APPROVED} and whose author is one of the bot's own logins
   * is touched: a person's review, another app's, and one GitHub already dismissed are left alone.
   * Every such approval is dismissed, newest first, not only the latest, so an older one cannot
   * count again once the newest is gone.
   *
   * <p>The approvals come from the run's one listing of the pull request's reviews ({@link
   * ReviewListing}: the newest 1,000, and the bot's own approvals from before those). A listing
   * that failed reaches here empty, so that round dismisses nothing; the next one reads the reviews
   * again. {@link ReviewContextLoader#fetchPriorReviews} logs that failure at warn.
   *
   * <p>Best-effort: a dismissal GitHub refuses (on a protected branch its API reference asks for a
   * repository administrator or a place on the rule's list of who may dismiss reviews) or that
   * fails is logged and the round is published as usual. The walk stops only at a permission
   * refusal (a 403 that is not a rate limit, {@link #isPermissionRefusal}), since the same refusal
   * would meet every other call. Any other failure is about that one call or that one review: a
   * timeout, a 5xx, a 404 or 422 (the review is gone or was dismissed since the listing), or a rate
   * limit that outlasted the write retries. The older approvals are still tried after it. An
   * approval that could not be dismissed stays until a later push or round withdraws it or a person
   * dismisses it. Runs inside the write-retry budget of the round's publication.
   *
   * <p>An approval already dismissed when commits were pushed ({@link #afterPush}) is {@code
   * DISMISSED} in the listing and is skipped here.
   *
   * @return whether at least one approval was dismissed
   */
  boolean afterRound(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      List<GitHubReviewClient.ReviewResponse> priorReviews) {
    return withdraw(
        new Withdrawal(
            auth,
            owner,
            repo,
            prNumber,
            approvalWithdrawnMessage(commitSha),
            "the round for " + commitSha + " did not approve"),
        priorReviews);
  }

  /**
   * Dismisses the approvals this bot still has in {@code reviews} because commits were pushed after
   * them and {@code headSha}, the pull request's head now, has no review yet ({@link
   * ApprovalOnPush}). The same walk and rules as a round that does not approve, with the message
   * for this case, under a write-retry budget of its own so a throttled dismissal cannot hold the
   * pull request's worker for longer than a review's publication may.
   *
   * @return whether at least one approval was dismissed
   */
  boolean afterPush(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String headSha,
      List<GitHubReviewClient.ReviewResponse> reviews) {
    var withdrawal =
        new Withdrawal(
            auth,
            owner,
            repo,
            prNumber,
            approvalWithdrawnOnPushMessage(headSha),
            "commits were pushed after it and " + headSha + " has not been reviewed");
    var dismissed = new AtomicBoolean();
    GitHubWriteBudget.within(
        owner + "/" + repo + " #" + prNumber,
        config.github().writeRetryBudget(),
        () -> dismissed.set(withdraw(withdrawal, reviews)));
    return dismissed.get();
  }

  /** A submitted approval by this bot that GitHub has not already dismissed. */
  boolean isStandingBotApproval(GitHubReviewClient.ReviewResponse review) {
    return REVIEW_APPROVED.equals(review.state()) && isByBot(review);
  }

  /** A review by this bot that was dismissed, by the bot, by GitHub on a push or by a person. */
  boolean isDismissedBotReview(GitHubReviewClient.ReviewResponse review) {
    return REVIEW_DISMISSED.equals(review.state()) && isByBot(review);
  }

  private boolean isByBot(GitHubReviewClient.ReviewResponse review) {
    // A review from a since-deleted account serializes as user:null.
    return review.user() != null && botIdentity.matches(review.user().login());
  }

  /** The walk: every standing approval, newest first, until GitHub refuses one. */
  private boolean withdraw(Withdrawal withdrawal, List<GitHubReviewClient.ReviewResponse> reviews) {
    var dismissed = false;
    for (var approval : reviews.reversed().stream().filter(this::isStandingBotApproval).toList()) {
      var outcome = dismissApproval(withdrawal, approval);
      if (outcome == Dismissal.REFUSED) {
        break;
      }
      dismissed |= outcome == Dismissal.DONE;
    }
    return dismissed;
  }

  /**
   * One walk over the bot's standing approvals: the pull request, the reason GitHub shows beside
   * each dismissed approval, and the same reason as the log lines word it.
   */
  private record Withdrawal(
      String auth, String owner, String repo, int prNumber, String message, String cause) {}

  /** How one dismissal call ended. */
  private enum Dismissal {
    DONE,
    /** GitHub answered 403 without throttling: the App may not dismiss reviews here. */
    REFUSED,
    /** No answer, a server error, a throttle or a 4xx about this review: the next call may work. */
    FAILED
  }

  /** Dismisses one approval, logging a call GitHub refused or that failed. */
  private Dismissal dismissApproval(
      Withdrawal withdrawal, GitHubReviewClient.ReviewResponse approval) {
    try {
      reviewClient.dismissReview(
          withdrawal.auth(),
          ACCEPT,
          withdrawal.owner(),
          withdrawal.repo(),
          withdrawal.prNumber(),
          approval.id(),
          withdrawal.message());
      Log.infof(
          "Dismissed approval %d on %s/%s #%d — %s",
          approval.id(),
          withdrawal.owner(),
          withdrawal.repo(),
          withdrawal.prNumber(),
          withdrawal.cause());
      return Dismissal.DONE;
    } catch (RuntimeException e) {
      var refused = isPermissionRefusal(e);
      Log.warnf(
          e,
          "Could not dismiss approval %d on %s/%s #%d although %s — it still counts towards the"
              + " pull request's approvals until someone dismisses it. %s",
          approval.id(),
          withdrawal.owner(),
          withdrawal.repo(),
          withdrawal.prNumber(),
          withdrawal.cause(),
          refused ? DISMISSAL_REFUSED_HINT : DISMISSAL_FAILED_HINT);
      return refused ? Dismissal.REFUSED : Dismissal.FAILED;
    }
  }

  /**
   * Whether GitHub refused this call for want of permission ({@link
   * GitHubApiError#isPermissionRefusal()}). Every other answer says nothing about the App's right
   * to dismiss reviews here: a 404 or 422 is about the one review, and a 429 or a rate-limit 403 is
   * a throttle the write retries did not outlast.
   */
  private static boolean isPermissionRefusal(RuntimeException failure) {
    return ReviewPublisher.webApplicationFailure(failure)
        .flatMap(GitHubApiError::of)
        .filter(GitHubApiError::isPermissionRefusal)
        .isPresent();
  }

  /**
   * The reason GitHub shows on the timeline beside a dismissed approval. It names the round, not a
   * review, because a round held on CI can withdraw an approval without posting a review.
   */
  static String approvalWithdrawnMessage(String commitSha) {
    return "ThrillhouseBot withdrew this approval: its review round for "
        + commitSha
        + " did not approve the pull request.";
  }

  /**
   * The reason beside an approval dismissed because the head moved on from the commit it approved,
   * before any review of the new head: the approval is withdrawn on the push itself.
   */
  static String approvalWithdrawnOnPushMessage(String headSha) {
    return "ThrillhouseBot withdrew this approval: commits were pushed after it, and "
        + headSha
        + " has not been reviewed yet.";
  }
}
