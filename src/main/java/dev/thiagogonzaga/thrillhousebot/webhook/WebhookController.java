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
import dev.thiagogonzaga.thrillhousebot.review.MaintainerReplyDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.MaintainerReplyService;
import dev.thiagogonzaga.thrillhousebot.review.ReviewDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebhookController {

  private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

  private final ThrillhouseConfig config;
  private final WebhookVerifier verifier;
  private final TriggerDetector triggerDetector;
  private final ReviewTriggerFilter triggerFilter;
  private final ReviewDispatcher reviewDispatcher;
  private final MaintainerReplyDispatcher replyDispatcher;
  private final WebhookDeduplicator deduplicator;
  private final ManualReviewAuthorizer manualReviewAuthorizer;
  private final CommentCommandService commentCommandService;
  private final PrPauseService prPauseService;
  private final ObjectMapper mapper;

  @Inject
  public WebhookController(
      ThrillhouseConfig config,
      WebhookVerifier verifier,
      TriggerDetector triggerDetector,
      ReviewTriggerFilter triggerFilter,
      ReviewDispatcher reviewDispatcher,
      MaintainerReplyDispatcher replyDispatcher,
      WebhookDeduplicator deduplicator,
      ManualReviewAuthorizer manualReviewAuthorizer,
      CommentCommandService commentCommandService,
      PrPauseService prPauseService,
      ObjectMapper mapper) {
    this.config = config;
    this.verifier = verifier;
    this.triggerDetector = triggerDetector;
    this.triggerFilter = triggerFilter;
    this.reviewDispatcher = reviewDispatcher;
    this.replyDispatcher = replyDispatcher;
    this.deduplicator = deduplicator;
    this.manualReviewAuthorizer = manualReviewAuthorizer;
    this.commentCommandService = commentCommandService;
    this.prPauseService = prPauseService;
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

    // GitHub redelivers webhooks — manual redelivery or an automatic retry — reusing the same
    // delivery id, so drop repeats to avoid a duplicate review and duplicate AI cost.
    if (deduplicator.isDuplicate(deliveryId)) {
      log.info("Ignoring redelivered {} event (delivery: {})", eventType, deliveryId);
      return Response.ok("{\"status\":\"ignored\"}").build();
    }

    routeEvent(eventType, payload, deliveryId);

    // GitHub expects 200 within 10 seconds; we acknowledge immediately
    return Response.ok("{\"status\":\"ok\"}").build();
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
        // The executor rejected the review (e.g. it is saturated), so nothing ran. Roll back the
        // dedup slot so a manual redelivery of this id can retry. (#89)
        deduplicator.forget(deliveryId);
      }
    } catch (RuntimeException e) {
      // An unexpected failure means the review was never handed off either, so roll back the dedup
      // slot for the same reason before acknowledging.
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
      // ready_for_review fires when a draft PR is marked ready; it is not followed by a
      // synchronize, so without this the PR stays unreviewed until the next push. (#72)
      case "opened", "reopened", "synchronize", "ready_for_review" -> {
        var pr = payload.pullRequest();
        var repo = payload.repository();
        if (pr == null || repo == null || payload.installation() == null) {
          log.debug("Ignoring pull_request with missing pull_request, repository, or installation");
          yield true;
        }
        // head/base drive the review request; a payload missing either would NPE below. The trigger
        // filter already treats base as nullable, so guard here rather than crash the delivery.
        if (pr.head() == null || pr.base() == null) {
          log.debug("Ignoring pull_request #{} with missing head or base", pr.number());
          yield true;
        }

        // A /pause on this PR silences automatic reviews until /resume — stay silent here, no
        // comment, since the event was not user-initiated.
        if (prPauseService.isPaused(repo.owner().login(), repo.name(), pr.number())) {
          log.info("Skipping review for paused PR #{} in {}", pr.number(), repo.fullName());
          yield true;
        }

        var skipReason = triggerFilter.skipReason(pr);
        if (skipReason.isPresent()) {
          log.info(
              "Skipping review for PR #{} in {}: {}",
              pr.number(),
              repo.fullName(),
              skipReason.get());
          yield true;
        }

        log.info("Dispatching review for PR #{} in {}", pr.number(), repo.fullName());
        yield reviewDispatcher.dispatch(
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
                false));
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
    // Only a freshly created comment is a trigger; editing or deleting one must not re-run a
    // review.
    if (!"created".equals(payload.action())) {
      log.debug("Ignoring issue_comment action: {}", payload.action());
      return true;
    }

    // Only act on PR comments, not issue comments
    if (payload.issue() == null || payload.issue().pullRequest() == null) {
      log.debug("Ignoring comment on issue (not PR)");
      return true;
    }

    if (payload.comment() == null || payload.comment().user() == null) {
      log.debug("Ignoring comment with missing author info");
      return true;
    }

    // Prevent infinite loops — skip bot's own comments
    if (triggerDetector.isBotComment(payload.comment().user().login())) {
      log.debug("Ignoring own comment");
      return true;
    }

    var command = triggerDetector.detectCommand(payload.comment().body());
    if (command != CommentCommand.NONE) {
      return handleCommentCommand(payload, command);
    }
    // Not a command: a bare @thrillhousebot mention on a PR is a conversational question.
    return maybeDispatchConversationalMention(payload);
  }

  /**
   * Builds the command context and routes a recognized comment command. {@code /review} keeps its
   * synchronous authorize-then-dispatch path so the dispatch outcome can roll back the webhook
   * dedup slot (#89); every other command runs asynchronously off the ack thread.
   */
  private boolean handleCommentCommand(WebhookPayload payload, CommentCommand command) {
    var repo = payload.repository();
    if (repo == null || payload.installation() == null) {
      log.debug("Ignoring {} command with missing repository or installation", command);
      return true;
    }
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
   * Handles {@code pull_request_review_comment} events — a maintainer replying inside an inline
   * review thread, or mentioning the bot on a diff line. A reply on the bot's own finding thread
   * (or any inline comment mentioning the bot) gets a contextual answer.
   *
   * @return {@code false} only when a reply was attempted but the dispatcher rejected it (so the
   *     dedup slot should be rolled back); {@code true} when the event was handled or ignored.
   */
  private boolean handleReviewComment(WebhookPayload payload) {
    if (!"created".equals(payload.action())) {
      log.debug("Ignoring pull_request_review_comment action: {}", payload.action());
      return true;
    }
    if (!config.review().conversationalRepliesEnabled()) {
      return true;
    }

    var comment = payload.comment();
    if (comment == null || comment.user() == null) {
      log.debug("Ignoring review comment with missing author info");
      return true;
    }
    // Prevent infinite loops — never answer the bot's own comments.
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

    boolean mentioned = triggerDetector.containsBotMention(comment.body());
    boolean isReply = comment.inReplyToId() != null;
    // Only a reply (possibly to the bot's finding — confirmed downstream) or an explicit mention is
    // addressed to the bot; a brand-new inline comment from a human is not.
    if (!mentioned && !isReply) {
      log.debug("Ignoring review comment that neither replies to a thread nor mentions the bot");
      return true;
    }

    // The thread root is the reply target; a top-level comment that mentions the bot is its own
    // root.
    Long rootCommentId = isReply ? comment.inReplyToId() : comment.id();
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
            mentioned,
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
    // Manual triggers spend the operator's API budget, so restrict them to users who actually
    // hold write access to the repository (or are explicitly allowlisted).
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
