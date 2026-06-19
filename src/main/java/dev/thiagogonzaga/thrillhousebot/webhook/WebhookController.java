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
  private final ReviewDispatcher reviewDispatcher;
  private final WebhookDeduplicator deduplicator;
  private final ManualReviewAuthorizer manualReviewAuthorizer;
  private final ObjectMapper mapper;

  @Inject
  public WebhookController(
      ThrillhouseConfig config,
      WebhookVerifier verifier,
      TriggerDetector triggerDetector,
      ReviewDispatcher reviewDispatcher,
      WebhookDeduplicator deduplicator,
      ManualReviewAuthorizer manualReviewAuthorizer,
      ObjectMapper mapper) {
    this.config = config;
    this.verifier = verifier;
    this.triggerDetector = triggerDetector;
    this.reviewDispatcher = reviewDispatcher;
    this.deduplicator = deduplicator;
    this.manualReviewAuthorizer = manualReviewAuthorizer;
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

    if (triggerDetector.isReviewTrigger(payload.comment().body())) {
      var repo = payload.repository();
      if (repo == null || payload.installation() == null) {
        log.debug("Ignoring review trigger with missing repository or installation");
        return true;
      }
      var owner = repo.owner().login();
      var login = payload.comment().user().login();
      var installationId = payload.installation().id();
      // Manual triggers spend the operator's API budget, so restrict them to users who actually
      // hold write access to the repository (or are explicitly allowlisted).
      if (!manualReviewAuthorizer.isAuthorized(
          owner, repo.name(), installationId, login, payload.comment().authorAssociation())) {
        log.info(
            "Ignoring unauthorized manual review trigger from @{} on PR #{}",
            login,
            payload.issue().number());
        return true;
      }
      log.info("Manual review triggered by @{} on PR #{}", login, payload.issue().number());
      // On issue_comment the issue number equals the PR number
      return reviewDispatcher.dispatch(
          new ReviewOrchestrator.ReviewRequest(
              owner,
              repo.name(),
              payload.issue().number(),
              "",
              "(manual review)",
              "",
              "",
              repo.defaultBranch(),
              installationId,
              true));
    }
    return true;
  }
}
