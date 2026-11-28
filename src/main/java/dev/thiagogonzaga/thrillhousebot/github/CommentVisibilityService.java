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
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Lists a pull request's conversation comments and reviews with their visibility, and minimizes
 * them, via GraphQL: the REST API neither reports nor changes whether a comment is hidden.
 */
@ApplicationScoped
public class CommentVisibilityService {

  private static final String COMMENTS_QUERY =
      """
      query($owner: String!, $name: String!, $number: Int!, $after: String) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $number) {
            comments(first: 100, after: $after) {
              nodes { id body isMinimized createdAt author { __typename login } }
              pageInfo { hasNextPage endCursor }
            }
          }
        }
      }
      """;

  private static final String REVIEWS_QUERY =
      """
      query($owner: String!, $name: String!, $number: Int!, $after: String) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $number) {
            reviews(first: 100, after: $after) {
              nodes { id body isMinimized createdAt state author { __typename login } }
              pageInfo { hasNextPage endCursor }
            }
          }
        }
      }
      """;

  private static final String MINIMIZE_MUTATION =
      """
      mutation($id: ID!) {
        minimizeComment(input: {subjectId: $id, classifier: OUTDATED}) {
          minimizedComment { isMinimized }
        }
      }
      """;

  // Stops a malformed pageInfo from looping forever.
  private static final int MAX_PAGES = 20;

  private static final String BOT_LOGIN_SUFFIX = "[bot]";

  /**
   * A conversation comment or a review. {@code login} is spelled as the REST API spells it, and
   * {@code state} is empty for a comment.
   */
  public record Entry(
      String id, String body, String login, Instant createdAt, boolean minimized, String state) {}

  private final GitHubGraphQLClient graphQLClient;
  private final GitHubWriteRetry writeRetry;

  @Inject
  public CommentVisibilityService(@RestClient GitHubGraphQLClient graphQLClient) {
    this(graphQLClient, GitHubWriteRetry.DEFAULT);
  }

  /** The same, backing off with {@code writeRetry}; the seam the tests run the backoff through. */
  CommentVisibilityService(GitHubGraphQLClient graphQLClient, GitHubWriteRetry writeRetry) {
    this.graphQLClient = graphQLClient;
    this.writeRetry = writeRetry;
  }

  /** The pull request's conversation comments, oldest first. */
  public List<Entry> comments(String auth, String owner, String repo, int prNumber) {
    return list(auth, COMMENTS_QUERY, "comments", owner, repo, prNumber);
  }

  /** The pull request's reviews, oldest first. */
  public List<Entry> reviews(String auth, String owner, String repo, int prNumber) {
    return list(auth, REVIEWS_QUERY, "reviews", owner, repo, prNumber);
  }

  private List<Entry> list(
      String auth, String query, String field, String owner, String repo, int prNumber) {
    var entries = new ArrayList<Entry>();
    String after = null;
    var page = 0;
    do {
      var variables = new HashMap<String, Object>();
      variables.put("owner", owner);
      variables.put("name", repo);
      variables.put("number", prNumber);
      if (after != null) {
        variables.put("after", after);
      }
      var response =
          graphQLClient.execute(auth, new GitHubGraphQLClient.GraphQLRequest(query, variables));
      var connection = response.path("data").path("repository").path("pullRequest").path(field);
      if (connection.isMissingNode() && !response.path("errors").isEmpty()) {
        throw GitHubGraphQLException.from(response.path("errors"));
      }
      connection.path("nodes").forEach(node -> entries.add(entry(node)));
      var pageInfo = connection.path("pageInfo");
      after =
          pageInfo.path("hasNextPage").asBoolean(false)
              ? pageInfo.path("endCursor").asText(null)
              : null;
      page++;
    } while (after != null && page < MAX_PAGES);
    if (after != null) {
      Log.warnf(
          "Stopped listing %s of %s/%s #%d after %d pages; newer ones are not seen",
          field, owner, repo, prNumber, MAX_PAGES);
    }
    return entries;
  }

  /** GraphQL names an App's bot without the {@code [bot]} suffix the REST API and config use. */
  private static Entry entry(JsonNode node) {
    var author = node.path("author");
    var login = author.path("login").asText("");
    if ("Bot".equals(author.path("__typename").asText()) && !login.endsWith(BOT_LOGIN_SUFFIX)) {
      login += BOT_LOGIN_SUFFIX;
    }
    return new Entry(
        node.path("id").asText(),
        node.path("body").asText(""),
        login,
        Instant.parse(node.path("createdAt").asText()),
        node.path("isMinimized").asBoolean(),
        node.path("state").asText(""));
  }

  /**
   * Minimizes a comment or review as outdated. The write is paced and backed off on a throttle as
   * the other writes are.
   *
   * @throws GitHubGraphQLException when GitHub answers with {@code errors} and no confirmation
   */
  public void minimize(String auth, String id) {
    var request = new GitHubGraphQLClient.GraphQLRequest(MINIMIZE_MUTATION, Map.of("id", id));
    Function<String, Boolean> attempt =
        credential -> confirmed(graphQLClient.executeOnce(credential, request));
    try {
      writeRetry.call("minimizing an outdated comment", auth, attempt);
    } catch (WebApplicationException e) {
      if (e.getCause() instanceof GitHubGraphQLException refusal) {
        throw refusal;
      }
      throw e;
    }
  }

  private static boolean confirmed(JsonNode response) {
    var minimized =
        response.path("data").path("minimizeComment").path("minimizedComment").path("isMinimized");
    if (minimized.asBoolean(false)) {
      return true;
    }
    var refusal = GitHubGraphQLException.from(response.path("errors"));
    throw refusal.isThrottled() ? refusal.asHttpThrottle() : refusal;
  }
}
