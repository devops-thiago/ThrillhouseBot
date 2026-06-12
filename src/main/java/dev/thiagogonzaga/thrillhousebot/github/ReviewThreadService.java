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
      query($owner: String!, $name: String!, $number: Int!) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $number) {
            reviewThreads(first: 100) {
              nodes { id isResolved comments(first: 1) { nodes { databaseId } } }
            }
          }
        }
      }
      """;

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

  /** Maps each thread's root-comment database id to the thread's node id and resolution state. */
  public Map<Long, ThreadRef> threadsByRootComment(
      String auth, String owner, String repo, int prNumber) {
    var response =
        graphQLClient.execute(
            auth,
            new GitHubGraphQLClient.GraphQLRequest(
                THREADS_QUERY, Map.of("owner", owner, "name", repo, "number", prNumber)));
    var threads = new HashMap<Long, ThreadRef>();
    var nodes =
        response
            .path("data")
            .path("repository")
            .path("pullRequest")
            .path("reviewThreads")
            .path("nodes");
    for (JsonNode node : nodes) {
      var firstComment = node.path("comments").path("nodes").path(0);
      if (firstComment.path("databaseId").isNumber() && node.path("id").isTextual()) {
        threads.put(
            firstComment.path("databaseId").asLong(),
            new ThreadRef(node.path("id").asText(), node.path("isResolved").asBoolean()));
      }
    }
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
