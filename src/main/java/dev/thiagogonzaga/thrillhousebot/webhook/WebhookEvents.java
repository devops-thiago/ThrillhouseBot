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
import dev.thiagogonzaga.thrillhousebot.github.AllowedAccounts;
import dev.thiagogonzaga.thrillhousebot.github.InstallationChange;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import dev.thiagogonzaga.thrillhousebot.review.AutoReviewRateLimiter;
import dev.thiagogonzaga.thrillhousebot.review.CiHoldRegistry;
import dev.thiagogonzaga.thrillhousebot.review.CiHoldRevisit;
import dev.thiagogonzaga.thrillhousebot.review.FindingFeedbackCaptureService;
import dev.thiagogonzaga.thrillhousebot.review.MaintainerReplyDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.MaintainerReplyService;
import dev.thiagogonzaga.thrillhousebot.review.PullRequestLabelGate;
import dev.thiagogonzaga.thrillhousebot.review.ReviewDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator;
import dev.thiagogonzaga.thrillhousebot.review.ReviewSkipEmitter;
import dev.thiagogonzaga.thrillhousebot.review.ReviewSkipReason;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.CheckSuite;
import io.quarkiverse.githubapp.event.IssueComment;
import io.quarkiverse.githubapp.event.Ping;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkiverse.githubapp.event.PullRequestReviewComment;
import io.quarkiverse.githubapp.event.RawEvent;
import io.quarkiverse.githubapp.event.Repository;
import io.quarkiverse.githubapp.event.Status;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The webhook entry: the Quarkiverse GitHub App extension verifies the {@code X-Hub-Signature-256}
 * over the raw body, parses the delivery and routes it to the observers below on the same {@code
 * POST /api/webhook} path. Business routing is unchanged: dedupe, pause state, trigger filters,
 * per-PR dispatch serialization and the comment commands keep their exact callers and semantics.
 *
 * <p>Each observer method receives the raw {@link GitHubEvent}: no payload is bound and no
 * installation client is created on the ACK path, so a delivery costs no GitHub call before the
 * routing decides otherwise.
 */
public class WebhookEvents {

  private static final Logger log = LoggerFactory.getLogger(WebhookEvents.class);

  private static final String ACTION_READY_FOR_REVIEW = "ready_for_review";
  private static final String ACTION_SYNCHRONIZE = "synchronize";

  /** pull_request actions that trigger an automatic review. */
  private static final Set<String> AUTO_REVIEW_ACTIONS =
      Set.of("opened", "reopened", ACTION_SYNCHRONIZE, ACTION_READY_FOR_REVIEW);

  private static final String EVENT_PULL_REQUEST = "pull_request";

  private final ThrillhouseConfig config;
  private final TriggerDetector triggerDetector;
  private final ReviewTriggerFilter triggerFilter;
  private final PullRequestLabelGate labelGate;
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
  private final CiHoldRegistry ciHoldRegistry;
  private final AllowedAccounts allowedAccounts;

  private final BeanManager beanManager;

  private final ObjectMapper mapper;

  @Inject
  public WebhookEvents(
      ThrillhouseConfig config,
      TriggerDetector triggerDetector,
      ReviewTriggerFilter triggerFilter,
      PullRequestLabelGate labelGate,
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
      CiHoldRegistry ciHoldRegistry,
      AllowedAccounts allowedAccounts,
      BeanManager beanManager,
      ObjectMapper mapper) {
    this.config = config;
    this.triggerDetector = triggerDetector;
    this.triggerFilter = triggerFilter;
    this.labelGate = labelGate;
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
    this.ciHoldRegistry = ciHoldRegistry;
    this.allowedAccounts = allowedAccounts;
    this.beanManager = beanManager;
    this.mapper = mapper;
  }

  void onPullRequest(
      @PullRequest.Opened @PullRequest.Reopened @PullRequest.Synchronize @PullRequest.ReadyForReview
          GitHubEvent event) {
    routeEvent("pull_request", event);
  }

  void onIssueComment(@IssueComment.Created GitHubEvent event) {
    routeEvent("issue_comment", event);
  }

  void onReviewComment(@PullRequestReviewComment.Created GitHubEvent event) {
    routeEvent("pull_request_review_comment", event);
  }

  void onCheckSuiteCompleted(@CheckSuite.Completed GitHubEvent event) {
    routeEvent("check_suite", event);
  }

  void onStatus(@Status GitHubEvent event) {
    routeEvent("status", event);
  }

  void onPing(@Ping GitHubEvent event) {
    log.info("Webhook ping received");
  }

