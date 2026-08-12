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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.WebApplicationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof of both halves of #568, through a real REST client against a loopback GitHub.
 *
 * <p>The unit tests around {@link GitHubWriteRetry} and {@link GitHubErrorLogger} cover the
 * decisions; what only a real client can show is that the pieces are wired at all — that the
 * registered mapper is invoked and, by returning nothing, still leaves callers the exception they
 * are written against, and that the throttled response reaches the retry with its {@code
 * Retry-After} intact instead of being reduced to a bare status.
 *
 * <p>{@code Retry-After: 0} keeps the backoff instant; the wait itself is pinned by {@link
 * GitHubWriteRetryTest}.
 */
@QuarkusTest
class GitHubCommentThrottleTest {

  private static final String ACCEPT = "application/vnd.github+json";

  private static final String SECONDARY_LIMIT_BODY =
      "{\"message\":\"You have exceeded a secondary rate limit. Please wait a few minutes before"
          + " you try again.\",\"documentation_url\":\"https://docs.github.com/rest\"}";

  private static final String PERMISSION_BODY =
      "{\"message\":\"Resource not accessible by integration\"}";

  private HttpServer server;
  private final AtomicInteger attempts = new AtomicInteger();

  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
  private Logger julLogger;
  private Handler capture;
  private Level originalLevel;

  @BeforeEach
  void captureLogging() {
    julLogger = Logger.getLogger(GitHubErrorLogger.class.getName());
    originalLevel = julLogger.getLevel();
    julLogger.setLevel(Level.ALL);
    capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logged.add(record);
          }

          @Override
          public void flush() {
            // Nothing is buffered.
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
    julLogger.addHandler(capture);
  }

  @AfterEach
  void tearDown() {
    julLogger.removeHandler(capture);
    julLogger.setLevel(originalLevel);
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private String log() {
    return logged.stream()
        .map(record -> record.getMessage() + " " + Arrays.toString(record.getParameters()))
        .reduce("", (left, right) -> left + right);
  }

  private GitHubCommentClient clientAnsweredBy(HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler);
    server.start();
    return QuarkusRestClientBuilder.newBuilder()
        .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
        .build(GitHubCommentClient.class);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Test
  void aThrottledCommentIsPostedOnTheRepeatInsteadOfDiscardingTheGeneration() throws IOException {
    var client =
        clientAnsweredBy(
            exchange -> {
              if (attempts.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.getResponseHeaders().add("x-ratelimit-remaining", "0");
                respond(exchange, 403, SECONDARY_LIMIT_BODY);
              } else {
                respond(exchange, 201, "{\"id\":42,\"html_url\":\"https://github.test/c/42\"}");
              }
            });

    var posted =
        client.createComment(
            "Bearer token",
            ACCEPT,
            "owner",
            "repo",
            7,
            new GitHubCommentClient.CreateCommentRequest("the generated reply"));

    assertEquals(42, posted.id());
    assertEquals(2, attempts.get(), "the throttled post is repeated, not dropped");
    var log = log();
    assertTrue(log.contains("secondary rate limit"), log);
    assertTrue(log.contains("retry-after=0"), log);
  }

  @Test
  void aPermissionRefusalStillFailsTheCallAndSaysWhyExactlyOnce() throws IOException {
    var client =
        clientAnsweredBy(
            exchange -> {
              attempts.incrementAndGet();
              respond(exchange, 403, PERMISSION_BODY);
            });

    // The registered logger raises nothing of its own, so callers keep the exception — and the
    // fail-soft handling built on it — that they had before #568.
    assertThrows(
        WebApplicationException.class,
        () ->
            client.createComment(
                "Bearer token",
                ACCEPT,
                "owner",
                "repo",
                7,
                new GitHubCommentClient.CreateCommentRequest("the generated reply")));

    assertEquals(1, attempts.get(), "a refusal that will never succeed is not repeated");
    var log = log();
    assertTrue(log.contains("Resource not accessible by integration"), log);
  }
}
