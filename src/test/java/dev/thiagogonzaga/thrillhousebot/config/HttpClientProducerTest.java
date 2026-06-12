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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpClientProducerTest {

  @Test
  void shouldConfigureConnectTimeoutFromConfig() {
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    when(config.httpConnectTimeout()).thenReturn(Duration.ofSeconds(10));

    var client = new HttpClientProducer(config).httpClient();

    assertTrue(client.connectTimeout().isPresent());
    assertEquals(Duration.ofSeconds(10), client.connectTimeout().get());
  }

  @Test
  void shouldHonorCustomConnectTimeout() {
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    when(config.httpConnectTimeout()).thenReturn(Duration.ofSeconds(30));

    var client = new HttpClientProducer(config).httpClient();

    assertEquals(Duration.ofSeconds(30), client.connectTimeout().get());
  }
}
