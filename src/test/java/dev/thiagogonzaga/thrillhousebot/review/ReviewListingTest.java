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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.CapturedLog;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGraphQLClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.ReviewResponse;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ReviewListingTest {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String BOT = "thrillhousebot[bot]";
  private static final String OTHER_BOT = "thrillhouse-bot[bot]";

  private final GitHubReviewClient reviewClient = mock(GitHubReviewClient.class);
  private final GitHubGraphQLClient graphQLClient = mock(GitHubGraphQLClient.class);
  private final ReviewListing listing =
      new ReviewListing(reviewClient, graphQLClient, BotIdentity.of(BOT, OTHER_BOT));

  /** As many reviews by someone else as the listing returns at most, ids from 1,000 up. */
  private List<ReviewResponse> fullListing() {
    var reviews =
        new ArrayList<>(
            LongStream.range(0, ReviewListing.MAX_LISTED_REVIEWS)
                .mapToObj(
                    i ->
                        new ReviewResponse(
                            1_000 + i, "", "COMMENTED", "sha9", new ReviewResponse.User("someone")))
                .toList());
    when(reviewClient.listReviews("auth", ACCEPT, "o", "r", 1)).thenReturn(reviews);
    return reviews;
  }

  private void approvalsBy(String login, String nodes) throws JsonProcessingException {
    ownReviewsBy(login, nodes, "");
  }

  private void ownReviewsBy(String login, String approved, String dismissed)
      throws JsonProcessingException {
    when(graphQLClient.execute(
            eq("auth"), argThat(request -> login.equals(request.variables().get("author")))))
        .thenReturn(
            json(
                "{\"data\":{\"repository\":{\"pullRequest\":{\"reviews\":{\"nodes\":["
                    + approved
                    + "]},\"dismissed\":{\"nodes\":["
                    + dismissed
                    + "]}}}}}"));
  }

  private static JsonNode json(String text) throws JsonProcessingException {
    return new ObjectMapper().readTree(text);
  }

  @Test
  void listingBelowTheLimitIsReturnedAsReadWithoutASecondCall() {
    var reviews =
        List.of(new ReviewResponse(1L, "", "APPROVED", "sha1", new ReviewResponse.User(BOT)));
    when(reviewClient.listReviews("auth", ACCEPT, "o", "r", 1)).thenReturn(reviews);

    assertSame(reviews, listing.list("auth", "o", "r", 1));
    verifyNoInteractions(graphQLClient);
  }

  @Test
  void approvalTheBotGaveBeforeTheListedReviewsIsPutInFrontOfThem() throws Exception {
    var listed = fullListing();
    approvalsBy(BOT, "{\"databaseId\":7,\"commit\":{\"oid\":\"sha1\"}}");
    approvalsBy(OTHER_BOT, "");

    var all = listing.list("auth", "o", "r", 1);

    assertEquals(listed.size() + 1, all.size());
    var approval = all.getFirst();
    assertEquals(7L, approval.id());
    assertEquals("APPROVED", approval.state());
    assertEquals("sha1", approval.commitId());
    assertEquals(BOT, approval.user().login());
    assertEquals(listed, all.subList(1, all.size()));
  }

  @Test
  void approvalsOfEveryBotLoginAreReadAndKeptOldestFirst() throws Exception {
    fullListing();
    approvalsBy(BOT, "{\"databaseId\":9,\"commit\":{\"oid\":\"sha3\"}}");
    approvalsBy(
        OTHER_BOT,
        "{\"databaseId\":4,\"commit\":{\"oid\":\"sha1\"}},"
            + "{\"databaseId\":6098573170,\"commit\":{\"oid\":\"sha2\"}}");

    var all = listing.list("auth", "o", "r", 1);

    assertEquals(
        List.of(4L, 9L, 6_098_573_170L), all.stream().limit(3).map(ReviewResponse::id).toList());
    assertEquals(OTHER_BOT, all.getFirst().user().login());
  }

  @Test
  void dismissedReviewOfTheBotsFromBeforeTheListedReviewsIsPutInFrontAsDismissed()
      throws Exception {
    var listed = fullListing();
    ownReviewsBy(
        BOT,
        "{\"databaseId\":8,\"commit\":{\"oid\":\"sha2\"}}",
        "{\"databaseId\":7,\"commit\":{\"oid\":\"sha1\"}}");
    approvalsBy(OTHER_BOT, "");

    var all = listing.list("auth", "o", "r", 1);

    assertEquals(listed.size() + 2, all.size());
    assertEquals(7L, all.getFirst().id());
    assertEquals("DISMISSED", all.getFirst().state());
    assertEquals("sha1", all.getFirst().commitId());
    assertEquals(BOT, all.getFirst().user().login());
    assertEquals("APPROVED", all.get(1).state());
    assertEquals(listed, all.subList(2, all.size()));
  }

  @Test
  void answerWithoutTheDismissedReviewsIsAFailedRead() throws Exception {
    var listed = fullListing();
    when(graphQLClient.execute(eq("auth"), any()))
        .thenReturn(
            json("{\"data\":{\"repository\":{\"pullRequest\":{\"reviews\":{\"nodes\":[]}}}}}"));

    try (var log = CapturedLog.of(ReviewListing.class)) {
      assertSame(listed, listing.list("auth", "o", "r", 1));

      assertEquals(1, log.at(Level.WARNING).size(), log.at(Level.WARNING).toString());
    }
  }

  @Test
  void approvalAlreadyInTheListingIsNotAddedTwice() throws Exception {
    var listed = fullListing();
    listed.set(
        listed.size() - 1,
        new ReviewResponse(5_000L, "", "APPROVED", "sha1", new ReviewResponse.User(BOT)));
    approvalsBy(BOT, "{\"databaseId\":5000,\"commit\":{\"oid\":\"sha1\"}},{\"commit\":null}");
    approvalsBy(OTHER_BOT, "");

    assertEquals(listed, listing.list("auth", "o", "r", 1));
  }

  @Test
  void answerWithErrorsAndNoReviewsKeepsTheListingAndSaysAnOlderApprovalWasNotChecked()
      throws Exception {
    var listed = fullListing();
    when(graphQLClient.execute(eq("auth"), any()))
        .thenReturn(json("{\"data\":{\"repository\":null},\"errors\":[{\"message\":\"nope\"}]}"));

    try (var log = CapturedLog.of(ReviewListing.class)) {
      assertSame(listed, listing.list("auth", "o", "r", 1));

      var warnings = log.at(Level.WARNING);
      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(warnings.getFirst().contains("o/r #1"), warnings.getFirst());
      assertTrue(warnings.getFirst().contains("neither seen nor dismissed"), warnings.getFirst());
    }
  }

  @Test
  void failedReadOfTheOlderApprovalsKeepsTheListing() {
    var listed = fullListing();
    when(graphQLClient.execute(eq("auth"), any())).thenThrow(new WebApplicationException(502));

    assertSame(listed, listing.list("auth", "o", "r", 1));
  }

  @Test
  void failedListingIsLeftToTheCaller() {
    when(reviewClient.listReviews("auth", ACCEPT, "o", "r", 1))
        .thenThrow(new WebApplicationException(502));

    assertThrows(WebApplicationException.class, () -> listing.list("auth", "o", "r", 1));
  }
}
