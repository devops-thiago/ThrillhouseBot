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
package dev.thiagogonzaga.thrillhousebot.review;

import com.fasterxml.jackson.databind.JsonNode;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGraphQLClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGraphQLException;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * The pull request's reviews as the bot acts on them: the listing {@link
 * GitHubReviewClient#listReviews} reads, and, when that listing was cut, the bot's own standing
 * approvals and dismissed reviews that fell outside it.
 *
 * <p>The listing holds at most {@link #MAX_LISTED_REVIEWS} reviews, the newest ones. Every inline
 * reply adds a review, so anyone who can comment can put that many after an approval the bot gave,
 * and an approval that is not read is not dismissed when commits are pushed or a later round does
 * not approve. No window over everybody's reviews closes that, whichever end it starts from. So for
 * a listing that reached the limit the bot's approvals are asked for by author and state, which the
 * GraphQL API filters on the server: other people's reviews cannot push them out of that answer.
 * They are put in front of the listing, since they are older than everything in it, and the result
 * stays oldest first.
 *
 * <p>The reviews GitHub still counts as {@code APPROVED} are asked for, the newest {@value
 * #APPROVALS_PER_LOGIN} for each of the bot's logins; each push dismisses the ones standing, so
 * there is rarely more than one. The bot's {@code DISMISSED} reviews are asked for the same way, on
 * their own so they cannot crowd an approval out: {@link ApprovalOnPush} reads them to tell that
 * the head the rate window covers lost its approval, and one GitHub dismissed on a push can lie
 * outside the listing just as an approval can. When that second read fails, the listing is returned
 * as it is and a warning says that an older approval was not checked.
 */
@ApplicationScoped
public class ReviewListing {

  private static final String ACCEPT = "application/vnd.github+json";

  /** The most reviews {@link GitHubReviewClient#listReviews} returns: every page it keeps, full. */
  static final int MAX_LISTED_REVIEWS =
      GitHubReviewClient.MAX_REVIEW_PAGES * GitHubReviewClient.REVIEWS_PER_PAGE;

  static final int APPROVALS_PER_LOGIN = 100;

  private static final String NODES = "nodes";
  private static final String APPROVED = "APPROVED";
  private static final String DISMISSED = "DISMISSED";

  private static final String APPROVALS_QUERY =
      """
      query($owner: String!, $name: String!, $number: Int!, $author: String!, $last: Int!) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $number) {
            reviews(last: $last, author: $author, states: [APPROVED]) {
              nodes { databaseId commit { oid } }
            }
            dismissed: reviews(last: $last, author: $author, states: [DISMISSED]) {
              nodes { databaseId commit { oid } }
            }
          }
        }
      }
      """;

  private final GitHubReviewClient reviewClient;
  private final GitHubGraphQLClient graphQLClient;
  private final BotIdentity botIdentity;

  @Inject
  public ReviewListing(
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubGraphQLClient graphQLClient,
      BotIdentity botIdentity) {
    this.reviewClient = reviewClient;
    this.graphQLClient = graphQLClient;
    this.botIdentity = botIdentity;
  }

  /**
   * The pull request's reviews, oldest first. A listing that fails throws, as {@link
   * GitHubReviewClient#listReviews} does; a failure to read the bot's older approvals does not.
   */
  public List<GitHubReviewClient.ReviewResponse> list(
      String auth, String owner, String repo, int prNumber) {
    var listed = reviewClient.listReviews(auth, ACCEPT, owner, repo, prNumber);
    if (listed.size() < MAX_LISTED_REVIEWS) {
      return listed;
    }
    List<GitHubReviewClient.ReviewResponse> older;
    try {
      older = ownReviewsOutside(listed, auth, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Reviews on %s/%s #%d reached the %d-review read limit and the bot's own approvals could"
              + " not be read apart from them: the newest %d reviews are acted on, and an approval"
              + " the bot gave before those is neither seen nor dismissed",
          owner,
          repo,
          prNumber,
          MAX_LISTED_REVIEWS,
          MAX_LISTED_REVIEWS);
      return listed;
    }
    Log.infof(
        "Reviews on %s/%s #%d reached the %d-review read limit: acting on the newest %d and on %d"
            + " standing or dismissed review(s) of the bot's from before them",
        owner, repo, prNumber, MAX_LISTED_REVIEWS, MAX_LISTED_REVIEWS, older.size());
    var all = new ArrayList<GitHubReviewClient.ReviewResponse>(older);
    all.addAll(listed);
    return all;
  }

  /**
   * The bot's standing approvals and dismissed reviews that {@code listed} does not hold, oldest
   * first.
   */
  private List<GitHubReviewClient.ReviewResponse> ownReviewsOutside(
      List<GitHubReviewClient.ReviewResponse> listed,
      String auth,
      String owner,
      String repo,
      int prNumber) {
    Set<Long> seen =
        listed.stream().map(GitHubReviewClient.ReviewResponse::id).collect(Collectors.toSet());
    var older = new ArrayList<GitHubReviewClient.ReviewResponse>();
    for (var login : botIdentity.logins()) {
      var own = ownReviews(auth, owner, repo, prNumber, login);
      addUnseen(older, seen, own.path("reviews").path(NODES), APPROVED, login);
      addUnseen(older, seen, own.path("dismissed").path(NODES), DISMISSED, login);
    }
    older.sort(Comparator.comparingLong(GitHubReviewClient.ReviewResponse::id));
    return older;
  }

  private static void addUnseen(
      List<GitHubReviewClient.ReviewResponse> older,
      Set<Long> seen,
      JsonNode nodes,
      String state,
      String login) {
    for (JsonNode node : nodes) {
      // GitHub hands review ids out in order, and the REST dismissal takes this same id.
      if (node.path("databaseId").isNumber() && seen.add(node.path("databaseId").asLong())) {
        older.add(
            new GitHubReviewClient.ReviewResponse(
                node.path("databaseId").asLong(),
                "",
                state,
                node.path("commit").path("oid").asText(null),
                // The answer names an App without the "[bot]" its REST login ends in.
                new GitHubReviewClient.ReviewResponse.User(login)));
      }
    }
  }

  /**
   * The pull request as GraphQL answers it for {@code login}: the reviews GitHub still counts as
   * approvals under {@code reviews}, the dismissed ones under {@code dismissed}. An answer without
   * both lists is a read that failed, never a pull request the bot has not approved.
   */
  private JsonNode ownReviews(String auth, String owner, String repo, int prNumber, String login) {
    var response =
        graphQLClient.execute(
            auth,
            new GitHubGraphQLClient.GraphQLRequest(
                APPROVALS_QUERY,
                Map.of(
                    "owner",
                    owner,
                    "name",
                    repo,
                    "number",
                    prNumber,
                    "author",
                    login,
                    "last",
                    APPROVALS_PER_LOGIN)));
    var pullRequest = response.path("data").path("repository").path("pullRequest");
    if (!pullRequest.path("reviews").path(NODES).isArray()
        || !pullRequest.path("dismissed").path(NODES).isArray()) {
      throw GitHubGraphQLException.from(response.path("errors"));
    }
    return pullRequest;
  }
}
