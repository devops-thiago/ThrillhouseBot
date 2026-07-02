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

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReactionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AckReactionServiceTest {

  @Mock private GitHubAuthClient authClient;

  @Mock private GitHubReactionClient reactionClient;

  private AckReactionService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(authClient.getAuthHeader(42L)).thenReturn("token abc");
    service = new AckReactionService(authClient, reactionClient);
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
}
