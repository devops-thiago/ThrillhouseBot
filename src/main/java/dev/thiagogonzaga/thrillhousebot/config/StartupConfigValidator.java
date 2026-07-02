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

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates the hard-required configuration at startup and fails fast when anything is missing or
 * malformed, so a misconfigured self-hosted deployment is rejected at boot with a clear, actionable
 * message rather than surfacing as a confusing failure on the first webhook or review.
 *
 * <p>The GitHub App credentials ({@code GITHUB_APP_ID}, {@code GITHUB_PRIVATE_KEY}, {@code
 * GITHUB_WEBHOOK_SECRET}) and the AI API key ({@code AI_API_KEY}) are hard-required. Dashboard
 * OAuth ({@code GITHUB_CLIENT_ID} / {@code GITHUB_CLIENT_SECRET}) is optional: when absent the
 * dashboard login is simply disabled, which is logged rather than treated as an error.
 */
@ApplicationScoped
public class StartupConfigValidator {

  private static final Logger log = LoggerFactory.getLogger(StartupConfigValidator.class);

  private static final String PRIVATE_KEY_PROPERTY = "thrillhousebot.github.private-key";
  private static final String APP_ID_PROPERTY = "thrillhousebot.github.app-id";

  private final ThrillhouseConfig config;
  private final String aiApiKey;

  @Inject
  public StartupConfigValidator(
      ThrillhouseConfig config,
      @ConfigProperty(name = "quarkus.langchain4j.openai.api-key") Optional<String> aiApiKey) {
    this(config, aiApiKey.orElse(""));
  }

  /** Visible for tests: exercises validation outcomes without booting the CDI container. */
  StartupConfigValidator(ThrillhouseConfig config, String aiApiKey) {
    this.config = config;
    this.aiApiKey = aiApiKey;
  }

  void onStart(@Observes StartupEvent event) {
    validate();
  }

  /**
   * Checks every hard-required setting and throws {@link ConfigValidationException} listing all
   * problems at once (so a self-hoster fixes them in one pass instead of one boot per missing
   * value). Returns normally when configuration is valid, after logging the dashboard OAuth status.
   */
  void validate() {
    var problems = new ArrayList<String>();

    var github = config.github();
    validateAppId(problems, github.appId());
    validatePrivateKey(problems, github.privateKey());
    requirePresent(
        problems,
        github.webhookSecret(),
        "GITHUB_WEBHOOK_SECRET",
        "thrillhousebot.github.webhook-secret");
    requirePresent(problems, aiApiKey, "AI_API_KEY", "quarkus.langchain4j.openai.api-key");
    validateReviewBudget(problems, config.review());
    validateReasoningEffort(problems, config.ai().reasoning());

    if (!problems.isEmpty()) {
      throw new ConfigValidationException(formatMessage(problems));
    }

    logDashboardStatus();
    logReasoningStatus();
    log.info(
        "Configuration validated: GitHub App id, private key, webhook secret, and AI API key are"
            + " present.");
  }

  /**
   * Validates the token-budget settings (#53) so a misconfiguration is rejected at boot rather than
   * silently producing a degenerate budget (e.g. a negative input budget disables batching when the
   * operator meant to set a limit). Token budgeting is off when {@code max-input-tokens} is 0.
   */
  private static void validateReviewBudget(
      List<String> problems, ThrillhouseConfig.ReviewConfig review) {
    if (review.maxInputTokens() < 0) {
      problems.add(
          "REVIEW_MAX_INPUT_TOKENS must be >= 0, where 0 disables token budgeting"
              + " (thrillhousebot.review.max-input-tokens): "
              + review.maxInputTokens());
    }
    if (review.outputBufferTokens() < 0) {
      problems.add(
          "REVIEW_OUTPUT_BUFFER_TOKENS must be >= 0"
              + " (thrillhousebot.review.output-buffer-tokens): "
              + review.outputBufferTokens());
    }
    if (review.maxAiCalls() < 1) {
      problems.add(
          "REVIEW_MAX_AI_CALLS must be >= 1 (thrillhousebot.review.max-ai-calls): "
              + review.maxAiCalls());
    }
    var margin = review.tokenSafetyMargin();
    if (margin <= 0 || margin > 1.0) {
      problems.add(
          "REVIEW_TOKEN_SAFETY_MARGIN must be in (0, 1]"
              + " (thrillhousebot.review.token-safety-margin): "
              + margin);
    }
    // The buffer is subtracted from the margin-scaled ceiling at runtime, so validating it
    // against the raw max would pass configs whose effective budget is still <= 0 (e.g.
    // 48000/45000/0.9 -> -1800) — the silent-disable this validator exists to reject.
    if (review.maxInputTokens() > 0
        && margin > 0
        && margin <= 1.0
        && (int) (review.maxInputTokens() * margin) - review.outputBufferTokens() <= 0) {
      problems.add(
          "REVIEW_OUTPUT_BUFFER_TOKENS ("
              + review.outputBufferTokens()
              + ") must be less than REVIEW_MAX_INPUT_TOKENS x REVIEW_TOKEN_SAFETY_MARGIN ("
              + (int) (review.maxInputTokens() * margin)
              + ") so there is budget left for the diff");
    }
  }

