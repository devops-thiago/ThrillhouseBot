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

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.WithName;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the Quarkus/SmallRye-documented way to bind per-model env vars onto hyphenated map keys: an
 * empty dotted property in a lower-ordinal source disambiguates the env name, then {@link
 * EnvConfigSource} supplies the value. Hyphen-only keys use the standard underscore env form; keys
 * with {@code .} or {@code /} use the quoted-key {@code __} form.
 *
 * @see <a href="https://quarkus.io/guides/config-reference#environment-variables">Quarkus env
 *     vars</a>
 */
class AiModelsEnvMappingTest {

  private static final String SEED = "thrillhousebot.ai.models.deepseek-v4-pro.max-input-tokens";
  private static final String ENV = "THRILLHOUSEBOT_AI_MODELS_DEEPSEEK_V4_PRO_MAX_INPUT_TOKENS";

  @ConfigMapping(prefix = "thrillhousebot.ai")
  interface AiModelsProbe {
    Map<String, ModelSettings> models();

    interface ModelSettings {
      @WithName("max-input-tokens")
      Optional<Integer> maxInputTokens();
    }
  }

  @Test
  void hyphenatedKeyEnvVarOverridesEmptySeedStub() {
    var config = config(Map.of(SEED, ""), Map.of(ENV, "1000000"));

    var settings = config.getConfigMapping(AiModelsProbe.class).models().get("deepseek-v4-pro");
    assertEquals(1_000_000, settings.maxInputTokens().orElseThrow());
  }

  @Test
  void emptySeedAloneLeavesOptionalAbsent() {
    var config = config(Map.of(SEED, ""), Map.of());

    var settings = config.getConfigMapping(AiModelsProbe.class).models().get("deepseek-v4-pro");
    assertTrue(settings.maxInputTokens().isEmpty());
  }

  @Test
  void envAloneCannotDisambiguateHyphenatedMapKey() {
    // Documents why application.properties must carry the empty dotted stub: without it, SmallRye
    // cannot know DEEPSEEK_V4_PRO means deepseek-v4-pro vs deepseek.v4.pro.
    var config = config(Map.of(), Map.of(ENV, "1000000"));

    assertFalse(
        config.getConfigMapping(AiModelsProbe.class).models().containsKey("deepseek-v4-pro"),
        "env-only must not invent hyphenated keys — seed stub is required");
  }

  @Test
  void dottedModelKeyUsesSameQuotedEnvForm() {
    var seed = "thrillhousebot.ai.models.\"gpt-5.5\".max-input-tokens";
    var env = "THRILLHOUSEBOT_AI_MODELS__GPT_5_5__MAX_INPUT_TOKENS";
    var config = config(Map.of(seed, ""), Map.of(env, "256000"));

    var settings = config.getConfigMapping(AiModelsProbe.class).models().get("gpt-5.5");
    assertEquals(256_000, settings.maxInputTokens().orElseThrow());
  }

  private static SmallRyeConfig config(Map<String, String> properties, Map<String, String> env) {
    return new SmallRyeConfigBuilder()
        .withValidateUnknown(false)
        .withMapping(AiModelsProbe.class)
        .withSources(new PropertiesConfigSource(new HashMap<>(properties), "test-props", 100))
        .withSources(new EnvConfigSource(new HashMap<>(env), 300))
        .build();
  }
}
