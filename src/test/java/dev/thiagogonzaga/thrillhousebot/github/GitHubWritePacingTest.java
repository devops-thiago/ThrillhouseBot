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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof of #579, through a real REST client against a loopback GitHub.
 *
 * <p>{@link GitHubWritePacerTest} pins the arithmetic of the shared cursor; what only a real client
 * can show is that the limiter is actually on the path a comment takes, and that it holds when the
 * burst is genuinely concurrent — several PRs commanded at once, which is exactly what produced the
 * 29 rejections behind #568. The assertion is on when the requests <em>arrive at GitHub</em>, not
 * on anything the bot recorded about itself.
 *
 * <p>The threshold is deliberately below the configured one-second envelope: a loaded machine can
 * only push the arrivals further apart, never closer together, so the test cannot flake in the
 * direction that would matter.
 */
@QuarkusTest
class GitHubWritePacingTest {

  private static final String ACCEPT = "application/vnd.github+json";

  /** Four concurrent posts: one more than the retry budget, so backoff cannot explain the gaps. */
  private static final int BURST = 4;

  /** Floor on the spacing GitHub must see, in milliseconds, against a 1s configured interval. */
  private static final long MIN_GAP_MS = 800;

  private HttpServer server;
  private final List<Long> arrivals = new CopyOnWriteArrayList<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private GitHubCommentClient clientAnsweredBy(HttpHandler handler) throws IOException {
    return startGitHub(handler).build(GitHubCommentClient.class);
  }

  private QuarkusRestClientBuilder startGitHub(HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return QuarkusRestClientBuilder.newBuilder()
        .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
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
  void aConcurrentBurstOfCommentsReachesGitHubSpacedOutRatherThanAllAtOnce() throws Exception {
    var client =
        clientAnsweredBy(
            exchange -> {
              arrivals.add(System.nanoTime());
              respond(exchange, 201, "{\"id\":1,\"html_url\":\"https://github.test/c/1\"}");
            });

    var posted = new ArrayList<Future<GitHubCommentClient.CommentResponse>>();
    try (var burst = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int pr = 1; pr <= BURST; pr++) {
        int number = pr;
        posted.add(
            burst.submit(
                () ->
                    client.createComment(
                        "Bearer token",
                        ACCEPT,
                        "owner",
                        "repo",
                        number,
                        new GitHubCommentClient.CreateCommentRequest("the generated reply"))));
      }
      for (var reply : posted) {
        reply.get(2, TimeUnit.MINUTES);
      }
    }

    assertEquals(BURST, arrivals.size(), "every comment still posted");
    assertSpacedOut();
  }

  @Test
  void everyContentCreatingCallIsPacedAndNotJustTheConversationComment() throws Exception {
    var builder =
        startGitHub(
            exchange -> {
              arrivals.add(System.nanoTime());
              respond(exchange, 201, "{\"id\":1,\"html_url\":\"https://github.test/c/1\"}");
            });
    var comments = builder.build(GitHubCommentClient.class);
    var reviews = builder.build(GitHubReviewClient.class);

    // All five of the calls GitHub counts as content creation, fired at once. The triggering
    // scenarios in #579 — a review posting many inline findings, several PRs reviewed at once —
    // run through the review client, so pacing only the conversation comment would leave the burst
    // this PR exists to prevent fully intact.
    List<Callable<Object>> everyWrite =
        List.of(
            () ->
                comments.createComment(
                    "Bearer token",
                    ACCEPT,
                    "owner",
                    "repo",
                    1,
                    new GitHubCommentClient.CreateCommentRequest("a reply")),
            () ->
                comments.updateComment(
                    "Bearer token",
                    ACCEPT,
                    "owner",
                    "repo",
                    99L,
                    new GitHubCommentClient.CreateCommentRequest("an edited summary")),
            () ->
                reviews.createReview(
                    "Bearer token",
                    ACCEPT,
                    "owner",
                    "repo",
                    1,
                    new GitHubReviewClient.CreateReviewRequest("sha", "a review", "COMMENT", null)),
            () ->
                reviews.createPullRequestComment(
                    "Bearer token",
                    ACCEPT,
                    "owner",
                    "repo",
                    1,
                    new GitHubReviewClient.CreatePullRequestCommentRequest(
                        "sha", "an inline finding", "src/Main.java", 3, "RIGHT", null, null)),
            () ->
                reviews.replyToReviewComment(
                    "Bearer token",
                    ACCEPT,
                    "owner",
                    "repo",
                    1,
                    77L,
                    new GitHubReviewClient.ReplyToReviewCommentRequest("a thread reply")));

    var posted = new ArrayList<Future<Object>>();
    try (var burst = Executors.newVirtualThreadPerTaskExecutor()) {
      everyWrite.forEach(write -> posted.add(burst.submit(write)));
      for (var write : posted) {
        write.get(2, TimeUnit.MINUTES);
      }
    }

    assertEquals(everyWrite.size(), arrivals.size(), "every write still reached GitHub");
    assertSpacedOut();
  }

  private void assertSpacedOut() {
    var ordered = arrivals.stream().sorted().toList();
    var gaps = new ArrayList<Long>();
    for (int i = 1; i < ordered.size(); i++) {
      gaps.add(TimeUnit.NANOSECONDS.toMillis(ordered.get(i) - ordered.get(i - 1)));
    }
    assertTrue(
        gaps.stream().allMatch(gap -> gap >= MIN_GAP_MS),
        () ->
            "content-creating calls reached GitHub "
                + gaps
                + "ms apart; its envelope is one per second, and a burst tighter than that is"
                + " exactly what it answers with 403");
  }
}
