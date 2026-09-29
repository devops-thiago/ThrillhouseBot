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

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends one notification body to the configured receiver, with a bounded retry (#73).
 *
 * <p>A timeout, a connection failure, a 429 or a 5xx is repeated after an exponential backoff
 * ({@link #BASE_BACKOFF}, doubling, capped at 8 seconds) until {@link
 * NotificationSettings#maxAttempts()} is spent; then the notification is dropped with a WARN. Any
 * other status is final: a 4xx will not change on a repeat, and a 3xx is refused rather than
 * followed — the shared {@link HttpClient} never follows redirects, so a receiver cannot bounce the
 * payload and its signature to a host the operator did not name.
 *
 * <p>Log lines name the receiver by {@link NotificationSettings#redactedUrl()} only and a failure
 * by its exception type only: the URL path of a Slack or Discord webhook is its credential, and an
 * I/O exception's message can quote the URL.
 */
final class WebhookDelivery {

  private static final Logger log = LoggerFactory.getLogger(WebhookDelivery.class);

  /** HMAC-SHA256 of the body, as {@code sha256=<hex>}; present only when a secret is configured. */
  static final String SIGNATURE_HEADER = "X-Thrillhousebot-Signature-256";

  /** The event name, e.g. {@code review.completed}. */
  static final String EVENT_HEADER = "X-Thrillhousebot-Event";

  /** A per-notification id, the same on every attempt, so a receiver can drop a repeat. */
  static final String DELIVERY_HEADER = "X-Thrillhousebot-Delivery";

  static final Duration BASE_BACKOFF = Duration.ofSeconds(1);

  /** Doublings of {@link #BASE_BACKOFF} before the wait stops growing: at most 8 seconds. */
  private static final int MAX_BACKOFF_DOUBLINGS = 3;

  private static final String HMAC_SHA256 = "HmacSHA256";

  /** Waits between attempts; a seam so tests never sleep. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration delay) throws InterruptedException;
  }

  /** How one delivery ended. */
  enum Result {
    /** The receiver answered 2xx. */
    DELIVERED,
    /** The receiver answered a status a repeat would not change; not retried. */
    REJECTED,
    /** Every attempt failed with a retryable error. */
    DROPPED,
    /** The thread was interrupted (shutdown) before the delivery finished. */
    INTERRUPTED
  }

  /** What to send: the rendered body and the headers that identify it. */
  record Request(String event, String deliveryId, byte[] body, String userAgent) {}

  private final HttpClient client;
  private final Sleeper sleeper;

  WebhookDelivery(HttpClient client, Sleeper sleeper) {
    this.client = client;
    this.sleeper = sleeper;
  }

  Result deliver(NotificationSettings settings, Request request) {
    var httpRequest = buildRequest(settings, request);
    String lastFailure = "none";
    for (var attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
      try {
        var status = client.send(httpRequest, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status / 100 == 2) {
          log.debug(
              "Delivered {} notification {} to {} on attempt {}",
              request.event(),
              request.deliveryId(),
              settings.redactedUrl(),
              attempt);
          return Result.DELIVERED;
        }
        if (!retryable(status)) {
          log.warn(
              "Notification receiver {} refused {} notification {} with HTTP {}{} — not retried",
              settings.redactedUrl(),
              request.event(),
              request.deliveryId(),
              status,
              status / 100 == 3 ? " (redirects are not followed)" : "");
          return Result.REJECTED;
        }
        lastFailure = "HTTP " + status;
      } catch (IOException e) {
        lastFailure = e.getClass().getSimpleName();
      } catch (InterruptedException _) {
        return interrupted(settings, request);
      }
      if (attempt < settings.maxAttempts()) {
        try {
          sleeper.sleep(backoff(attempt));
        } catch (InterruptedException _) {
          return interrupted(settings, request);
        }
      }
    }
    log.warn(
        "Dropped {} notification {} to {} after {} attempt(s); last failure: {}",
        request.event(),
        request.deliveryId(),
        settings.redactedUrl(),
        settings.maxAttempts(),
        lastFailure);
    return Result.DROPPED;
  }

  private static Result interrupted(NotificationSettings settings, Request request) {
    Thread.currentThread().interrupt();
    log.warn(
        "Interrupted while delivering {} notification {} to {} — dropped",
        request.event(),
        request.deliveryId(),
        settings.redactedUrl());
    return Result.INTERRUPTED;
  }

  private static HttpRequest buildRequest(NotificationSettings settings, Request request) {
    var builder =
        HttpRequest.newBuilder(settings.url())
            .timeout(settings.timeout())
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", request.userAgent())
            .header(EVENT_HEADER, request.event())
            .header(DELIVERY_HEADER, request.deliveryId())
            .POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
    settings
        .secret()
        .ifPresent(secret -> builder.header(SIGNATURE_HEADER, signature(secret, request.body())));
    return builder.build();
  }

  /** {@code sha256=<hex HMAC-SHA256 of body keyed by secret>}. Visible for tests. */
  static String signature(String secret, byte[] body) {
    return "sha256=" + HexFormat.of().formatHex(hmac(HMAC_SHA256, secret, body));
  }

  /** The MAC of {@code body}; the algorithm is a parameter only so its failure path is testable. */
  static byte[] hmac(String algorithm, String secret, byte[] body) {
    try {
      var mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
      return mac.doFinal(body);
    } catch (GeneralSecurityException e) {
      // HmacSHA256 is a mandatory JCA algorithm and a non-empty key is always valid for it.
      throw new IllegalStateException(algorithm + " is unavailable", e);
    }
  }

  /** A 429 or a 5xx may clear on its own; nothing else will. Visible for tests. */
  static boolean retryable(int status) {
    return status == 429 || status >= 500;
  }

  /** The wait after attempt {@code attempt} (1-based): 1s, 2s, 4s, then 8s. Visible for tests. */
  static Duration backoff(int attempt) {
    return BASE_BACKOFF.multipliedBy(1L << Math.min(attempt - 1, MAX_BACKOFF_DOUBLINGS));
  }
}
