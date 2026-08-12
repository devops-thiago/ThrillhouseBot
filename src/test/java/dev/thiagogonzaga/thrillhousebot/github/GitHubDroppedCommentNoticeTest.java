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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.WebApplicationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof of #578, through a real REST client against a loopback GitHub: a reply GitHub
 * threw away leaves a trace the user can actually see.
 *
 * <p>What is asserted is the body GitHub receives, because that is the only thing the user ever
 * sees — the log line the retry already writes is exactly what #578 says is not enough.
 *
 * <p>The pull-request numbers are unique to this class: the registry behind the notice is
 * process-wide by design, and a number nobody else posts to keeps this test from leaning on, or
 * disturbing, anything else in the suite.
 */
@QuarkusTest
class GitHubDroppedCommentNoticeTest {

  private static final String ACCEPT = "application/vnd.github+json";

  /** The PR whose reply is thrown away. */
  private static final int LOSING_PR = 5781;

  /** A different PR, to show the notice does not follow the bot around. */
  private static final int OTHER_PR = 5782;

  private static final String SECONDARY_LIMIT_BODY =
      "{\"message\":\"You have exceeded a secondary rate limit. Please wait a few minutes before"
          + " you try again.\",\"documentation_url\":\"https://docs.github.com/rest\"}";

  private HttpServer server;
  private GitHubCommentClient client;
  private final AtomicBoolean throttling = new AtomicBoolean(true);
  private final List<String> received = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startGitHub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          received.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          if (throttling.get()) {
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.getResponseHeaders().add("x-ratelimit-remaining", "0");
            respond(exchange, 403, SECONDARY_LIMIT_BODY);
          } else {
            respond(exchange, 201, "{\"id\":42,\"html_url\":\"https://github.test/c/42\"}");
          }
        });
    server.start();
    client =
        QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .build(GitHubCommentClient.class);
  }

  @AfterEach
  void stopGitHub() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private void comment(int pullNumber, String body) {
    client.createComment(
        "Bearer token",
        ACCEPT,
        "owner",
        "repo",
        pullNumber,
        new GitHubCommentClient.CreateCommentRequest(body));
  }

  @Test
  void aReplyGitHubThrewAwayIsAnnouncedOnTheNextCommentThatLands() {
    assertThrows(
        WebApplicationException.class,
        () -> comment(LOSING_PR, "the answer to /add-docs"),
        "the throttled reply is dropped once the budget is spent");
    assertEquals(GitHubWriteRetry.MAX_ATTEMPTS, received.size(), "the budget was actually spent");
    received.clear();
    throttling.set(false);

    comment(LOSING_PR, "the answer to /describe");

    // Without this the user sees a command that simply never answered, which is what #538 fixed
    // for the decline case and what #578 is about for the dropped case.
    var landed = received.getFirst();
    assertTrue(landed.contains("[!WARNING]"), landed);
    assertTrue(landed.contains("earlier reply on this pull request was never posted"), landed);
    assertTrue(landed.contains("run the command again"), landed);
    // It rides along with real content rather than costing a comment of its own.
    assertTrue(landed.contains("the answer to /describe"), landed);
  }

  @Test
  void theNoticeStaysOnThePullRequestThatLostTheReply() {
    assertThrows(WebApplicationException.class, () -> comment(OTHER_PR + 100, "a lost answer"));
    received.clear();
    throttling.set(false);

    comment(OTHER_PR, "an unrelated answer");

    var landed = received.getFirst();
    assertTrue(landed.contains("an unrelated answer"), landed);
    assertFalse(landed.contains("[!WARNING]"), landed);
  }
}
