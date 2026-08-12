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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.WithName;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
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
    private long maxTokensPerReview = 0;
    private String ciGating = "strict";
    private boolean reasoningEnabled = false;
    private String reasoningEffort = "low";
    private Optional<String> conciseReasoningEffort = Optional.empty();
    private String blockingStrictness = "balanced";
    private String modelName = "deepseek-chat";
    private Optional<Integer> conciseMaxOutputTokens = Optional.of(8192);
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

    ConfigBuilder maxTokensPerReview(long v) {
      this.maxTokensPerReview = v;
      return this;
    }

    ConfigBuilder ciGating(String v) {
      this.ciGating = v;
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

    ConfigBuilder conciseReasoningEffort(Optional<String> v) {
      this.conciseReasoningEffort = v;
      return this;
    }

    ConfigBuilder blockingStrictness(String v) {
      this.blockingStrictness = v;
      return this;
    }

    ConfigBuilder conciseMaxOutputTokens(Optional<Integer> v) {
      this.conciseMaxOutputTokens = v;
      return this;
    }

    ConfigBuilder modelName(String v) {
      this.modelName = v;
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
      lenient().when(reasoning.conciseEffort()).thenReturn(conciseReasoningEffort);
      lenient().when(github.appId()).thenReturn(appId);
      lenient().when(github.privateKey()).thenReturn(privateKey);
      lenient().when(github.webhookSecret()).thenReturn(webhookSecret);
      lenient().when(dashboard.clientId()).thenReturn(clientId);
      lenient().when(dashboard.clientSecret()).thenReturn(clientSecret);
      lenient().when(review.maxInputTokens()).thenReturn(maxInputTokens);
      lenient().when(review.outputBufferTokens()).thenReturn(outputBufferTokens);
      lenient().when(review.maxAiCalls()).thenReturn(maxAiCalls);
      lenient().when(review.tokenSafetyMargin()).thenReturn(tokenSafetyMargin);
      lenient().when(review.maxTokensPerReview()).thenReturn(maxTokensPerReview);
      lenient().when(review.ciGating()).thenReturn(ciGating);
      lenient().when(review.blockingStrictness()).thenReturn(blockingStrictness);
      lenient().when(ai.models()).thenReturn(models);
      return new StartupConfigValidator(
          config, aiApiKey, new ActiveModelSettings(config, modelName), conciseMaxOutputTokens);
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
    lenient().when(settings.separateOutputBudget()).thenReturn(Optional.empty());
    lenient().when(settings.contextTokens()).thenReturn(Optional.empty());
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
  void missingPrivateKeyNamesHowToObtainOne() {
    // The refusal is where the reader actually is, so it has to state the remedy — which key
    // format is accepted and the command that produces a throwaway one — not only the property.
    var message = assertFailsValidation(new ConfigBuilder().privateKey("  ").build()).getMessage();
    assertTrue(message.contains("openssl genrsa -traditional 2048"), message);
    assertTrue(message.contains("no surrounding quotes"), message);
  }

  @Test
  void malformedPrivateKeyNamesHowToObtainOne() {
    // Placeholder text is the fresh-clone case (#593): the parser error alone does not tell the
    // reader what a valid value looks like.
    var message =
        assertFailsValidation(new ConfigBuilder().privateKey("not-a-valid-key").build())
            .getMessage();
    assertTrue(message.contains("openssl genrsa -traditional 2048"), message);
    assertTrue(message.contains("no surrounding quotes"), message);
  }

  @Test
  void refusalNamesCopyingEnvExampleAsTheFirstStep() {
    // A fresh clone has no .env at all, so the closing line has to name the copy step rather than
    // merely point at the example file.
    var message = assertFailsValidation(new ConfigBuilder().aiApiKey("").build()).getMessage();
    assertTrue(message.contains("Copy .env.example to .env"), message);
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
  void failsFastWhenMaxTokensPerReviewNegative() {
    // #499: same fail-fast contract as the other budget keys — a negative spend ceiling is a
    // misconfiguration, rejected at boot naming the env var; 0 stays valid and means off.
    var ex = assertFailsValidation(new ConfigBuilder().maxTokensPerReview(-1).build());
    assertTrue(ex.getMessage().contains("REVIEW_MAX_TOKENS_PER_REVIEW"), ex.getMessage());
    assertTrue(
        ex.getMessage().contains("thrillhousebot.review.max-tokens-per-review"), ex.getMessage());
  }

  @Test
  void bootsWhenMaxTokensPerReviewIsPositive() {
    new ConfigBuilder().maxTokensPerReview(250_000).build().validate();
  }

  @Test
  void failsFastWhenConciseResponseCapBelowOne() {
    // Same fail-fast contract as the other budget keys: a degenerate cap on the concise model
    // (summary/verifier/replies) must be rejected at boot, naming the env var.
    for (var cap : new int[] {0, -1}) {
      var ex =
          assertFailsValidation(
              new ConfigBuilder().conciseMaxOutputTokens(Optional.of(cap)).build());
      assertTrue(
          ex.getMessage().contains("REVIEW_CONCISE_MAX_OUTPUT_TOKENS must be >= 1"),
          ex.getMessage());
    }
  }

  @Test
  void bootsWhenTheConciseResponseCapIsUnset() {
    // An operator may clear REVIEW_CONCISE_MAX_OUTPUT_TOKENS (empty value) so the concise calls
    // fall back to the provider default; absence is not a misconfiguration.
    new ConfigBuilder().conciseMaxOutputTokens(Optional.empty()).build().validate();
  }

  @Test
  void failsFastWhenTheConciseCapExceedsTheBufferOnASharedWindow() {
    // #517: post-#507 the summary/verifier/reply calls send REVIEW_CONCISE_MAX_OUTPUT_TOKENS as
    // max_tokens against the same shared window the budgeter packed to budget - buffer, so the
    // concise cap is held to the same reservation rule as the active model's own response cap:
    // 384000 on the active cap refuses boot, and the identical value on the concise cap must too.
    var ex =
        assertFailsValidation(
            new ConfigBuilder().conciseMaxOutputTokens(Optional.of(384_000)).build());
    assertTrue(
        ex.getMessage()
            .contains(
                "effective output buffer (8192) must be >= REVIEW_CONCISE_MAX_OUTPUT_TOKENS"
                    + " (384000"),
        ex.getMessage());
    // The message has to hand the operator every way out, in the active-model rule's style.
    assertTrue(ex.getMessage().contains("REVIEW_OUTPUT_BUFFER_TOKENS"), ex.getMessage());
    assertTrue(
        ex.getMessage()
            .contains("thrillhousebot.ai.models.\"deepseek-chat\".separate-output-budget=true"),
        "the failure must point at the escape hatch: " + ex.getMessage());
  }

  @Test
  void allowsAConciseCapAboveTheBufferWhenTheOutputBudgetIsSeparate() {
    // On a separate-output-budget model nothing is reserved out of the window for responses, so
    // the shared-window rule does not apply to the concise cap either — the deepseek-v4-flash
    // shape (384000 out against an 8192 buffer) must keep booting.
    var settings = emptyModelSettings();
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(1_000_000));
    lenient().when(settings.separateOutputBudget()).thenReturn(Optional.of(true));

    new ConfigBuilder()
        .model("deepseek-chat", settings)
        .conciseMaxOutputTokens(Optional.of(384_000))
        .build()
        .validate();
  }

  @Test
  void allowsAConciseCapAboveTheBufferWhenTokenBudgetingIsDisabled() {
    // With budgeting off there is no packed prompt to overrun, so — exactly like the active-model
    // rule — the concise reservation check does not apply.
    new ConfigBuilder()
        .maxInputTokens(0)
        .conciseMaxOutputTokens(Optional.of(384_000))
        .build()
        .validate();
  }

  @Test
  void holdsTheConciseCapToTheActiveModelsEffectiveBuffer() {
    // The reservation the rule compares against is the active model's resolved buffer — a
    // per-model output-buffer override that covers the concise cap must boot.
    var settings = emptyModelSettings();
    lenient().when(settings.outputBufferTokens()).thenReturn(Optional.of(16_384));

    new ConfigBuilder()
        .model("deepseek-chat", settings)
        .conciseMaxOutputTokens(Optional.of(16_384))
        .build()
        .validate();
  }

  @Test
  void namesThePerModelBufferKnobWhenAnOverrideSourcesTheEffectiveBuffer() {
    // #528: when a per-model output-buffer-tokens override is present, the global env var is never
    // read (ActiveModelSettings resolves the override first), so advising "raise
    // REVIEW_OUTPUT_BUFFER_TOKENS" sends the operator to a knob that changes nothing — following
    // it re-boots into the identical refusal. The remedy must name the override property instead.
    var settings = emptyModelSettings();
    lenient().when(settings.outputBufferTokens()).thenReturn(Optional.of(4_096));

    var ex =
        assertFailsValidation(
            new ConfigBuilder()
                .model("deepseek-chat", settings)
                .conciseMaxOutputTokens(Optional.of(8_192))
                .build());
    assertTrue(
        ex.getMessage()
            .contains(
                "effective output buffer (4096) must be >= REVIEW_CONCISE_MAX_OUTPUT_TOKENS"
                    + " (8192"),
        ex.getMessage());
    assertTrue(
        ex.getMessage()
            .contains(
                "raise thrillhousebot.ai.models.\"deepseek-chat\".output-buffer-tokens to cover"
                    + " it"),
        "the remedy must name the override that sources the effective buffer: " + ex.getMessage());
    assertFalse(
        ex.getMessage().contains("REVIEW_OUTPUT_BUFFER_TOKENS"),
        "the global env var is inert under the override and must not be advised: "
            + ex.getMessage());
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
  void failsFastWhenGlobalSafetyMarginIsNonFinite() {
    for (var margin :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      var ex = assertFailsValidation(new ConfigBuilder().tokenSafetyMargin(margin).build());
      assertTrue(
          ex.getMessage().contains("REVIEW_TOKEN_SAFETY_MARGIN must be in (0, 1] and finite"),
          ex.getMessage());
    }
  }

  @Test
  void failsFastWhenPerModelFloatingPointSettingsAreNonFinite() {
    var settings = emptyModelSettings();
    lenient().when(settings.tokenSafetyMargin()).thenReturn(Optional.of(Double.NaN));
    lenient().when(settings.temperature()).thenReturn(Optional.of(Double.POSITIVE_INFINITY));
    lenient().when(settings.topP()).thenReturn(Optional.of(Double.NEGATIVE_INFINITY));
    lenient().when(settings.frequencyPenalty()).thenReturn(Optional.of(Double.NaN));
    lenient().when(settings.presencePenalty()).thenReturn(Optional.of(Double.POSITIVE_INFINITY));

    var ex = assertFailsValidation(new ConfigBuilder().model("non-finite", settings).build());
    var message = ex.getMessage();
    assertTrue(message.contains("token-safety-margin must be in (0, 1] and finite"), message);
    assertTrue(message.contains("temperature must be in [0, 2] and finite"), message);
    assertTrue(message.contains("top-p must be in (0, 1] and finite"), message);
    assertTrue(message.contains("frequency-penalty must be in [-2, 2] and finite"), message);
    assertTrue(message.contains("presence-penalty must be in [-2, 2] and finite"), message);
  }

  @Test
  void failsFastWhenEnabledBudgetDoesNotReserveConfiguredResponseCap() {
    var settings = emptyModelSettings();
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(8_193));

    var ex = assertFailsValidation(new ConfigBuilder().model("deepseek-chat", settings).build());
    assertTrue(
        ex.getMessage()
            .contains("effective output buffer (8192) must be >= max-output-tokens (8193)"),
        ex.getMessage());
  }

  @Test
  void pointsAtSeparateOutputBudgetWhenTheResponseCapExceedsTheBuffer() {
    // The message has to name the way out, or an operator on a separate-budget model reads it as
    // "raise the buffer" and pays 384000 tokens of diff budget to satisfy a rule that should not
    // have applied to them.
    var settings = emptyModelSettings();
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(8_193));

    var ex = assertFailsValidation(new ConfigBuilder().model("deepseek-chat", settings).build());
    assertTrue(
        ex.getMessage().contains("separate-output-budget=true"),
        "the failure must point at the escape hatch: " + ex.getMessage());
  }

  @Test
  void allowsAResponseCapAboveTheBufferWhenTheOutputBudgetIsSeparate() {
    // #494: on a model whose response allowance is independent of its input window, requiring the
    // buffer to cover the cap makes the model's real output allowance unconfigurable. 384000 out
    // against an 8192 buffer is exactly the deepseek-v4-flash shape, and it must boot.
    var settings = emptyModelSettings();
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(1_000_000));
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(384_000));
    lenient().when(settings.separateOutputBudget()).thenReturn(Optional.of(true));

    new ConfigBuilder().model("deepseek-chat", settings).build().validate();
  }

  @Test
  void stillRejectsAResponseCapAboveTheBufferOnASharedWindow() {
    // The narrowing must not become a deletion: without the flag the rule stands, because there
    // the output really is spent out of the window the prompt was packed into.
    var settings = emptyModelSettings();
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(1_000_000));
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(384_000));
    lenient().when(settings.separateOutputBudget()).thenReturn(Optional.of(false));

    var ex = assertFailsValidation(new ConfigBuilder().model("deepseek-chat", settings).build());
    assertTrue(ex.getMessage().contains("must be >= max-output-tokens (384000)"), ex.getMessage());
  }

  @Nested
  class SharedContextWindow {

    /**
     * The exact shipped-default shape #562 was filed for, minus the separate-output-budget flag.
     */
    private ThrillhouseConfig.AiPricingConfig.ModelSettings flashCaps(int input, int output) {
      var settings = emptyModelSettings();
      lenient().when(settings.contextTokens()).thenReturn(Optional.of(1_048_576));
      lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(input));
      lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(output));
      return settings;
    }

    @Test
    void failsFastWhenAModelsOwnCapsCannotBothFitItsContextWindow() {
      // #562: the provider counts the completion against the same 1048576-token context as the
      // prompt ("2062275 in the messages, 384000 in the completion"), so 1000000 in + 384000 out
      // is a request no prompt budget can rescue — and it was retried five times at full price.
      // Held against an INACTIVE entry here: a shipped pair that cannot boot must be rejected
      // before some deployment switches AI_MODEL to it.
      var ex =
          assertFailsValidation(
              new ConfigBuilder()
                  .modelName("deepseek-chat")
                  .model("deepseek-v4-flash", flashCaps(1_000_000, 384_000))
                  .build());
      assertTrue(
          ex.getMessage()
              .contains(
                  "model 'deepseek-v4-flash' cannot fit its own caps: max-input-tokens (1000000) +"
                      + " max-output-tokens (384000) = 1384000 exceeds context-tokens (1048576)"),
          ex.getMessage());
      assertTrue(
          ex.getMessage().contains("separate-output-budget=true"),
          "the failure must name the other contract as the way out: " + ex.getMessage());
    }

    @Test
    void bootsWhenTheCapsFitTheContextWindow() {
      new ConfigBuilder()
          .modelName("deepseek-v4-flash")
          .model("deepseek-v4-flash", flashCaps(900_000, 8_192))
          .build()
          .validate();
    }

    @Test
    void bootsWhenTheCapsExactlyFillTheContextWindow() {
      // The boundary is inclusive: a pair that exactly fills the window is sendable.
      new ConfigBuilder()
          .modelName("deepseek-v4-flash")
          .model("deepseek-v4-flash", flashCaps(1_040_384, 8_192))
          .build()
          .validate();
    }

    @Test
    void skipsTheWindowRuleForASeparateOutputBudgetModel() {
      // A model that really publishes its response allowance on top of the input window does not
      // spend the window on completions, so the sum is not the provider's arithmetic there.
      var settings = flashCaps(1_000_000, 384_000);
      lenient().when(settings.separateOutputBudget()).thenReturn(Optional.of(true));

      new ConfigBuilder().modelName("deepseek-chat").model("separate", settings).build().validate();
    }

    @Test
    void skipsTheWindowRuleWhenTheModelDeclaresNoWindow() {
      // context-tokens is optional; a model with no declared window keeps the previous behaviour
      // rather than being assumed to have one.
      var settings = emptyModelSettings();
      lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(1_000_000));
      lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(384_000));
      lenient().when(settings.outputBufferTokens()).thenReturn(Optional.of(384_000));

      new ConfigBuilder()
          .modelName("undeclared")
          .maxInputTokens(1_000_000)
          .model("undeclared", settings)
          .build()
          .validate();
    }

    @Test
    void failsFastWhenContextTokensIsNotPositive() {
      var settings = emptyModelSettings();
      lenient().when(settings.contextTokens()).thenReturn(Optional.of(0));

      var ex = assertFailsValidation(new ConfigBuilder().model("zero-window", settings).build());
      assertTrue(
          ex.getMessage()
              .contains("thrillhousebot.ai.models.\"zero-window\".context-tokens must be >= 1: 0"),
          ex.getMessage());
    }

    @Test
    void failsFastWhenTheEffectiveInputBudgetOverrunsAWindowSmallerThanTheDefaultCap() {
      // A model that declares its window but no max-input-tokens falls back to the 128000 default
      // cap, so the effective budget can exceed the real window with nothing in the entry looking
      // wrong. Checked on the resolved values for that reason.
      var settings = emptyModelSettings();
      lenient().when(settings.contextTokens()).thenReturn(Optional.of(32_768));

      var ex =
          assertFailsValidation(
              new ConfigBuilder().modelName("small").model("small", settings).build());
      assertTrue(
          ex.getMessage()
              .contains(
                  "the effective per-call budget for model 'small' (max input 48000 + response cap"
                      + " 8192 = 56192) exceeds its context window"),
          ex.getMessage());
      assertTrue(ex.getMessage().contains("REVIEW_MAX_INPUT_TOKENS"), ex.getMessage());
    }

    @Test
    void countsTheConciseCapAsTheResponseTermWhenItIsTheLargerOne() {
      // The production shape #562 was filed from: the entry's own caps fit the window, but the
      // summary/verifier/reply calls send REVIEW_CONCISE_MAX_OUTPUT_TOKENS against that same
      // window, so the worst case per call is the largest cap any lane may request.
      var settings = emptyModelSettings();
      lenient().when(settings.contextTokens()).thenReturn(Optional.of(1_048_576));
      lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(1_000_000));
      lenient().when(settings.outputBufferTokens()).thenReturn(Optional.of(65_536));

      var ex =
          assertFailsValidation(
              new ConfigBuilder()
                  .modelName("deepseek-v4-flash")
                  .maxInputTokens(1_000_000)
                  .conciseMaxOutputTokens(Optional.of(65_536))
                  .model("deepseek-v4-flash", settings)
                  .build());
      assertTrue(
          ex.getMessage().contains("(max input 1000000 + response cap 65536 = 1065536)"),
          ex.getMessage());
      assertTrue(
          ex.getMessage().contains("REVIEW_CONCISE_MAX_OUTPUT_TOKENS"),
          "the remedy must name the cap that actually drove the overrun: " + ex.getMessage());
    }

    @Test
    void bootsWithTheWorkingProductionCombinationOnTheSharedWindow() {
      // 900000 in + 96000 out = 996000 of the 1048576 window — the values a deployment is running
      // today. The rule must accept what demonstrably works, not just refuse what does not.
      var settings = flashCaps(900_000, 96_000);
      lenient().when(settings.outputBufferTokens()).thenReturn(Optional.of(96_000));

      new ConfigBuilder()
          .modelName("deepseek-v4-flash")
          .maxInputTokens(900_000)
          .conciseMaxOutputTokens(Optional.of(65_536))
          .model("deepseek-v4-flash", settings)
          .build()
          .validate();
    }

    @Test
    void skipsTheEffectiveWindowRuleWhenTokenBudgetingIsDisabled() {
      // With budgeting off there is no per-call input budget to bound, exactly like the other
      // budget rules — even against a window smaller than the concise cap.
      var settings = emptyModelSettings();
      lenient().when(settings.contextTokens()).thenReturn(Optional.of(4_096));

      new ConfigBuilder()
          .modelName("tiny")
          .maxInputTokens(0)
          .model("tiny", settings)
          .build()
          .validate();
    }

    @Test
    void skipsTheEffectiveWindowRuleForASeparateOutputBudgetModel() {
      var settings = flashCaps(1_048_576, 8_192);
      lenient().when(settings.separateOutputBudget()).thenReturn(Optional.of(true));

      new ConfigBuilder()
          .modelName("separate")
          .maxInputTokens(1_048_576)
          .model("separate", settings)
          .build()
          .validate();
    }

    @Test
    void reportsOnlyTheRealProblemWhenTheActiveModelsWindowIsNotPositive() {
      // A window of 0 is rejected on its own terms; treating it as a real ceiling as well would
      // add a second, derived complaint ("48000 + 8192 exceeds 0") that tells the operator nothing
      // and hides which key is actually wrong.
      var settings = emptyModelSettings();
      lenient().when(settings.contextTokens()).thenReturn(Optional.of(0));

      var ex =
          assertFailsValidation(
              new ConfigBuilder().modelName("zero-window").model("zero-window", settings).build());
      assertTrue(ex.getMessage().contains("context-tokens must be >= 1: 0"), ex.getMessage());
      assertFalse(ex.getMessage().contains("exceeds its context window"), ex.getMessage());
    }

    @Test
    void skipsTheEffectiveWindowRuleWhenTheActiveModelDeclaresNoWindow() {
      new ConfigBuilder().modelName("deepseek-chat").maxInputTokens(1_048_576).build().validate();
    }
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
    lenient().when(settings.frequencyPenalty()).thenReturn(Optional.of(-2.5));
    lenient().when(settings.presencePenalty()).thenReturn(Optional.of(2.5));
    var ex = assertFailsValidation(new ConfigBuilder().model("some-other-model", settings).build());
    var message = ex.getMessage();
    assertTrue(
        message.contains("thrillhousebot.ai.models.\"some-other-model\".max-input-tokens"),
        message);
    assertTrue(message.contains("temperature must be in [0, 2]"), message);
    assertTrue(message.contains("top-p must be in (0, 1]"), message);
    assertTrue(message.contains("frequency-penalty must be in [-2, 2]"), message);
    assertTrue(message.contains("presence-penalty must be in [-2, 2]"), message);
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
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(4_096));
    lenient().when(settings.frequencyPenalty()).thenReturn(Optional.of(-2.0));
    lenient().when(settings.presencePenalty()).thenReturn(Optional.of(2.0));
    lenient().when(settings.seed()).thenReturn(Optional.of(42));
    // The 4096 buffer override shrinks the shared-window reservation, so the concise cap must fit
    // inside it too (#517) — this test's subject is the per-model entry, not that rule.
    new ConfigBuilder()
        .conciseMaxOutputTokens(Optional.of(4_096))
        .model("deepseek-chat", settings)
        .build()
        .validate();
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
    // The 1000 buffer override also shrinks the shared-window reservation below the default
    // concise cap, so the cap is lowered with it (#517) — this test pins the repair, not that rule.
    new ConfigBuilder()
        .outputBufferTokens(45_000)
        .conciseMaxOutputTokens(Optional.of(1_000))
        .model("deepseek-chat", settings)
        .build()
        .validate();
  }

  @Test
  void allowsTokenBudgetingDisabledWithZeroInputTokens() {
    // 0 input tokens disables budgeting (single call); neither output-buffer cross-check applies,
    // even when the provider response cap is larger than the otherwise-unused buffer.
    var settings = emptyModelSettings();
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(8_192));
    new ConfigBuilder()
        .maxInputTokens(0)
        .outputBufferTokens(4_096)
        .model("deepseek-chat", settings)
        .build()
        .validate();
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
    var ex = assertFailsValidation(new ConfigBuilder().reasoningEffort("maximum").build());
    assertTrue(
        ex.getMessage()
            .contains("AI_REASONING_EFFORT must be one of none, low, medium, high, xhigh, max"),
        ex.getMessage());
  }

  @Test
  void failsFastWhenCiGatingIsInvalid() {
    var ex = assertFailsValidation(new ConfigBuilder().ciGating("loose").build());
    assertTrue(
        ex.getMessage().contains("REVIEW_CI_GATING must be one of strict, warn, off"),
        ex.getMessage());
  }

  @Test
  void failsFastWhenBlockingStrictnessIsInvalid() {
    var ex = assertFailsValidation(new ConfigBuilder().blockingStrictness("aggressive").build());
    assertTrue(
        ex.getMessage()
            .contains("REVIEW_BLOCKING_STRICTNESS must be one of lenient, balanced, strict"),
        ex.getMessage());
  }

  @Test
  void acceptsEveryCiGatingModeCaseInsensitivelyWithWhitespace() {
    for (var mode : new String[] {"strict", "WARN", " Off "}) {
      new ConfigBuilder().ciGating(mode).build().validate();
    }
  }

  @Test
  void acceptsEveryBlockingStrictnessCaseInsensitivelyWithWhitespace() {
    for (var mode : List.of("balanced", " STRICT ", "Lenient")) {
      new ConfigBuilder().blockingStrictness(mode).build().validate();
    }
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
    for (var effort : new String[] {"none", "LOW", " Medium ", "high", "XHigh", " max "}) {
      new ConfigBuilder().reasoningEnabled(true).reasoningEffort(effort).build().validate();
    }
  }

  @Test
  void failsFastWhenTheConciseReasoningEffortIsInvalid() {
    // The concise lane's own effort (#567) rides the same wire as AI_REASONING_EFFORT, so a typo
    // must be refused at boot rather than rejected by the provider mid-review.
    var ex =
        assertFailsValidation(
            new ConfigBuilder().conciseReasoningEffort(Optional.of("minimal")).build());
    assertTrue(
        ex.getMessage()
            .contains(
                "AI_REASONING_EFFORT_CONCISE must be one of none, low, medium, high, xhigh, max"),
        ex.getMessage());
    assertTrue(
        ex.getMessage().contains("(thrillhousebot.ai.reasoning.concise-effort): minimal"),
        ex.getMessage());
  }

  @Test
  void acceptsEveryConciseReasoningEffortCaseInsensitivelyWithWhitespace() {
    for (var effort : new String[] {"none", "LOW", " Medium ", "high", "XHigh", " max "}) {
      new ConfigBuilder()
          .reasoningEnabled(true)
          .conciseReasoningEffort(Optional.of(effort))
          .build()
          .validate();
    }
  }

  @Test
  void bannerNamesTheConciseLanesResolvedEffortAndItsKnob() {
    // The concise cap is already named in the boot banner; the effort has to be too, since it is
    // the one generation parameter that no longer follows the active model (#567).
    assertEquals(
        "low (AI_REASONING_EFFORT_CONCISE)",
        new ConfigBuilder()
            .reasoningEnabled(true)
            .reasoningEffort("max")
            .build()
            .conciseReasoningEffortBanner());
    assertEquals(
        "high (AI_REASONING_EFFORT_CONCISE)",
        new ConfigBuilder()
            .reasoningEnabled(true)
            .conciseReasoningEffort(Optional.of("HIGH"))
            .build()
            .conciseReasoningEffortBanner());
  }

  @Test
  void bannerSaysNoEffortIsSentWhileReasoningIsDisabled() {
    assertEquals(
        "not sent (AI_REASONING_ENABLED=false)",
        new ConfigBuilder()
            .reasoningEnabled(false)
            .conciseReasoningEffort(Optional.of("high"))
            .build()
            .conciseReasoningEffortBanner());
  }

  @Test
  void bootsWithoutBuildingTheConciseBannerWhenInfoLoggingIsOff() {
    // Both banner arguments are produced by a call, so the line sits behind a level check. With
    // INFO off that check is false and nothing is formatted — validation must still complete.
    var julLogger = java.util.logging.Logger.getLogger(StartupConfigValidator.class.getName());
    var originalLevel = julLogger.getLevel();
    julLogger.setLevel(java.util.logging.Level.WARNING);
    try {
      assertDoesNotThrow(() -> new ConfigBuilder().build().validate());
    } finally {
      julLogger.setLevel(originalLevel);
    }
  }

  @Test
  void bootsWhenTheConciseReasoningEffortIsUnset() {
    // Unset is the shipped state: the lane resolves its own default, so there is nothing to reject.
    new ConfigBuilder()
        .reasoningEnabled(true)
        .reasoningEffort("max")
        .conciseReasoningEffort(Optional.empty())
        .build()
        .validate();
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

  @Nested
  class ShippedDefaults {

    /**
     * Budget-relevant subset of the shipped per-model table, bound straight from {@code
     * src/main/resources/application.properties} (no env source, so every {@code ${VAR:default}}
     * resolves to its shipped default).
     */
    @ConfigMapping(prefix = "thrillhousebot.ai")
    interface ShippedModelsProbe {
      Map<String, ModelProbe> models();

      interface ModelProbe {
        @WithName("max-input-tokens")
        Optional<Integer> maxInputTokens();

        @WithName("context-tokens")
        Optional<Integer> contextTokens();

        @WithName("max-output-tokens")
        Optional<Integer> maxOutputTokens();

        @WithName("output-buffer-tokens")
        Optional<Integer> outputBufferTokens();

        @WithName("separate-output-budget")
        Optional<Boolean> separateOutputBudget();
      }
    }

    @Test
    void theShippedConciseEffortResolvesToUnset() throws Exception {
      // The shipped line is
      // thrillhousebot.ai.reasoning.concise-effort=${AI_REASONING_EFFORT_CONCISE:}
      // — an empty default, so with no env var set the lane must see "unset" (and resolve its own
      // default) rather than an empty string the provider would reject as a reasoning tier.
      var shipped =
          new SmallRyeConfigBuilder()
              .addDefaultInterceptors()
              .withValidateUnknown(false)
              .withSources(
                  new PropertiesConfigSource(
                      Paths.get("src/main/resources/application.properties").toUri().toURL()))
              .build();
      assertEquals(
          Optional.empty(),
          shipped.getOptionalValue("thrillhousebot.ai.reasoning.concise-effort", String.class));
    }

    @Test
    void everyShippedModelBootsUnderTheShippedConciseCap() throws Exception {
      // The #502 lesson: the concise reservation rule only fires for the ACTIVE model, so a bad
      // shipped combination (a concise default above some model's effective buffer) would pass
      // every fixed-model test and refuse boot only for deployments naming that model. Walk the
      // whole shipped table with each entry active instead of trusting the defaults.
      var shipped =
          new SmallRyeConfigBuilder()
              .addDefaultInterceptors() // ${VAR:default} expansion, as at runtime
              .withValidateUnknown(false)
              .withMapping(ShippedModelsProbe.class)
              .withSources(
                  new PropertiesConfigSource(
                      Paths.get("src/main/resources/application.properties").toUri().toURL()))
              .build();
      var conciseCap =
          shipped.getValue(
              "quarkus.langchain4j.openai.concise.chat-model.max-tokens", Integer.class);
      var reviewBuffer = shipped.getValue("thrillhousebot.review.output-buffer-tokens", int.class);
      var reviewBudget = shipped.getValue("thrillhousebot.review.max-input-tokens", int.class);
      var models = shipped.getConfigMapping(ShippedModelsProbe.class).models();
      assertFalse(models.isEmpty(), "the shipped model table must resolve");

      models.forEach(
          (name, probe) -> {
            var settings = emptyModelSettings();
            lenient().when(settings.maxInputTokens()).thenReturn(probe.maxInputTokens());
            lenient().when(settings.contextTokens()).thenReturn(probe.contextTokens());
            lenient().when(settings.maxOutputTokens()).thenReturn(probe.maxOutputTokens());
            lenient().when(settings.outputBufferTokens()).thenReturn(probe.outputBufferTokens());
            lenient()
                .when(settings.separateOutputBudget())
                .thenReturn(probe.separateOutputBudget());
            var validator =
                new ConfigBuilder()
                    .modelName(name)
                    .maxInputTokens(reviewBudget)
                    .outputBufferTokens(reviewBuffer)
                    .conciseMaxOutputTokens(Optional.of(conciseCap))
                    .model(name, settings)
                    .build();
            assertDoesNotThrow(
                validator::validate,
                "shipped defaults must boot with model '"
                    + name
                    + "' active under the shipped concise cap "
                    + conciseCap);
          });
    }
  }

  @Nested
  class ModelEnvVarMapping {

    /** The properties key is unquoted (hyphens only), so SmallRye reads single underscores. */
    @Test
    void shouldUseSingleUnderscoresForAHyphenOnlyModelKey() {
      assertEquals(
          "THRILLHOUSEBOT_AI_MODELS_DEEPSEEK_V4_PRO_",
          StartupConfigValidator.modelEnvPrefix("deepseek-v4-pro"));
    }

    /**
     * A dotted key needs quoting in the properties file and doubles the surrounding underscores.
     */
    @Test
    void shouldUseDoubledUnderscoresForADottedModelKey() {
      assertEquals(
          "THRILLHOUSEBOT_AI_MODELS__GPT_5_5__", StartupConfigValidator.modelEnvPrefix("gpt-5.5"));
    }

    @Test
    void shouldWarnWhenTheWrongUnderscoreFormIsUsedForAKnownModel() {
      // The doubled form belongs to dotted keys; on a hyphen-only key it resolves to nothing and
      // the model silently keeps its default cap.
      var warnings =
          StartupConfigValidator.unmappedModelEnvWarnings(
              Set.of("deepseek-v4-pro"),
              Map.of("THRILLHOUSEBOT_AI_MODELS__DEEPSEEK_V4_PRO__MAX_INPUT_TOKENS", "1000000"));

      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(warnings.get(0).contains("does not map to any model setting"), warnings.get(0));
      assertTrue(
          warnings.get(0).contains("THRILLHOUSEBOT_AI_MODELS_DEEPSEEK_V4_PRO_MAX_INPUT_TOKENS"),
          "must name the spelling that actually works: " + warnings.get(0));
    }

    @Test
    void shouldStaySilentWhenTheFormIsCorrect() {
      assertTrue(
          StartupConfigValidator.unmappedModelEnvWarnings(
                  Set.of("deepseek-v4-pro"),
                  Map.of("THRILLHOUSEBOT_AI_MODELS_DEEPSEEK_V4_PRO_MAX_INPUT_TOKENS", "1000000"))
              .isEmpty());
    }

    @Test
    void shouldStaySilentForACorrectlySpelledDottedKey() {
      assertTrue(
          StartupConfigValidator.unmappedModelEnvWarnings(
                  Set.of("gpt-5.5"),
                  Map.of("THRILLHOUSEBOT_AI_MODELS__GPT_5_5__MAX_INPUT_TOKENS", "256000"))
              .isEmpty());
    }

    @Test
    void shouldWarnWhenTheModelHasNoConfiguredStubAtAll() {
      var warnings =
          StartupConfigValidator.unmappedModelEnvWarnings(
              Set.of("deepseek-v4-pro"),
              Map.of("THRILLHOUSEBOT_AI_MODELS_LLAMA_9_MAX_INPUT_TOKENS", "8000"));

      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(warnings.get(0).contains("does not map to any configured model"), warnings.get(0));
      assertTrue(warnings.get(0).contains("empty stub"), warnings.get(0));
    }

    @Test
    void shouldFallBackToTheGenericWarningWhenTheSettingNameIsNotRecognised() {
      // Right model, wrong setting: naming a spelling would be misleading when the suffix itself
      // is not a real per-model setting, so this takes the generic branch.
      var warnings =
          StartupConfigValidator.unmappedModelEnvWarnings(
              Set.of("deepseek-v4-pro"),
              Map.of("THRILLHOUSEBOT_AI_MODELS__DEEPSEEK_V4_PRO__CONTEXT_SIZE", "1000000"));

      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(warnings.get(0).contains("does not map to any configured model"), warnings.get(0));
    }

    @Test
    void shouldIgnoreUnrelatedEnvironmentVariables() {
      assertTrue(
          StartupConfigValidator.unmappedModelEnvWarnings(
                  Set.of("deepseek-v4-pro"),
                  Map.of("PATH", "/usr/bin", "REVIEW_MAX_INPUT_TOKENS", "900000"))
              .isEmpty());
    }
  }
}
