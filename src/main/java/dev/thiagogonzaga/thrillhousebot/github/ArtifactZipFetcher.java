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

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches the bytes behind the pre-signed URL GitHub redirects an artifact download to.
 *
 * <p>Separate from {@link GitHubActionsClient} on purpose. That redirect points at blob storage on
 * a different host, and the installation token must not travel there: an {@code Authorization}
 * header carried across the redirect would both leak a repository-scoped credential to a third
 * party and be rejected by the storage backend, which authenticates from the URL's own signature.
 * So this issues a plain, unauthenticated GET.
 *
 * <p>The response is attacker-influenced in size (any repository can upload a large artifact), so
 * the body is read under a hard {@link #MAX_BYTES} bound. Every failure yields an empty array — a
 * coverage report the bot could not fetch must degrade to no extra review context, never to a
 * failed review.
 */
@ApplicationScoped
public class ArtifactZipFetcher {

  /** Ceiling on a downloaded artifact; a coverage report zip is orders of magnitude smaller. */
  static final int MAX_BYTES = 16 * 1024 * 1024;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /** Every failure path: no bytes, which every caller reads as "no coverage report". */
  private static final byte[] NOTHING = new byte[0];

  /**
   * The artifact zip's bytes, or an empty array when {@code location} is unusable or the download
   * failed. Only {@code https} is followed: the redirect target is not something this bot chose,
   * and a downgrade to plaintext is never worth a review-context nicety.
   */
  public byte[] fetch(URI location) {
    if (location == null || !"https".equalsIgnoreCase(location.getScheme())) {
      Log.debugf("Refusing to download coverage artifact from %s", location);
      return NOTHING;
    }
    return transfer(location);
  }

  /**
   * The transfer itself, with {@link #fetch}'s scheme policy already applied — separated so the
   * bytes-on-the-wire behaviour (status handling, the size bound, redirect following, failure
   * degradation) can be exercised against a loopback server without a TLS endpoint. Production
   * reaches it only through {@link #fetch}, so only {@code https} is ever transferred.
   */
  byte[] transfer(URI location) {
    // Redirects are safe to follow here precisely because no credential is attached.
    var builder =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL);
    // Deployments behind an egress proxy configure it through the JVM's system properties, which
    // is what the default selector reads; it is absent only in exotic setups.
    var proxySelector = ProxySelector.getDefault();
    if (proxySelector != null) {
      builder.proxy(proxySelector);
    }
    try (var client = builder.build()) {
      var request = HttpRequest.newBuilder(location).timeout(REQUEST_TIMEOUT).GET().build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() / 100 != 2) {
        Log.debugf("Coverage artifact download returned HTTP %d", response.statusCode());
        return NOTHING;
      }
      try (var body = response.body()) {
        return readBounded(body);
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      Log.debug("Interrupted while downloading coverage artifact");
      return NOTHING;
    } catch (IOException | RuntimeException e) {
      Log.debugf(e, "Could not download coverage artifact from %s", location.getHost());
      return NOTHING;
    }
  }

  /**
   * At most {@link #MAX_BYTES} of {@code in}, or nothing when the stream is longer than that. An
   * over-long body is rejected rather than truncated: half a zip is not a coverage report, and
   * silently parsing a prefix would be worse than contributing nothing.
   */
  static byte[] readBounded(InputStream in) throws IOException {
    // One byte past the cap distinguishes "exactly at the cap" from "longer than the cap".
    var bytes = in.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) {
      Log.debugf("Coverage artifact exceeds %d bytes; ignoring it", MAX_BYTES);
      return NOTHING;
    }
    return bytes;
  }
}
