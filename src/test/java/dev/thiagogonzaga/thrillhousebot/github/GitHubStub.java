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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * A loopback stand-in for the GitHub REST API: routes are keyed by method and path, every request
 * is kept, and an unrouted one is answered 404.
 */
final class GitHubStub implements AutoCloseable {

  private static final String RECEIVED_PATH = "/__received";

  record Received(String method, String uri, String authorization, String body) {}

  @FunctionalInterface
  interface Answer {
    void to(HttpExchange exchange) throws IOException;
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, Answer> routes = new ConcurrentHashMap<>();
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final CountDownLatch closed = new CountDownLatch(1);

  private GitHubStub(HttpServer server) {
    this.server = server;
  }

  static GitHubStub start() {
    try {
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      var stub = new GitHubStub(server);
      server.createContext("/", stub::handle);
      server.setExecutor(stub.executor);
      server.start();
      return stub;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  String url() {
    return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
  }

  GitHubStub on(String methodAndPath, Answer answer) {
    routes.put(methodAndPath, answer);
    return this;
  }

  List<Received> received() {
    return List.copyOf(received);
  }

  /** Asks a stub running in another class loader what it got, one request per line. */
  static List<String> receivedBy(String url) throws IOException, InterruptedException {
    try (var client = HttpClient.newHttpClient()) {
      var request = HttpRequest.newBuilder(URI.create(url + RECEIVED_PATH)).build();
      return client.send(request, HttpResponse.BodyHandlers.ofString()).body().lines().toList();
    }
  }

  static Answer json(int status, String body, String... headers) {
    return exchange -> {
      for (var i = 0; i < headers.length; i += 2) {
        exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
      }
      send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    };
  }

  static Answer gzipped(int status, String body) {
    return exchange -> {
      var bytes = new ByteArrayOutputStream();
      try (var gzip = new GZIPOutputStream(bytes)) {
        gzip.write(body.getBytes(StandardCharsets.UTF_8));
      }
      exchange.getResponseHeaders().add("Content-Encoding", "gzip");
      send(exchange, status, bytes.toByteArray());
    };
  }

  /** Reads the request and never answers it. */
  Answer silent() {
    return _ -> awaitClose();
  }

  /** Sends the response headers and then never the body they announce. */
  Answer stalledBody() {
    return exchange -> {
      exchange.sendResponseHeaders(200, 100);
      exchange.getResponseBody().flush();
      awaitClose();
    };
  }

  private void awaitClose() {
    try {
      closed.await();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private void handle(HttpExchange exchange) throws IOException {
    var path = exchange.getRequestURI().getPath();
    if (RECEIVED_PATH.equals(path)) {
      var lines = new StringBuilder();
      received.forEach(
          r -> lines.append(r.method() + " " + r.uri() + " " + r.authorization() + "\n"));
      send(exchange, 200, lines.toString().getBytes(StandardCharsets.UTF_8));
      return;
    }
    var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    received.add(
        new Received(
            exchange.getRequestMethod(),
            exchange.getRequestURI().toString(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            body));
    routes
        .getOrDefault(
            exchange.getRequestMethod() + " " + path, json(404, "{\"message\":\"Not Found\"}"))
        .to(exchange);
  }

  @Override
  public void close() {
    closed.countDown();
    server.stop(0);
    executor.shutdownNow();
  }
}
