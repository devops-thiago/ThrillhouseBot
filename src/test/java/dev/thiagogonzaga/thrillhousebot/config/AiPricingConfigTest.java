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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Guards the default model pricing table in application.properties. Model names containing '.' or
 * '/' must be double-quoted so SmallRye maps them as single keys; this verifies they actually
 * resolve and aren't silently dropped.
 */
@QuarkusTest
class AiPricingConfigTest {

  @Inject ThrillhouseConfig config;

  @Test
  void shouldResolveDottedOpenAiKey() {
    var pricing = config.ai().pricing().get("gpt-5.5");
    assertNotNull(pricing, "gpt-5.5 pricing must resolve");
    assertEquals(0.005, pricing.inputPer1k(), 1e-9);
    assertEquals(0.030, pricing.outputPer1k(), 1e-9);
  }

  @Test
  void shouldResolveDottedQwenKey() {
    var pricing = config.ai().pricing().get("qwen3.7-max");
    assertNotNull(pricing, "qwen3.7-max pricing must resolve");
    assertEquals(0.00165, pricing.inputPer1k(), 1e-9);
    assertEquals(0.004951, pricing.outputPer1k(), 1e-9);
  }

  @Test
  void shouldResolveDottedQwenPlusKey() {
    var pricing = config.ai().pricing().get("qwen3.5-plus");
    assertNotNull(pricing, "qwen3.5-plus pricing must resolve");
    assertEquals(0.000115, pricing.inputPer1k(), 1e-9);
    assertEquals(0.000688, pricing.outputPer1k(), 1e-9);
  }

  @Test
  void shouldResolveSlashedGroqKey() {
    var pricing = config.ai().pricing().get("openai/gpt-oss-20b");
    assertNotNull(pricing, "openai/gpt-oss-20b pricing must resolve");
    assertEquals(0.000075, pricing.inputPer1k(), 1e-9);
    assertEquals(0.0003, pricing.outputPer1k(), 1e-9);
  }

  @Test
  void shouldResolvePlainDeepSeekKey() {
    var pricing = config.ai().pricing().get("deepseek-chat");
    assertNotNull(pricing, "deepseek-chat pricing must resolve");
    assertEquals(0.00014, pricing.inputPer1k(), 1e-9);
    assertEquals(0.00028, pricing.outputPer1k(), 1e-9);
  }

  @Test
  void shouldPriceDeepSeekV4FlashAtItsPublishedRate() {
    // $0.14 per 1M input / $0.28 per 1M output, expressed per 1K. Pinned because a wrong factor of
    // ten here is invisible — every session still gets a cost, just the wrong one.
    var pricing = config.ai().pricing().get("deepseek-v4-flash");
    assertNotNull(pricing, "deepseek-v4-flash pricing must resolve");
    assertEquals(0.00014, pricing.inputPer1k(), 1e-9);
    assertEquals(0.00028, pricing.outputPer1k(), 1e-9);
  }

  @Test
  void shouldShipDeepSeekV4FlashCapsThatFitItsSharedContextWindow() {
    // Unlike the other entries in the models map, deepseek-v4-flash ships real values rather than
    // an empty binding stub, so the caps are a shipped default a deployment inherits silently.
    // #562: the provider counts the completion against the same 1048576-token context as the
    // prompt ("2062275 in the messages, 384000 in the completion" against that limit), so the
    // entry must describe a shared window — the shipped 1000000 in + 384000 out marked
    // separate-output-budget could not fit that context under any prompt budget.
    var settings = config.ai().models().get("deepseek-v4-flash");
    assertNotNull(settings, "deepseek-v4-flash model settings must resolve");
    assertEquals(
        Optional.empty(),
        settings.separateOutputBudget(),
        "this model's completion is spent out of its context window, so it must stay on the shared"
            + " contract where the buffer is reserved and the caps are held to context-tokens");
    assertEquals(1_048_576, settings.contextTokens().orElseThrow());
    assertEquals(900_000, settings.maxInputTokens().orElseThrow());
    assertEquals(8_192, settings.maxOutputTokens().orElseThrow());
    assertTrue(
        settings.maxInputTokens().orElseThrow() + settings.maxOutputTokens().orElseThrow()
            <= settings.contextTokens().orElseThrow(),
        "prompt + completion must fit the one context the provider charges them to");
  }

  @Test
  void noShippedModelsCapsExceedItsDeclaredContextWindow() {
    // Sibling of the buffer walk below: a shipped pair that cannot fit its own window refuses
    // every deployment naming that model, and the validator's rule only fires for the ACTIVE one.
    config
        .ai()
        .models()
        .forEach(
            (model, settings) -> {
              if (settings.separateOutputBudget().orElse(false)) {
                return; // its completion is not charged to the window
              }
              settings
                  .contextTokens()
                  .ifPresent(
                      window ->
                          assertTrue(
                              settings.maxInputTokens().orElse(0)
                                      + settings.maxOutputTokens().orElse(0)
                                  <= window,
                              "shipped max-input-tokens + max-output-tokens for '"
                                  + model
                                  + "' exceeds its context-tokens "
                                  + window
                                  + " — on a shared window the provider rejects every call at that"
                                  + " budget, whatever the prompt budgeter does"));
            });
  }

  @Test
  void noShippedModelCapExceedsItsBufferOnASharedWindow() {
    // A shipped max-output-tokens above the effective buffer refuses to boot every deployment
    // naming that model. The validator rule only fires for the ACTIVE model — one of eighteen in
    // the table — so no ordinary test sees it. Walk the table directly instead.
    var reviewBuffer = config.review().outputBufferTokens();

    config
        .ai()
        .models()
        .forEach(
            (model, settings) -> {
              if (settings.separateOutputBudget().orElse(false)) {
                return; // its response draws on a budget of its own; nothing to reserve
              }
              settings
                  .maxOutputTokens()
                  .ifPresent(
                      cap -> {
                        int buffer = settings.outputBufferTokens().orElse(reviewBuffer);
                        assertTrue(
                            cap <= buffer,
                            "shipped max-output-tokens "
                                + cap
                                + " for '"
                                + model
                                + "' exceeds its effective output buffer "
                                + buffer
                                + " — every deployment naming this model would fail to start."
                                + " Either lower the cap or mark it separate-output-budget");
                      });
            });
  }
}
