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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommentVisibilityServiceTest {

  private final GitHubGraphQLClient graphQLClient = mock(GitHubGraphQLClient.class);
  private final List<Duration> waits = new ArrayList<>();
  private final CommentVisibilityService service =
      new CommentVisibilityService(
          graphQLClient, new GitHubWriteRetry(waits::add, () -> Instant.EPOCH));

  @Test
  void listsEveryPageAndSpellsABotAsTheRestApiDoes() {
    var first =
        page(
            """
            {"id": "C1", "body": "one", "isMinimized": true, "createdAt": "2026-01-01T00:00:00Z",
             "author": {"__typename": "Bot", "login": "thrillhousebot"}}
            """,
            true);
    var second =
        page(
            """
            {"id": "C2", "body": "two", "isMinimized": false, "createdAt": "2026-01-02T00:00:00Z",
             "author": {"__typename": "User", "login": "octocat"}}
            """,
            false);
    when(graphQLClient.execute(anyString(), any())).thenReturn(first, second);

    var comments = service.comments("auth", "owner", "repo", 7);

    assertEquals(
        List.of("thrillhousebot[bot]", "octocat"),
        comments.stream().map(CommentVisibilityService.Entry::login).toList());
    assertEquals(
        new CommentVisibilityService.Entry(
            "C1", "one", "thrillhousebot[bot]", Instant.parse("2026-01-01T00:00:00Z"), true, ""),
        comments.get(0));
    verify(graphQLClient, times(2)).execute(anyString(), any());
  }

  @Test
  void raisesGitHubsRefusalOfAListing() {
    when(graphQLClient.execute(anyString(), any()))
        .thenReturn(json("{\"errors\": [{\"message\": \"Something went wrong\"}]}"));

    var refusal =
        assertThrows(
            GitHubGraphQLException.class, () -> service.reviews("auth", "owner", "repo", 7));

    assertEquals("Something went wrong", refusal.refusal());
  }

  @Test
  void minimizesAnEntry() {
    when(graphQLClient.executeOnce(anyString(), any()))
        .thenReturn(
            json("{\"data\":{\"minimizeComment\":{\"minimizedComment\":{\"isMinimized\":true}}}}"));

    service.minimize("auth", "C1");

    verify(graphQLClient).executeOnce(anyString(), any());
  }

  @Test
  void raisesGitHubsRefusalToMinimize() {
    when(graphQLClient.executeOnce(anyString(), any()))
        .thenReturn(
            json("{\"errors\": [{\"message\": \"Resource not accessible by integration\"}]}"));

    var refusal = assertThrows(GitHubGraphQLException.class, () -> service.minimize("auth", "C1"));

    assertEquals("Resource not accessible by integration", refusal.refusal());
  }

  @Test
  void stopsAtThePageBoundAndKeepsASuffixedBotLogin() {
    var page =
        page(
            """
            {"id": "C1", "createdAt": "2026-01-01T00:00:00Z",
             "author": {"__typename": "Bot", "login": "thrillhousebot[bot]"}}
            """,
            true);
    when(graphQLClient.execute(anyString(), any())).thenReturn(page);

    var comments = service.comments("auth", "owner", "repo", 7);

    assertEquals(20, comments.size());
    assertEquals("thrillhousebot[bot]", comments.get(0).login());
  }

  @Test
  void listsNothingWhenGitHubReturnsNeitherDataNorErrors() {
    when(graphQLClient.execute(anyString(), any())).thenReturn(json("{}"));

    assertEquals(List.of(), service.comments("auth", "owner", "repo", 7));
  }

  @Test
  void repeatsAThrottledMinimize() {
    when(graphQLClient.executeOnce(anyString(), any()))
        .thenReturn(
            json("{\"errors\": [{\"type\": \"RATE_LIMITED\", \"message\": \"slow down\"}]}"),
            json("{\"data\":{\"minimizeComment\":{\"minimizedComment\":{\"isMinimized\":true}}}}"));

    service.minimize("auth", "C1");

    verify(graphQLClient, times(2)).executeOnce(anyString(), any());
  }

  @Test
  void raisesGitHubsRefusalOnceTheThrottleRetriesAreSpent() {
    when(graphQLClient.executeOnce(anyString(), any()))
        .thenReturn(
            json("{\"errors\": [{\"type\": \"RATE_LIMITED\", \"message\": \"slow down\"}]}"));

    var refusal = assertThrows(GitHubGraphQLException.class, () -> service.minimize("auth", "C1"));

    assertEquals("slow down", refusal.refusal());
    verify(graphQLClient, times(GitHubWriteRetry.MAX_ATTEMPTS)).executeOnce(anyString(), any());
  }

  @Test
  void listsReviewsWithTheirState() {
    when(graphQLClient.execute(anyString(), any()))
        .thenReturn(
            json(
                "{\"data\":{\"repository\":{\"pullRequest\":{\"reviews\":{\"nodes\":[{\"id\":\"R1\","
                    + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"state\":\"CHANGES_REQUESTED\"}]}}}}}"));

    var reviews = new CommentVisibilityService(graphQLClient).reviews("auth", "owner", "repo", 7);

    assertEquals("CHANGES_REQUESTED", reviews.get(0).state());
  }

  @Test
  void passesOnAnHttpFailure() {
    when(graphQLClient.executeOnce(anyString(), any())).thenThrow(new WebApplicationException(404));

    assertThrows(WebApplicationException.class, () -> service.minimize("auth", "C1"));
  }

  private static JsonNode page(String node, boolean more) {
    return json(
        "{\"data\":{\"repository\":{\"pullRequest\":{\"comments\":{\"nodes\":["
            + node
            + "],\"pageInfo\":{\"hasNextPage\":"
            + more
            + ",\"endCursor\":\"NEXT\"}}}}}}");
  }

  private static JsonNode json(String text) {
    try {
      return new ObjectMapper().readTree(text);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
