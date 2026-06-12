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

import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(DashboardResource.class)
class DashboardResourceTest extends ReviewSessionTestSupport {

  private static final String COOKIE_NAME = "thrillhouse_session";
  private static final String VALID_TOKEN = "valid-session-token";

  @InjectMock DashboardSessionValidator sessionValidator;

  @BeforeEach
  void setUp() {
    when(sessionValidator.isValidSession(anyString())).thenReturn(true);
    when(sessionValidator.isValidSession(isNull())).thenReturn(false);
  }

  // ── GET /api/dashboard/sessions/{id} ──────────────────────────────────

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

  // ── GET /api/dashboard/sessions (list) ─────────────────────────────────

  @Test
  void shouldReturnUnauthorizedForListSessionsWithoutCookie() {
    given().when().get("/sessions").then().statusCode(401);
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

  // ── GET /api/dashboard/costs ──────────────────────────────────────────

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

  // ── GET /api/dashboard/tokens ─────────────────────────────────────────

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

  // ── GET /api/dashboard/summary ────────────────────────────────────────

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

  // ── Edge cases ─────────────────────────────────────────────────────────

  @Test
  void shouldRejectBlankCookieForListSessions() {
    given().cookie(COOKIE_NAME, "").when().get("/sessions").then().statusCode(401);
  }

  @Test
  void shouldRejectBlankCookieForCosts() {
    given().cookie(COOKIE_NAME, "   ").when().get("/costs").then().statusCode(401);
  }

  // ── Helpers ───────────────────────────────────────────────────────────

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
