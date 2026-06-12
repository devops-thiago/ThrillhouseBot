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

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests AuthResource behaviour when no dashboard OAuth client ID is configured. Uses a {@link
 * QuarkusTestProfile} to override the client-id to an empty string, simulating a deployment where
 * dashboard GitHub OAuth is not set up.
 */
@QuarkusTest
@TestHTTPEndpoint(AuthResource.class)
@TestProfile(AuthResourceNoClientIdTest.WithoutClientId.class)
class AuthResourceNoClientIdTest {

  @Test
  void loginShouldReturn503WhenClientIdNotConfigured() {
    given()
        .when()
        .get("/login")
        .then()
        .statusCode(503)
        .body("error", equalTo("Dashboard OAuth not configured"));
  }

  public static class WithoutClientId implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("thrillhousebot.dashboard.github.client-id", "");
    }
  }
}
