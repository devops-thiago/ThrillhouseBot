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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
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
    private int maxInputTokens = 48000;
    private int outputBufferTokens = 8192;
    private int maxAiCalls = 6;
    private double tokenSafetyMargin = 0.9;
    private boolean reasoningEnabled = false;
    private String reasoningEffort = "low";
    private String modelName = "deepseek-chat";
    private final Map<String, ThrillhouseConfig.AiPricingConfig.ModelSettings> models =
        new HashMap<>();

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

    ConfigBuilder maxInputTokens(int v) {
      this.maxInputTokens = v;
      return this;
    }

    ConfigBuilder outputBufferTokens(int v) {
      this.outputBufferTokens = v;
      return this;
    }

    ConfigBuilder maxAiCalls(int v) {
      this.maxAiCalls = v;
      return this;
    }

    ConfigBuilder tokenSafetyMargin(double v) {
      this.tokenSafetyMargin = v;
      return this;
    }

    ConfigBuilder reasoningEnabled(boolean v) {
      this.reasoningEnabled = v;
      return this;
    }

    ConfigBuilder reasoningEffort(String v) {
      this.reasoningEffort = v;
      return this;
    }

    ConfigBuilder model(String name, ThrillhouseConfig.AiPricingConfig.ModelSettings settings) {
      this.models.put(name, settings);
      return this;
    }

    StartupConfigValidator build() {
      var config = mock(ThrillhouseConfig.class);
      var github = mock(ThrillhouseConfig.GitHubConfig.class);
      var dashboard = mock(ThrillhouseConfig.DashboardConfig.class);
      var review = mock(ThrillhouseConfig.ReviewConfig.class);
      var ai = mock(ThrillhouseConfig.AiPricingConfig.class);
      var reasoning = mock(ThrillhouseConfig.AiPricingConfig.ReasoningConfig.class);
      lenient().when(config.github()).thenReturn(github);
      lenient().when(config.dashboard()).thenReturn(dashboard);
      lenient().when(config.review()).thenReturn(review);
      lenient().when(config.ai()).thenReturn(ai);
      lenient().when(ai.reasoning()).thenReturn(reasoning);
      lenient().when(reasoning.enabled()).thenReturn(reasoningEnabled);
      lenient().when(reasoning.effort()).thenReturn(reasoningEffort);
      lenient().when(github.appId()).thenReturn(appId);
      lenient().when(github.privateKey()).thenReturn(privateKey);
      lenient().when(github.webhookSecret()).thenReturn(webhookSecret);
      lenient().when(dashboard.clientId()).thenReturn(clientId);
      lenient().when(dashboard.clientSecret()).thenReturn(clientSecret);
      lenient().when(review.maxInputTokens()).thenReturn(maxInputTokens);
      lenient().when(review.outputBufferTokens()).thenReturn(outputBufferTokens);
      lenient().when(review.maxAiCalls()).thenReturn(maxAiCalls);
      lenient().when(review.tokenSafetyMargin()).thenReturn(tokenSafetyMargin);
      lenient().when(ai.models()).thenReturn(models);
      return new StartupConfigValidator(
          config, aiApiKey, new ActiveModelSettings(config, modelName));
    }
  }

  /** A per-model settings entry mock; every value defaults to absent unless stubbed by the test. */
  private static ThrillhouseConfig.AiPricingConfig.ModelSettings emptyModelSettings() {
    var settings = mock(ThrillhouseConfig.AiPricingConfig.ModelSettings.class);
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.empty());
    lenient().when(settings.outputBufferTokens()).thenReturn(Optional.empty());
    lenient().when(settings.tokenSafetyMargin()).thenReturn(Optional.empty());
    lenient().when(settings.temperature()).thenReturn(Optional.empty());
    lenient().when(settings.topP()).thenReturn(Optional.empty());
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.empty());
    lenient().when(settings.frequencyPenalty()).thenReturn(Optional.empty());
    lenient().when(settings.presencePenalty()).thenReturn(Optional.empty());
    lenient().when(settings.seed()).thenReturn(Optional.empty());
    return settings;
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
  void failsFastWhenAppIdNull() {
    var ex = assertFailsValidation(new ConfigBuilder().appId(null).build());
    assertTrue(ex.getMessage().contains("GITHUB_APP_ID is required"), ex.getMessage());
  }

  @Test
  void failsFastWhenAppIdIsNotNumeric() {
    // A non-numeric app id passes a bare presence check but yields a JWT GitHub rejects, so it must
    // be rejected at boot rather than on the first webhook.
    var ex = assertFailsValidation(new ConfigBuilder().appId("my-app").build());
    assertTrue(
        ex.getMessage().contains("GITHUB_APP_ID must be the numeric GitHub App id"),
        ex.getMessage());
  }

  @Test
  void passesWhenAppIdIsNumericWithSurroundingWhitespace() {
    new ConfigBuilder().appId("  12345  ").build().validate();
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
  void failsFastWhenMaxInputTokensNegative() {
    var ex = assertFailsValidation(new ConfigBuilder().maxInputTokens(-1).build());
    assertTrue(ex.getMessage().contains("REVIEW_MAX_INPUT_TOKENS"), ex.getMessage());
  }

  @Test
  void failsFastWhenOutputBufferNegative() {
    var ex = assertFailsValidation(new ConfigBuilder().outputBufferTokens(-1).build());
    assertTrue(ex.getMessage().contains("REVIEW_OUTPUT_BUFFER_TOKENS"), ex.getMessage());
  }

  @Test
  void failsFastWhenMaxAiCallsBelowOne() {
    var ex = assertFailsValidation(new ConfigBuilder().maxAiCalls(0).build());
    assertTrue(ex.getMessage().contains("REVIEW_MAX_AI_CALLS"), ex.getMessage());
  }

  @Test
  void failsFastWhenSafetyMarginOutOfRange() {
    assertTrue(
        assertFailsValidation(new ConfigBuilder().tokenSafetyMargin(0).build())
            .getMessage()
            .contains("REVIEW_TOKEN_SAFETY_MARGIN"));
    assertTrue(
        assertFailsValidation(new ConfigBuilder().tokenSafetyMargin(1.5).build())
            .getMessage()
            .contains("REVIEW_TOKEN_SAFETY_MARGIN"));
  }

  @Test
  void failsFastWhenOutputBufferLeavesNoDiffBudget() {
    var ex =
        assertFailsValidation(
            new ConfigBuilder().maxInputTokens(8000).outputBufferTokens(8000).build());
    assertTrue(ex.getMessage().contains("budget left for the diff"), ex.getMessage());
  }

  @Test
  void failsFastWhenMarginScaledBudgetIsNonPositive() {
    // Passes the raw buffer < max comparison but the runtime budget is
    // 48000 * 0.9 - 45000 = -1800 — the silent-disable case the validator must reject.
    var ex =
        assertFailsValidation(
            new ConfigBuilder()
                .maxInputTokens(48000)
                .outputBufferTokens(45000)
                .tokenSafetyMargin(0.9)
                .build());
    assertTrue(ex.getMessage().contains("budget left for the diff"), ex.getMessage());
  }

  @Test
  void failsFastWhenAModelSettingsEntryIsOutOfRange() {
    // Every entry is validated — not just the active model's — so a typo is caught before
    // AI_MODEL is later switched to that model.
    var settings = emptyModelSettings();
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(0));
    lenient().when(settings.temperature()).thenReturn(Optional.of(3.0));
    lenient().when(settings.topP()).thenReturn(Optional.of(1.5));
    var ex = assertFailsValidation(new ConfigBuilder().model("some-other-model", settings).build());
    var message = ex.getMessage();
    assertTrue(
        message.contains("thrillhousebot.ai.models.\"some-other-model\".max-input-tokens"),
        message);
    assertTrue(message.contains("temperature must be in [0, 2]"), message);
    assertTrue(message.contains("top-p must be in (0, 1]"), message);
  }

  @Test
  void failsFastOnEveryOutOfRangePerModelValue() {
    var settings = emptyModelSettings();
    lenient().when(settings.outputBufferTokens()).thenReturn(Optional.of(-1));
    lenient().when(settings.tokenSafetyMargin()).thenReturn(Optional.of(1.5));
    lenient().when(settings.temperature()).thenReturn(Optional.of(-0.1));
    lenient().when(settings.topP()).thenReturn(Optional.of(0.0));
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(0));
    lenient().when(settings.frequencyPenalty()).thenReturn(Optional.of(2.5));
    lenient().when(settings.presencePenalty()).thenReturn(Optional.of(-2.5));
    var zeroMargin = emptyModelSettings();
    lenient().when(zeroMargin.tokenSafetyMargin()).thenReturn(Optional.of(0.0));
    var ex =
        assertFailsValidation(
            new ConfigBuilder().model("m", settings).model("m2", zeroMargin).build());
    var message = ex.getMessage();
    assertTrue(message.contains("output-buffer-tokens must be >= 0"), message);
    assertTrue(message.contains("token-safety-margin must be in (0, 1]"), message);
    assertTrue(message.contains("\"m2\".token-safety-margin"), message);
    assertTrue(message.contains("temperature must be in [0, 2]"), message);
    assertTrue(message.contains("top-p must be in (0, 1]"), message);
    assertTrue(message.contains("max-output-tokens must be >= 1"), message);
    assertTrue(message.contains("frequency-penalty must be in [-2, 2]"), message);
    assertTrue(message.contains("presence-penalty must be in [-2, 2]"), message);
  }

  @Test
  void bootsWhenTheGlobalBudgetIsClampedByTheDefaultModelCap() {
    // A budget past the 128k default cap is not a misconfiguration — it boots with the clamped
    // budget (and a startup warning), because the operator may simply not know the model's window.
    new ConfigBuilder().maxInputTokens(500_000).build().validate();
  }

  @Test
  void bootsWhenAModelSettingsEntryIsValid() {
    var settings = emptyModelSettings();
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(64_000));
    lenient().when(settings.outputBufferTokens()).thenReturn(Optional.of(4_096));
    lenient().when(settings.tokenSafetyMargin()).thenReturn(Optional.of(0.8));
    lenient().when(settings.temperature()).thenReturn(Optional.of(0.2));
    lenient().when(settings.topP()).thenReturn(Optional.of(0.95));
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(8_192));
    lenient().when(settings.frequencyPenalty()).thenReturn(Optional.of(-2.0));
    lenient().when(settings.presencePenalty()).thenReturn(Optional.of(2.0));
    lenient().when(settings.seed()).thenReturn(Optional.of(42));
    new ConfigBuilder().model("deepseek-chat", settings).build().validate();
  }

  @Test
  void failsFastWhenAPerModelOverrideLeavesNoDiffBudget() {
    // The global combination is fine, but the active model's output-buffer override swallows the
    // whole margin-scaled budget — the effective values are what the budgeter runs with.
    var settings = emptyModelSettings();
    lenient().when(settings.outputBufferTokens()).thenReturn(Optional.of(48_000));
    var ex = assertFailsValidation(new ConfigBuilder().model("deepseek-chat", settings).build());
    assertTrue(ex.getMessage().contains("budget left for the diff"), ex.getMessage());
  }

  @Test
  void bootsWhenAPerModelOverrideRepairsABrokenGlobalCombination() {
    // Effective values decide: the global buffer would swallow the budget, but the active model's
    // override restores headroom, so the boot must succeed.
    var settings = emptyModelSettings();
    lenient().when(settings.outputBufferTokens()).thenReturn(Optional.of(1_000));
    new ConfigBuilder()
        .outputBufferTokens(45_000)
        .model("deepseek-chat", settings)
        .build()
        .validate();
  }

  @Test
  void allowsTokenBudgetingDisabledWithZeroInputTokens() {
    // 0 input tokens disables budgeting (single call); the output-buffer cross-check is skipped.
    new ConfigBuilder().maxInputTokens(0).build().validate();
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

  @Test
  void failsFastWhenReasoningEffortIsInvalid() {
    var ex = assertFailsValidation(new ConfigBuilder().reasoningEffort("max").build());
    assertTrue(
        ex.getMessage().contains("AI_REASONING_EFFORT must be one of none, low, medium, high"),
        ex.getMessage());
  }

  @Test
  void rejectsInvalidReasoningEffortEvenWhileReasoningIsDisabled() {
    // Setting AI_REASONING_EFFORT expresses clear intent, so a typo is rejected at boot instead of
    // surfacing later when the flag is flipped on.
    var ex =
        assertFailsValidation(
            new ConfigBuilder().reasoningEnabled(false).reasoningEffort("hgih").build());
    assertTrue(ex.getMessage().contains("AI_REASONING_EFFORT"), ex.getMessage());
  }

  @Test
  void acceptsEveryReasoningEffortCaseInsensitivelyWithWhitespace() {
    for (var effort : new String[] {"none", "LOW", " Medium ", "high"}) {
      new ConfigBuilder().reasoningEnabled(true).reasoningEffort(effort).build().validate();
    }
  }

  @Test
  void classifiesDashboardOauthStatusForEveryCombination() {
    assertEquals(
        StartupConfigValidator.DashboardOauthStatus.ENABLED,
        StartupConfigValidator.dashboardOauthStatus(true, true));
    assertEquals(
        StartupConfigValidator.DashboardOauthStatus.DISABLED,
        StartupConfigValidator.dashboardOauthStatus(false, false));
    assertEquals(
        StartupConfigValidator.DashboardOauthStatus.PARTIAL,
        StartupConfigValidator.dashboardOauthStatus(true, false));
    assertEquals(
        StartupConfigValidator.DashboardOauthStatus.PARTIAL,
        StartupConfigValidator.dashboardOauthStatus(false, true));
  }
}
