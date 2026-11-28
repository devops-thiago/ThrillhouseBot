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

import static dev.thiagogonzaga.thrillhousebot.github.GitHubStub.gzipped;
import static dev.thiagogonzaga.thrillhousebot.github.GitHubStub.json;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.ProcessingException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.connector.GitHubConnectorRequest;

class GitHubAppConnectorTest {

  private static final String APP = "{\"id\":1,\"slug\":\"thrillhousebot\"}";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);

  private final GitHubStub stub = GitHubStub.start();
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void stop() {
    stub.close();
    httpClient.shutdownNow();
  }

  private GitHub github(String endpoint, Duration timeout) throws IOException {
    return new GitHubBuilder()
        .withConnector(new GitHubAppConnector(httpClient, timeout))
        .withEndpoint(endpoint)
        .withJwtToken("app-jwt")
        .build();
  }

  private GitHub github() throws IOException {
    return github(stub.url(), TIMEOUT);
  }

  @Test
  void aCompressedAnswerIsReadFromOneRequestCarryingTheJwt() throws IOException {
    stub.on("GET /app", gzipped(200, APP));

    var app = github().getApp();

    assertEquals("thrillhousebot", app.getSlug());
    assertEquals(1, stub.received().size());
    assertEquals("Bearer app-jwt", stub.received().get(0).authorization());
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 410, 422, 500, 503})
  void aRefusalIsTheRestClientsExceptionWithNoBodyInItsMessage(int status) throws IOException {
    stub.on("GET /app", json(status, "{\"message\":\"provider-detail\"}"));
    var github = github();

    var refused = assertThrows(ClientWebApplicationException.class, github::getApp);

    assertEquals(status, refused.getResponse().getStatus());
    assertEquals("GitHub App request failed with status " + status, refused.getMessage());
    assertEquals("{\"message\":\"provider-detail\"}", refused.getResponse().getEntity());
    assertEquals(1, stub.received().size(), "one attempt, as the REST client made");
  }

  @Test
  void aCompressedRefusalStillClassifiesAsAnExpiredCredential() throws IOException {
    stub.on("GET /app", gzipped(401, "{\"message\":\"Bad credentials\"}"));
    var github = github();

    var refused = assertThrows(ClientWebApplicationException.class, github::getApp);

    assertTrue(GitHubApiError.of(refused).orElseThrow().isExpiredCredential());
  }

  @Test
  void aRateLimitIsReportedWithItsHeadersInsteadOfBeingSleptThrough() throws IOException {
    stub.on(
        "GET /app",
        json(
            403,
            "{\"message\":\"API rate limit exceeded\"}",
            "Retry-After",
            "3600",
            "x-ratelimit-remaining",
            "0",
            "x-ratelimit-reset",
            "4102444800",
            "x-ratelimit-resource",
            "core"));
    var github = github();

    var refused = assertThrows(ClientWebApplicationException.class, github::getApp);

    var response = refused.getResponse();
    assertEquals("3600", response.getHeaderString("Retry-After"));
    assertEquals("0", response.getHeaderString("x-ratelimit-remaining"));
    assertEquals("4102444800", response.getHeaderString("x-ratelimit-reset"));
    assertEquals("core", response.getHeaderString("x-ratelimit-resource"));
    assertTrue(GitHubApiError.of(refused).orElseThrow().isThrottled());
    assertEquals(1, stub.received().size());
  }

  @Test
  void anUnansweredRequestTimesOutAndIsNotTakenForAnInterrupt() throws IOException {
    stub.on("GET /app", stub.silent());

    assertTimedOut(github(stub.url(), SHORT_TIMEOUT));
  }

  @Test
  void theTimeoutAlsoBoundsABodyThatNeverArrives() throws IOException {
    stub.on("GET /app", stub.stalledBody());

    assertTimedOut(github(stub.url(), SHORT_TIMEOUT));
  }

  private void assertTimedOut(GitHub github) {
    var failure = assertThrows(ProcessingException.class, github::getApp);

    var cause = assertInstanceOf(HttpTimeoutException.class, failure.getCause());
    assertFalse(InstallationRepository.isInterrupt(cause));
    assertFalse(Thread.currentThread().isInterrupted());
    assertEquals(1, stub.received().size(), "a timeout is not retried");
  }

  @Test
  void anInterruptStopsTheWaitAndLeavesTheThreadInterrupted() throws Exception {
    stub.on("GET /app", stub.silent());
    var github = github();
    var failure = new AtomicReference<Throwable>();
    var interrupted = new AtomicBoolean();
    var caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    github.getApp();
                  } catch (Exception e) {
                    failure.set(e);
                    interrupted.set(Thread.currentThread().isInterrupted());
                  }
                });
    while (stub.received().isEmpty()) {
      Thread.onSpinWait();
    }

    caller.interrupt();

    assertTrue(caller.join(TIMEOUT));
    var cause = assertInstanceOf(ProcessingException.class, failure.get()).getCause();
    var interruption = assertInstanceOf(InterruptedIOException.class, cause);
    assertTrue(InstallationRepository.isInterrupt(interruption));
    assertTrue(interrupted.get());
  }

  @Test
  void aConnectionFailureIsAProcessingException() throws IOException {
    var github = github();
    stub.close();

    var failure = assertThrows(ProcessingException.class, github::getApp);

    assertInstanceOf(IOException.class, failure.getCause());
  }

  @Test
  void aWriteWithNoContentIsSentWithoutABody() throws IOException {
    stub.on("POST /app", json(200, APP));
    var request = mock(GitHubConnectorRequest.class);
    when(request.url()).thenReturn(URI.create(stub.url() + "/app").toURL());
    when(request.method()).thenReturn("POST");
    when(request.hasBody()).thenReturn(true);
    when(request.allHeaders()).thenReturn(Map.of());

    try (var response = new GitHubAppConnector(httpClient, TIMEOUT).send(request)) {
      assertEquals(200, response.statusCode());
    }

    assertEquals("", stub.received().get(0).body());
  }

  @ParameterizedTest
  @CsvSource({"200, ''", "302, ''", "404, the body", "503, the body"})
  void aDownloadKeepsOnlyTheBodyOfARefusal(int status, String kept) {
    var answer = mock(HttpResponse.ResponseInfo.class);
    when(answer.statusCode()).thenReturn(status);
    var subscriber = GitHubAppConnector.REFUSAL_BODY_ONLY.apply(answer);

    subscriber.onSubscribe(mock(Flow.Subscription.class));
    subscriber.onNext(List.of(ByteBuffer.wrap("the body".getBytes(StandardCharsets.UTF_8))));
    subscriber.onComplete();

    var body = subscriber.getBody().toCompletableFuture().join();
    assertEquals(kept, new String(body, StandardCharsets.UTF_8));
  }

  /**
   * What {@code send} keeps of the body GitHub answers {@code path} with: the HTTP client here
   * feeds the body to whatever handler the connector asks for and records what comes out of it.
   */
  private static String keptBy(String path, int status) throws IOException {
    var kept = new AtomicReference<byte[]>();
    var client = mock(HttpClient.class);
    when(client.sendAsync(any(), any()))
        .thenAnswer(
            call -> {
              HttpResponse.BodyHandler<byte[]> handler = call.getArgument(1);
              var info = mock(HttpResponse.ResponseInfo.class);
              when(info.statusCode()).thenReturn(status);
              var subscriber = handler.apply(info);
              subscriber.onSubscribe(mock(Flow.Subscription.class));
              subscriber.onNext(List.of(ByteBuffer.wrap("the body".getBytes(UTF_8))));
              subscriber.onComplete();
              kept.set(subscriber.getBody().toCompletableFuture().join());
              var answer = mock(HttpResponse.class);
              when(answer.statusCode()).thenReturn(status);
              when(answer.headers()).thenReturn(HttpHeaders.of(Map.of(), (_, _) -> true));
              when(answer.body()).thenReturn(kept.get());
              return CompletableFuture.completedFuture(answer);
            });
    var request = mock(GitHubConnectorRequest.class);
    when(request.url()).thenReturn(URI.create("https://github.test/api/v3" + path).toURL());
    when(request.method()).thenReturn("GET");
    when(request.allHeaders()).thenReturn(Map.of());

    try (var response = new GitHubAppConnector(client, TIMEOUT).send(request)) {
      assertEquals(status, response.statusCode());
    } catch (GitHubAppConnector.DownloadRedirect | ClientWebApplicationException _) {
      // How a download and a refusal end; the body kept is what is under test.
    }
    return new String(kept.get(), UTF_8);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/repos/o/r/actions/artifacts/99/zip", "/repos/o/r/actions/jobs/7/logs"})
  void sendDoesNotKeepTheBodyOfADownloadGitHubDidNotRefuse(String path) throws IOException {
    assertEquals("", keptBy(path, 200));
    assertEquals("", keptBy(path, 302));
    assertEquals("the body", keptBy(path, 404));
  }

  @Test
  void sendKeepsTheBodyOfEveryOtherAnswer() throws IOException {
    assertEquals("the body", keptBy("/repos/o/r/actions/artifacts/99", 200));
    assertEquals("the body", keptBy("/repos/o/r", 404));
  }

  @Test
  void anAddressThatIsNotAUriIsReported() throws IOException {
    var github = github(stub.url() + "/with space", TIMEOUT);

    var failure = assertThrows(ProcessingException.class, github::getApp);

    assertEquals("GitHub App request failed: invalid request URL", failure.getMessage());
  }
}
