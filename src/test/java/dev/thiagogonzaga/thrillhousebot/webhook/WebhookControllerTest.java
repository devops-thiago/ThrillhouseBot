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
import dev.thiagogonzaga.thrillhousebot.review.AutoReviewRateLimiter;
import dev.thiagogonzaga.thrillhousebot.review.MaintainerReplyDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.MaintainerReplyService;
import dev.thiagogonzaga.thrillhousebot.review.ReviewDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator;
import dev.thiagogonzaga.thrillhousebot.review.ReviewSkipEmitter;
import dev.thiagogonzaga.thrillhousebot.review.ReviewSkipReason;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class WebhookControllerTest {

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.GitHubConfig githubConfig;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  @Mock private WebhookVerifier verifier;

  @Mock private TriggerDetector triggerDetector;

  @Mock private ReviewTriggerFilter triggerFilter;

  @Mock private AutoReviewRateLimiter autoReviewRateLimiter;

  @Mock private ReviewDispatcher reviewDispatcher;

  @Mock private MaintainerReplyDispatcher replyDispatcher;

  @Mock private WebhookDeduplicator deduplicator;

  @Mock private ManualReviewAuthorizer manualReviewAuthorizer;

  @Mock private CommentCommandService commentCommandService;

  @Mock private PrPauseService prPauseService;

  @Mock private AckReactionService ackReactionService;

  @Mock private ReviewSkipEmitter skipEmitter;

  private final ObjectMapper mapper = new ObjectMapper();

  /** A non-null delivery id; the mocked deduplicator reports it unseen unless a test overrides. */
  private static final String DELIVERY = "00000000-0000-0000-0000-000000000001";

  private WebhookController controller;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.webhookSecret()).thenReturn("test-secret");
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.conversationalRepliesEnabled()).thenReturn(true);
    controller =
        new WebhookController(
            config,
            verifier,
            triggerDetector,
            triggerFilter,
            autoReviewRateLimiter,
            reviewDispatcher,
            replyDispatcher,
            deduplicator,
            manualReviewAuthorizer,
            commentCommandService,
            prPauseService,
            ackReactionService,
            skipEmitter,
            mapper);
  }

  @Test
  void shouldReturn200WithValidSignature() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 42, "owner/repo", "abc123", "main")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);

    assertEquals(200, response.getStatus());
  }

  @Test
  void shouldReturn401WithInvalidSignature() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(false);

    var body = "{}".getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=invalid", "pull_request", null, DELIVERY, body);

    assertEquals(401, response.getStatus());
    var entity = (String) response.getEntity();
    assertTrue(entity.contains("Invalid signature"));
  }

  @Test
  void shouldReturn400WithBadJsonBody() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body = "not-valid-json".getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);

    assertEquals(400, response.getStatus());
    var entity = (String) response.getEntity();
    assertTrue(entity.contains("Invalid payload"));
  }

  @Test
  void shouldDropRedeliveredWebhookWithoutDispatching() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 7, "owner/repo", "sha7", "main")
            .getBytes(StandardCharsets.UTF_8);

    var response =
        controller.handleWebhook("sha256=valid", "pull_request", null, "redelivered-id", body);

    assertEquals(200, response.getStatus());
    assertTrue(((String) response.getEntity()).contains("ignored"));
    // A redelivery must not start a second review.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldProcessFirstDeliveryAndPassIdToDeduplicator() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 8, "owner/repo", "sha8", "main")
            .getBytes(StandardCharsets.UTF_8);

    var response =
        controller.handleWebhook("sha256=valid", "pull_request", null, "delivery-xyz", body);

    assertEquals(200, response.getStatus());
    verify(deduplicator).isDuplicate("delivery-xyz");
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldNotConsultDeduplicatorWhenSignatureInvalid() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(false);

    var body = "{}".getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=bad", "pull_request", null, "any-id", body);

    assertEquals(401, response.getStatus());
    // Dedup happens only after the signature is verified, so forged ids cannot poison the cache.
    verifyNoInteractions(deduplicator);
  }

  @Test
  void shouldRouteOpenedPullRequestToOrchestrator() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 99, "owner/myrepo", "headsha123", "default")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

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
                false,
                "default"));
  }

  @Test
  void shouldIgnorePullRequestWithMissingHead() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    // A pull_request event whose payload omits head (or base) must not NPE — it is ignored.
    var body =
        ("{"
                + "\"action\":\"opened\","
                + "\"pull_request\":{\"number\":42,\"title\":\"T\","
                + "\"base\":{\"sha\":\"b\",\"ref\":\"main\"},\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"repository\":{\"full_name\":\"owner/repo\",\"name\":\"repo\","
                + "\"default_branch\":\"main\",\"owner\":{\"login\":\"owner\",\"id\":2}},"
                + "\"installation\":{\"id\":12345}}")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnorePullRequestWithMissingBase() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    // head present, base omitted — the other side of the head/base guard.
    var body =
        ("{"
                + "\"action\":\"opened\","
                + "\"pull_request\":{\"number\":42,\"title\":\"T\","
                + "\"head\":{\"sha\":\"h\",\"ref\":\"feature\"},\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"repository\":{\"full_name\":\"owner/repo\",\"name\":\"repo\","
                + "\"default_branch\":\"main\",\"owner\":{\"login\":\"owner\",\"id\":2}},"
                + "\"installation\":{\"id\":12345}}")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldRouteSynchronizePullRequestToOrchestrator() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildPullRequestPayload("synchronize", 55, "org/app", "newsha", "develop")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

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
                false,
                "develop"));
  }

  @Test
  void shouldRouteReopenedPullRequestToOrchestrator() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildPullRequestPayload("reopened", 10, "a/b", "sha10", "main")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

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
                false,
                "main"));
  }

  @Test
  void shouldRouteReadyForReviewPullRequestToOrchestrator() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    // A draft marked "Ready for review" fires ready_for_review, not synchronize, so it must
    // dispatch on its own or the PR stays unreviewed until the next push.
    var body =
        buildPullRequestPayload("ready_for_review", 21, "org/svc", "sha21", "main")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

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
                false,
                "main"));
  }

  @Test
  void shouldDefaultMissingPrBodyToEmptyDescription() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 12, "a/b", "sha12", "main", false)
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

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
                false,
                "main"));
  }

  @Test
  void shouldSkipAutomaticReviewWhenPrPaused() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(prPauseService.isPaused("owner", "myrepo", 99)).thenReturn(true);

    var body =
        buildPullRequestPayload("synchronize", 99, "owner/myrepo", "headsha123", "main")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // A paused PR must not auto-review on new commits.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreClosedPullRequest() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildPullRequestPayload("closed", 1, "x/y", "sha", "main").getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // Orchestrator should NOT be called for "closed" action
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnorePullRequestWithNullAction() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        "{"
            + "\"action\":null,"
            + "\"pull_request\":{\"number\":1,\"title\":\"t\",\"head\":{\"sha\":\"s\"},\"base\":{\"sha\":\"b\"}},"
            + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":{\"id\":1}"
            + "}";

    var response =
        controller.handleWebhook(
            "sha256=valid", "pull_request", null, DELIVERY, body.getBytes(StandardCharsets.UTF_8));
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreOpenedPullRequestWithMissingFields() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        "{"
            + "\"action\":\"opened\","
            + "\"pull_request\":null,"
            + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":{\"id\":1}"
            + "}";

    var response =
        controller.handleWebhook(
            "sha256=valid", "pull_request", null, DELIVERY, body.getBytes(StandardCharsets.UTF_8));
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldSkipReviewWhenTriggerFilterRejects() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerFilter.skipReason(any()))
        .thenReturn(
            Optional.of(new ReviewTriggerFilter.Skip(ReviewSkipReason.DRAFT, "PR is a draft")));

    var body =
        buildPullRequestPayload("opened", 21, "owner/repo", "sha21", "main")
            .getBytes(StandardCharsets.UTF_8);

    var response =
        controller.handleWebhook("sha256=valid", "pull_request", null, "delivery-skip", body);
    assertEquals(200, response.getStatus());

    // A filtered-out PR must not be reviewed, and the dedup slot stays claimed (it was a
    // deliberate decision, not a rejected dispatch), so a redelivery is ignored too.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(deduplicator, never()).forget(anyString());
  }

  @Test
  void shouldSkipAutomaticReviewWithinRateLimitWindow() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(reviewConfig.autoReviewMinInterval()).thenReturn(Duration.ofHours(1));
    when(autoReviewRateLimiter.isThrottled("owner", "repo", 55)).thenReturn(true);

    // A new push (fresh head SHA) within the window is still skipped.
    var body =
        buildPullRequestPayload("synchronize", 55, "owner/repo", "fresh-sha", "main")
            .getBytes(StandardCharsets.UTF_8);

    var response =
        controller.handleWebhook("sha256=valid", "pull_request", null, "delivery-throttled", body);
    assertEquals(200, response.getStatus());

    // A throttled PR must not be reviewed, and the dedup slot stays claimed (a deliberate
    // decision, not a rejected dispatch), so a redelivery is ignored too.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verify(deduplicator, never()).forget(anyString());
  }

  @Test
  void shouldEmitStructuredSkipEventWhenDraftFilterRejects() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerFilter.skipReason(any()))
        .thenReturn(
            Optional.of(new ReviewTriggerFilter.Skip(ReviewSkipReason.DRAFT, "PR is a draft")));

    var body =
        buildPullRequestPayload("opened", 21, "owner/repo", "sha21", "main")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);

    verify(skipEmitter)
        .recordSkip(eq(ReviewSkipReason.DRAFT), eq("owner"), eq("repo"), eq(21), anyString());
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldEmitStructuredSkipEventWhenPrPaused() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(prPauseService.isPaused("owner", "myrepo", 99)).thenReturn(true);

    var body =
        buildPullRequestPayload("synchronize", 99, "owner/myrepo", "headsha123", "main")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);

    verify(skipEmitter)
        .recordSkip(eq(ReviewSkipReason.PAUSED), eq("owner"), eq("myrepo"), eq(99), anyString());
  }

  @Test
  void shouldEmitStructuredSkipEventWhenRateLimited() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(reviewConfig.autoReviewMinInterval()).thenReturn(Duration.ofHours(1));
    when(autoReviewRateLimiter.isThrottled("owner", "repo", 55)).thenReturn(true);

    var body =
        buildPullRequestPayload("synchronize", 55, "owner/repo", "fresh-sha", "main")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.RATE_LIMITED), eq("owner"), eq("repo"), eq(55), anyString());
  }

  @Test
  void shouldEmitStructuredSkipEventOnDuplicateDelivery() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 7, "owner/repo", "sha7", "main")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, "redelivered-id", body);

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.DUPLICATE_DELIVERY), eq("owner"), eq("repo"), eq(7), anyString());
  }

  @Test
  void shouldNotEmitDuplicateSkipEventForNonReviewRedelivery() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        buildPullRequestPayload("closed", 7, "owner/repo", "sha7", "main")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, "redelivered-id", body);

    verifyNoInteractions(skipEmitter);
  }

  @Test
  void shouldNotEmitDuplicateSkipEventForNonPullRequestRedelivery() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body = "{\"action\":\"created\"}".getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "issue_comment", null, "redelivered-id", body);

    verifyNoInteractions(skipEmitter);
  }

  @Test
  void shouldNotEmitDuplicateSkipEventWhenRedeliveryHasNoAction() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body = "{}".getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, "redelivered-id", body);

    verifyNoInteractions(skipEmitter);
  }

  @Test
  void shouldNotEmitDuplicateSkipEventWhenRedeliveryLacksPullRequest() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        ("{\"action\":\"opened\",\"repository\":{\"full_name\":\"owner/repo\",\"name\":\"repo\","
                + "\"owner\":{\"login\":\"owner\",\"id\":1}}}")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, "redelivered-id", body);

    verifyNoInteractions(skipEmitter);
  }

  @Test
  void shouldNotEmitDuplicateSkipEventWhenRedeliveryLacksRepositoryOwner() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        ("{\"action\":\"opened\",\"pull_request\":{\"number\":7},"
                + "\"repository\":{\"full_name\":\"owner/repo\",\"name\":\"repo\"}}")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, "redelivered-id", body);

    verifyNoInteractions(skipEmitter);
  }

  @Test
  void shouldNotEmitDuplicateSkipEventWhenRedeliveryLacksRepository() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(deduplicator.isDuplicate("redelivered-id")).thenReturn(true);

    var body =
        "{\"action\":\"opened\",\"pull_request\":{\"number\":7}}".getBytes(StandardCharsets.UTF_8);

    var response =
        controller.handleWebhook("sha256=valid", "pull_request", null, "redelivered-id", body);

    assertEquals(200, response.getStatus());
    verifyNoInteractions(skipEmitter);
  }

  @Test
  void shouldEmitStructuredSkipEventWhenDispatchRejected() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(reviewDispatcher.dispatch(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(false);

    var body =
        buildPullRequestPayload("opened", 8, "owner/repo", "sha8", "main")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, "delivery-rejected", body);

    verify(skipEmitter)
        .recordSkip(
            eq(ReviewSkipReason.DISPATCH_REJECTED), eq("owner"), eq("repo"), eq(8), anyString());
    // A rejected dispatch rolls back the dedup slot so a redelivery can retry.
    verify(deduplicator).forget("delivery-rejected");
  }

  @Test
  void shouldNotEmitSkipEventWhenReviewDispatches() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(reviewDispatcher.dispatch(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 8, "owner/repo", "sha8", "main")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);

    verifyNoInteractions(skipEmitter);
  }

  @Test
  void shouldDispatchAutomaticReviewWhenRateLimitWindowOpen() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(autoReviewRateLimiter.isThrottled("owner", "repo", 56)).thenReturn(false);

    var body =
        buildPullRequestPayload("synchronize", 56, "owner/repo", "sha56", "main")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);

    verify(autoReviewRateLimiter).isThrottled("owner", "repo", 56);
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldClearRateLimitAndDispatchReadyForReviewEvenWhenThrottled() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(reviewConfig.autoReviewMinInterval()).thenReturn(Duration.ofHours(1));
    when(autoReviewRateLimiter.isThrottled("org", "svc", 57)).thenReturn(true);

    var body =
        buildPullRequestPayload("ready_for_review", 57, "org/svc", "sha57", "main")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(autoReviewRateLimiter).clearForPr("org", "svc", 57);
    verify(autoReviewRateLimiter, never()).isThrottled("org", "svc", 57);
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldPassParsedDraftAndLabelsToTriggerFilter() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        ("{"
                + "\"action\":\"opened\","
                + "\"pull_request\":{"
                + "\"number\":33,\"title\":\"t\",\"head\":{\"sha\":\"s\"},"
                + "\"base\":{\"sha\":\"b\",\"ref\":\"main\"},\"draft\":true,"
                + "\"labels\":[{\"name\":\"wip\"},{\"name\":\"ai-review\"}]"
                + "},"
                + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
                + "\"installation\":{\"id\":1}"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);

    var captor = ArgumentCaptor.forClass(WebhookPayload.PullRequest.class);
    verify(triggerFilter).skipReason(captor.capture());
    var pr = captor.getValue();
    assertTrue(pr.draft());
    assertEquals(2, pr.labels().size());
    assertEquals("wip", pr.labels().get(0).name());
    // Filter said review (default empty Optional), so dispatch still happens.
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldTriggerManualReviewOnReviewCommand() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "owner", "repo", 77, "", "(manual review)", "", "", "main", 12345L, true));
  }

  @Test
  void shouldTriggerManualReviewEvenWithinRateLimitWindow() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // An explicit /review always runs — the manual path never consults the rate limiter, so
    // even a PR deep inside the throttle window is reviewed on request.
    verify(reviewDispatcher)
        .dispatch(
            new ReviewOrchestrator.ReviewRequest(
                "owner", "repo", 77, "", "(manual review)", "", "", "main", 12345L, true));
    verifyNoInteractions(autoReviewRateLimiter);
  }

  @Test
  void shouldIgnoreUnauthorizedManualReviewTrigger() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("stranger")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized(
            anyString(), anyString(), anyLong(), anyString(), any()))
        .thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 88, "owner/repo", "stranger", "/review", "NONE")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // A user without write access must not be able to spend the operator's API budget.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldSkipManualReviewOnPausedPr() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(prPauseService.isPaused("owner", "repo", 77)).thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // A paused PR neither authorizes nor dispatches the review; it just posts the paused notice.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verifyNoInteractions(manualReviewAuthorizer);
    verify(commentCommandService).notifyPaused(any(CommentCommandService.CommandContext.class));
  }

  @Test
  void shouldRouteNonReviewCommandsToCommandService() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/help")).thenReturn(CommentCommand.HELP);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/help")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    var ctx = org.mockito.ArgumentCaptor.forClass(CommentCommandService.CommandContext.class);
    verify(commentCommandService).handle(ctx.capture());
    assertEquals(CommentCommand.HELP, ctx.getValue().command());
    assertEquals("owner", ctx.getValue().owner());
    assertEquals("repo", ctx.getValue().repo());
    assertEquals(77, ctx.getValue().prNumber());
    assertEquals(12345L, ctx.getValue().installationId());
    // /help must not consult authorization or the review dispatcher.
    verifyNoInteractions(manualReviewAuthorizer);
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldRouteAddDocsCommandToCommandService() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/add-docs")).thenReturn(CommentCommand.ADD_DOCS);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/add-docs")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    var ctx = org.mockito.ArgumentCaptor.forClass(CommentCommandService.CommandContext.class);
    verify(commentCommandService).handle(ctx.capture());
    assertEquals(CommentCommand.ADD_DOCS, ctx.getValue().command());
    assertEquals(77, ctx.getValue().prNumber());
    // The command service (not the controller) does the authorization and the AI work.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldAddEyesReactionOnReviewCommand() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized("owner", "repo", 12345L, "octocat", "OWNER"))
        .thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);

    verify(ackReactionService)
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);
  }

  @Test
  void shouldAddEyesReactionOnNonReviewCommand() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/help")).thenReturn(CommentCommand.HELP);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/help")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);

    verify(ackReactionService)
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);
  }

  @Test
  void shouldAddEyesReactionEvenWhenReviewUnauthorized() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("stranger")).thenReturn(false);
    when(manualReviewAuthorizer.isAuthorized(
            anyString(), anyString(), anyLong(), anyString(), any()))
        .thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 88, "owner/repo", "stranger", "/review", "NONE")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);

    // The ack fires before authorization: the commenter still sees the command was noticed even
    // when it is then rejected.
    verify(ackReactionService)
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldAddEyesReactionOnReviewCommandOnPausedPr() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/review")).thenReturn(CommentCommand.REVIEW);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(prPauseService.isPaused("owner", "repo", 77)).thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 77, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);

    // The ack also precedes the pause gate — the command itself was still seen.
    verify(ackReactionService)
        .addEyes(12345L, "owner", "repo", 1L, AckReactionService.CommentKind.ISSUE);
  }

  @Test
  void shouldNotReactOnConversationalMention() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
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

    controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);

    // A bare conversational mention is not a command — no 👀 ack, only the reply.
    verify(replyDispatcher).dispatch(any(MaintainerReplyService.ReplyTask.class));
    verifyNoInteractions(ackReactionService);
  }

  @Test
  void shouldNotReactOnReviewThreadMention() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
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

    controller.handleWebhook("sha256=valid", "pull_request_review_comment", null, DELIVERY, body);

    // An inline-thread mention is conversational, never a command — no 👀 ack.
    verify(replyDispatcher).dispatch(any(MaintainerReplyService.ReplyTask.class));
    verifyNoInteractions(ackReactionService);
  }

  @Test
  void shouldNotReactWhenCommandInstallationMissing() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/help")).thenReturn(CommentCommand.HELP);
    when(triggerDetector.isBotComment("user")).thenReturn(false);

    var body =
        ("{"
                + "\"action\":\"created\","
                + "\"issue\":{\"number\":1,\"pull_request\":{\"url\":\"u\"}},"
                + "\"comment\":{\"id\":1,\"body\":\"/help\",\"user\":{\"login\":\"user\",\"id\":1}},"
                + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
                + "\"installation\":null"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);

    // Without an installation there is no token to react with.
    verifyNoInteractions(ackReactionService);
  }

  @Test
  void shouldIgnoreEditedIssueComment() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildIssueCommentPayload("edited", 88, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // Editing a comment must not re-dispatch a review, and authorization is never consulted.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verifyNoInteractions(manualReviewAuthorizer);
  }

  @Test
  void shouldIgnoreDeletedIssueComment() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildIssueCommentPayload("deleted", 88, "owner/repo", "octocat", "/review")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // Deleting a comment must not re-dispatch a review.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verifyNoInteractions(manualReviewAuthorizer);
  }

  @Test
  void shouldIgnoreIssueCommentWithoutTrigger() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("Nice PR!")).thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 1, "a/b", "octocat", "Nice PR!")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // Neither the dispatcher nor the command service runs when no command is detected.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verifyNoInteractions(commentCommandService);
  }

  @Test
  void shouldIgnoreBotComment() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.isBotComment("thrillhousebot[bot]")).thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 1, "a/b", "thrillhousebot[bot]", "/review")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // The bot's own comment is dropped before command detection, so nothing runs.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
    verifyNoInteractions(commentCommandService);
  }

  @Test
  void shouldDispatchReplyForBotMentionOnPrComment() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
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

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(replyDispatcher)
        .dispatch(
            new MaintainerReplyService.ReplyTask(
                "owner",
                "repo",
                77,
                12345L,
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
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("@thrillhousebot is this thread-safe?"))
        .thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot is this thread-safe?"))
        .thenReturn(true);
    when(prPauseService.isPaused("owner", "repo", 77)).thenReturn(true);

    var body =
        buildIssueCommentPayload(
                "created", 77, "owner/repo", "octocat", "@thrillhousebot is this thread-safe?")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // /pause silences the bot, so a mention must not spend a paid conversational reply.
    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldNotDispatchReplyForMentionWhenRepliesDisabled() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(reviewConfig.conversationalRepliesEnabled()).thenReturn(false);
    when(triggerDetector.detectCommand("@thrillhousebot hi")).thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 5, "owner/repo", "octocat", "@thrillhousebot hi")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldDispatchReplyForReviewCommentMentionInReply() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
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

    var response =
        controller.handleWebhook(
            "sha256=valid", "pull_request_review_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(replyDispatcher)
        .dispatch(
            new MaintainerReplyService.ReplyTask(
                "owner",
                "repo",
                42,
                12345L,
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
  void shouldDispatchReplyForReviewCommentMentionOnNewLine() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot thoughts?")).thenReturn(true);
    when(replyDispatcher.dispatch(any(MaintainerReplyService.ReplyTask.class))).thenReturn(true);

    // A brand-new inline comment (no in_reply_to_id) that mentions the bot replies under itself.
    var body =
        buildReviewCommentPayload(
                "created", 42, "owner/repo", "octocat", "@thrillhousebot thoughts?", null, 2000L)
            .getBytes(StandardCharsets.UTF_8);

    var response =
        controller.handleWebhook(
            "sha256=valid", "pull_request_review_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(replyDispatcher)
        .dispatch(
            new MaintainerReplyService.ReplyTask(
                "owner",
                "repo",
                42,
                12345L,
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
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot why is this flagged?"))
        .thenReturn(true);
    when(prPauseService.isPaused("owner", "repo", 42)).thenReturn(true);

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

    var response =
        controller.handleWebhook(
            "sha256=valid", "pull_request_review_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // /pause silences the bot, so a review-thread reply must not spend a paid conversational reply.
    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @ParameterizedTest
  @EnumSource(IgnoredReviewComment.class)
  void shouldIgnoreReviewCommentWithoutDispatching(IgnoredReviewComment scenario) {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
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

    var response =
        controller.handleWebhook(
            "sha256=valid", "pull_request_review_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

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
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildReviewCommentPayload("edited", 42, "owner/repo", "octocat", "edited text", 99L, 5000L)
            .getBytes(StandardCharsets.UTF_8);

    var response =
        controller.handleWebhook(
            "sha256=valid", "pull_request_review_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // Editing a review comment must not re-trigger a reply.
    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
    verifyNoInteractions(triggerDetector);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("reviewCommentsWithMissingFields")
  void shouldIgnoreReviewCommentWithMissingFields(String name, String body) {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    // A present author reaches the bot-loop guard; the null-author/null-comment cases short-circuit
    // before it, so this stub is simply unused for them.
    when(triggerDetector.isBotComment(anyString())).thenReturn(false);

    var response =
        controller.handleWebhook(
            "sha256=valid",
            "pull_request_review_comment",
            null,
            DELIVERY,
            body.getBytes(StandardCharsets.UTF_8));
    assertEquals(200, response.getStatus(), name);

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
                + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":2}},"
                + "\"installation\":{\"id\":12345}"
                + "}"),
        arguments(
            "missing pull_request",
            "{"
                + "\"action\":\"created\","
                + "\"comment\":{\"id\":1,\"body\":\"reply\",\"author_association\":\"OWNER\",\"in_reply_to_id\":99,\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"pull_request\":null,"
                + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":2}},"
                + "\"installation\":{\"id\":12345}"
                + "}"),
        arguments(
            "missing comment",
            "{"
                + "\"action\":\"created\","
                + "\"comment\":null,"
                + "\"pull_request\":{\"number\":42,\"title\":\"T\",\"head\":{\"sha\":\"h\"},\"base\":{\"sha\":\"b\"},\"body\":\"d\"},"
                + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":2}},"
                + "\"installation\":{\"id\":12345}"
                + "}"));
  }

  @Test
  void shouldIgnoreReviewCommentWithMissingRepository() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
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

    var response =
        controller.handleWebhook(
            "sha256=valid", "pull_request_review_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldIgnoreReviewCommentWithMissingInstallation() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    // pull_request and repository present, but installation missing — exercises the third
    // operand of the compound guard.
    var body =
        ("{"
                + "\"action\":\"created\","
                + "\"comment\":{\"id\":1,\"body\":\"reply\",\"author_association\":\"OWNER\",\"in_reply_to_id\":99,\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"pull_request\":{\"number\":42,\"title\":\"T\",\"head\":{\"sha\":\"h\"},\"base\":{\"sha\":\"b\"},\"body\":\"d\"},"
                + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":2}},"
                + "\"installation\":null"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    var response =
        controller.handleWebhook(
            "sha256=valid", "pull_request_review_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldIgnoreMentionWithMissingInstallation() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("@thrillhousebot hi")).thenReturn(CommentCommand.NONE);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.containsBotMention("@thrillhousebot hi")).thenReturn(true);

    // repository present but installation missing — exercises the mention guard's second operand.
    var body =
        ("{"
                + "\"action\":\"created\","
                + "\"issue\":{\"number\":5,\"title\":\"T\",\"body\":\"d\",\"pull_request\":{\"url\":\"u\"}},"
                + "\"comment\":{\"id\":1,\"body\":\"@thrillhousebot hi\",\"author_association\":\"OWNER\",\"user\":{\"login\":\"octocat\",\"id\":1}},"
                + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":2}},"
                + "\"installation\":null"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldIgnoreMentionWithMissingRepository() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
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

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(replyDispatcher, never()).dispatch(any(MaintainerReplyService.ReplyTask.class));
  }

  @Test
  void shouldHandleInstallationEvent() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body = buildInstallationPayload("created", 12345L).getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "installation", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldHandleInstallationRepositoriesEvent() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body = buildInstallationPayload("added", 42L).getBytes(StandardCharsets.UTF_8);

    var response =
        controller.handleWebhook("sha256=valid", "installation_repositories", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldHandleInstallationEventWithoutInstallationId() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    // Payload with action but no installation field
    var body = "{\"action\":\"created\"}".getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "installation", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldHandlePingEvent() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body = "{}".getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "ping", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldHandleUnknownEventType() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 1, "a/b", "sha", "main").getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "push", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // Orchestrator should NOT be called for unknown event types
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldCatchRuntimeExceptionInRouteEvent() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    doThrow(new RuntimeException("simulated failure"))
        .when(reviewDispatcher)
        .dispatch(any(ReviewOrchestrator.ReviewRequest.class));

    var body =
        buildPullRequestPayload("opened", 1, "a/b", "sha", "main").getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "pull_request", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // The async routeEvent should have caught the RuntimeException and logged it
    verify(reviewDispatcher).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldForgetDeliveryIdWhenDispatchIsRejected() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    // A saturated executor makes dispatch return false (it does not throw — the dispatcher
    // swallows RejectedExecutionException), which is the real scenario.
    when(reviewDispatcher.dispatch(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(false);

    var body =
        buildPullRequestPayload("opened", 1, "a/b", "sha", "main").getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, "delivery-abc", body);

    // The rejected dispatch must roll back the dedup slot so manual redelivery can retry.
    verify(deduplicator).forget("delivery-abc");
  }

  @Test
  void shouldForgetDeliveryIdWhenDispatchThrows() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    doThrow(new RuntimeException("unexpected failure"))
        .when(reviewDispatcher)
        .dispatch(any(ReviewOrchestrator.ReviewRequest.class));

    var body =
        buildPullRequestPayload("opened", 1, "a/b", "sha", "main").getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, "delivery-throw", body);

    // An unexpected RuntimeException must also roll back the dedup slot for the same reason.
    verify(deduplicator).forget("delivery-throw");
  }

  @Test
  void shouldNotForgetDeliveryIdWhenDispatchSucceeds() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(reviewDispatcher.dispatch(any(ReviewOrchestrator.ReviewRequest.class))).thenReturn(true);

    var body =
        buildPullRequestPayload("opened", 1, "a/b", "sha", "main").getBytes(StandardCharsets.UTF_8);

    controller.handleWebhook("sha256=valid", "pull_request", null, "delivery-ok", body);

    // A successful dispatch keeps the dedup slot so redeliveries stay suppressed.
    verify(deduplicator, never()).forget(anyString());
  }

  @Test
  void shouldIgnoreIssueCommentWhenIssueMissing() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        "{"
            + "\"action\":\"created\","
            + "\"issue\":null,"
            + "\"comment\":{\"id\":1,\"body\":\"/review\",\"user\":{\"login\":\"user\",\"id\":1}},"
            + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":{\"id\":12345}"
            + "}";

    var response =
        controller.handleWebhook(
            "sha256=valid", "issue_comment", null, DELIVERY, body.getBytes(StandardCharsets.UTF_8));
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreIssueCommentOnPlainIssue() {
    // An issue comment without pull_request field (plain issue, not PR)
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.isBotComment("user")).thenReturn(false);

    var body =
        "{"
            + "\"action\":\"created\","
            + "\"issue\":{\"number\":1,\"title\":\"Issue\",\"body\":\"desc\"},"
            + "\"comment\":{\"id\":1,\"body\":\"/review\",\"user\":{\"login\":\"user\",\"id\":1}},"
            + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":{\"id\":12345}"
            + "}";

    var response =
        controller.handleWebhook(
            "sha256=valid", "issue_comment", null, DELIVERY, body.getBytes(StandardCharsets.UTF_8));
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreIssueCommentWithMissingAuthor() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);

    var body =
        "{"
            + "\"action\":\"created\","
            + "\"issue\":{\"number\":1,\"pull_request\":{\"url\":\"u\"}},"
            + "\"comment\":{\"id\":1,\"body\":\"/review\",\"user\":null},"
            + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":{\"id\":1}"
            + "}";

    var response =
        controller.handleWebhook(
            "sha256=valid", "issue_comment", null, DELIVERY, body.getBytes(StandardCharsets.UTF_8));
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreReviewTriggerWithMissingRepository() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
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

    var response =
        controller.handleWebhook(
            "sha256=valid", "issue_comment", null, DELIVERY, body.getBytes(StandardCharsets.UTF_8));
    assertEquals(200, response.getStatus());

    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreCommandWhenInstallationMissing() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.detectCommand("/help")).thenReturn(CommentCommand.HELP);
    when(triggerDetector.isBotComment("user")).thenReturn(false);

    var body =
        "{"
            + "\"action\":\"created\","
            + "\"issue\":{\"number\":1,\"pull_request\":{\"url\":\"u\"}},"
            + "\"comment\":{\"id\":1,\"body\":\"/help\",\"user\":{\"login\":\"user\",\"id\":1}},"
            + "\"repository\":{\"full_name\":\"a/b\",\"name\":\"b\",\"default_branch\":\"main\",\"owner\":{\"login\":\"a\",\"id\":1}},"
            + "\"installation\":null"
            + "}";

    var response =
        controller.handleWebhook(
            "sha256=valid", "issue_comment", null, DELIVERY, body.getBytes(StandardCharsets.UTF_8));
    assertEquals(200, response.getStatus());

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
