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
package dev.thiagogonzaga.thrillhousebot.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.NotificationsConfig.OutgoingWebhookConfig;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NotificationSettingsTest {

  private static final String SLACK_URL =
      "https://hooks.slack.com/services/T000/B000/secretTokenPart";

  /** A valid enabled configuration; each test changes the one value it is about. */
  private static final class Config {
    private Optional<String> url = Optional.of(SLACK_URL);
    private String format = "slack";
    private Optional<String> secret = Optional.of("signing-key");
    private List<String> events = List.of("completed", "failed");
    private boolean includeContent = false;
    private boolean allowHttp = false;
    private Duration timeout = Duration.ofSeconds(10);
    private int maxAttempts = 3;

    OutgoingWebhookConfig build() {
      var config = mock(OutgoingWebhookConfig.class);
      lenient().when(config.url()).thenReturn(url);
      lenient().when(config.format()).thenReturn(format);
      lenient().when(config.secret()).thenReturn(secret);
      lenient().when(config.events()).thenReturn(events);
      lenient().when(config.includeContent()).thenReturn(includeContent);
      lenient().when(config.allowHttp()).thenReturn(allowHttp);
      lenient().when(config.timeout()).thenReturn(timeout);
      lenient().when(config.maxAttempts()).thenReturn(maxAttempts);
      return config;
    }
  }

  private static Config config() {
    return new Config();
  }

  @Test
  void noUrlMeansOffAndNothingElseIsChecked() {
    var c = config();
    c.url = Optional.empty();
    c.format = "carrier-pigeon";
    c.maxAttempts = 0;

    assertThat(NotificationSettings.enabled(c.build())).isFalse();
    assertThat(NotificationSettings.problems(c.build())).isEmpty();
    assertThat(NotificationSettings.resolve(c.build())).isEmpty();
  }

  @Test
  void blankUrlMeansOff() {
    var c = config();
    c.url = Optional.of("   ");

    assertThat(NotificationSettings.enabled(c.build())).isFalse();
    assertThat(NotificationSettings.resolve(c.build())).isEmpty();
  }

  @Test
  void validConfigurationResolves() {
    var c = config();
    c.url = Optional.of("  " + SLACK_URL + "  ");
    c.events = List.of(" Completed ");
    c.includeContent = true;

    var settings = NotificationSettings.resolve(c.build()).orElseThrow();

    assertThat(settings.url()).isEqualTo(URI.create(SLACK_URL));
    assertThat(settings.format()).isEqualTo(NotificationFormat.SLACK);
    assertThat(settings.secret()).contains("signing-key");
    assertThat(settings.signed()).isTrue();
    assertThat(settings.events()).containsExactly(ReviewOutcome.Event.COMPLETED);
    assertThat(settings.includeContent()).isTrue();
    assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(settings.maxAttempts()).isEqualTo(3);
  }

  @Test
  void blankSecretMeansUnsigned() {
    var c = config();
    c.secret = Optional.of("  ");

    var settings = NotificationSettings.resolve(c.build()).orElseThrow();

    assertThat(settings.secret()).isEmpty();
    assertThat(settings.signed()).isFalse();
  }

  @Test
  void invalidConfigurationDoesNotResolve() {
    var c = config();
    c.format = "teams";

    assertThat(NotificationSettings.resolve(c.build())).isEmpty();
  }

  @Test
  void plainHttpIsRefusedUnlessAllowed() {
    var c = config();
    c.url = Optional.of("http://localhost:9000/hook/secretTokenPart");

    assertThat(NotificationSettings.problems(c.build()))
        .singleElement()
        .asString()
        .contains("must be an https:// URL", "NOTIFICATIONS_WEBHOOK_ALLOW_HTTP")
        .doesNotContain("secretTokenPart");

    c.allowHttp = true;
    assertThat(NotificationSettings.problems(c.build())).isEmpty();
  }

  @Test
  void otherSchemesAreRefusedEvenWithHttpAllowed() {
    var c = config();
    c.url = Optional.of("ftp://files.example/secretTokenPart");
    c.allowHttp = true;

    assertThat(NotificationSettings.problems(c.build()))
        .singleElement()
        .asString()
        .contains("must be an https:// URL")
        .doesNotContain("secretTokenPart");
  }

  @Test
  void relativeUrlIsRefused() {
    var c = config();
    c.url = Optional.of("/relative/secretTokenPart");

    assertThat(NotificationSettings.problems(c.build()))
        .singleElement()
        .asString()
        .contains("must be an https:// URL");
  }

  @Test
  void unparsableUrlIsRefusedWithoutQuotingIt() {
    var c = config();
    c.url = Optional.of("https://hooks example/secretTokenPart");

    assertThat(NotificationSettings.problems(c.build()))
        .singleElement()
        .asString()
        .contains("is not a valid URL")
        .doesNotContain("secretTokenPart");
  }

  @Test
  void urlWithoutAHostIsRefused() {
    var c = config();
    c.url = Optional.of("https:///secretTokenPart");

    assertThat(NotificationSettings.problems(c.build()))
        .singleElement()
        .asString()
        .contains("must name a host");
  }

  @Test
  void urlWithCredentialsIsRefused() {
    var c = config();
    c.url = Optional.of("https://user:pass@hooks.example/path");

    assertThat(NotificationSettings.problems(c.build()))
        .singleElement()
        .asString()
        .contains("must not carry credentials")
        .doesNotContain("pass@");
  }

  @Test
  void unknownFormatIsRefused() {
    var c = config();
    c.format = "teams";

    assertThat(NotificationSettings.problems(c.build()))
        .singleElement()
        .asString()
        .contains("NOTIFICATIONS_WEBHOOK_FORMAT must be one of json, slack, discord", "teams");
  }

  @Test
  void unknownEventIsRefused() {
    var c = config();
    c.events = List.of("completed", "started");

    assertThat(NotificationSettings.problems(c.build()))
        .singleElement()
        .asString()
        .contains("NOTIFICATIONS_WEBHOOK_EVENTS", "completed,started");
  }

  @Test
  void emptyOrMissingEventsAreRefused() {
    var c = config();
    c.events = List.of();
    assertThat(NotificationSettings.problems(c.build()))
        .singleElement()
        .asString()
        .contains("NOTIFICATIONS_WEBHOOK_EVENTS");

    c.events = null;
    assertThat(NotificationSettings.problems(c.build()))
        .singleElement()
        .asString()
        .contains("NOTIFICATIONS_WEBHOOK_EVENTS");
  }

  @Test
  void timeoutMustBePositiveAndBounded() {
    for (var bad : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(61))) {
      var c = config();
      c.timeout = bad;
      assertThat(NotificationSettings.problems(c.build()))
          .as("timeout %s", bad)
          .singleElement()
          .asString()
          .contains("NOTIFICATIONS_WEBHOOK_TIMEOUT");
    }
    var c = config();
    c.timeout = Duration.ofSeconds(60);
    assertThat(NotificationSettings.problems(c.build())).isEmpty();
  }

  @Test
  void maxAttemptsMustBeBetweenOneAndFive() {
    for (var bad : List.of(0, 6)) {
      var c = config();
      c.maxAttempts = bad;
      assertThat(NotificationSettings.problems(c.build()))
          .as("attempts %s", bad)
          .singleElement()
          .asString()
          .contains("NOTIFICATIONS_WEBHOOK_MAX_ATTEMPTS");
    }
    for (var good : List.of(1, 5)) {
      var c = config();
      c.maxAttempts = good;
      assertThat(NotificationSettings.problems(c.build())).isEmpty();
    }
  }

  @Test
  void everyProblemIsReportedAtOnce() {
    var c = config();
    c.url = Optional.of("http://x.example/");
    c.format = "teams";
    c.events = List.of("nope");
    c.timeout = Duration.ZERO;
    c.maxAttempts = 9;

    assertThat(NotificationSettings.problems(c.build())).hasSize(5);
  }

  @Test
  void redactionKeepsSchemeHostAndPortOnly() {
    assertThat(NotificationSettings.redact(URI.create(SLACK_URL)))
        .isEqualTo("https://hooks.slack.com/…");
    assertThat(NotificationSettings.redact(URI.create("http://localhost:9000/a/b?token=x")))
        .isEqualTo("http://localhost:9000/…");
  }

  @Test
  void toStringNeverPrintsTheSecretOrTheUrlPath() {
    var settings = NotificationSettings.resolve(config().build()).orElseThrow();

    assertThat(settings.redactedUrl()).isEqualTo("https://hooks.slack.com/…");
    assertThat(settings.toString())
        .contains("hooks.slack.com", "signed=true", "SLACK")
        .doesNotContain("secretTokenPart")
        .doesNotContain("signing-key");
  }

  @Test
  void eventsAreCopiedDefensively() {
    var events = new java.util.HashSet<>(Set.of(ReviewOutcome.Event.FAILED));
    var settings =
        new NotificationSettings(
            URI.create(SLACK_URL),
            NotificationFormat.JSON,
            Optional.empty(),
            events,
            false,
            Duration.ofSeconds(1),
            1);
    events.add(ReviewOutcome.Event.COMPLETED);

    assertThat(settings.events()).containsExactly(ReviewOutcome.Event.FAILED);
  }
}
