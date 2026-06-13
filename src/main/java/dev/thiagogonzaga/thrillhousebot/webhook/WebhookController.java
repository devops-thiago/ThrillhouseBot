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
  private final ObjectMapper mapper;

  @Inject
  public WebhookController(
      ThrillhouseConfig config,
      WebhookVerifier verifier,
      TriggerDetector triggerDetector,
      ReviewDispatcher reviewDispatcher,
      ObjectMapper mapper) {
    this.config = config;
    this.verifier = verifier;
    this.triggerDetector = triggerDetector;
    this.reviewDispatcher = reviewDispatcher;
    this.mapper = mapper;
  }

  @POST
  public Response handleWebhook(
      @HeaderParam("X-Hub-Signature-256") String signature,
      @HeaderParam("X-GitHub-Event") String eventType,
      @HeaderParam("X-GitHub-Hook-Installation-Target-Type") String targetType,
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

    log.info("Received {} event (action: {})", eventType, payload.action());

    routeEvent(eventType, payload);

    // GitHub expects 200 within 10 seconds; we acknowledge immediately
    return Response.ok("{\"status\":\"ok\"}").build();
  }

  private void routeEvent(String eventType, WebhookPayload payload) {
    try {
      switch (eventType) {
        case "pull_request" -> handlePullRequest(payload);
        case "issue_comment" -> handleIssueComment(payload);
        case "installation", "installation_repositories" ->
            log.info(
                "App installation event (action: {}), installation id: {}",
                payload.action(),
                payload.installation() != null ? payload.installation().id() : "unknown");
        case "ping" -> log.info("Webhook ping received");
        default -> log.debug("Unhandled event type: {}", eventType);
      }
    } catch (RuntimeException e) {
      log.error("Error processing webhook event", e);
    }
  }

  private void handlePullRequest(WebhookPayload payload) {
    var action = payload.action();
    if (action == null) return;

    switch (action) {
      case "opened", "reopened", "synchronize" -> {
        var pr = payload.pullRequest();
        var repo = payload.repository();
        if (pr == null || repo == null || payload.installation() == null) {
          log.debug("Ignoring pull_request with missing pull_request, repository, or installation");
          return;
        }
        log.info("Dispatching review for PR #{} in {}", pr.number(), repo.fullName());
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
                false));
      }
      default -> log.debug("Ignoring pull_request action: {}", action);
    }
  }

  private void handleIssueComment(WebhookPayload payload) {
    // Only act on PR comments, not issue comments
    if (payload.issue() == null || payload.issue().pullRequest() == null) {
      log.debug("Ignoring comment on issue (not PR)");
      return;
    }

    if (payload.comment() == null || payload.comment().user() == null) {
      log.debug("Ignoring comment with missing author info");
      return;
    }

    // Prevent infinite loops — skip bot's own comments
    if (triggerDetector.isBotComment(payload.comment().user().login())) {
      log.debug("Ignoring own comment");
      return;
    }

    if (triggerDetector.isReviewTrigger(payload.comment().body())) {
      // Manual triggers spend the operator's API budget, so restrict them to users with write
      // access (owner/member/collaborator). Otherwise anyone could repeatedly trigger paid reviews.
      if (!triggerDetector.isAuthorizedToTrigger(payload.comment().authorAssociation())) {
        log.info(
            "Ignoring unauthorized manual review trigger from @{} (association: {}) on PR #{}",
            payload.comment().user().login(),
            payload.comment().authorAssociation(),
            payload.issue().number());
        return;
      }
      var repo = payload.repository();
      if (repo == null || payload.installation() == null) {
        log.debug("Ignoring review trigger with missing repository or installation");
        return;
      }
      log.info(
          "Manual review triggered by @{} on PR #{}",
          payload.comment().user().login(),
          payload.issue().number());
      // On issue_comment the issue number equals the PR number
      reviewDispatcher.dispatch(
          new ReviewOrchestrator.ReviewRequest(
              repo.owner().login(),
              repo.name(),
              payload.issue().number(),
              "",
              "(manual review)",
              "",
              "",
              repo.defaultBranch(),
              payload.installation().id(),
              true));
    }
  }
}