  void onInstallationEvent(@RawEvent(event = "installation") GitHubEvent event) {
    routeEvent("installation", event);
  }

  void onInstallationRepositoriesEvent(
      @RawEvent(event = "installation_repositories") GitHubEvent event) {
    routeEvent("installation_repositories", event);
  }

  void onRepositoryEvent(@Repository GitHubEvent event) {
    routeEvent("repository", event);
  }

  /**
   * Routes one delivery the same way the controller always did: parse, dedupe against the delivery
   * id, then business handling. Visible for tests.
   */
  void routeEvent(String eventType, GitHubEvent event) {
    WebhookPayload payload = parsePayload(event);
    if (payload == null) {
      return;
    }

    log.info(
        "Received {} event (action: {}, delivery: {})",
        eventType,
        payload.action(),
        event.getDeliveryId());

    // A public App can be installed by anyone; serve only the configured accounts (#1235).
    var account = accountOf(payload);
    if (account != null && !allowedAccounts.allows(account)) {
      log.info(
          "Ignoring {} event from {}: not an allowed account (delivery: {})",
          eventType,
          account,
          event.getDeliveryId());
      return;
    }

    // GitHub redeliveries (manual or automatic retry) reuse the same delivery id.
    if (deduplicator.isDuplicate(event.getDeliveryId())) {
      log.info("Ignoring redelivered {} event (delivery: {})", eventType, event.getDeliveryId());
      recordDuplicateAutoReviewSkip(eventType, payload, event.getDeliveryId());
      return;
    }

    try {
      boolean handled = handle(eventType, payload);
      if (!handled) {
        deduplicator.forget(event.getDeliveryId());
      }
    } catch (RuntimeException e) {
      deduplicator.forget(event.getDeliveryId());
      log.error("Error processing webhook event", e);
    }
  }

