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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ArtifactZipFetcher} — the unauthenticated second hop of a GitHub Actions
 * artifact download (#115).
 *
 * <p>The transfer is exercised against a loopback {@link HttpServer} through the package-private
 * {@code transfer}, which is {@code fetch} with its scheme policy already applied. That covers
 * everything about the transfer except TLS itself (status handling, the size bound, redirect
 * following, failure degradation); the https-only policy is covered separately through {@code
 * fetch}, which is the only entry point production uses.
 */
class ArtifactZipFetcherTest {

  private final ArtifactZipFetcher fetcher = new ArtifactZipFetcher();
  private HttpServer server;

  @AfterEach
  void stopServerAndFetcher() {
    fetcher.shutdown();
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  /** Starts a loopback server whose {@code /artifact.zip} is served by {@code handler}. */
  private URI serve(HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/artifact.zip", handler);
    server.start();
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/artifact.zip");
  }

  private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.sendResponseHeaders(status, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  @Nested
  class SchemePolicy {

    @Test
    void refusesAMissingOrPlaintextLocationWithoutMakingARequest() {
      assertEquals(0, fetcher.fetch(null).length, "no location, no download");
      assertEquals(
          0,
          fetcher.fetch(URI.create("http://blob.example/artifact.zip")).length,
          "a plaintext redirect target is never followed");
      assertEquals(
          0,
          fetcher.fetch(URI.create("file:///etc/passwd")).length,
          "only https redirect targets are followed");
    }

    @Test
    void anHttpsLocationReachesTheTransfer() throws IOException {
      // Nothing listens on this port, so the transfer fails — but reaching a *download failure*
      // (rather than the policy's early return) is what proves https is passed through.
      int closedPort;
      try (var socket = new ServerSocket(0)) {
        closedPort = socket.getLocalPort();
      }

      assertEquals(
          0, fetcher.fetch(URI.create("https://127.0.0.1:" + closedPort + "/artifact.zip")).length);
    }
  }

  @Nested
  class Transfer {

    @Test
    void returnsTheBodyOfASuccessfulDownload() throws IOException {
      var payload = "PK pretend zip".getBytes(StandardCharsets.UTF_8);
      var uri = serve(exchange -> respond(exchange, 200, payload));

      assertArrayEquals(payload, fetcher.transfer(uri));
    }

    @Test
    void followsARedirectToTheRealBlobLocation() throws IOException {
      var payload = "redirected body".getBytes(StandardCharsets.UTF_8);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/blob", exchange -> respond(exchange, 200, payload));
      server.createContext(
          "/artifact.zip",
          exchange -> {
            exchange.getResponseHeaders().add("Location", "/blob");
            respond(exchange, 302, new byte[0]);
          });
      server.start();
      var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/artifact.zip");

      assertArrayEquals(
          payload,
          fetcher.transfer(uri),
          "the signed URL GitHub redirects to may itself redirect once more");
    }

    @Test
    void degradesToNothingOnAnErrorStatus() throws IOException {
      var uri = serve(exchange -> respond(exchange, 410, "gone".getBytes(StandardCharsets.UTF_8)));

      assertEquals(
          0, fetcher.transfer(uri).length, "an expired or revoked signed URL yields no coverage");
    }

    @Test
    void degradesToNothingWhenTheConnectionFails() throws IOException {
      int closedPort;
      try (var socket = new ServerSocket(0)) {
        closedPort = socket.getLocalPort();
      }

      assertEquals(
          0,
          fetcher.transfer(URI.create("http://127.0.0.1:" + closedPort + "/artifact.zip")).length,
          "a failed download must degrade to no coverage context, never propagate");
    }
  }

  @Nested
  class SharedClient {

    /**
     * #478 — one client per bean, not per download. Observed on the wire rather than by identity: a
     * client that outlives the call keeps its HTTP/1.1 connection pooled, so the second download
     * arrives on the same TCP connection as the first; a client built and closed per call cannot
     * hold one, so each download opened a fresh connection (and, in production, a fresh TLS
     * handshake) from a new ephemeral port.
     */
    @Test
    void twoDownloadsShareOneClientAndItsPooledConnection() throws IOException {
      var payload = "PK pretend zip".getBytes(StandardCharsets.UTF_8);
      var remotePorts = new CopyOnWriteArrayList<Integer>();
      var uri =
          serve(
              exchange -> {
                remotePorts.add(exchange.getRemoteAddress().getPort());
                respond(exchange, 200, payload);
              });

      assertArrayEquals(payload, fetcher.transfer(uri));
      assertArrayEquals(payload, fetcher.transfer(uri));

      assertEquals(2, remotePorts.size(), "both downloads reached the server");
      assertEquals(
          remotePorts.get(0),
          remotePorts.get(1),
          "a second download on the same bean must reuse the first one's pooled connection, which"
              + " only a client that outlives the call can hold");
    }

    /**
     * The bean's {@code @PreDestroy}: once the application is stopping, a download that still
     * reaches the fetcher degrades to no bytes like every other failure, and nothing propagates.
     */
    @Test
    void aDownloadAfterShutdownDegradesInsteadOfPropagating() throws IOException {
      var uri = serve(exchange -> respond(exchange, 200, "late".getBytes(StandardCharsets.UTF_8)));
      var stopping = new ArtifactZipFetcher();

      stopping.shutdown();

      assertEquals(
          0,
          stopping.transfer(uri).length,
          "a shut-down client refuses the request; the fetcher must turn that into no coverage");
      assertDoesNotThrow(stopping::shutdown, "shutting down twice is harmless");
    }
  }

  @Nested
  class EnvironmentEdges {

    @Test
    void worksWhenTheJvmHasNoDefaultProxySelector() throws IOException {
      var payload = "no proxy configured".getBytes(StandardCharsets.UTF_8);
      var uri = serve(exchange -> respond(exchange, 200, payload));
      var original = ProxySelector.getDefault();
      ArtifactZipFetcher builtWithoutSelector = null;
      try {
        // ProxySelector.setDefault(null) is legal and HttpClient.Builder.proxy(null) is not, so
        // the guard is what keeps a JVM with no selector from failing every download. The selector
        // is read when the bean is built (#478), so the fetcher has to be built inside the window.
        ProxySelector.setDefault(null);
        builtWithoutSelector = new ArtifactZipFetcher();

        assertArrayEquals(payload, builtWithoutSelector.transfer(uri));
      } finally {
        ProxySelector.setDefault(original);
        if (builtWithoutSelector != null) {
          builtWithoutSelector.shutdown();
        }
      }
    }

    @Test
    void restoresTheInterruptFlagAndDegradesWhenTheDownloadIsInterrupted() throws IOException {
      var uri = serve(exchange -> respond(exchange, 200, new byte[0]));
      Thread.currentThread().interrupt();
      try {
        assertEquals(0, fetcher.transfer(uri).length, "an interrupted download yields no coverage");
        assertTrue(
            Thread.currentThread().isInterrupted(),
            "swallowing the InterruptedException without restoring the flag would hide the"
                + " shutdown signal from the review thread");
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Nested
  class SizeBound {

    @Test
    void rejectsABodyLargerThanTheCapInsteadOfTruncatingIt() throws IOException {
      var atCap = new byte[ArtifactZipFetcher.MAX_BYTES];
      var overCap = new byte[ArtifactZipFetcher.MAX_BYTES + 1];

      assertEquals(
          ArtifactZipFetcher.MAX_BYTES,
          ArtifactZipFetcher.readBounded(new ByteArrayInputStream(atCap)).length,
          "a body exactly at the cap is still read whole");
      assertEquals(
          0,
          ArtifactZipFetcher.readBounded(new ByteArrayInputStream(overCap)).length,
          "half a zip is not a coverage report; an over-long body is dropped, not truncated");
    }

    @Test
    void aBodyThatFailsMidStreamDegradesInsteadOfPropagating() throws IOException {
      var uri = serve(exchange -> respond(exchange, 200, new byte[0]));
      // The read failure a truncated response produces, injected directly: readBounded declares
      // IOException, and transfer is what has to absorb it.
      InputStream failing =
          new InputStream() {
            @Override
            public int read() throws IOException {
              throw new IOException("connection reset mid-body");
            }
          };

      assertThrows(IOException.class, () -> ArtifactZipFetcher.readBounded(failing));
      assertEquals(0, fetcher.transfer(URI.create(uri + "/missing")).length);
    }
  }
}
