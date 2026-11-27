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

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.WithDefault;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the limits a deployment gets when the operator sets none of them. Anyone who can open a pull
 * request on a repository the bot serves starts automatic reviews, so each of these has to ship
 * switched on: a default of {@code 0} would leave the number and the size of the reviews a stranger
 * can start bounded by nothing. Every default is declared twice, on the typed config and in {@code
 * application.properties}, and the two must agree.
 */
class CostLimitDefaultsTest {

  private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
  private static final Pattern ENV_WITH_DEFAULT = Pattern.compile("\\$\\{([A-Z_]+):([^}]*)}");

  @ParameterizedTest
  @CsvSource({
    "thrillhousebot.review.max-tokens-per-review, REVIEW_MAX_TOKENS_PER_REVIEW,"
        + " ReviewConfig, maxTokensPerReview",
    "thrillhousebot.ai.max-concurrent-calls, AI_MAX_CONCURRENT_CALLS,"
        + " AiPricingConfig, maxConcurrentCalls",
    "thrillhousebot.review.auto-review-max-concurrent-per-repo,"
        + " AUTO_REVIEW_MAX_CONCURRENT_PER_REPO, ReviewConfig, autoReviewMaxConcurrentPerRepo",
    "thrillhousebot.review.auto-review-max-queued-per-repo, AUTO_REVIEW_MAX_QUEUED_PER_REPO,"
        + " ReviewConfig, autoReviewMaxQueuedPerRepo"
  })
  void aNumericLimitShipsSwitchedOn(String key, String variable, String group, String method)
      throws IOException, ReflectiveOperationException {
    var shipped = shippedDefault(key, variable);

    assertTrue(Long.parseLong(shipped) > 0, key + " ships as " + shipped + ", which is off");
    assertEquals(typedDefault(group, method), shipped, key);
  }

  @Test
  void theAutomaticReviewIntervalShipsSwitchedOn()
      throws IOException, ReflectiveOperationException {
    var shipped =
        shippedDefault(
            "thrillhousebot.review.auto-review-min-interval", "AUTO_REVIEW_MIN_INTERVAL");

    assertTrue(
        DurationConverter.parseDuration(shipped).toMillis() > 0,
        "the interval ships as " + shipped + ", which is off");
    assertEquals(typedDefault("ReviewConfig", "autoReviewMinInterval"), shipped);
  }

  @Test
  void theShippedSpendCeilingCoversAFullReviewAtTheShippedBudget() throws IOException {
    var properties = properties();
    long input = defaultOf(properties, "thrillhousebot.review.max-input-tokens");
    long output = defaultOf(properties, "thrillhousebot.review.output-buffer-tokens");
    long calls = defaultOf(properties, "thrillhousebot.review.max-ai-calls");
    long ceiling = defaultOf(properties, "thrillhousebot.review.max-tokens-per-review");

    // Every planned call at its full input and output, and a verifier call as large per batch.
    long fullReview = (input + output) * calls + (input + output) * (calls - 1);

    assertTrue(
        ceiling >= fullReview,
        "a full review at the shipped budget spends up to " + fullReview + ", above " + ceiling);
  }

  @Test
  void theShippedCallCeilingCoversOneReviewsFanOut() throws IOException {
    var properties = properties();
    long calls = defaultOf(properties, "thrillhousebot.review.max-ai-calls");
    long ceiling = defaultOf(properties, "thrillhousebot.ai.max-concurrent-calls");

    assertTrue(ceiling >= calls - 1, "one review sends " + (calls - 1) + " batch calls at once");
  }

  private static long defaultOf(Properties properties, String key) {
    var value = properties.getProperty(key);
    assertNotNull(value, key);
    var matcher = ENV_WITH_DEFAULT.matcher(value);
    return Long.parseLong(matcher.matches() ? matcher.group(2) : value);
  }

  private static String shippedDefault(String key, String variable) throws IOException {
    var value = properties().getProperty(key);
    assertNotNull(value, key);
    var matcher = ENV_WITH_DEFAULT.matcher(value);
    assertTrue(matcher.matches(), key + " must read an environment variable: " + value);
    assertEquals(variable, matcher.group(1), key);
    return matcher.group(2);
  }

  private static String typedDefault(String group, String method)
      throws ReflectiveOperationException {
    var type = Class.forName(ThrillhouseConfig.class.getName() + "$" + group);
    return type.getMethod(method).getAnnotation(WithDefault.class).value();
  }

  private static Properties properties() throws IOException {
    var properties = new Properties();
    try (Reader reader = Files.newBufferedReader(PROPERTIES)) {
      properties.load(reader);
    }
    return properties;
  }
}
