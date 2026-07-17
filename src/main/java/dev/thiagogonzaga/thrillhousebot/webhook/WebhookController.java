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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.AutoReviewRateLimiter;
import dev.thiagogonzaga.thrillhousebot.review.FindingFeedbackCaptureService;
import dev.thiagogonzaga.thrillhousebot.review.MaintainerReplyDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.MaintainerReplyService;
import dev.thiagogonzaga.thrillhousebot.review.ReviewDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator;
import dev.thiagogonzaga.thrillhousebot.review.ReviewSkipEmitter;
import dev.thiagogonzaga.thrillhousebot.review.ReviewSkipReason;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebhookController {

  private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

  private static final String ACTION_READY_FOR_REVIEW = "ready_for_review";

  /** pull_request actions that trigger an automatic review. */
  private static final Set<String> AUTO_REVIEW_ACTIONS =
      Set.of("opened", "reopened", "synchronize", ACTION_READY_FOR_REVIEW);

  private final ThrillhouseConfig config;
  private final WebhookVerifier verifier;
  private final TriggerDetector triggerDetector;
  private final ReviewTriggerFilter triggerFilter;
  private final AutoReviewRateLimiter autoReviewRateLimiter;
  private final ReviewDispatcher reviewDispatcher;
  private final MaintainerReplyDispatcher replyDispatcher;
  private final WebhookDeduplicator deduplicator;
  private final ManualReviewAuthorizer manualReviewAuthorizer;
  private final CommentCommandService commentCommandService;
  private final PrPauseService prPauseService;
  private final AckReactionService ackReactionService;
  private final ReviewSkipEmitter skipEmitter;
  private final FindingFeedbackCaptureService findingFeedbackCapture;
  private final ObjectMapper mapper;

  @Inject
  public WebhookController(
      ThrillhouseConfig config,
      WebhookVerifier verifier,
      TriggerDetector triggerDetector,
      ReviewTriggerFilter triggerFilter,
      AutoReviewRateLimiter autoReviewRateLimiter,
      ReviewDispatcher reviewDispatcher,
      MaintainerReplyDispatcher replyDispatcher,
      WebhookDeduplicator deduplicator,
      ManualReviewAuthorizer manualReviewAuthorizer,
      CommentCommandService commentCommandService,
      PrPauseService prPauseService,
      AckReactionService ackReactionService,
      ReviewSkipEmitter skipEmitter,
      FindingFeedbackCaptureService findingFeedbackCapture,
      ObjectMapper mapper) {
    this.config = config;
    this.verifier = verifier;
    this.triggerDetector = triggerDetector;
    this.triggerFilter = triggerFilter;
    this.autoReviewRateLimiter = autoReviewRateLimiter;
    this.reviewDispatcher = reviewDispatcher;
    this.replyDispatcher = replyDispatcher;
    this.deduplicator = deduplicator;
    this.manualReviewAuthorizer = manualReviewAuthorizer;
    this.commentCommandService = commentCommandService;
    this.prPauseService = prPauseService;
    this.ackReactionService = ackReactionService;
    this.skipEmitter = skipEmitter;
    this.findingFeedbackCapture = findingFeedbackCapture;
    this.mapper = mapper;
  }

  @POST
  public Response handleWebhook(
      @HeaderParam("X-Hub-Signature-256") String signature,
      @HeaderParam("X-GitHub-Event") String eventType,
      @HeaderParam("X-GitHub-Hook-Installation-Target-Type") String targetType,
      @HeaderParam("X-GitHub-Delivery") String deliveryId,
      byte[] body) {
    if (!verifier.verify(signature, body, config.github().webhookSecret())) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity("{\"error\":\"Invalid signature\"}")
          .build();
    }

    WebhookPayload payload;
    try {
      payload = mapper.readValue(body, WebhookPayload.class);
    } catch (IOException e) {
      log.error("Failed to parse webhook payload", e);
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("{\"error\":\"Invalid payload\"}")
          .build();
    }

    log.info(
        "Received {} event (action: {}, delivery: {})", eventType, payload.action(), deliveryId);

    // GitHub redeliveries (manual or automatic retry) reuse the same delivery id.
    if (deduplicator.isDuplicate(deliveryId)) {
      log.info("Ignoring redelivered {} event (delivery: {})", eventType, deliveryId);
      recordDuplicateAutoReviewSkip(eventType, payload, deliveryId);
      return Response.ok("{\"status\":\"ignored\"}").build();
    }

    routeEvent(eventType, payload, deliveryId);

    // GitHub expects a 200 within 10 seconds.
    return Response.ok("{\"status\":\"ok\"}").build();
  }

  /**
   * Records a structured skip when a redelivered webhook would have produced an automatic review.
   * Redeliveries of non-review events (comments, pings, …) are ignored silently as before.
   */
  private void recordDuplicateAutoReviewSkip(
      String eventType, WebhookPayload payload, String deliveryId) {
    if (!"pull_request".equals(eventType)
        || payload.action() == null
        || !AUTO_REVIEW_ACTIONS.contains(payload.action())) {
      return;
    }
    var pr = payload.pullRequest();
    var repo = payload.repository();
    if (pr == null || repo == null || repo.owner() == null) {
      return;
    }
    skipEmitter.recordSkip(
        ReviewSkipReason.DUPLICATE_DELIVERY,
        repo.owner().login(),
        repo.name(),
        pr.number(),
        "redelivered webhook (delivery: " + deliveryId + ")");
  }

  private void routeEvent(String eventType, WebhookPayload payload, String deliveryId) {
    try {
      boolean handled =
          switch (eventType) {
            case "pull_request" -> handlePullRequest(payload);
            case "issue_comment" -> handleIssueComment(payload);
            case "pull_request_review_comment" -> handleReviewComment(payload);
            case "installation", "installation_repositories" -> {
              log.info(
                  "App installation event (action: {}), installation id: {}",
                  payload.action(),
                  payload.installation() != null ? payload.installation().id() : "unknown");
              yield true;
            }
            case "ping" -> {
              log.info("Webhook ping received");
              yield true;
            }
            default -> {
              log.debug("Unhandled event type: {}", eventType);
              yield true;
            }
          };
      if (!handled) {
        deduplicator.forget(deliveryId);
      }
    } catch (RuntimeException e) {
      deduplicator.forget(deliveryId);
      log.error("Error processing webhook event", e);
    }
  }

  /**
   * @return {@code false} only when a review was attempted but the dispatcher rejected it (so the
   *     dedup slot should be rolled back); {@code true} when the event was handled or legitimately
   *     ignored.
   */
  private boolean handlePullRequest(WebhookPayload payload) {
    var action = payload.action();
    if (action == null) return true;

    return switch (action) {
      // ready_for_review fires when a draft is marked ready and is not followed by a synchronize.
      case "opened", "reopened", "synchronize", ACTION_READY_FOR_REVIEW -> {
        var pr = payload.pullRequest();
        var repo = payload.repository();
        if (pr == null || repo == null || payload.installation() == null) {
          log.debug("Ignoring pull_request with missing pull_request, repository, or installation");
          yield true;
        }
        if (pr.head() == null || pr.base() == null) {
          log.debug("Ignoring pull_request #{} with missing head or base", pr.number());
          yield true;
        }

        if (prPauseService.isPaused(repo.owner().login(), repo.name(), pr.number())) {
          skipEmitter.recordSkip(
              ReviewSkipReason.PAUSED,
              repo.owner().login(),
              repo.name(),
              pr.number(),
              "PR is paused via /pause");
          yield true;
        }

        var skipReason = triggerFilter.skipReason(pr);
        if (skipReason.isPresent()) {
          skipEmitter.recordSkip(
              skipReason.get().reason(),
              repo.owner().login(),
              repo.name(),
              pr.number(),
              skipReason.get().detail());
          yield true;
        }

        if (ACTION_READY_FOR_REVIEW.equals(action)) {
          autoReviewRateLimiter.clearForPr(repo.owner().login(), repo.name(), pr.number());
        } else if (autoReviewRateLimiter.isThrottled(
            repo.owner().login(), repo.name(), pr.number())) {
          skipEmitter.recordSkip(
              ReviewSkipReason.RATE_LIMITED,
              repo.owner().login(),
              repo.name(),
              pr.number(),
              "last automatic review completed less than "
                  + config.review().autoReviewMinInterval()
                  + " ago (manual /review bypasses)");
          yield true;
        }

        log.info("Dispatching review for PR #{} in {}", pr.number(), repo.fullName());
        boolean queued =
            reviewDispatcher.dispatch(
                new ReviewOrchestrator.ReviewRequest(
                    repo.owner().login(),
                    repo.name(),
                    pr.number(),
                    pr.head().sha(),
                    pr.title(),
                    pr.body() != null ? pr.body() : "",
                    pr.base().sha(),
                    repo.defaultBranch(),
                    payload.installation().id(),
                    false,
                    pr.base().ref()));
        if (!queued) {
          skipEmitter.recordSkip(
              ReviewSkipReason.DISPATCH_REJECTED,
              repo.owner().login(),
              repo.name(),
              pr.number(),
              "review executor rejected the task; a webhook redelivery can retry");
        }
        yield queued;
      }
      default -> {
        log.debug("Ignoring pull_request action: {}", action);
        yield true;
      }
    };
  }

  /**
   * @return {@code false} only when a review was attempted but the dispatcher rejected it (so the
   *     dedup slot should be rolled back); {@code true} when the event was handled or legitimately
   *     ignored.
   */
  private boolean handleIssueComment(WebhookPayload payload) {
    if (!"created".equals(payload.action())) {
      log.debug("Ignoring issue_comment action: {}", payload.action());
      return true;
    }

    // issue_comment fires for both issues and PRs; the pull_request link marks a PR.
    if (payload.issue() == null || payload.issue().pullRequest() == null) {
      log.debug("Ignoring comment on issue (not PR)");
      return true;
    }

    if (payload.comment() == null || payload.comment().user() == null) {
      log.debug("Ignoring comment with missing author info");
      return true;
    }

    if (triggerDetector.isBotComment(payload.comment().user().login())) {
      log.debug("Ignoring own comment");
      return true;
    }

    var command = triggerDetector.detectCommand(payload.comment().body());
    if (command != CommentCommand.NONE) {
      return handleCommentCommand(payload, command);
    }
    return maybeDispatchConversationalMention(payload);
  }

  /**
   * Builds the command context and routes a recognized comment command. {@code /review} keeps its
   * synchronous authorize-then-dispatch path so the dispatch outcome can roll back the webhook
   * dedup slot; every other command runs asynchronously off the ack thread.
   */
  private boolean handleCommentCommand(WebhookPayload payload, CommentCommand command) {
    var repo = payload.repository();
    if (repo == null || payload.installation() == null) {
      log.debug("Ignoring {} command with missing repository or installation", command);
      return true;
    }
    ackReactionService.addEyes(
        payload.installation().id(),
        repo.owner().login(),
        repo.name(),
        payload.comment().id(),
        AckReactionService.CommentKind.ISSUE);
    // On issue_comment the issue number equals the PR number.
    var ctx =
        new CommentCommandService.CommandContext(
            command,
            repo.owner().login(),
            repo.name(),
            payload.issue().number(),
            repo.defaultBranch(),
            payload.installation().id(),
            payload.comment().user().login(),
            payload.comment().authorAssociation());

    if (command == CommentCommand.REVIEW) {
      return handleReviewCommand(ctx);
    }
    commentCommandService.handle(ctx);
    return true;
  }

  /** Dispatches a conversational reply for a bare {@code @thrillhousebot} mention on a PR. */
  private boolean maybeDispatchConversationalMention(WebhookPayload payload) {
    if (!config.review().conversationalRepliesEnabled()
        || !triggerDetector.containsBotMention(payload.comment().body())) {
      return true;
    }
    var repo = payload.repository();
    if (repo == null || payload.installation() == null) {
      log.debug("Ignoring bot mention with missing repository or installation");
      return true;
    }
    if (prPauseService.isPaused(repo.owner().login(), repo.name(), payload.issue().number())) {
      log.info(
          "Skipping conversational mention on paused PR #{} in {}",
          payload.issue().number(),
          repo.fullName());
      return true;
    }
    log.info(
        "Conversational mention from @{} on PR #{}",
        payload.comment().user().login(),
        payload.issue().number());
    return replyDispatcher.dispatch(
        new MaintainerReplyService.ReplyTask(
            repo.owner().login(),
            repo.name(),
            payload.issue().number(),
            payload.installation().id(),
            payload.comment().user().login(),
            payload.comment().authorAssociation(),
            payload.comment().body(),
            payload.issue().title(),
            payload.issue().body(),
            false,
            null,
            payload.comment().id(),
            true,
            null));
  }

  /**
   * Handles {@code pull_request_review_comment} events — a maintainer mentioning the bot on a diff
   * line or inside an inline review thread. Only an explicit {@code @}-mention gets a contextual
   * answer; a bare reply (even on the bot's own finding thread) is ignored.
   *
   * @return {@code false} only when a reply was attempted but the dispatcher rejected it (so the
   *     dedup slot should be rolled back); {@code true} when the event was handled or ignored.
   */
  private boolean handleReviewComment(WebhookPayload payload) {
    if (!"created".equals(payload.action())) {
      log.debug("Ignoring pull_request_review_comment action: {}", payload.action());
      return true;
    }

    var comment = payload.comment();
    if (comment == null || comment.user() == null) {
      log.debug("Ignoring review comment with missing author info");
      return true;
    }
    if (triggerDetector.isBotComment(comment.user().login())) {
      log.debug("Ignoring own review comment");
      return true;
    }

    var pr = payload.pullRequest();
    var repo = payload.repository();
    if (pr == null || repo == null || payload.installation() == null) {
      log.debug("Ignoring review comment with missing pull_request, repository, or installation");
      return true;
    }

    // GitHub thread roots carry no in_reply_to_id; a reply targets its thread's root.
    boolean isReply = comment.inReplyToId() != null;
    Long rootCommentId = isReply ? comment.inReplyToId() : comment.id();

    // Finding feedback (#324): poll 👍/👎 on the finding root when a human replies. Independent of
    // conversational replies — reactions are not delivered as a webhook event for GitHub Apps.
    if (isReply) {
      findingFeedbackCapture.scheduleCaptureOnReviewReply(
          payload.installation().id(),
          repo.owner().login(),
          repo.name(),
          pr.number(),
          rootCommentId,
          comment.user().login(),
          comment.body());
    }

    if (!config.review().conversationalRepliesEnabled()) {
      return true;
    }

    if (!triggerDetector.containsBotMention(comment.body())) {
      log.debug("Ignoring review comment that does not mention the bot");
      return true;
    }

    if (prPauseService.isPaused(repo.owner().login(), repo.name(), pr.number())) {
      log.info(
          "Skipping conversational review-thread reply on paused PR #{} in {}",
          pr.number(),
          repo.fullName());
      return true;
    }

    log.info(
        "Conversational review-thread reply from @{} on PR #{}",
        comment.user().login(),
        pr.number());
    return replyDispatcher.dispatch(
        new MaintainerReplyService.ReplyTask(
            repo.owner().login(),
            repo.name(),
            pr.number(),
            payload.installation().id(),
            comment.user().login(),
            comment.authorAssociation(),
            comment.body(),
            pr.title(),
            pr.body(),
            true,
            rootCommentId,
            comment.id(),
            true,
            comment.diffHunk()));
  }

  /**
   * Handles a manual {@code /review}: honors a pause, restricts the paid review to write-access
   * holders, then dispatches it.
   *
   * @return the dispatch outcome (so a rejected dispatch rolls back the dedup slot), or {@code
   *     true} when the command was handled without dispatching (paused or unauthorized).
   */
  private boolean handleReviewCommand(CommentCommandService.CommandContext ctx) {
    if (prPauseService.isPaused(ctx.owner(), ctx.repo(), ctx.prNumber())) {
      log.info(
          "Ignoring /review on paused PR #{} in {}/{}", ctx.prNumber(), ctx.owner(), ctx.repo());
      commentCommandService.notifyPaused(ctx);
      return true;
    }
    if (!manualReviewAuthorizer.isAuthorized(
        ctx.owner(), ctx.repo(), ctx.installationId(), ctx.login(), ctx.authorAssociation())) {
      log.info(
          "Ignoring unauthorized manual review trigger from @{} on PR #{}",
          ctx.login(),
          ctx.prNumber());
      return true;
    }
    log.info("Manual review triggered by @{} on PR #{}", ctx.login(), ctx.prNumber());
    return reviewDispatcher.dispatch(
        new ReviewOrchestrator.ReviewRequest(
            ctx.owner(),
            ctx.repo(),
            ctx.prNumber(),
            "",
            "(manual review)",
            "",
            "",
            ctx.defaultBranch(),
            ctx.installationId(),
            true));
  }
}
