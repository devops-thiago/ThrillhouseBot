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
package dev.thiagogonzaga.thrillhousebot.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "thrillhousebot")
public interface ThrillhouseConfig {
  /** TCP connect timeout for the shared outbound {@link java.net.http.HttpClient}. */
  @WithDefault("10s")
  @WithName("http-connect-timeout")
  Duration httpConnectTimeout();

  /** Per-request timeout for outbound HTTP calls made with the shared client. */
  @WithDefault("10s")
  @WithName("http-request-timeout")
  Duration httpRequestTimeout();

  /**
   * Interval between dashboard WebSocket keepalive frames so reverse proxies do not idle-close
   * connections.
   */
  @WithDefault("25000")
  @WithName("websocket-keepalive-ms")
  long websocketKeepAliveMs();

  GitHubConfig github();

  WebhookConfig webhook();

  ReviewConfig review();

  DashboardConfig dashboard();

  AiPricingConfig ai();

  interface GitHubConfig {
    @WithName("app-id")
    String appId();

    @WithName("private-key")
    String privateKey();

    @WithName("webhook-secret")
    String webhookSecret();
  }

  interface WebhookConfig {
    /**
     * How long a processed {@code X-GitHub-Delivery} id is remembered so GitHub redeliveries
     * (manual redelivery or automatic retries) within this window are dropped instead of triggering
     * a duplicate review. State is in-memory per replica.
     */
    @WithDefault("24h")
    @WithName("dedup-ttl")
    Duration dedupTtl();
  }

  interface ReviewConfig {
    @WithDefault("50")
    @WithName("max-review-comments")
    int maxReviewComments();

    @WithDefault("5")
    @WithName("max-ai-retries")
    int maxAiRetries();

    @WithDefault("2000")
    @WithName("ai-retry-base-delay-ms")
    long aiRetryBaseDelayMs();

    @WithDefault("300")
    @WithName("ai-timeout-seconds")
    int aiTimeoutSeconds();

    @WithDefault("5000")
    @WithName("max-diff-lines")
    int maxDiffLines();

    @WithDefault(".github/thrillhousebot.md")
    @WithName("instructions-file")
    String instructionsFile();

    /** Second-pass AI audit of findings before posting; trades one extra call for fewer FPs. */
    @WithDefault("true")
    @WithName("verifier-enabled")
    boolean verifierEnabled();

    @WithDefault("**/pom.xml,**/package-lock.json,**/*.lock,**/*.generated.*,**/target/**")
    @WithName("ignored-files")
    List<String> ignoredFiles();
  }

  interface DashboardConfig {
    /** Public base URL of the dashboard, used for session deep-links posted to GitHub. */
    @WithName("url")
    @WithDefault("http://localhost:8080")
    String url();

    @WithName("github.client-id")
    Optional<String> clientId();

    @WithName("github.client-secret")
    Optional<String> clientSecret();

    @WithName("github.redirect-uri")
    @WithDefault("http://localhost:8080/api/auth/callback")
    String redirectUri();

    @WithName("github.oauth-url")
    @WithDefault("https://github.com/login/oauth")
    String oauthUrl();

    /** Optional override; when unset the owner is resolved from the GitHub App via GET /app. */
    @WithName("github.account-owner")
    Optional<String> accountOwner();
  }

  interface AiPricingConfig {
    Map<String, ModelPricing> pricing();

    interface ModelPricing {
      @WithName("input-per-1k")
      double inputPer1k();

      @WithName("output-per-1k")
      double outputPer1k();

      /** Cost for the given token counts using per-1K input/output rates. */
      static double cost(
          double inputPer1k, double outputPer1k, long inputTokens, long outputTokens) {
        return (inputTokens * inputPer1k + outputTokens * outputPer1k) / 1000.0;
      }
    }
  }
}
