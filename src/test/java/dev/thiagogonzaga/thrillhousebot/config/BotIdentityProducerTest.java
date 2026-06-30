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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class BotIdentityProducerTest {

  @Test
  void shouldProduceIdentityFromConfiguredBotLogins() {
    var config = mock(ThrillhouseConfig.class);
    var github = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(github);
    when(github.botLogins()).thenReturn(List.of("my-app[bot]"));

    var identity = new BotIdentityProducer().botIdentity(config);

    assertEquals(BotIdentity.from(List.of("my-app[bot]")), identity);
  }

  @Test
  void shouldFallBackToDefaultLoginsWhenNoneConfigured() {
    var config = mock(ThrillhouseConfig.class);
    var github = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(github);
    when(github.botLogins()).thenReturn(List.of());

    var identity = new BotIdentityProducer().botIdentity(config);

    assertEquals(BotIdentity.from(BotIdentity.DEFAULT_LOGINS), identity);
  }
}
