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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks up and resolves pull request review threads via GraphQL — the REST API can list inline
 * comments but cannot resolve the threads they belong to.
 */
@ApplicationScoped
public class ReviewThreadService {

  private static final Logger log = LoggerFactory.getLogger(ReviewThreadService.class);

  private static final String THREADS_QUERY =
      """
      query($owner: String!, $name: String!, $number: Int!, $after: String) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $number) {
            reviewThreads(first: 100, after: $after) {
              nodes { id isResolved comments(first: 1) { nodes { databaseId } } }
              pageInfo { hasNextPage endCursor }
            }
          }
        }
      }
      """;

  // A PR with more review threads than this (100 per page) is far beyond anything realistic; the
  // bound just stops a malformed pageInfo from looping forever.
  private static final int MAX_THREAD_PAGES = 20;

  private static final String RESOLVE_MUTATION =
      """
      mutation($threadId: ID!) {
        resolveReviewThread(input: {threadId: $threadId}) { thread { isResolved } }
      }
      """;

  /** A review thread keyed by its root comment, with its GraphQL node id. */
  public record ThreadRef(String id, boolean resolved) {}

  private final GitHubGraphQLClient graphQLClient;

  @Inject
  public ReviewThreadService(@RestClient GitHubGraphQLClient graphQLClient) {
    this.graphQLClient = graphQLClient;
  }

  /**
   * Maps each thread's root-comment database id to the thread's node id and resolution state. Walks
   * every page so a PR with more than 100 review threads still has all its threads resolvable —
   * otherwise {@code /resolve} would silently skip bot findings past the first page.
   */
  public Map<Long, ThreadRef> threadsByRootComment(
      String auth, String owner, String repo, int prNumber) {
    var threads = new HashMap<Long, ThreadRef>();
    String after = null;
    int page = 0;
    do {
      var variables = new HashMap<String, Object>();
      variables.put("owner", owner);
      variables.put("name", repo);
      variables.put("number", prNumber);
      if (after != null) {
        variables.put("after", after);
      }
      var reviewThreads =
          graphQLClient
              .execute(auth, new GitHubGraphQLClient.GraphQLRequest(THREADS_QUERY, variables))
              .path("data")
              .path("repository")
              .path("pullRequest")
              .path("reviewThreads");
      for (JsonNode node : reviewThreads.path("nodes")) {
        var firstComment = node.path("comments").path("nodes").path(0);
        if (firstComment.path("databaseId").isNumber() && node.path("id").isTextual()) {
          threads.put(
              firstComment.path("databaseId").asLong(),
              new ThreadRef(node.path("id").asText(), node.path("isResolved").asBoolean()));
        }
      }
      var pageInfo = reviewThreads.path("pageInfo");
      after =
          pageInfo.path("hasNextPage").asBoolean(false)
              ? pageInfo.path("endCursor").asText(null)
              : null;
      page++;
    } while (after != null && page < MAX_THREAD_PAGES);
    return threads;
  }

  /** Resolves a review thread; returns whether GitHub reports it resolved afterwards. */
  public boolean resolve(String auth, String threadId) {
    var response =
        graphQLClient.execute(
            auth,
            new GitHubGraphQLClient.GraphQLRequest(RESOLVE_MUTATION, Map.of("threadId", threadId)));
    var resolved =
        response
            .path("data")
            .path("resolveReviewThread")
            .path("thread")
            .path("isResolved")
            .asBoolean(false);
    if (!resolved) {
      log.warn("GitHub did not confirm resolution of review thread {}", threadId);
    }
    return resolved;
  }
}
