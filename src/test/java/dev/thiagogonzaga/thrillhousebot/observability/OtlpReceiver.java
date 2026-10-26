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
package dev.thiagogonzaga.thrillhousebot.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in OTLP/HTTP collector for tests: it accepts every export request, answers 200, and keeps
 * each request's path and body. OTLP/protobuf writes metric names, span names and log bodies as
 * plain UTF-8 strings, so a body can be searched without decoding it.
 *
 * <p>A test profile starts the receiver before the application, in a different class loader from
 * the one the {@code @QuarkusTest} class runs in, so the test asks the receiver what it got over
 * HTTP ({@link #received(String, String, String)}) rather than through a shared static.
 */
final class OtlpReceiver {

  private static final String QUERY_PATH = "/received";

  private record Export(String path, String body) {}

  private final HttpServer server;
  private final List<Export> exports = new CopyOnWriteArrayList<>();

  private OtlpReceiver(HttpServer server) {
    this.server = server;
  }

  /** Starts a receiver on a free loopback port. */
  static OtlpReceiver start() {
    try {
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      var receiver = new OtlpReceiver(server);
      server.createContext("/", receiver::accept);
      server.start();
      // A test profile has no teardown, so the receiver stops with the test JVM.
      Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
      return receiver;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The base URL to hand the exporter, as {@code OTEL_EXPORTER_ENDPOINT} would be set. */
  String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Asks the receiver at {@code endpoint} whether an export to {@code path} carried {@code text}.
   */
  static boolean received(String endpoint, String path, String text) {
    var query =
        "?path="
            + URLEncoder.encode(path, StandardCharsets.UTF_8)
            + "&text="
            + URLEncoder.encode(text, StandardCharsets.UTF_8);
    return Boolean.parseBoolean(get(endpoint + QUERY_PATH + query));
  }

  /** The paths every export so far went to, for an assertion message. */
  static String describe(String endpoint) {
    return "OTLP exports to " + endpoint + ": " + get(endpoint + QUERY_PATH);
  }

  private static String get(String url) {
    try (var client = HttpClient.newHttpClient()) {
      return client
          .send(
              HttpRequest.newBuilder(URI.create(url)).GET().build(),
              HttpResponse.BodyHandlers.ofString())
          .body();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private void accept(HttpExchange exchange) throws IOException {
    try (exchange) {
      var path = exchange.getRequestURI().getPath();
      if (QUERY_PATH.equals(path)) {
        answer(exchange, exchange.getRequestURI().getRawQuery());
        return;
      }
      var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      exports.add(new Export(path, body));
      exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
      exchange.sendResponseHeaders(200, -1);
    }
  }

  private void answer(HttpExchange exchange, String rawQuery) throws IOException {
    String answer;
    if (rawQuery == null) {
      answer = exports.stream().map(Export::path).toList().toString();
    } else {
      var params = params(rawQuery);
      answer =
          String.valueOf(
              exports.stream()
                  .anyMatch(
                      e ->
                          e.path().equals(params.get("path"))
                              && e.body().contains(params.get("text"))));
    }
    var bytes = answer.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static Map<String, String> params(String rawQuery) {
    var params = new HashMap<String, String>();
    for (var pair : rawQuery.split("&")) {
      var at = pair.indexOf('=');
      params.put(
          URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
    }
    return params;
  }
}
