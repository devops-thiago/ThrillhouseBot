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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class StartupConfigValidatorTest {

  // Throwaway 2048-bit PKCS#1 RSA key generated for tests only.
  private static final String VALID_PRIVATE_KEY =
      "-----BEGIN RSA PRIVATE KEY-----\\n"
          + "MIIEowIBAAKCAQEAqEcn0Wmzxjw+MTxPpYpetynJEC+u5dV87wWx2m6Xs8TJB3dn\\n"
          + "GRemwG/x1/DZMLk5WzMOIxYBBYUQ3HQlB+tobP3EyD9TwQDsR9/CMhvMbZ4IQlBD\\n"
          + "rbV4cyvcQkEPGP4ojqr4TU7+GQh8ZlcA6QxGgQrgyAEdGaiOPkBtrbw+oOUdvAMF\\n"
          + "kU0+lvd0BuhJ0vEkyLz66reMwfhRMoNZcGaLogVwE8aEAnabQhYw5Xgt5A9o/SOt\\n"
          + "1i+PqvjzUDP/XcJzuKS2Wak0LAjp/HuU2JnpPIjwE2qwZ9WcJC2YYbJWDBYP6rbh\\n"
          + "uFghflQxXOjmRQL8RAclma5AyjQvUk0VLOfMnwIDAQABAoIBAA3i0UZWkp8pGXi2\\n"
          + "oVvnrykuKYlMZgJRO539uk9sENikxHH5SGipqvj2Q96t4T5ECpeb/u6mQi9Sa7HF\\n"
          + "Y8jjhBp6VmKv1xl2GglUTZIU+SmSgNv4A52x+96FIAfXlodZcb9UHGlEu3MVYC6R\\n"
          + "v2F7wdWkMChQ7zXO0u4QIvzTK9fa7eVffnK99PgMAtPBHMwMhuPgRc+Ur0OWE+Xd\\n"
          + "7CppB+szpJAGmvuWyuoulfjp7ynPJGLCOds2r+FWvPwXFi80RCvoppaQ9oKUWxiB\\n"
          + "dQAtAwr0myjvsQJBsWVlav75tlayHDo1PdnPbTlp7n7hBlZBL5TYziOqK6V+TYsE\\n"
          + "/9uruAECgYEA3Yhqe0PcrGttn7OIWPqlZpjqocE0H4taZnd90mA/m9sM9Ans1pJ9\\n"
          + "3eAxzluqDZCCYFrVhAR82UeB8FMOt1w8wMvLGMOOa586FYNI16UzuJp5t7xWasoc\\n"
          + "zpnohKlfmDxYYGMzXjcU3KWhTeIkoJO/sIwVZ67s++XQwlqbHFzlOL8CgYEAwnWc\\n"
          + "51s+N8F03Wzwk9HLmpHxs0ydO5dPjQFfj8Hk7tHIx5q94921x/CXH4hLeMO5zjl4\\n"
          + "vOAQGr4KBqRWZ4TQhXkea6Kfd1dGm9E4KKx8wm7zqhDikgVbeGBKXDjblCKnlTHb\\n"
          + "v2JWoNSStBqZnHLM3ZxH8ZZKY/YYTLcyNuMnhCECgYBRCsGhfG7zKI2++aesnWz6\\n"
          + "voA/UnWmAI2+pIID/y/l7VmswSDCUm73RzgRPNlWAwKfCzvHAvlFZ3Jyn3/ntjeH\\n"
          + "dEZFNe0ZE/PkwNVaBlaIdwKGI8Edafjl38n/FhMhlxnhkQjOs6nPGkyLOGqbz9E1\\n"
          + "XdnKx2RstmMLZqgN1TIJ8wKBgC8dnhm0UtvhhLZNufCm6WUXLW/bBVG19LFefs/v\\n"
          + "E9AFhldOl+nJA01hbsxWEqs9CRz9cdKZm21PVFCNqt3EIV3lnchIi8i3ncUNKUU5\\n"
          + "nbTieylek/b7U1FUS1AS+qjmyKHuhabWZdTsDGuU8lkku5yKTCgt2PJlYzfbP1Br\\n"
          + "M1zhAoGBAMHT2Y3JiLkfUifKEytraOpIwjcKxX5x6qkVjZ0W8MqGUn0+B9IY9jpo\\n"
          + "yQIy96VtO8p+PIXh6FAVrGzDGU/FUd+vlKqo7u73bF6hdWps/t6CuZKG9vIQNjPs\\n"
          + "qK75ejcwlPmFYeQJscpQ2c8KNqcewsA53bJWYmroAc/na7JCfpf7\\n"
          + "-----END RSA PRIVATE KEY-----";

  /** Builder for the config mock so each test tweaks only the value it cares about. */
  private static final class ConfigBuilder {
    private String appId = "12345";
    private String privateKey = VALID_PRIVATE_KEY;
    private String webhookSecret = "webhook-secret";
    private String aiApiKey = "ai-key";
    private Optional<String> clientId = Optional.of("client-id");
    private Optional<String> clientSecret = Optional.of("client-secret");

    ConfigBuilder appId(String v) {
      this.appId = v;
      return this;
    }

    ConfigBuilder privateKey(String v) {
      this.privateKey = v;
      return this;
    }

    ConfigBuilder webhookSecret(String v) {
      this.webhookSecret = v;
      return this;
    }

    ConfigBuilder aiApiKey(String v) {
      this.aiApiKey = v;
      return this;
    }

    ConfigBuilder clientId(Optional<String> v) {
      this.clientId = v;
      return this;
    }

    ConfigBuilder clientSecret(Optional<String> v) {
      this.clientSecret = v;
      return this;
    }

    StartupConfigValidator build() {
      var config = mock(ThrillhouseConfig.class);
      var github = mock(ThrillhouseConfig.GitHubConfig.class);
      var dashboard = mock(ThrillhouseConfig.DashboardConfig.class);
      lenient().when(config.github()).thenReturn(github);
      lenient().when(config.dashboard()).thenReturn(dashboard);
      lenient().when(github.appId()).thenReturn(appId);
      lenient().when(github.privateKey()).thenReturn(privateKey);
      lenient().when(github.webhookSecret()).thenReturn(webhookSecret);
      lenient().when(dashboard.clientId()).thenReturn(clientId);
      lenient().when(dashboard.clientSecret()).thenReturn(clientSecret);
      return new StartupConfigValidator(config, aiApiKey);
    }
  }

  private static ConfigValidationException assertFailsValidation(StartupConfigValidator validator) {
    return assertThrows(ConfigValidationException.class, validator::validate);
  }

  @Test
  void passesWhenAllRequiredConfigIsPresent() {
    // No exception means the application would boot.
    new ConfigBuilder().build().validate();
  }

  @Test
  void failsFastWhenAppIdMissing() {
    var ex = assertFailsValidation(new ConfigBuilder().appId("").build());
    assertTrue(ex.getMessage().contains("GITHUB_APP_ID"), ex.getMessage());
  }

  @Test
  void failsFastWhenPrivateKeyMissing() {
    var ex = assertFailsValidation(new ConfigBuilder().privateKey("  ").build());
    assertTrue(ex.getMessage().contains("GITHUB_PRIVATE_KEY is required"), ex.getMessage());
  }

  @Test
  void failsFastWhenPrivateKeyMalformed() {
    var ex = assertFailsValidation(new ConfigBuilder().privateKey("not-a-valid-key").build());
    assertTrue(
        ex.getMessage()
            .contains("GITHUB_PRIVATE_KEY is set but is not a valid PEM RSA private key"),
        ex.getMessage());
  }

  @Test
  void failsFastWhenWebhookSecretMissing() {
    var ex = assertFailsValidation(new ConfigBuilder().webhookSecret(null).build());
    assertTrue(ex.getMessage().contains("GITHUB_WEBHOOK_SECRET"), ex.getMessage());
  }

  @Test
  void failsFastWhenAiApiKeyMissing() {
    var ex = assertFailsValidation(new ConfigBuilder().aiApiKey("").build());
    assertTrue(ex.getMessage().contains("AI_API_KEY"), ex.getMessage());
  }

  @Test
  void listsEveryProblemAtOnce() {
    var ex =
        assertFailsValidation(
            new ConfigBuilder().appId("").privateKey("").webhookSecret("").aiApiKey("").build());
    var message = ex.getMessage();
    assertTrue(message.contains("GITHUB_APP_ID"), message);
    assertTrue(message.contains("GITHUB_PRIVATE_KEY"), message);
    assertTrue(message.contains("GITHUB_WEBHOOK_SECRET"), message);
    assertTrue(message.contains("AI_API_KEY"), message);
  }

  @Test
  void bootsWhenDashboardOauthIsFullyDisabled() {
    // OAuth vars absent is a supported "dashboard login disabled" state, not a startup failure.
    new ConfigBuilder()
        .clientId(Optional.empty())
        .clientSecret(Optional.empty())
        .build()
        .validate();
  }

  @Test
  void failsFastWhenPrivateKeyNull() {
    var ex = assertFailsValidation(new ConfigBuilder().privateKey(null).build());
    assertTrue(ex.getMessage().contains("GITHUB_PRIVATE_KEY is required"), ex.getMessage());
  }

  @Test
  void bootsWhenDashboardOauthHasOnlyClientSecret() {
    // Mirror of the partial case (secret set, id absent): dashboard stays disabled, app still
    // boots.
    new ConfigBuilder().clientId(Optional.empty()).build().validate();
  }

  @Test
  void treatsBlankDashboardOauthValuesAsDisabled() {
    // Present-but-blank OAuth values are filtered out, so the dashboard is treated as disabled.
    new ConfigBuilder()
        .clientId(Optional.of("   "))
        .clientSecret(Optional.of("   "))
        .build()
        .validate();
  }
}
