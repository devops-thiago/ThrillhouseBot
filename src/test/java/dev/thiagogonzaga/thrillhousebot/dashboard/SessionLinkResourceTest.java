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

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SessionLinkResourceTest extends ReviewSessionTestSupport {

  @Inject ReviewSessionPersistence persistence;

  private ReviewSession persistSession() throws Exception {
    ReviewSession session = ReviewSession.create("owner/repo", 7, "Title", "sha1");
    tx.begin();
    persistence.create(session);
    tx.commit();
    return session;
  }

  @Test
  void shouldRedirectKnownPublicIdToSessionsPageWithNumericId() throws Exception {
    var session = persistSession();
    assertNotNull(session.getPublicId());

    given()
        .redirects()
        .follow(false)
        .when()
        .get("/session/" + session.getPublicId())
        .then()
        .statusCode(303)
        .header("Location", endsWith("/dashboard/sessions/?id=" + session.id));
  }

  @Test
  void shouldFallBackToSessionsListForUnknownPublicId() {
    given()
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
        .redirects()
        .follow(false)
        .when()
        .get("/session/12345")
        .then()
        .statusCode(303)
        .header("Location", endsWith("/dashboard/sessions/"));
  }
}
