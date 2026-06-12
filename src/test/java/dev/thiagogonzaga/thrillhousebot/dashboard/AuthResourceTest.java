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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for AuthResource with the default test configuration (client ID is configured via {@code
 * GITHUB_CLIENT_ID=dummy-client-id} in test application.properties).
 */
@QuarkusTest
@TestHTTPEndpoint(AuthResource.class)
class AuthResourceTest {

  // ── login() ────────────────────────────────────────────────────────────

  @Test
  void loginShouldRedirectToGitHubWhenClientIdConfigured() {
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/login")
        .then()
        .statusCode(303)
        .header("Location", containsString("https://github.com/login/oauth/authorize"))
        .header("Location", containsString("scope=read:user,read:org"));
  }

  // ── callback() ─────────────────────────────────────────────────────────

  @Test
  void callbackShouldReturn400WhenCodeIsNull() {
    given()
        .when()
        .get("/callback")
        .then()
        .statusCode(400)
        .body("error", equalTo("Missing authorization code"));
  }

  @Test
  void callbackShouldReturn403WhenStateCookieIsNull() {
    given()
        .queryParam("code", "test-code")
        .queryParam("state", "some-state")
        .when()
        .get("/callback")
        .then()
        .statusCode(403)
        .body("error", equalTo("Invalid state parameter"));
  }

  @Test
  void callbackShouldReturn403WhenStateParamIsNull() {
    given()
        .queryParam("code", "test-code")
        .cookie("thrillhouse_oauth_state", "some-state")
        .when()
        .get("/callback")
        .then()
        .statusCode(403)
        .body("error", equalTo("Invalid state parameter"));
  }

  @Test
  void callbackShouldReturn403WhenStateDoesNotMatchCookie() {
    given()
        .queryParam("code", "test-code")
        .queryParam("state", "state-from-param")
        .cookie("thrillhouse_oauth_state", "different-state-from-cookie")
        .when()
        .get("/callback")
        .then()
        .statusCode(403)
        .body("error", equalTo("Invalid state parameter"));
  }

  // ── me() ───────────────────────────────────────────────────────────────

  @Test
  void meShouldReturn401WhenSessionCookieIsMissing() {
    given().when().get("/me").then().statusCode(401).body("error", equalTo("Not authenticated"));
  }

  @Test
  void meShouldReturn401WhenSessionCookieIsBlank() {
    given()
        .cookie("thrillhouse_session", "")
        .when()
        .get("/me")
        .then()
        .statusCode(401)
        .body("error", equalTo("Not authenticated"));
  }

  @Test
  void meShouldReturn401WhenSessionCookieIsWhitespace() {
    given()
        .cookie("thrillhouse_session", "   ")
        .when()
        .get("/me")
        .then()
        .statusCode(401)
        .body("error", equalTo("Not authenticated"));
  }

  @Test
  void logoutShouldClearSessionCookie() {
    given()
        .cookie("thrillhouse_session", "valid-token")
        .when()
        .post("/logout")
        .then()
        .statusCode(204)
        .header("Set-Cookie", containsString("thrillhouse_session="));
  }

  // ── me() success/expired via session store ──────────────────────────

  @Nested
  class LoginEndpoint {

    @Test
    void loginShouldReturn503WhenClientIdNotConfigured() {
      var authResource = AuthResourceTestFixtures.newContext().withClientId("   ").build();

      var response = authResource.login();

      assertEquals(503, response.getStatus());
      @SuppressWarnings("unchecked")
      var body = (Map<String, Object>) response.getEntity();
      assertEquals("Dashboard OAuth not configured", body.get("error"));
    }
  }

  @Nested
  class MeEndpoint {

    private AuthResource authResource;
    private AuthResourceTestFixtures.Context fixtureContext;

    @BeforeEach
    void setUp() {
      fixtureContext = AuthResourceTestFixtures.newContext();
      authResource = fixtureContext.build();
    }

    @Test
    void meShouldReturnUserDataWhenSessionValid() {
      var sessionId =
          fixtureContext.sessionStore.createSession(
              "testuser", "gho_secret", "https://avatars.example/u", "Test User");

      var response = authResource.me(sessionId);

      assertEquals(200, response.getStatus());
      @SuppressWarnings("unchecked")
      var body = (Map<String, Object>) response.getEntity();
      assertEquals("testuser", body.get("login"));
      assertEquals("https://avatars.example/u", body.get("avatarUrl"));
      assertEquals("Test User", body.get("name"));
    }

    @Test
    void meShouldReturn401WhenSessionUnknown() {
      var response = authResource.me("unknown-session-id");

      assertEquals(401, response.getStatus());
      @SuppressWarnings("unchecked")
      var body = (Map<String, Object>) response.getEntity();
      assertEquals("Session expired", body.get("error"));
    }

    @Test
    void meShouldFallbackToLoginWhenNameIsNull() {
      var sessionId =
          fixtureContext.sessionStore.createSession(
              "no-name-user", "gho_secret", "https://avatars.example/u", "no-name-user");

      var response = authResource.me(sessionId);

      assertEquals(200, response.getStatus());
      @SuppressWarnings("unchecked")
      var body = (Map<String, Object>) response.getEntity();
      assertEquals("no-name-user", body.get("login"));
      assertEquals("no-name-user", body.get("name"));
    }
  }
}
