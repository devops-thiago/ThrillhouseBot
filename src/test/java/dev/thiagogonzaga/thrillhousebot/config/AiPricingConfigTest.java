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
  void shouldShipDeepSeekV4FlashContextWindowWithoutAResponseCap() {
    // Unlike the other entries in the models map, deepseek-v4-flash ships a real input cap rather
    // than an empty binding stub, so it is a default a deployment inherits silently. It ships no
    // max-output-tokens on purpose — see noShippedModelCapCanRefuseToBoot.
    var settings = config.ai().models().get("deepseek-v4-flash");
    assertNotNull(settings, "deepseek-v4-flash model settings must resolve");
    assertEquals(1_000_000, settings.maxInputTokens().orElseThrow());
    assertTrue(
        settings.maxOutputTokens().isEmpty(),
        "shipping a response cap here refuses to boot on the default output buffer");
  }

  @Test
  void noShippedModelCapCanRefuseToBoot() {
    // StartupConfigValidator rejects a configuration whose max-output-tokens exceeds the effective
    // output buffer, so a shipped max-output-tokens above the default buffer takes down every
    // deployment naming that model — with a startup failure, not a degraded review.
    //
    // That rule only fires for the ACTIVE model, which is why no ordinary test sees it: the suite
    // activates one model and the shipped table names eighteen. Walk the table directly instead.
    var reviewBuffer = config.review().outputBufferTokens();

    config
        .ai()
        .models()
        .forEach(
            (model, settings) ->
                settings
                    .maxOutputTokens()
                    .ifPresent(
                        cap -> {
                          // A model may raise its own buffer alongside the cap; that is consistent
                          // and boots. Only the pair as it actually resolves matters.
                          int buffer = settings.outputBufferTokens().orElse(reviewBuffer);
                          assertTrue(
                              cap <= buffer,
                              "shipped max-output-tokens "
                                  + cap
                                  + " for '"
                                  + model
                                  + "' exceeds its effective output buffer "
                                  + buffer
                                  + " — every deployment naming this model would fail to start");
                        }));
  }
}
