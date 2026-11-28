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
package dev.thiagogonzaga.thrillhousebot.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A reaction GitHub rejects for its credential is sent once more with the replacement token. */
class GitHubReactionClientTest {

  private static final String DEAD = "Bearer expired-token";
  private static final String FRESH = "Bearer minted-token";
  private static final GitHubReactionClient.CreateReactionRequest EYES =
      new GitHubReactionClient.CreateReactionRequest("eyes");

  private final GitHubReactionClient client = mock(GitHubReactionClient.class);

  @BeforeEach
  void bindFresh() {
    GitHubTokenRefresh.SHARED.bind(_ -> Optional.of(FRESH));
  }

  @AfterEach
  void unbind() {
    GitHubTokenRefresh.SHARED.bind(null);
  }

  private static WebApplicationException badCredentials() {
    return new WebApplicationException(
        Response.status(401).entity("{\"message\":\"Bad credentials\"}").build());
  }

  @Test
  void aConversationCommentReactionIsRepeatedWithAFreshCredential() {
    doCallRealMethod()
        .when(client)
        .createIssueCommentReaction(
            anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    doThrow(badCredentials())
        .when(client)
        .createIssueCommentReactionOnce(DEAD, "json", "o", "r", 5L, EYES);

    client.createIssueCommentReaction(DEAD, "json", "o", "r", 5L, EYES);

    verify(client).createIssueCommentReactionOnce(FRESH, "json", "o", "r", 5L, EYES);
    verify(client, times(2))
        .createIssueCommentReactionOnce(
            anyString(), anyString(), anyString(), anyString(), anyLong(), any());
  }

  @Test
  void anInlineCommentReactionIsRepeatedWithAFreshCredential() {
    doCallRealMethod()
        .when(client)
        .createReviewCommentReaction(
            anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    doThrow(badCredentials())
        .when(client)
        .createReviewCommentReactionOnce(DEAD, "json", "o", "r", 5L, EYES);

    client.createReviewCommentReaction(DEAD, "json", "o", "r", 5L, EYES);

    verify(client).createReviewCommentReactionOnce(FRESH, "json", "o", "r", 5L, EYES);
    verify(client, times(2))
        .createReviewCommentReactionOnce(
            anyString(), anyString(), anyString(), anyString(), anyLong(), any());
  }
}
