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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Resolution precedence for the active model's AI settings. */
class ActiveModelSettingsTest {

  private static final String MODEL = "test-model";

  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);
  private final ThrillhouseConfig.ReviewConfig review = mock(ThrillhouseConfig.ReviewConfig.class);
  private final ThrillhouseConfig.AiPricingConfig ai =
      mock(ThrillhouseConfig.AiPricingConfig.class);
  private final Map<String, ThrillhouseConfig.AiPricingConfig.ModelSettings> models =
      new HashMap<>();
  private final ActiveModelSettings active = new ActiveModelSettings(config, MODEL);

  {
    lenient().when(config.review()).thenReturn(review);
    lenient().when(config.ai()).thenReturn(ai);
    lenient().when(ai.models()).thenReturn(models);
    lenient().when(review.maxInputTokens()).thenReturn(48_000);
    lenient().when(review.outputBufferTokens()).thenReturn(8_192);
    lenient().when(review.tokenSafetyMargin()).thenReturn(0.9);
  }

  private ThrillhouseConfig.AiPricingConfig.ModelSettings entry() {
    var settings = mock(ThrillhouseConfig.AiPricingConfig.ModelSettings.class);
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.empty());
    lenient().when(settings.outputBufferTokens()).thenReturn(Optional.empty());
    lenient().when(settings.tokenSafetyMargin()).thenReturn(Optional.empty());
    lenient().when(settings.temperature()).thenReturn(Optional.empty());
    lenient().when(settings.topP()).thenReturn(Optional.empty());
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.empty());
    return settings;
  }

  @Test
  void fallsBackToTheGlobalReviewKeysWithoutAnEntry() {
    assertEquals(MODEL, active.modelName());
    assertEquals(48_000, active.maxInputTokens());
    assertEquals(8_192, active.outputBufferTokens());
    assertEquals(0.9, active.tokenSafetyMargin());
    assertTrue(active.temperature().isEmpty());
    assertTrue(active.topP().isEmpty());
    assertTrue(active.maxOutputTokens().isEmpty());
    assertFalse(active.budgetClampedByModelCap());
  }

  @Test
  void defaultInputCapClampsARaisedGlobalBudget() {
    lenient().when(review.maxInputTokens()).thenReturn(500_000);
    assertEquals(
        ThrillhouseConfig.AiPricingConfig.ModelSettings.DEFAULT_MAX_INPUT_TOKENS,
        active.maxInputTokens());
    assertTrue(active.budgetClampedByModelCap());
  }

  @Test
  void anExplicitCapReplacesTheDefault() {
    var settings = entry();
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(1_000_000));
    models.put(MODEL, settings);
    lenient().when(review.maxInputTokens()).thenReturn(500_000);
    assertEquals(500_000, active.maxInputTokens());
    assertFalse(active.budgetClampedByModelCap());
  }

  @Test
  void disabledBudgetingStaysDisabledRegardlessOfTheCap() {
    var settings = entry();
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(64_000));
    models.put(MODEL, settings);
    lenient().when(review.maxInputTokens()).thenReturn(0);
    assertEquals(0, active.maxInputTokens());
  }

  @Test
  void perModelOverridesWinOverTheGlobalKeys() {
    var settings = entry();
    lenient().when(settings.outputBufferTokens()).thenReturn(Optional.of(2_048));
    lenient().when(settings.tokenSafetyMargin()).thenReturn(Optional.of(0.8));
    lenient().when(settings.temperature()).thenReturn(Optional.of(0.1));
    lenient().when(settings.topP()).thenReturn(Optional.of(0.9));
    lenient().when(settings.maxOutputTokens()).thenReturn(Optional.of(2_000));
    models.put(MODEL, settings);
    assertEquals(2_048, active.outputBufferTokens());
    assertEquals(0.8, active.tokenSafetyMargin());
    assertEquals(Optional.of(0.1), active.temperature());
    assertEquals(Optional.of(0.9), active.topP());
    assertEquals(Optional.of(2_000), active.maxOutputTokens());
  }

  @Test
  void anotherModelsEntryIsIgnored() {
    var settings = entry();
    lenient().when(settings.maxInputTokens()).thenReturn(Optional.of(1_000));
    lenient().when(settings.temperature()).thenReturn(Optional.of(1.9));
    models.put("some-other-model", settings);
    assertEquals(48_000, active.maxInputTokens());
    assertTrue(active.temperature().isEmpty());
  }
}
