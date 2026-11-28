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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.AllowedAccounts;
import dev.thiagogonzaga.thrillhousebot.github.InstallationChange;
import dev.thiagogonzaga.thrillhousebot.github.PullRequestClosure;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
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
import io.quarkiverse.githubapp.runtime.SimpleGitHubEvent;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class WebhookEventsTest {

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.GitHubConfig githubConfig;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  @Mock private TriggerDetector triggerDetector;

  @Mock private ReviewTriggerFilter triggerFilter;

  @Mock private PullRequestLabelGate labelGate;

  @Mock private AutoReviewRateLimiter autoReviewRateLimiter;

  @Mock private ReviewDispatcher reviewDispatcher;

  @Mock private MaintainerReplyDispatcher replyDispatcher;

  @Mock private WebhookDeduplicator deduplicator;

  @Mock private ManualReviewAuthorizer manualReviewAuthorizer;

  @Mock private CommentCommandService commentCommandService;

  @Mock private PrPauseService prPauseService;

  @Mock private AckReactionService ackReactionService;

  private final HelpReplyLimiter helpReplyLimiter = new HelpReplyLimiter();

  @Mock private ReviewSkipEmitter skipEmitter;

  @Mock private FindingFeedbackCaptureService findingFeedbackCapture;

  @Mock private CiHoldRegistry ciHoldRegistry;

  @Mock private jakarta.enterprise.inject.spi.BeanManager beanManager;
  @Mock private jakarta.enterprise.event.Event<Object> installationEvents;

  private final ObjectMapper mapper = new ObjectMapper();

  /** A non-null delivery id; the mocked deduplicator reports it unseen unless a test overrides. */
  private static final String DELIVERY = "00000000-0000-0000-0000-000000000001";

  private WebhookEvents events;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.webhookSecret()).thenReturn("test-secret");
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.conversationalRepliesEnabled()).thenReturn(true);
    doReturn(installationEvents).when(beanManager).getEvent();
    when(reviewDispatcher.holdForRateWindow(any()))
        .thenReturn(ReviewDispatcher.RateWindowHold.NOT_HELD);
    events = newEvents(triggerFilter);
  }

  private AllowedAccounts allowedAccounts = new AllowedAccounts(List.of());

  private void deliver(String event, String json) {
    deliver(event, event(event, DELIVERY, json.getBytes(StandardCharsets.UTF_8)));
  }

  /** Drives the observer entry points; assertions below still inspect their business outcomes. */
  private void deliver(String eventType, GitHubEvent event) {
    switch (eventType) {
      case "pull_request" -> events.onPullRequest(event);
      case "issue_comment" -> events.onIssueComment(event);
      case "pull_request_review_comment" -> events.onReviewComment(event);
      case "check_suite" -> events.onCheckSuiteCompleted(event);
      case "status" -> events.onStatus(event);
      case "installation" -> events.onInstallationEvent(event);
      case "installation_repositories" -> events.onInstallationRepositoriesEvent(event);
      case "repository" -> events.onRepositoryEvent(event);
      case "ping" -> events.onPing(event);
      default -> events.routeEvent(eventType, event);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"action\":\"opened\",\"pull_request\":{\"number\":\"not-a-number\"}}",
        "{\"action\":\"opened\",\"pull_request\":[]}"
      })
  void invalidBoundPayloadDoesNotConsumeDeliveryAndAValidRetryStillDispatches(String json) {
    deliver("pull_request", json);

    verifyNoInteractions(deduplicator, reviewDispatcher, replyDispatcher, installationEvents);

    deliver("pull_request", buildPullRequestPayload("opened", 42, "owner/repo", "sha", "main"));

    verify(deduplicator).isDuplicate(DELIVERY);
    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                42,
                "sha",
                "Test PR title",
                "PR body",
                "basesha456",
                "main",
                12345L,
                TestScopes.REPOSITORY_ID,
                false,
                "main"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "{\"id\":1,\"body\":\"/review\"}"})
  void issueCommentWithoutACommentOrAuthorDoesNotStartBusinessWork(String comment) {
    deliver(
        "issue_comment",
        "{\"action\":\"created\",\"issue\":{\"number\":42,\"pull_request\":{}},\"comment\":"
            + comment
            + "}");

    verify(deduplicator).isDuplicate(DELIVERY);
    verifyNoMoreInteractions(deduplicator);
    verifyNoInteractions(
        triggerDetector,
        commentCommandService,
        reviewDispatcher,
        replyDispatcher,
        installationEvents);
  }

  @Test
  void pingAcknowledgesTheDeliveryWithoutBindingPayloadOrStartingBusinessWork() {
    var captured = new ArrayList<String>();
    var logger = Logger.getLogger(WebhookEvents.class.getName());
    var handler =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            captured.add(entry.getMessage());
          }

          @Override
          public void flush() {
            // Records are captured immediately; nothing is buffered.
          }

          @Override
          public void close() {
            // This in-memory handler owns no external resources.
          }
        };
    var ping = mock(GitHubEvent.class);
    logger.addHandler(handler);
    try {
      events.onPing(ping);
    } finally {
      logger.removeHandler(handler);
    }

    assertTrue(captured.stream().anyMatch(m -> m.startsWith("Webhook ping received")));
    verify(ping).getDeliveryId();
    verifyNoMoreInteractions(ping);
    verifyNoInteractions(deduplicator, reviewDispatcher, replyDispatcher, installationEvents);
  }

  private InstallationChange firedChange() {
    var captor = org.mockito.ArgumentCaptor.forClass(InstallationChange.class);
    verify(installationEvents).fire(captor.capture());
    return captor.getValue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "deleted, GONE, true",
    "suspend, GONE, false",
    "unsuspend, BACK, false",
    "new_permissions_accepted, PERMISSIONS, false"
  })
  void installationEventsAreAnnouncedToTheObservers(
      String action, InstallationChange.Kind kind, boolean uninstalled) {
    deliver(
        "installation",
        "{\"action\":\""
            + action
            + "\",\"installation\":{\"id\":5,\"account\":{\"login\":\"owner\"}}}");

    // Only an uninstall deletes the stored findings (#1319); a suspended App comes back to them.
    assertEquals(new InstallationChange(kind, 5L, List.of(), uninstalled), firedChange());
  }

  @Test
  void otherOrIncompleteInstallationEventsAnnounceNothing() {
    deliver("installation", "{\"action\":\"created\",\"installation\":{\"id\":5}}");
    deliver("installation", "{\"action\":\"deleted\"}");
    deliver("installation", "{\"action\":\"deleted\",\"installation\":{\"id\":0}}");
    deliver("installation", "{\"installation\":{\"id\":5}}");

    verifyNoInteractions(installationEvents);
  }

  @Test
  void removedAndAddedRepositoriesAreAnnounced() {
    deliver(
        "installation_repositories",
        "{\"action\":\"removed\",\"installation\":{\"id\":5},"
            + "\"repositories_removed\":[{\"id\":70,\"full_name\":\"owner/a\"},{\"id\":0},null],"
            + "\"repositories_added\":[{\"id\":71,\"full_name\":\"owner/b\"}]}");

    var captor = org.mockito.ArgumentCaptor.forClass(InstallationChange.class);
    verify(installationEvents, times(2)).fire(captor.capture());
    assertEquals(
        List.of(
            new InstallationChange(
                InstallationChange.Kind.REPOSITORIES_REMOVED,
                5L,
                List.of(new InstallationChange.Repository(70L, "owner/a"))),
            new InstallationChange(
                InstallationChange.Kind.REPOSITORIES_ADDED,
                5L,
                List.of(new InstallationChange.Repository(71L, "owner/b")))),
        captor.getAllValues());
  }

  @Test
  void repositoryListsWithNothingUsableAnnounceNothing() {
    deliver(
        "installation_repositories",
        "{\"action\":\"removed\",\"installation\":{\"id\":5},\"repositories_removed\":[{\"id\":0}]}");
    deliver("installation_repositories", "{\"action\":\"added\"}");
    deliver(
        "installation_repositories",
        "{\"action\":\"added\",\"installation\":{\"id\":0},\"repositories_added\":[{\"id\":71}]}");

    verifyNoInteractions(installationEvents);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"renamed", "transferred"})
  void aRenamedOrTransferredRepositoryIsAnnouncedWithItsNewName(String action) {
    deliver(
        "repository",
        "{\"action\":\""
            + action
            + "\",\"repository\":{\"id\":7,\"full_name\":\"owner/new\","
            + "\"name\":\"new\",\"owner\":{\"login\":\"owner\"}},\"installation\":{\"id\":5}}");

    assertEquals(
        new InstallationChange(
            InstallationChange.Kind.REPOSITORY_RENAMED,
            5L,
            List.of(new InstallationChange.Repository(7L, "owner/new"))),
        firedChange());
  }

  @Test
  void otherOrIncompleteRepositoryEventsAnnounceNothing() {
    deliver(
        "repository",
        "{\"action\":\"edited\",\"repository\":{\"id\":7,\"full_name\":\"owner/new\"},\"installation\":{\"id\":5}}");
    deliver("repository", "{\"action\":\"renamed\",\"installation\":{\"id\":5}}");
    deliver(
        "repository",
        "{\"action\":\"renamed\",\"repository\":{\"id\":7,\"name\":\"new\"},\"installation\":{\"id\":5}}");

    verifyNoInteractions(installationEvents);
  }

  @Test
  void eventsFromAnotherAccountsInstallationRunNothing() {
    allowedAccounts = new AllowedAccounts(List.of("someone-else"));
    events = newEvents(triggerFilter);

    for (var event :
        List.of(
            new String[] {
              "pull_request", buildPullRequestPayload("opened", 1, "owner/repo", "sha", "main")
            },
            new String[] {
              "check_suite",
              "{\"action\":\"completed\",\"check_suite\":{\"head_sha\":\"abc\"},"
                  + "\"repository\":{\"id\":7,\"name\":\"b\",\"owner\":{\"id\":1}},"
                  + "\"installation\":{\"id\":5,\"account\":{\"login\":\"stranger\"}}}"
            },
            new String[] {
              "check_suite",
              "{\"action\":\"completed\",\"check_suite\":{\"head_sha\":\"abc\"},"
                  + "\"repository\":{\"id\":7,\"name\":\"b\"},"
                  + "\"installation\":{\"id\":5,\"account\":{\"login\":\"stranger\"}}}"
            },
            new String[] {
              "installation",
              "{\"action\":\"created\",\"installation\":{\"id\":5,\"account\":{\"login\":\"stranger\"}}}"
            })) {
      deliver(event[0], event(event[0], DELIVERY, event[1].getBytes(StandardCharsets.UTF_8)));
    }
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verifyNoInteractions(deduplicator, prPauseService);
  }

  @Test
  void anAllowedAccountIsServedIgnoringCase() {
    allowedAccounts = new AllowedAccounts(List.of(" OWNER "));
    events = newEvents(triggerFilter);
    when(reviewDispatcher.dispatch(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(true);

    deliver(
        "pull_request",
        event(
            "pull_request",
            DELIVERY,
            buildPullRequestPayload("opened", 1, "owner/repo", "sha", "main")
                .getBytes(StandardCharsets.UTF_8)));

    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void anEventNamingNoAccountIsNotFiltered() {
    allowedAccounts = new AllowedAccounts(List.of("owner"));
    events = newEvents(triggerFilter);

    events.routeEvent("push", event("push", DELIVERY, "{}".getBytes(StandardCharsets.UTF_8)));

    verify(deduplicator).isDuplicate(DELIVERY);
  }

  private WebhookEvents newEvents(ReviewTriggerFilter filter) {
    return newEvents(filter, autoReviewRateLimiter);
  }

  /** Makes the mocked dispatcher answer for the rate window from {@code limiter}. */
  private void readRateWindowFrom(AutoReviewRateLimiter limiter) {
    when(reviewDispatcher.holdForRateWindow(any()))
        .thenAnswer(
            invocation -> {
              ReviewOrchestrator.ReviewRequest request = invocation.getArgument(0);
              var hold =
                  limiter.hold(
                      request.owner(), request.repo(), request.prNumber(), request.commitSha());
              return hold.throttled()
                  ? ReviewDispatcher.RateWindowHold.NOT_KEPT
                  : ReviewDispatcher.RateWindowHold.NOT_HELD;
            });
  }

  private WebhookEvents newEvents(ReviewTriggerFilter filter, AutoReviewRateLimiter limiter) {
    return new WebhookEvents(
        config,
        triggerDetector,
        filter,
        labelGate,
        limiter,
        reviewDispatcher,
        replyDispatcher,
        deduplicator,
        manualReviewAuthorizer,
        commentCommandService,
        prPauseService,
        ackReactionService,
        helpReplyLimiter,
        skipEmitter,
        findingFeedbackCapture,
        ciHoldRegistry,
        allowedAccounts,
        beanManager,
        mapper);
  }

  @Test
  void shouldDispatchAValidPullRequestPayload() {

    var body =
        buildPullRequestPayload("opened", 42, "owner/repo", "abc123", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldDropRedeliveredWebhookWithoutDispatching() {
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 7, "owner/repo", "sha7", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "redelivered-id", body));

    // A redelivery must not start a second review.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldProcessFirstDeliveryAndPassIdToDeduplicator() {

    var body =
        buildPullRequestPayload("opened", 8, "owner/repo", "sha8", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "delivery-xyz", body));

    verify(deduplicator).isDuplicate("delivery-xyz");
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  private GitHubEvent event(String eventType, String deliveryId, byte[] body) {
    return event(eventType, deliveryId, new String(body, StandardCharsets.UTF_8));
  }

  private GitHubEvent event(String eventType, String deliveryId, String json) {
    var parsed = new JsonObject(json);
    var installation = parsed.getJsonObject("installation");
    var repository = parsed.getJsonObject("repository");
    return new SimpleGitHubEvent(
        installation != null ? installation.getLong("id") : null,
        null,
        deliveryId,
        repository != null ? repository.getString("full_name") : null,
        eventType,
        parsed.getString("action"),
        json,
        parsed,
        false);
  }

  @Test
  void shouldRouteOpenedPullRequestToOrchestrator() {

    var body =
        buildPullRequestPayload("opened", 99, "owner/myrepo", "headsha123", "default")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "myrepo",
                99,
                "headsha123",
                "Test PR title",
                "PR body",
                "basesha456",
                "default",
                12345L,
                TestScopes.REPOSITORY_ID,
                false,
                "default"));
  }

  @Test
  void shouldIgnorePullRequestWithMissingHead() {

    // A pull_request event whose payload omits head (or base) must not NPE — it is ignored.
    var body =
        ("{"
                + "\"action\":\"opened\","
                + "\"pull_request\":{\"number\":42,\"title\":\"T\","
                + "\"base\":{\"sha\":\"b\",\"ref\":\"main\"},\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"repository\":{\"id\":7,\"full_name\":\"owner/repo\",\"name\":\"repo\","
                + "\"default_branch\":\"main\",\"owner\":{\"login\":\"owner\",\"id\":2}},"
                + "\"installation\":{\"id\":12345}}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnorePullRequestWithMissingBase() {

    // head present, base omitted — the other side of the head/base guard.
    var body =
        ("{"
                + "\"action\":\"opened\","
                + "\"pull_request\":{\"number\":42,\"title\":\"T\","
                + "\"head\":{\"sha\":\"h\",\"ref\":\"feature\"},\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"repository\":{\"id\":7,\"full_name\":\"owner/repo\",\"name\":\"repo\","
                + "\"default_branch\":\"main\",\"owner\":{\"login\":\"owner\",\"id\":2}},"
                + "\"installation\":{\"id\":12345}}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldRouteSynchronizePullRequestToOrchestrator() {

    var body =
        buildPullRequestPayload("synchronize", 55, "org/app", "newsha", "develop")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "org",
                "app",
                55,
                "newsha",
                "Test PR title",
                "PR body",
                "basesha456",
                "develop",
                12345L,
                TestScopes.REPOSITORY_ID,
                false,
                "develop"));
  }

  @Test
  void shouldRouteReopenedPullRequestToOrchestrator() {

    var body =
        buildPullRequestPayload("reopened", 10, "a/b", "sha10", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "a",
                "b",
                10,
                "sha10",
                "Test PR title",
                "PR body",
                "basesha456",
                "main",
                12345L,
                TestScopes.REPOSITORY_ID,
                false,
                "main"));
  }

  @Test
  void shouldRouteReadyForReviewPullRequestToOrchestrator() {

    // A draft marked "Ready for review" fires ready_for_review, not synchronize, so it must
    // dispatch on its own or the PR stays unreviewed until the next push.
    var body =
        buildPullRequestPayload("ready_for_review", 21, "org/svc", "sha21", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "org",
                "svc",
                21,
                "sha21",
                "Test PR title",
                "PR body",
                "basesha456",
                "main",
                12345L,
                TestScopes.REPOSITORY_ID,
                false,
                "main"));
  }

  @Test
  void shouldDefaultMissingPrBodyToEmptyDescription() {

    var body =
        buildPullRequestPayload("opened", 12, "a/b", "sha12", "main", false)
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "a",
                "b",
                12,
                "sha12",
                "Test PR title",
                "",
                "basesha456",
                "main",
                12345L,
                TestScopes.REPOSITORY_ID,
                false,
                "main"));
  }

  @Test
  void shouldSkipAutomaticReviewWhenPrPaused() {
    when(prPauseService.isPaused(any(), eq("owner"), eq("myrepo"), eq(99))).thenReturn(true);

    var body =
        buildPullRequestPayload("synchronize", 99, "owner/myrepo", "headsha123", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    // A paused PR must not auto-review on new commits.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreClosedPullRequest() {

    var body =
        buildPullRequestPayload("closed", 1, "x/y", "sha", "main").getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    // Orchestrator should NOT be called for "closed" action
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnorePullRequestWithNullAction() {

    var body =
        "{"
            + "\"action\":null,"
            + "\"pull_request\":{\"number\":1,\"title\":\"t\",\"head\":{\"sha\":\"s\"},\"base\":{\"sha\":\"b\"}},"
            + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":{\"id\":1}"
            + "}";

    deliver("pull_request", event("pull_request", DELIVERY, body.getBytes(StandardCharsets.UTF_8)));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreOpenedPullRequestWithMissingFields() {

    var body =
        "{"
            + "\"action\":\"opened\","
            + "\"pull_request\":null,"
            + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":{\"id\":1}"
            + "}";

    deliver("pull_request", event("pull_request", DELIVERY, body.getBytes(StandardCharsets.UTF_8)));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldSkipReviewWhenTriggerFilterRejects() {
    when(triggerFilter.skipReason(any(), any()))
        .thenReturn(
            Optional.of(new ReviewTriggerFilter.Skip(ReviewSkipReason.DRAFT, "PR is a draft")));

    var body =
        buildPullRequestPayload("opened", 21, "owner/repo", "sha21", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "delivery-skip", body));

    // A filtered-out PR must not be reviewed, and the dedup slot stays claimed (it was a
    // deliberate decision, not a rejected dispatch), so a redelivery is ignored too.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(deduplicator, never()).forget(anyString());
  }

  @Test
  void shouldSkipAutomaticReviewWithinRateLimitWindow() {
    when(reviewConfig.autoReviewMinInterval()).thenReturn(Duration.ofHours(1));
    when(reviewDispatcher.holdForRateWindow(any(ReviewOrchestrator.ReviewRequest.class)))
        .thenReturn(ReviewDispatcher.RateWindowHold.NOT_KEPT);

    // A new push (fresh head SHA) within the window is still skipped.
    var body =
        buildPullRequestPayload("synchronize", 55, "owner/repo", "fresh-sha", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "delivery-throttled", body));

    // A throttled PR must not be reviewed, and the dedup slot stays claimed (a deliberate
    // decision, not a rejected dispatch), so a redelivery is ignored too.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(deduplicator, never()).forget(anyString());
  }

  @Test
  void shouldEmitStructuredSkipEventWhenDraftFilterRejects() {
    when(triggerFilter.skipReason(any(), any()))
        .thenReturn(
            Optional.of(new ReviewTriggerFilter.Skip(ReviewSkipReason.DRAFT, "PR is a draft")));

    var body =
        buildPullRequestPayload("opened", 21, "owner/repo", "sha21", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.DRAFT), anyLong(), eq("owner"), eq("repo"), eq(21), anyString());
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldEmitStructuredSkipEventWhenPrPaused() {
    when(prPauseService.isPaused(any(), eq("owner"), eq("myrepo"), eq(99))).thenReturn(true);

    var body =
        buildPullRequestPayload("synchronize", 99, "owner/myrepo", "headsha123", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.PAUSED), anyLong(), eq("owner"), eq("myrepo"), eq(99), anyString());
  }

  @Test
  void shouldEmitStructuredSkipEventWhenRateLimited() {
    when(reviewConfig.autoReviewMinInterval()).thenReturn(Duration.ofHours(1));
    when(reviewDispatcher.holdForRateWindow(any(ReviewOrchestrator.ReviewRequest.class)))
        .thenReturn(ReviewDispatcher.RateWindowHold.NOT_KEPT);

    var body =
        buildPullRequestPayload("synchronize", 55, "owner/repo", "fresh-sha", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.RATE_LIMITED),
            anyLong(),
            eq("owner"),
            eq("repo"),
            eq(55),
            anyString());
  }

  @Test
  void aPushInsideTheRateWindowIsKeptForLaterInsteadOfSkipped() {
    when(reviewDispatcher.holdForRateWindow(any(ReviewOrchestrator.ReviewRequest.class)))
        .thenReturn(ReviewDispatcher.RateWindowHold.KEPT);

    deliverPullRequest("push-in-window", "synchronize", 55, "owner/repo", "fresh-sha");

    // The new head is reviewed when the window closes, so nothing is counted as skipped.
    var kept = ArgumentCaptor.forClass(ReviewOrchestrator.ReviewRequest.class);
    verify(reviewDispatcher).holdForRateWindow(kept.capture());
    assertEquals("fresh-sha", kept.getValue().commitSha());
    assertEquals(55, kept.getValue().prNumber());
    assertFalse(kept.getValue().isManualTrigger());
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(skipEmitter, never()).recordSkip(any(), anyLong(), any(), any(), anyInt(), any());
    verify(deduplicator, never()).forget(anyString());
  }

  @Test
  void aPausedPullRequestDropsTheReviewALimitHeldBack() {
    when(prPauseService.isPaused(any(), eq("owner"), eq("repo"), eq(55))).thenReturn(true);

    deliverPullRequest("push-paused", "synchronize", 55, "owner/repo", "sha55");

    verify(reviewDispatcher).forgetDeferred(any(RepoScope.class), eq(55));
  }

  @Test
  void anEventTheFiltersRejectDropsTheReviewALimitHeldBack() {
    when(triggerFilter.skipReason(any(), any()))
        .thenReturn(
            Optional.of(new ReviewTriggerFilter.Skip(ReviewSkipReason.DRAFT, "PR is a draft")));

    deliverPullRequest("push-draft", "synchronize", 55, "owner/repo", "sha55");

    verify(reviewDispatcher).forgetDeferred(any(RepoScope.class), eq(55));
    verify(reviewDispatcher, never()).holdForRateWindow(any());
  }

  @Test
  void convertingToDraftDropsTheReviewALimitHeldBackWhenDraftsAreSkipped() {
    when(triggerFilter.skipReason(any()))
        .thenReturn(
            Optional.of(new ReviewTriggerFilter.Skip(ReviewSkipReason.DRAFT, "PR is a draft")));

    deliverPullRequest("to-draft", "converted_to_draft", 55, "owner/repo", "sha55");

    verify(reviewDispatcher).dropDeferred(any(RepoScope.class), eq(55));
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(deduplicator, never()).forget(anyString());
    // Nothing waited for a limit, so there is no review to count as skipped.
    verifyNoInteractions(skipEmitter);
  }

  @Test
  void aReviewStillWaitingForALimitThatADraftConversionDropsIsCountedAsSkipped() {
    when(triggerFilter.skipReason(any()))
        .thenReturn(
            Optional.of(new ReviewTriggerFilter.Skip(ReviewSkipReason.DRAFT, "PR is a draft")));
    when(reviewDispatcher.dropDeferred(any(RepoScope.class), eq(55))).thenReturn(true);

    deliverPullRequest("to-draft", "converted_to_draft", 55, "owner/repo", "sha55");

    verify(skipEmitter)
        .recordSkip(
            ReviewSkipReason.DRAFT,
            12345L,
            "owner",
            "repo",
            55,
            "PR is a draft; the review a limit held back for it is dropped");
  }

  @Test
  void convertingToDraftKeepsTheReviewALimitHeldBackWhenDraftsAreReviewed() {
    when(triggerFilter.skipReason(any())).thenReturn(Optional.empty());

    deliverPullRequest("to-draft", "converted_to_draft", 55, "owner/repo", "sha55");

    verify(reviewDispatcher, never()).dropDeferred(any(), anyInt());
    verify(reviewDispatcher, never()).forgetDeferred(any(), anyInt());
  }

  @Test
  void aConvertedToDraftEventWithoutAPullRequestOrScopeIsIgnored() {
    deliver("pull_request", "{\"action\":\"converted_to_draft\"}");
    deliver("pull_request", "{\"action\":\"converted_to_draft\",\"pull_request\":{\"number\":55}}");

    verify(reviewDispatcher, never()).dropDeferred(any(), anyInt());
    verifyNoInteractions(triggerFilter);
  }

  @Test
  void aDuplicateDeliveryWithoutAnInstallationIsCountedUnderNoInstallation() {
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 7, "owner/repo", "sha7", "main")
            .replace("\"installation\":{\"id\":12345}", "\"installation\":null")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "redelivered-id", body));

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.DUPLICATE_DELIVERY),
            eq(ReviewSkipEmitter.UNKNOWN_INSTALLATION),
            eq("owner"),
            eq("repo"),
            eq(7),
            anyString());
  }

  @Test
  void shouldEmitStructuredSkipEventOnDuplicateDelivery() {
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 7, "owner/repo", "sha7", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "redelivered-id", body));

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.DUPLICATE_DELIVERY),
            anyLong(),
            eq("owner"),
            eq("repo"),
            eq(7),
            anyString());
  }

  @Test
  void shouldNotEmitDuplicateSkipEventForNonReviewRedelivery() {
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        buildPullRequestPayload("closed", 7, "owner/repo", "sha7", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "redelivered-id", body));

    verifyNoInteractions(skipEmitter);
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("redeliveriesWithoutReviewIdentity")
  void shouldNotEmitDuplicateSkipEventWithoutReviewIdentity(String eventType, String payload) {
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    deliver(eventType, event(eventType, "redelivered-id", payload));

    verifyNoInteractions(skipEmitter);
  }

  private static Stream<Arguments> redeliveriesWithoutReviewIdentity() {
    return Stream.of(
        arguments("issue_comment", "{\"action\":\"created\"}"),
        arguments("pull_request", "{}"),
        arguments("pull_request", "{\"action\":\"opened\",\"pull_request\":{\"number\":7}}"));
  }

  @Test
  void shouldNotEmitDuplicateSkipEventWhenRedeliveryLacksPullRequest() {
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        ("{\"action\":\"opened\",\"repository\":{\"id\":7,\"full_name\":\"owner/repo\",\"name\":\"repo\","
                + "\"owner\":{\"login\":\"owner\",\"id\":1}}}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "redelivered-id", body));

    verifyNoInteractions(skipEmitter);
  }

  @Test
  void shouldNotEmitDuplicateSkipEventWhenRedeliveryLacksRepositoryOwner() {
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        ("{\"action\":\"opened\",\"pull_request\":{\"number\":7},"
                + "\"repository\":{\"id\":7,\"full_name\":\"owner/repo\",\"name\":\"repo\"}}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "redelivered-id", body));

    verifyNoInteractions(skipEmitter);
  }

  @Test
  void shouldEmitStructuredSkipEventWhenDispatchRejected() {
    when(reviewDispatcher.dispatch(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(false);

    var body =
        buildPullRequestPayload("opened", 8, "owner/repo", "sha8", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "delivery-rejected", body));

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.DISPATCH_REJECTED),
            anyLong(),
            eq("owner"),
            eq("repo"),
            eq(8),
            anyString());
    // A rejected dispatch rolls back the dedup slot so a redelivery can retry.
    verify(deduplicator).forget("delivery-rejected");
  }

  @Test
  void shouldNotEmitSkipEventWhenReviewDispatches() {
    when(reviewDispatcher.dispatch(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 8, "owner/repo", "sha8", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verifyNoInteractions(skipEmitter);
  }

  @Test
  void shouldDispatchAutomaticReviewWhenRateLimitWindowOpen() {
    var body =
        buildPullRequestPayload("synchronize", 56, "owner/repo", "sha56", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher).holdForRateWindow(any(ReviewOrchestrator.ReviewRequest.class));
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void theFirstReadyForReviewOfAHeadIsDispatchedEvenWhenThrottled() {
    when(reviewConfig.autoReviewMinInterval()).thenReturn(Duration.ofHours(1));
    when(autoReviewRateLimiter.clearForReadyHead("org", "svc", 57, "sha57")).thenReturn(true);
    when(reviewDispatcher.holdForRateWindow(any(ReviewOrchestrator.ReviewRequest.class)))
        .thenReturn(ReviewDispatcher.RateWindowHold.NOT_KEPT);

    var body =
        buildPullRequestPayload("ready_for_review", 57, "org/svc", "sha57", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher, never()).holdForRateWindow(any());
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void aReadyForReviewWhoseHeadAlreadyLiftedTheWindowIsThrottled() {
    when(reviewConfig.autoReviewMinInterval()).thenReturn(Duration.ofHours(1));
    when(autoReviewRateLimiter.clearForReadyHead("org", "svc", 57, "sha57")).thenReturn(false);
    when(reviewDispatcher.holdForRateWindow(any(ReviewOrchestrator.ReviewRequest.class)))
        .thenReturn(ReviewDispatcher.RateWindowHold.NOT_KEPT);

    var body =
        buildPullRequestPayload("ready_for_review", 57, "org/svc", "sha57", "main")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.RATE_LIMITED), anyLong(), eq("org"), eq("svc"), eq(57), any());
  }

  @Test
  void convertingToDraftAndBackDoesNotStartAReviewOfTheSameHeadAgain() {
    when(reviewConfig.autoReviewMinInterval()).thenReturn(Duration.ofHours(1));
    var limiter = limiterWithInterval(Duration.ofHours(1));
    events = newEvents(triggerFilter, limiter);
    readRateWindowFrom(limiter);
    // A review of an earlier push just completed, so the window is open.
    limiter.recordCompletion("org", "svc", 57, "reviewed");

    deliverPullRequest("ready-1", "ready_for_review", 57, "org/svc", "sha57");
    // The review that the first ready-for-review started completes.
    limiter.recordCompletion("org", "svc", 57, "sha57");
    deliverPullRequest("ready-2", "ready_for_review", 57, "org/svc", "sha57");
    deliverPullRequest("ready-3", "ready_for_review", 57, "org/svc", "sha57");

    // Only the first ready-for-review of the head ran; the repeats were throttled.
    verify(reviewDispatcher, times(1)).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(skipEmitter, times(2))
        .recordSkip(
            eq(ReviewSkipReason.RATE_LIMITED), anyLong(), eq("org"), eq("svc"), eq(57), any());

    // A new head marked ready is reviewed inside the window, once.
    deliverPullRequest("ready-4", "ready_for_review", 57, "org/svc", "sha58");
    verify(reviewDispatcher, times(2)).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void anUnchangedHeadReopenedInsideTheDefaultWindowIsNotReviewedAgain()
      throws NoSuchMethodException {
    // The interval an operator gets without setting anything.
    var shipped =
        io.quarkus.runtime.configuration.DurationConverter.parseDuration(
            ThrillhouseConfig.ReviewConfig.class
                .getMethod("autoReviewMinInterval")
                .getAnnotation(io.smallrye.config.WithDefault.class)
                .value());
    when(reviewConfig.autoReviewMinInterval()).thenReturn(shipped);
    var limiter = limiterWithInterval(shipped);
    events = newEvents(triggerFilter, limiter);
    readRateWindowFrom(limiter);
    limiter.recordCompletion("owner", "repo", 61, "sha61");

    deliverPullRequest("reopen-1", "reopened", 61, "owner/repo", "sha61");
    deliverPullRequest("reopen-2", "reopened", 61, "owner/repo", "sha61");

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(skipEmitter, times(2))
        .recordSkip(
            eq(ReviewSkipReason.RATE_LIMITED), anyLong(), eq("owner"), eq("repo"), eq(61), any());
  }

  @Test
  void withTheIntervalSetToZeroEveryReopenAndReadyForReviewIsReviewed() {
    when(reviewConfig.autoReviewMinInterval()).thenReturn(Duration.ZERO);
    var limiter = limiterWithInterval(Duration.ZERO);
    events = newEvents(triggerFilter, limiter);
    limiter.recordCompletion("owner", "repo", 62, "reviewed");

    deliverPullRequest("off-1", "reopened", 62, "owner/repo", "sha62");
    deliverPullRequest("off-2", "ready_for_review", 62, "owner/repo", "sha62");
    deliverPullRequest("off-3", "ready_for_review", 62, "owner/repo", "sha62");

    verify(reviewDispatcher, times(3)).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(skipEmitter, never())
        .recordSkip(eq(ReviewSkipReason.RATE_LIMITED), anyLong(), any(), any(), anyInt(), any());
  }

  /** A real limiter reading {@code interval} the way the application builds it. */
  private static AutoReviewRateLimiter limiterWithInterval(Duration interval) {
    var limiterConfig = mock(ThrillhouseConfig.class);
    var limiterReview = mock(ThrillhouseConfig.ReviewConfig.class);
    when(limiterConfig.review()).thenReturn(limiterReview);
    when(limiterReview.autoReviewMinInterval()).thenReturn(interval);
    return new AutoReviewRateLimiter(limiterConfig);
  }

  private void deliverPullRequest(
      String deliveryId, String action, int prNumber, String fullName, String headSha) {
    deliver(
        "pull_request",
        event(
            "pull_request",
            deliveryId,
            buildPullRequestPayload(action, prNumber, fullName, headSha, "main")
                .getBytes(StandardCharsets.UTF_8)));
  }

  /** A controller gating on the real filter with {@code dependencies} excluded (#1021). */
  private WebhookEvents excludingDependencies() {
    return newEvents(
        new ReviewTriggerFilter(false, List.of(), List.of("dependencies"), List.of(), List.of()));
  }

  @Test
  void dependabotOpenedPayloadWithoutLabelsIsSkippedOnTheLabelsReadFromTheApi() {
    // Dependabot opens the pull request, then labels it: the opened payload has no labels.
    when(labelGate.currentLabels(12345L, "owner", "repo", 1012))
        .thenReturn(Optional.of(List.of("dependencies", "java")));
    var body =
        buildPullRequestPayload("opened", 1012, "owner/repo", "sha1012", "main")
            .getBytes(StandardCharsets.UTF_8);

    excludingDependencies().routeEvent("pull_request", event("pull_request", DELIVERY, body));

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.EXCLUDED_LABEL),
            anyLong(),
            eq("owner"),
            eq("repo"),
            eq(1012),
            anyString());
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @ParameterizedTest
  @MethodSource("authorGatedActions")
  void anExcludedAuthorIsSkippedBeforeAnyLabelIsRead(String action) {
    var authorGated =
        newEvents(
            new ReviewTriggerFilter(
                false,
                List.of(),
                List.of("dependencies"),
                List.of(),
                List.of(),
                List.of("octocat")));
    var body =
        buildPullRequestPayload(action, 1276, "owner/repo", "sha1276", "main")
            .getBytes(StandardCharsets.UTF_8);

    authorGated.routeEvent("pull_request", event("pull_request", DELIVERY, body));

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.EXCLUDED_AUTHOR),
            anyLong(),
            eq("owner"),
            eq("repo"),
            eq(1276),
            anyString());
    verify(labelGate, never()).currentLabels(anyLong(), anyString(), anyString(), anyInt());
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void aManualReviewStillRunsOnAPullRequestByAnExcludedAuthor() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);
    var authorGated =
        newEvents(
            new ReviewTriggerFilter(
                false, List.of(), List.of(), List.of(), List.of(), List.of("octocat")));
    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    authorGated.routeEvent("issue_comment", event("issue_comment", DELIVERY, body));

    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  static Stream<String> authorGatedActions() {
    return Stream.of("opened", "synchronize", "reopened", "ready_for_review");
  }

  @ParameterizedTest
  @MethodSource("labelReadingActions")
  void reopenedAndReadyForReviewAlsoReadTheLabelsFromTheApi(String action) {
    when(labelGate.currentLabels(12345L, "owner", "repo", 7))
        .thenReturn(Optional.of(List.of("Dependencies")));
    var body =
        buildPullRequestPayload(action, 7, "owner/repo", "sha7", "main")
            .getBytes(StandardCharsets.UTF_8);

    excludingDependencies().routeEvent("pull_request", event("pull_request", DELIVERY, body));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  static Stream<String> labelReadingActions() {
    return Stream.of("reopened", "ready_for_review");
  }

  @Test
  void anUnreadableLabelSetFallsBackToThePayloadLabels() {
    when(labelGate.currentLabels(anyLong(), anyString(), anyString(), anyInt()))
        .thenReturn(Optional.empty());
    var labelled = labelledOpenedPayload(8, "dependencies");
    var unlabelled =
        buildPullRequestPayload("opened", 9, "owner/repo", "sha9", "main")
            .getBytes(StandardCharsets.UTF_8);
    var gated = excludingDependencies();

    gated.routeEvent("pull_request", event("pull_request", "d-8", labelled));
    gated.routeEvent("pull_request", event("pull_request", "d-9", unlabelled));

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.EXCLUDED_LABEL),
            anyLong(),
            eq("owner"),
            eq("repo"),
            eq(8),
            anyString());
    var dispatched = ArgumentCaptor.forClass(ReviewOrchestrator.ReviewRequest.class);
    verify(reviewDispatcher).dispatch(dispatched.capture());
    assertEquals(9, dispatched.getValue().prNumber());
  }

  @Test
  void synchronizeGatesOnThePayloadLabelsWithoutAnApiRead() {
    var body =
        buildPullRequestPayload("synchronize", 10, "owner/repo", "sha10", "main")
            .getBytes(StandardCharsets.UTF_8);

    excludingDependencies().routeEvent("pull_request", event("pull_request", DELIVERY, body));

    verifyNoInteractions(labelGate);
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void noLabelGateMeansNoApiRead() {
    var body =
        buildPullRequestPayload("opened", 11, "owner/repo", "sha11", "main")
            .getBytes(StandardCharsets.UTF_8);

    newEvents(new ReviewTriggerFilter(false, List.of(), List.of(), List.of(), List.of()))
        .routeEvent("pull_request", event("pull_request", DELIVERY, body));

    verifyNoInteractions(labelGate);
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  private byte[] labelledOpenedPayload(int prNumber, String label) {
    return buildPullRequestPayload("opened", prNumber, "owner/repo", "sha" + prNumber, "main")
        .replace(
            "\"user\":{\"login\":\"octocat\",\"id\":1}",
            "\"user\":{\"login\":\"octocat\",\"id\":1},\"labels\":[{\"name\":\"" + label + "\"}]")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void shouldPassParsedDraftAndLabelsToTriggerFilter() {

    var body =
        ("{"
                + "\"action\":\"opened\","
                + "\"pull_request\":{"
                + "\"number\":33,\"title\":\"t\",\"head\":{\"sha\":\"s\"},"
                + "\"base\":{\"sha\":\"b\",\"ref\":\"main\"},\"draft\":true,"
                + "\"labels\":[{\"name\":\"wip\"},{\"name\":\"ai-review\"}]"
                + "},"
                + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
                + "\"installation\":{\"id\":1}"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    var captor = ArgumentCaptor.forClass(WebhookPayload.PullRequest.class);
    verify(triggerFilter).skipReason(captor.capture(), any());
    var pr = captor.getValue();
    assertTrue(pr.draft());
    assertEquals(2, pr.labels().size());
    assertEquals("wip", pr.labels().get(0).name());
    // Filter said review (default empty Optional), so dispatch still happens.
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldTriggerManualReviewOnReviewCommand() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                77,
                "",
                "(manual review)",
                "",
                "",
                "main",
                12345L,
                TestScopes.REPOSITORY_ID,
                true));
  }

  @Test
  void shouldTriggerManualReviewEvenWithinRateLimitWindow() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // An explicit /review always runs — the manual path never consults the rate limiter, so
    // even a PR deep inside the throttle window is reviewed on request.
    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "owner",
                "repo",
                77,
                "",
                "(manual review)",
                "",
                "",
                "main",
                12345L,
                TestScopes.REPOSITORY_ID,
                true));
    verifyNoInteractions(autoReviewRateLimiter);
  }

  @Test
  void shouldIgnoreUnauthorizedManualReviewTrigger() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("stranger")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized(
            anyString(), anyString(), anyLong(), anyString(), any()))
        .thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 88, "owner/repo", "stranger", "/review", "NONE")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // A user without write access must not be able to spend the operator's API budget.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void aClosedPullRequestStartsItsStoredFindingsRetentionAndIsNotReviewed() {
    deliver("pull_request", buildPullRequestPayload("closed", 21, "a/b", "sha21", "main"));

    verify(installationEvents)
        .fire(new PullRequestClosure(new RepoScope(12345L, TestScopes.REPOSITORY_ID), 21, true));
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void aReopenedPullRequestKeepsItsStoredFindingsEvenWhenPaused() {
    when(prPauseService.isPaused(any(), eq("a"), eq("b"), eq(22))).thenReturn(true);

    deliver("pull_request", buildPullRequestPayload("reopened", 22, "a/b", "sha22", "main"));

    // #1319: the reopen is recorded before the pause ends the handling.
    verify(installationEvents)
        .fire(new PullRequestClosure(new RepoScope(12345L, TestScopes.REPOSITORY_ID), 22, false));
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void aClosedEventWithoutAPullRequestIsIgnored() {
    deliver(
        "pull_request",
        "{\"action\":\"closed\",\"number\":24,"
            + "\"repository\":{\"id\":7,\"name\":\"b\",\"full_name\":\"a/b\","
            + "\"owner\":{\"login\":\"a\"}},\"installation\":{\"id\":12345}}");

    verifyNoInteractions(installationEvents);
  }

  @Test
  void aClosedEventWithoutAnInstallationIsIgnored() {
    deliver(
        "pull_request", "{\"action\":\"closed\",\"number\":23,\"pull_request\":{\"number\":23}}");

    verifyNoInteractions(installationEvents);
  }

  @Test
  void shouldSkipManualReviewOnPausedPr() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);
    when(prPauseService.isPaused(any(), eq("owner"), eq("repo"), eq(77))).thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // A paused PR does not dispatch the review; a commenter with write access gets the notice.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(commentCommandService).notifyPaused(any(CommentCommandService.CommandContext.class));
  }

  @Test
  void aStrangersReviewOnAPausedPrPostsNothingAndGetsNoReaction() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("stranger")).thenReturn(false);
    when(prPauseService.isPaused(any(), eq("owner"), eq("repo"), eq(77))).thenReturn(true);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "stranger", "NONE"))
        .thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "stranger", "/review", "NONE")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // Anyone can comment on a public pull request, so nothing is written for a commenter the
    // authorization refused: no paused notice, no reaction, no review.
    verify(commentCommandService, never()).notifyPaused(any());
    verifyNoInteractions(ackReactionService);
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(deduplicator, never()).forget(anyString());
  }

  @Test
  void shouldSkipManualReviewOnClosedPr() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    deliver("issue_comment", event("issue_comment", DELIVERY, closedReviewComment("closed")));

    // #1458: a closed or merged PR is not reviewed; the commenter gets the fixed notice instead.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(commentCommandService).notifyClosed(any(CommentCommandService.CommandContext.class));
  }

  @Test
  void anUnauthorizedReviewOnClosedPrPostsNothing() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized(
            anyString(), anyString(), anyLong(), anyString(), any()))
        .thenReturn(false);

    deliver("issue_comment", event("issue_comment", DELIVERY, closedReviewComment("closed")));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(commentCommandService, never()).notifyClosed(any());
  }

  @Test
  void aManualReviewStillRunsWhenThePayloadSaysThePrIsOpen() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    deliver("issue_comment", event("issue_comment", DELIVERY, closedReviewComment("open")));

    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(commentCommandService, never()).notifyClosed(any());
  }

  /** A {@code /review} comment on PR #77 whose issue carries the given state. */
  private byte[] closedReviewComment(String state) {
    return buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
        .replace("\"issue\":{", "\"issue\":{\"state\":\"" + state + "\",")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void shouldRouteNonReviewCommandsToCommandService() {
    when(triggerDetector.detectCommand("/help")).thenReturn(CommentCommand.HELP);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/help")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    var ctx = org.mockito.ArgumentCaptor.forClass(CommentCommandService.CommandContext.class);
    verify(commentCommandService).handle(ctx.capture());
    assertEquals(CommentCommand.HELP, ctx.getValue().command());
    assertEquals("owner", ctx.getValue().owner());
    assertEquals("repo", ctx.getValue().repo());
    assertEquals(77, ctx.getValue().prNumber());
    assertEquals(12345L, ctx.getValue().installationId());
    // /help is answered for a commenter the authorization refused, and never starts a review.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldRouteAddDocsCommandToCommandService() {
    when(triggerDetector.detectCommand("/add-docs")).thenReturn(CommentCommand.ADD_DOCS);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/add-docs")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    var ctx = org.mockito.ArgumentCaptor.forClass(CommentCommandService.CommandContext.class);
    verify(commentCommandService).handle(ctx.capture());
    assertEquals(CommentCommand.ADD_DOCS, ctx.getValue().command());
    assertEquals(77, ctx.getValue().prNumber());
    // The command service checks the commenter again and does the AI work.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldRouteImproveCommandToCommandServiceAndAckIt() {
    when(triggerDetector.detectCommand("/improve")).thenReturn(CommentCommand.IMPROVE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/improve")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    var ctx = org.mockito.ArgumentCaptor.forClass(CommentCommandService.CommandContext.class);
    verify(commentCommandService).handle(ctx.capture());
    assertEquals(CommentCommand.IMPROVE, ctx.getValue().command());
    assertEquals(77, ctx.getValue().prNumber());
    // The ack reaction (#315) covers /improve like every other comment command.
    verify(ackReactionService)
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);
    // The command service checks the commenter again and does the AI work.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldRouteGenerateTestsCommandToCommandService() {
    when(triggerDetector.detectCommand("/generate-tests"))
        .thenReturn(CommentCommand.GENERATE_TESTS);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/generate-tests")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    var ctx = org.mockito.ArgumentCaptor.forClass(CommentCommandService.CommandContext.class);
    verify(commentCommandService).handle(ctx.capture());
    assertEquals(CommentCommand.GENERATE_TESTS, ctx.getValue().command());
    assertEquals(77, ctx.getValue().prNumber());
    // New commands inherit the 👀 ack and the command service's pause gates.
    verify(ackReactionService)
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldAddEyesReactionOnReviewCommand() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    verify(ackReactionService)
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);
  }

  @Test
  void shouldAddEyesReactionOnNonReviewCommand() {
    when(triggerDetector.detectCommand("/help")).thenReturn(CommentCommand.HELP);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/help")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    verify(ackReactionService)
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);
  }

  @Test
  void anUnauthorizedReviewGetsNoReaction() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("stranger")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized(
            anyString(), anyString(), anyLong(), anyString(), any()))
        .thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 88, "owner/repo", "stranger", "/review", "NONE")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // The commenter is checked before the reaction: a refused command leaves no trace on the PR.
    verifyNoInteractions(ackReactionService);
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldAddEyesReactionOnReviewCommandOnPausedPr() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);
    when(prPauseService.isPaused(any(), eq("owner"), eq("repo"), eq(77))).thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // The ack precedes the pause gate for a commenter with write access.
    verify(ackReactionService)
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);
  }

  @ParameterizedTest
  @EnumSource(
      value = CommentCommand.class,
      names = {"NONE", "HELP", "REVIEW"},
      mode = EnumSource.Mode.EXCLUDE)
  void aStrangersCommandIsDroppedWithoutAReaction(CommentCommand command) {
    when(triggerDetector.detectCommand("/command")).thenReturn(command);
    when(triggerDetector.isBotComment("stranger")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "stranger", "NONE"))
        .thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 88, "owner/repo", "stranger", "/command", "NONE")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    verifyNoInteractions(ackReactionService, commentCommandService);
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(deduplicator, never()).forget(anyString());
  }

  @Test
  void helpFromACommenterWithoutWriteAccessIsAnsweredOncePerPullRequest() {
    when(triggerDetector.detectCommand("/help")).thenReturn(CommentCommand.HELP);
    when(triggerDetector.isBotComment("stranger")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "stranger", "NONE"))
        .thenReturn(false);

    for (var delivery : List.of("help-1", "help-2", "help-3")) {
      deliver(
          "issue_comment",
          event(
              "issue_comment",
              delivery,
              buildIssueCommentPayload("created", 88, "owner/repo", "stranger", "/help", "NONE")
                  .getBytes(StandardCharsets.UTF_8)));
    }

    // One answer and one reaction, however often the commenter asks.
    verify(commentCommandService, times(1)).handle(any());
    verify(ackReactionService, times(1))
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);

    // Another pull request has its own allowance.
    deliver(
        "issue_comment",
        event(
            "issue_comment",
            "help-4",
            buildIssueCommentPayload("created", 89, "owner/repo", "stranger", "/help", "NONE")
                .getBytes(StandardCharsets.UTF_8)));
    verify(commentCommandService, times(2)).handle(any());
  }

  @Test
  void helpFromACommenterWithWriteAccessIsAlwaysAnswered() {
    when(triggerDetector.detectCommand("/help")).thenReturn(CommentCommand.HELP);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    for (var delivery : List.of("help-1", "help-2", "help-3")) {
      deliver(
          "issue_comment",
          event(
              "issue_comment",
              delivery,
              buildIssueCommentPayload("created", 88, "owner/repo", "octocat", "/help")
                  .getBytes(StandardCharsets.UTF_8)));
    }

    verify(commentCommandService, times(3)).handle(any());
    verify(ackReactionService, times(3))
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);
  }

  @Test
  void shouldNotReactOnConversationalMention() {
    when(triggerDetector.detectCommand("@thrillhousebot is this thread-safe?"))
        .thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot is this thread-safe?"))
        .thenReturn(true);
    when(replyDispatcher.dispatch(any(MaintainerReplyService.ReplyTask.class))).thenReturn(true);

    var body =
        buildIssueCommentPayload(
                "created", 77, "owner/repo", "octocat", "@thrillhousebot is this thread-safe?")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // A bare conversational mention is not a command — no 👀 ack, only the reply.
    verify(replyDispatcher).dispatch(any(MaintainerReplyService.ReplyTask.class));
    verifyNoInteractions(ackReactionService);
  }

  @Test
  void shouldNotReactOnReviewThreadMention() {
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot why is this flagged?"))
        .thenReturn(true);
    when(replyDispatcher.dispatch(any(MaintainerReplyService.ReplyTask.class))).thenReturn(true);

    var body =
        buildReviewCommentPayload(
                "created",
                42,
                "owner/repo",
                "octocat",
                "@thrillhousebot why is this flagged?",
                99L,
                1000L)
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request_review_comment", event("pull_request_review_comment", DELIVERY, body));

    // An inline-thread mention is conversational, never a command — no 👀 ack.
    verify(replyDispatcher).dispatch(any(MaintainerReplyService.ReplyTask.class));
    verifyNoInteractions(ackReactionService);
  }

  @Test
  void shouldNotReactWhenCommandInstallationMissing() {
    when(triggerDetector.detectCommand("/help")).thenReturn(CommentCommand.HELP);
    when(triggerDetector.isBotComment("user")).thenReturn(false);

    var body =
        ("{"
                + "\"action\":\"created\","
                + "\"issue\":{\"number\":1,\"pull_request\":{\"url\":\"u\"}},"
                + "\"comment\":{\"id\":1,\"body\":\"/help\",\"user\":{\"login\":\"user\",\"id\":1}},"
                + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
                + "\"installation\":null"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // Without an installation there is no token to react with.
    verifyNoInteractions(ackReactionService);
  }

  @Test
  void shouldIgnoreEditedIssueComment() {

    var body =
        buildIssueCommentPayload("edited", 88, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // Editing a comment must not re-dispatch a review, and authorization is never consulted.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verifyNoInteractions(manualReviewAuthorizer);
  }

  @Test
  void shouldIgnoreDeletedIssueComment() {

    var body =
        buildIssueCommentPayload("deleted", 88, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // Deleting a comment must not re-dispatch a review.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verifyNoInteractions(manualReviewAuthorizer);
  }

  @Test
  void shouldIgnoreIssueCommentWithoutTrigger() {
    when(triggerDetector.detectCommand("Nice PR!")).thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 1, "a/b", "octocat", "Nice PR!")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // Neither the dispatcher nor the command service runs when no command is detected.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verifyNoInteractions(commentCommandService);
  }

  @Test
  void shouldIgnoreBotComment() {
    when(triggerDetector.isBotComment("thrillhousebot[bot]")).thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 1, "a/b", "thrillhousebot[bot]", "/review")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // The bot's own comment is dropped before command detection, so nothing runs.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verifyNoInteractions(commentCommandService);
  }

  @Test
  void shouldDispatchReplyForBotMentionOnPrComment() {
    when(triggerDetector.detectCommand("@thrillhousebot is this thread-safe?"))
        .thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot is this thread-safe?"))
        .thenReturn(true);
    when(replyDispatcher.dispatch(any(MaintainerReplyService.ReplyTask.class))).thenReturn(true);

    var body =
        buildIssueCommentPayload(
                "created", 77, "owner/repo", "octocat", "@thrillhousebot is this thread-safe?")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    verify(replyDispatcher)
        .dispatch(
            new MaintainerReplyService.ReplyTask(
                "owner",
                "repo",
                77,
                12345L,
                7L,
                "octocat",
                "OWNER",
                "@thrillhousebot is this thread-safe?",
                "PR Title",
                "desc",
                false,
                null,
                1L,
                true,
                null));
    // A bare mention is a question, not a review command.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldSkipConversationalMentionOnPausedPr() {
    when(triggerDetector.detectCommand("@thrillhousebot is this thread-safe?"))
        .thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot is this thread-safe?"))
        .thenReturn(true);
    when(prPauseService.isPaused(any(), eq("owner"), eq("repo"), eq(77))).thenReturn(true);

    var body =
        buildIssueCommentPayload(
                "created", 77, "owner/repo", "octocat", "@thrillhousebot is this thread-safe?")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    // /pause silences the bot, so a mention must not spend a paid conversational reply.
    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldNotDispatchReplyForMentionWhenRepliesDisabled() {
    when(reviewConfig.conversationalRepliesEnabled()).thenReturn(false);
    when(triggerDetector.detectCommand("@thrillhousebot hi")).thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 5, "owner/repo", "octocat", "@thrillhousebot hi")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldDispatchReplyForReviewCommentMentionInReply() {
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot why is this flagged?"))
        .thenReturn(true);
    when(replyDispatcher.dispatch(any(MaintainerReplyService.ReplyTask.class))).thenReturn(true);

    // A mention that is itself a reply targets the thread root it replies to (in_reply_to_id 99),
    // not the mention comment.
    var body =
        buildReviewCommentPayload(
                "created",
                42,
                "owner/repo",
                "octocat",
                "@thrillhousebot why is this flagged?",
                99L,
                1000L)
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request_review_comment", event("pull_request_review_comment", DELIVERY, body));

    verify(findingFeedbackCapture)
        .scheduleCaptureOnReviewReply(
            new RepoScope(12345L, 7L),
            "owner",
            "repo",
            42,
            99L,
            new FindingFeedbackCaptureService.ReviewReply(
                "octocat", "OWNER", "@thrillhousebot why is this flagged?"));

    verify(replyDispatcher)
        .dispatch(
            new MaintainerReplyService.ReplyTask(
                "owner",
                "repo",
                42,
                12345L,
                7L,
                "octocat",
                "OWNER",
                "@thrillhousebot why is this flagged?",
                "PR Title",
                "PR body",
                true,
                99L,
                1000L,
                true,
                "@@ -1 +1 @@"));
  }

  @Test
  void shouldCaptureFindingFeedbackOnReviewReplyWithoutBotMention() {
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("not useful")).thenReturn(false);

    var body =
        buildReviewCommentPayload("created", 42, "owner/repo", "octocat", "not useful", 99L, 1001L)
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request_review_comment", event("pull_request_review_comment", DELIVERY, body));

    verify(findingFeedbackCapture)
        .scheduleCaptureOnReviewReply(
            new RepoScope(12345L, 7L),
            "owner",
            "repo",
            42,
            99L,
            new FindingFeedbackCaptureService.ReviewReply("octocat", "OWNER", "not useful"));
    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldDispatchReplyForReviewCommentMentionOnNewLine() {
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot thoughts?")).thenReturn(true);
    when(replyDispatcher.dispatch(any(MaintainerReplyService.ReplyTask.class))).thenReturn(true);

    // A brand-new inline comment (no in_reply_to_id) that mentions the bot replies under itself.
    var body =
        buildReviewCommentPayload(
                "created", 42, "owner/repo", "octocat", "@thrillhousebot thoughts?", null, 2000L)
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request_review_comment", event("pull_request_review_comment", DELIVERY, body));

    verify(replyDispatcher)
        .dispatch(
            new MaintainerReplyService.ReplyTask(
                "owner",
                "repo",
                42,
                12345L,
                7L,
                "octocat",
                "OWNER",
                "@thrillhousebot thoughts?",
                "PR Title",
                "PR body",
                true,
                2000L,
                2000L,
                true,
                "@@ -1 +1 @@"));
  }

  @Test
  void shouldSkipReviewCommentReplyOnPausedPr() {
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot why is this flagged?"))
        .thenReturn(true);
    when(prPauseService.isPaused(any(), eq("owner"), eq("repo"), eq(42))).thenReturn(true);

    // A mention clears the trigger gate; the pause check is what must suppress the reply here.
    var body =
        buildReviewCommentPayload(
                "created",
                42,
                "owner/repo",
                "octocat",
                "@thrillhousebot why is this flagged?",
                99L,
                1000L)
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request_review_comment", event("pull_request_review_comment", DELIVERY, body));

    // /pause silences the bot, so a review-thread reply must not spend a paid conversational reply.
    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @ParameterizedTest
  @EnumSource(IgnoredReviewComment.class)
  void shouldIgnoreReviewCommentWithoutDispatching(IgnoredReviewComment scenario) {
    scenario.applySetup(triggerDetector, reviewConfig);

    var body =
        buildReviewCommentPayload(
                "created",
                42,
                "owner/repo",
                scenario.author,
                scenario.commentBody,
                scenario.inReplyToId,
                scenario.commentId)
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request_review_comment", event("pull_request_review_comment", DELIVERY, body));

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  /** Review-comment scenarios that must be ignored without dispatching a conversational reply. */
  private enum IgnoredReviewComment {
    NEITHER_REPLIES_NOR_MENTIONS("octocat", "looks good", null, 3000L) {
      @Override
      void applySetup(
          TriggerDetector triggerDetector, ThrillhouseConfig.ReviewConfig reviewConfig) {
        when(triggerDetector.isBotComment("octocat")).thenReturn(false);
        when(triggerDetector.containsBotMention("looks good")).thenReturn(false);
      }
    },
    // A bare reply (even on the bot's finding thread) without a mention must not pull the bot in.
    REPLY_WITHOUT_MENTION("octocat", "Why is this flagged?", 99L, 1000L) {
      @Override
      void applySetup(
          TriggerDetector triggerDetector, ThrillhouseConfig.ReviewConfig reviewConfig) {
        when(triggerDetector.isBotComment("octocat")).thenReturn(false);
        when(triggerDetector.containsBotMention("Why is this flagged?")).thenReturn(false);
      }
    },
    OWN_COMMENT("thrillhousebot[bot]", "follow-up", 99L, 4000L) {
      @Override
      void applySetup(
          TriggerDetector triggerDetector, ThrillhouseConfig.ReviewConfig reviewConfig) {
        when(triggerDetector.isBotComment("thrillhousebot[bot]")).thenReturn(true);
      }
    },
    REPLIES_DISABLED("octocat", "Why?", 99L, 6000L) {
      @Override
      void applySetup(
          TriggerDetector triggerDetector, ThrillhouseConfig.ReviewConfig reviewConfig) {
        when(reviewConfig.conversationalRepliesEnabled()).thenReturn(false);
      }
    };

    final String author;
    final String commentBody;
    final Long inReplyToId;
    final long commentId;

    IgnoredReviewComment(String author, String commentBody, Long inReplyToId, long commentId) {
      this.author = author;
      this.commentBody = commentBody;
      this.inReplyToId = inReplyToId;
      this.commentId = commentId;
    }

    abstract void applySetup(
        TriggerDetector triggerDetector, ThrillhouseConfig.ReviewConfig reviewConfig);
  }

  @Test
  void shouldIgnoreEditedReviewComment() {

    var body =
        buildReviewCommentPayload("edited", 42, "owner/repo", "octocat", "edited text", 99L, 5000L)
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request_review_comment", event("pull_request_review_comment", DELIVERY, body));

    // Editing a review comment must not re-trigger a reply.
    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
    verifyNoInteractions(triggerDetector);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("reviewCommentsWithMissingFields")
  void shouldIgnoreReviewCommentWithMissingFields(String name, String body) {
    // A present author reaches the bot-loop guard; the null-author/null-comment cases short-circuit
    // before it, so this stub is simply unused for them.
    when(triggerDetector.isBotComment(anyString())).thenReturn(false);

    deliver(
        "pull_request_review_comment",
        event("pull_request_review_comment", DELIVERY, body.getBytes(StandardCharsets.UTF_8)));

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  private static Stream<Arguments> reviewCommentsWithMissingFields() {
    return Stream.of(
        arguments(
            "missing comment author",
            "{"
                + "\"action\":\"created\","
                + "\"comment\":{\"id\":1,\"body\":\"reply\",\"in_reply_to_id\":99,\"user\":null},"
                + "\"pull_request\":{\"number\":42,\"title\":\"T\",\"head\":{\"sha\":\"h\"},\"base\":{\"sha\":\"b\"},\"body\":\"d\"},"
                + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":2}},"
                + "\"installation\":{\"id\":12345}"
                + "}"),
        arguments(
            "missing pull_request",
            "{"
                + "\"action\":\"created\","
                + "\"comment\":{\"id\":1,\"body\":\"reply\",\"author_association\":\"OWNER\",\"in_reply_to_id\":99,\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"pull_request\":null,"
                + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":2}},"
                + "\"installation\":{\"id\":12345}"
                + "}"),
        arguments(
            "missing comment",
            "{"
                + "\"action\":\"created\","
                + "\"comment\":null,"
                + "\"pull_request\":{\"number\":42,\"title\":\"T\",\"head\":{\"sha\":\"h\"},\"base\":{\"sha\":\"b\"},\"body\":\"d\"},"
                + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":2}},"
                + "\"installation\":{\"id\":12345}"
                + "}"));
  }

  @Test
  void shouldIgnoreReviewCommentWithMissingRepository() {
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    // pull_request present but repository missing — exercises the second operand of the guard.
    var body =
        ("{"
                + "\"action\":\"created\","
                + "\"comment\":{\"id\":1,\"body\":\"reply\",\"author_association\":\"OWNER\",\"in_reply_to_id\":99,\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"pull_request\":{\"number\":42,\"title\":\"T\",\"head\":{\"sha\":\"h\"},\"base\":{\"sha\":\"b\"},\"body\":\"d\"},"
                + "\"repository\":null,"
                + "\"installation\":{\"id\":12345}"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request_review_comment", event("pull_request_review_comment", DELIVERY, body));

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldIgnoreReviewCommentWithMissingInstallation() {
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    // pull_request and repository present, but installation missing — exercises the third
    // operand of the compound guard.
    var body =
        ("{"
                + "\"action\":\"created\","
                + "\"comment\":{\"id\":1,\"body\":\"reply\",\"author_association\":\"OWNER\",\"in_reply_to_id\":99,\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"pull_request\":{\"number\":42,\"title\":\"T\",\"head\":{\"sha\":\"h\"},\"base\":{\"sha\":\"b\"},\"body\":\"d\"},"
                + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":2}},"
                + "\"installation\":null"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("pull_request_review_comment", event("pull_request_review_comment", DELIVERY, body));

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldIgnoreMentionWithMissingInstallation() {
    when(triggerDetector.detectCommand("@thrillhousebot hi")).thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot hi")).thenReturn(true);

    // repository present but installation missing — exercises the mention guard's second operand.
    var body =
        ("{"
                + "\"action\":\"created\","
                + "\"issue\":{\"number\":5,\"title\":\"T\",\"body\":\"d\",\"pull_request\":{\"url\":\"u\"}},"
                + "\"comment\":{\"id\":1,\"body\":\"@thrillhousebot hi\",\"author_association\":\"OWNER\",\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":2}},"
                + "\"installation\":null"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldIgnoreMentionWithMissingRepository() {
    when(triggerDetector.detectCommand("@thrillhousebot hi")).thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot hi")).thenReturn(true);

    var body =
        ("{"
                + "\"action\":\"created\","
                + "\"issue\":{\"number\":5,\"title\":\"T\",\"body\":\"d\",\"pull_request\":{\"url\":\"u\"}},"
                + "\"comment\":{\"id\":1,\"body\":\"@thrillhousebot hi\",\"author_association\":\"OWNER\",\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"repository\":null,"
                + "\"installation\":{\"id\":12345}"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("issue_comment", event("issue_comment", DELIVERY, body));

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldHandleInstallationEvent() {

    var body = buildInstallationPayload("created", 12345L).getBytes(StandardCharsets.UTF_8);

    deliver("installation", event("installation", DELIVERY, body));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldHandleInstallationRepositoriesEvent() {

    var body = buildInstallationPayload("added", 42L).getBytes(StandardCharsets.UTF_8);

    deliver("installation_repositories", event("installation_repositories", DELIVERY, body));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldHandleInstallationEventWithoutInstallationId() {

    // Payload with action but no installation field
    var body = "{\"action\":\"created\"}".getBytes(StandardCharsets.UTF_8);

    deliver("installation", event("installation", DELIVERY, body));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  /**
   * Everything the bot stores is scoped by installation and repository id (#1220), so a payload
   * without either is ignored rather than handled unscoped.
   */
  @Test
  void shouldIgnoreAPullRequestWithoutARepositoryOrInstallationId() {
    var payload = buildPullRequestPayload("opened", 1, "a/b", "sha", "main");

    for (var body :
        List.of(
            payload.replace("\"id\":7,", ""),
            payload.replace("\"installation\":{\"id\":12345}", "\"installation\":{\"id\":0}"))) {
      deliver(
          "pull_request", event("pull_request", DELIVERY, body.getBytes(StandardCharsets.UTF_8)));
    }

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(prPauseService, never()).isPaused(any(), any(), any(), anyInt());
  }

  @Test
  void shouldHandleUnknownEventType() {

    var body =
        buildPullRequestPayload("opened", 1, "a/b", "sha", "main").getBytes(StandardCharsets.UTF_8);

    deliver("push", event("push", DELIVERY, body));

    // Orchestrator should NOT be called for unknown event types
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldCatchRuntimeExceptionInRouteEvent() {
    doThrow(new RuntimeException("simulated failure"))
        .when(reviewDispatcher)
        .dispatch(any(ReviewOrchestrator.ReviewRequest.class));

    var body =
        buildPullRequestPayload("opened", 1, "a/b", "sha", "main").getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", DELIVERY, body));

    // The async routeEvent should have caught the RuntimeException and logged it
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldRecheckHeldVerdictWhenCheckSuiteCompletes() {
    when(ciHoldRegistry.pullRequestsAt("a", "b", "abc123")).thenReturn(List.of(7));
    when(reviewDispatcher.dispatchCiRecheck(any())).thenReturn(true);

    var body =
        buildCheckSuitePayload("completed", "a/b", "abc123", 99L).getBytes(StandardCharsets.UTF_8);

    deliver("check_suite", event("check_suite", DELIVERY, body));

    verify(reviewDispatcher)
        .dispatchCiRecheck(
            new CiHoldRevisit.Recheck("a", "b", 7, "abc123", 1L, TestScopes.REPOSITORY_ID));
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(deduplicator, never()).forget(anyString());
  }

  @Test
  void shouldRecheckEveryPullRequestHeldAtTheCompletedHead() {
    when(ciHoldRegistry.pullRequestsAt("a", "b", "abc123")).thenReturn(List.of(7, 8));
    when(reviewDispatcher.dispatchCiRecheck(any())).thenReturn(true);

    var body =
        buildCheckSuitePayload("completed", "a/b", "abc123", 99L).getBytes(StandardCharsets.UTF_8);

    deliver("check_suite", event("check_suite", DELIVERY, body));

    verify(reviewDispatcher)
        .dispatchCiRecheck(
            new CiHoldRevisit.Recheck("a", "b", 7, "abc123", 1L, TestScopes.REPOSITORY_ID));
    verify(reviewDispatcher)
        .dispatchCiRecheck(
            new CiHoldRevisit.Recheck("a", "b", 8, "abc123", 1L, TestScopes.REPOSITORY_ID));
  }

  @Test
  void shouldIgnoreCheckSuiteWhenNoVerdictIsHeldAtItsHead() {
    when(ciHoldRegistry.pullRequestsAt("a", "b", "abc123")).thenReturn(List.of());

    var body =
        buildCheckSuitePayload("completed", "a/b", "abc123", 99L).getBytes(StandardCharsets.UTF_8);

    deliver("check_suite", event("check_suite", DELIVERY, body));

    verify(reviewDispatcher, never()).dispatchCiRecheck(any());
  }

  @Test
  void shouldIgnoreCheckSuiteThatIsNotCompleted() {

    var body =
        buildCheckSuitePayload("requested", "a/b", "abc123", 99L).getBytes(StandardCharsets.UTF_8);

    deliver("check_suite", event("check_suite", DELIVERY, body));

    verifyNoInteractions(ciHoldRegistry);
    verify(reviewDispatcher, never()).dispatchCiRecheck(any());
  }

  @Test
  void shouldIgnoreTheBotsOwnCheckSuite() {
    when(githubConfig.appId()).thenReturn("99");

    var body =
        buildCheckSuitePayload("completed", "a/b", "abc123", 99L).getBytes(StandardCharsets.UTF_8);

    deliver("check_suite", event("check_suite", DELIVERY, body));

    verifyNoInteractions(ciHoldRegistry);
    verify(reviewDispatcher, never()).dispatchCiRecheck(any());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("incompleteCheckSuitePayloads")
  void shouldIgnoreCheckSuiteWithMissingFields(String name, String payload) {

    deliver(
        "check_suite", event("check_suite", DELIVERY, payload.getBytes(StandardCharsets.UTF_8)));

    verifyNoInteractions(ciHoldRegistry);
    verify(reviewDispatcher, never()).dispatchCiRecheck(any());
  }

  static Stream<Arguments> incompleteCheckSuitePayloads() {
    var repository = repositoryJson("a/b");
    return Stream.of(
        arguments(
            "no check_suite",
            "{\"action\":\"completed\"," + repository + ",\"installation\":{\"id\":1}}"),
        arguments(
            "no head_sha",
            "{\"action\":\"completed\",\"check_suite\":{\"head_branch\":\"f\"},"
                + repository
                + ",\"installation\":{\"id\":1}}"),
        arguments(
            "no repository",
            "{\"action\":\"completed\",\"check_suite\":{\"head_sha\":\"abc123\"},"
                + "\"installation\":{\"id\":1}}"),
        arguments(
            "blank head_sha",
            "{\"action\":\"completed\",\"check_suite\":{\"head_sha\":\" \"},"
                + repository
                + ",\"installation\":{\"id\":1}}"),
        arguments(
            "no repository owner",
            "{\"action\":\"completed\",\"check_suite\":{\"head_sha\":\"abc123\"},"
                + "\"repository\":{\"id\":7,\"name\":\"b\"},\"installation\":{\"id\":1}}"),
        arguments(
            "no installation",
            "{\"action\":\"completed\",\"check_suite\":{\"head_sha\":\"abc123\"},"
                + repository
                + "}"));
  }

  @Test
  void shouldRecheckOnAnotherAppsCheckSuiteWhenOwnAppIdIsConfigured() {
    when(githubConfig.appId()).thenReturn("12345");
    when(ciHoldRegistry.pullRequestsAt("a", "b", "abc123")).thenReturn(List.of(7));
    when(reviewDispatcher.dispatchCiRecheck(any())).thenReturn(true);

    var body =
        buildCheckSuitePayload("completed", "a/b", "abc123", 99L).getBytes(StandardCharsets.UTF_8);

    deliver("check_suite", event("check_suite", DELIVERY, body));

    verify(reviewDispatcher)
        .dispatchCiRecheck(
            new CiHoldRevisit.Recheck("a", "b", 7, "abc123", 1L, TestScopes.REPOSITORY_ID));
  }

  @Test
  void shouldIgnoreCommitStatusWithoutState() {

    var body =
        ("{\"sha\":\"abc123\",\"context\":\"ci\","
                + repositoryJson("a/b")
                + ",\"installation\":{\"id\":1}}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("status", event("status", DELIVERY, body));

    verifyNoInteractions(ciHoldRegistry);
  }

  @Test
  void shouldForgetDeliveryIdWhenCiRecheckDispatchIsRejected() {
    when(ciHoldRegistry.pullRequestsAt("a", "b", "abc123")).thenReturn(List.of(7));
    when(reviewDispatcher.dispatchCiRecheck(any())).thenReturn(false);

    var body =
        buildCheckSuitePayload("completed", "a/b", "abc123", 99L).getBytes(StandardCharsets.UTF_8);

    deliver("check_suite", event("check_suite", "delivery-ci", body));

    verify(deduplicator).forget("delivery-ci");
  }

  @Test
  void shouldRecheckHeldVerdictWhenCommitStatusSettles() {
    when(ciHoldRegistry.pullRequestsAt("a", "b", "abc123")).thenReturn(List.of(7));
    when(reviewDispatcher.dispatchCiRecheck(any())).thenReturn(true);

    var body = buildStatusPayload("a/b", "abc123", "success").getBytes(StandardCharsets.UTF_8);

    deliver("status", event("status", DELIVERY, body));

    verify(reviewDispatcher)
        .dispatchCiRecheck(
            new CiHoldRevisit.Recheck("a", "b", 7, "abc123", 1L, TestScopes.REPOSITORY_ID));
  }

  @Test
  void shouldIgnorePendingCommitStatus() {

    var body = buildStatusPayload("a/b", "abc123", "pending").getBytes(StandardCharsets.UTF_8);

    deliver("status", event("status", DELIVERY, body));

    verifyNoInteractions(ciHoldRegistry);
    verify(reviewDispatcher, never()).dispatchCiRecheck(any());
  }

  @Test
  void shouldIgnoreCommitStatusWithoutSha() {

    var body =
        ("{\"state\":\"success\",\"context\":\"ci\","
                + repositoryJson("a/b")
                + ",\"installation\":{\"id\":1}}")
            .getBytes(StandardCharsets.UTF_8);

    deliver("status", event("status", DELIVERY, body));

    verifyNoInteractions(ciHoldRegistry);
  }

  private static String buildCheckSuitePayload(
      String action, String fullName, String headSha, long appId) {
    return "{\"action\":\""
        + action
        + "\",\"check_suite\":{\"head_sha\":\""
        + headSha
        + "\",\"head_branch\":\"feature\",\"conclusion\":\"success\",\"app\":{\"id\":"
        + appId
        + ",\"slug\":\"ci\"},\"pull_requests\":[]},"
        + repositoryJson(fullName)
        + ",\"installation\":{\"id\":1}}";
  }

  private static String buildStatusPayload(String fullName, String sha, String state) {
    return "{\"sha\":\""
        + sha
        + "\",\"state\":\""
        + state
        + "\",\"context\":\"codecov/patch\","
        + repositoryJson(fullName)
        + ",\"installation\":{\"id\":1}}";
  }

  private static String repositoryJson(String fullName) {
    var slash = fullName.indexOf('/');
    return "\"repository\":{\"id\":7,\"full_name\":\""
        + fullName
        + "\",\"name\":\""
        + fullName.substring(slash + 1)
        + "\",\"default_branch\":\"main\",\"owner\":{\"login\":\""
        + fullName.substring(0, slash)
        + "\",\"id\":2}}";
  }

  @Test
  void shouldForgetDeliveryIdWhenDispatchIsRejected() {
    // A saturated executor makes dispatch return false (it does not throw — the dispatcher
    // swallows RejectedExecutionException), which is the real scenario.
    when(reviewDispatcher.dispatch(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(false);

    var body =
        buildPullRequestPayload("opened", 1, "a/b", "sha", "main").getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "delivery-abc", body));

    // The rejected dispatch must roll back the dedup slot so manual redelivery can retry.
    verify(deduplicator).forget("delivery-abc");
  }

  @Test
  void shouldForgetDeliveryIdWhenDispatchThrows() {
    doThrow(new RuntimeException("unexpected failure"))
        .when(reviewDispatcher)
        .dispatch(any(ReviewOrchestrator.ReviewRequest.class));

    var body =
        buildPullRequestPayload("opened", 1, "a/b", "sha", "main").getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "delivery-throw", body));

    // An unexpected RuntimeException must also roll back the dedup slot for the same reason.
    verify(deduplicator).forget("delivery-throw");
  }

  @Test
  void shouldNotForgetDeliveryIdWhenDispatchSucceeds() {
    when(reviewDispatcher.dispatch(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 1, "a/b", "sha", "main").getBytes(StandardCharsets.UTF_8);

    deliver("pull_request", event("pull_request", "delivery-ok", body));

    // A successful dispatch keeps the dedup slot so redeliveries stay suppressed.
    verify(deduplicator, never()).forget(anyString());
  }

  @Test
  void shouldIgnoreIssueCommentWhenIssueMissing() {

    var body =
        "{"
            + "\"action\":\"created\","
            + "\"issue\":null,"
            + "\"comment\":{\"id\":1,\"body\":\"/review\",\"user\":{\"login\":\"user\",\"id\":1}},"
            + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":{\"id\":12345}"
            + "}";

    deliver(
        "issue_comment", event("issue_comment", DELIVERY, body.getBytes(StandardCharsets.UTF_8)));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreIssueCommentOnPlainIssue() {
    // An issue comment without pull_request field (plain issue, not PR)
    when(triggerDetector.isBotComment("user")).thenReturn(false);

    var body =
        "{"
            + "\"action\":\"created\","
            + "\"issue\":{\"number\":1,\"title\":\"Issue\",\"body\":\"desc\"},"
            + "\"comment\":{\"id\":1,\"body\":\"/review\",\"user\":{\"login\":\"user\",\"id\":1}},"
            + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":{\"id\":12345}"
            + "}";

    deliver(
        "issue_comment", event("issue_comment", DELIVERY, body.getBytes(StandardCharsets.UTF_8)));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreIssueCommentWithMissingAuthor() {

    var body =
        "{"
            + "\"action\":\"created\","
            + "\"issue\":{\"number\":1,\"pull_request\":{\"url\":\"u\"}},"
            + "\"comment\":{\"id\":1,\"body\":\"/review\",\"user\":null},"
            + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":{\"id\":1}"
            + "}";

    deliver(
        "issue_comment", event("issue_comment", DELIVERY, body.getBytes(StandardCharsets.UTF_8)));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreReviewTriggerWithMissingRepository() {
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("user")).thenReturn(false);

    var body =
        "{"
            + "\"action\":\"created\","
            + "\"issue\":{\"number\":1,\"pull_request\":{\"url\":\"u\"}},"
            + "\"comment\":{\"id\":1,\"body\":\"/review\",\"user\":{\"login\":\"user\",\"id\":1}},"
            + "\"repository\":null,"
            + "\"installation\":{\"id\":1}"
            + "}";

    deliver(
        "issue_comment", event("issue_comment", DELIVERY, body.getBytes(StandardCharsets.UTF_8)));

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreCommandWhenInstallationMissing() {
    when(triggerDetector.detectCommand("/help")).thenReturn(CommentCommand.HELP);
    when(triggerDetector.isBotComment("user")).thenReturn(false);

    var body =
        "{"
            + "\"action\":\"created\","
            + "\"issue\":{\"number\":1,\"pull_request\":{\"url\":\"u\"}},"
            + "\"comment\":{\"id\":1,\"body\":\"/help\",\"user\":{\"login\":\"user\",\"id\":1}},"
            + "\"repository\":{\"id\":7,\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":null"
            + "}";

    deliver(
        "issue_comment", event("issue_comment", DELIVERY, body.getBytes(StandardCharsets.UTF_8)));

    // A command with no installation cannot be authenticated to GitHub, so nothing runs.
    verifyNoInteractions(commentCommandService);
  }

  private String buildPullRequestPayload(
      String action, int prNumber, String fullName, String headSha, String defaultBranch) {
    return buildPullRequestPayload(action, prNumber, fullName, headSha, defaultBranch, true);
  }

  private String buildPullRequestPayload(
      String action,
      int prNumber,
      String fullName,
      String headSha,
      String defaultBranch,
      boolean includeBody) {
    return ("{"
        + "\"action\":\""
        + action
        + "\","
        + "\"pull_request\":{"
        + "\"number\":"
        + prNumber
        + ","
        + "\"title\":\"Test PR title\","
        + "\"head\":{\"sha\":\""
        + headSha
        + "\",\"ref\":\"feature\"},"
        + "\"base\":{\"sha\":\"basesha456\",\"ref\":\""
        + defaultBranch
        + "\"},"
        + "\"user\":{\"login\":\"octocat\",\"id\":1}"
        + (includeBody ? ",\"body\":\"PR body\"" : "")
        + "},"
        + "\"repository\":{"
        + "\"id\":7,"
        + "\"full_name\":\""
        + fullName
        + "\","
        + "\"name\":\""
        + fullName.substring(fullName.indexOf('/') + 1)
        + "\","
        + "\"default_branch\":\""
        + defaultBranch
        + "\","
        + "\"owner\":{\"login\":\""
        + fullName.substring(0, fullName.indexOf('/'))
        + "\",\"id\":2}"
        + "},"
        + "\"installation\":{\"id\":12345}"
        + "}");
  }

  private String buildInstallationPayload(String action, long installationId) {
    return ("{"
        + "\"action\":\""
        + action
        + "\","
        + "\"installation\":{\"id\":"
        + installationId
        + "}"
        + "}");
  }

  private String buildReviewCommentPayload(
      String action,
      int prNumber,
      String fullName,
      String userLogin,
      String commentBody,
      Long inReplyToId,
      long commentId) {
    return ("{"
        + "\"action\":\""
        + action
        + "\","
        + "\"comment\":{"
        + "\"id\":"
        + commentId
        + ","
        + "\"body\":\""
        + commentBody
        + "\","
        + "\"author_association\":\"OWNER\","
        + "\"path\":\"src/Foo.java\","
        + "\"diff_hunk\":\"@@ -1 +1 @@\","
        + (inReplyToId != null ? "\"in_reply_to_id\":" + inReplyToId + "," : "")
        + "\"user\":{\"login\":\""
        + userLogin
        + "\",\"id\":1}"
        + "},"
        + "\"pull_request\":{"
        + "\"number\":"
        + prNumber
        + ","
        + "\"title\":\"PR Title\","
        + "\"head\":{\"sha\":\"headsha\"},"
        + "\"base\":{\"sha\":\"basesha\"},"
        + "\"body\":\"PR body\""
        + "},"
        + "\"repository\":{"
        + "\"id\":7,"
        + "\"full_name\":\""
        + fullName
        + "\","
        + "\"name\":\""
        + fullName.substring(fullName.indexOf('/') + 1)
        + "\","
        + "\"default_branch\":\"main\","
        + "\"owner\":{\"login\":\""
        + fullName.substring(0, fullName.indexOf('/'))
        + "\",\"id\":2}"
        + "},"
        + "\"installation\":{\"id\":12345}"
        + "}");
  }

  private String buildIssueCommentPayload(
      String action, int issueNumber, String fullName, String userLogin, String commentBody) {
    return buildIssueCommentPayload(action, issueNumber, fullName, userLogin, commentBody, "OWNER");
  }

  private String buildIssueCommentPayload(
      String action,
      int issueNumber,
      String fullName,
      String userLogin,
      String commentBody,
      String authorAssociation) {
    return ("{"
        + "\"action\":\""
        + action
        + "\","
        + "\"issue\":{"
        + "\"number\":"
        + issueNumber
        + ","
        + "\"title\":\"PR Title\","
        + "\"body\":\"desc\","
        + "\"pull_request\":{"
        + "\"url\":\"https://api.github.com/repos/foo/bar/pulls/"
        + issueNumber
        + "\""
        + "}"
        + "},"
        + "\"comment\":{"
        + "\"id\":1,"
        + "\"body\":\""
        + commentBody
        + "\","
        + "\"author_association\":\""
        + authorAssociation
        + "\","
        + "\"user\":{\"login\":\""
        + userLogin
        + "\",\"id\":1}"
        + "},"
        + "\"repository\":{"
        + "\"id\":7,"
        + "\"full_name\":\""
        + fullName
        + "\","
        + "\"name\":\""
        + fullName.substring(fullName.indexOf('/') + 1)
        + "\","
        + "\"default_branch\":\"main\","
        + "\"owner\":{\"login\":\""
        + fullName.substring(0, fullName.indexOf('/'))
        + "\",\"id\":2}"
        + "},"
        + "\"installation\":{\"id\":12345}"
        + "}");
  }
}
