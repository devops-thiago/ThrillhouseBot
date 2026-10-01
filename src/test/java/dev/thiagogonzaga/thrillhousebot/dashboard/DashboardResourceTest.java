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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.review.FindingFeedback;
import dev.thiagogonzaga.thrillhousebot.review.FindingFeedbackService;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearning;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(DashboardResource.class)
class DashboardResourceTest extends ReviewSessionTestSupport {

  private static final String COOKIE_NAME = "thrillhouse_session";
  private static final String VALID_TOKEN = "valid-session-token";
  private static final String COLLAB_TOKEN = "collaborator-session-token";

  /** The account owner's scope: every repository these tests persist, except private/repo. */
  private static final RepositoryScope OWNER_SCOPE =
      new RepositoryScope(
          true,
          Set.of(
              "owner/repo",
              "repo/a",
              "repo/b",
              "repo/x",
              "repo/y",
              "repo/oldest",
              "repo/newest",
              "repo/middle",
              "test/repo",
              "allowed/repo"));

  /** A collaborator on acme/allowed only, in an installation that also has acme/secret. */
  private static final RepositoryScope COLLAB_SCOPE =
      new RepositoryScope(false, Set.of("acme/allowed"));

  @InjectMock DashboardSessionValidator sessionValidator;

  @Inject FindingFeedbackService findingFeedbackService;

  @Inject ReviewLearningService reviewLearningService;

  @BeforeEach
  void setUp() {
    when(sessionValidator.isValidSession(anyString())).thenReturn(true);
    when(sessionValidator.isValidSession(isNull())).thenReturn(false);
    when(sessionValidator.hasRepositoryAccess(anyString(), anyString())).thenReturn(true);
    when(sessionValidator.repositoryScope(anyString())).thenReturn(OWNER_SCOPE);
    when(sessionValidator.repositoryScope(COLLAB_TOKEN)).thenReturn(COLLAB_SCOPE);
  }

  @AfterEach
  void cleanupFeedback() throws Exception {
    tx.begin();
    FindingFeedback.deleteAll();
    ReviewLearning.deleteAll();
    tx.commit();
  }

  @Test
  void shouldReturnUnauthorizedForGetSessionWithoutCookie() {
    given().when().get("/sessions/99999").then().statusCode(401);
  }

  @Test
  void shouldReturnSessionForValidId() throws Exception {
    var session = createPersistedSession("owner/repo", 1, "Test PR", "abc123");

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/sessions/" + session.id)
        .then()
        .statusCode(200)
        .body("repository", equalTo("owner/repo"))
        .body("prNumber", equalTo(1))
        .body("prTitle", equalTo("Test PR"))
        .body("commitSha", equalTo("abc123"))
        .body("status", equalTo(ReviewSession.STATUS_IN_PROGRESS))
        .body("id", instanceOf(Number.class));
  }

  @Test
  void shouldReturn404ForNonExistentSessionId() {
    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/sessions/999999")
        .then()
        .statusCode(404)
        .body("error", equalTo("Session not found"));
  }

  @Test
  void shouldReturnUnauthorizedForListSessionsWithoutCookie() {
    given().when().get("/sessions").then().statusCode(401);
  }

  @Test
  void shouldReturnUnauthorizedForFeedbackWithoutCookie() {
    given().when().get("/feedback").then().statusCode(401);
  }

