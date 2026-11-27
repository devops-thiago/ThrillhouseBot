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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Withdraws the bot's approval when commits are pushed after it, or when a pull request is reopened
 * on a head other than the one it approved. An approval is given for one head; once the head moves,
 * GitHub goes on counting it until someone dismisses it, and the review of the new head may run
 * late, fail or never run (a draft, a label or branch filter, a paused pull request, a restart
 * while the review waits for a limit). So a {@code synchronize} or {@code reopened} event dismisses
 * the bot's standing approvals at once, whether or not a review follows. The model is never called.
 *
 * <p>Runs on the dispatcher's per-pull-request worker, before the review the same push queued and
 * after any review in flight, so an approval that review is about to post is seen here. It costs
 * one listing of the pull request's reviews per push. Only when that listing holds an approval of
 * another commit is the head read once more: a delivery can arrive late or be redelivered after the
 * head moved again, and the approval to keep is the one for the head the pull request has now. A
 * head that cannot be read falls back to the event's, because an approval left on code nobody
 * reviewed is the worse outcome.
 *
 * <p>Nothing is dismissed while the bot has a standing approval of the current head, so a
 * force-push back to the approved commit keeps it. Otherwise every standing approval goes, through
 * the walk a round that does not approve uses ({@link ReviewPublisher#withdrawApprovalsAfterPush}).
 * A review of the new head that approves posts a new approval as usual. A dismissal also makes the
 * rate limiter forget the head its last review covered ({@link
 * AutoReviewRateLimiter#forgetCoveredHead}), so a head that returns to the commit whose approval
 * was just withdrawn is reviewed when the window closes, not skipped as already reviewed.
 */
@ApplicationScoped
public class ApprovalOnPush {

  private static final String ACCEPT = "application/vnd.github+json";

  /** One push to act on: the pull request, the head the event reported, the installation. */
  public record Push(
      String owner, String repo, int prNumber, String headSha, long installationId) {}

  private final GitHubAuthClient authClient;
  private final GitHubReviewClient reviewClient;
  private final GitHubPullRequestClient prClient;
  private final ReviewPublisher reviewPublisher;
  private final AutoReviewRateLimiter autoReviewRateLimiter;

  @Inject
  public ApprovalOnPush(
      GitHubAuthClient authClient,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubPullRequestClient prClient,
      ReviewPublisher reviewPublisher,
      AutoReviewRateLimiter autoReviewRateLimiter) {
    this.authClient = authClient;
    this.reviewClient = reviewClient;
    this.prClient = prClient;
    this.reviewPublisher = reviewPublisher;
    this.autoReviewRateLimiter = autoReviewRateLimiter;
  }

  /**
   * Dismisses the bot's standing approvals on the pushed pull request unless one of them is for its
   * head. A listing that fails is logged at warn and dismisses nothing; the caller catches whatever
   * else goes wrong, so the review of the new head runs either way.
   */
  @ActivateRequestContext
  public void withdraw(Push push) {
    var auth = authClient.getAuthHeader(push.installationId(), push.repo());
    var approvals = standingApprovals(auth, push);
    if (approvals.isEmpty() || approves(approvals, push.headSha())) {
      return;
    }
    var head = currentHead(auth, push);
    if (approves(approvals, head)) {
      Log.infof(
          "Keeping the approval of %s on %s/%s #%d — the head moved on from the pushed %s to it",
          head, push.owner(), push.repo(), push.prNumber(), push.headSha());
      return;
    }
    if (reviewPublisher.withdrawApprovalsAfterPush(
        auth, push.owner(), push.repo(), push.prNumber(), head, approvals)) {
      // The commit the last automatic review covered has no approval any more: if the head comes
      // back to it inside the rate window, it is reviewed again when the window closes.
      autoReviewRateLimiter.forgetCoveredHead(push.owner(), push.repo(), push.prNumber());
    }
  }

  /** The bot's approvals GitHub still counts, oldest first; empty when the listing failed. */
  private List<GitHubReviewClient.ReviewResponse> standingApprovals(String auth, Push push) {
    List<GitHubReviewClient.ReviewResponse> reviews;
    try {
      reviews = reviewClient.listReviews(auth, ACCEPT, push.owner(), push.repo(), push.prNumber());
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Could not list the reviews on %s/%s #%d after the push of %s: the bot's earlier"
              + " approvals could not be checked, so none is dismissed now and one it gave still"
              + " counts until a later push or review withdraws it or someone dismisses it",
          push.owner(),
          push.repo(),
          push.prNumber(),
          push.headSha());
      return List.of();
    }
    if (reviews.size() >= ReviewContextLoader.MAX_LISTED_REVIEWS) {
      Log.warnf(
          "Reviews on %s/%s #%d hit the %d-review read ceiling after the push of %s: GitHub lists"
              + " the oldest first, so an approval the bot gave among the newer ones is neither"
              + " seen nor dismissed",
          push.owner(),
          push.repo(),
          push.prNumber(),
          ReviewContextLoader.MAX_LISTED_REVIEWS,
          push.headSha());
    }
    return reviews.stream().filter(reviewPublisher::isStandingBotApproval).toList();
  }

  private static boolean approves(List<GitHubReviewClient.ReviewResponse> approvals, String head) {
    return approvals.stream().anyMatch(approval -> head.equalsIgnoreCase(approval.commitId()));
  }

  /** The pull request's head now, or the pushed one when it cannot be read. */
  private String currentHead(String auth, Push push) {
    try {
      return Optional.ofNullable(
              prClient.getPullRequest(auth, ACCEPT, push.owner(), push.repo(), push.prNumber()))
          .map(GitHubPullRequestClient.PullRequestDetails::head)
          .map(GitHubPullRequestClient.Ref::sha)
          .filter(sha -> !sha.isBlank())
          .orElse(push.headSha());
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Could not re-read the head of %s/%s #%d after the push of %s — taking the pushed commit"
              + " as its head",
          push.owner(),
          push.repo(),
          push.prNumber(),
          push.headSha());
    }
    return push.headSha();
  }
}
