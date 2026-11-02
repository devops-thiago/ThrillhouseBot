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
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The short links posted on GitHub redirect to a session's numeric id only for a dashboard login
 * that may read it (#1220); everyone else gets the sessions list, exactly as for an unknown id.
 */
@QuarkusTest
class SessionLinkResourceTest extends ReviewSessionTestSupport {

  private static final String COOKIE = "thrillhouse_session";
  private static final String READER = "reader-token";
  private static final String OUTSIDER = "outsider-token";

  @Inject ReviewSessionPersistence persistence;

  @InjectMock DashboardSessionValidator sessionValidator;

  @BeforeEach
  void stubLogins() {
    when(sessionValidator.isValidSession(any())).thenReturn(false);
    when(sessionValidator.isValidSession(READER)).thenReturn(true);
    when(sessionValidator.isValidSession(OUTSIDER)).thenReturn(true);
    when(sessionValidator.repositoryScope(READER))
        .thenReturn(TestScopes.readable(false, "owner/repo"));
    when(sessionValidator.repositoryScope(OUTSIDER))
        .thenReturn(TestScopes.readable(false, "owner/other"));
  }

  private ReviewSession persistSession(RepoScope scope) throws Exception {
    ReviewSession session = ReviewSession.create(scope, "owner/repo", 7, "Title", "sha1");
    tx.begin();
    persistence.create(session);
    tx.commit();
    return session;
  }

  @Test
  void shouldRedirectAReaderToTheSessionsPageWithTheNumericId() throws Exception {
    var session = persistSession(TestScopes.scope("owner/repo"));
    assertNotNull(session.getPublicId());

    given()
        .cookie(COOKIE, READER)
        .redirects()
        .follow(false)
        .when()
        .get("/session/" + session.getPublicId())
        .then()
        .statusCode(303)
        .header("Location", endsWith("/dashboard/sessions/?id=" + session.id));
  }

  @Test
  void shouldNotRevealTheNumericIdWithoutALogin() throws Exception {
    var session = persistSession(TestScopes.scope("owner/repo"));

    given()
        .redirects()
        .follow(false)
        .when()
        .get("/session/" + session.getPublicId())
        .then()
        .statusCode(303)
        .header("Location", endsWith("/dashboard/sessions/"));
  }

  @Test
  void shouldNotRevealTheNumericIdToALoginOutsideTheRepository() throws Exception {
    var session = persistSession(TestScopes.scope("owner/repo"));

    given()
        .cookie(COOKIE, OUTSIDER)
        .redirects()
        .follow(false)
        .when()
        .get("/session/" + session.getPublicId())
        .then()
        .statusCode(303)
        .header("Location", endsWith("/dashboard/sessions/"));
  }

  @Test
  void shouldNotResolveTheSameRepositoryNameUnderAnotherInstallation() throws Exception {
    var ours = TestScopes.scope("owner/repo");
    var session = persistSession(new RepoScope(ours.installationId() + 1, ours.repositoryId()));

    given()
        .cookie(COOKIE, READER)
        .redirects()
        .follow(false)
        .when()
        .get("/session/" + session.getPublicId())
        .then()
        .statusCode(303)
        .header("Location", endsWith("/dashboard/sessions/"));
  }

  @Test
  void shouldFallBackToSessionsListForUnknownPublicId() {
    given()
        .cookie(COOKIE, READER)
        .redirects()
        .follow(false)
        .when()
        .get("/session/" + UUID.randomUUID())
        .then()
        .statusCode(303)
        .header("Location", endsWith("/dashboard/sessions/"));
  }

  @Test
  void shouldRejectNonUuidPublicIdWithoutDatabaseLookup() {
    // Sequential numeric ids must no longer resolve — only the random public id does
    given()
        .cookie(COOKIE, READER)
        .redirects()
        .follow(false)
        .when()
        .get("/session/12345")
        .then()
        .statusCode(303)
        .header("Location", endsWith("/dashboard/sessions/"));
  }
}