  @Test
  void shouldReturnFeedbackAggregates() {
    findingFeedbackService.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            "owner/repo",
            1,
            10L,
            1,
            FindingFeedback.SIGNAL_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "octocat",
            101L));
    findingFeedbackService.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            "owner/repo",
            1,
            10L,
            1,
            FindingFeedback.SIGNAL_NOT_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "alice",
            102L));

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("repository", "owner/repo")
        .when()
        .get("/feedback")
        .then()
        .statusCode(200)
        .body("repositories[0].repository", equalTo("owner/repo"))
        .body("repositories[0].usefulCount", equalTo(1))
        .body("repositories[0].notUsefulCount", equalTo(1))
        .body("repositories[0].totalEvents", equalTo(2))
        .body("recent", hasSize(2));

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/feedback")
        .then()
        .statusCode(200)
        .body("repositories", not(empty()));

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("repository", "   ")
        .when()
        .get("/feedback")
        .then()
        .statusCode(200)
        .body("repositories", not(empty()));
  }

  @Test
  void learningsNeedASessionARepositoryAndAccessToIt() {
    given().when().get("/learnings").then().statusCode(401);
    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/learnings")
        .then()
        .statusCode(400)
        .body("error", equalTo("repository is required"));
    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("repository", " ")
        .when()
        .get("/learnings")
        .then()
        .statusCode(400);
    when(sessionValidator.hasRepositoryAccess(VALID_TOKEN, "private/repo")).thenReturn(false);
    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("repository", "private/repo")
        .when()
        .get("/learnings")
        .then()
        .statusCode(403);
  }

  @Test
  void learningsListEveryLearningWithItsSourceIncludingRetractedOnes() {
    reviewLearningService.save(
        new ReviewLearningService.LearningInput(
            1L,
            "owner/repo",
            ReviewLearning.KIND_DECLINE,
            "renderThread misses deeper nested replies",
            "medium",
            "src/A.java",
            "GitHub PR review threads are flat.",
            159,
            "https://github.com/owner/repo/pull/159#discussion_r1",
            "maintainer"),
        10);
    var id = reviewLearningService.listActive(1L, "owner/repo", 10).get(0).id();
    reviewLearningService.retract(1L, "owner/repo", id, "admin");

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("repository", "owner/repo")
        .when()
        .get("/learnings")
        .then()
        .statusCode(200)
        .body("repository", equalTo("owner/repo"))
        .body("learnings", hasSize(1))
        .body("learnings[0].text", equalTo("GitHub PR review threads are flat."))
        .body(
            "learnings[0].sourceUrl",
            equalTo("https://github.com/owner/repo/pull/159#discussion_r1"))
        .body("learnings[0].active", equalTo(false))
        .body("learnings[0].retractedBy", equalTo("admin"));
  }

  @Test
  void shouldRejectFeedbackForAnInaccessibleRepository() {
    when(sessionValidator.hasRepositoryAccess(VALID_TOKEN, "private/repo")).thenReturn(false);

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("repository", "private/repo")
        .when()
        .get("/feedback")
        .then()
        .statusCode(403)
        .body("error", equalTo("Repository access denied"));
  }

  @Test
  void shouldReturnFeedbackWhenRepositoryQueryCasingDiffers() {
    findingFeedbackService.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            "Owner/Repo",
            1,
            10L,
            1,
            FindingFeedback.SIGNAL_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "octocat",
            301L));
    when(sessionValidator.hasRepositoryAccess(VALID_TOKEN, "owner/repo")).thenReturn(true);

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("repository", "owner/repo")
        .when()
        .get("/feedback")
        .then()
        .statusCode(200)
        .body("repositories[0].repository", equalTo("Owner/Repo"))
        .body("repositories[0].usefulCount", equalTo(1))
        .body("repositories[0].totalEvents", equalTo(1))
        .body("recent", hasSize(1));
  }

  @Test
  void shouldFilterInaccessibleRepositoriesFromFeedbackAggregates() {
    findingFeedbackService.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            "allowed/repo",
            1,
            10L,
            1,
            FindingFeedback.SIGNAL_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "octocat",
            201L));
    findingFeedbackService.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            "private/repo",
            2,
            20L,
            1,
            FindingFeedback.SIGNAL_NOT_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "alice",
            202L));

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/feedback")
        .then()
        .statusCode(200)
        .body("repositories", hasSize(1))
        .body("repositories[0].repository", equalTo("allowed/repo"));
  }

  @Test
  void shouldListSessionsWithPagination() throws Exception {
    createPersistedSession("repo/a", 1, "PR 1", "sha1");
    createPersistedSession("repo/b", 2, "PR 2", "sha2");

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("page", 0)
        .queryParam("size", 10)
        .when()
        .get("/sessions")
        .then()
        .statusCode(200)
        .body("sessions", hasSize(2))
        .body("total", equalTo(2))
        .body("page", equalTo(0))
        .body("size", equalTo(10));
  }

  @Test
  void shouldListSessionsNewestFirst() throws Exception {
    // Fixed base: /sessions orders by timestamp with no now-relative filter
    Instant base = Instant.parse("2025-06-01T12:00:00Z");
    createPersistedSessionAt("repo/oldest", 1, base.minus(2, ChronoUnit.HOURS));
    createPersistedSessionAt("repo/newest", 2, base);
    createPersistedSessionAt("repo/middle", 3, base.minus(1, ChronoUnit.HOURS));

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/sessions")
        .then()
        .statusCode(200)
        .body("sessions[0].repository", equalTo("repo/newest"))
        .body("sessions[1].repository", equalTo("repo/middle"))
        .body("sessions[2].repository", equalTo("repo/oldest"));
  }

  @Test
  void shouldClampOversizedPageParameters() throws Exception {
    createPersistedSession("repo/a", 1, "PR 1", "sha1");

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("size", 5_000_000)
        .queryParam("page", -3)
        .when()
        .get("/sessions")
        .then()
        .statusCode(200)
        .body("size", equalTo(DashboardResource.MAX_PAGE_SIZE))
        .body("page", equalTo(0));
  }

  @Test
  void shouldClampNonPositivePageSizeToOne() throws Exception {
    createPersistedSession("repo/a", 1, "PR 1", "sha1");
    createPersistedSession("repo/b", 2, "PR 2", "sha2");

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("size", 0)
        .when()
        .get("/sessions")
        .then()
        .statusCode(200)
        .body("size", equalTo(1))
        .body("sessions", hasSize(1));
  }

  @Test
  void shouldFilterSessionsByRepository() throws Exception {
    createPersistedSession("repo/x", 1, "X PR", "sha1");
    createPersistedSession("repo/y", 2, "Y PR", "sha2");

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("repository", "repo/x")
        .when()
        .get("/sessions")
        .then()
        .statusCode(200)
        .body("sessions", hasSize(1))
        .body("sessions[0].repository", equalTo("repo/x"))
        .body("total", equalTo(1));
  }

  @Test
  void shouldReturnUnauthorizedForCostsWithoutCookie() {
    given().when().get("/costs").then().statusCode(401);
  }

  @Test
  void shouldReturnCostsWithDayPeriod() throws Exception {
    createPersistedCompletedSession("deepseek-chat", 100, 200, 0.05);

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("period", "day")
        .when()
        .get("/costs")
        .then()
        .statusCode(200)
        .body("period", equalTo("day"))
        .body("totalCost", instanceOf(Number.class))
        .body("byModel", hasSize(greaterThanOrEqualTo(0)))
        .body("since", instanceOf(String.class));
  }

  @Test
  void shouldReturnCostsWithWeekPeriod() throws Exception {
    createPersistedCompletedSession("gpt-4", 50, 100, 0.03);

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("period", "week")
        .when()
        .get("/costs")
        .then()
        .statusCode(200)
        .body("period", equalTo("week"))
        .body("byModel", hasSize(greaterThanOrEqualTo(1)));
  }

  @Test
  void shouldReturnCostsWithYearPeriod() throws Exception {
    createPersistedCompletedSession("claude", 300, 500, 0.10);

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("period", "year")
        .when()
        .get("/costs")
        .then()
        .statusCode(200)
        .body("period", equalTo("year"))
        .body("byModel", hasSize(greaterThanOrEqualTo(1)));
  }

  @Test
  void shouldReturnCostsWithDefaultMonthPeriod() throws Exception {
    createPersistedCompletedSession("deepseek-chat", 100, 200, 0.01);

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/costs")
        .then()
        .statusCode(200)
        .body("period", equalTo("month"))
        .body("totalCost", instanceOf(Number.class));
  }

  @Test
  void shouldReturnUnauthorizedForTokensWithoutCookie() {
    given().when().get("/tokens").then().statusCode(401);
  }

  @Test
  void shouldReturnTokensWithDayPeriod() throws Exception {
    createPersistedCompletedSession("deepseek-chat", 100, 200, 0.05);

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("period", "day")
        .when()
        .get("/tokens")
        .then()
        .statusCode(200)
        .body("period", equalTo("day"))
        .body("totalTokens", instanceOf(Number.class))
        .body("byModel", hasSize(greaterThanOrEqualTo(0)))
        .body("since", instanceOf(String.class));
  }

  @Test
  void shouldReturnTokensWithWeekPeriod() throws Exception {
    createPersistedCompletedSession("gpt-4", 30, 60, 0.01);

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .queryParam("period", "week")
        .when()
        .get("/tokens")
        .then()
        .statusCode(200)
        .body("period", equalTo("week"))
        .body("totalTokens", instanceOf(Number.class));
  }

  @Test
  void shouldReturnTokensWithDefaultMonthPeriod() throws Exception {
    createPersistedCompletedSession("deepseek-chat", 10, 20, 0.001);

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/tokens")
        .then()
        .statusCode(200)
        .body("period", equalTo("month"))
        .body("totalTokens", instanceOf(Number.class));
  }

  @Test
  void shouldReturnUnauthorizedForSummaryWithoutCookie() {
    given().when().get("/summary").then().statusCode(401);
  }

  @Test
  void shouldReturnSummaryWithEmptyDatabase() {
    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/summary")
        .then()
        .statusCode(200)
        .body("totalReviews", equalTo(0))
        .body("completedReviews", equalTo(0))
        .body("failedReviews", equalTo(0))
        .body("totalCost", is(0.0f))
        .body("topModel", equalTo("N/A"))
        .body("skippedReviewsByReason", instanceOf(Map.class))
        .body("since", instanceOf(String.class));
  }

  @Test
  void shouldReturnSummaryWithCompletedAndFailedReviews() throws Exception {
    createPersistedCompletedSession("deepseek-chat", 100, 200, 0.05);
    createPersistedFailedSession("gpt-4", 50, 100, 0.02, "timeout");

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/summary")
        .then()
        .statusCode(200)
        .body("totalReviews", equalTo(2))
        .body("completedReviews", equalTo(1))
        .body("failedReviews", equalTo(1))
        .body("totalCost", instanceOf(Number.class))
        .body("since", instanceOf(String.class));
  }

  @Test
  void shouldReturnTopModelFromSummary() throws Exception {
    createPersistedCompletedSession("deepseek-chat", 50, 100, 0.01);
    createPersistedCompletedSession("gpt-4", 100, 200, 0.02);
    createPersistedCompletedSession("deepseek-chat", 30, 80, 0.005);

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/summary")
        .then()
        .statusCode(200)
        .body("topModel", anyOf(equalTo("deepseek-chat"), equalTo("gpt-4")));
  }

  @Test
  void shouldRejectBlankCookieForListSessions() {
    given().cookie(COOKIE_NAME, "").when().get("/sessions").then().statusCode(401);
  }

  @Test
  void shouldRejectBlankCookieForCosts() {
    given().cookie(COOKIE_NAME, "   ").when().get("/costs").then().statusCode(401);
  }

  @Test
  void sessionListShowsACollaboratorOnlyTheirRepositoriesWithAMatchingTotal() throws Exception {
    var base = Instant.now();
    createPersistedSessionAt("acme/allowed", 1, base.minus(3, ChronoUnit.MINUTES));
    createPersistedSessionAt("acme/secret", 2, base.minus(2, ChronoUnit.MINUTES));
    createPersistedSessionAt("ACME/Allowed", 3, base.minus(1, ChronoUnit.MINUTES));
    createPersistedSessionAt("acme/secret", 4, base);

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .queryParam("size", 1)
        .when()
        .get("/sessions")
        .then()
        .statusCode(200)
        .body("total", equalTo(2))
        .body("sessions", hasSize(1))
        .body("sessions[0].prNumber", equalTo(3));

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .when()
        .get("/sessions")
        .then()
        .statusCode(200)
        .body("total", equalTo(2))
        .body("sessions.prNumber", contains(3, 1))
        .body("sessions.repository", not(hasItem("acme/secret")));
  }

  @Test
  void sessionListFilteredToAnInaccessibleRepositoryIsForbidden() throws Exception {
    createPersistedSession("acme/allowed", 1, "Allowed", "sha1");
    createPersistedSession("acme/secret", 2, "Secret", "sha2");

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .queryParam("repository", "acme/secret")
        .when()
        .get("/sessions")
        .then()
        .statusCode(403)
        .body("error", equalTo("Repository access denied"));

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .queryParam("repository", "ACME/allowed")
        .when()
        .get("/sessions")
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("sessions[0].prTitle", equalTo("Allowed"));
  }

  @Test
  void aBlankRepositoryFilterListsTheWholeScope() throws Exception {
    createPersistedSession("acme/allowed", 1, "Allowed", "sha1");
    createPersistedSession("acme/secret", 2, "Secret", "sha2");

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .queryParam("repository", "  ")
        .when()
        .get("/sessions")
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("sessions[0].repository", equalTo("acme/allowed"));
  }

  @Test
  void sessionDetailOfAnotherRepositoryAnswersLikeAMissingSession() throws Exception {
    var allowed = createPersistedSession("acme/allowed", 1, "Allowed", "sha1");
    var secret = createPersistedSession("acme/secret", 2, "Secret", "sha2");

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .when()
        .get("/sessions/" + secret.id)
        .then()
        .statusCode(404)
        .body("error", equalTo("Session not found"))
        .body(not(containsString("acme/secret")));

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .when()
        .get("/sessions/" + allowed.id)
        .then()
        .statusCode(200)
        .body("repository", equalTo("acme/allowed"));
  }

  @Test
  void costsAndTokensAggregateOnlyTheCollaboratorsRepositories() throws Exception {
    createCompletedSessionIn("acme/allowed", "deepseek-chat", 100, 200, 0.25);
    createCompletedSessionIn("acme/secret", "deepseek-chat", 1000, 2000, 4.0);
    createCompletedSessionIn("acme/secret", "secret-model", 5000, 5000, 8.0);

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .when()
        .get("/costs")
        .then()
        .statusCode(200)
        .body("totalCost", is(0.25f))
        .body("byModel", hasSize(1))
        .body("byModel[0].model", equalTo("deepseek-chat"))
        .body("byModel[0].count", equalTo(1));

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .when()
        .get("/tokens")
        .then()
        .statusCode(200)
        .body("totalTokens", equalTo(300))
        .body("byModel", hasSize(1))
        .body("byModel[0].inputTokens", equalTo(100));
  }

  @Test
  void summaryCountsOnlyTheCollaboratorsRepositoriesAndHidesInstallationWideSkips()
      throws Exception {
    createCompletedSessionIn("acme/allowed", "deepseek-chat", 100, 200, 0.25);
    createCompletedSessionIn("acme/secret", "secret-model", 1, 1, 4.0);
    createCompletedSessionIn("acme/secret", "secret-model", 1, 1, 4.0);
    createPersistedSession("acme/secret", 9, "Secret running", "sha9");

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .when()
        .get("/summary")
        .then()
        .statusCode(200)
        .body("totalReviews", equalTo(1))
        .body("completedReviews", equalTo(1))
        .body("failedReviews", equalTo(0))
        .body("totalCost", is(0.25f))
        .body("topModel", equalTo("deepseek-chat"))
        .body("skippedReviewsByReason", anEmptyMap());
  }

  @Test
  void aLoginWithNoReadableRepositorySeesNothing() throws Exception {
    when(sessionValidator.repositoryScope("nothing-token")).thenReturn(RepositoryScope.NONE);
    createCompletedSessionIn("acme/secret", "secret-model", 10, 10, 1.0);

    given()
        .cookie(COOKIE_NAME, "nothing-token")
        .when()
        .get("/sessions")
        .then()
        .statusCode(200)
        .body("total", equalTo(0))
        .body("sessions", empty());
    given()
        .cookie(COOKIE_NAME, "nothing-token")
        .when()
        .get("/costs")
        .then()
        .statusCode(200)
        .body("totalCost", is(0.0f))
        .body("byModel", empty());
    given()
        .cookie(COOKIE_NAME, "nothing-token")
        .when()
        .get("/summary")
        .then()
        .statusCode(200)
        .body("totalReviews", equalTo(0))
        .body("topModel", equalTo("N/A"));
  }

  @Test
  void feedbackAggregatesFollowTheRepositoryScope() {
    findingFeedbackService.recordFeedback(
        new FindingFeedbackService.FeedbackInput(
            "acme/secret",
            1,
            10L,
            1,
            FindingFeedback.SIGNAL_USEFUL,
            FindingFeedback.SOURCE_REACTION,
            "octocat",
            401L));

    given()
        .cookie(COOKIE_NAME, COLLAB_TOKEN)
        .when()
        .get("/feedback")
        .then()
        .statusCode(200)
        .body("repositories", empty());
  }

  private void createCompletedSessionIn(
      String repo, String model, int inputTokens, int outputTokens, double cost) throws Exception {
    tx.begin();
    ReviewSession s = ReviewSession.create(repo, 50, "Completed", "sha");
    s.setModel(model);
    s.setInputTokens(inputTokens);
    s.setOutputTokens(outputTokens);
    s.setCost(cost);
    s.setStatus(ReviewSession.STATUS_COMPLETED);
    s.persist();
    s.flush();
    tx.commit();
  }

  private ReviewSession createPersistedSession(String repo, int prNumber, String title, String sha)
      throws Exception {
    tx.begin();
    ReviewSession s = ReviewSession.create(repo, prNumber, title, sha);
    s.persist();
    s.flush();
    tx.commit();
    return s;
  }

  private ReviewSession createPersistedSessionAt(String repo, int prNumber, Instant timestamp)
      throws Exception {
    tx.begin();
    ReviewSession s = ReviewSession.create(repo, prNumber, "PR " + prNumber, "sha" + prNumber);
    s.setTimestamp(timestamp);
    s.persist();
    s.flush();
    tx.commit();
    return s;
  }

  private ReviewSession createPersistedCompletedSession(
      String model, int inputTokens, int outputTokens, double cost) throws Exception {
    tx.begin();
    ReviewSession s = ReviewSession.create("test/repo", 99, "Completed PR", "abc123");
    s.setModel(model);
    s.setInputTokens(inputTokens);
    s.setOutputTokens(outputTokens);
    s.setCost(cost);
    s.setStatus(ReviewSession.STATUS_COMPLETED);
    s.persist();
    s.flush();
    tx.commit();
    return s;
  }

  private ReviewSession createPersistedFailedSession(
      String model, int inputTokens, int outputTokens, double cost, String errorType)
      throws Exception {
    tx.begin();
    ReviewSession s = ReviewSession.create("test/repo", 98, "Failed PR", "def456");
    s.setModel(model);
    s.setInputTokens(inputTokens);
    s.setOutputTokens(outputTokens);
    s.setCost(cost);
    s.setStatus(ReviewSession.STATUS_FAILED);
    s.setErrorMessage(errorType);
    s.persist();
    s.flush();
    tx.commit();
    return s;
  }
}
