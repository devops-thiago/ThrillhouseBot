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
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewThreadServiceTest {

  @Mock private GitHubGraphQLClient graphQLClient;

  private final ObjectMapper mapper = new ObjectMapper();

  private final List<Duration> waits = new ArrayList<>();

  private ReviewThreadService service;

  @BeforeEach
  void setUp() {
    service =
        new ReviewThreadService(
            graphQLClient, new GitHubWriteRetry(waits::add, () -> Instant.EPOCH));
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
  void shouldStopAtThePageBoundWhenGitHubKeepsReportingMorePages() throws Exception {
    // hasNextPage never goes false, so only the page bound can stop the walk.
    stubResponse(
        """
        {"data": {"repository": {"pullRequest": {"reviewThreads": {
          "nodes": [{"id": "T", "isResolved": false, "comments": {"nodes": [{"databaseId": 1}]}}],
          "pageInfo": {"hasNextPage": true, "endCursor": "C"}
        }}}}}
        """);

    service.threadsByRootComment("auth", "owner", "repo", 7);

    verify(graphQLClient, times(20)).execute(anyString(), any());
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

  // #1434: a batched round attaches each thread to its pending review through GraphQL.
  @Test
  void addPendingReviewThreadSendsTheLineAnchoredCommentAsMutationInput() throws Exception {
    when(graphQLClient.executeOnce(anyString(), any()))
        .thenReturn(
            mapper.readTree(
                "{\"data\":{\"addPullRequestReviewThread\":{\"thread\":{\"id\":\"PRRT_1\"}}}}"));

    var threadId =
        service.addPendingReviewThread(
            "auth",
            "owner",
            "repo",
            7,
            "PRR_9",
            new GitHubReviewClient.CreatePullRequestCommentRequest(
                "sha", "body", "src/A.java", 12, "RIGHT", 10, "RIGHT"));

    assertEquals("PRRT_1", threadId);
    var request = ArgumentCaptor.forClass(GitHubGraphQLClient.GraphQLRequest.class);
    verify(graphQLClient).executeOnce(eq("auth"), request.capture());
    var variables = request.getValue().variables();
    assertTrue(request.getValue().query().contains("addPullRequestReviewThread"));
    assertEquals("PRR_9", variables.get("reviewId"));
    assertEquals("body", variables.get("body"));
    assertEquals("src/A.java", variables.get("path"));
    assertEquals(12, variables.get("line"));
    assertEquals("RIGHT", variables.get("side"));
    assertEquals(10, variables.get("startLine"));
    assertEquals("RIGHT", variables.get("startSide"));
    assertFalse(variables.containsKey("subjectType"));
  }

  @Test
  void addPendingReviewThreadSendsAFileLevelCommentWithNoLine() throws Exception {
    when(graphQLClient.executeOnce(anyString(), any()))
        .thenReturn(
            mapper.readTree(
                "{\"data\":{\"addPullRequestReviewThread\":{\"thread\":{\"id\":\"PRRT_2\"}}}}"));

    service.addPendingReviewThread(
        "auth",
        "owner",
        "repo",
        7,
        "PRR_9",
        GitHubReviewClient.CreatePullRequestCommentRequest.onFile("sha", "body", "src/A.java"));

    var request = ArgumentCaptor.forClass(GitHubGraphQLClient.GraphQLRequest.class);
    verify(graphQLClient).executeOnce(eq("auth"), request.capture());
    var variables = request.getValue().variables();
    assertEquals("FILE", variables.get("subjectType"));
    assertFalse(variables.containsKey("line"));
    assertFalse(variables.containsKey("side"));
  }

  @Test
  void addPendingReviewThreadOmitsTheRangeOfASingleLineComment() throws Exception {
    when(graphQLClient.executeOnce(anyString(), any()))
        .thenReturn(
            mapper.readTree(
                "{\"data\":{\"addPullRequestReviewThread\":{\"thread\":{\"id\":\"PRRT_3\"}}}}"));

    service.addPendingReviewThread(
        "auth",
        "owner",
        "repo",
        7,
        "PRR_9",
        new GitHubReviewClient.CreatePullRequestCommentRequest(
            "sha", "body", "src/A.java", 12, "LEFT", null, null));

    var request = ArgumentCaptor.forClass(GitHubGraphQLClient.GraphQLRequest.class);
    verify(graphQLClient).executeOnce(eq("auth"), request.capture());
    assertEquals("LEFT", request.getValue().variables().get("side"));
    assertFalse(request.getValue().variables().containsKey("startLine"));
    assertFalse(request.getValue().variables().containsKey("startSide"));
  }

  @Test
  void addPendingReviewThreadRaisesGitHubsErrorsWhenNoThreadCameBack() throws Exception {
    when(graphQLClient.executeOnce(anyString(), any()))
        .thenReturn(
            mapper.readTree(
                "{\"data\":{\"addPullRequestReviewThread\":null},"
                    + "\"errors\":[{\"type\":\"UNPROCESSABLE\","
                    + "\"message\":\"Line could not be resolved\"}]}"));
    var comment =
        new GitHubReviewClient.CreatePullRequestCommentRequest(
            "sha", "body", "src/A.java", 12, "RIGHT", null, null);

    var failure =
        assertThrows(
            GitHubGraphQLException.class,
            () -> service.addPendingReviewThread("auth", "owner", "repo", 7, "PRR_9", comment));

    assertEquals("Line could not be resolved", failure.refusal());
    assertFalse(failure.isThrottled());
  }

  private static final String THROTTLED =
      "{\"data\":{\"addPullRequestReviewThread\":null},"
          + "\"errors\":[{\"type\":\"RATE_LIMITED\","
          + "\"message\":\"You have exceeded a secondary rate limit\"}]}";

  private static final String OPENED =
      "{\"data\":{\"addPullRequestReviewThread\":{\"thread\":{\"id\":\"PRRT_1\"}}}}";

  private static GitHubReviewClient.CreatePullRequestCommentRequest lineComment() {
    return new GitHubReviewClient.CreatePullRequestCommentRequest(
        "sha", "body", "src/A.java", 12, "RIGHT", null, null);
  }

  @Test
  void addPendingReviewThreadBacksOffAThrottleReportedInTheErrorsAndRepeatsTheWrite()
      throws Exception {
    when(graphQLClient.executeOnce(anyString(), any()))
        .thenReturn(mapper.readTree(THROTTLED), mapper.readTree(OPENED));

    var thread = service.addPendingReviewThread("auth", "owner", "repo", 7, "PRR_9", lineComment());

    assertEquals("PRRT_1", thread);
    assertEquals(List.of(GitHubApiError.FALLBACK_DELAY), waits);
    verify(graphQLClient, times(2)).executeOnce(anyString(), any());
  }

  @Test
  void addPendingReviewThreadRaisesTheThrottleOnceItsRetriesRunOut() throws Exception {
    when(graphQLClient.executeOnce(anyString(), any())).thenReturn(mapper.readTree(THROTTLED));

    var failure =
        assertThrows(
            GitHubGraphQLException.class,
            () ->
                service.addPendingReviewThread("auth", "owner", "repo", 7, "PRR_9", lineComment()));

    assertTrue(failure.isThrottled());
    assertEquals("You have exceeded a secondary rate limit", failure.refusal());
    assertEquals(GitHubWriteRetry.MAX_ATTEMPTS - 1, waits.size());
    verify(graphQLClient, times(GitHubWriteRetry.MAX_ATTEMPTS)).executeOnce(anyString(), any());
  }

  @Test
  void addPendingReviewThreadDoesNotRepeatARefusal() throws Exception {
    when(graphQLClient.executeOnce(anyString(), any()))
        .thenReturn(
            mapper.readTree(
                "{\"errors\":[{\"type\":\"UNPROCESSABLE\",\"message\":\"bad line\"}]}"));

    assertThrows(
        GitHubGraphQLException.class,
        () -> service.addPendingReviewThread("auth", "owner", "repo", 7, "PRR_9", lineComment()));

    assertTrue(waits.isEmpty());
    verify(graphQLClient).executeOnce(anyString(), any());
  }

  @Test
  void addPendingReviewThreadPassesOnAnHttpFailureUnchanged() {
    var refused = new WebApplicationException(403);
    when(graphQLClient.executeOnce(anyString(), any())).thenThrow(refused);

    var failure =
        assertThrows(
            WebApplicationException.class,
            () ->
                service.addPendingReviewThread("auth", "owner", "repo", 7, "PRR_9", lineComment()));

    assertSame(refused, failure);
  }
}
