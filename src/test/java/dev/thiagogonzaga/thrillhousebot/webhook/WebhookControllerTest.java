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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.ReviewDispatcher;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class WebhookControllerTest {

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.GitHubConfig githubConfig;

  @Mock private WebhookVerifier verifier;

  @Mock private TriggerDetector triggerDetector;

  @Mock private ReviewDispatcher reviewDispatcher;

  @Mock private WebhookDeduplicator deduplicator;

  private final ObjectMapper mapper = new ObjectMapper();

  /** A non-null delivery id; the mocked deduplicator reports it unseen unless a test overrides. */
  private static final String DELIVERY = "00000000-0000-0000-0000-000000000001";

  private WebhookController controller;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.webhookSecret()).thenReturn("test-secret");
    controller =
        new WebhookController(
            config, verifier, triggerDetector, reviewDispatcher, deduplicator, mapper);
  }

  // ── handleWebhook tests ────────────────────────────────────────────────

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

  // ── delivery dedup tests ───────────────────────────────────────────────

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

  // ── pull_request routing tests ─────────────────────────────────────────

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
                false));
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
                false));
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
                false));
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
                "a", "b", 12, "sha12", "Test PR title", "", "basesha456", "main", 12345L, false));
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

  // ── issue_comment routing tests ────────────────────────────────────────

  @Test
  void shouldTriggerManualReviewOnReviewCommand() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.isReviewTrigger("/review")).thenReturn(true);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);
    when(triggerDetector.isAuthorizedToTrigger("OWNER")).thenReturn(true);

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
  void shouldIgnoreUnauthorizedManualReviewTrigger() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.isReviewTrigger("/review")).thenReturn(true);
    when(triggerDetector.isBotComment("stranger")).thenReturn(false);
    when(triggerDetector.isAuthorizedToTrigger("NONE")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 88, "owner/repo", "stranger", "/review", "NONE")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // A non-collaborator must not be able to spend the operator's API budget.
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreIssueCommentWithoutTrigger() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.isReviewTrigger("Nice PR!")).thenReturn(false);
    when(triggerDetector.isBotComment("octocat")).thenReturn(false);

    var body =
        buildIssueCommentPayload("created", 1, "a/b", "octocat", "Nice PR!")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // Orchestrator should NOT be called when trigger not detected
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  @Test
  void shouldIgnoreBotComment() {
    when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
    when(triggerDetector.isReviewTrigger("/review")).thenReturn(true);
    when(triggerDetector.isBotComment("thrillhousebot[bot]")).thenReturn(true);

    var body =
        buildIssueCommentPayload("created", 1, "a/b", "thrillhousebot[bot]", "/review")
            .getBytes(StandardCharsets.UTF_8);

    var response = controller.handleWebhook("sha256=valid", "issue_comment", null, DELIVERY, body);
    assertEquals(200, response.getStatus());

    // Orchestrator should NOT be called for bot comments
    verify(reviewDispatcher, never()).dispatch(any(ReviewOrchestrator.ReviewRequest.class));
  }

  // ── installation / ping event tests ─────────────────────────────────────

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

  // ── edge case tests ────────────────────────────────────────────────────

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

  // ── helper methods ─────────────────────────────────────────────────────

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
