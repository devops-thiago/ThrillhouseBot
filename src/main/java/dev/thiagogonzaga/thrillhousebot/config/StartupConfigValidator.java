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

    if (!problems.isEmpty()) {
      throw new ConfigValidationException(formatMessage(problems));
    }

    logDashboardStatus();
    log.info(
        "Configuration validated: GitHub App id, private key, webhook secret, and AI API key are"
            + " present.");
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
    // Keeping the message and log level on the enum makes dispatch a single boolean check. That
    // avoids the extra, never-exercised branch arcs that an enum-based switch or a per-constant
    // comparison chain would introduce, keeping patch coverage at 100 percent.
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