  private boolean handle(String eventType, WebhookPayload payload) {
    return switch (eventType) {
      case "pull_request" -> handlePullRequest(payload);
      case "issue_comment" -> handleIssueComment(payload);
      case "pull_request_review_comment" -> handleReviewComment(payload);
      case "check_suite" -> handleCheckSuite(payload);
      case "status" -> handleStatus(payload);
      case "installation" -> {
        handleInstallation(payload);
        yield true;
      }
      case "installation_repositories" -> {
        handleInstallationRepositories(payload);
        yield true;
      }
      case "repository" -> {
        handleRepository(payload);
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
  }

  private WebhookPayload parsePayload(GitHubEvent event) {
    try {
      return mapper.readValue(event.getParsedPayload().encode(), WebhookPayload.class);
    } catch (IOException e) {
      log.error("Failed to parse webhook payload", e);
      return null;
    }
  }

  /**
   * Records a structured skip when a redelivered webhook would have produced an automatic review.
   * Redeliveries of non-review events (comments, pings, …) are ignored silently as before.
   */
  private void recordDuplicateAutoReviewSkip(
      String eventType, WebhookPayload payload, String deliveryId) {
    if (!EVENT_PULL_REQUEST.equals(eventType)
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
        installationOf(payload),
        repo.owner().login(),
        repo.name(),
        pr.number(),
        "redelivered webhook (delivery: " + deliveryId + ")");
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
      case "opened", "reopened", ACTION_SYNCHRONIZE, ACTION_READY_FOR_REVIEW -> {
        var pr = payload.pullRequest();
        var repo = payload.repository();
        if (pr == null || unscoped(payload)) {
          log.debug("Ignoring pull_request with missing pull_request, repository, or installation");
          yield true;
        }
        if (pr.head() == null || pr.base() == null) {
          log.debug("Ignoring pull_request #{} with missing head or base", pr.number());
          yield true;
        }

        if (prPauseService.isPaused(
            scope(payload), repo.owner().login(), repo.name(), pr.number())) {
          skipEmitter.recordSkip(
              ReviewSkipReason.PAUSED,
              installationOf(payload),
              repo.owner().login(),
              repo.name(),
              pr.number(),
              "PR is paused via /pause");
          yield true;
        }

        var skipReason = triggerFilter.skipReason(pr, () -> gatedLabels(payload));
        if (skipReason.isPresent()) {
          skipEmitter.recordSkip(
              skipReason.get().reason(),
              installationOf(payload),
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
              installationOf(payload),
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
                    repo.id(),
                    false,
                    pr.base().ref()));
        if (!queued) {
          skipEmitter.recordSkip(
              ReviewSkipReason.DISPATCH_REJECTED,
              installationOf(payload),
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

  private List<String> gatedLabels(WebhookPayload payload) {
    var pr = payload.pullRequest();
    var payloadLabels = pr.labels().stream().map(WebhookPayload.Label::name).toList();
    if (ACTION_SYNCHRONIZE.equals(payload.action())) {
      return payloadLabels;
    }
    var repo = payload.repository();
    var current =
        labelGate.currentLabels(
            payload.installation().id(), repo.owner().login(), repo.name(), pr.number());
    if (current.isEmpty()) {
      log.warn(
          "Could not read the current labels on PR #{} in {} — gating on the {} payload's labels",
          pr.number(),
          repo.fullName(),
          payload.action());
      return payloadLabels;
    }
    return current.get();
  }

  private boolean handleIssueComment(WebhookPayload payload) {
    if (!"created".equals(payload.action())) {
      log.debug("Ignoring issue_comment action: {}", payload.action());
      return true;
    }

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

  private boolean handleCommentCommand(WebhookPayload payload, CommentCommand command) {
    var repo = payload.repository();
    if (unscoped(payload)) {
      log.debug("Ignoring {} command with missing repository or installation", command);
      return true;
    }
    ackReactionService.addEyes(
        payload.installation().id(),
        repo.owner().login(),
        repo.name(),
        payload.comment().id(),
        AckReactionService.CommentKind.ISSUE);
    var ctx =
        new CommentCommandService.CommandContext(
            command,
            repo.owner().login(),
            repo.name(),
            payload.issue().number(),
            repo.defaultBranch(),
            payload.installation().id(),
            repo.id(),
            payload.comment().user().login(),
            payload.comment().authorAssociation(),
            payload.comment().id(),
            payload.comment().body());

    if (command == CommentCommand.REVIEW) {
      return handleReviewCommand(ctx);
    }
    commentCommandService.handle(ctx);
    return true;
  }

  private boolean maybeDispatchConversationalMention(WebhookPayload payload) {
    if (!config.review().conversationalRepliesEnabled()
        || !triggerDetector.containsBotMention(payload.comment().body())) {
      return true;
    }
    var repo = payload.repository();
    if (unscoped(payload)) {
      log.debug("Ignoring bot mention with missing repository or installation");
      return true;
    }
    if (prPauseService.isPaused(
        scope(payload), repo.owner().login(), repo.name(), payload.issue().number())) {
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
    if (pr == null || unscoped(payload)) {
      log.debug("Ignoring review comment with missing pull_request, repository, or installation");
      return true;
    }

    boolean isReply = comment.inReplyToId() != null;
    Long rootCommentId = isReply ? comment.inReplyToId() : comment.id();

    if (isReply) {
      findingFeedbackCapture.scheduleCaptureOnReviewReply(
          scope(payload),
          repo.owner().login(),
          repo.name(),
          pr.number(),
          rootCommentId,
          new FindingFeedbackCaptureService.ReviewReply(
              comment.user().login(), comment.authorAssociation(), comment.body()));
    }

    if (!config.review().conversationalRepliesEnabled()) {
      return true;
    }

    if (!triggerDetector.containsBotMention(comment.body())) {
      log.debug("Ignoring review comment that does not mention the bot");
      return true;
    }

    if (prPauseService.isPaused(scope(payload), repo.owner().login(), repo.name(), pr.number())) {
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

  private boolean handleCheckSuite(WebhookPayload payload) {
    if (!"completed".equals(payload.action())) {
      log.debug("Ignoring check_suite action: {}", payload.action());
      return true;
    }
    var suite = payload.checkSuite();
    if (suite == null) {
      log.debug("Ignoring check_suite with missing check_suite");
      return true;
    }
    if (suite.app() != null && isOwnApp(suite.app())) {
      log.debug("Ignoring the bot's own check suite");
      return true;
    }
    return recheckHeldVerdicts(payload, suite.headSha(), "check suite");
  }

  private boolean handleStatus(WebhookPayload payload) {
    if (payload.state() == null || "pending".equalsIgnoreCase(payload.state())) {
      log.debug("Ignoring status with state: {}", payload.state());
      return true;
    }
    return recheckHeldVerdicts(payload, payload.sha(), "status " + payload.context());
  }

  private boolean isOwnApp(WebhookPayload.App app) {
    var appId = config.github().appId();
    return appId != null && appId.strip().equals(Long.toString(app.id()));
  }

  private boolean recheckHeldVerdicts(WebhookPayload payload, String headSha, String source) {
    var repo = payload.repository();
    if (headSha == null || headSha.isBlank() || unscoped(payload) || repo.owner() == null) {
      log.debug("Ignoring {} event with missing head sha, repository, or installation", source);
      return true;
    }
    var owner = repo.owner().login();
    var prNumbers = ciHoldRegistry.pullRequestsAt(owner, repo.name(), headSha);
    if (prNumbers.isEmpty()) {
      log.debug(
          "No review held on {} in {} — ignoring the {} completion",
          headSha,
          repo.fullName(),
          source);
      return true;
    }
    var queued = true;
    for (var prNumber : prNumbers) {
      log.info(
          "CI reported on {} for PR #{} in {} ({}) — re-evaluating the held verdict",
          headSha,
          prNumber,
          repo.fullName(),
          source);
      var task =
          new CiHoldRevisit.Recheck(
              owner, repo.name(), prNumber, headSha, payload.installation().id(), repo.id());
      if (!reviewDispatcher.dispatchCiRecheck(task)) {
        queued = false;
      }
    }
    return queued;
  }

  private boolean handleReviewCommand(CommentCommandService.CommandContext ctx) {
    if (prPauseService.isPaused(ctx.scope(), ctx.owner(), ctx.repo(), ctx.prNumber())) {
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
            ctx.repositoryId(),
            true));
  }

  private void handleInstallation(WebhookPayload payload) {
    var installation = payload.installation();
    if (installation == null || installation.id() <= 0 || payload.action() == null) {
      log.debug("Ignoring installation event with no installation or action");
      return;
    }
    var kind =
        switch (payload.action()) {
          case "deleted", "suspend" -> InstallationChange.Kind.GONE;
          case "unsuspend" -> InstallationChange.Kind.BACK;
          case "new_permissions_accepted" -> InstallationChange.Kind.PERMISSIONS;
          default -> null;
        };
    log.info(
        "App installation event (action: {}), installation id: {}",
        payload.action(),
        installation.id());
    if (kind != null) {
      beanManager.getEvent().fire(new InstallationChange(kind, installation.id(), List.of()));
    }
  }

  private void handleInstallationRepositories(WebhookPayload payload) {
    var installation = payload.installation();
    if (installation == null || installation.id() <= 0) {
      log.debug("Ignoring installation_repositories event with no installation");
      return;
    }
    fireRepositories(
        InstallationChange.Kind.REPOSITORIES_REMOVED,
        installation.id(),
        payload.repositoriesRemoved());
    fireRepositories(
        InstallationChange.Kind.REPOSITORIES_ADDED, installation.id(), payload.repositoriesAdded());
  }

  private void fireRepositories(
      InstallationChange.Kind kind,
      long installationId,
      List<WebhookPayload.RepositoryRef> repositories) {
    var changed =
        repositories.stream()
            .filter(r -> r.id() > 0)
            .map(r -> new InstallationChange.Repository(r.id(), r.fullName()))
            .toList();
    if (!changed.isEmpty()) {
      log.info("Installation {}: {} {} repository(ies)", installationId, kind, changed.size());
      beanManager.getEvent().fire(new InstallationChange(kind, installationId, changed));
    }
  }

  private void handleRepository(WebhookPayload payload) {
    var repo = payload.repository();
    if (!"renamed".equals(payload.action()) && !"transferred".equals(payload.action())) {
      return;
    }
    if (unscoped(payload) || repo.fullName() == null) {
      log.debug(
          "Ignoring repository {} event with no repository or installation", payload.action());
      return;
    }
    log.info("Repository {} {} to {}", repo.id(), payload.action(), repo.fullName());
    beanManager
        .getEvent()
        .fire(
            new InstallationChange(
                InstallationChange.Kind.REPOSITORY_RENAMED,
                payload.installation().id(),
                List.of(new InstallationChange.Repository(repo.id(), repo.fullName()))));
  }

  private static String accountOf(WebhookPayload payload) {
    var repo = payload.repository();
    if (repo != null && repo.owner() != null && repo.owner().login() != null) {
      return repo.owner().login();
    }
    var installation = payload.installation();
    return installation != null && installation.account() != null
        ? installation.account().login()
        : null;
  }

  private static long installationOf(WebhookPayload payload) {
    return payload.installation() == null
        ? ReviewSkipEmitter.UNKNOWN_INSTALLATION
        : payload.installation().id();
  }

  private static boolean unscoped(WebhookPayload payload) {
    return payload.repository() == null
        || payload.repository().id() <= 0
        || payload.installation() == null
        || payload.installation().id() <= 0;
  }

  private static RepoScope scope(WebhookPayload payload) {
    return new RepoScope(payload.installation().id(), payload.repository().id());
  }
}
