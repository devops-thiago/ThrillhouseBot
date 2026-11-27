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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkiverse.githubapp.GitHubClientProvider;
import jakarta.ws.rs.ProcessingException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

class GitHubAppApiTest {

  private static final String APP = "{\"id\":1,\"slug\":\"thrillhousebot\"}";
  private static final String INSTALLATION = "{\"id\":7,\"account\":{\"login\":\"o\"}}";
  private static final String TOKEN =
      "{\"token\":\"ghs_minted\",\"expires_at\":\"2030-01-01T00:00:00Z\"}";

  private final GitHubStub stub = GitHubStub.start();
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final GitHubClientProvider clientProvider = mock(GitHubClientProvider.class);
  private GitHubAppApi appApi;

  @BeforeEach
  void setUp() {
    var config = mock(ThrillhouseConfig.class);
    when(config.httpRequestTimeout()).thenReturn(Duration.ofSeconds(10));
    when(clientProvider.getApplicationClient()).thenAnswer(_ -> applicationClient());
    appApi = new GitHubAppApi(clientProvider, httpClient, config);
    stub.on("GET /app", json(200, APP));
  }

  @AfterEach
  void stop() {
    stub.close();
    httpClient.shutdownNow();
  }

  /** What the extension does for an application client, as far as these calls can see. */
  private GitHub applicationClient() {
    var builder = new GitHubBuilder().withEndpoint(stub.url()).withJwtToken("app-jwt");
    new FailFastGitHubCustomizer().customizeApplicationClient(builder);
    try {
      return builder.build();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private List<String> requests() {
    return stub.received().stream().map(r -> r.method() + " " + r.uri()).toList();
  }

  @Test
  void mintingTakesTheAppTheInstallationAndTheToken() {
    stub.on("GET /app/installations/7", json(200, INSTALLATION))
        .on("POST /app/installations/7/access_tokens", json(201, TOKEN));

    assertEquals("ghs_minted", appApi.createInstallationToken(7));

    assertEquals(
        List.of("GET /app", "GET /app/installations/7", "POST /app/installations/7/access_tokens"),
        requests());
    assertEquals("{}", stub.received().get(2).body(), "the token request body is forwarded");
  }

  @Test
  void aRepositoryTokenIsAskedForThatRepositoryAlone() {
    stub.on("GET /app/installations/7", json(200, INSTALLATION))
        .on("POST /app/installations/7/access_tokens", json(201, TOKEN));

    assertEquals("ghs_minted", appApi.createRepositoryToken(7, "widgets"));

    assertEquals(
        List.of("GET /app", "GET /app/installations/7", "POST /app/installations/7/access_tokens"),
        requests());
    assertEquals("{\"repositories\":[\"widgets\"]}", stub.received().get(2).body());
  }

  @Test
  void aRepositoryTokenTheInstallationDoesNotCoverIsRefusedAndNothingWiderIsAsked() {
    stub.on("GET /app/installations/7", json(200, INSTALLATION))
        .on("POST /app/installations/7/access_tokens", json(422, "{\"message\":\"nope\"}"));

    assertThrows(RuntimeException.class, () -> appApi.createRepositoryToken(7, "elsewhere"));

    assertEquals(1, requests().stream().filter(r -> r.startsWith("POST ")).count());
  }

  @Test
  void anUnreadableRepositoryTokenAnswerKeepsTheAnswerOutOfTheException() {
    stub.on("GET /app/installations/7", json(200, INSTALLATION))
        .on("POST /app/installations/7/access_tokens", json(201, "{\"token\": ghs_secret"));

    var failure =
        assertThrows(ProcessingException.class, () -> appApi.createRepositoryToken(7, "widgets"));

    assertFalse(String.valueOf(failure.getMessage()).contains("ghs_secret"));
  }

  @Test
  void aGoneInstallationIsTheRestClientsNotFound() {
    var refused =
        assertThrows(ClientWebApplicationException.class, () -> appApi.createInstallationToken(7));

    assertEquals(404, refused.getResponse().getStatus());
    assertEquals(List.of("GET /app", "GET /app/installations/7"), requests());
  }

  @Test
  void anUnreadableTokenAnswerKeepsTheAnswerOutOfTheException() {
    stub.on("GET /app/installations/7", json(200, INSTALLATION))
        .on("POST /app/installations/7/access_tokens", json(201, "{\"token\":\"ghs_leaky\","));

    var failure = assertThrows(ProcessingException.class, () -> appApi.createInstallationToken(7));

    assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains("ghs_leaky"));
  }

  private static String installations(long from, long to) {
    return LongStream.rangeClosed(from, to)
        .mapToObj(id -> "{\"id\":" + id + "}")
        .collect(Collectors.joining(",", "[", "]"));
  }

  private String nextPage(int page) {
    return "<" + stub.url() + "/app/installations?per_page=100&page=" + page + ">; rel=\"next\"";
  }

  @Test
  void listingFollowsEveryPage() {
    stub.on(
        "GET /app/installations",
        exchange -> {
          var second = exchange.getRequestURI().getQuery().endsWith("&page=2");
          var answer =
              second
                  ? json(200, installations(101, 102))
                  : json(200, installations(1, 100), "Link", nextPage(2));
          answer.to(exchange);
        });

    assertEquals(LongStream.rangeClosed(1, 102).boxed().toList(), appApi.listInstallationIds());

    assertEquals(
        List.of(
            "GET /app",
            "GET /app/installations?per_page=100",
            "GET /app/installations?per_page=100&page=2"),
        requests());
  }

  @Test
  void listingGivesUpRatherThanAnswerWithPartOfTheInstallations() {
    stub.on("GET /app/installations", json(200, installations(1, 1), "Link", nextPage(2)));

    var failure = assertThrows(IllegalStateException.class, appApi::listInstallationIds);

    assertEquals("GitHub listing exceeded 100 pages", failure.getMessage());
  }

  @Test
  void anUnreadableListingIsReportedWithoutItsContent() {
    stub.on("GET /app/installations", json(200, "[{\"id\":"));

    var failure = assertThrows(ProcessingException.class, appApi::listInstallationIds);

    assertNull(failure.getCause());
  }

  @Test
  void aRefusedListingIsTheRestClientsException() {
    stub.on("GET /app/installations", json(401, "{\"message\":\"Bad credentials\"}"));

    var refused = assertThrows(ClientWebApplicationException.class, appApi::listInstallationIds);

    assertEquals(401, refused.getResponse().getStatus());
  }

  @Test
  void aKeyTheExtensionCannotSignWithIsAnAuthFailure() {
    var cause = new IllegalStateException("unusable key");
    when(clientProvider.getApplicationClient()).thenThrow(cause);

    var failure = assertThrows(GitHubAuthException.class, () -> appApi.createInstallationToken(7));

    assertSame(cause, failure.getCause());
  }
}