  private static void requirePresent(
      List<String> problems, String value, String envVar, String property) {
    if (value == null || value.isBlank()) {
      problems.add(envVar + " is required but is not set (" + property + ")");
    }
  }

  /**
   * The GitHub App id is the JWT issuer; a non-numeric value passes a bare presence check but
   * yields a JWT GitHub rejects on the first call, so reject it at boot — exactly the late failure
   * this validator exists to prevent.
   */
  private static void validateAppId(List<String> problems, String appId) {
    if (appId == null || appId.isBlank()) {
      problems.add("GITHUB_APP_ID is required but is not set (" + APP_ID_PROPERTY + ")");
      return;
    }
    if (!appId.strip().chars().allMatch(Character::isDigit)) {
      problems.add(
          "GITHUB_APP_ID must be the numeric GitHub App id (" + APP_ID_PROPERTY + "): " + appId);
    }
  }

  private static void validatePrivateKey(List<String> problems, String privateKey) {
    if (privateKey == null || privateKey.isBlank()) {
      problems.add("GITHUB_PRIVATE_KEY is required but is not set (" + PRIVATE_KEY_PROPERTY + ")");
      return;
    }
    try {
      RsaPrivateKeyParser.parse(privateKey);
    } catch (IllegalArgumentException e) {
      problems.add(
          "GITHUB_PRIVATE_KEY is set but is not a valid PEM RSA private key ("
              + PRIVATE_KEY_PROPERTY
              + "): "
              + e.getMessage());
    }
  }

  /**
   * Validated even while reasoning is disabled: an operator who sets an invalid effort value has
   * expressed clear intent, and rejecting the typo at boot beats discovering it when the flag is
   * later flipped on.
   */
  private static void validateReasoningEffort(
      List<String> problems, ThrillhouseConfig.AiPricingConfig.ReasoningConfig reasoning) {
    var allowed = ThrillhouseConfig.AiPricingConfig.ReasoningConfig.ALLOWED_EFFORTS;
    if (!allowed.contains(
        ThrillhouseConfig.AiPricingConfig.ReasoningConfig.normalize(reasoning.effort()))) {
      problems.add(
          "AI_REASONING_EFFORT must be one of "
              + String.join(", ", allowed)
              + " (thrillhousebot.ai.reasoning.effort): "
              + reasoning.effort());
    }
  }

  private void logReasoningStatus() {
    var reasoning = config.ai().reasoning();
    if (reasoning.enabled()) {
      var effort = ThrillhouseConfig.AiPricingConfig.ReasoningConfig.normalize(reasoning.effort());
      log.info(
          "AI reasoning enabled (reasoning_effort={}) — reasoning tokens are billed as output"
              + " tokens, so expect higher cost and latency on reasoning-capable models.",
          effort);
    }
  }

  private static String formatMessage(List<String> problems) {
    var sb =
        new StringBuilder(
            "ThrillhouseBot cannot start — required configuration is missing or invalid:");
    for (var problem : problems) {
      sb.append(System.lineSeparator()).append("  - ").append(problem);
    }
    sb.append(System.lineSeparator())
        .append(
            "Set the values above (see .env.example) and restart. Dashboard OAuth"
                + " (GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET) is optional.");
    return sb.toString();
  }

  private void logDashboardStatus() {
    var hasClientId = config.dashboard().clientId().filter(s -> !s.isBlank()).isPresent();
    var hasClientSecret = config.dashboard().clientSecret().filter(s -> !s.isBlank()).isPresent();
    // Message and log level live on the enum, so dispatch is a single boolean check.
    var status = dashboardOauthStatus(hasClientId, hasClientSecret);
    if (status.warn) {
      log.warn(status.message);
    } else {
      log.info(status.message);
    }
  }

  /** Dashboard OAuth login state derived from whether each credential is present and non-blank. */
  enum DashboardOauthStatus {
    ENABLED(
        false,
        "Dashboard OAuth login enabled (GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET are set)."),
    DISABLED(
        false,
        "Dashboard OAuth login disabled — set GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET to enable it."),
    PARTIAL(
        true,
        "Dashboard OAuth login disabled — only one of GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET is set;"
            + " set both (or neither) to fix this.");

    private final boolean warn;
    private final String message;

    DashboardOauthStatus(boolean warn, String message) {
      this.warn = warn;
      this.message = message;
    }
  }

  /** Pure classification of the OAuth pair, kept separate so every branch is unit-testable. */
  static DashboardOauthStatus dashboardOauthStatus(boolean hasClientId, boolean hasClientSecret) {
    if (hasClientId && hasClientSecret) {
      return DashboardOauthStatus.ENABLED;
    }
    if (!hasClientId && !hasClientSecret) {
      return DashboardOauthStatus.DISABLED;
    }
    return DashboardOauthStatus.PARTIAL;
  }
}
