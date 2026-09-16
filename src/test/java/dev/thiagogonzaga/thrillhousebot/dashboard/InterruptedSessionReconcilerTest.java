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
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class InterruptedSessionReconcilerTest extends ReviewSessionTestSupport {

  private static final String COOKIE_NAME = "thrillhouse_session";
  private static final String VALID_TOKEN = "valid-session-token";

  @Inject InterruptedSessionReconciler reconciler;

  @InjectMock DashboardSessionValidator sessionValidator;

  @BeforeEach
  void allowDashboardAccess() {
    when(sessionValidator.isValidSession(anyString())).thenReturn(true);
    when(sessionValidator.isValidSession(isNull())).thenReturn(false);
  }

  @Test
  void shouldFailASessionLeftInProgressByAnEarlierRun() throws Exception {
    var id = persistInProgress();

    var reconciled = reconciler.reconcile();

    assertEquals(1, reconciled);
    ReviewSession loaded = ReviewSession.findById(id);
    assertEquals(ReviewSession.STATUS_FAILED, loaded.getStatus());
    assertEquals(InterruptedSessionReconciler.INTERRUPTED_ERROR_MESSAGE, loaded.getErrorMessage());
  }

  @Test
  void shouldKeepTheTokensAndCostRecordedBeforeTheInterruption() throws Exception {
    var id = persistInProgress();

    reconciler.reconcile();

    ReviewSession loaded = ReviewSession.findById(id);
    assertEquals(1200, loaded.getInputTokens());
    assertEquals(340, loaded.getOutputTokens());
    assertEquals(0.07, loaded.getCost(), 1e-9);
  }

  @Test
  void shouldLeaveATerminalSessionAlone() throws Exception {
    var completed = persistTerminal(ReviewSession.STATUS_COMPLETED, null);
    var failed = persistTerminal(ReviewSession.STATUS_FAILED, "AI request timed out");

    var reconciled = reconciler.reconcile();

    assertEquals(0, reconciled);
    ReviewSession loadedCompleted = ReviewSession.findById(completed);
    assertEquals(ReviewSession.STATUS_COMPLETED, loadedCompleted.getStatus());
    ReviewSession loadedFailed = ReviewSession.findById(failed);
    assertEquals("AI request timed out", loadedFailed.getErrorMessage());
  }

  @Test
  void shouldReconcileOnStartup() throws Exception {
    var id = persistInProgress();

    reconciler.onStart(mock(StartupEvent.class));

    ReviewSession loaded = ReviewSession.findById(id);
    assertEquals(ReviewSession.STATUS_FAILED, loaded.getStatus());
  }

  @Test
  void shouldCountAReconciledSessionAsFailedOnTheDashboard() throws Exception {
    persistInProgress();

    reconciler.onStart(mock(StartupEvent.class));

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/api/dashboard/summary")
        .then()
        .statusCode(200)
        .body("totalReviews", equalTo(1))
        .body("completedReviews", equalTo(0))
        .body("failedReviews", equalTo(1));
  }

  @Test
  void shouldShowTheInterruptionReasonOnTheSessionPage() throws Exception {
    var id = persistInProgress();

    reconciler.onStart(mock(StartupEvent.class));

    given()
        .cookie(COOKIE_NAME, VALID_TOKEN)
        .when()
        .get("/api/dashboard/sessions/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo(ReviewSession.STATUS_FAILED))
        .body("errorMessage", equalTo(InterruptedSessionReconciler.INTERRUPTED_ERROR_MESSAGE));
  }

  private long persistInProgress() throws Exception {
    tx.begin();
    ReviewSession session = ReviewSession.create("owner/repo", 7, "Interrupted PR", "sha7");
    session.setModel("deepseek-chat");
    session.setInputTokens(1200);
    session.setOutputTokens(340);
    session.setCost(0.07);
    session.persist();
    session.flush();
    tx.commit();
    return session.id;
  }

  private long persistTerminal(String status, String errorMessage) throws Exception {
    tx.begin();
    ReviewSession session = ReviewSession.create("owner/repo", 8, "Finished PR", "sha8");
    session.setStatus(status);
    session.setErrorMessage(errorMessage);
    session.persist();
    session.flush();
    tx.commit();
    return session.id;
  }
}
