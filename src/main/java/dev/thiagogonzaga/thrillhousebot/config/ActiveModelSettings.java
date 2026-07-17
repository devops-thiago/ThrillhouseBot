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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.AiPricingConfig.ModelSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves the effective AI settings for the configured model ({@code AI_MODEL}) from the per-model
 * map ({@code thrillhousebot.ai.models.*}) with the global review keys as fallback, so consumers
 * never re-implement the precedence. The budget keys feed the diff budgeter; the generation
 * parameters feed the chat-model builders.
 */
@ApplicationScoped
public class ActiveModelSettings {

  private final ThrillhouseConfig config;
  private final String modelName;

  @Inject
  public ActiveModelSettings(
      ThrillhouseConfig config,
      @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name") String modelName) {
    this.config = config;
    this.modelName = modelName;
  }

  /** The configured model name ({@code AI_MODEL}) — the key looked up in the per-model map. */
  public String modelName() {
    return modelName;
  }

  private Optional<ModelSettings> settings() {
    return Optional.ofNullable(config.ai().models().get(modelName));
  }

  /**
   * The model's input-token hard cap: its explicit {@code max-input-tokens} entry, or {@link
   * ModelSettings#DEFAULT_MAX_INPUT_TOKENS} when the model has none.
   */
  public int modelInputCap() {
    return settings()
        .flatMap(ModelSettings::maxInputTokens)
        .orElse(ModelSettings.DEFAULT_MAX_INPUT_TOKENS);
  }

  /**
   * The effective per-call max input tokens the budgeter uses: the smaller of the global {@code
   * thrillhousebot.review.max-input-tokens} budget and {@link #modelInputCap()}. Returns 0 when
   * token budgeting is explicitly disabled (global budget {@code <= 0}) — the cap bounds the
   * budget, it never re-enables budgeting the operator switched off.
   */
  public int maxInputTokens() {
    var budget = config.review().maxInputTokens();
    if (budget <= 0) {
      return 0;
    }
    return Math.min(budget, modelInputCap());
  }

  /** Whether {@link #maxInputTokens()} is the cap rather than the configured global budget. */
  public boolean budgetClampedByModelCap() {
    return config.review().maxInputTokens() > modelInputCap();
  }

  /** Output-buffer tokens: the model's override, else the global review value. */
  public int outputBufferTokens() {
    return settings()
        .flatMap(ModelSettings::outputBufferTokens)
        .orElseGet(() -> config.review().outputBufferTokens());
  }

  /** Token safety margin: the model's override, else the global review value. */
  public double tokenSafetyMargin() {
    return settings()
        .flatMap(ModelSettings::tokenSafetyMargin)
        .orElseGet(() -> config.review().tokenSafetyMargin());
  }

  /** Sampling temperature for the active model; empty leaves the provider default. */
  public Optional<Double> temperature() {
    return settings().flatMap(ModelSettings::temperature);
  }

  /** Nucleus-sampling top-p for the active model; empty leaves the provider default. */
  public Optional<Double> topP() {
    return settings().flatMap(ModelSettings::topP);
  }

  /** Response-length cap ({@code max_tokens}) for the active model; empty leaves the default. */
  public Optional<Integer> maxOutputTokens() {
    return settings().flatMap(ModelSettings::maxOutputTokens);
  }

  /** {@code frequency_penalty} for the active model; empty leaves the provider default. */
  public Optional<Double> frequencyPenalty() {
    return settings().flatMap(ModelSettings::frequencyPenalty);
  }

  /** {@code presence_penalty} for the active model; empty leaves the provider default. */
  public Optional<Double> presencePenalty() {
    return settings().flatMap(ModelSettings::presencePenalty);
  }

  /** Determinism {@code seed} for the active model; empty sends no seed. */
  public Optional<Integer> seed() {
    return settings().flatMap(ModelSettings::seed);
  }
}
