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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReactionClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AckReactionServiceTest {

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  @Mock private GitHubAuthClient authClient;

  @Mock private GitHubReactionClient reactionClient;

  private AckReactionService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.ackReactionTimeout()).thenReturn(Duration.ofSeconds(2));
    when(authClient.getAuthHeader(42L)).thenReturn("token abc");
    service = new AckReactionService(config, authClient, reactionClient);
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
  }

  @Test
  void shouldReactEyesOnIssueComment() {
    service.addEyes(42L, "owner", "repo", 1001L, AckReactionService.CommentKind.ISSUE);

    var request = ArgumentCaptor.forClass(GitHubReactionClient.CreateReactionRequest.class);
    verify(reactionClient)
        .createIssueCommentReaction(
            eq("token abc"),
            eq("application/vnd.github+json"),
            eq("owner"),
            eq("repo"),
            eq(1001L),
            request.capture());
    assertEquals("eyes", request.getValue().content());
    verify(reactionClient, never())
        .createReviewCommentReaction(
            anyString(), anyString(), anyString(), anyString(), anyLong(), any());
  }

  @Test
  void shouldReactEyesOnReviewComment() {
    service.addEyes(42L, "owner", "repo", 2002L, AckReactionService.CommentKind.REVIEW);

    var request = ArgumentCaptor.forClass(GitHubReactionClient.CreateReactionRequest.class);
    verify(reactionClient)
        .createReviewCommentReaction(
            eq("token abc"),
            eq("application/vnd.github+json"),
            eq("owner"),
            eq("repo"),
            eq(2002L),
            request.capture());
    assertEquals("eyes", request.getValue().content());
    verify(reactionClient, never())
        .createIssueCommentReaction(
            anyString(), anyString(), anyString(), anyString(), anyLong(), any());
  }

  @Test
  void shouldSwallowReactionFailure() {
    doThrow(new RuntimeException("403 Forbidden"))
        .when(reactionClient)
        .createIssueCommentReaction(
            anyString(), anyString(), anyString(), anyString(), anyLong(), any());

    // Best-effort contract: a failed reaction must never propagate and block the command.
    assertDoesNotThrow(
        () -> service.addEyes(42L, "owner", "repo", 1001L, AckReactionService.CommentKind.ISSUE));
  }

  @Test
  void shouldSwallowAuthFailureWithoutCallingApi() {
    when(authClient.getAuthHeader(42L)).thenThrow(new RuntimeException("token exchange failed"));

    assertDoesNotThrow(
        () -> service.addEyes(42L, "owner", "repo", 1001L, AckReactionService.CommentKind.REVIEW));

    verifyNoInteractions(reactionClient);
  }

  @Test
  void shouldStopWaitingWhenReactionExceedsTimeout() {
    when(reviewConfig.ackReactionTimeout()).thenReturn(Duration.ofMillis(50));
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              // Simulates a degraded GitHub: the POST hangs far past the ack timeout.
              release.await(5, TimeUnit.SECONDS);
              return null;
            })
        .when(reactionClient)
        .createIssueCommentReaction(
            anyString(), anyString(), anyString(), anyString(), anyLong(), any());

    var start = System.nanoTime();
    service.addEyes(42L, "owner", "repo", 1001L, AckReactionService.CommentKind.ISSUE);
    var elapsed = Duration.ofNanos(System.nanoTime() - start);

    release.countDown();
    // The ack thread must be released at the timeout, not held for the full slow call.
    assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "waited " + elapsed);
  }

  @Test
  void shouldPropagateFatalError() {
    doThrow(new OutOfMemoryError("simulated"))
        .when(reactionClient)
        .createIssueCommentReaction(
            anyString(), anyString(), anyString(), anyString(), anyLong(), any());

    // A fatal Error must surface as it would have on the calling thread, not be masked.
    assertThrows(
        OutOfMemoryError.class,
        () -> service.addEyes(42L, "owner", "repo", 1001L, AckReactionService.CommentKind.ISSUE));
  }

  @Test
  void shouldRestoreInterruptFlagWhenInterrupted() {
    Thread.currentThread().interrupt();

    assertDoesNotThrow(
        () -> service.addEyes(42L, "owner", "repo", 1001L, AckReactionService.CommentKind.ISSUE));

    // The interrupt is swallowed for the caller but must stay visible on the thread.
    assertTrue(Thread.interrupted());
  }
}
