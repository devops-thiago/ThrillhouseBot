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

import static dev.thiagogonzaga.thrillhousebot.github.GitHubStub.json;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.dashboard.DashboardSessionStore;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

/**
 * With only {@code GITHUB_URL} naming another GitHub, every client the application has reaches that
 * host on the paths of GitHub Enterprise Server: the bot's REST and GraphQL clients, its reads
 * through hub4j with a caller's token, the GitHub App extension's REST and GraphQL clients, and
 * dashboard sign-in and sign-out.
 *
 * <p>The profile blanks {@code GITHUB_API_URL} and {@code GITHUB_GRAPHQL_URL}, which GitHub Actions
 * sets to github.com in every job and which would otherwise win over the derived addresses.
 */
@QuarkusTest
@TestProfile(GitHubAddressWiringTest.OtherGitHubProfile.class)
class GitHubAddressWiringTest {

  private static final String QUERY = "query { viewer { login } }";

  @ConfigProperty(name = "thrillhousebot.github.web-url")
  String webUrl;

  @Inject @RestClient GitHubInstallationClient botRest;

  @Inject @RestClient GitHubGraphQLClient botGraphql;

  @Inject GitHubTokenReads tokenReads;

  @Inject GitHubClientProvider extension;

  @Inject DashboardSessionStore sessions;

  /** Fails unless the stub standing in for the other GitHub received such a request. */
  private void assertReceived(String start, String authorization)
      throws IOException, InterruptedException {
    var received = GitHubStub.receivedBy(webUrl);
    assertTrue(
        received.stream()
            .anyMatch(line -> line.startsWith(start + " ") && line.contains(authorization)),
        () -> start + " with " + authorization + " is not among " + received);
  }

  @Test
  void theBotsOwnClientsReachTheOtherHost() throws Exception {
    botRest.getRepository("token bot-rest", "application/vnd.github+json", "octo", "repo").close();
    botGraphql.executeOnce(
        "Bearer bot-graphql", new GitHubGraphQLClient.GraphQLRequest(QUERY, Map.of()));

    assertReceived("GET /api/v3/repos/octo/repo", "token bot-rest");
    assertReceived("POST /api/graphql", "Bearer bot-graphql");
  }

  @Test
  void theBotsHub4jReadsReachTheOtherHost() throws Exception {
    assertEquals(List.of(), tokenReads.installationRepositories("token hub4j-read"));

    assertReceived("GET /api/v3/installation/repositories?per_page=100", "token hub4j-read");
  }

  @Test
  void theExtensionsClientsReachTheOtherHost() throws Exception {
    extension.getApplicationClient().getApp();
    extension.getInstallationClient(7).getRepository("octo/repo");
    try (var graphql = extension.getInstallationGraphQLClient(7)) {
      assertEquals(
          "bot", graphql.executeSync(QUERY).getData().getJsonObject("viewer").getString("login"));
    }

    assertReceived("GET /api/v3/app", "Bearer ");
    assertReceived("POST /api/v3/app/installations/7/access_tokens", "Bearer ");
    assertReceived("GET /api/v3/repos/octo/repo", "ghs_other");
    assertReceived("POST /api/graphql", "ghs_other");
  }

  @Test
  void signInAndSignOutReachTheOtherHost() throws Exception {
    var authorize =
        given().redirects().follow(false).get("/api/auth/login").then().statusCode(303).extract();
    assertTrue(
        authorize.header("Location").startsWith(webUrl + "/login/oauth/authorize?"),
        authorize.header("Location"));

    given()
        .redirects()
        .follow(false)
        .cookie("thrillhouse_oauth_state", "state")
        .get("/api/auth/callback?code=abc&state=state");
    var session = sessions.createSession("octocat", "gho_signed_out", null, "Octo Cat");
    given().cookie("thrillhouse_session", session).post("/api/auth/logout").then().statusCode(204);

    assertReceived("POST /login/oauth/access_token", "null");
    assertReceived("GET /api/v3/user", "Bearer gho_signed_in");
    assertReceived("GET /api/v3/user/installations?per_page=100&page=1", "gho_signed_in");
    assertReceived("DELETE /api/v3/applications/dummy-client-id/token", "Basic ");
  }

  public static class OtherGitHubProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      var stub =
          GitHubStub.start()
              .on("GET /api/v3/app", json(200, "{\"id\":12345}"))
              .on("GET /api/v3/app/installations/7", json(200, "{\"id\":7}"))
              .on(
                  "POST /api/v3/app/installations/7/access_tokens",
                  json(201, "{\"token\":\"ghs_other\",\"expires_at\":\"2099-01-01T00:00:00Z\"}"))
              .on(
                  "GET /api/v3/repos/octo/repo",
                  json(200, "{\"id\":1,\"name\":\"repo\",\"full_name\":\"octo/repo\"}"))
              .on(
                  "GET /api/v3/installation/repositories",
                  json(200, "{\"total_count\":0,\"repositories\":[]}"))
              .on("POST /api/graphql", json(200, "{\"data\":{\"viewer\":{\"login\":\"bot\"}}}"))
              .on(
                  "POST /login/oauth/access_token",
                  json(200, "{\"access_token\":\"gho_signed_in\"}"))
              .on("GET /api/v3/user", json(200, "{\"login\":\"octocat\"}"));
      Runtime.getRuntime().addShutdownHook(new Thread(stub::close));
      var key =
          ConfigProvider.getConfig().getValue("thrillhousebot.github.private-key", String.class);
      return Map.of(
          "GITHUB_URL",
          stub.url() + "/",
          "GITHUB_API_URL",
          "",
          "GITHUB_GRAPHQL_URL",
          "",
          "GITHUB_APP_ID",
          "12345",
          "GITHUB_PRIVATE_KEY",
          key);
    }
  }
}
