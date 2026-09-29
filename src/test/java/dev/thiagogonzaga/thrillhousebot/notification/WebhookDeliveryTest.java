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
package dev.thiagogonzaga.thrillhousebot.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookDeliveryTest {

  private static final byte[] BODY =
      "{\"event\":\"review.completed\"}".getBytes(StandardCharsets.UTF_8);
  private static final WebhookDelivery.Request REQUEST =
      new WebhookDelivery.Request("review.completed", "delivery-1", BODY, "ThrillhouseBot/test");

  private HttpServer server;
  private final List<Integer> statuses = new CopyOnWriteArrayList<>();
  private final AtomicInteger hits = new AtomicInteger();
  private final AtomicInteger redirectTargetHits = new AtomicInteger();
  private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
  private volatile byte[] lastBody;
  private final List<Duration> sleeps = new ArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          var i = hits.getAndIncrement();
          lastBody = exchange.getRequestBody().readAllBytes();
          exchange
              .getRequestHeaders()
              .forEach((k, v) -> lastHeaders.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
          var status = statuses.get(Math.min(i, statuses.size() - 1));
          if (status >= 300 && status < 400) {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.createContext(
        "/elsewhere",
        exchange -> {
          redirectTargetHits.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private NotificationSettings settings(Optional<String> secret, int maxAttempts) {
    return new NotificationSettings(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"),
        NotificationFormat.JSON,
        secret,
        Set.of(ReviewOutcome.Event.COMPLETED),
        false,
        Duration.ofSeconds(5),
        maxAttempts);
  }

  private WebhookDelivery delivery() {
    return new WebhookDelivery(HttpClient.newHttpClient(), sleeps::add);
  }

  @Test
  void deliversTheBodyWithIdentifyingHeadersAndAVerifiableSignature() throws Exception {
    statuses.add(204);

    var result = delivery().deliver(settings(Optional.of("top-secret"), 3), REQUEST);

    assertThat(result).isEqualTo(WebhookDelivery.Result.DELIVERED);
    assertThat(lastBody).isEqualTo(BODY);
    assertThat(lastHeaders)
        .containsEntry("content-type", "application/json; charset=utf-8")
        .containsEntry("user-agent", "ThrillhouseBot/test")
        .containsEntry("x-thrillhousebot-event", "review.completed")
        .containsEntry("x-thrillhousebot-delivery", "delivery-1");
    // A receiver recomputes the HMAC over the raw body with the shared secret, as with GitHub's
    // X-Hub-Signature-256.
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("top-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    var expected = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(lastBody));
    assertThat(lastHeaders).containsEntry("x-thrillhousebot-signature-256", expected);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void unsignedWhenNoSecretIsConfigured() {
    statuses.add(200);

    delivery().deliver(settings(Optional.empty(), 1), REQUEST);

    assertThat(lastHeaders).doesNotContainKey("x-thrillhousebot-signature-256");
  }

  @Test
  void retriesServerErrorsWithBackoffThenDelivers() {
    statuses.addAll(List.of(503, 500, 200));

    var result = delivery().deliver(settings(Optional.empty(), 3), REQUEST);

    assertThat(result).isEqualTo(WebhookDelivery.Result.DELIVERED);
    assertThat(hits).hasValue(3);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
  }

  @Test
  void retriesTooManyRequests() {
    statuses.addAll(List.of(429, 200));

    assertThat(delivery().deliver(settings(Optional.empty(), 2), REQUEST))
        .isEqualTo(WebhookDelivery.Result.DELIVERED);
    assertThat(hits).hasValue(2);
  }

  @Test
  void dropsAfterTheLastAttemptWithoutSleepingAfterIt() {
    statuses.add(502);

    var result = delivery().deliver(settings(Optional.empty(), 3), REQUEST);

    assertThat(result).isEqualTo(WebhookDelivery.Result.DROPPED);
    assertThat(hits).hasValue(3);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
  }

  @Test
  void clientErrorsAreNotRetried() {
    statuses.add(404);

    var result = delivery().deliver(settings(Optional.empty(), 3), REQUEST);

    assertThat(result).isEqualTo(WebhookDelivery.Result.REJECTED);
    assertThat(hits).hasValue(1);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void redirectsAreNotFollowed() {
    statuses.add(302);

    var result = delivery().deliver(settings(Optional.of("k"), 3), REQUEST);

    assertThat(result).isEqualTo(WebhookDelivery.Result.REJECTED);
    assertThat(hits).hasValue(1);
    assertThat(redirectTargetHits).hasValue(0);
  }

  @Test
  @SuppressWarnings("unchecked") // Mockito cannot type the BodyHandler generic
  void timeoutsAreRetried() throws Exception {
    var client = mock(HttpClient.class);
    var ok = mock(HttpResponse.class);
    when(ok.statusCode()).thenReturn(200);
    when(client.send(any(), any()))
        .thenThrow(new HttpTimeoutException("request timed out"))
        .thenReturn(ok);

    var result =
        new WebhookDelivery(client, sleeps::add).deliver(settings(Optional.empty(), 2), REQUEST);

    assertThat(result).isEqualTo(WebhookDelivery.Result.DELIVERED);
    verify(client, times(2)).send(any(), any());
    assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
  }

  @Test
  void connectionFailuresEndInADrop() throws Exception {
    var client = mock(HttpClient.class);
    when(client.send(any(), any())).thenThrow(new java.net.ConnectException());

    var result =
        new WebhookDelivery(client, sleeps::add).deliver(settings(Optional.empty(), 2), REQUEST);

    assertThat(result).isEqualTo(WebhookDelivery.Result.DROPPED);
  }

  @Test
  void interruptionDuringSendStopsAndKeepsTheFlag() throws Exception {
    var client = mock(HttpClient.class);
    when(client.send(any(), any())).thenThrow(new InterruptedException());

    var result =
        new WebhookDelivery(client, sleeps::add).deliver(settings(Optional.empty(), 3), REQUEST);
    // Reads and clears the flag, so the interrupt never leaks into the next test.
    var flagged = Thread.interrupted();

    assertThat(result).isEqualTo(WebhookDelivery.Result.INTERRUPTED);
    assertThat(flagged).isTrue();
    verify(client, times(1)).send(any(), any());
  }

  @Test
  void interruptionDuringBackoffStopsAndKeepsTheFlag() {
    statuses.add(500);
    WebhookDelivery.Sleeper interrupted =
        delay -> {
          throw new InterruptedException();
        };

    var result =
        new WebhookDelivery(HttpClient.newHttpClient(), interrupted)
            .deliver(settings(Optional.empty(), 3), REQUEST);
    var flagged = Thread.interrupted();

    assertThat(result).isEqualTo(WebhookDelivery.Result.INTERRUPTED);
    assertThat(flagged).isTrue();
    assertThat(hits).hasValue(1);
  }

  @Test
  void backoffDoublesAndStopsGrowingAtEightSeconds() {
    assertThat(WebhookDelivery.backoff(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(WebhookDelivery.backoff(2)).isEqualTo(Duration.ofSeconds(2));
    assertThat(WebhookDelivery.backoff(3)).isEqualTo(Duration.ofSeconds(4));
    assertThat(WebhookDelivery.backoff(4)).isEqualTo(Duration.ofSeconds(8));
    assertThat(WebhookDelivery.backoff(9)).isEqualTo(Duration.ofSeconds(8));
  }

  @Test
  void onlyThrottlingAndServerErrorsAreRetryable() {
    assertThat(WebhookDelivery.retryable(429)).isTrue();
    assertThat(WebhookDelivery.retryable(500)).isTrue();
    assertThat(WebhookDelivery.retryable(503)).isTrue();
    assertThat(WebhookDelivery.retryable(400)).isFalse();
    assertThat(WebhookDelivery.retryable(401)).isFalse();
    assertThat(WebhookDelivery.retryable(301)).isFalse();
  }

  @Test
  void signatureMatchesAKnownVector() {
    // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?".
    assertThat(
            WebhookDelivery.signature(
                "Jefe", "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
  }

  @Test
  void anUnavailableMacAlgorithmFailsLoudly() {
    assertThatThrownBy(() -> WebhookDelivery.hmac("HmacNope", "k", BODY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HmacNope");
  }
}
