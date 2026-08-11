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

import dev.thiagogonzaga.thrillhousebot.review.BlockingStrictness;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private final ActiveModelSettings activeModel;
  private final Optional<Integer> conciseMaxOutputTokens;

  @Inject
  public StartupConfigValidator(
      ThrillhouseConfig config,
      @ConfigProperty(name = "quarkus.langchain4j.openai.api-key") Optional<String> aiApiKey,
      ActiveModelSettings activeModel,
      @ConfigProperty(name = "quarkus.langchain4j.openai.concise.chat-model.max-tokens")
          Optional<Integer> conciseMaxOutputTokens) {
    this(config, aiApiKey.orElse(""), activeModel, conciseMaxOutputTokens);
  }

  /** Visible for tests: exercises validation outcomes without booting the CDI container. */
  StartupConfigValidator(
      ThrillhouseConfig config,
      String aiApiKey,
      ActiveModelSettings activeModel,
      Optional<Integer> conciseMaxOutputTokens) {
    this.config = config;
    this.aiApiKey = aiApiKey;
    this.activeModel = activeModel;
    this.conciseMaxOutputTokens = conciseMaxOutputTokens;
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
    validateCiGating(problems, config.review());
    validateBlockingStrictness(problems, config.review());
    validateModelSettings(problems, config.ai().models());
    validateEffectiveBudget(problems);
    validateReasoningEffort(problems, config.ai().reasoning());
    validateConciseResponseCap(problems);

    if (!problems.isEmpty()) {
      throw new ConfigValidationException(formatMessage(problems));
    }

    logDashboardStatus();
    logReasoningStatus();
    logCiGatingStatus();
    logActiveModelStatus();
    logConciseModelStatus();
    warnUnmappedModelEnvVars(System.getenv());
    log.info(
        "Configuration validated: GitHub App id, private key, webhook secret, and AI API key are"
            + " present.");
  }

  /**
   * Rejects an unrecognized {@code REVIEW_BLOCKING_STRICTNESS} at boot — the same fail-fast pattern
   * as {@link #validateReasoningEffort} — so a typo never silently falls back to balanced.
   */
  private static void validateBlockingStrictness(
      List<String> problems, ThrillhouseConfig.ReviewConfig review) {
    var raw = review.blockingStrictness();
    if (BlockingStrictness.fromString(raw).isEmpty()) {
      problems.add(
          "REVIEW_BLOCKING_STRICTNESS must be one of "
              + String.join(", ", BlockingStrictness.ALLOWED)
              + " (thrillhousebot.review.blocking-strictness): "
              + raw);
    }
  }

  /**
   * Validates the token-budget settings so a misconfiguration is rejected at boot rather than
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
    if (!Double.isFinite(margin) || margin <= 0 || margin > 1.0) {
      problems.add(
          "REVIEW_TOKEN_SAFETY_MARGIN must be in (0, 1] and finite"
              + " (thrillhousebot.review.token-safety-margin): "
              + margin);
    }
    if (review.maxTokensPerReview() < 0) {
      problems.add(
          "REVIEW_MAX_TOKENS_PER_REVIEW must be >= 0, where 0 disables the per-review token spend"
              + " ceiling (thrillhousebot.review.max-tokens-per-review): "
              + review.maxTokensPerReview());
    }
  }

  /**
   * Validates every per-model settings entry — not just the active model's — because an operator
   * who wrote an invalid value has expressed clear intent, and rejecting the typo at boot beats
   * discovering it when {@code AI_MODEL} is later switched to that model.
   */
  private static void validateModelSettings(
      List<String> problems, Map<String, ThrillhouseConfig.AiPricingConfig.ModelSettings> models) {
    models.forEach(
        (name, settings) -> {
          var prefix = "thrillhousebot.ai.models.\"" + name + "\".";
          settings
              .maxInputTokens()
              .filter(v -> v < 1)
              .ifPresent(v -> problems.add(prefix + "max-input-tokens must be >= 1: " + v));
          settings
              .outputBufferTokens()
              .filter(v -> v < 0)
              .ifPresent(v -> problems.add(prefix + "output-buffer-tokens must be >= 0: " + v));
          settings
              .tokenSafetyMargin()
              .filter(v -> !Double.isFinite(v) || v <= 0 || v > 1.0)
              .ifPresent(
                  v ->
                      problems.add(
                          prefix + "token-safety-margin must be in (0, 1] and finite: " + v));
          settings
              .temperature()
              .filter(v -> !Double.isFinite(v) || v < 0 || v > 2.0)
              .ifPresent(
                  v -> problems.add(prefix + "temperature must be in [0, 2] and finite: " + v));
          settings
              .topP()
              .filter(v -> !Double.isFinite(v) || v <= 0 || v > 1.0)
              .ifPresent(v -> problems.add(prefix + "top-p must be in (0, 1] and finite: " + v));
          settings
              .maxOutputTokens()
              .filter(v -> v < 1)
              .ifPresent(v -> problems.add(prefix + "max-output-tokens must be >= 1: " + v));
          settings
              .frequencyPenalty()
              .filter(v -> !Double.isFinite(v) || v < -2.0 || v > 2.0)
              .ifPresent(
                  v ->
                      problems.add(
                          prefix + "frequency-penalty must be in [-2, 2] and finite: " + v));
          settings
              .presencePenalty()
              .filter(v -> !Double.isFinite(v) || v < -2.0 || v > 2.0)
              .ifPresent(
                  v ->
                      problems.add(
                          prefix + "presence-penalty must be in [-2, 2] and finite: " + v));
        });
  }

  /**
   * Rejects a configuration whose <em>effective</em> per-call budget for the active model is
   * degenerate or reserves fewer output tokens than the provider may generate. Checked on the
   * active model's resolved values because that is the combination the budgeter actually uses.
   */
  private void validateEffectiveBudget(List<String> problems) {
    var maxInputTokens = activeModel.maxInputTokens();
    var margin = activeModel.tokenSafetyMargin();
    // What the budgeter will actually hold back — zero when the response has a budget of its own.
    // Reading the same value the planner reads keeps the two from disagreeing about the arithmetic.
    var outputBuffer = activeModel.reservedOutputTokens();
    if (maxInputTokens > 0
        && Double.isFinite(margin)
        && margin > 0
        && margin <= 1.0
        && (int) (maxInputTokens * margin) - outputBuffer <= 0) {
      problems.add(
          "the effective output buffer ("
              + outputBuffer
              + ") must be less than the effective max input tokens x safety margin ("
              + (int) (maxInputTokens * margin)
              + ") for model '"
              + activeModel.modelName()
              + "' so there is budget left for the diff (thrillhousebot.review.* with"
              + " thrillhousebot.ai.models overrides)");
    }
    // Only meaningful on a shared window, where output tokens are spent out of the same pool the
    // budgeter packed the prompt into: licensing more output than was reserved overruns it. When
    // the response has a budget of its own there is nothing to reserve, and enforcing the ceiling
    // would make the model's real output allowance unconfigurable — so the rule does not apply.
    if (maxInputTokens > 0 && !activeModel.separateOutputBudget()) {
      activeModel
          .maxOutputTokens()
          .filter(maxOutput -> maxOutput > outputBuffer)
          .ifPresent(
              maxOutput ->
                  problems.add(
                      "the effective output buffer ("
                          + outputBuffer
                          + ") must be >= max-output-tokens ("
                          + maxOutput
                          + ") for model '"
                          + activeModel.modelName()
                          + "' so the token budget reserves the configured response cap. Set"
                          + " thrillhousebot.ai.models.\""
                          + activeModel.modelName()
                          + "\".separate-output-budget=true if this model's response allowance is"
                          + " independent of its input window."));
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

  /**
   * Rejects a degenerate response cap for the {@code concise} named model (the summary, verifier,
   * and reply calls — #498) at boot, the same fail-fast contract as the other budget keys. Empty is
   * allowed: an operator who clears {@code REVIEW_CONCISE_MAX_OUTPUT_TOKENS} drops the cap and the
   * provider default applies.
   *
   * <p>On a shared-window active model the concise cap is also held to the same reservation rule as
   * the active model's own response cap ({@link #validateEffectiveBudget}): the concise calls spend
   * their {@code max_tokens} out of the same window the budgeter packed the prompt into with only
   * {@code reservedOutputTokens} held back, so licensing more output than was reserved overruns the
   * window just as surely from this knob (#517). Not applied when token budgeting is off (no packed
   * prompt to overrun) or when the active model declares {@code separate-output-budget} (nothing is
   * reserved, and the response never draws on the window).
   *
   * <p>The buffer remedy names the knob that actually sources the effective buffer: when the active
   * model carries an {@code output-buffer-tokens} override, {@link
   * ActiveModelSettings#outputBufferTokens} never reads the global value, so advising {@code
   * REVIEW_OUTPUT_BUFFER_TOKENS} there would send the operator to an inert knob and re-boot into
   * the identical refusal (#528).
   */
  private void validateConciseResponseCap(List<String> problems) {
    conciseMaxOutputTokens
        .filter(v -> v < 1)
        .ifPresent(
            v ->
                problems.add(
                    "REVIEW_CONCISE_MAX_OUTPUT_TOKENS must be >= 1"
                        + " (quarkus.langchain4j.openai.concise.chat-model.max-tokens): "
                        + v));
    if (activeModel.maxInputTokens() > 0 && !activeModel.separateOutputBudget()) {
      var outputBuffer = activeModel.reservedOutputTokens();
      // A per-model output-buffer-tokens override shadows the global env var entirely, so the
      // remedy must name whichever knob the effective buffer actually came from (#528).
      var bufferKnob =
          Optional.ofNullable(config.ai().models().get(activeModel.modelName()))
                  .flatMap(ThrillhouseConfig.AiPricingConfig.ModelSettings::outputBufferTokens)
                  .isPresent()
              ? "thrillhousebot.ai.models.\"" + activeModel.modelName() + "\".output-buffer-tokens"
              : "REVIEW_OUTPUT_BUFFER_TOKENS";
      conciseMaxOutputTokens
          .filter(v -> v > outputBuffer)
          .ifPresent(
              v ->
                  problems.add(
                      "the effective output buffer ("
                          + outputBuffer
                          + ") must be >= REVIEW_CONCISE_MAX_OUTPUT_TOKENS ("
                          + v
                          + ", quarkus.langchain4j.openai.concise.chat-model.max-tokens) for model"
                          + " '"
                          + activeModel.modelName()
                          + "' so the token budget reserves the response cap the"
                          + " summary/verifier/reply calls send. Lower"
                          + " REVIEW_CONCISE_MAX_OUTPUT_TOKENS, raise "
                          + bufferKnob
                          + " to cover it, or set"
                          + " thrillhousebot.ai.models.\""
                          + activeModel.modelName()
                          + "\".separate-output-budget=true if this model's response allowance is"
                          + " independent of its input window."));
    }
  }

  /**
   * Rejects an unrecognized {@code REVIEW_CI_GATING} value at boot so a typo never silently falls
   * through to the fail-closed default.
   */
  private static void validateCiGating(
      List<String> problems, ThrillhouseConfig.ReviewConfig review) {
    var allowed = ThrillhouseConfig.ReviewConfig.ALLOWED_CI_GATING;
    if (!allowed.contains(ThrillhouseConfig.ReviewConfig.normalizeCiGating(review.ciGating()))) {
      problems.add(
          "REVIEW_CI_GATING must be one of "
              + String.join(", ", allowed)
              + " (thrillhousebot.review.ci-gating): "
              + review.ciGating());
    }
  }

  private void logCiGatingStatus() {
    var normalized = ThrillhouseConfig.ReviewConfig.normalizeCiGating(config.review().ciGating());
    if ("strict".equals(normalized)) {
      return;
    }
    log.info(
        "CI gating mode={} — APPROVE will {}.",
        normalized,
        "warn".equals(normalized)
            ? "be allowed while CI is noted as uncertain in the summary/check"
            : "ignore CI status (findings-only gate)");
  }

  /**
   * Surfaces the per-model resolution at boot: warns when the model's input cap silently lowers the
   * configured global budget (the operator raised {@code REVIEW_MAX_INPUT_TOKENS} past the model's
   * window — or past the 128k default for a model with no entry — and should raise the cap
   * deliberately), and logs the active generation parameters so a tuning entry that targets the
   * wrong model name is visible immediately.
   */
  private void logActiveModelStatus() {
    if (config.review().maxInputTokens() > 0 && activeModel.budgetClampedByModelCap()) {
      log.warn(
          "REVIEW_MAX_INPUT_TOKENS ({}) exceeds the input cap of model '{}' ({}); using {}. Raise"
              + " thrillhousebot.ai.models.\"{}\".max-input-tokens if the model's context window"
              + " allows it.",
          config.review().maxInputTokens(),
          activeModel.modelName(),
          activeModel.modelInputCap(),
          activeModel.maxInputTokens(),
          activeModel.modelName());
    }
    if (config.ai().models().containsKey(activeModel.modelName())) {
      var temperature = orProviderDefault(activeModel.temperature());
      var topP = orProviderDefault(activeModel.topP());
      var maxOutputTokens = orProviderDefault(activeModel.maxOutputTokens());
      var frequencyPenalty = orProviderDefault(activeModel.frequencyPenalty());
      var presencePenalty = orProviderDefault(activeModel.presencePenalty());
      var seed = activeModel.seed().map(String::valueOf).orElse("none");
      log.info(
          "Per-model AI settings active for '{}': max-input-tokens={}, output-buffer-tokens={}"
              + " (reserved={}), separate-output-budget={}, token-safety-margin={}, temperature={},"
              + " top-p={}, max-output-tokens={}, frequency-penalty={}, presence-penalty={},"
              + " seed={}",
          activeModel.modelName(),
          activeModel.maxInputTokens(),
          activeModel.outputBufferTokens(),
          activeModel.reservedOutputTokens(),
          activeModel.separateOutputBudget(),
          activeModel.tokenSafetyMargin(),
          temperature,
          topP,
          maxOutputTokens,
          frequencyPenalty,
          presencePenalty,
          seed);
    }
  }

  /**
   * Mirrors {@link #logActiveModelStatus}'s per-model line for the {@code concise} named model, so
   * the boot log states which response cap the summary/verifier/reply calls run under — including
   * the shipped 8192 default, which applies without any operator action.
   */
  private void logConciseModelStatus() {
    log.info(
        "Concise model active for summary/verifier/reply calls: max-output-tokens={}"
            + " (REVIEW_CONCISE_MAX_OUTPUT_TOKENS); other generation parameters follow the active"
            + " model '{}'.",
        orProviderDefault(conciseMaxOutputTokens),
        activeModel.modelName());
  }

  /** Env-var prefix every per-model setting shares. */
  private static final String MODEL_ENV_PREFIX = "THRILLHOUSEBOT_AI_MODELS_";

  /** The per-model setting names, in env-var form. */
  private static final List<String> MODEL_SETTING_SUFFIXES =
      List.of(
          "MAX_INPUT_TOKENS",
          "OUTPUT_BUFFER_TOKENS",
          "TOKEN_SAFETY_MARGIN",
          "MAX_OUTPUT_TOKENS",
          "SEPARATE_OUTPUT_BUDGET",
          "FREQUENCY_PENALTY",
          "PRESENCE_PENALTY",
          "TEMPERATURE",
          "TOP_P",
          "SEED");

  /**
   * The env-var prefix SmallRye actually reads for a model key. A key that needs quoting in the
   * properties file because it contains a dot ({@code "gpt-5.5"}) maps with a doubled underscore
   * either side of the key; a hyphen-only key ({@code deepseek-v4-pro}) maps with single ones.
   */
  static String modelEnvPrefix(String model) {
    var upper = model.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    return model.indexOf('.') >= 0
        ? MODEL_ENV_PREFIX + "_" + upper + "__"
        : MODEL_ENV_PREFIX + upper + "_";
  }

  /** Separator-insensitive form, so both underscore spellings of one key compare equal. */
  private static String separatorInsensitive(String value) {
    return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
  }

  /**
   * Warns about {@code THRILLHOUSEBOT_AI_MODELS_*} variables that map to no configured model.
   * Choosing the wrong underscore spelling resolves to nothing and the tuning silently never
   * applies — the model keeps its default cap, and the only downstream signal is a budget that
   * looks inexplicably small. Package-private so tests can drive an environment map.
   */
  void warnUnmappedModelEnvVars(Map<String, String> environment) {
    unmappedModelEnvWarnings(config.ai().models().keySet(), environment).forEach(log::warn);
  }

  /**
   * The warnings {@link #warnUnmappedModelEnvVars} emits, as plain strings so the rule is testable
   * without a log appender. Sorted so the output is stable across environment iteration order.
   */
  static List<String> unmappedModelEnvWarnings(
      Set<String> configuredModels, Map<String, String> environment) {
    var warnings = new ArrayList<String>();
    for (var name : environment.keySet()) {
      if (!name.startsWith(MODEL_ENV_PREFIX)
          || configuredModels.stream().anyMatch(model -> name.startsWith(modelEnvPrefix(model)))) {
        continue;
      }
      var intended =
          configuredModels.stream()
              .filter(
                  model ->
                      separatorInsensitive(name)
                          .startsWith(separatorInsensitive(modelEnvPrefix(model))))
              .findFirst();
      var setting = MODEL_SETTING_SUFFIXES.stream().filter(name::endsWith).findFirst();
      if (intended.isPresent() && setting.isPresent()) {
        warnings.add(
            name
                + " does not map to any model setting and is being ignored — model '"
                + intended.get()
                + "' is read as "
                + modelEnvPrefix(intended.get())
                + setting.get()
                + ". Fix the underscores or the setting will silently never apply.");
      } else {
        warnings.add(
            name
                + " does not map to any configured model and is being ignored. Known models: "
                + configuredModels
                + ". An unlisted model needs an empty stub"
                + " (thrillhousebot.ai.models.\"<model>\".max-input-tokens=) so its key exists.");
      }
    }
    warnings.sort(String::compareTo);
    return List.copyOf(warnings);
  }

  private static String orProviderDefault(Optional<? extends Number> value) {
    return value.map(String::valueOf).orElse("provider default");
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
