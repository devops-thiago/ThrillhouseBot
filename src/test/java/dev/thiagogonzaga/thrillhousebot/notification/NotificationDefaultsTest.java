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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Default profile: outgoing notifications are off, so an untouched deployment sends nothing
 * anywhere new (#73, #45). Asserted through the resolved configuration, so the {@code
 * thrillhousebot.notifications.webhook.*} wiring in {@code application.properties} is covered, and
 * the notifier bean itself is resolved to prove it boots on that configuration.
 */
@QuarkusTest
class NotificationDefaultsTest {

  @Inject ThrillhouseConfig config;

  @Inject ReviewNotifier notifier;

  @Test
  void notificationsAreOffByDefault() {
    var webhook = config.notifications().webhook();

    assertThat(webhook.url()).isEmpty();
    assertThat(NotificationSettings.enabled(webhook)).isFalse();
    assertThat(NotificationSettings.resolve(webhook)).isEmpty();
  }

  @Test
  void theOtherKnobsShipTheDocumentedDefaults() {
    var webhook = config.notifications().webhook();

    assertThat(webhook.format()).isEqualTo("json");
    assertThat(webhook.secret()).isEmpty();
    assertThat(webhook.events()).containsExactly("completed", "failed");
    assertThat(webhook.includeContent()).isFalse();
    assertThat(webhook.allowHttp()).isFalse();
    assertThat(webhook.timeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(webhook.maxAttempts()).isEqualTo(3);
  }

  @Test
  void theNotifierBeanResolves() {
    assertThat(notifier).isNotNull();
  }
}
