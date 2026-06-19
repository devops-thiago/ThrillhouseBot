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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ReviewThreadServiceTest {

  @Mock private GitHubGraphQLClient graphQLClient;

  private final ObjectMapper mapper = new ObjectMapper();

  private ReviewThreadService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new ReviewThreadService(graphQLClient);
  }

  private void stubResponse(String json) throws JsonProcessingException {
    when(graphQLClient.execute(anyString(), any())).thenReturn(mapper.readTree(json));
  }

  @Test
  void shouldMapThreadsByRootCommentId() throws Exception {
    stubResponse(
        """
        {"data": {"repository": {"pullRequest": {"reviewThreads": {"nodes": [
          {"id": "T1", "isResolved": false, "comments": {"nodes": [{"databaseId": 100}]}},
          {"id": "T2", "isResolved": true, "comments": {"nodes": [{"databaseId": 200}]}}
        ]}}}}}
        """);

    var threads = service.threadsByRootComment("auth", "owner", "repo", 7);

    assertEquals(2, threads.size());
    assertEquals(new ReviewThreadService.ThreadRef("T1", false), threads.get(100L));
    assertEquals(new ReviewThreadService.ThreadRef("T2", true), threads.get(200L));
  }

  @Test
  void shouldWalkAllPagesWhenThreadsSpanMoreThanOnePage() throws Exception {
    var page1 =
        mapper.readTree(
            """
            {"data": {"repository": {"pullRequest": {"reviewThreads": {
              "nodes": [{"id": "T1", "isResolved": false, "comments": {"nodes": [{"databaseId": 100}]}}],
              "pageInfo": {"hasNextPage": true, "endCursor": "CURSOR1"}
            }}}}}
            """);
    var page2 =
        mapper.readTree(
            """
            {"data": {"repository": {"pullRequest": {"reviewThreads": {
              "nodes": [{"id": "T2", "isResolved": true, "comments": {"nodes": [{"databaseId": 200}]}}],
              "pageInfo": {"hasNextPage": false, "endCursor": null}
            }}}}}
            """);
    when(graphQLClient.execute(anyString(), any())).thenReturn(page1, page2);

    var threads = service.threadsByRootComment("auth", "owner", "repo", 7);

    assertEquals(2, threads.size());
    assertEquals("T1", threads.get(100L).id());
    assertEquals("T2", threads.get(200L).id());
    // Page 1 carries no cursor; page 2 carries the cursor returned by page 1.
    var captor = ArgumentCaptor.forClass(GitHubGraphQLClient.GraphQLRequest.class);
    verify(graphQLClient, times(2)).execute(eq("auth"), captor.capture());
    assertNull(captor.getAllValues().get(0).variables().get("after"));
    assertEquals("CURSOR1", captor.getAllValues().get(1).variables().get("after"));
  }

  @Test
  void shouldSkipThreadsWithMissingIdsOrComments() throws Exception {
    stubResponse(
        """
        {"data": {"repository": {"pullRequest": {"reviewThreads": {"nodes": [
          {"id": "T1", "isResolved": false, "comments": {"nodes": []}},
          {"isResolved": false, "comments": {"nodes": [{"databaseId": 300}]}},
          {"id": "T3", "isResolved": false, "comments": {"nodes": [{"databaseId": 400}]}}
        ]}}}}}
        """);

    var threads = service.threadsByRootComment("auth", "owner", "repo", 7);

    assertEquals(1, threads.size());
    assertEquals("T3", threads.get(400L).id());
  }

  @Test
  void shouldReturnEmptyMapForMalformedResponse() throws Exception {
    stubResponse("{\"errors\": [{\"message\": \"boom\"}]}");

    assertTrue(service.threadsByRootComment("auth", "owner", "repo", 7).isEmpty());
  }

  @Test
  void shouldSendOwnerRepoAndNumberAsVariables() throws Exception {
    stubResponse("{}");

    service.threadsByRootComment("auth", "owner", "repo", 7);

    var captor = ArgumentCaptor.forClass(GitHubGraphQLClient.GraphQLRequest.class);
    verify(graphQLClient).execute(eq("auth"), captor.capture());
    assertTrue(captor.getValue().query().contains("reviewThreads"));
    assertEquals("owner", captor.getValue().variables().get("owner"));
    assertEquals("repo", captor.getValue().variables().get("name"));
    assertEquals(7, captor.getValue().variables().get("number"));
  }

  @Test
  void graphQLRequestShouldCopyVariablesDefensively() {
    var variables = new java.util.HashMap<String, Object>();
    variables.put("owner", "o");

    var request = new GitHubGraphQLClient.GraphQLRequest("query", variables);
    variables.clear();

    assertEquals("o", request.variables().get("owner"));
    assertTrue(new GitHubGraphQLClient.GraphQLRequest("query", null).variables().isEmpty());
  }

  @Test
  void resolveShouldReturnTrueWhenGitHubConfirms() throws Exception {
    stubResponse(
        """
        {"data": {"resolveReviewThread": {"thread": {"isResolved": true}}}}
        """);

    assertTrue(service.resolve("auth", "T1"));
  }

  @Test
  void resolveShouldReturnFalseWhenConfirmationMissing() throws Exception {
    stubResponse("{\"data\": {}}");

    assertFalse(service.resolve("auth", "T1"));
  }
}
