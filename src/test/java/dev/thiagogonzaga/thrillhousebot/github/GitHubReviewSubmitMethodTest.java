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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** #1434: GitHub documents submit-review as POST …/reviews/{id}/events; the client must match. */
@QuarkusTest
class GitHubReviewSubmitMethodTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
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

  @Test
  void submitReviewUsesPostOnTheEventsRoute() throws IOException {
    var method = new AtomicReference<String>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/repos/o/r/pulls/7/reviews/99/events",
        exchange -> {
          method.set(exchange.getRequestMethod());
          respond(
              exchange,
              200,
              "{\"id\":99,\"body\":\"ok\",\"state\":\"COMMENTED\",\"commit_id\":\"sha\",\"user\":null}");
        });
    server.start();
    var client =
        QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .build(GitHubReviewClient.class);

    client.submitReviewOnce(
        "Bearer tok",
        "application/vnd.github+json",
        "o",
        "r",
        7,
        99L,
        new GitHubReviewClient.SubmitReviewRequest("body", "COMMENT"));

    assertEquals("POST", method.get());
  }
}
